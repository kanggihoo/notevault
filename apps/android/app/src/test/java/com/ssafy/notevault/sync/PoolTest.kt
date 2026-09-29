package com.ssafy.notevault.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RN 판 src/sync/pool.test.ts 이식. runTest 안의 delay 는 가상 시간이라 즉시 끝난다. */
class PoolTest {

    private val noRetry: (Throwable) -> RetryDecision = { RetryDecision(retry = false) }
    private val noSleep: suspend (Long) -> Unit = {}

    @Test
    fun `모든 항목을 처리한다`() = runTest {
        val seen = mutableListOf<String>()
        val result = runPool(listOf("a", "b", "c", "d", "e", "f"), classify = noRetry, sleep = noSleep) { seen += it }

        assertEquals(6, result.done)
        assertEquals(emptyList(), result.failed)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), seen.sorted())
    }

    @Test
    fun `동시 실행 수가 initialConcurrency 를 넘지 않는다`() = runTest {
        var active = 0
        var peak = 0
        runPool((0 until 20).map { "f$it" }, initialConcurrency = 3, maxConcurrency = 3, classify = noRetry) {
            active += 1
            peak = maxOf(peak, active)
            delay(5)
            active -= 1
        }
        assertEquals(3, peak)
    }

    @Test
    fun `재시도 오류가 나면 동시성을 절반으로 줄이고 같은 항목을 다시 시도한다`() = runTest {
        val changes = mutableListOf<Int>()
        var failedOnce = false
        val result = runPool(
            (0 until 12).map { "f$it" },
            initialConcurrency = 8,
            maxConcurrency = 8,
            classify = { RetryDecision(retry = true, backoffMs = 1) },
            onConcurrencyChange = { changes += it },
            sleep = noSleep,
        ) { item ->
            if (item == "f0" && !failedOnce) {
                failedOnce = true
                error("rate limited")
            }
        }

        assertEquals(12, result.done) // f0 은 재시도로 결국 성공한다
        assertEquals(emptyList(), result.failed)
        assertEquals(4, changes.first()) // 8 → 4 로 축소
        assertTrue(5 in changes) // 성공이 이어지면 +1 씩 복원
    }

    @Test
    fun `재시도 한도를 넘으면 failed 로 넘어가고 나머지는 계속된다`() = runTest {
        val result = runPool(
            listOf("bad", "ok1", "ok2"),
            maxRetries = 2,
            classify = { RetryDecision(retry = true, backoffMs = 0) },
            sleep = noSleep,
        ) { if (it == "bad") error("always fails") }

        assertEquals(2, result.done)
        assertEquals(listOf("bad"), result.failed.map { it.item })
    }

    @Test
    fun `재시도 불가 오류는 즉시 failed 로 간다`() = runTest {
        var attempts = 0
        val result = runPool(listOf("a", "b", "c"), classify = noRetry, sleep = noSleep) {
            if (it == "b") {
                attempts += 1
                error("not found")
            }
        }
        assertEquals(2, result.done)
        assertEquals(listOf("b"), result.failed.map { it.item })
        assertEquals(1, attempts)
    }

    @Test
    fun `취소되면 새 항목을 집지 않는다`() = runTest {
        var cancelled = false
        var processed = 0
        val result = runPool(
            (0 until 50).map { "f$it" },
            initialConcurrency = 1,
            maxConcurrency = 1,
            classify = noRetry,
            isCancelled = { cancelled },
        ) {
            processed += 1
            if (processed == 5) cancelled = true
            delay(1)
        }
        assertTrue(result.cancelled)
        assertEquals(5, result.done)
    }

    @Test
    fun `진행률을 보고한다`() = runTest {
        val progress = mutableListOf<Int>()
        runPool(listOf("a", "b", "c"), classify = noRetry, onProgress = { done, _, _ -> progress += done }) {}
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test
    fun `빈 목록이면 즉시 끝난다`() = runTest {
        val result = runPool(emptyList<String>(), classify = noRetry) {}
        assertEquals(0, result.done)
        assertEquals(emptyList(), result.failed)
    }

    @Test
    fun `백오프가 시도 횟수에 따라 지수로 커진다`() = runTest {
        val waits = mutableListOf<Long>()
        runPool(
            listOf("x"),
            maxRetries = 3,
            classify = { RetryDecision(retry = true, backoffMs = 100) },
            sleep = { waits += it },
        ) { error("always") }
        assertEquals(listOf(100L, 200L, 400L), waits)
    }
}
