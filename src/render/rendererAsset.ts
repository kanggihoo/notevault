import { Asset } from 'expo-asset'
import { File } from 'expo-file-system'

/**
 * 번들된 렌더러 HTML(4.2MB, JS·CSS 인라인)을 문자열로 읽는다.
 *
 * WebView 에 source={{ html, baseUrl: 볼트루트 }} 로 넘겨야 하기 때문이다 —
 * uri 로 로드하면 baseUrl 을 볼트 루트로 지정할 수 없어 상대경로 이미지가
 * 깨진다 (설계 문서 8장).
 */
let cached: string | null = null

export async function loadRendererHtml(): Promise<string> {
  if (cached) return cached
  const asset = Asset.fromModule(require('../../assets/renderer/dist/index.html'))
  await asset.downloadAsync()
  if (!asset.localUri) throw new Error('renderer asset 을 찾을 수 없다')
  cached = await new File(asset.localUri).text()
  return cached
}
