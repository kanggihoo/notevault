module.exports = function (api) {
  api.cache(true)
  return {
    // NativeWind v5 는 babel 설정이 필요 없다 (Tailwind v4 CSS-first).
    presets: ['babel-preset-expo'],
    plugins: [
      // react-native-reanimated(worklets) 플러그인은 반드시 배열의 마지막에 와야 한다.
      // 순서가 틀리면 빌드는 성공하는데 애니메이션만 조용히 깨진다.
      'react-native-worklets/plugin',
    ],
  }
}
