package com.ssafy.notevault.settings

import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.github.RepoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ViewModel 은 viewModelScope(= Main 디스패처)에서 코루틴을 돌린다.
 * JVM 테스트에는 Android Main 스레드가 없으므로 테스트 디스패처로 바꿔 끼운다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel

    private val plainCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        store = SettingsStore(InMemoryDataStore(), plainCipher)
        vm = SettingsViewModel(
            store = store,
            clientFactory = { token -> GithubClient(token, baseUrl = server.url("/")) },
            clock = { Instant.parse("2026-09-29T00:00:00Z") },
        )
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun fillForm(token: String = "github_pat_abcd") {
        vm.onTokenInput(token)
        vm.onOwnerInput("kanggihoo")
        vm.onRepoInput("obsidian")
        vm.onBranchInput("main")
    }

    private suspend fun awaitResult() = vm.state.first { it.connection !is ConnectionState.Testing && it.connection != ConnectionState.Idle }

    @Test
    fun `저장하고 연결 테스트 — 성공하면 토큰·저장소·만료 시각을 저장한다`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"sha":"abc"}""")
                .addHeader("github-authentication-token-expiration", "2026-10-29 12:00:00 UTC")
                .build(),
        )
        fillForm()

        vm.onSaveAndTest()
        val state = awaitResult()

        assertIs<ConnectionState.Ok>(state.connection)
        assertEquals("", state.tokenInput) // 저장 후 입력칸을 비운다
        assertEquals("abcd", state.savedTokenHint)
        assertEquals(RepoRef("kanggihoo", "obsidian", "main"), store.settings.first().repo)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), store.settings.first().tokenExpiresAt)
        assertEquals("/repos/kanggihoo/obsidian/commits/main", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `401 이면 원인을 담은 메시지를 보여준다`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"message":"Bad credentials"}""").build())
        fillForm()

        vm.onSaveAndTest()
        val failed = assertIs<ConnectionState.Failed>(awaitResult().connection)

        assertTrue("Bad credentials" in failed.message, failed.message)
    }

    @Test
    fun `토큰이 없으면 요청하지 않고 안내한다`() = runTest {
        fillForm(token = "")

        vm.onSaveAndTest()
        val failed = assertIs<ConnectionState.Failed>(awaitResult().connection)

        assertTrue("토큰" in failed.message, failed.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `저장소 칸이 비어 있으면 요청하지 않고 안내한다`() = runTest {
        vm.onTokenInput("github_pat_abcd")

        vm.onSaveAndTest()
        val failed = assertIs<ConnectionState.Failed>(awaitResult().connection)

        assertTrue("저장소" in failed.message, failed.message)
        assertEquals(0, server.requestCount)
    }
}
