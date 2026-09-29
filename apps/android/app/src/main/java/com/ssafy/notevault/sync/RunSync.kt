package com.ssafy.notevault.sync

import com.ssafy.notevault.db.FileEntity
import com.ssafy.notevault.db.MetaKeys
import com.ssafy.notevault.db.VaultDao
import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.github.GithubException
import com.ssafy.notevault.github.RepoRef
import kotlinx.coroutines.delay

sealed interface SyncProgress {
    data object Checking : SyncProgress
    data object Planning : SyncProgress
    data class Downloading(val done: Int, val total: Int, val currentPath: String) : SyncProgress
    data object Deleting : SyncProgress
}

data class FailedFile(val path: String, val error: Throwable)

sealed interface SyncOutcome {
    data object UpToDate : SyncOutcome

    data class Synced(
        val downloaded: Int,
        val deleted: Int,
        /** 남은 실패. 있으면 완료 마커를 올리지 않았다 — 다음 동기화가 이어받는다. */
        val failed: List<FailedFile>,
        val cancelled: Boolean,
    ) : SyncOutcome
}

/** 재시도 분류. 인증·404 는 다시 해도 소용없으므로 즉시 실패로 보낸다. */
fun classifyForRetry(error: Throwable): RetryDecision = when (error) {
    is GithubException.RateLimited -> RetryDecision(retry = true, backoffMs = (error.retryAfterSeconds ?: 2) * 1000)
    is GithubException.Network, is GithubException.Server -> RetryDecision(retry = true, backoffMs = 1000)
    else -> RetryDecision(retry = false)
}

/**
 * 동기화 실행 (RN 판 src/sync/runSync.ts 이식). 계산은 [planSync] 가 하고, 여기는 순서와 오류 처리만 안다.
 *
 * 1) HEAD 확인 → 같으면 종료   2) 트리 1회   3) planSync
 * 4) 다운로드: 파일 하나 끝날 때마다 DB 기록   5) 삭제   6) 전부 성공했을 때만 완료 마커 갱신
 */
suspend fun runSync(
    client: GithubClient,
    ref: RepoRef,
    dao: VaultDao,
    files: VaultFiles,
    concurrency: Int = 5,
    maxConcurrency: Int = 10,
    isCancelled: () -> Boolean = { false },
    onProgress: (SyncProgress) -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): SyncOutcome {
    // 1) HEAD 확인 — 변경 없으면 요청 한 번으로 끝난다
    onProgress(SyncProgress.Checking)
    val headSha = client.getHeadSha(ref)
    if (headSha == dao.getMeta(MetaKeys.LAST_COMMIT_SHA)) {
        dao.setMeta(MetaKeys.LAST_SYNCED_AT, now().toString())
        return SyncOutcome.UpToDate
    }

    // 2) 트리 1회 → 3) 순수 계산
    onProgress(SyncProgress.Planning)
    val tree = client.getTree(ref, headSha)
    // 10만 항목 초과는 현재 볼트(약 4천 개)에서 생기지 않는다. 생기면 폴더별 조회가 필요하다.
    check(!tree.truncated) { "tree truncated — folder-by-folder fallback not implemented" }
    val plan = planSync(tree.entries, dao.getLocalFiles(), dao.getSubscriptions())

    // 4) 다운로드 — 임시 파일 → 이름 변경, 완료 즉시 기록 (중단 복구의 근거)
    val pool = runPool(
        plan.download,
        initialConcurrency = concurrency,
        maxConcurrency = maxConcurrency,
        classify = ::classifyForRetry,
        isCancelled = isCancelled,
        onProgress = { done, total, entry -> onProgress(SyncProgress.Downloading(done, total, entry.path)) },
        sleep = sleep,
    ) { entry ->
        val data = client.getRawFile(ref, entry.path, headSha)
        files.writeAtomic(entry.path, data)
        dao.upsertFile(FileEntity(entry.path, entry.sha, entry.size, downloadedAt = now()))
    }

    // 5) 삭제 → 6) 완료 마커. 취소됐으면 둘 다 건너뛴다.
    var deleted = 0
    if (!pool.cancelled) {
        onProgress(SyncProgress.Deleting)
        plan.delete.forEach { path ->
            files.delete(path)
            deleted += 1
        }
        dao.deleteFiles(plan.delete)

        // "이 커밋까지 완전히 동기화됨". 부분 실패면 갱신하지 않는다 —
        // 다음 동기화가 SHA 불일치를 감지하고, 받은 파일은 blob SHA 로 건너뛴다.
        if (pool.failed.isEmpty()) dao.setMeta(MetaKeys.LAST_COMMIT_SHA, headSha)
    }
    dao.setMeta(MetaKeys.LAST_SYNCED_AT, now().toString())

    return SyncOutcome.Synced(
        downloaded = pool.done,
        deleted = deleted,
        failed = pool.failed.map { FailedFile(it.item.path, it.error) },
        cancelled = pool.cancelled,
    )
}
