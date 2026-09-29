package com.ssafy.notevault.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ssafy.notevault.db.FileEntity
import com.ssafy.notevault.db.MetaKeys
import com.ssafy.notevault.db.VaultDao
import com.ssafy.notevault.db.VaultDatabase
import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.github.RepoRef
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RN 판 src/sync/runSync.test.ts 이식.
 * RN 은 메모리 가짜 저장소를 썼지만, 여기서는 진짜 Room(메모리 DB)·진짜 파일·가짜 GitHub 서버로
 * 순서와 오류 처리를 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
class RunSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ref = RepoRef("o", "r", "main")
    private lateinit var db: VaultDatabase
    private lateinit var dao: VaultDao
    private lateinit var files: VaultFiles
    private lateinit var server: MockWebServer
    private lateinit var client: GithubClient

    // 가짜 GitHub 의 상태. 서버 스레드에서 읽으므로 스레드 안전한 컬렉션을 쓴다.
    private var head = "head"
    private var tree: List<Pair<String, String>> = emptyList() // path to sha
    private val failingPaths = mutableSetOf<String>()
    private val requestedFiles = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        files = VaultFiles(tmp.root)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = fakeGithub(request)
        }
        server.start()
        client = GithubClient("token", baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun fakeGithub(request: RecordedRequest): MockResponse {
        val segments = request.url.pathSegments // [repos, o, r, ...]
        return when {
            segments[3] == "commits" -> ok("""{"sha":"$head"}""")
            segments[3] == "git" -> ok(
                """{"truncated":false,"tree":[${tree.joinToString(",") { (p, s) -> """{"path":"$p","sha":"$s","size":10,"type":"blob"}""" }}]}""",
            )
            segments[3] == "contents" -> {
                val path = segments.drop(4).joinToString("/")
                requestedFiles += path
                if (path in failingPaths) MockResponse.Builder().code(404).body("{}").build() else ok("content:$path")
            }
            else -> MockResponse.Builder().code(500).build()
        }
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private suspend fun sync(
        concurrency: Int = 5,
        isCancelled: () -> Boolean = { false },
        onProgress: (SyncProgress) -> Unit = {},
    ) = runSync(
        client, ref, dao, files,
        concurrency = concurrency,
        maxConcurrency = concurrency,
        isCancelled = isCancelled,
        onProgress = onProgress,
        sleep = {},
    )

    private suspend fun seedLocal(path: String, sha: String) {
        dao.upsertFile(FileEntity(path, sha, size = 10, downloadedAt = 0))
        files.writeAtomic(path, byteArrayOf())
    }

    @Test
    fun `HEAD 가 같으면 트리 조회 없이 즉시 종료한다`() = runTest {
        dao.setMeta(MetaKeys.LAST_COMMIT_SHA, "head1")
        head = "head1"

        assertEquals(SyncOutcome.UpToDate, sync())
        assertEquals(1, server.requestCount) // commits 한 번뿐
    }

    @Test
    fun `신규 파일을 받고 완료 마커를 갱신한다`() = runTest {
        dao.setSubscriptions(listOf(""))
        head = "head2"
        tree = listOf("Dev/a.md" to "s1", "Dev/attachments/i.png" to "s2")

        val outcome = assertIs<SyncOutcome.Synced>(sync())

        assertEquals(SyncOutcome.Synced(downloaded = 2, deleted = 0, failed = emptyList(), cancelled = false), outcome)
        assertEquals("content:Dev/a.md", files.readText("Dev/a.md"))
        assertEquals(listOf(LocalFile("Dev/a.md", "s1"), LocalFile("Dev/attachments/i.png", "s2")), dao.getLocalFiles().sortedBy { it.path })
        assertEquals("head2", dao.getMeta(MetaKeys.LAST_COMMIT_SHA))
    }

    @Test
    fun `원격에서 사라진 파일을 파일과 DB 에서 지운다`() = runTest {
        dao.setSubscriptions(listOf(""))
        seedLocal("Dev/gone.md", "x")
        head = "head3"
        tree = emptyList()

        val outcome = assertIs<SyncOutcome.Synced>(sync())

        assertEquals(1, outcome.deleted)
        assertFalse(files.exists("Dev/gone.md"))
        assertEquals(emptyList(), dao.getLocalFiles())
    }

    @Test
    fun `부분 실패 시 완료 마커를 갱신하지 않는다 — 받은 파일은 유지`() = runTest {
        dao.setSubscriptions(listOf(""))
        dao.setMeta(MetaKeys.LAST_COMMIT_SHA, "old")
        head = "head4"
        tree = listOf("Dev/ok.md" to "s1", "Dev/bad.md" to "s2")
        failingPaths += "Dev/bad.md"

        val outcome = assertIs<SyncOutcome.Synced>(sync())

        assertEquals(1, outcome.downloaded)
        assertEquals(listOf("Dev/bad.md"), outcome.failed.map { it.path })
        // 마커가 old 그대로 — 다음 동기화가 SHA 불일치를 감지해 이어받는다
        assertEquals("old", dao.getMeta(MetaKeys.LAST_COMMIT_SHA))
        // 성공한 파일은 기록됨 — 재개 시 blob SHA 로 스킵된다
        assertEquals(listOf(LocalFile("Dev/ok.md", "s1")), dao.getLocalFiles())
    }

    @Test
    fun `중단 복구 — 이미 받은 파일은 다시 요청하지 않는다`() = runTest {
        dao.setSubscriptions(listOf(""))
        repeat(12) { seedLocal("Dev/f$it.md", "s$it") }
        head = "head5"
        tree = (0 until 47).map { "Dev/f$it.md" to "s$it" }

        val outcome = assertIs<SyncOutcome.Synced>(sync())

        assertEquals(35, outcome.downloaded)
        assertEquals(35, requestedFiles.size)
        assertEquals("head5", dao.getMeta(MetaKeys.LAST_COMMIT_SHA))
    }

    @Test
    fun `취소되면 삭제·마커 갱신을 건너뛴다`() = runTest {
        dao.setSubscriptions(listOf(""))
        dao.setMeta(MetaKeys.LAST_COMMIT_SHA, "old")
        seedLocal("Dev/del.md", "x")
        head = "head6"
        tree = (0 until 10).map { "Dev/n$it.md" to "s$it" }
        var cancelled = false

        val outcome = assertIs<SyncOutcome.Synced>(
            sync(
                concurrency = 1,
                isCancelled = { cancelled },
                onProgress = { if (it is SyncProgress.Downloading && it.done == 3) cancelled = true },
            ),
        )

        assertTrue(outcome.cancelled)
        assertEquals(0, outcome.deleted)
        assertEquals("old", dao.getMeta(MetaKeys.LAST_COMMIT_SHA))
        assertTrue(files.exists("Dev/del.md"))
    }
}
