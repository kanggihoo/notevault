/**
 * SQLite 스키마. 설계 문서 5장 참조.
 *
 * 마이그레이션은 PRAGMA user_version 기반이다. MIGRATIONS[n] 은
 * 버전 n → n+1 로 올리는 문장들이다.
 */
export const MIGRATIONS: readonly (readonly string[])[] = [
  // v0 → v1: 초기 스키마
  [
    // 구독 폴더 경로(prefix). '' 은 볼트 전체 구독을 뜻한다.
    `CREATE TABLE subscriptions (
      path TEXT PRIMARY KEY
    )`,
    // 받아둔 파일. blob_sha 가 증분 동기화의 전부다.
    `CREATE TABLE files (
      path          TEXT PRIMARY KEY,
      blob_sha      TEXT NOT NULL,
      size          INTEGER NOT NULL,
      downloaded_at INTEGER NOT NULL
    )`,
    // last_commit_sha, last_synced_at, owner, repo, branch 등
    `CREATE TABLE meta (
      key   TEXT PRIMARY KEY,
      value TEXT NOT NULL
    )`,
    `CREATE TABLE bookmarks (
      path       TEXT PRIMARY KEY,
      created_at INTEGER NOT NULL
    )`,
    `CREATE TABLE recents (
      path      TEXT PRIMARY KEY,
      opened_at INTEGER NOT NULL
    )`,
  ],
]

export const SCHEMA_VERSION = MIGRATIONS.length
