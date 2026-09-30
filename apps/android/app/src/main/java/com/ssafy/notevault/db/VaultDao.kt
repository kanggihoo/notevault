package com.ssafy.notevault.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ssafy.notevault.sync.LocalFile
import kotlinx.coroutines.flow.Flow

/**
 * 쿼리 레이어. SQL 은 전부 여기에만 있다 (RN 판 src/db/queries.ts 이식).
 *
 * 인터페이스가 아니라 abstract class 인 이유: 여러 테이블을 한 번에 바꾸는
 * @Transaction 메서드에 본문(여러 쿼리 호출)을 쓰기 위해서다.
 */
@Dao
abstract class VaultDao {

    // ── files ──────────────────────────────────────────────────

    @Query("SELECT path, blob_sha AS blobSha FROM files")
    abstract suspend fun getLocalFiles(): List<LocalFile>

    /** 파일 하나 완료마다 즉시 기록한다 — 중단 복구의 근거. */
    @Upsert
    abstract suspend fun upsertFile(file: FileEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM files WHERE path = :path)")
    abstract suspend fun hasFile(path: String): Boolean

    /** 홈 화면용: 받아둔 파일 수. DB 가 바뀔 때마다 새 값이 흐른다. */
    @Query("SELECT COUNT(*) FROM files")
    abstract fun observeFileCount(): Flow<Int>

    /** 저장공간 화면용: 받아둔 총 바이트. */
    @Query("SELECT COALESCE(SUM(size), 0) FROM files")
    abstract suspend fun getTotalSize(): Long

    /** 파일 삭제와 함께 북마크·최근 기록도 정리한다 — 열리지 않는 항목이 목록에 남지 않는다. */
    @Transaction
    open suspend fun deleteFiles(paths: List<String>) {
        // Android 8(minSdk 26) 의 SQLite 는 쿼리 하나에 변수 999개까지만 받는다.
        // 폴더 구독 해제 한 번에 1,000개 넘게 지울 수 있으므로 나눠서 보낸다.
        paths.chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            deleteFileRows(chunk)
            deleteBookmarkRows(chunk)
            deleteRecentRows(chunk)
        }
    }

    @Query("DELETE FROM files WHERE path IN (:paths)")
    protected abstract suspend fun deleteFileRows(paths: List<String>)

    @Query("DELETE FROM bookmarks WHERE path IN (:paths)")
    protected abstract suspend fun deleteBookmarkRows(paths: List<String>)

    @Query("DELETE FROM recents WHERE path IN (:paths)")
    protected abstract suspend fun deleteRecentRows(paths: List<String>)

    // ── subscriptions ──────────────────────────────────────────

    @Query("SELECT path FROM subscriptions")
    abstract suspend fun getSubscriptions(): List<String>

    @Transaction
    open suspend fun setSubscriptions(paths: List<String>) {
        clearSubscriptions()
        insertSubscriptions(paths.map(::SubscriptionEntity))
        // 완료 마커는 "그 시점의 구독 집합 기준"이다. 구독이 바뀌면 무효 —
        // 지우지 않으면 HEAD 가 그대로일 때 "이미 최신"으로 끝나 새 구독분을 받지 않는다.
        deleteMeta(MetaKeys.LAST_COMMIT_SHA)
    }

    @Query("DELETE FROM subscriptions")
    protected abstract suspend fun clearSubscriptions()

    @Insert
    protected abstract suspend fun insertSubscriptions(rows: List<SubscriptionEntity>)

    // ── meta ───────────────────────────────────────────────────

    @Query("SELECT value FROM meta WHERE key = :key")
    abstract suspend fun getMeta(key: String): String?

    suspend fun setMeta(key: String, value: String) = upsertMeta(MetaEntity(key, value))

    @Upsert
    protected abstract suspend fun upsertMeta(row: MetaEntity)

    @Query("DELETE FROM meta WHERE key = :key")
    abstract suspend fun deleteMeta(key: String)

    // ── bookmarks ──────────────────────────────────────────────

    /** DB 가 바뀔 때마다 새 목록을 흘려보낸다 — 화면이 자동으로 갱신된다. */
    @Query("SELECT path FROM bookmarks ORDER BY created_at DESC")
    abstract fun observeBookmarks(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path)")
    abstract suspend fun isBookmarked(path: String): Boolean

    /** @return 토글 후 북마크 상태 */
    @Transaction
    open suspend fun toggleBookmark(path: String, now: Long): Boolean =
        if (isBookmarked(path)) {
            deleteBookmark(path)
            false
        } else {
            insertBookmark(BookmarkEntity(path, createdAt = now))
            true
        }

    @Insert
    protected abstract suspend fun insertBookmark(row: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE path = :path")
    protected abstract suspend fun deleteBookmark(path: String)

    // ── recents ────────────────────────────────────────────────

    @Query("SELECT path FROM recents ORDER BY opened_at DESC LIMIT $RECENTS_LIMIT")
    abstract fun observeRecents(): Flow<List<String>>

    @Transaction
    open suspend fun touchRecent(path: String, now: Long) {
        upsertRecent(RecentEntity(path, openedAt = now))
        trimRecents() // 상한을 넘는 오래된 항목 정리
    }

    @Upsert
    protected abstract suspend fun upsertRecent(row: RecentEntity)

    @Query("DELETE FROM recents WHERE path NOT IN (SELECT path FROM recents ORDER BY opened_at DESC LIMIT $RECENTS_LIMIT)")
    protected abstract suspend fun trimRecents()

    // ── 전체 초기화 ────────────────────────────────────────────

    /** 저장소를 바꿀 때 쓴다. 이전 저장소의 모든 기록을 지운다. */
    @Transaction
    open suspend fun clearAll() {
        clearFiles()
        clearSubscriptions()
        clearMeta()
        clearBookmarks()
        clearRecents()
    }

    @Query("DELETE FROM files")
    protected abstract suspend fun clearFiles()

    @Query("DELETE FROM meta")
    protected abstract suspend fun clearMeta()

    @Query("DELETE FROM bookmarks")
    protected abstract suspend fun clearBookmarks()

    @Query("DELETE FROM recents")
    protected abstract suspend fun clearRecents()

    private companion object {
        /** 999 보다 작게 잡는다. */
        const val SQLITE_MAX_VARIABLES = 500
    }
}
