import Constants from 'expo-constants'

/**
 * 환경 설정 접근점.
 *
 * app.config.ts 의 extra 로 주입된 값을 읽는다. process.env 를 앱 코드에서
 * 직접 읽지 않는 이유는 RN 번들에서는 빌드 시점에 치환되어 런타임 값이
 * 아니기 때문이다. extra 를 거치면 EAS 빌드와 로컬 dev 가 같은 경로를 쓴다.
 */
const extra = (Constants.expoConfig?.extra ?? {}) as { githubClientId?: string }

/**
 * GitHub OAuth App 의 client_id.
 *
 * 비밀값이 아니다. Device Flow 는 client_secret 을 요구하지 않으므로
 * 이 값만으로 토큰을 교환한다. 설계 문서 3장 참조.
 */
export const GITHUB_CLIENT_ID = extra.githubClientId ?? ''

/** 인증을 시도할 수 있는 상태인지. 미설정이면 설정 화면에서 안내한다. */
export const hasGithubClientId = GITHUB_CLIENT_ID.length > 0

/** GitHub API 상수. */
export const GITHUB = {
  api: 'https://api.github.com',
  deviceCodeUrl: 'https://github.com/login/device/code',
  accessTokenUrl: 'https://github.com/login/oauth/access_token',
  /** private 저장소 읽기에 필요한 최소 scope. */
  scope: 'repo',
} as const

/** 동기화 기본값. 설정 화면에서 변경 가능하다. */
export const SYNC_DEFAULTS = {
  /** 기본 동시 다운로드 수. 429·타임아웃 시 절반으로 줄인다. */
  concurrency: 5,
  /** 적응형 조절의 상한. */
  maxConcurrency: 10,
  /** blob API 파일당 제한. 초과 파일은 건너뛴다. */
  maxFileBytes: 100 * 1024 * 1024,
  autoSyncOnLaunch: true,
  wifiOnly: false,
} as const
