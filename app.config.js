// @ts-check
/** @type {import('expo/config').ExpoConfig} */
const config = {
  name: 'NoteVault',
  slug: 'notevault',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/icon.png',

  // 시스템 다크모드를 따른다. 설정 화면에서 수동 지정도 가능하다.
  userInterfaceStyle: 'automatic',

  scheme: 'notevault',

  android: {
    package: 'com.ssafy.notevault',
    versionCode: 1,
    adaptiveIcon: {
      backgroundColor: '#1E1E1E',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    // 볼트를 앱 전용 디렉토리(documentDirectory)에 저장하므로
    // 저장소 접근 권한이 필요 없다. 스코프드 스토리지 대응도 불필요.
    permissions: ['INTERNET'],
    predictiveBackGestureEnabled: false,
  },

  plugins: [
    'expo-router',
    'expo-sqlite',
    'expo-secure-store',
    'expo-web-browser',
  ],

  experiments: {
    typedRoutes: true,
  },

  extra: {
    githubClientId: process.env.GITHUB_CLIENT_ID ?? '',
    eas: {
      projectId: '84716c94-4f83-40b8-b173-3726188e4cc6',
    },
  },
}

module.exports = config
