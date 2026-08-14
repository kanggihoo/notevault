import { pollForToken, requestDeviceCode, waitForAuthorization } from './deviceFlow'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200 })
}

function fetchQueue(...responses: Response[]): typeof fetch {
  const queue = [...responses]
  return (async () => {
    const next = queue.shift()
    if (!next) throw new Error('no more mocked responses')
    return next
  }) as unknown as typeof fetch
}

const CODE = {
  userCode: 'ABCD-1234',
  verificationUri: 'https://github.com/login/device',
  deviceCode: 'dev123',
  expiresIn: 900,
  interval: 5,
}

const noSleep = async () => {}

describe('requestDeviceCode', () => {
  it('응답을 camelCase 로 매핑한다', async () => {
    const code = await requestDeviceCode(
      'client1',
      fetchQueue(
        jsonResponse({
          user_code: 'ABCD-1234',
          verification_uri: 'https://github.com/login/device',
          device_code: 'dev123',
          expires_in: 900,
          interval: 5,
        }),
      ),
    )
    expect(code).toEqual(CODE)
  })
})

describe('pollForToken', () => {
  it.each([
    ['authorization_pending', 'pending'],
    ['expired_token', 'expired'],
    ['access_denied', 'denied'],
  ])('%s → %s', async (error, status) => {
    const result = await pollForToken('c', 'd', fetchQueue(jsonResponse({ error })))
    expect(result.status).toBe(status)
  })

  it('토큰이 오면 authorized', async () => {
    const result = await pollForToken('c', 'd', fetchQueue(jsonResponse({ access_token: 'tok' })))
    expect(result).toEqual({ status: 'authorized', token: 'tok' })
  })
})

describe('waitForAuthorization', () => {
  it('pending 을 지나 승인되면 토큰을 돌려준다', async () => {
    const result = await waitForAuthorization('c', CODE, {
      fetchImpl: fetchQueue(
        jsonResponse({ error: 'authorization_pending' }),
        jsonResponse({ error: 'authorization_pending' }),
        jsonResponse({ access_token: 'tok' }),
      ),
      sleep: noSleep,
    })
    expect(result).toEqual({ status: 'authorized', token: 'tok' })
  })

  it('slow_down 이 오면 알려준 간격으로 폴링 간격을 늘린다', async () => {
    const waits: number[] = []
    const result = await waitForAuthorization('c', CODE, {
      fetchImpl: fetchQueue(
        jsonResponse({ error: 'slow_down', interval: 10 }),
        jsonResponse({ access_token: 'tok' }),
      ),
      sleep: async (ms) => {
        waits.push(ms)
      },
    })
    expect(result.status).toBe('authorized')
    expect(waits).toEqual([5000, 10000]) // 기본 5초 → slow_down 후 10초
  })

  it('취소되면 폴링을 멈춘다', async () => {
    let calls = 0
    const result = await waitForAuthorization('c', CODE, {
      fetchImpl: (async () => {
        calls += 1
        return jsonResponse({ error: 'authorization_pending' })
      }) as unknown as typeof fetch,
      isCancelled: () => calls >= 2,
      sleep: noSleep,
    })
    expect(result.status).toBe('expired')
    expect(calls).toBe(2)
  })

  it('사용자가 거부하면 denied 로 끝난다', async () => {
    const result = await waitForAuthorization('c', CODE, {
      fetchImpl: fetchQueue(jsonResponse({ error: 'access_denied' })),
      sleep: noSleep,
    })
    expect(result.status).toBe('denied')
  })
})
