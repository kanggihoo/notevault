import { fetch as expoFetch } from 'expo/fetch'

/**
 * 앱에서 쓰는 fetch. expo-data-fetching skill 의 권장에 따라 expo/fetch 를 쓴다
 * (WinterCG 호환, 스트리밍 지원).
 *
 * GithubClient 는 fetch 를 주입받으므로 테스트는 이 모듈을 거치지 않는다 —
 * jest 환경에서 expo/fetch 네이티브 모듈을 불러올 필요가 없다.
 */
export const appFetch = expoFetch as unknown as typeof globalThis.fetch
