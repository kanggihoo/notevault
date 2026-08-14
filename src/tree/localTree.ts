/**
 * 로컬 파일 경로 목록 → 사이드바 트리. 순수 함수.
 *
 * 사이드바는 로컬에 받은 파일만 표시한다 (설계 문서 7장) — 원격 트리는
 * 구독 관리 화면(remoteTree.ts)의 몫이다.
 */

export type TreeNode = {
  name: string
  path: string
  children?: TreeNode[] // 있으면 폴더
}

const collator = new Intl.Collator('ko')

/** 폴더 먼저, 그다음 이름순. Obsidian 탐색기와 같은 규칙. */
function compare(a: TreeNode, b: TreeNode): number {
  const aFolder = a.children != null
  const bFolder = b.children != null
  if (aFolder !== bFolder) return aFolder ? -1 : 1
  return collator.compare(a.name, b.name)
}

export function buildLocalTree(paths: readonly string[]): TreeNode[] {
  type Dir = { node: TreeNode; dirs: Map<string, Dir> }
  const root: Dir = { node: { name: '', path: '', children: [] }, dirs: new Map() }

  for (const path of paths) {
    const segments = path.split('/')
    let current = root
    for (let i = 0; i < segments.length - 1; i++) {
      const name = segments[i]
      let next = current.dirs.get(name)
      if (!next) {
        const node: TreeNode = {
          name,
          path: segments.slice(0, i + 1).join('/'),
          children: [],
        }
        current.node.children!.push(node)
        next = { node, dirs: new Map() }
        current.dirs.set(name, next)
      }
      current = next
    }
    current.node.children!.push({ name: segments[segments.length - 1], path })
  }

  const sortAll = (nodes: TreeNode[]): void => {
    nodes.sort(compare)
    for (const n of nodes) if (n.children) sortAll(n.children)
  }
  sortAll(root.node.children!)
  return root.node.children!
}

/** 파일명 검색 필터 — 매치한 파일과 그 조상 폴더만 남긴다. */
export function filterTree(nodes: readonly TreeNode[], query: string): TreeNode[] {
  const q = query.trim().toLowerCase()
  if (!q) return [...nodes]
  const result: TreeNode[] = []
  for (const node of nodes) {
    if (node.children) {
      const kept = filterTree(node.children, q)
      if (kept.length > 0) result.push({ ...node, children: kept })
    } else if (node.name.toLowerCase().includes(q)) {
      result.push(node)
    }
  }
  return result
}
