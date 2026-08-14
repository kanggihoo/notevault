import '../../global.css'

import { useEffect } from 'react'
import { Drawer } from 'expo-router/drawer'
import { GestureHandlerRootView } from 'react-native-gesture-handler'
import { StatusBar } from 'expo-status-bar'
import { useColorScheme } from 'react-native'

import { DrawerContent } from '@/components/DrawerContent'
import { useSettings } from '@/store/settings'
import { useSync } from '@/store/sync'

export { ErrorBoundary } from '@/components/ErrorBoundary'

export default function RootLayout() {
  const dark = useColorScheme() === 'dark'
  const settings = useSettings()
  const sync = useSync((s) => s.sync)

  // 앱 실행 시: 설정 로드 → 자동 동기화 (설계 문서 9장).
  // 자동 동기화의 401 은 배너로만 표시되고 읽기를 막지 않는다.
  useEffect(() => {
    void settings.load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (settings.loaded && settings.autoSyncOnLaunch && settings.repo) {
      void sync(settings.repo, { manual: false })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings.loaded])

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <StatusBar style={dark ? 'light' : 'dark'} />
      <Drawer
        drawerContent={() => <DrawerContent />}
        screenOptions={{
          drawerType: 'slide',
          drawerStyle: { width: 300, backgroundColor: dark ? '#262626' : '#F7F7F7' },
          headerStyle: { backgroundColor: dark ? '#1E1E1E' : '#FFFFFF' },
          headerTintColor: dark ? '#DCDDDE' : '#1E1E1E',
          headerShadowVisible: false,
        }}
      >
        <Drawer.Screen name="index" options={{ title: 'NoteVault' }} />
        <Drawer.Screen name="note/[...path]" options={{ title: '' }} />
        <Drawer.Screen name="auth" options={{ title: 'GitHub 연결' }} />
        <Drawer.Screen name="settings/index" options={{ title: '설정' }} />
        <Drawer.Screen name="settings/subscriptions" options={{ title: '구독 관리' }} />
      </Drawer>
    </GestureHandlerRootView>
  )
}
