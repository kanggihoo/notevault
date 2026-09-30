package com.ssafy.notevault.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ssafy.notevault.sync.LocalFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN 판 src/db/queries.ts 의 동작을 고정한다.
 * Robolectric 이 JVM 위에서 Android(SQLite 포함)를 흉내 내므로 에뮬레이터 없이 돈다.
 */
@RunWith(RobolectricTestRunner::class)
class VaultDaoTest {

    private lateinit var db: VaultDatabase
    private lateinit var dao: VaultDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(path: String, sha: String = "s", size: Long = 10) =
        FileEntity(path = path, blobSha = sha, size = size, downloadedAt = 0)

    // ── files ──────────────────────────────────────────────────

    @Test
    fun `upsert 는 같은 경로를 덮어쓴다 — 행이 늘지 않는다`() = runTest {
        dao.upsertFile(file("Dev/a.md", sha = "old"))
        dao.upsertFile(file("Dev/a.md", sha = "new"))

        assertEquals(listOf(LocalFile("Dev/a.md", "new")), dao.getLocalFiles())
    }

    @Test
    fun `파일 삭제는 북마크와 최근 기록도 함께 지운다`() = runTest {
        dao.upsertFile(file("Dev/a.md"))
        dao.upsertFile(file("Dev/b.md"))
        dao.toggleBookmark("Dev/a.md", now = 1)
        dao.toggleBookmark("Dev/b.md", now = 2)
        dao.touchRecent("Dev/a.md", now = 1)

        dao.deleteFiles(listOf("Dev/a.md"))

        assertEquals(listOf("Dev/b.md"), dao.getLocalFiles().map { it.path })
        assertEquals(listOf("Dev/b.md"), dao.observeBookmarks().first())
        assertEquals(emptyList(), dao.observeRecents().first())
    }

    @Test
    fun `대량 삭제도 된다 — SQLite 변수 개수 제한을 넘는 1200개`() = runTest {
        val paths = (0 until 1200).map { "big/f$it.md" }
        paths.forEach { dao.upsertFile(file(it)) }

        dao.deleteFiles(paths)

        assertEquals(emptyList(), dao.getLocalFiles())
    }

    @Test
    fun `hasFile 과 총 용량`() = runTest {
        assertEquals(0L, dao.getTotalSize())
        dao.upsertFile(file("a.md", size = 100))
        dao.upsertFile(file("b.png", size = 250))

        assertTrue(dao.hasFile("a.md"))
        assertFalse(dao.hasFile("zzz.md"))
        assertEquals(350L, dao.getTotalSize())
    }

    @Test
    fun `받은 파일 경로를 관찰한다 — 바뀌면 새 목록이 흐른다`() = runTest {
        dao.upsertFile(file("b.md"))
        dao.upsertFile(file("a.md"))
        assertEquals(listOf("a.md", "b.md"), dao.observeFilePaths().first())

        dao.deleteFiles(listOf("a.md"))
        assertEquals(listOf("b.md"), dao.observeFilePaths().first())
    }

    // ── subscriptions · meta ───────────────────────────────────

    @Test
    fun `구독을 통째로 교체한다`() = runTest {
        dao.setSubscriptions(listOf("Dev", "spring"))
        dao.setSubscriptions(listOf("알고리즘"))

        assertEquals(listOf("알고리즘"), dao.getSubscriptions())
    }

    @Test
    fun `구독이 바뀌면 완료 마커(last_commit_sha)만 지운다`() = runTest {
        dao.setMeta(MetaKeys.LAST_COMMIT_SHA, "abc")
        dao.setMeta(MetaKeys.OWNER, "kanggihoo")

        dao.setSubscriptions(listOf("Dev"))

        // 지우지 않으면 HEAD 가 그대로일 때 "이미 최신"으로 끝나 새 구독분을 받지 않는다.
        assertNull(dao.getMeta(MetaKeys.LAST_COMMIT_SHA))
        assertEquals("kanggihoo", dao.getMeta(MetaKeys.OWNER))
    }

    @Test
    fun `meta 는 없으면 null, 다시 쓰면 덮어쓴다`() = runTest {
        assertNull(dao.getMeta("k"))
        dao.setMeta("k", "1")
        dao.setMeta("k", "2")
        assertEquals("2", dao.getMeta("k"))
    }

    // ── bookmarks · recents ────────────────────────────────────

    @Test
    fun `북마크 토글과 최신순 정렬`() = runTest {
        assertTrue(dao.toggleBookmark("a.md", now = 1))
        assertTrue(dao.toggleBookmark("b.md", now = 2))
        assertEquals(listOf("b.md", "a.md"), dao.observeBookmarks().first())

        assertFalse(dao.toggleBookmark("a.md", now = 3))
        assertEquals(listOf("b.md"), dao.observeBookmarks().first())
        assertFalse(dao.isBookmarked("a.md"))
    }

    @Test
    fun `최근 기록 — 다시 열면 맨 위로, 상한을 넘으면 오래된 것부터 정리`() = runTest {
        (1..RECENTS_LIMIT + 5).forEach { dao.touchRecent("n$it.md", now = it.toLong()) }
        dao.touchRecent("n1.md", now = 1000) // 가장 오래된 것을 다시 연다 — 이미 정리됐어도 새로 들어온다

        val recents = dao.observeRecents().first()
        assertEquals(RECENTS_LIMIT, recents.size)
        assertEquals("n1.md", recents.first())
        assertEquals("n${RECENTS_LIMIT + 5}.md", recents[1])
    }
}
