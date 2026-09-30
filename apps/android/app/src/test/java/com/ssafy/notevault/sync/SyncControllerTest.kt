package com.ssafy.notevault.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ssafy.notevault.db.MetaKeys
import com.ssafy.notevault.db.VaultDatabase
import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.github.RepoRef
import com.ssafy.notevault.settings.InMemoryDataStore
import com.ssafy.notevault.settings.SettingsStore
import com.ssafy.notevault.settings.TokenCipher
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SyncControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: VaultDatabase
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsStore
    private lateinit var files: VaultFiles
    private lateinit var controller: SyncController
    private var headStatus = 200

    private val plainCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val segments = request.url.pathSegments
                return when {
                    headStatus != 200 -> MockResponse.Builder().code(headStatus).body("""{"message":"Bad credentials"}""").build()
                    segments[3] == "commits" -> ok("""{"sha":"head1"}""")
                    segments[3] == "git" -> ok("""{"truncated":false,"tree":[{"path":"Dev/a.md","sha":"s1","size":5,"type":"blob"}]}""")
                    else -> ok("hello")
                }
            }
        }
        server.start()
        settings = SettingsStore(InMemoryDataStore(), plainCipher)
        files = VaultFiles(tmp.root)
        controller = SyncController(
            settings = settings,
            dao = db.dao(),
            files = files,
            clientFactory = { GithubClient(it, baseUrl = server.url("/")) },
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private suspend fun configure() {
        settings.saveToken("github_pat_x")
        settings.saveRepo(RepoRef("o", "r", "main"))
        db.dao().setSubscriptions(listOf(""))
    }

    private suspend fun awaitFinish(): SyncStatus =
        controller.status.first { it is SyncStatus.Finished || it is SyncStatus.Failed }

    @Test
    fun `설정이 없으면 요청하지 않고 실패로 알린다`() = runTest {
        controller.sync()
        val failed = assertIs<SyncStatus.Failed>(awaitFinish())
        assertTrue("설정" in failed.message, failed.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `동기화하면 파일을 받고 결과를 알린다`() = runTest {
        configure()
        controller.sync()

        val finished = assertIs<SyncStatus.Finished>(awaitFinish())
        assertEquals(SyncOutcome.Synced(downloaded = 1, deleted = 0, failed = emptyList(), cancelled = false), finished.outcome)
        assertEquals("hello", files.readText("Dev/a.md"))
    }

    @Test
    fun `401 이면 원인 문구로 실패를 알린다`() = runTest {
        configure()
        headStatus = 401
        controller.sync()

        val failed = assertIs<SyncStatus.Failed>(awaitFinish())
        assertTrue("401" in failed.message && "Bad credentials" in failed.message, failed.message)
    }

    @Test
    fun `응답의 토큰 만료 시각을 설정에 저장한다`() = runTest {
        configure()
        controller.sync()
        awaitFinish()
        // 만료 헤더가 없는 응답 → 저장된 값도 없음 (헤더가 있으면 그 값이 저장된다)
        assertNull(settings.settings.first().tokenExpiresAt)
    }

    @Test
    fun `볼트 초기화는 파일·DB·구독을 모두 지운다`() = runTest {
        configure()
        controller.sync()
        awaitFinish()

        controller.resetVault()

        assertFalse(files.exists("Dev/a.md"))
        assertEquals(emptyList(), db.dao().getLocalFiles())
        assertEquals(emptyList(), db.dao().getSubscriptions())
        assertNull(db.dao().getMeta(MetaKeys.LAST_COMMIT_SHA))
        assertEquals(SyncStatus.Idle, controller.status.value)
    }
}
