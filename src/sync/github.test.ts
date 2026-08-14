import { GithubClient, GithubError } from './github'

const REF = { owner: 'kkh', repo: 'obsidian', branch: 'main' }

function response(
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), { status, headers })
}

function clientWith(...responses: Response[]): GithubClient {
  const queue = [...responses]
  const fetchMock = jest.fn(async () => {
    const next = queue.shift()
    if (!next) throw new Error('no more mocked responses')
    return next
  })
  return new GithubClient('token', fetchMock as unknown as typeof fetch)
}

async function kindOf(promise: Promise<unknown>): Promise<string> {
  try {
    await promise
    return 'ok'
  } catch (error) {
    if (error instanceof GithubError) return error.kind
    throw error
  }
}

describe('GithubClient 오류 분류 (설계 문서 9장 표)', () => {
  it('401 → auth', async () => {
    const client = clientWith(response(401, {}))
    expect(await kindOf(client.getHeadSha(REF))).toBe('auth')
  })

  it('403 (rate limit 헤더 없음) → auth', async () => {
    const client = clientWith(response(403, {}, { 'x-ratelimit-remaining': '4999' }))
    expect(await kindOf(client.getHeadSha(REF))).toBe('auth')
  })

  it('403 + remaining 0 → rate-limit', async () => {
    const client = clientWith(response(403, {}, { 'x-ratelimit-remaining': '0' }))
    expect(await kindOf(client.getHeadSha(REF))).toBe('rate-limit')
  })

  it('429 → rate-limit, Retry-After 를 읽는다', async () => {
    const client = clientWith(response(429, {}, { 'retry-after': '30' }))
    try {
      await client.getHeadSha(REF)
      fail('should throw')
    } catch (error) {
      expect((error as GithubError).kind).toBe('rate-limit')
      expect((error as GithubError).retryAfter).toBe(30)
    }
  })

  it('404 → not-found', async () => {
    const client = clientWith(response(404, {}))
    expect(await kindOf(client.getHeadSha(REF))).toBe('not-found')
  })

  it('fetch 실패(타임아웃·오프라인) → network', async () => {
    const fetchMock = jest.fn(async () => {
      throw new TypeError('Network request failed')
    })
    const client = new GithubClient('t', fetchMock as unknown as typeof fetch)
    expect(await kindOf(client.getHeadSha(REF))).toBe('network')
  })

  it('500 → server', async () => {
    const client = clientWith(response(500, {}))
    expect(await kindOf(client.getHeadSha(REF))).toBe('server')
  })
})

describe('GithubClient 정상 경로', () => {
  it('HEAD SHA 를 반환한다', async () => {
    const client = clientWith(response(200, { sha: 'abc123' }))
    expect(await client.getHeadSha(REF)).toBe('abc123')
  })

  it('트리에서 blob 만 남기고 truncated 를 전달한다', async () => {
    const client = clientWith(
      response(200, {
        truncated: false,
        tree: [
          { path: 'Dev', sha: 'd1', type: 'tree' },
          { path: 'Dev/a.md', sha: 'f1', size: 10, type: 'blob' },
          { path: 'Dev/attachments/i.png', sha: 'f2', size: 999, type: 'blob' },
        ],
      }),
    )
    const result = await client.getTree(REF, 'head')
    expect(result.truncated).toBe(false)
    expect(result.entries).toEqual([
      { path: 'Dev/a.md', sha: 'f1', size: 10 },
      { path: 'Dev/attachments/i.png', sha: 'f2', size: 999 },
    ])
  })

  it('rate limit 헤더를 파싱해 노출한다 (설정 화면 표시용)', async () => {
    const client = clientWith(
      response(
        200,
        { sha: 'x' },
        { 'x-ratelimit-remaining': '4980', 'x-ratelimit-limit': '5000' },
      ),
    )
    await client.getHeadSha(REF)
    expect(client.rateLimit?.remaining).toBe(4980)
    expect(client.rateLimit?.limit).toBe(5000)
  })

  it('파일 경로의 특수문자·한글을 세그먼트별로 인코딩한다', async () => {
    const calls: string[] = []
    const fetchMock = jest.fn(async (url: string) => {
      calls.push(url)
      return new Response(new ArrayBuffer(4), { status: 200 })
    })
    const client = new GithubClient('t', fetchMock as unknown as typeof fetch)
    await client.getRawFile(REF, 'C++(알고리즘)/자료구조.md', 'head')
    expect(calls[0]).toContain(encodeURIComponent('C++(알고리즘)'))
    expect(calls[0]).toContain(encodeURIComponent('자료구조.md'))
    // 경로 구분자는 살아있어야 한다
    expect(calls[0]).toContain(`${encodeURIComponent('C++(알고리즘)')}/${encodeURIComponent('자료구조.md')}`)
  })
})
