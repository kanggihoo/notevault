import { Appearance } from 'react-native'
import { colorScheme as cssColorScheme } from 'react-native-css/native'
import { create } from 'zustand'
import { getDb } from '../db'
import { getMeta, setMeta } from '../db/queries'
import type { RepoRef } from '../sync/github'

/**
 * 저장소 설정. SQLite meta 테이블이 영속 저장소이고 이 스토어는 화면용
 * 캐시다 — 앱 시작 시 load() 로 한 번 올린다.
 */
export type ThemePreference = 'system' | 'light' | 'dark'

/**
 * 테마 적용은 두 곳에 해야 한다.
 *
 * 1. Appearance.setColorScheme — RN useColorScheme 소비자 (헤더, WebView theme)
 * 2. react-native-css 의 colorScheme.set — className 스타일 (light-dark 토큰)
 *
 * react-native-css 가 Appearance 변경 이벤트를 받지 못하는 것을 jest 재현으로
 * 확인했다 (preview 단계 버그로 추정). 2번 없이는 헤더만 바뀌고 본문 배경이
 * 라이트로 고정된다.
 */
function applyTheme(preference: ThemePreference): void {
  // RN 0.86: 'unspecified' 가 시스템 추종이다.
  Appearance.setColorScheme(preference === 'system' ? 'unspecified' : preference)
  cssColorScheme.set(
    preference === 'system'
      ? // 관찰값을 비우면 getter 가 Appearance(시스템) 값으로 폴백한다
        (Appearance.getColorScheme() ?? 'light')
      : preference,
  )
}

type SettingsState = {
  loaded: boolean
  repo: RepoRef | null
  autoSyncOnLaunch: boolean
  wifiOnly: boolean
  theme: ThemePreference
  load(): Promise<void>
  setRepo(ref: RepoRef): Promise<void>
  setAutoSync(on: boolean): Promise<void>
  setWifiOnly(on: boolean): Promise<void>
  setTheme(theme: ThemePreference): Promise<void>
}

export const useSettings = create<SettingsState>((set) => ({
  loaded: false,
  repo: null,
  autoSyncOnLaunch: true,
  wifiOnly: false,
  theme: 'system',

  async load() {
    const db = await getDb()
    const [owner, repo, branch, autoSync, wifiOnly, theme] = await Promise.all([
      getMeta(db, 'owner'),
      getMeta(db, 'repo'),
      getMeta(db, 'branch'),
      getMeta(db, 'auto_sync'),
      getMeta(db, 'wifi_only'),
      getMeta(db, 'theme'),
    ])
    const themePref: ThemePreference =
      theme === 'light' || theme === 'dark' ? theme : 'system'
    applyTheme(themePref)
    set({
      loaded: true,
      repo: owner && repo ? { owner, repo, branch: branch ?? 'main' } : null,
      autoSyncOnLaunch: autoSync !== '0',
      wifiOnly: wifiOnly === '1',
      theme: themePref,
    })
  },

  async setRepo(ref) {
    const db = await getDb()
    await Promise.all([
      setMeta(db, 'owner', ref.owner),
      setMeta(db, 'repo', ref.repo),
      setMeta(db, 'branch', ref.branch),
    ])
    set({ repo: ref })
  },

  async setAutoSync(on) {
    await setMeta(await getDb(), 'auto_sync', on ? '1' : '0')
    set({ autoSyncOnLaunch: on })
  },

  async setWifiOnly(on) {
    await setMeta(await getDb(), 'wifi_only', on ? '1' : '0')
    set({ wifiOnly: on })
  },

  async setTheme(theme) {
    applyTheme(theme)
    await setMeta(await getDb(), 'theme', theme)
    set({ theme })
  },
}))

// 시스템 추종 모드에서 기기 테마가 바뀌면 css 쪽에도 전달한다.
// react-native-css 자체 리스너가 동작하지 않으므로 우리가 다리를 놓는다.
Appearance.addChangeListener(({ colorScheme: scheme }) => {
  if (useSettings.getState().theme === 'system') {
    cssColorScheme.set(scheme ?? 'light')
  }
})
