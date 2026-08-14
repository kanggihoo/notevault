import type { SQLiteDatabase } from 'expo-sqlite'
import type { LocalFile } from '../sync/types'

/*
 * 쿼리 레이어. SQL 은 전부 여기에만 있다.
 *
 * 모든 함수가 db 를 인자로 받는 이유: 전역 핸들에 묶이지 않아야 동기화
 * 트랜잭션과 화면 코드가 같은 함수를 쓸 수 있다.
 */

// ── files ─────────────────────────────────────────────────────

export async function getLocalFiles(db: SQLiteDatabase): Promise<LocalFile[]> {
  const rows = await db.getAllAsync<{ path: string; blob_sha: string }>(
    'SELECT path, blob_sha FROM files',
  )
  return rows.map((r) => ({ path: r.path, blobSha: r.blob_sha }))
}

/** 파일 하나 완료마다 즉시 기록한다 — 중단 복구의 근거. 설계 문서 6장. */
export async function upsertFile(
  db: SQLiteDatabase,
  file: { path: string; blobSha: string; size: number },
): Promise<void> {
  await db.runAsync(
    `INSERT INTO files (path, blob_sha, size, downloaded_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(path) DO UPDATE SET blob_sha = excluded.blob_sha,
       size = excluded.size, downloaded_at = excluded.downloaded_at`,
    file.path,
    file.blobSha,
    file.size,
    Date.now(),
  )
}

/**
 * 파일 삭제와 함께 북마크·최근 기록도 정리한다 — 확정된 결정.
 * 목록에 열리지 않는 항목이 남지 않는다.
 */
export async function deleteFiles(db: SQLiteDatabase, paths: string[]): Promise<void> {
  if (paths.length === 0) return
  await db.withTransactionAsync(async () => {
    for (const path of paths) {
      await db.runAsync('DELETE FROM files WHERE path = ?', path)
      await db.runAsync('DELETE FROM bookmarks WHERE path = ?', path)
      await db.runAsync('DELETE FROM recents WHERE path = ?', path)
    }
  })
}

export async function hasFile(db: SQLiteDatabase, path: string): Promise<boolean> {
  const row = await db.getFirstAsync('SELECT 1 FROM files WHERE path = ?', path)
  return row != null
}

/** 저장공간 화면용: 받아둔 총 바이트. */
export async function getTotalSize(db: SQLiteDatabase): Promise<number> {
  const row = await db.getFirstAsync<{ total: number | null }>(
    'SELECT SUM(size) AS total FROM files',
  )
  return row?.total ?? 0
}

// ── subscriptions ─────────────────────────────────────────────

export async function getSubscriptions(db: SQLiteDatabase): Promise<string[]> {
  const rows = await db.getAllAsync<{ path: string }>('SELECT path FROM subscriptions')
  return rows.map((r) => r.path)
}

export async function setSubscriptions(db: SQLiteDatabase, paths: string[]): Promise<void> {
  await db.withTransactionAsync(async () => {
    await db.runAsync('DELETE FROM subscriptions')
    for (const path of paths) {
      await db.runAsync('INSERT INTO subscriptions (path) VALUES (?)', path)
    }
    // 완료 마커는 "그 시점의 구독 집합 기준으로 이 커밋까지 동기화됨"이다.
    // 구독이 바뀌면 마커가 무효다 — 지우지 않으면 HEAD 가 그대로일 때
    // 다음 동기화가 "이미 최신"으로 즉시 종료해 새 구독분을 받지 않는다.
    await db.runAsync("DELETE FROM meta WHERE key = 'last_commit_sha'")
  })
}

// ── meta ──────────────────────────────────────────────────────

export async function getMeta(db: SQLiteDatabase, key: string): Promise<string | null> {
  const row = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM meta WHERE key = ?',
    key,
  )
  return row?.value ?? null
}

export async function setMeta(db: SQLiteDatabase, key: string, value: string): Promise<void> {
  await db.runAsync(
    `INSERT INTO meta (key, value) VALUES (?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
    key,
    value,
  )
}

// ── bookmarks · recents ───────────────────────────────────────

export async function getBookmarks(db: SQLiteDatabase): Promise<string[]> {
  const rows = await db.getAllAsync<{ path: string }>(
    'SELECT path FROM bookmarks ORDER BY created_at DESC',
  )
  return rows.map((r) => r.path)
}

export async function toggleBookmark(db: SQLiteDatabase, path: string): Promise<boolean> {
  const exists = await db.getFirstAsync('SELECT 1 FROM bookmarks WHERE path = ?', path)
  if (exists) {
    await db.runAsync('DELETE FROM bookmarks WHERE path = ?', path)
    return false
  }
  await db.runAsync('INSERT INTO bookmarks (path, created_at) VALUES (?, ?)', path, Date.now())
  return true
}

const RECENTS_LIMIT = 30

export async function getRecents(db: SQLiteDatabase): Promise<string[]> {
  const rows = await db.getAllAsync<{ path: string }>(
    'SELECT path FROM recents ORDER BY opened_at DESC LIMIT ?',
    RECENTS_LIMIT,
  )
  return rows.map((r) => r.path)
}

export async function touchRecent(db: SQLiteDatabase, path: string): Promise<void> {
  await db.runAsync(
    `INSERT INTO recents (path, opened_at) VALUES (?, ?)
     ON CONFLICT(path) DO UPDATE SET opened_at = excluded.opened_at`,
    path,
    Date.now(),
  )
  // 상한을 넘는 오래된 항목은 정리한다.
  await db.runAsync(
    `DELETE FROM recents WHERE path NOT IN
       (SELECT path FROM recents ORDER BY opened_at DESC LIMIT ?)`,
    RECENTS_LIMIT,
  )
}
