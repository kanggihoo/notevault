import { GithubClient } from './github'
import { runSync, type SyncFs, type SyncStore } from './runSync'
import type { LocalFile } from './types'

/** 메모리 저장소 — SQLite 없이 runSync 의 순서·오류 처리를 검증한다. */
function memoryStore(initial: {
  files?: LocalFile[]
  subscriptions?: string[]
  lastCommitSha?: string | null
}) {
  const files = new Map(initial.files?.map((f) => [f.path, f.blobSha]) ?? [])
  let lastCommitSha = initial.lastCommitSha ?? null
  const store: SyncStore = {
    getLocalFiles: async () => [...files].map(([path, blobSha]) => ({ path, blobSha })),
    getSubscriptions: async () => initial.subscriptions ?? [''],
    getLastCommitSha: async () => lastCommitSha,
    setLastCommitSha: async (sha) => {
      lastCommitSha = sha
    },
    setLastSyncedAt: async () => {},
    upsertFile: async (f) => {
      files.set(f.path, f.blobSha)
    },
    deleteFiles: async (paths) => {
      for (const p of paths) files.delete(p)
    },
  }
  return { store, files, getLastCommitSha: () => lastCommitSha }
}

function memoryFs() {
  const disk = new Map<string, Uint8Array>()
  const fs: SyncFs = {
    writeFileAtomic: async (path, data) => {
      disk.set(path, data)
    },
    deleteFile: async (path) => {
      disk.delete(path)
    },
  }
  return { fs, disk }
}

/** URL 패턴에 따라 응답을 돌려주는 GitHub mock. */
function mockClient(handlers: {
  head: string
  tree?: { path: string; sha: string; size?: number; type?: string }[]
  fileError?: (path: string) => Error | null
}) {
  const fetchMock = async (url: string): Promise<Response> => {
    if (url.includes('/commits/')) {
      return new Response(JSON.stringify({ sha: handlers.head }), { status: 200 })
    }
    if (url.includes('/git/trees/')) {
      return new Response(
        JSON.stringify({
          truncated: false,
          tree: (handlers.tree ?? []).map((t) => ({ type: 'blob', size: 10, ...t })),
        }),
        { status: 200 },
      )
    }
    if (url.includes('/contents/')) {
      const path = decodeURIComponent(url.split('/contents/')[1].split('?')[0])
      const error = handlers.fileError?.(path)
      if (error) throw error
      return new Response(new TextEncoder().encode(`content:${path}`), { status: 200 })
    }
    throw new Error(`unexpected url: ${url}`)
  }
  return new GithubClient('token', fetchMock as unknown as typeof fetch)
}

const noSleep = async () => {}

describe('runSync', () => {
  it('HEAD 가 같으면 트리 조회 없이 즉시 종료한다', async () => {
    const { store } = memoryStore({ lastCommitSha: 'head1' })
    const { fs } = memoryFs()
    const outcome = await runSync({
      client: mockClient({ head: 'head1' }),
      ref: { owner: 'o', repo: 'r', branch: 'main' },
      store,
      fs,
      sleep: noSleep,
    })
    expect(outcome.kind).toBe('up-to-date')
  })

  it('신규 파일을 받고 완료 마커를 갱신한다', async () => {
    const mem = memoryStore({ lastCommitSha: null })
    const { fs, disk } = memoryFs()
    const outcome = await runSync({
      client: mockClient({
        head: 'head2',
        tree: [
          { path: 'Dev/a.md', sha: 's1' },
          { path: 'Dev/attachments/i.png', sha: 's2' },
        ],
      }),
      ref: { owner: 'o', repo: 'r', branch: 'main' },
      store: mem.store,
      fs,
      sleep: noSleep,
    })
    expect(outcome).toMatchObject({ kind: 'synced', downloaded: 2, deleted: 0, failed: [] })
    expect(disk.has('Dev/a.md')).toBe(true)
    expect(mem.files.get('Dev/a.md')).toBe('s1')
    expect(mem.getLastCommitSha()).toBe('head2')
  })

  it('원격에서 사라진 파일을 지우고 북마크 정리 경로(deleteFiles)로 넘긴다', async () => {
    const mem = memoryStore({
      lastCommitSha: 'old',
      files: [{ path: 'Dev/gone.md', blobSha: 'x' }],
    })
    const { fs, disk } = memoryFs()
    disk.set('Dev/gone.md', new Uint8Array())

    const outcome = await runSync({
      client: mockClient({ head: 'head3', tree: [] }),
      ref: { owner: 'o', repo: 'r', branch: 'main' },
      store: mem.store,
      fs,
      sleep: noSleep,
    })
    expect(outcome).toMatchObject({ kind: 'synced', deleted: 1 })
    expect(disk.has('Dev/gone.md')).toBe(false)
    expect(mem.files.has('Dev/gone.md')).toBe(false)
  })

  it('부분 실패 시 완료 마커를 갱신하지 않는다 — 받은 파일은 유지', async () => {
    const mem = memoryStore({ lastCommitSha: 'old' })
    const { fs } = memoryFs()
    const outcome = await runSync({
      client: mockClient({
        head: 'head4',
        tree: [
          { path: 'Dev/ok.md', sha: 's1' },
          { path: 'Dev/bad.md', sha: 's2' },
        ],
        fileError: (path) => (path === 'Dev/bad.md' ? new Error('disk full') : null),
      }),
      ref: { owner: 'o', repo: 'r', branch: 'main' },
      store: mem.store,
      fs,
      sleep: noSleep,
    })
    expect(outcome.kind).toBe('synced')
    if (outcome.kind === 'synced') {
      expect(outcome.downloaded).toBe(1)
      expect(outcome.failed.map((f) => f.path)).toEqual(['Dev/bad.md'])
    }
    // 마커가 old 그대로 — 다음 동기화가 SHA 불일치를 감지해 이어받는다
    expect(mem.getLastCommitSha()).toBe('old')
    // 성공한 파일은 기록됨 (재개 시 blob SHA 로 스킵)
    expect(mem.files.get('Dev/ok.md')).toBe('s1')
  })

  it('중단 복구: 이미 받은 파일은 다시 요청하지 않는다', async () => {
    const requested: string[] = []
    const mem = memoryStore({
      lastCommitSha: 'old',
      files: Array.from({ length: 12 }, (_, i) => ({ path: `Dev/f${i}.md`, blobSha: `s${i}` })),
    })
    const { fs } = memoryFs()
    const client = mockClient({
      head: 'head5',
      tree: Array.from({ length: 47 }, (_, i) => ({ path: `Dev/f${i}.md`, sha: `s${i}` })),
      fileError: (path) => {
        requested.push(path)
        return null
      },
    })
    const outcome = await runSync({
      client,
      ref: { owner: 'o', repo: 'r', branch: 'main' },
      store: mem.store,
      fs,
      sleep: noSleep,
    })
    expect(outcome).toMatchObject({ kind: 'synced', downloaded: 35 })
    expect(requested).toHaveLength(35)
    expect(mem.getLastCommitSha()).toBe('head5')
  })

  it('취소되면 삭제·마커 갱신을 건너뛴다', async () => {
    let cancelled = false
    const mem = memoryStore({ lastCommitSha: 'old', files: [{ path: 'Dev/del.md', blobSha: 'x' }] })
    const { fs } = memoryFs()
    const outcome = await runSync({
      client: mockClient({
        head: 'head6',
        tree: Array.from({ length: 10 }, (_, i) => ({ path: `Dev/n${i}.md`, sha: `s${i}` })),
      }),
      ref: { owner: 'o', repo: 'r', branch: 'main' },
      store: mem.store,
      fs,
      concurrency: 1,
      maxConcurrency: 1,
      isCancelled: () => cancelled,
      onProgress: (p) => {
        if (p.phase === 'downloading' && p.done === 3) cancelled = true
      },
      sleep: noSleep,
    })
    expect(outcome.kind).toBe('synced')
    if (outcome.kind === 'synced') {
      expect(outcome.cancelled).toBe(true)
      expect(outcome.deleted).toBe(0)
    }
    expect(mem.getLastCommitSha()).toBe('old')
    expect(mem.files.has('Dev/del.md')).toBe(true)
  })
})
