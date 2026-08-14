import { GITHUB } from '../config'

/**
 * GitHub Device Flow. 설계 문서 3장 참조.
 *
 * client_secret 이 필요 없다 — client_id 와 device_code 만으로 토큰을
 * 교환한다. 휴대폰에서 40자 토큰을 타이핑하는 상황을 없애는 것이 목적이다.
 *
 * SecureStore 를 모른다 — 저장은 src/auth/index.ts 가 담당한다.
 * fetch 주입으로 폴링 응답을 테스트에서 제어한다.
 */

export type DeviceCode = {
  /** 사용자가 GitHub 페이지에 입력할 8자리 코드 (XXXX-XXXX). */
  userCode: string
  verificationUri: string
  /** 폴링에 쓰는 내부 코드. */
  deviceCode: string
  /** 코드 만료(초). */
  expiresIn: number
  /** 최소 폴링 간격(초). */
  interval: number
}

export type PollResult =
  | { status: 'authorized'; token: string }
  | { status: 'pending' }
  | { status: 'slow-down'; interval: number }
  | { status: 'expired' }
  | { status: 'denied' }

type FetchLike = typeof fetch

export async function requestDeviceCode(
  clientId: string,
  fetchImpl: FetchLike = fetch,
): Promise<DeviceCode> {
  const response = await fetchImpl(GITHUB.deviceCodeUrl, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ client_id: clientId, scope: GITHUB.scope }),
  })
  if (!response.ok) throw new Error(`device code request failed (${response.status})`)
  const body = (await response.json()) as {
    user_code: string
    verification_uri: string
    device_code: string
    expires_in: number
    interval: number
  }
  return {
    userCode: body.user_code,
    verificationUri: body.verification_uri,
    deviceCode: body.device_code,
    expiresIn: body.expires_in,
    interval: body.interval,
  }
}

/** 1회 폴링. 호출 측이 interval 간격으로 반복한다. */
export async function pollForToken(
  clientId: string,
  deviceCode: string,
  fetchImpl: FetchLike = fetch,
): Promise<PollResult> {
  const response = await fetchImpl(GITHUB.accessTokenUrl, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      client_id: clientId,
      device_code: deviceCode,
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
    }),
  })
  const body = (await response.json()) as {
    access_token?: string
    error?: string
    interval?: number
  }

  if (body.access_token) return { status: 'authorized', token: body.access_token }
  switch (body.error) {
    case 'authorization_pending':
      return { status: 'pending' }
    case 'slow_down':
      return { status: 'slow-down', interval: body.interval ?? 10 }
    case 'expired_token':
      return { status: 'expired' }
    case 'access_denied':
      return { status: 'denied' }
    default:
      throw new Error(`unexpected device flow error: ${body.error}`)
  }
}

/**
 * 승인될 때까지 폴링한다. GitHub 규칙:
 * - interval 미만 간격으로 치면 slow_down 이 오고, 그때는 알려준 간격으로 늘린다.
 * - expired 면 코드부터 다시 받아야 한다.
 */
export async function waitForAuthorization(
  clientId: string,
  code: DeviceCode,
  options: {
    fetchImpl?: FetchLike
    isCancelled?: () => boolean
    sleep?: (ms: number) => Promise<void>
  } = {},
): Promise<PollResult> {
  const fetchImpl = options.fetchImpl ?? fetch
  const sleep = options.sleep ?? ((ms) => new Promise<void>((r) => setTimeout(r, ms)))
  const isCancelled = options.isCancelled ?? (() => false)

  let interval = code.interval
  const deadline = Date.now() + code.expiresIn * 1000

  while (!isCancelled() && Date.now() < deadline) {
    await sleep(interval * 1000)
    if (isCancelled()) break

    const result = await pollForToken(clientId, code.deviceCode, fetchImpl)
    if (result.status === 'pending') continue
    if (result.status === 'slow-down') {
      interval = result.interval
      continue
    }
    return result
  }
  return { status: 'expired' }
}
