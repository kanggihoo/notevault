import { MAX_BLOB_BYTES, type LocalFile, type RemoteEntry, type SyncPlan } from './types'

/**
 * 점(.)으로 시작하는 경로 세그먼트가 있으면 제외 대상이다.
 *
 * .obsidian/ .claude/ .agents/ .codex/ .github/ 등을 걸러낸다. 볼트 실측
 * 기준 153개가 해당하며, 제외하면 대상이 3555 → 3402개로 줄어든다.
 *
 * 세그먼트 시작만 검사하므로 `Dev/v1.2/note.md` 는 통과한다.
 * 설계 문서 6장 참조.
 */
export function isDotPath(path: string): boolean {
  return path.split('/').some((seg) => seg.startsWith('.'))
}

/**
 * 경로가 구독 prefix 하위에 있는지 검사한다.
 *
 * 폴더 경계를 확인하므로 `Dev` 구독이 `Development/x.md` 를 포함하지 않는다.
 */
export function isSubscribed(path: string, subscriptions: Set<string>): boolean {
  if (subscriptions.has('')) return true // 볼트 전체 구독
  const segs = path.split('/')
  // 파일 자신을 뺀 조상 경로만 검사한다 → O(d)
  for (let i = segs.length - 1; i > 0; i--) {
    if (subscriptions.has(segs.slice(0, i).join('/'))) return true
  }
  return false
}

/**
 * 이 프로젝트의 중심 함수. 순수 함수이므로 네트워크·파일시스템과 무관하다.
 *
 * 입력  (원격 트리, 로컬 파일 목록, 구독 목록)
 * 출력  (다운로드 목록, 삭제 목록)
 *
 * 복잡도는 O(N·d + L) 로 선형이다. 설계 문서 6장 참조.
 */
export function planSync(
  remote: RemoteEntry[],
  local: LocalFile[],
  subscriptions: string[],
): SyncPlan {
  const subs = new Set(subscriptions)
  const localMap = new Map(local.map((f) => [f.path, f.blobSha]))

  const download: RemoteEntry[] = []
  // 구독 대상으로 남는 원격 경로. 나머지 로컬 파일은 삭제 대상이 된다.
  const keep = new Set<string>()

  for (const entry of remote) {
    if (isDotPath(entry.path)) continue
    if (!isSubscribed(entry.path, subs)) continue
    // blob API 제한을 넘는 파일은 받을 수 없다.
    if (entry.size > MAX_BLOB_BYTES) continue

    keep.add(entry.path)

    const localSha = localMap.get(entry.path)
    // 로컬에 없음 → 다운로드 / SHA 다름 → 재다운로드 / SHA 같음 → 스킵
    if (localSha !== entry.sha) download.push(entry)
  }

  // 원격에서 사라졌거나 구독이 해제된 로컬 파일을 지운다.
  const del = local.filter((f) => !keep.has(f.path)).map((f) => f.path)

  return { download, delete: del }
}
