import { useCallback, useRef, useState } from 'react'
import { ActivityIndicator } from 'react-native'
import { router } from 'expo-router'
import * as Clipboard from 'expo-clipboard'
import * as Linking from 'expo-linking'
import * as WebBrowser from 'expo-web-browser'
import { Copy, ExternalLink } from 'lucide-react-native'

import { Pressable, Text, View } from '@/tw'
import { GITHUB_CLIENT_ID, hasGithubClientId } from '@/config'
import { saveToken } from '@/auth'
import {
  requestDeviceCode,
  waitForAuthorization,
  type DeviceCode,
} from '@/auth/deviceFlow'

/**
 * Device Flow 인증 화면. 설계 문서 7장.
 * 코드 표시 → 복사 → 브라우저 열기 → 승인 폴링 → 자동 진행.
 */
type Phase =
  | { kind: 'idle' }
  | { kind: 'requesting' }
  | { kind: 'waiting'; code: DeviceCode }
  | { kind: 'error'; message: string }

export default function AuthScreen() {
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })
  const cancelled = useRef(false)

  const start = useCallback(async () => {
    cancelled.current = false
    setPhase({ kind: 'requesting' })
    try {
      const code = await requestDeviceCode(GITHUB_CLIENT_ID)
      setPhase({ kind: 'waiting', code })

      const result = await waitForAuthorization(GITHUB_CLIENT_ID, code, {
        isCancelled: () => cancelled.current,
      })
      if (cancelled.current) return

      if (result.status === 'authorized') {
        await saveToken(result.token)
        // Device Flow 는 리다이렉트가 없어 GitHub 이 앱을 다시 열어주지 않는다.
        // 승인을 감지한 쪽(우리)이 브라우저를 닫고 앱을 전면으로 가져온다.
        try {
          WebBrowser.dismissBrowser() // iOS 전용 — Android 에서는 무해하게 무시됨
        } catch {
          /* noop */
        }
        try {
          // 자기 자신으로의 딥링크가 앱을 전면으로 올린다.
          // Expo Go 에서는 exp://, dev build 에서는 notevault:// 로 해석된다.
          await Linking.openURL(Linking.createURL('/'))
        } catch {
          /* 실패해도 토큰은 저장됨 — 사용자가 수동 복귀하면 연결 상태 */
        }
        router.back()
      } else if (result.status === 'denied') {
        setPhase({ kind: 'error', message: '승인이 거부되었습니다.' })
      } else {
        setPhase({ kind: 'error', message: '코드가 만료되었습니다. 다시 시도하세요.' })
      }
    } catch (error) {
      setPhase({ kind: 'error', message: String(error) })
    }
  }, [])

  if (!hasGithubClientId) {
    return (
      <View className="flex-1 items-center justify-center gap-3 bg-background px-8">
        <Text className="text-h3 font-semibold text-foreground">GITHUB_CLIENT_ID 미설정</Text>
        <Text className="text-center text-meta text-muted-foreground">
          .env 에 GITHUB_CLIENT_ID 를 넣고 개발 서버를 다시 시작하세요.
        </Text>
      </View>
    )
  }

  return (
    <View className="flex-1 items-center justify-center gap-5 bg-background px-8">
      {phase.kind === 'idle' && (
        <>
          <Text className="text-center text-meta text-muted-foreground">
            GitHub 에서 8자리 코드를 입력해 이 앱을 승인합니다.{'\n'}
            필요한 권한은 private 저장소 읽기(repo)뿐입니다.
          </Text>
          <Pressable
            onPress={start}
            className="h-11 items-center justify-center rounded-md bg-primary px-6 active:bg-primary-pressed"
          >
            <Text className="text-row font-semibold text-primary-foreground">인증 시작</Text>
          </Pressable>
        </>
      )}

      {phase.kind === 'requesting' && <ActivityIndicator />}

      {phase.kind === 'waiting' && (
        <>
          <View className="items-center gap-4 rounded-lg border border-border bg-card px-8 py-6">
            {/* 다른 기기로 옮겨 적는 유일한 지점 — 자간을 넓힌다 (design.md) */}
            <Text
              className="font-bold text-foreground"
              style={{ fontSize: 32, letterSpacing: 4, fontVariant: ['tabular-nums'] }}
            >
              {phase.code.userCode}
            </Text>
            <Pressable
              onPress={() => Clipboard.setStringAsync(phase.code.userCode)}
              className="flex-row items-center gap-2 active:opacity-60"
            >
              <Copy size={15} color="#999999" />
              <Text className="text-meta text-muted-foreground">코드 복사</Text>
            </Pressable>
          </View>

          <Text className="text-center text-meta text-muted-foreground">
            GitHub 에서 이 코드를 입력하세요
          </Text>

          <Pressable
            onPress={() => WebBrowser.openBrowserAsync(phase.code.verificationUri)}
            className="h-11 flex-row items-center justify-center gap-2 rounded-md bg-primary px-6 active:bg-primary-pressed"
          >
            <ExternalLink size={16} color="#ffffff" />
            <Text className="text-row font-semibold text-primary-foreground">GitHub 열기</Text>
          </Pressable>

          <View className="flex-row items-center gap-2">
            <ActivityIndicator size="small" />
            <Text className="text-meta text-subtle-foreground">승인을 기다리는 중…</Text>
          </View>
        </>
      )}

      {phase.kind === 'error' && (
        <>
          <Text className="text-center text-meta text-error">{phase.message}</Text>
          <Pressable
            onPress={start}
            className="h-11 items-center justify-center rounded-md bg-primary px-6 active:bg-primary-pressed"
          >
            <Text className="text-row font-semibold text-primary-foreground">다시 시도</Text>
          </Pressable>
        </>
      )}
    </View>
  )
}
