import { Stack } from 'expo-router'
import { FileQuestion } from 'lucide-react-native'

import { Link, Text, View } from '@/tw'

/**
 * 존재하지 않는 라우트로 이동했을 때 표시된다.
 *
 * 이 앱에서 실제로 도달하는 경로는 잘못된 딥링크뿐이다. 위키링크는 RN 이
 * 로컬 존재 여부를 확인해 시트로 안내하므로(설계 문서 8장) 여기까지 오지
 * 않는다.
 */
export default function NotFound() {
  return (
    <>
      <Stack.Screen options={{ title: '찾을 수 없음' }} />
      <View className="flex-1 items-center justify-center gap-4 bg-background px-8">
        <FileQuestion size={40} color="#767676" />

        <Text className="text-h3 font-semibold text-foreground">화면을 찾을 수 없습니다</Text>

        <Text className="text-center text-meta text-muted-foreground">
          요청한 경로가 없습니다. 노트를 찾으시면 사이드바에서 열어보세요.
        </Text>

        <Link href="/" replace className="mt-2 text-row text-accent">
          홈으로 돌아가기
        </Link>
      </View>
    </>
  )
}
