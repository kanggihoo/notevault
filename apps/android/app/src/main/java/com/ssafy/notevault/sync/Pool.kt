package com.ssafy.notevault.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 오류를 보고 정한 재시도 여부. [backoffMs] 는 지수 백오프의 기준값이다. */
data class RetryDecision(val retry: Boolean, val backoffMs: Long = 1000)

data class PoolFailure<T>(val item: T, val error: Throwable)

data class PoolResult<T>(val done: Int, val failed: List<PoolFailure<T>>, val cancelled: Boolean)

/** 성공이 이 횟수만큼 이어질 때마다 동시성을 1 올린다. */
const val RESTORE_AFTER = 5

/**
 * 적응형 동시성 풀 (RN 판 src/sync/pool.ts 이식).
 *
 * - [initialConcurrency] 개씩 병렬로 돌린다.
 * - 재시도 대상 오류(429·네트워크)가 나면 동시성을 절반으로 줄이고, 지수 백오프 후 같은 항목을 다시 시도한다.
 * - 성공이 [RESTORE_AFTER] 번 이어지면 동시성을 1 올린다 ([maxConcurrency] 까지).
 *
 * RN 판은 "슬롯" 을 직접 관리했지만, 코루틴은 가벼워서 항목마다 하나씩 띄우고
 * [AdaptiveLimiter] 로 동시에 도는 개수만 제한한다 — 스레드였다면 수천 개를 띄울 수 없다.
 */
suspend fun <T> runPool(
    items: List<T>,
    initialConcurrency: Int = 5,
    maxConcurrency: Int = 10,
    maxRetries: Int = 3,
    classify: (Throwable) -> RetryDecision,
    isCancelled: () -> Boolean = { false },
    onProgress: (done: Int, total: Int, item: T) -> Unit = { _, _, _ -> },
    onConcurrencyChange: (Int) -> Unit = {},
    sleep: suspend (Long) -> Unit = { delay(it) },
    worker: suspend (T) -> Unit,
): PoolResult<T> {
    val limiter = AdaptiveLimiter(initialConcurrency, maxConcurrency, onConcurrencyChange)
    val stats = Mutex()
    var done = 0
    var successStreak = 0
    val failed = mutableListOf<PoolFailure<T>>()

    suspend fun process(item: T) {
        var attempt = 0
        while (true) {
            val result = limiter.withPermit {
                if (isCancelled()) return // 취소되면 새 항목을 집지 않는다
                runCatching { worker(item) }
            }

            val error = result.exceptionOrNull()
            if (error == null) {
                val (doneNow, restore) = stats.withLock {
                    done += 1
                    successStreak += 1
                    val restore = successStreak >= RESTORE_AFTER
                    if (restore) successStreak = 0
                    done to restore
                }
                if (restore) limiter.grow()
                onProgress(doneNow, items.size, item)
                return
            }

            // runCatching 은 코루틴 취소 신호까지 삼킨다 — 다시 던져야 취소가 전파된다.
            if (error is CancellationException) throw error

            stats.withLock { successStreak = 0 }
            val decision = classify(error)
            if (decision.retry && attempt < maxRetries) {
                limiter.halve()
                sleep(decision.backoffMs shl attempt) // backoff × 2^attempt
                attempt += 1
            } else {
                stats.withLock { failed += PoolFailure(item, error) }
                return
            }
        }
    }

    coroutineScope {
        items.forEach { item -> launch { process(item) } }
    } // coroutineScope 는 안에서 띄운 코루틴이 전부 끝날 때까지 기다린다

    return PoolResult(done, failed.toList(), cancelled = isCancelled())
}

/**
 * 동시에 도는 개수의 상한을 실행 중에 바꿀 수 있는 세마포어.
 * kotlinx 의 Semaphore 는 허용 개수가 고정이라 직접 만든다.
 */
private class AdaptiveLimiter(
    initial: Int,
    private val max: Int,
    private val onChange: (Int) -> Unit,
) {
    private val mutex = Mutex()
    private var limit = initial.coerceIn(1, max)
    private var inUse = 0

    /** 자리가 날 수 있는 사건(반납·상한 변경)마다 1 씩 오른다. 기다리는 쪽이 이것을 지켜본다. */
    private val changes = MutableStateFlow(0L)

    suspend inline fun <R> withPermit(block: () -> R): R {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    suspend fun acquire() {
        while (true) {
            val seen = changes.value // 확인 전에 읽어야 그 사이의 반납을 놓치지 않는다
            val acquired = mutex.withLock { (inUse < limit).also { if (it) inUse += 1 } }
            if (acquired) return
            changes.first { it != seen }
        }
    }

    suspend fun release() {
        mutex.withLock { inUse -= 1 }
        changes.update { it + 1 }
    }

    suspend fun halve() = setLimit { maxOf(1, it / 2) }

    suspend fun grow() = setLimit { minOf(max, it + 1) }

    private suspend fun setLimit(next: (Int) -> Int) {
        val changed = mutex.withLock {
            val new = next(limit)
            (new != limit).also { if (it) limit = new }
        }
        if (changed) {
            onChange(limit)
            changes.update { it + 1 }
        }
    }
}
