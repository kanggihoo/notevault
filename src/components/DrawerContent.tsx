import { useCallback, useEffect, useMemo, useState } from 'react'
import { FlatList } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { router } from 'expo-router'
import {
  ChevronDown,
  ChevronRight,
  FileText,
  History,
  RefreshCw,
  Search,
  Settings,
  Star,
  X,
} from 'lucide-react-native'

import { Pressable, Text, TextInput, View } from '@/tw'
import { getDb } from '@/db'
import { getBookmarks, getLocalFiles, getRecents } from '@/db/queries'
import { buildLocalTree, filterTree, type TreeNode } from '@/tree/localTree'
import { useSettings } from '@/store/settings'
import { useSync } from '@/store/sync'

/** 트리를 펼침 상태에 따라 평탄화 — FlatList 한 개로 그린다. */
type Row = { node: TreeNode; depth: number }

function flatten(
  nodes: readonly TreeNode[],
  expanded: ReadonlySet<string>,
  depth = 0,
  out: Row[] = [],
): Row[] {
  for (const node of nodes) {
    out.push({ node, depth })
    if (node.children && expanded.has(node.path)) {
      flatten(node.children, expanded, depth + 1, out)
    }
  }
  return out
}

const ICON_MUTED = '#999999'

export function DrawerContent() {
  // Android edge-to-edge: 시스템 내비게이션 바가 컨텐츠 위에 겹치므로
  // 하단 버튼(동기화·설정)이 인셋만큼 올라와야 눌린다.
  const insets = useSafeAreaInsets()
  const [paths, setPaths] = useState<string[]>([])
  const [bookmarks, setBookmarks] = useState<string[]>([])
  const [recents, setRecents] = useState<string[]>([])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [query, setQuery] = useState('')
  const [section, setSection] = useState<'tree' | 'bookmarks' | 'recents'>('tree')

  const filesVersion = useSync((s) => s.filesVersion)
  const status = useSync((s) => s.status)
  const sync = useSync((s) => s.sync)
  const repo = useSettings((s) => s.repo)

  useEffect(() => {
    let alive = true
    ;(async () => {
      const db = await getDb()
      const [files, marks, recent] = await Promise.all([
        getLocalFiles(db),
        getBookmarks(db),
        getRecents(db),
      ])
      if (!alive) return
      setPaths(files.map((f) => f.path))
      setBookmarks(marks)
      setRecents(recent)
    })()
    return () => {
      alive = false
    }
  }, [filesVersion])

  const tree = useMemo(() => buildLocalTree(paths), [paths])
  const visible = useMemo(() => {
    // 검색 중에는 매치 경로가 전부 보이도록 필터 결과를 그대로 편다.
    if (query.trim()) {
      const filtered = filterTree(tree, query)
      const allPaths = new Set<string>()
      const collect = (nodes: readonly TreeNode[]) => {
        for (const n of nodes) {
          if (n.children) {
            allPaths.add(n.path)
            collect(n.children)
          }
        }
      }
      collect(filtered)
      return flatten(filtered, allPaths)
    }
    return flatten(tree, expanded)
  }, [tree, expanded, query])

  const toggle = useCallback((path: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }, [])

  const openNote = useCallback((path: string) => {
    router.push({ pathname: '/note/[...path]', params: { path: path.split('/') } })
  }, [])

  const syncing = status.kind === 'checking' || status.kind === 'downloading'

  const listData =
    section === 'tree'
      ? visible
      : (section === 'bookmarks' ? bookmarks : recents).map<Row>((path) => ({
          node: { name: path.split('/').pop()!, path },
          depth: 0,
        }))

  return (
    <View className="flex-1 bg-card pt-12">
      {/* 검색 */}
      <View className="mx-3 mb-2 flex-row items-center gap-2 rounded-md bg-muted px-3">
        <Search size={16} color={ICON_MUTED} />
        <TextInput
          className="h-10 flex-1 text-row text-foreground"
          placeholder="파일명 검색"
          placeholderTextColor="#767676"
          value={query}
          onChangeText={setQuery}
        />
        {query.length > 0 && (
          <Pressable onPress={() => setQuery('')} hitSlop={8}>
            <X size={16} color={ICON_MUTED} />
          </Pressable>
        )}
      </View>

      {/* 북마크·최근 토글 */}
      <View className="mx-3 mb-2 flex-row gap-2">
        <Pressable
          onPress={() => setSection(section === 'bookmarks' ? 'tree' : 'bookmarks')}
          className={`flex-row items-center gap-1 rounded-full px-3 py-1 ${section === 'bookmarks' ? 'bg-primary-tint' : 'bg-muted'}`}
        >
          <Star size={13} color={section === 'bookmarks' ? '#a78bfa' : ICON_MUTED} />
          <Text className="text-meta text-muted-foreground">북마크</Text>
        </Pressable>
        <Pressable
          onPress={() => setSection(section === 'recents' ? 'tree' : 'recents')}
          className={`flex-row items-center gap-1 rounded-full px-3 py-1 ${section === 'recents' ? 'bg-primary-tint' : 'bg-muted'}`}
        >
          <History size={13} color={section === 'recents' ? '#a78bfa' : ICON_MUTED} />
          <Text className="text-meta text-muted-foreground">최근</Text>
        </Pressable>
      </View>

      {/* 트리 (FlatList — 로컬 트리는 수백 개 수준이라 FlashList 불필요) */}
      <FlatList
        data={listData}
        keyExtractor={(row) => row.node.path}
        ListEmptyComponent={
          <Text className="px-4 py-8 text-center text-meta text-subtle-foreground">
            {section === 'tree'
              ? paths.length === 0
                ? '받아둔 노트가 없습니다.\n설정에서 GitHub 를 연결하고 구독을 추가하세요.'
                : '검색 결과가 없습니다'
              : '비어 있습니다'}
          </Text>
        }
        renderItem={({ item }) => {
          const isFolder = item.node.children != null
          return (
            <Pressable
              onPress={() => (isFolder ? toggle(item.node.path) : openNote(item.node.path))}
              className="h-10 flex-row items-center gap-1.5 pr-3 active:bg-muted"
              style={{ paddingLeft: 12 + item.depth * 16 }}
            >
              {isFolder ? (
                expanded.has(item.node.path) || query.trim() ? (
                  <ChevronDown size={15} color={ICON_MUTED} />
                ) : (
                  <ChevronRight size={15} color={ICON_MUTED} />
                )
              ) : (
                <FileText size={14} color={ICON_MUTED} />
              )}
              <Text className="flex-1 text-row text-foreground" numberOfLines={1}>
                {item.node.name.replace(/\.md$/i, '')}
              </Text>
            </Pressable>
          )
        }}
      />

      {/* 하단: 동기화 + 설정 */}
      <View
        className="border-t border-border px-3 py-2"
        style={{ paddingBottom: 8 + insets.bottom }}
      >
        <Pressable
          onPress={() => repo && sync(repo, { manual: true })}
          disabled={syncing || !repo}
          className="h-10 flex-row items-center gap-2 active:bg-muted"
        >
          <RefreshCw size={16} color={syncing ? '#a78bfa' : ICON_MUTED} />
          <Text className="flex-1 text-row text-foreground">
            {status.kind === 'downloading'
              ? `${status.done} / ${status.total}`
              : status.kind === 'checking'
                ? '확인 중…'
                : status.kind === 'up-to-date'
                  ? '이미 최신입니다'
                  : status.kind === 'done'
                    ? `새로 ${status.downloaded} · 삭제 ${status.deleted}`
                    : status.kind === 'partial'
                      ? `${status.failedCount}개 실패 — 다시 시도`
                      : status.kind === 'offline'
                        ? '오프라인 상태입니다'
                        : status.kind === 'auth-required'
                          ? 'GitHub 재연결 필요'
                          : '동기화'}
          </Text>
        </Pressable>
        <Pressable
          onPress={() => router.push('/settings')}
          className="h-10 flex-row items-center gap-2 active:bg-muted"
        >
          <Settings size={16} color={ICON_MUTED} />
          <Text className="text-row text-foreground">설정</Text>
        </Pressable>
      </View>
    </View>
  )
}
