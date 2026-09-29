package com.ssafy.notevault.github

import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorMessagesTest {

    private val now = Instant.parse("2026-09-29T00:00:00Z")

    // ── describe: "재연결 필요" 하나로 뭉치지 않고 원인을 말한다 ──

    @Test
    fun `401 은 GitHub 메시지를 함께 보여준다`() {
        val text = describe(GithubException.Unauthorized("Bad credentials"), now = now)
        assertTrue("401" in text && "Bad credentials" in text, text)
    }

    @Test
    fun `알려진 만료 시각이 지났으면 401 을 만료로 안내한다`() {
        val text = describe(
            GithubException.Unauthorized("Bad credentials"),
            tokenExpiresAt = Instant.parse("2026-09-28T00:00:00Z"),
            now = now,
        )
        assertTrue("만료" in text, text)
    }

    @Test
    fun `403 은 권한을 확인하라고 하고 GitHub 메시지를 보여준다`() {
        val text = describe(GithubException.Forbidden("Resource not accessible by personal access token"), now = now)
        assertTrue("403" in text && "Resource not accessible" in text && "권한" in text, text)
    }

    @Test
    fun `404 는 저장소·브랜치를 확인하라고 한다`() {
        val text = describe(GithubException.NotFound(), now = now)
        assertTrue("404" in text && "브랜치" in text, text)
    }

    @Test
    fun `rate limit 은 기다릴 시간을 알려준다`() {
        assertTrue("30초" in describe(GithubException.RateLimited(30), now = now))
    }

    @Test
    fun `네트워크 오류는 Wi-Fi 차단 가능성을 알려준다`() {
        val text = describe(GithubException.Network(IOException("timeout")), now = now)
        assertTrue("네트워크" in text && "timeout" in text && "LTE" in text, text)
    }

    @Test
    fun `서버 오류는 상태 코드를 보여준다`() {
        assertTrue("502" in describe(GithubException.Server(502), now = now))
    }

    // ── expiryNotice: 만료 D-7 경고 ──

    @Test
    fun `만료 정보가 없으면 경고도 없다`() {
        assertNull(expiryNotice(null, now, ZoneOffset.UTC))
    }

    @Test
    fun `8일 이상 남으면 경고하지 않는다`() {
        assertNull(expiryNotice(Instant.parse("2026-10-07T12:00:00Z"), now, ZoneOffset.UTC))
    }

    @Test
    fun `7일 이내면 남은 날짜와 함께 경고한다`() {
        assertEquals(
            ExpiryNotice.Soon(daysLeft = 7, date = LocalDate.of(2026, 10, 6)),
            expiryNotice(Instant.parse("2026-10-06T12:00:00Z"), now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `지났으면 만료로 알린다`() {
        assertEquals(
            ExpiryNotice.Expired(date = LocalDate.of(2026, 9, 28)),
            expiryNotice(Instant.parse("2026-09-28T12:00:00Z"), now, ZoneOffset.UTC),
        )
    }
}
