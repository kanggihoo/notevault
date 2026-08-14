import { useCallback, useEffect, useMemo, useState } from 'react'
import { ActivityIndicator, Alert } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { FlashList } from '@shopify/flash-list'
import { Check, ChevronDown, ChevronRight, Minus } from 'lucide-react-native'

import { Pressable, Text, View } from '@/tw'
import { getToken } from '@/auth'
import { getDb } from '@/db'
import { deleteFiles, getLocalFiles, getSubscriptions, setSubscriptions } from '@/db/queries'
import { GithubClient } from '@/sync/github'
import { appFetch } from '@/sync/http'
import { createVaultFs } from '@/sync/vaultFs'
import {
  buildRemoteTree,
  formatBytes,
  subscriptionState,
  toggleSubscription,
  type RemoteFolder,
} from '@/tree/remoteTree'
import { useSettings } from '@/store/settings'
import { useSync } from '@/store/sync'

/**
 * 구독 관리. 이 화면에서만 GitHub 원격 트리를 다룬다 (설계 문서 7장).
 * 원격 3555개 항목이므로 FlashList 가상화가 필수다.
 */
type Row = { folder: RemoteFolder }

function flatten(
  nodes: readonly RemoteFolder[],
  expanded: ReadonlySet<string>,
  out: Row[] = [],
): Row[] {
  for (const node of nodes) {
    out.push({ folder: node })
    if (expanded.has(node.path)) flatten(node.children, expanded, out)
  }
  return out
}

export default function SubscriptionsScreen() {
  const insets = useSafeAreaInsets()
  const repo = useSettings((s) => s.repo)
  const status = useSync((s) => s.status)
  const sync = useSync((s) => s.sync)
  const [tree, setTree] = useState<RemoteFolder[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [subs, setSubs] = useState<string[]>([])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [dirty, setDirty] = useState(false)

  useEffect(() => {
    void (async () => {
      try {
        const db = await getDb()
        setSubs(await getSubscriptions(db))

        const token = await getToken()
        if (!token || !repo) {
          setError(!token ? 'GitHub 연결이 필요합니다.' : '저장소를 먼저 지정하세요.')
          return
        }
        const client = new GithubClient(token, appFetch)
        const head = await client.getHeadSha(repo)
        const result = await client.getTree(repo, head)
        setTree(buildRemoteTree(result.entries))
      } catch (e) {
        setError(String(e))
      }
    })()
  }, [repo])

  const rows = useMemo(() => (tree ? flatten(tree, expanded) : []), [tree, expanded])

  const toggleExpand = useCallback((path: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }, [])

  const toggleSub = useCallback(
    async (path: string) => {
      const next = toggleSubscription(subs, path)
      setSubs(next)
      setDirty(true)
      const db = await getDb()
      await setSubscriptions(db, next)

      // 구독 해제로 범위 밖이 된 로컬 파일은 즉시 삭제한다 (설계 문서 7장).
      const wasSubscribed = subs.includes(path) && !next.includes(path)
      if (wasSubscribed) {
        const local = await getLocalFiles(db)
        const prefix = path === '' ? '' : path + '/'
        const doomed = local
          .map((f) => f.path)
          .filter((p) => (prefix === '' ? true : p.startsWith(prefix)))
          // 다른 구독이 여전히 커버하면 남긴다
          .filter((p) => {
            const segs = p.split('/')
            for (let i = segs.length - 1; i > 0; i--) {
              if (next.includes(segs.slice(0, i).join('/'))) return false
            }
            return !next.includes('')
          })
        if (doomed.length > 0) {
          const fs = createVaultFs()
          for (const p of doomed) await fs.deleteFile(p)
          await deleteFiles(db, doomed)
          Alert.alert('구독 해제', `로컬 파일 ${doomed.length}개를 삭제했습니다.`)
        }
      }
    },
    [subs],
  )

  if (error) {
    return (
      <View className="flex-1 items-center justify-center bg-background px-8">
        <Text className="text-center text-meta text-error">{error}</Text>
      </View>
    )
  }
  if (!tree) {
    return (
      <View className="flex-1 items-center justify-center bg-background">
        <ActivityIndicator />
        <Text className="mt-3 text-meta text-muted-foreground">원격 트리를 불러오는 중…</Text>
      </View>
    )
  }

  return (
    <View className="flex-1 bg-background">
      <FlashList
        data={rows}
        keyExtractor={(row) => row.folder.path}
        renderItem={({ item }) => {
          const f = item.folder
          const state = subscriptionState(subs, f.path)
          return (
            <View
              className="h-14 flex-row items-center gap-2 pr-4"
              style={{ paddingLeft: 8 + f.depth * 16 }}
            >
              <Pressable onPress={() => toggleExpand(f.path)} hitSlop={6} className="p-1">
                {f.children.length > 0 ? (
                  expanded.has(f.path) ? (
                    <ChevronDown size={16} color="#999999" />
                  ) : (
                    <ChevronRight size={16} color="#999999" />
                  )
                ) : (
                  <View style={{ width: 16 }} />
                )}
              </Pressable>

              <Pressable onPress={() => void toggleSub(f.path)} className="flex-1 flex-row items-center gap-3">
                <View
                  className={`h-5 w-5 items-center justify-center rounded-md border ${
                    state === 'checked' || state === 'inherited'
                      ? 'border-primary bg-primary'
                      : state === 'partial'
                        ? 'border-primary'
                        : 'border-border'
                  } ${state === 'inherited' ? 'opacity-50' : ''}`}
                >
                  {(state === 'checked' || state === 'inherited') && (
                    <Check size={13} color="#ffffff" strokeWidth={3} />
                  )}
                  {state === 'partial' && <Minus size={13} color="#7c3aed" strokeWidth={3} />}
                </View>

                <View className="flex-1">
                  <Text className="text-row text-foreground" numberOfLines={1}>
                    {f.name}
                  </Text>
                  <Text className="text-meta text-muted-foreground">
                    노트 {f.noteCount} · 이미지 {f.imageCount} · {formatBytes(f.totalBytes)}
                  </Text>
                </View>
              </Pressable>
            </View>
          )
        }}
      />
      {/* 하단 동기화 바 — 체크한 자리에서 바로 받는다. edge-to-edge 인셋 필요 */}
      <View
        className="border-t border-border px-4 py-3"
        style={{ paddingBottom: 12 + insets.bottom }}
      >
        {status.kind === 'downloading' ? (
          <View className="flex-row items-center gap-3">
            <ActivityIndicator size="small" />
            <View className="flex-1">
              <Text className="text-row text-foreground">
                {status.done} / {status.total}
              </Text>
              <Text className="text-meta text-muted-foreground" numberOfLines={1}>
                {status.currentPath}
              </Text>
            </View>
          </View>
        ) : status.kind === 'checking' ? (
          <View className="flex-row items-center gap-3">
            <ActivityIndicator size="small" />
            <Text className="text-row text-foreground">확인 중…</Text>
          </View>
        ) : (
          <View className="flex-row items-center gap-3">
            <Text className="flex-1 text-meta text-muted-foreground">
              {status.kind === 'done'
                ? `완료 — 새로 ${status.downloaded} · 삭제 ${status.deleted}`
                : status.kind === 'partial'
                  ? `${status.failedCount}개 실패 — 다시 시도하세요`
                  : status.kind === 'up-to-date'
                    ? '이미 최신입니다'
                    : dirty
                      ? '변경된 구독이 있습니다'
                      : '구독을 체크하면 여기서 바로 받습니다'}
            </Text>
            <Pressable
              onPress={() => {
                if (repo) {
                  setDirty(false)
                  void sync(repo, { manual: true })
                }
              }}
              disabled={!repo}
              className={`h-10 items-center justify-center rounded-md px-5 ${dirty ? 'bg-primary active:bg-primary-pressed' : 'bg-muted active:opacity-70'}`}
            >
              <Text
                className={`text-row font-semibold ${dirty ? 'text-primary-foreground' : 'text-foreground'}`}
              >
                동기화
              </Text>
            </Pressable>
          </View>
        )}
      </View>
    </View>
  )
}
