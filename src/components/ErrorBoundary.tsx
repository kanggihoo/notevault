import { TriangleAlert } from 'lucide-react-native'
import type { ErrorBoundaryProps } from 'expo-router'

import { Pressable, ScrollView, Text, View } from '@/tw'

/**
 * 렌더 중 예외가 발생했을 때의 폴백 화면.
 *
 * src/app/_layout.tsx 에서 named export 로 내보내면 Expo Router 가 해당
 * 라우트 세그먼트 전체의 ErrorBoundary 로 사용한다.
 *
 * 핵심 원칙: **크래시가 받아둔 노트를 잃게 하지 않는다.** 볼트 파일과 SQLite 는
 * 그대로 있으므로 retry() 로 복구하면 읽기를 계속할 수 있다. 사용자에게
 * 재동기화나 재인증을 요구하지 않는다.
 */
export function ErrorBoundary({ error, retry }: ErrorBoundaryProps) {
  return (
    <View className="flex-1 bg-background px-6 pt-16">
      <View className="flex-row items-center gap-2">
        <TriangleAlert size={22} color="#f87171" />
        <Text className="text-h3 font-semibold text-foreground">문제가 발생했습니다</Text>
      </View>

      <Text className="mt-3 text-meta text-muted-foreground">
        받아둔 노트는 안전합니다. 다시 시도하면 이어서 읽을 수 있습니다.
      </Text>

      <Pressable
        onPress={retry}
        className="mt-6 self-start rounded-md bg-primary px-4 py-3 active:bg-primary-pressed"
      >
        <Text className="text-row font-semibold text-primary-foreground">다시 시도</Text>
      </Pressable>

      {/* 개인용 앱이므로 오류 원문을 그대로 보여준다. 원격 리포팅은 없다. */}
      <ScrollView className="mt-8 max-h-64 rounded-sm bg-popover p-3">
        <Text className="text-code text-subtle-foreground">
          {error.message}
          {__DEV__ && error.stack ? `\n\n${error.stack}` : ''}
        </Text>
      </ScrollView>
    </View>
  )
}
