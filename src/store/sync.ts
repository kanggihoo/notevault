import { create } from 'zustand'
import { getDb } from '../db'
import * as q from '../db/queries'
import { getToken } from '../auth'
import { GithubClient, GithubError, type RepoRef } from '../sync/github'
import { appFetch } from '../sync/http'
import { runSync, type SyncStore } from '../sync/runSync'
import { createVaultFs } from '../sync/vaultFs'

/**
 * 동기화 상태와 실행. 설계 문서 9장의 UX 표를 상태로 옮긴 것이다.
 *
 * Context 대신 zustand 를 쓰는 이유: 진행률 갱신(파일마다 1회)이 잦아
 * Context 로는 불필요한 리렌더가 넓게 퍼진다 (설계 문서 2.2).
 */
export type SyncStatus =
  | { kind: 'idle' }
  | { kind: 'checking' }
  | { kind: 'downloading'; done: number; total: number; currentPath: string }
  | { kind: 'up-to-date' }
  | { kind: 'done'; downloaded: number; deleted: number }
  | { kind: 'partial'; failedCount: number }
  | { kind: 'auth-required' }
  | { kind: 'offline' }
  | { kind: 'error'; message: string }

type SyncState = {
  status: SyncStatus
  /** 로컬 파일 목록의 세대. 바뀌면 사이드바가 다시 읽는다. */
  filesVersion: number
  cancelRequested: boolean
  cancel(): void
  /**
   * 동기화 실행. manual=false(앱 실행 시 자동)면 인증 실패를 배너로만
   * 표시하고 인증 화면으로 밀어내지 않는다 (설계 문서 7장 인증 화면).
   */
  sync(repo: RepoRef, options?: { manual?: boolean }): Promise<void>
}

async function makeStoreAdapter(): Promise<SyncStore> {
  const db = await getDb()
  return {
    getLocalFiles: () => q.getLocalFiles(db),
    getSubscriptions: () => q.getSubscriptions(db),
    getLastCommitSha: () => q.getMeta(db, 'last_commit_sha'),
    setLastCommitSha: (sha) => q.setMeta(db, 'last_commit_sha', sha),
    setLastSyncedAt: (ms) => q.setMeta(db, 'last_synced_at', String(ms)),
    upsertFile: (f) => q.upsertFile(db, f),
    deleteFiles: (paths) => q.deleteFiles(db, paths),
  }
}

export const useSync = create<SyncState>((set, get) => ({
  status: { kind: 'idle' },
  filesVersion: 0,
  cancelRequested: false,

  cancel() {
    set({ cancelRequested: true })
  },

  async sync(repo, options = {}) {
    if (get().status.kind === 'checking' || get().status.kind === 'downloading') return

    const token = await getToken()
    if (!token) {
      set({ status: { kind: 'auth-required' } })
      return
    }

    set({ status: { kind: 'checking' }, cancelRequested: false })
    try {
      const outcome = await runSync({
        client: new GithubClient(token, appFetch),
        ref: repo,
        store: await makeStoreAdapter(),
        fs: createVaultFs(),
        isCancelled: () => get().cancelRequested,
        onProgress: (p) => {
          if (p.phase === 'downloading') {
            set({
              status: {
                kind: 'downloading',
                done: p.done,
                total: p.total,
                currentPath: p.currentPath,
              },
            })
          }
        },
      })

      set((state) => ({ filesVersion: state.filesVersion + 1 }))

      if (outcome.kind === 'up-to-date') {
        set({ status: { kind: 'up-to-date' } })
      } else if (outcome.failed.length > 0) {
        set({ status: { kind: 'partial', failedCount: outcome.failed.length } })
      } else {
        set({
          status: { kind: 'done', downloaded: outcome.downloaded, deleted: outcome.deleted },
        })
      }
    } catch (error) {
      if (error instanceof GithubError) {
        if (error.kind === 'auth') {
          set({ status: { kind: 'auth-required' } })
          return
        }
        if (error.kind === 'network') {
          set({ status: { kind: 'offline' } })
          return
        }
      }
      set({ status: { kind: 'error', message: String(error) } })
    }
  },
}))
