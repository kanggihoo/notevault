package com.ssafy.notevault.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class RepoRef(val owner: String, val repo: String, val branch: String)

data class RateLimit(val remaining: Int, val limit: Int, val resetAt: Instant)

/**
 * GitHub API 오류 분류. RN 판은 401·403 을 모두 'auth' 로 뭉쳐서
 * "재연결 필요" 원인을 알 수 없었다 — 여기서는 나누고 GitHub 메시지를 보존한다.
 */
sealed class GithubException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(val githubMessage: String?) : GithubException("401: $githubMessage")
    class Forbidden(val githubMessage: String?) : GithubException("403: $githubMessage")
    class RateLimited(val retryAfterSeconds: Long?) : GithubException("rate limited (retry after ${retryAfterSeconds}s)")
    class NotFound : GithubException("404: repository or ref not found")
    class Network(cause: IOException) : GithubException("network: ${cause.message}", cause)
    class Server(val status: Int) : GithubException("server error ($status)")
}

/**
 * GitHub REST 클라이언트. RN 판 src/sync/github.ts 이식 (1단계: HEAD SHA · 연결 테스트).
 *
 * [baseUrl] 을 주입받는 이유: 테스트에서 MockWebServer 로 401·429·연결 실패를 재현하기 위해서다.
 */
class GithubClient(
    private val token: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
) {
    /** 마지막 응답의 rate limit. 설정 화면에 표시한다. */
    var rateLimit: RateLimit? = null
        private set

    /** 마지막 응답이 알려준 토큰 만료 시각. 만료 없는 토큰이면 null. */
    var tokenExpiresAt: Instant? = null
        private set

    /** 1단계 동기화: HEAD 커밋 SHA. 이전 값과 같으면 동기화를 여기서 끝낸다. */
    suspend fun getHeadSha(ref: RepoRef): String {
        val url = repoUrl(ref).newBuilder()
            .addPathSegment("commits")
            .addPathSegment(ref.branch) // 세그먼트 단위로 인코딩된다: feature/x → feature%2Fx
            .build()
        val body = request(url)
        return json.decodeFromString<CommitResponse>(body).sha
    }

    /** 설정 화면의 "연결 테스트". 저장소에 접근 가능한지만 본다. */
    suspend fun checkAccess(ref: RepoRef) {
        request(repoUrl(ref))
    }

    private fun repoUrl(ref: RepoRef): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("repos")
        .addPathSegment(ref.owner)
        .addPathSegment(ref.repo)
        .build()

    /** 요청 → 헤더 기록 → 상태 코드 분류. 성공이면 본문 문자열을 돌려준다. */
    private suspend fun request(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GithubException.Network(e)
        }

        response.use {
            recordHeaders(it)
            val body = it.body.string()
            if (it.isSuccessful) body else throw classify(it, body)
        }
    }

    private fun recordHeaders(response: Response) {
        val remaining = response.header("x-ratelimit-remaining")?.toIntOrNull()
        val limit = response.header("x-ratelimit-limit")?.toIntOrNull()
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
        if (remaining != null && limit != null && reset != null) {
            rateLimit = RateLimit(remaining, limit, Instant.ofEpochSecond(reset))
        }
        tokenExpiresAt = response.header("github-authentication-token-expiration")?.let(::parseExpiration)
    }

    private fun classify(response: Response, body: String): GithubException {
        val retryAfter = response.header("retry-after")?.toLongOrNull()
        val remaining = response.header("x-ratelimit-remaining")
        return when (val status = response.code) {
            429 -> GithubException.RateLimited(retryAfter)
            401 -> GithubException.Unauthorized(messageOf(body))
            // secondary rate limit 은 403 으로도 온다 — remaining 0 이거나 Retry-After 가 있으면 rate limit.
            403 -> if (remaining == "0" || retryAfter != null) {
                GithubException.RateLimited(retryAfter)
            } else {
                GithubException.Forbidden(messageOf(body))
            }
            404 -> GithubException.NotFound()
            else -> GithubException.Server(status)
        }
    }

    /** GitHub 오류 본문 {"message": "..."} 에서 메시지만 꺼낸다. 형식이 다르면 null. */
    private fun messageOf(body: String): String? =
        runCatching { json.decodeFromString<ErrorResponse>(body).message }.getOrNull()

    @Serializable
    private data class CommitResponse(val sha: String)

    @Serializable
    private data class ErrorResponse(val message: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** GitHub 은 "2026-10-29 12:00:00 UTC" 또는 "... +0900" 형식으로 준다. */
        val expirationFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

        fun parseExpiration(value: String): Instant? = runCatching {
            OffsetDateTime.parse(value.replace(" UTC", " +0000"), expirationFormat).toInstant()
        }.getOrNull()
    }
}
