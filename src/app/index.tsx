import { BookOpen } from 'lucide-react-native'

import { Text, View } from '@/tw'

/** 노트를 선택하기 전의 빈 상태. 실제 열람은 note/[...path] 가 담당한다. */
export default function Home() {
  return (
    <View className="flex-1 items-center justify-center gap-3 bg-background px-8">
      <BookOpen size={40} color="#767676" />
      <Text className="text-h3 font-semibold text-foreground">NoteVault</Text>
      <Text className="text-center text-meta text-muted-foreground">
        왼쪽에서 사이드바를 열어 노트를 선택하세요.{'\n'}
        처음이라면 설정에서 GitHub 연결과 구독을 먼저 추가합니다.
      </Text>
    </View>
  )
}
