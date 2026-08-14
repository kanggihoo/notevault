import { GithubError, type GithubClient, type RepoRef } from './github'
import { planSync } from './planSync'
import { runPool, type RetryDecision } from './pool'
import type { LocalFile, RemoteEntry } from './types'

/**
 * 동기화 실행. 설계 문서 6장 알고리즘의 IO 부분이다.
 *
 * 계산(planSync)과 실행을 분리했으므로 이 모듈은 순서와 오류 처리만 안다.
 * DB·파일시스템은 인터페이스로 주입받는다 — 테스트는 메모리 구현을 쓴다.
 */

/** runSync 가 필요로 하는 저장소 연산. src/db/queries 가 실제 구현이다. */
export interface SyncStore {
  getLocalFiles(): Promise<LocalFile[]>
  getSubscriptions(): Promise<string[]>
  getLastCommitSha(): Promise<string | null>
  setLastCommitSha(sha: string): Promise<void>
  setLastSyncedAt(ms: number): Promise<void>
  /** 파일 하나 완료마다 즉시 기록 — 중단 복구의 근거. */
  upsertFile(file: { path: string; blobSha: string; size: number }): Promise<void>
  /** 파일 삭제와 함께 북마크·최근도 정리한다. */
  deleteFiles(paths: string[]): Promise<void>
}

export interface SyncFs {
  writeFileAtomic(relPath: string, data: Uint8Array): Promise<void>
  deleteFile(relPath: string): Promise<void>
}

export type SyncProgress =
  | { phase: 'checking' }
  | { phase: 'planning' }
  | { phase: 'downloading'; done: number; total: number; currentPath: string }
  | { phase: 'deleting' }

export type SyncOutcome =
  | { kind: 'up-to-date' }
  | {
      kind: 'synced'
      downloaded: number
      deleted: number
      /** 남은 실패. 있으면 last_commit_sha 를 올리지 않았다 — 다음 동기화가 이어받는다. */
      failed: { path: string; error: unknown }[]
      cancelled: boolean
    }

export type RunSyncOptions = {
  client: GithubClient
  ref: RepoRef
  store: SyncStore
  fs: SyncFs
  concurrency?: number
  maxConcurrency?: number
  isCancelled?: () => boolean
  onProgress?: (progress: SyncProgress) => void
  onConcurrencyChange?: (n: number) => void
  /** 테스트용 sleep 주입. */
  sleep?: (ms: number) => Promise<void>
}

/** 설계 문서 9장 표에 따른 재시도 분류. */
export function classifyForRetry(error: unknown): RetryDecision {
  if (error instanceof GithubError) {
    if (error.kind === 'rate-limit') {
      return { retry: true, backoffMs: (error.retryAfter ?? 2) * 1000 }
    }
    if (error.kind === 'network' || error.kind === 'server') {
      return { retry: true, backoffMs: 1000 }
    }
    // auth·not-found 는 재시도해도 소용없다 — 즉시 실패로.
    return { retry: false }
  }
  // 파일시스템 오류(저장공간 부족 등)는 재시도 대상이 아니다.
  return { retry: false }
}

export async function runSync(options: RunSyncOptions): Promise<SyncOutcome> {
  const { client, ref, store, fs } = options

  // 1) HEAD 확인 — 변경 없으면 즉시 종료 (1초 내 목표)
  options.onProgress?.({ phase: 'checking' })
  const headSha = await client.getHeadSha(ref)
  if (headSha === (await store.getLastCommitSha())) {
    await store.setLastSyncedAt(Date.now())
    return { kind: 'up-to-date' }
  }

  // 2) 트리 1회 호출 → 3·4) 순수 계산
  options.onProgress?.({ phase: 'planning' })
  const tree = await client.getTree(ref, headSha)
  // truncated (10만 항목 초과) 는 실측 3555개 기준 발생하지 않는다.
  // 발생하면 폴더별 조회 폴백이 필요하다 — v1 은 명시적으로 실패시킨다.
  if (tree.truncated) {
    throw new GithubError('server', 'tree truncated — folder-by-folder fallback not implemented')
  }

  const [local, subscriptions] = await Promise.all([
    store.getLocalFiles(),
    store.getSubscriptions(),
  ])
  const plan = planSync(tree.entries, local, subscriptions)

  // 5) 다운로드 — 적응형 동시성, 임시 파일 → rename, 완료 즉시 기록
  const pool = await runPool<RemoteEntry>(
    plan.download,
    async (entry) => {
      const data = await client.getRawFile(ref, entry.path, headSha)
      await fs.writeFileAtomic(entry.path, data)
      await store.upsertFile({ path: entry.path, blobSha: entry.sha, size: entry.size })
    },
    {
      initialConcurrency: options.concurrency ?? 5,
      maxConcurrency: options.maxConcurrency ?? 10,
      classify: classifyForRetry,
      isCancelled: options.isCancelled,
      onConcurrencyChange: options.onConcurrencyChange,
      sleep: options.sleep,
      onProgress: (done, total, entry) =>
        options.onProgress?.({ phase: 'downloading', done, total, currentPath: entry.path }),
    },
  )

  // 6) 삭제 → 전부 성공한 경우에만 완료 마커 갱신
  let deleted = 0
  if (!pool.cancelled) {
    options.onProgress?.({ phase: 'deleting' })
    for (const path of plan.delete) {
      await fs.deleteFile(path)
      deleted += 1
    }
    await store.deleteFiles(plan.delete)

    if (pool.failed.length === 0) {
      // "이 커밋까지 완전히 동기화됨". 부분 실패면 갱신하지 않는다 —
      // 다음 동기화가 SHA 불일치를 감지하고, 받은 파일은 blob SHA 로 스킵된다.
      await store.setLastCommitSha(headSha)
    }
  }
  await store.setLastSyncedAt(Date.now())

  return {
    kind: 'synced',
    downloaded: pool.done,
    deleted,
    failed: pool.failed.map((f) => ({ path: f.item.path, error: f.error })),
    cancelled: pool.cancelled,
  }
}
