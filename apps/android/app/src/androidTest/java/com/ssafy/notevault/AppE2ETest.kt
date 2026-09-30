package com.ssafy.notevault

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Base64
import com.ssafy.notevault.vault.CurrentWebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.Buffer
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
    /** true 면 가짜 볼트에 렌더링 검사용 리치 노트와 이미지를 더 넣는다. */
    private var withRichNote = false

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
            "git" -> json(200, """{"truncated":false,"tree":[${treeEntries().joinToString(",")}]}""")
            "contents" -> {
                val path = segments.drop(4).joinToString("/")
                when (path) {
                    RICH_NOTE -> json(200, RICH_MARKDOWN)
                    RICH_IMAGE -> MockResponse.Builder().code(200).body(Buffer().write(Base64.decode(PNG_1PX, Base64.DEFAULT))).build()
                    else -> json(200, "# ${segments.last()}")
                }
            }
            else -> json(404, "{}")
        }
    }

    private fun treeEntries(): List<String> {
        val files = mutableListOf("spring/a.md", "spring/AOP/b.md", "알고리즘/x.md")
        if (withRichNote) files += listOf(RICH_NOTE, RICH_IMAGE)
        return files.mapIndexed { i, p -> """{"path":"$p","type":"blob","sha":"s$i","size":10}""" }
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

    /** 설정 → 구독(spring 만) → 동기화. spring 아래 파일을 받는다 (기본 2개). */
    private fun setUpAndSyncSpring(expected: Int = 2) {
        fillSettingsAndTest("github_pat_GOOD")
        waitForText("연결됨")
        compose.onNodeWithContentDescription("뒤로").performClick()

        compose.onNodeWithText("구독 관리").performClick()
        waitForText("볼트 전체")
        compose.onNodeWithText("spring").performClick()
        compose.onNodeWithText("저장하고 동기화", substring = true).performClick()
        waitForText("새로 $expected")
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

        // 가짜 GitHub 이 준 본문 "# a.md" 가 렌더러에서 제목(h1)으로 그려진다
        waitForJs("document.querySelector('#nv-root h1')?.textContent === 'a.md'")
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

    // ── 4단계: WebView 렌더링 ─────────────────────────────────────

    @Test
    fun 마크다운_노트가_렌더러로_그려지고_위키링크로_이동한다() {
        withRichNote = true
        setUpAndSyncSpring(expected = 4)

        compose.onNodeWithContentDescription("메뉴").performClick()
        compose.onNodeWithText("spring").performClick()
        compose.onNodeWithText("rich").performClick()

        // WebView 안의 DOM 을 JS 로 검사한다 — Compose 테스트는 WebView 속을 볼 수 없다.
        waitForJs(
            """
            (function () {
              const r = document.getElementById('nv-root');
              const img = r.querySelector('img.nv-img');
              return !!(r.querySelector('.callout')            // > [!note]
                && r.querySelector('.katex')                   // ${'$'}a^2${'$'}
                && r.querySelector('.nv-mermaid svg')          // mermaid 다이어그램
                && r.querySelector('.nv-copy')                 // 코드 복사 버튼
                && img && img.complete && img.naturalWidth > 0 // 다른 폴더(attachments)의 ![[pic.png]]
                && !r.textContent.includes('title: 리치'));     // frontmatter 는 숨긴다
            })()
            """.trimIndent(),
        )

        saveScreenshot("rich-note.png")

        // [[b]] 를 누르면 spring/AOP/b.md 로 이동한다
        evalInWebView("document.querySelector('a.nv-wikilink').click(); true")
        waitForText("b") // 상단 제목
        compose.onNodeWithContentDescription("뒤로").assertExists()
    }

    /** 화면에 떠 있는 WebView 에서 JS 를 실행하고 결과(JSON 문자열)를 받는다. */
    private fun evalInWebView(js: String): String {
        val latch = CountDownLatch(1)
        var result = "<WebView 없음>"
        scenario.onActivity {
            val web = CurrentWebView.ref?.get()
            if (web == null) latch.countDown() else web.evaluateJavascript(js) { result = it; latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return "<응답 없음>"
        return result
    }

    /**
     * WebView 안 조건이 참이 될 때까지 기다린다.
     * Thread.sleep 으로 기다리면 Compose 테스트의 화면 시계가 멈춰 화면 전환·WebView 생성이 안 일어난다
     * — 반드시 compose.waitUntil 로 기다려야 그 사이 프레임이 진행된다.
     */
    private fun waitForJs(js: String, timeoutMs: Long = 20_000) {
        try {
            compose.waitUntil(timeoutMs) { evalInWebView(js) == "true" }
            return
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // 아래에서 진단 정보를 담아 다시 던진다.
        }
        val probe = """
            JSON.stringify({
              url: location.href,
              callout: !!document.querySelector('.callout'),
              katex: !!document.querySelector('.katex'),
              mermaid: document.querySelector('.nv-mermaid')?.outerHTML.slice(0, 200),
              copy: !!document.querySelector('.nv-copy'),
              img: (() => { const i = document.querySelector('img.nv-img'); return i && [i.getAttribute('src'), i.complete, i.naturalWidth] })(),
              html: document.getElementById('nv-root')?.innerHTML.slice(0, 300),
            })
        """.trimIndent()
        error("WebView 조건이 ${timeoutMs}ms 안에 참이 되지 않았다: ${evalInWebView(probe)}")
    }

    /** 눈으로 확인하기 위한 캡처. /sdcard/Android/data/<앱>/files/ 에 남는다 (adb pull 로 꺼낸다). */
    private fun saveScreenshot(name: String) {
        // 화면 전체 캡처는 Compose 테스트 시계보다 늦게 따라와서, 노트 영역(WebView) 노드를 직접 찍는다.
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("note-webview").captureToImage().asAndroidBitmap()
        val dir = ApplicationProvider.getApplicationContext<NoteVaultApp>().getExternalFilesDir(null) ?: return
        java.io.File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val RICH_NOTE = "spring/rich.md"
        const val RICH_IMAGE = "spring/attachments/pic.png"
        const val PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val RICH_MARKDOWN = """
            ---
            title: 리치
            ---
            # 리치 노트

            > [!note] 알림
            > 콜아웃 본문

            수식 ${'$'}a^2 + b^2 = c^2${'$'}

            ```mermaid
            graph TD; A-->B
            ```

            ```kotlin
            val x = 1
            ```

            ![[pic.png]]

            | 항목 | 값 |
            |---|---|
            | ==하이라이트== | `code` |

            [[b]] 로 이동
        """.trimIndent()
    }
}
