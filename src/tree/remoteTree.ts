import type { RemoteEntry } from '../sync/types'
import { isDotPath } from '../sync/planSync'

/**
 * 원격 트리 → 구독 관리 화면 데이터. 순수 함수.
 *
 * 각 폴더에 노트 수·이미지 수·용량을 집계한다 — Git Tree API 응답의 size
 * 만으로 계산하므로 추가 요청이 없다 (설계 문서 6장 "구독 전 용량 표시").
 */

export type RemoteFolder = {
  name: string
  path: string
  depth: number
  noteCount: number
  imageCount: number
  totalBytes: number
  children: RemoteFolder[]
}

const IMAGE_EXT = /\.(png|jpe?g|gif|svg|webp|avif)$/i

export function buildRemoteTree(entries: readonly RemoteEntry[]): RemoteFolder[] {
  type Dir = { node: RemoteFolder; dirs: Map<string, Dir> }
  const mkNode = (name: string, path: string, depth: number): RemoteFolder => ({
    name,
    path,
    depth,
    noteCount: 0,
    imageCount: 0,
    totalBytes: 0,
    children: [],
  })
  const root: Dir = { node: mkNode('', '', -1), dirs: new Map() }

  for (const entry of entries) {
    if (isDotPath(entry.path)) continue
    const segments = entry.path.split('/')
    const isNote = /\.md$/i.test(entry.path)
    const isImage = IMAGE_EXT.test(entry.path)

    // 파일이 속한 모든 조상 폴더에 집계를 더한다.
    let current = root
    current.node.totalBytes += entry.size
    if (isNote) current.node.noteCount += 1
    if (isImage) current.node.imageCount += 1

    for (let i = 0; i < segments.length - 1; i++) {
      const name = segments[i]
      let next = current.dirs.get(name)
      if (!next) {
        const node = mkNode(name, segments.slice(0, i + 1).join('/'), i)
        current.node.children.push(node)
        next = { node, dirs: new Map() }
        current.dirs.set(name, next)
      }
      current = next
      current.node.totalBytes += entry.size
      if (isNote) current.node.noteCount += 1
      if (isImage) current.node.imageCount += 1
    }
  }

  const collator = new Intl.Collator('ko')
  const sortAll = (nodes: RemoteFolder[]): void => {
    nodes.sort((a, b) => collator.compare(a.name, b.name))
    for (const n of nodes) sortAll(n.children)
  }
  sortAll(root.node.children)
  return root.node.children
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)}GB`
}

/**
 * 구독 토글 규칙. 설계 문서 10.1:
 * - 부모를 구독하면 그 하위의 개별 구독은 중복이므로 정리한다.
 * - 부모 구독 해제는 그 경로만 제거한다.
 */
export function toggleSubscription(current: readonly string[], path: string): string[] {
  const set = new Set(current)
  if (set.has(path)) {
    set.delete(path)
    return [...set]
  }
  // 새 구독의 하위에 있는 기존 구독을 정리한다.
  for (const existing of [...set]) {
    if (existing !== path && (existing.startsWith(path + '/') || path === '')) {
      set.delete(existing)
    }
  }
  set.add(path)
  return [...set]
}

/** 폴더의 구독 상태. 화면 체크박스 표시용. */
export function subscriptionState(
  subscriptions: readonly string[],
  path: string,
): 'checked' | 'inherited' | 'partial' | 'none' {
  const set = new Set(subscriptions)
  if (set.has(path)) return 'checked'
  // 조상이 구독됨 → 상속
  const segments = path.split('/')
  for (let i = segments.length - 1; i > 0; i--) {
    if (set.has(segments.slice(0, i).join('/'))) return 'inherited'
  }
  if (set.has('')) return 'inherited'
  // 자손 중 구독이 있으면 부분
  for (const s of set) {
    if (s.startsWith(path + '/')) return 'partial'
  }
  return 'none'
}
