package com.ssafy.notevault

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 앱 전체 통합 테스트 — 에뮬레이터·폰에서 돈다 (`./gradlew e2e`).
 *
 * 진짜 앱(MainActivity, Room, DataStore, Keystore, 파일)을 그대로 띄우고 GitHub 만 기기 안의
 * 가짜 서버(MockWebServer)로 바꿔 끼운다. Spring 의 @SpringBootTest + WireMock 자리다.
 * 요소는 좌표가 아니라 화면 글자(semantics)로 찾으므로 키보드가 올라와도 깨지지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class AppE2ETest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var rejectToken = false

    private val container get() = ApplicationProvider.getApplicationContext<NoteVaultApp>().container

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = fakeGithub(request)
        }
        server.start()
        container.githubBaseUrl = server.url("/")
        // 매 테스트를 빈 앱에서 시작한다 (이전 테스트·수동으로 넣은 토큰도 지워진다).
        runBlocking {
            container.settingsStore.clearAll()
            container.syncController.resetVault()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        server.close()
    }

    private fun fakeGithub(request: RecordedRequest): MockResponse {
        if (rejectToken) return json(401, """{"message":"Bad credentials"}""")
        val segments = request.url.pathSegments // [repos, owner, repo, ...]
        return when (segments.getOrNull(3)) {
            "commits" -> json(200, """{"sha":"abc1234def"}""")
            "git" -> json(
                200,
                """{"truncated":false,"tree":[
                    {"path":"spring","type":"tree","sha":"t1"},
                    {"path":"spring/a.md","type":"blob","sha":"s1","size":10},
                    {"path":"spring/AOP/b.md","type":"blob","sha":"s2","size":20},
                    {"path":"알고리즘/x.md","type":"blob","sha":"s3","size":30}
                ]}""",
            )
            "contents" -> json(200, "# ${segments.last()}")
            else -> json(404, "{}")
        }
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun fillSettingsAndTest(token: String) {
        compose.onNodeWithText("설정 열기").performClick()
        compose.onNodeWithText("fine-grained PAT 붙여넣기").performTextInput(token)
        compose.onNodeWithText("owner").performTextInput("kanggihoo")
        compose.onNodeWithText("repo").performTextInput("obsidian")
        compose.onNodeWithText("저장하고 연결 테스트").performScrollTo().performClick()
    }

    @Test
    fun 잘못된_토큰이면_원인을_담은_401_카드가_뜬다() {
        rejectToken = true

        fillSettingsAndTest("github_pat_FAKE")

        waitForText("401")
        compose.onNodeWithText("Bad credentials", substring = true).assertExists()
    }

    /** 설정 → 구독(spring 만) → 동기화. spring 아래 2개를 받는다. */
    private fun setUpAndSyncSpring() {
        fillSettingsAndTest("github_pat_GOOD")
        waitForText("연결됨")
        compose.onNodeWithContentDescription("뒤로").performClick()

        compose.onNodeWithText("구독 관리").performClick()
        waitForText("볼트 전체")
        compose.onNodeWithText("spring").performClick()
        compose.onNodeWithText("저장하고 동기화", substring = true).performClick()
        waitForText("새로 2")
    }

    @Test
    fun 설정_구독_동기화까지_한_번에() {
        setUpAndSyncSpring()

        compose.onNodeWithText("받은 파일 2개").assertExists()
        assertTrue(container.vaultFiles.exists("spring/AOP/b.md"))
        assertEquals(false, container.vaultFiles.exists("알고리즘/x.md"))
    }

    @Test
    fun 서랍에서_노트를_열고_즐겨찾기하면_목록에_남는다() {
        setUpAndSyncSpring()

        // 서랍 → spring 펼치기 → a.md (확장자 없이 "a" 로 보인다)
        compose.onNodeWithContentDescription("메뉴").performClick()
        compose.onNodeWithText("spring").performClick()
        compose.onNodeWithText("a").performClick()

        waitForText("# a.md") // 가짜 GitHub 이 준 본문
        compose.onNodeWithContentDescription("즐겨찾기 추가").performClick()
        compose.onNodeWithContentDescription("즐겨찾기 해제").assertExists()
        compose.onNodeWithContentDescription("뒤로").performClick()

        // 즐겨찾기·최근 탭에 남아 있다
        compose.onNodeWithContentDescription("메뉴").performClick()
        compose.onNodeWithText("즐겨찾기").performClick()
        compose.onNodeWithText("a").assertExists()
        compose.onNodeWithText("최근").performClick()
        compose.onNodeWithText("a").assertExists()

        // 검색: 파일 이름으로 찾는다
        compose.onNodeWithText("파일 이름 검색").performTextInput("b")
        waitForText("spring/AOP") // 결과 아래에 폴더 경로가 보인다
    }
}
