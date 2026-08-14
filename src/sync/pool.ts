/**
 * 적응형 동시성 풀. 설계 문서 6장 "동시성" 참조.
 *
 * - 기본 N 개 병렬로 시작하되 고정하지 않는다.
 * - 재시도 대상 오류(429·네트워크)가 나면 동시성을 절반으로 줄이고
 *   지수 백오프 후 같은 항목을 다시 시도한다.
 * - 성공이 이어지면 점진적으로 복원한다.
 *
 * GitHub 를 모르는 순수 로직이다 — 분류는 classify 로 주입받는다.
 */

export type RetryDecision = {
  retry: boolean
  /** 재시도 전 대기(ms). 지수 백오프의 기준값. */
  backoffMs?: number
}

export type PoolOptions<T> = {
  initialConcurrency: number
  maxConcurrency: number
  /** 항목당 최대 재시도 횟수. 초과하면 failed 로 넘어간다. */
  maxRetries?: number
  /** 오류를 보고 재시도 여부를 정한다. */
  classify: (error: unknown) => RetryDecision
  /** 취소 신호. true 가 되면 새 항목을 집지 않는다. */
  isCancelled?: () => boolean
  onProgress?: (done: number, total: number, item: T) => void
  /** 동시성이 조절될 때마다 호출된다. UI 표시와 테스트 관찰용. */
  onConcurrencyChange?: (concurrency: number) => void
  /** 테스트에서 대기를 건너뛰기 위해 주입 가능. */
  sleep?: (ms: number) => Promise<void>
}

export type PoolResult<T> = {
  done: number
  failed: { item: T; error: unknown }[]
  cancelled: boolean
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** 성공이 이 횟수만큼 이어질 때마다 동시성을 1 올린다. */
export const RESTORE_AFTER = 5

export async function runPool<T>(
  items: readonly T[],
  worker: (item: T) => Promise<void>,
  options: PoolOptions<T>,
): Promise<PoolResult<T>> {
  const sleep = options.sleep ?? defaultSleep
  const maxRetries = options.maxRetries ?? 3
  const isCancelled = options.isCancelled ?? (() => false)

  // 재시도 항목은 큐 앞에 다시 넣는다.
  const queue: { item: T; attempts: number }[] = items.map((item) => ({ item, attempts: 0 }))
  const failed: { item: T; error: unknown }[] = []

  let concurrency = Math.max(1, options.initialConcurrency)
  let successStreak = 0
  let done = 0
  // 살아있는 슬롯 수. 동시성이 줄면 초과분이 스스로 종료해 이 값이 따라온다.
  let slots = 0
  let running: Promise<void>[] = []

  function setConcurrency(next: number): void {
    if (next === concurrency) return
    concurrency = next
    options.onConcurrencyChange?.(next)
  }

  async function slotBody(): Promise<void> {
    while (true) {
      // 동시성이 줄었으면 초과분 슬롯은 스스로 종료한다. 백오프 대기를 마친
      // 슬롯이 재시도 항목을 남기고 나가는 경우가 있으므로, 큐가 비었는지는
      // 여기서 보장하지 않는다 — 바깥의 드레인 루프가 다시 슬롯을 띄운다.
      if (slots > concurrency) return
      if (isCancelled()) return

      const next = queue.shift()
      if (!next) return

      try {
        await worker(next.item)
        done += 1
        successStreak += 1
        options.onProgress?.(done, items.length, next.item)

        // 안정적이면 점진 복원 — 슬롯을 하나 더 띄운다.
        if (successStreak >= RESTORE_AFTER && concurrency < options.maxConcurrency) {
          successStreak = 0
          setConcurrency(concurrency + 1)
          if (queue.length > 0) spawn()
        }
      } catch (error) {
        const decision = options.classify(error)
        successStreak = 0

        if (decision.retry && next.attempts < maxRetries) {
          // 절반으로 축소 — 초과 슬롯은 위의 검사에서 자연히 빠진다.
          setConcurrency(Math.max(1, Math.floor(concurrency / 2)))
          const backoff = (decision.backoffMs ?? 1000) * 2 ** next.attempts
          await sleep(backoff)
          queue.unshift({ item: next.item, attempts: next.attempts + 1 })
        } else {
          failed.push({ item: next.item, error })
        }
      }
    }
  }

  function spawn(): void {
    slots += 1
    running.push(
      slotBody().finally(() => {
        slots -= 1
      }),
    )
  }

  // 드레인 루프: 슬롯 종료 경합으로 큐에 항목이 남을 수 있으므로,
  // 큐가 빌 때까지(또는 취소될 때까지) 슬롯을 다시 띄운다.
  while (queue.length > 0 && !isCancelled()) {
    const count = Math.min(concurrency, queue.length) - slots
    for (let i = 0; i < count; i++) spawn()

    while (slots > 0) await Promise.all([...running])
    running = []
  }

  return { done, failed, cancelled: isCancelled() }
}
