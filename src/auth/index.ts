import * as SecureStore from 'expo-secure-store'

/**
 * 인증 접근점. 설계 문서 3장 "격리" 참조.
 *
 * 앱의 다른 코드는 토큰의 출처를 알지 못한다 — 이 두 함수만 쓴다.
 * 향후 PKCE 나 백엔드 프록시로 전환해도 이 파일 내부만 바뀐다.
 * Device Flow 진행 자체는 인증 화면이 deviceFlow.ts 를 직접 쓴다.
 */

const TOKEN_KEY = 'github_token'

export async function getToken(): Promise<string | null> {
  return SecureStore.getItemAsync(TOKEN_KEY)
}

export async function saveToken(token: string): Promise<void> {
  await SecureStore.setItemAsync(TOKEN_KEY, token)
}

export async function signOut(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKEN_KEY)
}
