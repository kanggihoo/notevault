import { useEffect, useState } from 'react'
import { Alert, Switch } from 'react-native'
import { router } from 'expo-router'
import { ChevronRight } from 'lucide-react-native'

import { Pressable, ScrollView, Text, TextInput, View } from '@/tw'
import { getToken, signOut } from '@/auth'
import { getDb } from '@/db'
import { getMeta, getTotalSize } from '@/db/queries'
import { GithubClient } from '@/sync/github'
import { appFetch } from '@/sync/http'
import { formatBytes } from '@/tree/remoteTree'
import { useSettings } from '@/store/settings'

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View className="mb-6">
      <Text className="mb-2 px-4 text-section font-bold uppercase text-muted-foreground">
        {title}
      </Text>
      <View className="mx-3 rounded-lg border border-border bg-card">{children}</View>
    </View>
  )
}

function Row({
  label,
  onPress,
  right,
}: {
  label: string
  onPress?: () => void
  right?: React.ReactNode
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      className="h-13 flex-row items-center justify-between px-4 py-3.5 active:bg-muted"
    >
      <Text className="text-row text-foreground">{label}</Text>
      {right ?? (onPress ? <ChevronRight size={16} color="#999999" /> : null)}
    </Pressable>
  )
}

export default function SettingsScreen() {
  const settings = useSettings()
  const [hasToken, setHasToken] = useState(false)
  const [owner, setOwner] = useState('')
  const [repo, setRepo] = useState('')
  const [branch, setBranch] = useState('main')
  const [storageBytes, setStorageBytes] = useState(0)
  const [lastSynced, setLastSynced] = useState<string | null>(null)

  useEffect(() => {
    void (async () => {
      setHasToken((await getToken()) != null)
      if (settings.repo) {
        setOwner(settings.repo.owner)
        setRepo(settings.repo.repo)
        setBranch(settings.repo.branch)
      }
      const db = await getDb()
      setStorageBytes(await getTotalSize(db))
      const at = await getMeta(db, 'last_synced_at')
      if (at) setLastSynced(new Date(Number(at)).toLocaleString('ko'))
    })()
  }, [settings.repo])

  async function saveRepo() {
    if (!owner.trim() || !repo.trim()) return
    await settings.setRepo({ owner: owner.trim(), repo: repo.trim(), branch: branch.trim() || 'main' })
    Alert.alert('저장됨', `${owner}/${repo} (${branch})`)
  }

  async function testConnection() {
    const token = await getToken()
    if (!token) {
      Alert.alert('토큰 없음', 'GitHub 연결을 먼저 해주세요.')
      return
    }
    if (!settings.repo) {
      Alert.alert('저장소 미지정', 'owner/repo 를 먼저 저장하세요.')
      return
    }
    try {
      const client = new GithubClient(token, appFetch)
      await client.checkAccess(settings.repo)
      const rl = client.rateLimit
      Alert.alert('연결 성공', rl ? `API 잔여 ${rl.remaining}/${rl.limit}` : '접근 가능합니다.')
    } catch (error) {
      Alert.alert('연결 실패', String(error))
    }
  }

  return (
    <ScrollView
      className="flex-1 bg-background pt-4"
      contentContainerStyle={{ paddingBottom: 48 }}
    >
      <Section title="GitHub 연결">
        <Row
          label={hasToken ? '연결됨 — 재인증' : 'Device Flow 인증'}
          onPress={() => router.push('/auth')}
        />
        {hasToken && (
          <Row
            label="연결 해제"
            onPress={() => {
              void signOut().then(() => setHasToken(false))
            }}
          />
        )}
        <View className="gap-2 border-t border-border px-4 py-3">
          <TextInput
            className="h-10 rounded-md bg-muted px-3 text-row text-foreground"
            placeholder="owner"
            placeholderTextColor="#767676"
            autoCapitalize="none"
            value={owner}
            onChangeText={setOwner}
          />
          <TextInput
            className="h-10 rounded-md bg-muted px-3 text-row text-foreground"
            placeholder="repo"
            placeholderTextColor="#767676"
            autoCapitalize="none"
            value={repo}
            onChangeText={setRepo}
          />
          <TextInput
            className="h-10 rounded-md bg-muted px-3 text-row text-foreground"
            placeholder="branch (기본 main)"
            placeholderTextColor="#767676"
            autoCapitalize="none"
            value={branch}
            onChangeText={setBranch}
          />
          <View className="flex-row gap-2">
            <Pressable
              onPress={saveRepo}
              className="h-10 flex-1 items-center justify-center rounded-md bg-primary active:bg-primary-pressed"
            >
              <Text className="text-row font-semibold text-primary-foreground">저장</Text>
            </Pressable>
            <Pressable
              onPress={testConnection}
              className="h-10 flex-1 items-center justify-center rounded-md bg-muted active:opacity-70"
            >
              <Text className="text-row text-foreground">연결 테스트</Text>
            </Pressable>
          </View>
        </View>
      </Section>

      <Section title="구독">
        <Row label="구독 관리" onPress={() => router.push('/settings/subscriptions')} />
      </Section>

      <Section title="동기화">
        <Row
          label="앱 실행 시 자동 동기화"
          right={
            <Switch
              value={settings.autoSyncOnLaunch}
              onValueChange={(v) => void settings.setAutoSync(v)}
            />
          }
        />
        <Row
          label="Wi-Fi 에서만"
          right={
            <Switch value={settings.wifiOnly} onValueChange={(v) => void settings.setWifiOnly(v)} />
          }
        />
        <Row
          label="마지막 동기화"
          right={
            <Text className="text-meta text-muted-foreground">{lastSynced ?? '없음'}</Text>
          }
        />
      </Section>

      <Section title="표시">
        <View className="flex-row gap-2 px-4 py-3">
          {(
            [
              ['system', '시스템'],
              ['light', '라이트'],
              ['dark', '다크'],
            ] as const
          ).map(([value, label]) => (
            <Pressable
              key={value}
              onPress={() => void settings.setTheme(value)}
              className={`h-10 flex-1 items-center justify-center rounded-md ${
                settings.theme === value ? 'bg-primary' : 'bg-muted'
              }`}
            >
              <Text
                className={`text-row font-semibold ${
                  settings.theme === value ? 'text-primary-foreground' : 'text-foreground'
                }`}
              >
                {label}
              </Text>
            </Pressable>
          ))}
        </View>
      </Section>

      <Section title="저장공간">
        <Row
          label="사용량"
          right={<Text className="text-meta text-muted-foreground">{formatBytes(storageBytes)}</Text>}
        />
      </Section>
    </ScrollView>
  )
}
