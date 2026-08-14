import { openDatabaseAsync, type SQLiteDatabase } from 'expo-sqlite'
import { MIGRATIONS, SCHEMA_VERSION } from './schema'

let dbPromise: Promise<SQLiteDatabase> | null = null

/** 앱 전역에서 공유하는 단일 DB 핸들. 첫 호출 시 마이그레이션을 수행한다. */
export function getDb(): Promise<SQLiteDatabase> {
  dbPromise ??= open()
  return dbPromise
}

async function open(): Promise<SQLiteDatabase> {
  const db = await openDatabaseAsync('notevault.db')
  await db.execAsync('PRAGMA journal_mode = WAL')
  await migrate(db)
  return db
}

async function migrate(db: SQLiteDatabase): Promise<void> {
  const row = await db.getFirstAsync<{ user_version: number }>('PRAGMA user_version')
  let version = row?.user_version ?? 0

  while (version < SCHEMA_VERSION) {
    const statements = MIGRATIONS[version]
    await db.withTransactionAsync(async () => {
      for (const sql of statements) await db.execAsync(sql)
    })
    version += 1
    await db.execAsync(`PRAGMA user_version = ${version}`)
  }
}

/** 테스트에서 모듈 상태를 초기화할 때만 사용한다. */
export function resetDbForTesting(): void {
  dbPromise = null
}
