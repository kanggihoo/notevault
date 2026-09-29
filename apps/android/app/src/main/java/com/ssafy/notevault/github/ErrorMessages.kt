package com.ssafy.notevault.github

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 오류 → 화면 문구. RN 판은 401·403 을 모두 "GitHub 재연결 필요" 로만 보여줘서 원인을 알 수 없었다.
 * 여기서는 상태 코드와 GitHub 이 보낸 메시지를 그대로 보여준다.
 *
 * [tokenExpiresAt] 은 마지막으로 성공한 응답이 알려준 만료 시각이다.
 * GitHub 은 만료된 토큰에도 그냥 401 "Bad credentials" 를 주므로, 만료 여부는 이 값으로 판단한다.
 */
fun describe(error: Throwable, tokenExpiresAt: Instant? = null, now: Instant = Instant.now()): String = when (error) {
    is GithubException.Unauthorized ->
        if (tokenExpiresAt != null && !tokenExpiresAt.isAfter(now)) {
            "토큰이 만료되었습니다 (${tokenExpiresAt.toLocalDate()}). PC 에서 새 토큰을 발급해 붙여넣으세요."
        } else {
            "토큰이 거부되었습니다 (401). 잘못 입력했거나 폐기·만료된 토큰일 수 있어요." + github(error.githubMessage)
        }
    is GithubException.Forbidden ->
        "접근이 거부되었습니다 (403). 토큰에 이 저장소의 Contents 읽기 권한이 있는지 확인하세요." + github(error.githubMessage)
    is GithubException.NotFound ->
        "저장소나 브랜치를 찾을 수 없습니다 (404). owner·repo·브랜치 이름을 확인하세요. " +
            "토큰에 이 저장소 권한이 없어도 404 로 보입니다."
    is GithubException.RateLimited ->
        "요청 한도를 넘었습니다. ${error.retryAfterSeconds ?: 60}초 뒤에 다시 시도하세요."
    is GithubException.Network ->
        "네트워크 연결에 실패했습니다: ${error.cause?.message}. " +
            "지금 Wi-Fi 가 GitHub 을 막을 수 있으니 LTE 로도 시도해 보세요."
    is GithubException.Server -> "GitHub 서버 오류입니다 (${error.status}). 잠시 뒤 다시 시도하세요."
    else -> "알 수 없는 오류: ${error.message ?: error::class.simpleName}"
}

private fun github(message: String?) = if (message.isNullOrBlank()) "" else "\nGitHub 메시지: $message"

private fun Instant.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = atZone(zone).toLocalDate()

/** 토큰 만료 경고. 7일 이내면 [Soon], 지났으면 [Expired], 그 외엔 null. */
sealed interface ExpiryNotice {
    data class Soon(val daysLeft: Long, val date: LocalDate) : ExpiryNotice
    data class Expired(val date: LocalDate) : ExpiryNotice
}

const val EXPIRY_WARNING_DAYS = 7L

fun expiryNotice(expiresAt: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): ExpiryNotice? {
    if (expiresAt == null) return null
    val date = expiresAt.toLocalDate(zone)
    if (!expiresAt.isAfter(now)) return ExpiryNotice.Expired(date)
    val daysLeft = ChronoUnit.DAYS.between(now.toLocalDate(zone), date)
    return if (daysLeft <= EXPIRY_WARNING_DAYS) ExpiryNotice.Soon(daysLeft, date) else null
}
