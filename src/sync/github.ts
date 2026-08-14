import { GITHUB } from '../config'
import type { RemoteEntry } from './types'

/** 설계 문서 9장의 오류 처리 표에 대응하는 분류. */
export type GithubErrorKind =
  | 'auth' // 401·403 → 재연결 안내
  | 'rate-limit' // 429 또는 403+rate-limit 헤더 → 백오프
  | 'not-found' // 404 → owner/repo/branch 오설정
  | 'network' // fetch 실패, 타임아웃 → 오프라인 안내
  | 'server' // 5xx → 재시도

export class GithubError extends Error {
  constructor(
    readonly kind: GithubErrorKind,
    message: string,
    readonly status?: number,
    /** Retry-After 헤더(초). rate-limit 백오프에 쓴다. */
    readonly retryAfter?: number,
  ) {
    super(message)
    this.name = 'GithubError'
  }
}

export type RepoRef = { owner: string; repo: string; branch: string }

export type RateLimit = { remaining: number; limit: number; resetAt: number }

export type TreeResult = {
  entries: RemoteEntry[]
  /** 항목 10만 개 초과 시 true — 폴더별 조회 폴백이 필요하다. */
  truncated: boolean
}

type FetchLike = typeof fetch

/**
 * GitHub API 클라이언트.
 *
 * fetch 를 주입받는 이유: 테스트에서 401·429·타임아웃·부분 실패를
 * 주입하기 위해서다 (설계 문서 10.1절).
 */
export class GithubClient {
  /** 마지막 응답의 rate limit. 설정 화면에 표시한다. */
  rateLimit: RateLimit | null = null

  constructor(
    private readonly token: string,
    private readonly fetchImpl: FetchLike = fetch,
  ) {}

  private async request(url: string, accept: string): Promise<Response> {
    let response: Response
    try {
      response = await this.fetchImpl(url, {
        headers: {
          Authorization: `Bearer ${this.token}`,
          Accept: accept,
          'X-GitHub-Api-Version': '2022-11-28',
        },
      })
    } catch (error) {
      throw new GithubError('network', String(error))
    }

    const remaining = response.headers.get('x-ratelimit-remaining')
    if (remaining != null) {
      this.rateLimit = {
        remaining: Number(remaining),
        limit: Number(response.headers.get('x-ratelimit-limit') ?? 0),
        resetAt: Number(response.headers.get('x-ratelimit-reset') ?? 0) * 1000,
      }
    }

    if (response.ok) return response

    const retryAfter = Number(response.headers.get('retry-after') ?? 0) || undefined
    const status = response.status

    // secondary rate limit 은 403 으로도 온다 — remaining 0 이거나
    // Retry-After 가 있으면 인증 문제가 아니라 rate limit 이다.
    if (status === 429 || (status === 403 && (remaining === '0' || retryAfter))) {
      throw new GithubError('rate-limit', `rate limited (${status})`, status, retryAfter)
    }
    if (status === 401 || status === 403) {
      throw new GithubError('auth', `authentication failed (${status})`, status)
    }
    if (status === 404) {
      throw new GithubError('not-found', 'repository or ref not found', status)
    }
    if (status >= 500) {
      throw new GithubError('server', `server error (${status})`, status)
    }
    throw new GithubError('server', `unexpected status ${status}`, status)
  }

  /**
   * 1단계: HEAD 커밋 SHA. 응답 수 KB — 변경이 없으면 여기서 동기화가 끝난다.
   */
  async getHeadSha(ref: RepoRef): Promise<string> {
    const url = `${GITHUB.api}/repos/${ref.owner}/${ref.repo}/commits/${encodeURIComponent(ref.branch)}`
    const response = await this.request(url, 'application/vnd.github+json')
    const body = (await response.json()) as { sha: string }
    return body.sha
  }

  /**
   * 2단계: 전체 트리 1회 호출. blob 만 남긴다 (tree 항목은 폴더).
   */
  async getTree(ref: RepoRef, commitSha: string): Promise<TreeResult> {
    const url = `${GITHUB.api}/repos/${ref.owner}/${ref.repo}/git/trees/${commitSha}?recursive=1`
    const response = await this.request(url, 'application/vnd.github+json')
    const body = (await response.json()) as {
      truncated: boolean
      tree: { path: string; sha: string; size?: number; type: string }[]
    }
    return {
      truncated: body.truncated,
      entries: body.tree
        .filter((item) => item.type === 'blob')
        .map((item) => ({ path: item.path, sha: item.sha, size: item.size ?? 0 })),
    }
  }

  /**
   * 5단계: 파일 원문 다운로드.
   * ref 를 head SHA 로 고정해 동기화 도중 push 가 와도 일관된 스냅샷을 받는다.
   */
  async getRawFile(ref: RepoRef, path: string, commitSha: string): Promise<Uint8Array> {
    const encoded = path.split('/').map(encodeURIComponent).join('/')
    const url = `${GITHUB.api}/repos/${ref.owner}/${ref.repo}/contents/${encoded}?ref=${commitSha}`
    const response = await this.request(url, 'application/vnd.github.raw+json')
    return new Uint8Array(await response.arrayBuffer())
  }

  /** 설정 화면의 "연결 테스트". 저장소 접근 가능 여부만 본다. */
  async checkAccess(ref: RepoRef): Promise<void> {
    const url = `${GITHUB.api}/repos/${ref.owner}/${ref.repo}`
    await this.request(url, 'application/vnd.github+json')
  }
}
