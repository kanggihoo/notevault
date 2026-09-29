package com.ssafy.notevault.github

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * RN 판 src/sync/github.test.ts 이식.
 * 차이: 401 과 403 을 구분하고 GitHub 의 오류 메시지를 보존한다 ("재연결 필요" 원인 추적용).
 */
class GithubClientTest {

    private val ref = RepoRef(owner = "kkh", repo = "obsidian", branch = "main")
    private lateinit var server: MockWebServer
    private lateinit var client: GithubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GithubClient(token = "test-token", baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String = "{}", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        server.enqueue(builder.build())
    }

    // ── 오류 분류 ──────────────────────────────────────────────

    @Test
    fun `401 은 Unauthorized 이고 GitHub 메시지를 담는다`() = runTest {
        respond(401, """{"message":"Bad credentials"}""")
        val error = assertFailsWith<GithubException.Unauthorized> { client.getHeadSha(ref) }
        assertEquals("Bad credentials", error.githubMessage)
    }

    @Test
    fun `rate limit 헤더 없는 403 은 Forbidden 이고 메시지를 담는다`() = runTest {
        respond(403, """{"message":"Resource not accessible"}""", "x-ratelimit-remaining" to "4999")
        val error = assertFailsWith<GithubException.Forbidden> { client.getHeadSha(ref) }
        assertEquals("Resource not accessible", error.githubMessage)
    }

    @Test
    fun `403 에 remaining 0 이면 RateLimited`() = runTest {
        respond(403, headers = arrayOf("x-ratelimit-remaining" to "0"))
        assertFailsWith<GithubException.RateLimited> { client.getHeadSha(ref) }
    }

    @Test
    fun `429 는 RateLimited 이고 Retry-After 를 읽는다`() = runTest {
        respond(429, headers = arrayOf("retry-after" to "30"))
        val error = assertFailsWith<GithubException.RateLimited> { client.getHeadSha(ref) }
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `404 는 NotFound`() = runTest {
        respond(404)
        assertFailsWith<GithubException.NotFound> { client.getHeadSha(ref) }
    }

    @Test
    fun `500 은 Server 이고 상태 코드를 담는다`() = runTest {
        respond(500)
        val error = assertFailsWith<GithubException.Server> { client.getHeadSha(ref) }
        assertEquals(500, error.status)
    }

    @Test
    fun `연결 실패는 Network`() = runTest {
        server.close() // 닫힌 포트로 요청 → 연결 거부
        assertFailsWith<GithubException.Network> { client.getHeadSha(ref) }
    }

    // ── 정상 경로 ──────────────────────────────────────────────

    @Test
    fun `HEAD SHA 를 반환하고 인증 헤더를 보낸다`() = runTest {
        respond(200, """{"sha":"abc123","commit":{"message":"ignored"}}""")

        assertEquals("abc123", client.getHeadSha(ref))

        val request = server.takeRequest()
        assertEquals("/repos/kkh/obsidian/commits/main", request.url.encodedPath)
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun `브랜치 이름의 슬래시는 인코딩한다`() = runTest {
        respond(200, """{"sha":"x"}""")
        client.getHeadSha(ref.copy(branch = "feature/mobile"))
        assertEquals("/repos/kkh/obsidian/commits/feature%2Fmobile", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `rate limit 헤더를 파싱한다`() = runTest {
        respond(
            200, """{"sha":"x"}""",
            "x-ratelimit-remaining" to "4980",
            "x-ratelimit-limit" to "5000",
            "x-ratelimit-reset" to "1790000000",
        )
        client.getHeadSha(ref)
        assertEquals(RateLimit(remaining = 4980, limit = 5000, resetAt = Instant.ofEpochSecond(1790000000)), client.rateLimit)
    }

    @Test
    fun `토큰 만료 시각 헤더를 파싱한다 (UTC 표기)`() = runTest {
        respond(200, """{"sha":"x"}""", "github-authentication-token-expiration" to "2026-10-29 12:00:00 UTC")
        client.getHeadSha(ref)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), client.tokenExpiresAt)
    }

    @Test
    fun `토큰 만료 시각 헤더를 파싱한다 (오프셋 표기)`() = runTest {
        respond(200, """{"sha":"x"}""", "github-authentication-token-expiration" to "2026-10-29 21:00:00 +0900")
        client.getHeadSha(ref)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), client.tokenExpiresAt)
    }

    @Test
    fun `만료 헤더가 없으면 null`() = runTest {
        respond(200, """{"sha":"x"}""")
        client.getHeadSha(ref)
        assertNull(client.tokenExpiresAt)
    }

    @Test
    fun `checkAccess 는 저장소 경로를 호출한다`() = runTest {
        respond(200, """{"full_name":"kkh/obsidian"}""")
        client.checkAccess(ref)
        assertEquals("/repos/kkh/obsidian", server.takeRequest().url.encodedPath)
    }
}
