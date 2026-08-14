const { getDefaultConfig } = require('expo/metro-config')
const { withNativewind } = require('nativewind/metro')

/** @type {import('expo/metro-config').MetroConfig} */
const config = getDefaultConfig(__dirname)

// 렌더러 번들(assets/renderer/dist/index.html)을 asset 으로 포함한다.
config.resolver.assetExts.push('html')

/*
 * 다크모드가 죽는 함정 두 개 — 재발 방지 기록:
 *
 * 1. inlineVariables: false (expo-tailwind-setup skill 권장) 를 쓰지 않는다.
 *    light-dark() 변수의 다크 값이 컴파일에서 소실된다 (jest 재현:
 *    vr:[["nv-bg",[["#fff"]]]]). skill 의 근거인 PlatformColor 는 안 쓴다.
 *
 * 2. package.json 의 "browserslist": ["chrome 138"] 을 지우면 안 된다.
 *    react-native-css 의 metro 변환기는 CSS 를 먼저 Expo 웹 변환기(lightningcss,
 *    browserslist 타깃)로 돌리는데, 타깃 미설정 시 defaults(구형 브라우저 포함)가
 *    되어 light-dark() 가 폴리필로 대체되고 인라이너가 라이트 폴백만 남긴다.
 *    폰 번들 실측: bg-background 에 #fff 규칙만 존재했다. 이 CSS 는 rnc 컴파일
 *    입력일 뿐 실제 브라우저와 무관하므로 최신 타깃이 안전하다.
 */
module.exports = withNativewind(config, {
  // className 은 src/tw 래퍼가 명시적으로 처리한다
  globalClassNamePolyfill: false,
})
