import { useEffect, useMemo, useRef, useState } from 'react'
import { ActivityIndicator, ToastAndroid, useColorScheme } from 'react-native'
import { WebView, type WebViewMessageEvent } from 'react-native-webview'
import { Stack, router, useLocalSearchParams } from 'expo-router'
import * as Clipboard from 'expo-clipboard'
import * as WebBrowser from 'expo-web-browser'
import { Star } from 'lucide-react-native'

import { Pressable, Text, View } from '@/tw'
import { getDb } from '@/db'
import { getBookmarks, hasFile, toggleBookmark, touchRecent } from '@/db/queries'
import { loadRendererHtml } from '@/render/rendererAsset'
import { createVaultFs } from '@/sync/vaultFs'

/**
 * 노트 뷰어. 설계 문서 8장.
 *
 * - .md: 렌더러 HTML(단일 WebView) + postMessage 로 원문 전달
 * - .html: 원본 CSS 유지, 우리 테마를 주입하지 않는 별도 로드 (design.md 8장)
 *
 * base URL 은 볼트 루트로 고정 — 노트 전환 시 WebView 를 리로드하지 않고
 * 상대 경로는 렌더러가 noteDir 로 해석한다.
 */
export default function NoteScreen() {
  const params = useLocalSearchParams<{ path: string[] | string }>()
  const relPath = useMemo(
    () => (Array.isArray(params.path) ? params.path.join('/') : (params.path ?? '')),
    [params.path],
  )

  const dark = useColorScheme() === 'dark'
  const webviewRef = useRef<WebView>(null)
  const [html, setHtml] = useState<string | null>(null)
  const [rendererReady, setRendererReady] = useState(false)
  const [bookmarked, setBookmarked] = useState(false)
  const [missing, setMissing] = useState(false)

  const vaultFs = useMemo(() => createVaultFs(), [])
  const isHtmlFile = /\.html?$/i.test(relPath)
  const noteDir = relPath.includes('/') ? relPath.slice(0, relPath.lastIndexOf('/')) : ''
  const title = relPath.split('/').pop()?.replace(/\.md$/i, '') ?? ''

  // 렌더러 HTML 로드 (1회, 캐시됨)
  useEffect(() => {
    if (!isHtmlFile) void loadRendererHtml().then(setHtml)
  }, [isHtmlFile])

  // 노트 원문을 렌더러로 전달
  useEffect(() => {
    let alive = true
    ;(async () => {
      if (isHtmlFile || !rendererReady) return
      if (!vaultFs.exists(relPath)) {
        setMissing(true)
        return
      }
      const markdown = await vaultFs.readTextFile(relPath)
      if (!alive) return
      webviewRef.current?.postMessage(
        JSON.stringify({ type: 'render', markdown, noteDir, theme: dark ? 'dark' : 'light' }),
      )
      const db = await getDb()
      await touchRecent(db, relPath)
      const marks = await getBookmarks(db)
      if (alive) setBookmarked(marks.includes(relPath))
    })()
    return () => {
      alive = false
    }
  }, [relPath, rendererReady, dark, isHtmlFile, noteDir, vaultFs])

  async function onMessage(event: WebViewMessageEvent) {
    try {
      const message = JSON.parse(event.nativeEvent.data) as
        | { type: 'ready' }
        | { type: 'wikilink'; target: string }
        | { type: 'external'; href: string }
        | { type: 'copy'; text: string }
        | { type: 'error'; message: string }

      if (message.type === 'ready') {
        setRendererReady(true)
      } else if (message.type === 'external') {
        // 외부 링크는 시스템 브라우저로 (설계 문서 8장)
        void WebBrowser.openBrowserAsync(message.href)
      } else if (message.type === 'copy') {
        await Clipboard.setStringAsync(message.text)
        ToastAndroid.show('복사됨', ToastAndroid.SHORT)
      } else if (message.type === 'wikilink') {
        await openWikilink(message.target)
      }
    } catch {
      /* 렌더러가 아닌 메시지는 무시 */
    }
  }

  /** 위키링크 해석: 탭 시점에 로컬 존재를 확인한다 (렌더 시점 조회 없음). */
  async function openWikilink(target: string) {
    const db = await getDb()
    // Obsidian 관례: 확장자 없으면 .md. 노트 폴더 상대 → 볼트 전역 순서로 찾는다.
    const name = /\.\w+$/.test(target) ? target : `${target}.md`
    const candidates = [noteDir ? `${noteDir}/${name}` : name, name]
    for (const candidate of candidates) {
      if (await hasFile(db, candidate)) {
        router.push({ pathname: '/note/[...path]', params: { path: candidate.split('/') } })
        return
      }
    }
    // 전역 파일명 매치 (Obsidian 은 볼트 전역에서 이름으로 찾는다)
    const rows = await db.getAllAsync<{ path: string }>(
      "SELECT path FROM files WHERE path LIKE '%' || ? LIMIT 1",
      `/${name}`,
    )
    if (rows[0]) {
      router.push({ pathname: '/note/[...path]', params: { path: rows[0].path.split('/') } })
    } else {
      setMissing(false)
      alertNotDownloaded(target)
    }
  }

  async function onToggleBookmark() {
    const db = await getDb()
    setBookmarked(await toggleBookmark(db, relPath))
  }

  if (missing) {
    return (
      <View className="flex-1 items-center justify-center gap-3 bg-background px-8">
        <Text className="text-h3 font-semibold text-foreground">아직 받지 않은 노트입니다</Text>
        <Text className="text-center text-meta text-muted-foreground">
          이 노트가 포함된 폴더를 구독한 뒤 동기화하세요.
        </Text>
      </View>
    )
  }

  return (
    <>
      <Stack.Screen options={{ title }} />
      <View className="flex-1 bg-background">
        <View className="h-11 flex-row items-center justify-end border-b border-border px-3">
          <Text className="flex-1 text-row font-semibold text-foreground" numberOfLines={1}>
            {title}
          </Text>
          <Pressable onPress={onToggleBookmark} hitSlop={10}>
            <Star size={18} color={bookmarked ? '#a78bfa' : '#999999'} />
          </Pressable>
        </View>

        {isHtmlFile ? (
          // .html 은 원본 그대로 — 렌더러도 테마도 없다. 배경은 흰색 고정.
          <WebView
            source={{ uri: `${vaultFs.vaultRootUri()}/${relPath}` }}
            originWhitelist={['*']}
            allowFileAccess
            allowFileAccessFromFileURLs
            allowUniversalAccessFromFileURLs
            forceDarkOn={false}
            style={{ backgroundColor: '#ffffff' }}
          />
        ) : html ? (
          <WebView
            ref={webviewRef}
            source={{ html, baseUrl: vaultFs.vaultRootUri() + '/' }}
            originWhitelist={['*']}
            allowFileAccess
            allowFileAccessFromFileURLs
            allowUniversalAccessFromFileURLs
            // 테마는 CSS 변수로 직접 관리한다 — 자동 다크닝은 이중 변환 (design.md 8장)
            forceDarkOn={false}
            onMessage={onMessage}
            style={{ backgroundColor: 'transparent' }}
          />
        ) : (
          <View className="flex-1 items-center justify-center">
            <ActivityIndicator />
          </View>
        )}
      </View>
    </>
  )
}

function alertNotDownloaded(target: string) {
  // RN Alert 는 시트 대신 간단한 다이얼로그로 v1 처리
  const { Alert } = require('react-native') as typeof import('react-native')
  Alert.alert('아직 받지 않은 노트입니다', `"${target}" 이(가) 로컬에 없습니다.`)
}
