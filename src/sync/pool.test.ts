import { runPool, type PoolOptions } from './pool'

const noSleep = async () => {}

function baseOptions(overrides: Partial<PoolOptions<string>> = {}): PoolOptions<string> {
  return {
    initialConcurrency: 5,
    maxConcurrency: 10,
    classify: () => ({ retry: false }),
    sleep: noSleep,
    ...overrides,
  }
}

describe('runPool', () => {
  it('모든 항목을 처리한다', async () => {
    const seen: string[] = []
    const result = await runPool(
      ['a', 'b', 'c', 'd', 'e', 'f'],
      async (item) => {
        seen.push(item)
      },
      baseOptions(),
    )
    expect(result.done).toBe(6)
    expect(result.failed).toHaveLength(0)
    expect(seen.sort()).toEqual(['a', 'b', 'c', 'd', 'e', 'f'])
  })

  it('동시 실행 수가 initialConcurrency 를 넘지 않는다', async () => {
    let active = 0
    let peak = 0
    await runPool(
      Array.from({ length: 20 }, (_, i) => `f${i}`),
      async () => {
        active += 1
        peak = Math.max(peak, active)
        await new Promise((r) => setTimeout(r, 5))
        active -= 1
      },
      baseOptions({ initialConcurrency: 3, maxConcurrency: 3 }),
    )
    expect(peak).toBeLessThanOrEqual(3)
  })

  it('재시도 오류가 나면 동시성을 절반으로 줄이고 같은 항목을 다시 시도한다', async () => {
    const concurrencyChanges: number[] = []
    let failedOnce = false
    const result = await runPool(
      Array.from({ length: 12 }, (_, i) => `f${i}`),
      async (item) => {
        if (item === 'f0' && !failedOnce) {
          failedOnce = true
          throw new Error('rate limited')
        }
      },
      baseOptions({
        initialConcurrency: 8,
        maxConcurrency: 8,
        classify: () => ({ retry: true, backoffMs: 1 }),
        onConcurrencyChange: (n) => concurrencyChanges.push(n),
      }),
    )
    // f0 은 재시도로 결국 성공한다
    expect(result.done).toBe(12)
    expect(result.failed).toHaveLength(0)
    // 8 → 4 로 축소됐다가, 성공이 이어지면 점진 복원(+1)된다
    expect(concurrencyChanges[0]).toBe(4)
    expect(concurrencyChanges).toContain(5)
  })

  it('전 슬롯이 축소로 빠져도 큐에 남은 항목이 버려지지 않는다', async () => {
    // 회귀 테스트: 재시도 항목을 unshift 한 슬롯 자신이 축소 검사로 종료하고
    // 나머지 슬롯도 이미 빠진 상태 — 드레인 루프가 다시 슬롯을 띄워야 한다.
    let failedOnce = false
    const result = await runPool(
      ['bad', 'ok1', 'ok2'],
      async (item) => {
        if (item === 'bad' && !failedOnce) {
          failedOnce = true
          throw new Error('transient')
        }
      },
      baseOptions({
        initialConcurrency: 5,
        maxConcurrency: 10,
        classify: () => ({ retry: true, backoffMs: 0 }),
      }),
    )
    expect(result.done).toBe(3)
    expect(result.failed).toHaveLength(0)
  })

  it('재시도 한도를 넘으면 failed 로 넘어가고 나머지는 계속된다', async () => {
    const result = await runPool(
      ['bad', 'ok1', 'ok2'],
      async (item) => {
        if (item === 'bad') throw new Error('always fails')
      },
      baseOptions({ classify: () => ({ retry: true, backoffMs: 0 }), maxRetries: 2 }),
    )
    expect(result.done).toBe(2)
    expect(result.failed).toHaveLength(1)
    expect(result.failed[0].item).toBe('bad')
  })

  it('재시도 불가 오류는 즉시 failed 로 간다 — 이미 받은 파일은 유지', async () => {
    const result = await runPool(
      ['a', 'b', 'c'],
      async (item) => {
        if (item === 'b') throw new Error('not found')
      },
      baseOptions(),
    )
    expect(result.done).toBe(2)
    expect(result.failed.map((f) => f.item)).toEqual(['b'])
  })

  it('취소되면 새 항목을 집지 않는다', async () => {
    let cancelled = false
    let processed = 0
    const result = await runPool(
      Array.from({ length: 50 }, (_, i) => `f${i}`),
      async () => {
        processed += 1
        if (processed === 5) cancelled = true
        await new Promise((r) => setTimeout(r, 1))
      },
      baseOptions({ initialConcurrency: 1, maxConcurrency: 1, isCancelled: () => cancelled }),
    )
    expect(result.cancelled).toBe(true)
    expect(result.done).toBeLessThan(50)
  })

  it('진행률을 보고한다', async () => {
    const progress: number[] = []
    await runPool(
      ['a', 'b', 'c'],
      async () => {},
      baseOptions({ onProgress: (done) => progress.push(done) }),
    )
    expect(progress).toEqual([1, 2, 3])
  })

  it('빈 목록이면 즉시 끝난다', async () => {
    const result = await runPool([], async () => {}, baseOptions())
    expect(result.done).toBe(0)
    expect(result.failed).toHaveLength(0)
  })

  it('백오프가 시도 횟수에 따라 지수로 커진다', async () => {
    const waits: number[] = []
    await runPool(
      ['x'],
      async () => {
        throw new Error('always')
      },
      baseOptions({
        classify: () => ({ retry: true, backoffMs: 100 }),
        maxRetries: 3,
        sleep: async (ms) => {
          waits.push(ms)
        },
      }),
    )
    expect(waits).toEqual([100, 200, 400])
  })
})
