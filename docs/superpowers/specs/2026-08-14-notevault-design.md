# notevault 설계 문서

작성일: 2026-08-14

## 1. 배경과 목적

Obsidian 볼트를 GitHub private 저장소에 동기화하고 있다. 출퇴근길에 이를 읽기 위해
현재는 별도 VPS에서 Jenkins로 Quartz를 빌드해 웹으로 보고 있다. 노트가 늘어날수록
빌드 시간이 선형으로 증가하는데, 정작 읽는 것은 휴대폰뿐이다. 전체를 매번 빌드할
이유가 없다.

**목적:** 필요한 노트만 휴대폰에 내려받아 오프라인으로 읽는 개인용 안드로이드 앱을
만들어 Quartz + Jenkins + VPS 파이프라인을 대체한다.

### 성공 기준

- 지하철에서 네트워크 없이 받아둔 노트를 전부 읽을 수 있다.
- 저장소에 변경이 없으면 동기화가 1초 안에 끝난다.
- 변경된 파일만 다시 받는다.
- 서버가 필요 없다. 앱 하나로 완결된다.

### 비목표

노트 편집, 본문 전문검색, 원격 푸시 자동 동기화, iOS 지원, 그래프뷰, 다중 저장소,
파일 단위 구독. 필요해지면 나중에 얹을 수 있도록 구조만 열어둔다.

## 2. 기술 스택과 의존성

### 2.1 기본 선택

| 영역 | 선택 | 근거 |
|---|---|---|
| 앱 | React Native + Expo (Android 전용) | 네이티브 모듈 직접 작성 불필요. 필요한 기능이 전부 기성 라이브러리로 해결된다. |
| 언어 | TypeScript (strict) | 동기화 로직의 상태 조합이 많아 타입 검사의 이득이 크다. |
| 배포 | EAS Build → APK 사이드로드 | 개인용이므로 스토어 등록 불필요. |
| 렌더링 | `react-native-webview` | 8장 참조. |

**플레이스토어의 Expo Go 로는 동작하지 않는다.** 당초 근거(네이티브 모듈)와 달리
실제 이유는 스토어 Expo Go 가 SDK 54 에 고정되어 있고 이 프로젝트는 SDK 57 이기
때문이다 — 네이티브 의존성 자체는 전부 Expo Go 번들 목록에 있다. 개발은 SDK 57
Expo Go(별도 설치) 또는 development build 로 한다.

### 2.2 런타임 의존성

버전은 명시하지 않는다. `npx expo install <pkg>` 가 설치된 Expo SDK와 호환되는
버전을 자동으로 고정하므로, 수동으로 버전을 적으면 오히려 충돌을 만든다.

**핵심 기능**

| 패키지 | 용도 |
|---|---|
| `expo`, `expo-router` | 런타임, 파일 기반 라우팅 |
| `expo-file-system` | 볼트 파일 읽기·쓰기·삭제, 임시 파일 rename |
| `expo-sqlite` | 메타데이터 (구독·파일·SHA) |
| `expo-secure-store` | GitHub 토큰 (Keystore 암호화) |
| `react-native-webview` | 마크다운·HTML 렌더 |
| `expo-web-browser` | Device Flow 인증 페이지 열기 |
| `expo-clipboard` | Device Flow 코드 복사, 코드블록 복사 |
| `@react-native-community/netinfo` | 오프라인 감지, Wi-Fi 전용 동기화 판별 |

**화면·네비게이션**

| 패키지 | 용도 |
|---|---|
| `@react-navigation/drawer` | 사이드바. Expo Router의 `Drawer`가 이 위에 올라간다 |
| `react-native-gesture-handler` | 사이드바 스와이프 (Drawer 요구사항) |
| `react-native-reanimated` | 애니메이션 (Drawer 요구사항) |
| `react-native-screens`, `react-native-safe-area-context` | 네비게이션 기반 |
| `nativewind@5(preview)` + `tailwindcss@4` + `react-native-css` | Tailwind 문법 스타일링. **CSS-first** — `tailwind.config.js` 없이 `global.css` 의 `@theme` 이 토큰의 단일 출처. v5 는 className 을 자동 부착하지 않으므로 `src/tw/` 래퍼(useCssElement)를 통해서만 쓴다. Expo 공식 skill(expo-tailwind-setup)의 방향을 따르되 skill 의 nightly 핀 대신 정식 react-native-css 3.x 를 쓴다 |
| `lucide-react-native` + `react-native-svg` | 아이콘. 렌더러의 콜아웃 아이콘(markdown-it-obsidian-callouts 인라인 SVG)과 같은 세트라 앱·본문의 아이콘이 일관된다 |

react-native-reusables 는 채택하지 않았다 — v5 래퍼 체계와 겹치고, 실제 필요한
부품(버튼·스위치·체크박스)이 래퍼 + 토큰만으로 충분했다.

**NativeWind v5 의 다크모드 함정 두 개** (재발 방지, metro.config.js 주석에도 있음):
`inlineVariables: false` 옵션과 `browserslist` 미설정은 각각 light-dark() 토큰의
다크 값을 컴파일에서 소실시킨다. package.json 의 `"browserslist": ["chrome 138"]` 은
lightningcss 가 light-dark() 를 폴리필로 바꾸지 않게 하는 장치다. 또한
react-native-css 는 Appearance 변경 이벤트를 받지 못하므로 테마 전환은
`Appearance.setColorScheme` 와 css 쪽 `colorScheme.set` 을 **모두** 호출해야 한다
(src/store/theme.test.tsx 가 근거를 고정).
| `@shopify/flash-list` | 리스트 가상화. **구독 관리 화면에서 원격 트리 3555개를 다루므로 필수** |

**UI 라이브러리 선택 근거.** 이 앱이 실제로 필요로 하는 부품은 체크박스, 스위치,
스피너·진행바, 바텀시트, 버튼, 검색 입력창, 리스트 아이템 정도다. 사이드바는
`@react-navigation/drawer`가, 트리는 직접 구현이 담당하므로 UI 라이브러리가 커버하는
범위 자체가 넓지 않다.

결정적인 차이는 **테마가 WebView까지 이어지는가**다. 이 앱은 화면 UI뿐 아니라
WebView 안의 노트 본문 CSS도 다크/라이트를 맞춰야 한다. NativeWind는 색상 토큰이
CSS 변수이므로 **동일한 변수를 WebView 스타일시트에서 그대로 재사용할 수 있다.**
앱 UI와 노트 본문의 테마가 한 소스에서 나온다. Material 3 계열 라이브러리는 테마가
JS 객체라 WebView용 CSS로 변환하는 계층을 따로 두어야 하고, 색을 바꿀 때마다 두
곳을 관리하게 된다.

**상태 관리**

| 패키지 | 용도 |
|---|---|
| `zustand` | 동기화 진행 상태, 테마, 설정. Redux는 이 규모에 과하고, Context만으로는 동기화 진행률 갱신 시 불필요한 리렌더가 넓게 퍼진다 |

### 2.3 렌더러 번들 의존성

아래는 RN이 아니라 **WebView 안에서 실행되는** 라이브러리다. `assets/renderer/`에서
별도로 번들해 앱 asset으로 포함하며, RN 번들 크기와 무관하다.

| 패키지 | 용도 |
|---|---|
| `markdown-it` | 마크다운 → HTML. **14.x로 고정** (아래 참조) |
| `markdown-it-obsidian-callouts` | Obsidian 콜아웃 (`> [!NOTE]`) |
| `highlight.js` | 코드블록 문법 강조 |
| `mermaid` | 다이어그램 |
| `katex` | 수식 |
| `esbuild` | 위 다섯 + 자체 위키임베드 플러그인을 단일 JS/CSS로 번들 (devDependency) |

**`markdown-it`은 14.x로 고정한다.** `markdown-it-obsidian-callouts`의 peer
dependency가 `^14.0.0`이며, 15를 설치하면 `npm i`가 ERESOLVE로 실패한다.

**콜아웃은 기성 플러그인을 쓴다.** 볼트 실측 결과 519개 노트에 콜아웃 1693개가
있어(전체 노트의 18%) 렌더러에서 가장 비중이 큰 부품이다. 사용 타입은 19종으로
`note`(344) · `warning`(297) · `tip`(239) · `important`(238) · `info`(184) 순이며,
Obsidian 표준이 아닌 `warn`(35) · `primary`(1)과 대소문자 혼용(`[!NOTE]`)도 섞여
있다. 검증 결과 이들 전부와 접기 문법(`[!tip]-`), 중첩 콜아웃이 통과했다.

출력은 `<div class="callout" data-callout="note">` 형태이고 접기는 `<details>`로
렌더된다. `data-callout` 속성이 CSS 훅이 되므로 타입별 색을 Tailwind 토큰으로
제어할 수 있다. 아이콘은 인라인 Lucide SVG로, 앱 UI가 쓰는 세트와 동일하다.
`fs`·`process.cwd`에 의존하지 않아 WebView에서 동작한다.

**위키임베드(`![[...]]`)는 직접 작성한다.** 기성 `markdown-it-obsidian`은
매 매치마다 `fs.readdirSync(process.cwd())`로 볼트 전체를 순회하는 정적 사이트
생성기용 플러그인이라 WebView에서 동작하지 않는다. 또한 파일명의 공백을 `_`로
치환해(`Pasted image ...` → `Pasted_image ...`) 우리 파일명을 손상시키고, 크기
지정(`|718`)을 라벨로 해석한다. `markdown-it-wikilinks`는 이미지 임베드를 아예
지원하지 않는다.

자체 플러그인은 40줄 미만이다. `[[`에서만 발동하므로 표준 마크다운 이미지
543개와 원격 URL 1436개는 markdown-it 기본 동작으로 통과한다. 8장 참조.

CDN을 일절 사용하지 않으므로 오프라인에서 완전히 동작한다. 번들 총량은 약 2~3MB이며
대부분 mermaid다. mermaid는 409개 노트, KaTeX는 37개 노트가 사용한다.

### 2.4 개발 의존성

| 패키지 | 용도 |
|---|---|
| `typescript`, `@types/react` | |
| `jest`, `jest-expo` | 순수 함수 테스트. Expo 공식 지원 프리셋이라 SDK 업그레이드 시 함께 관리된다 |
| `esbuild` | 렌더러 번들 |
| `eslint-config-expo`, `prettier` | |

`vitest`가 더 빠르지만 채택하지 않는다. 테스트 대상이 fixture 수십 개를 도는 순수
함수뿐이라 어느 쪽이든 1초 안에 끝나므로 속도 차이가 실질적이지 않고, 그렇다면 Expo
공식 지원 경로를 따르는 편이 낫다.

`@testing-library/react-native`은 넣지 않는다. 근거는 10.3 참조.

### 2.5 TypeScript 설정

`tsconfig.json`은 `expo/tsconfig.base`를 확장하고 다음을 적용한다.

- `strict: true`
- `paths`: `@/*` → `src/*` (Expo Router 기본값과 일관)

동기화 엔진의 핵심 타입을 먼저 정의하고 시작한다.

```ts
type RemoteEntry = { path: string; sha: string; size: number }
type LocalFile   = { path: string; blobSha: string }
type SyncPlan    = { download: RemoteEntry[]; delete: string[] }
```

`planSync(remote, local, subscriptions) → SyncPlan` 이 이 프로젝트의 중심 함수다.

점(.) 경로 제외는 `planSync` 안에서 수행한다. 트리 파싱 단계가 아니라 `planSync`에
두는 이유는 제외 규칙이 테스트 대상이고, 순수 함수 안에 있으면 fixture로 검증할 수
있기 때문이다. 6장 참조.

### 2.6 Expo 빌드 설정

#### app.config.ts

`app.json` 대신 `app.config.ts`를 쓴다. GitHub OAuth `client_id`를 환경변수로
주입해야 하기 때문이다.

주요 설정:

```
name / slug            notevault
android.package        com.ssafy.notevault      (한 번 정하면 변경 불가)
android.versionCode    빌드마다 증가
userInterfaceStyle     automatic                (다크모드 자동 추종)
plugins                expo-router, expo-secure-store,
                       expo-sqlite, react-native-webview
extra.githubClientId   process.env.GITHUB_CLIENT_ID
```

**권한(permissions)은 `INTERNET` 외에 필요 없다.** 볼트를 앱 전용 디렉토리
(`documentDirectory`)에 저장하므로 저장소 접근 권한이 불필요하고, 이는 안드로이드
스코프드 스토리지 대응 부담도 함께 없앤다.

`client_id`는 비밀값이 아니므로(secret이 아님) 저장소에 포함해도 무방하지만,
환경변수로 분리해 나중에 OAuth App을 교체하기 쉽게 한다.

#### eas.json

세 개 프로파일을 둔다. **전부 APK로 뽑는다** — AAB는 스토어 업로드용이라 사이드로드에
쓸 수 없다.

| 프로파일 | 용도 | 특징 |
|---|---|---|
| `development` | 에뮬레이터·실기기 개발 | dev client 포함, 코드 수정이 즉시 반영됨 |
| `preview` | 실제 사용할 빌드 | dev client 없음, 릴리스 최적화, APK |
| `production` | 예비 | preview와 동일하되 버전 관리용 |

빌드는 EAS 클라우드에서 수행하므로 로컬에 Android SDK 전체를 갖출 필요는 없다.
다만 에뮬레이터를 쓰려면 Android Studio가 필요하다(아래 10.2 참조).

#### 초기 스캐폴딩

```
npx create-expo-app notevault --template expo-template-blank-typescript
npx expo install expo-router expo-file-system expo-sqlite expo-secure-store \
                 react-native-webview expo-web-browser expo-clipboard \
                 @react-native-community/netinfo @react-navigation/drawer \
                 react-native-gesture-handler react-native-reanimated \
                 react-native-screens react-native-safe-area-context \
                 @shopify/flash-list react-native-svg
npx expo install nativewind@5.0.0-preview.4 react-native-css tailwindcss@^4 \
                 @tailwindcss/postcss expo-constants expo-linking \
                 react-native-worklets expo-asset babel-preset-expo
npm install lucide-react-native zustand tailwind-merge clsx
npm install -D markdown-it@14 markdown-it-obsidian-callouts \
               highlight.js mermaid katex esbuild jest jest-expo \
               @types/jest@29 @types/markdown-it @testing-library/react-native \
               react-test-renderer@<react버전과 동일>
```

실측 주의점: expo-router 가 요구하는 react-dom 과 템플릿의 react 버전이 어긋나
ERESOLVE 가 나므로 둘을 같은 버전으로 맞춘다 (`expo.install.exclude` 로 기록).
`babel-preset-expo`·peer 3종(expo-constants·expo-linking·react-native-worklets)은
자동 설치되지 않아 명시해야 한다.

`react-native-reanimated`는 `babel.config.js`에 플러그인 등록이 필요하며 **반드시
plugins 배열의 마지막에 와야 한다.** 순서가 틀리면 빌드는 되는데 애니메이션만 조용히
깨지므로 초기에 확인한다.

## 3. GitHub 인증

**Device Flow를 사용한다.**

앱이 8자리 코드를 표시하고 GitHub 로그인 페이지를 연다. 사용자가 코드를 입력해
승인하면 앱이 토큰을 자동으로 수신해 SecureStore에 저장한다. 휴대폰에서 40자 토큰을
직접 타이핑하는 상황을 없애는 것이 목적이다. 필요한 scope는 private 저장소 읽기용
`repo` 하나다.

### 대안 검토

GitHub OAuth App은 2025년 7월부터 PKCE를 지원한다(`code_challenge`,
`code_challenge_method`는 `S256`만, `code_verifier`). 그러나 GitHub은 public client와
confidential client를 구분하지 않아 **PKCE를 사용하더라도 토큰 교환 시
`client_secret`이 여전히 필요하다.** 즉 GitHub의 PKCE는 authorization code 탈취에 대한
추가 방어일 뿐, 네이티브 앱이 secret을 숨길 수 없다는 문제를 해결하지 않는다.

Device Flow는 `client_id`와 `device_code`만으로 토큰을 교환하므로 `client_secret`이
필요 없다. 자체 백엔드를 두고 secret을 서버에만 보관하는 구성도 가능하지만, 이
프로젝트는 서버 의존성 제거가 목표이므로 채택하지 않는다.

### 격리

인증은 다음 인터페이스 뒤에 완전히 격리한다.

```ts
// src/auth/
getToken(): Promise<string>
signOut(): Promise<void>
```

앱의 다른 코드는 토큰의 출처를 알지 못한다. 향후 PKCE나 백엔드 프록시로 전환하더라도
`src/auth/` 내부만 교체하면 된다.

**구현 착수 시 확인할 항목:** GitHub이 PKCE 사용 시 `client_secret`을 생략할 수 있게
되었는지 문서로 재확인한다. 가능해졌다면 딥링크 복귀 방식이 UX가 더 낫다.

## 4. 시스템 구조

앱 하나. 외부 서버 없음.

```
┌─────────────────────────────────────────┐
│  화면 (Expo Router)                      │
│  뷰어 + 사이드바 · 구독관리 · 설정 · 인증  │
├─────────────────────────────────────────┤
│  동기화 엔진                              │
│    planSync (순수 함수)                   │
│    runSync  (IO 실행)                     │
├──────────────────┬──────────────────────┤
│  GitHub API      │  로컬 저장소           │
│  클라이언트       │  파일 + SQLite         │
└──────────────────┴──────────────────────┘
```

핵심 원칙: **"무엇을 받고 무엇을 지울지 계산하는 로직"을 네트워크·파일시스템과 분리된
순수 함수로 만든다.** 입력은 (원격 트리, 로컬 파일 목록, 구독 목록), 출력은
(다운로드 목록, 삭제 목록)이다. 이 프로젝트에서 유일하게 복잡한 로직이며, 순수
함수이므로 테스트가 쉽다.

## 5. 로컬 저장 구조

### 파일

저장소 디렉토리 구조를 그대로 미러링한다.

```
<documentDirectory>/vault/
  프로젝트/02-동시성처리/00-이론/01-사전기반지식.md
  프로젝트/02-동시성처리/00-이론/attachments/Pasted image 20260320144034.png
  learning/0019-kafka-design-deep-dive.html
  learning/assets/lesson.css
```

이렇게 하면 마크다운의 상대경로 이미지(`attachments/...png`)와 HTML의
`<link href="./assets/lesson.css">`가 경로 변환 없이 그대로 동작한다. WebView의 base
URL을 해당 파일이 있는 폴더로 지정하기만 하면 된다. 리소스 디렉토리 규약은 6장 참조.

### 메타데이터 (SQLite)

```sql
subscriptions(path TEXT PRIMARY KEY)           -- 구독 폴더 경로(prefix)
files(path TEXT PRIMARY KEY, blob_sha TEXT, size INTEGER, downloaded_at INTEGER)
meta(key TEXT PRIMARY KEY, value TEXT)         -- last_commit_sha, last_synced_at
bookmarks(path TEXT PRIMARY KEY, created_at INTEGER)
recents(path TEXT PRIMARY KEY, opened_at INTEGER)
```

`files.blob_sha`가 증분 동기화의 전부다. GitHub이 파일마다 내용 해시를 제공하므로,
이 값만 비교하면 네트워크 요청 없이 변경 여부를 판단할 수 있다.

**북마크·최근 기록은 파일 삭제와 함께 정리한다.** 로컬 파일이 사라지는 경우
(원격에서 삭제, 구독 해제)에 `bookmarks`·`recents`에서 같은 경로를 함께 삭제한다.
목록에 열리지 않는 항목이 남지 않으므로 "받지 않은 노트" 예외 처리가 불필요하다.
구독을 해제한 뒤 다시 구독하면 북마크는 복원되지 않는다.

### 토큰

`expo-secure-store`. AsyncStorage나 평문 파일에 저장하지 않는다.

## 6. 증분 동기화

### 알고리즘

```
1) GET /repos/{owner}/{repo}/commits?per_page=1        (응답 수 KB)
   head_sha == meta.last_commit_sha  →  즉시 종료

2) GET /repos/{owner}/{repo}/git/trees/{head_sha}?recursive=1
   → [{ path, sha, size, type }, ...]                  (1회 호출로 전체 목록)

3) 점(.)으로 시작하는 경로 세그먼트를 가진 항목을 제외
   → .obsidian/ .claude/ .agents/ .codex/ .github/ 등 (실측 153개)
   구독 prefix 하위의 남은 모든 파일을 대상 집합으로 선정
   (.md, 이미지, .html, .css 구분 없음)

4) 로컬 files 테이블과 대조
     로컬에 없음         → 다운로드
     blob_sha 다름       → 재다운로드
     blob_sha 같음       → 스킵            ← 증분의 핵심
     원격에 없음(삭제됨)  → 로컬 파일 삭제

5) 다운로드
   GET /repos/{owner}/{repo}/contents/{path}?ref={head_sha}
   Accept: application/vnd.github.raw
   적응형 동시성 (아래 참조)

6) 삭제 수행 → 전부 성공한 경우에만 meta.last_commit_sha = head_sha

주의: 완료 마커는 "그 시점의 구독 집합 기준"이다. **구독이 바뀌면 마커를
무효화해야 한다** — 지우지 않으면 HEAD 가 그대로일 때 1단계가 "이미 최신"으로
즉시 종료해 새 구독분을 받지 않는다 (실기기에서 실제로 발생했던 버그.
setSubscriptions 트랜잭션이 last_commit_sha 를 함께 지운다).
```

### 대상 파일과 제외 규칙

**볼트 실측 (전체)**

| | 개수 |
|---|---|
| Git 추적 파일 전체 | 3555 |
| 점(.) 경로 제외 후 = 동기화 대상 | **3402** |
| 그중 `.md` | 2944 |
| 이미지 | 337 |

`.trash/`는 볼트의 `.gitignore`에 이미 포함되어 원격에 존재하지 않는다. `.git/`은
저장소 자체이므로 Tree API 응답에 등장하지 않는다(`git ls-files | grep '^\.git/'`
→ 0). 따라서 앱이 별도로 다룰 필요가 없다.

**점 경로를 제외하면 파일 수가 늘지 않고 153개 줄어든다.** 제외 대상은
`.obsidian/`(94) · `.claude/`(28) · `.agents/`(28) · `.codex/`(2) · `.github/`(1)이며,
스킬 스크립트(`.py`) · 플러그인 설정(`.json`) · 워크플로(`.yml`)가 여기 들어 있다.
이들을 빼면 `.md`·이미지가 아닌 파일은 `skills-lock.json` 하나만 남으므로 **확장자
화이트리스트가 불필요하다.**

**용량은 이미지가 결정한다.** 예를 들어 `기업조사/` 폴더는 `.md` 5개가 약 1.5KB인데
`attachments/*.png` 36개가 29.3MB다(평균 834KB, 최대 2.8MB). 전부 스크린샷
붙여넣기(`Pasted image ...png`)라 개별 크기가 크다. **동기화의 데이터량과 저장공간은
마크다운이 아니라 이미지가 결정한다.**

**볼트 리소스 규약**

Obsidian 설정이 `attachmentFolderPath: "./attachments"`이므로 첨부는 노트 기준
상대 경로에 놓인다. `.html`의 정적 리소스는 이름을 분리해 `./assets/`에 둔다.

```
<폴더>/
  note.md                    → attachments/ 참조
  attachments/               ← Obsidian 자동 생성. 이미지·첨부
    Pasted image ....png
  page.html                  → ./assets/ 참조
  assets/                    ← 수동 배치. css·정적 리소스
    lesson.css
```

`attachments/`와 `assets/`를 통일하지 않는 이유는 `attachments/`가 Obsidian이
자동으로 쓰는 디렉토리이기 때문이다. 스크린샷을 붙여넣으면 그 폴더에 파일이
생기므로 손으로 관리하는 `.css`와 섞인다. 또한 `file-cleaner-redux` 플러그인은
Obsidian의 링크 그래프를 기준으로 미참조 첨부를 정리하는데, Obsidian은
`<link href>`를 파싱하지 않아 `.html`에서만 참조되는 `.css`가 미참조로 보인다.
이름을 통일해도 앱 코드가 줄지 않으므로(폴더 단위 구독이라 양쪽 모두 같은 prefix에
포함된다) 분리하는 편이 안전하다.

두 디렉토리 모두 노트와 같은 prefix 안에 있으므로 **구독 폴더 하위를 전부 받으면
이미지와 CSS가 자동으로 포함되며, 본문을 파싱해 다운로드 대상을 추출하는 과정이
필요 없다.**

**확장자 필터는 두지 않는다.** 구독 폴더 하위를 조건 없이 받는다. 용량 우려는
아래의 사전 용량 표시로 해결되므로, 규칙을 하나 더 만들 이유가 없다. 표시할 수 없는
대용량 파일이 실제로 문제가 되면 그때 화이트리스트를 추가한다. (blob API 제한인
100MB 초과 파일은 자동으로 건너뛴다.)

**구독 전 용량 표시.** Git Tree API 응답에 파일별 `size`가 포함되므로, 이미 받은
트리 데이터만으로 폴더별 예상 용량을 추가 요청 없이 계산할 수 있다. 구독 관리
화면에서 구독 이전에 표시한다.

```
▸ 기업조사          노트 5 · 이미지 36 · 29.3MB
▸ Dev/Kubernetes    노트 42 · 이미지 8 · 4.1MB
```

**구독 폴더 밖의 이미지**를 참조하는 노트가 있으면 뷰어에서 해당 위치에 "이미지 없음"
자리표시를 보여준다. 실제로 그런 노트가 나타나면 해당 폴더를 구독하면 된다. 실측
기준 위키임베드 69개는 전부 자기 노트 폴더의 `attachments/`에서 해석된다.

**원격 URL 이미지는 받지 않는다.** 볼트의 이미지 참조는 로컬 상대경로 543개보다
원격 URL이 1436개로 많다(`imagedelivery.net` 722 · `camo.githubusercontent.com` 115 ·
`grafana.com` 82 등, 대부분 외부 문서 번역 노트). 이들은 렌더 시점에 URL을 그대로
참조하며 사전 다운로드나 캐시를 하지 않는다.

따라서 **오프라인에서는 원격 이미지가 자리표시로 보인다.** 텍스트 읽기에는 영향이
없다. 사전 캐시는 GitHub 외 네트워크 요청과 캐시 테이블·용량 관리를 새로 들여오는
반면 얻는 것은 이미지 표시뿐이므로 v1 범위에서 제외한다. 실사용에서 답답함이 확인되면
12장의 확장 항목으로 올린다.

### 시간 복잡도

N = 원격 파일 수(실측 3555), L = 로컬 파일 수, d = 경로 깊이(3~6).

- 3단계: 점 경로 필터는 각 경로를 1회 순회 → `O(N·d)`.
  구독 경로를 Set에 넣고 각 파일의 조상 경로만 조회 → `O(N·d)`.
  약 18,000회 해시 조회, 1ms 미만.
- 4단계: 로컬 `files`를 `Map<path, blob_sha>`로 올린 뒤 1회 순회 → `O(N + L)`.

총 `O(N·d + L)`로 선형이다. 3555개 기준 계산 시간은 수 밀리초이며, 트리 JSON 파싱
(1~2MB, 약 50ms)이 오히려 더 크다. **실제 소요 시간은 전적으로 네트워크가 결정한다.**

### 동시성

기본 5개 병렬로 시작하되 고정하지 않는다. GitHub은 동시 요청 100개까지 허용하나
짧은 시간에 몰아치면 secondary rate limit(429)에 걸리며, 문서화된 임계값이 없다.
또한 모바일 네트워크는 소켓을 많이 열수록 오히려 느려지는 구간이 있다.

**적응형으로 조절한다.** 429나 타임아웃이 발생하면 동시성을 절반으로 줄이고 지수
백오프, 안정적이면 점진적으로 복원. 상한은 설정에서 조절 가능하게 한다.

### 중단 복구

동기화 도중 앱이 종료되거나 네트워크가 끊기는 상황을 다음 세 가지로 처리한다.

**1. 반쪽 파일 방지 —** 임시 파일에 받은 뒤 완료되면 목적지로 이름을 바꾼다.
이름 변경은 원자적이므로 파일은 "없거나 완전하거나" 둘 중 하나만 존재한다.

**2. 진행 상황 기록 —** 파일 하나가 완료될 때마다 `files` 테이블에
`(path, blob_sha)`를 즉시 기록한다. 테이블에는 이름 변경까지 끝난 파일만 들어가므로
테이블과 실제 파일이 항상 일치한다.

**3. 완료 마커 —** `meta.last_commit_sha`는 "이 커밋까지 완전히 동기화됨"을 뜻하며
전부 성공한 경우에만 갱신한다.

47개 중 12개에서 중단된 경우, 다음 동기화는 1단계에서 SHA 불일치를 감지해 재계산에
들어가고, 이미 받은 12개는 blob SHA가 같아 자동으로 스킵된다. **별도의 재개 로직이
필요 없다.**

### GitHub API 제약

- 트리 API는 항목 10만 개까지. 3555개는 여유롭다. 초과 시 `truncated: true`가
  반환되므로 폴더별 조회로 폴백한다.
- rate limit은 시간당 5000 요청. 구독 범위만 받으므로 통상 수십~수백 건이다.
  잔여 횟수는 응답 헤더로 확인해 설정 화면에 표시한다.
- blob API는 파일당 100MB 제한. 초과 파일은 건너뛰고 경고를 표시한다.

## 7. 화면

### 뷰어 + 사이드바 (메인)

```
┌──────────────┬──────────────────────────┐
│ ☰            │  # Pod 라이프사이클        │
│              │                          │
│ 🔍 파일명 검색 │  쿠버네티스에서 Pod은...   │
│ ★ 북마크      │                          │
│ 🕘 최근 본 것  │  ```mermaid              │
│ ──────────   │   (다이어그램 렌더)        │
│ ▾ Dev        │  ```                     │
│   ▾ K8s      │                          │
│     pod.md   │                          │
│     svc.md   │                          │
│   ▸ Spring   │                          │
│ ▸ Archive    │                          │
│ ──────────   │                          │
│ ⟳ 동기화      │                          │
│ ⚙ 설정        │                          │
└──────────────┴──────────────────────────┘
```

위 그림의 기호는 Lucide 아이콘에 대응한다.

| 기호 | Lucide | 용도 |
|---|---|---|
| ☰ | `Menu` | 사이드바 열기·닫기 |
| 🔍 | `Search` | 파일명 필터 |
| ★ | `Star` | 북마크 |
| 🕘 | `History` | 최근 본 노트 |
| ▸ ▾ | `ChevronRight` / `ChevronDown` | 폴더 펼침·접기 |
| ⟳ | `RefreshCw` | 동기화 (진행 중에는 회전 애니메이션) |
| ⚙ | `Settings` | 설정 |

사이드바는 `Menu` 아이콘으로 열고 닫는다. **로컬에 받은 파일만** 트리로 표시한다. 원격
트리 개념은 이 화면에 등장하지 않으므로 완전한 오프라인 동작이 보장된다.
당겨서 새로고침으로도 동기화할 수 있다.

### 구독 관리 (설정 하위)

**이 화면에서만** GitHub 원격 트리를 다룬다. 폴더를 lazy expand 방식으로 펼치고
체크박스로 구독/해제한다. **각 폴더에 노트 수·이미지 수·예상 용량을 함께 표시한다**
(6장 참조). 이미지가 용량을 좌우하므로 구독 시점에 판단할 수 있어야 한다. 구독을 해제하면 해당 prefix 하위의 로컬 파일을 삭제한다.
자주 진입하지 않는 화면이므로 메인 동선에서 분리하되, **화면 하단에 동기화 바를
둔다** — 체크한 자리에서 바로 받도록 진행률·완료·실패 상태와 동기화 버튼을
표시한다 (사이드바로 돌아가 동기화를 누르는 동선이 불편하다는 실사용 피드백).

### 설정

| 항목 | 내용 |
|---|---|
| GitHub 연결 | Device Flow 인증, 연결 해제, owner/repo/branch 지정, 연결 테스트 |
| 구독 관리 | 위 화면으로 이동 |
| 동기화 | 앱 실행 시 자동 동기화 on/off, Wi-Fi에서만, 동시 다운로드 상한, 마지막 동기화 시각, API 잔여 횟수 |
| 저장공간 | 사용량 표시, 오래된 노트 정리, 전체 삭제 후 재동기화 |
| 표시 | 다크/라이트/시스템, 글자 크기 |

### 인증 화면

최초 실행 시, 또는 사용자가 동기화를 시도했을 때 토큰이 없거나 거부된 경우. Device
Flow 코드 표시, 복사 버튼, 브라우저 열기, 승인 감지 후 자동 진행.

Device Flow 는 리다이렉트가 없어 GitHub 이 앱을 다시 열어주지 않는다 — 폴링이
승인을 감지한 시점에 **앱이 스스로 복귀**시킨다: `WebBrowser.dismissBrowser()`
(iOS) + 자기 자신으로의 딥링크 `Linking.openURL(Linking.createURL('/'))` (Android).

**인증은 노트 읽기를 막지 않는다.** 읽기 경로에는 토큰이 등장하지 않기 때문이다.

```
노트 읽기:  로컬 파일 + SQLite → WebView    (네트워크 0, 토큰 0)
동기화:     GitHub API                     (토큰 필요)
```

토큰이 없거나 거부되어도 이미 받아둔 노트는 정상적으로 읽힌다. 따라서 앱 실행 시
자동 동기화가 401/403을 받으면 인증 화면으로 밀어내지 않고 배너만 표시한다. 인증
화면으로 전환하는 것은 사용자가 동기화를 명시적으로 요청했을 때뿐이다. 9장 참조.

참고로 OAuth App의 `repo` scope user-to-server 토큰은 만료가 없다(사용자가 revoke
하거나 GitHub이 1년 미사용으로 정리하지 않는 한). 재인증은 드문 경로다.

## 8. 렌더링

WebView 하나로 통일한다. 앱 asset에 로컬 HTML 템플릿을 두고 `markdown-it`,
콜아웃 플러그인, `highlight.js`, `mermaid`, `KaTeX`를 번들한다. CDN을 사용하지
않으므로 완전히 오프라인에서 동작한다.

```
RN                            WebView (assets/renderer/index.html)
────────────────────────────────────────────────────────────────
.md 원문 텍스트  ──postMessage──►  frontmatter 제거
테마 설정                          → markdown-it (콜아웃 · 위키임베드 플러그인)
                                   → highlight.js / mermaid / KaTeX 후처리

base URL = 볼트 루트 (고정)  →  렌더러가 noteDir 를 받아 상대경로에 접두
```

base URL 을 노트 폴더가 아니라 **볼트 루트로 고정**한 이유: 노트를 바꿀 때마다
WebView 를 리로드하지 않기 위해서다. source 의 baseUrl 은 바꾸면 리로드가
일어나므로, 대신 render 메시지에 noteDir 를 실어 보내고 렌더러가 상대경로
이미지에 이를 접두한다. 코드블록에는 복사 버튼이 붙는다(WebView → RN copy
메시지 → expo-clipboard).

### WebView를 선택한 이유

- **mermaid** 다이어그램은 브라우저에서 SVG를 그리는 라이브러리다. 네이티브 렌더러를
  쓰더라도 다이어그램마다 WebView를 띄워야 하므로, 처음부터 전체를 WebView로 그리는
  것이 일관적이다.
- **드래그 텍스트 선택·복사**가 브라우저 기본 동작이라 별도 구현이 없다. 네이티브
  마크다운 렌더러는 문단 단위 선택만 가능해 문단을 가로지르는 드래그가 안 된다.
- **코드 하이라이팅, 수식(KaTeX)** 이 기성 라이브러리로 해결된다.
- `.html` 파일도 어차피 WebView로 봐야 하므로 **렌더 경로가 하나로 통일된다.**

### 세부 동작

- **frontmatter는 숨긴다.** 실측 2944개 노트 중 1365개(46%)에 YAML frontmatter가
  있다. markdown-it 기본 동작은 이를 수평선과 `키: 값` 텍스트로 렌더하므로, 파싱
  전에 정규식으로 제거한다. 본문만 보여 읽기에 집중된다.

- **콜아웃(`> [!NOTE]`)** 은 `markdown-it-obsidian-callouts`가 처리한다. 볼트에서
  가장 많이 쓰이는 확장 문법이며(519개 노트, 1693개), 타입별 색은 `data-callout`
  속성을 훅으로 Tailwind 토큰에서 가져온다. 2.3절 참조.

- **이미지 임베드(`![[파일명.png|718]]`)** 는 표준 마크다운이 아니므로 `markdown-it`이
  이미지로 인식하지 못하고 글자 그대로 출력한다. 실측 69개가 사용 중이라 전용 처리가
  필요하다.

  경로는 본문에 명시되어 있다(`![[attachments/Pasted image ....png]]`). 볼트가
  `attachments/` 규약으로 통일되어 있어 **SQLite `files` 테이블 조회가 필요하지
  않다.** 플러그인이 `[[`…`]]` 안을 읽어 `<img src="...">`로 치환하고, base URL이
  노트 폴더이므로 그대로 해석된다. 파일이 없으면(구독 범위 밖) `onerror`가 "이미지
  없음" 자리표시로 교체한다.

  크기 지정 `|718` · `|640x480`은 각각 `width` · `width`×`height`로 적용하고 화면
  초과를 막기 위해 `max-width:100%`를 함께 준다. 파일명을 얻으려면 `|`를 분리하는
  것이 어차피 필수이므로(분리하지 않으면 `"파일명.png|718"`을 찾는다) 크기 처리에
  추가 비용이 없다.

- 표준 마크다운 이미지(`![](attachments/x.png)` 543개)와 원격 URL(1436개)은 위
  플러그인이 `[[`에서만 발동하므로 markdown-it 기본 동작으로 통과한다. 전처리가
  들어가지 않는다.

- 위키링크는 `<a data-wikilink="...">`로 렌더한다. 탭하면 WebView가 RN에 알리고, RN이
  로컬 존재 여부를 확인해 있으면 이동, 없으면 "아직 받지 않은 노트입니다" 시트를
  띄운다. **렌더 시점에 링크 대상 존재 여부를 미리 조회하지 않는다** — 긴 노트에서
  불필요한 비용이 발생하기 때문이다.
- 이미지 로드 실패 시 깨진 아이콘 대신 "이미지 없음" 자리표시를 보여준다. 오프라인
  상태의 원격 URL 이미지가 이 경로를 탄다.
- **파일 경로는 URL 인코딩한다.** 볼트 파일명 79개에 `#` `&` `+` `,` `(` 같은 문자가
  있고(`@RestClientTest&MockRestServiceServer.md`, `C++(알고리즘)/`) 한글 파일명도
  전부 인코딩 대상이다. `file://` URL 생성과 위키링크 경로 조회 양쪽에 적용한다.
- `.html` 파일은 변환 없이 `file://`로 직접 로드한다. base URL이 같은 폴더이므로
  6장의 리소스 규약(`./assets/`)을 따르는 참조는 그대로 잡힌다. 13장 참조.
- 외부 http(s) 링크는 WebView 안에서 열지 않고 시스템 브라우저로 넘긴다.
- 읽기 전용이다. 편집 기능은 없다.
- 노트 본문 스타일시트는 `global.css`의 `--nv-` 토큰(light-dark)을 빌드 시
  추출해 생성한다. 앱 UI와 본문의 다크/라이트 테마가 한 출처에서 나온다.
  design.md 8장 참조.

## 9. 동기화 UX와 오류 처리

| 상황 | 표시 |
|---|---|
| 네트워크 없음 | "오프라인 상태입니다" — 노트 읽기는 정상 동작 |
| 변경 없음 | "이미 최신입니다" |
| 진행 중 | 스피너 + `12 / 47` + 현재 파일명 + 취소 버튼 |
| 완료 | "새 노트 5 · 갱신 3 · 삭제 1" |
| 부분 실패 | "3개 실패 — 다시 시도" 버튼. 이미 받은 파일은 유지 |
| 401 / 403 (수동 동기화) | "GitHub 재연결이 필요합니다" → 인증 화면 |
| 401 / 403 (자동 동기화) | 배너만 표시. **읽기를 막지 않고 인증 화면으로 밀어내지 않는다** (7장 인증 화면 참조) |
| 429 | 동시성 축소 후 자동 백오프 재시도. 사용자에게는 진행 중으로 표시 |
| 저장공간 부족 | 동기화 중단, 정리 안내 |
| 트리 truncated | 폴더별 조회로 폴백 |

동기화 실행 시점은 **앱 실행 시 자동 + 당겨서 새로고침**이다. 백그라운드 폴링은
하지 않는다.

동기화 도중 앱을 벗어나면 안드로이드가 JS 실행을 중단하므로 다운로드가 멈춘다.
앱으로 돌아오면 6장의 중단 복구 메커니즘에 따라 남은 파일부터 이어받는다. 이는
포그라운드 서비스 없이 백그라운드 다운로드가 불가능하기 때문이며, v1에서는 의도된
동작이다.

**로컬 알림은 넣지 않는다.** 유일한 발화 조건이 "동기화 중 앱을 벗어났다가 완료된
경우"인데, 위 제약 때문에 앱을 벗어나면 다운로드가 멈추므로 알림은 앱에 돌아온
뒤에야 울린다. 그때는 이미 화면을 보고 있어 정보 가치가 없다.

## 10. 테스트

### 10.1 자동화 테스트

테스트는 **`planSync` 순수 함수에 집중한다.** 원격 트리, 로컬 상태, 구독 목록을
fixture로 두고 예상 결과를 검증한다.

검증 케이스:

- 신규 파일 다운로드
- blob SHA 변경 시 재다운로드
- blob SHA 동일 시 스킵
- 원격에서 삭제된 파일의 로컬 삭제
- 구독 해제 시 해당 prefix 하위 전체 삭제
- 폴더 이름 변경 (삭제 + 신규로 처리되는지)
- 중단 복구: 47개 중 12개 완료 상태에서 정확히 35개만 요청하는지
- 점(.) 경로 제외: `.obsidian/app.json`, `Dev/.claude/x.md`처럼 중간 세그먼트가 점으로
  시작하는 경우도 제외되는지. 반대로 `Dev/v1.2/note.md`처럼 점이 세그먼트 시작이
  아닌 경우는 포함되는지
- 북마크·최근이 있는 파일이 삭제 대상일 때 함께 정리되는지

`src/tree/`의 순수 함수도 함께 검증한다.

- 부모 폴더 구독 시 자식 전체가 포함되는지
- 자식이 이미 구독된 상태에서 부모를 구독할 때 중복이 정리되는지
- 구독 해제 시 삭제 대상 prefix가 정확한지
- 폴더별 용량·파일 수 집계가 트리 데이터에서 정확히 계산되는지

렌더러의 순수 변환도 fixture로 검증한다. WebView 없이 Node에서 돌아간다.

- `![[attachments/Pasted image ....png|718]]` → `<img>` + `width:718px`
- `|640x480` → width×height, `max-width:100%` 동반
- 표준 문법 `![](attachments/x.png)`와 원격 URL이 변환 없이 통과하는지
- 특수문자·한글 파일명이 URL 인코딩되는지
- frontmatter가 제거되는지
- 콜아웃 19종(비표준 `warn`·`primary`, 대소문자 혼용, 접기 `[!tip]-`, 중첩 포함)

GitHub 클라이언트는 mock으로 401, 429, 타임아웃, 부분 실패를 주입한다.

렌더링과 화면은 수동 확인한다. 자동화 비용 대비 이득이 없다.

### 10.2 에뮬레이터 테스트

Android Studio의 AVD(API 34 이상, Pixel 계열 프로파일)를 사용한다. EAS 클라우드
빌드만으로는 반복 주기가 너무 느리므로, 개발 중에는 로컬 dev build를 쓴다.

```
npx expo run:android          # 최초 1회: dev build 생성 후 에뮬레이터에 설치
npx expo start --dev-client   # 이후: JS만 갱신되어 즉시 반영
```

이 방식은 Android Studio 설치가 필요하지만, 코드 수정이 초 단위로 반영되므로
개발 중에는 필수다. EAS 클라우드 빌드는 실기기에 넣을 APK를 뽑을 때만 사용한다.

**에뮬레이터에서 반드시 확인할 항목** — 대부분 자동화가 불가능하거나 비용이 과한
것들이다.

| 항목 | 확인 내용 |
|---|---|
| WebView 로컬 파일 | `file://` 로 이미지와 CSS가 로드되는지. `allowFileAccess`, `allowFileAccessFromFileURLs` 설정 누락 시 조용히 실패한다 |
| mermaid / KaTeX | 번들이 오프라인에서 실제로 렌더되는지. CDN 참조가 남아 있으면 여기서 드러난다 |
| 드래그 선택·복사 | 문단을 가로지르는 텍스트 선택이 되는지 (WebView 선택의 핵심 근거) |
| `.html` 파일 | 인접 `.css`·이미지가 base URL로 자동 해석되는지 |
| 오프라인 동작 | 비행기모드에서 노트 읽기가 정상이고, 동기화만 "오프라인" 안내로 막히는지 |
| 중단 복구 | 동기화 중 앱을 강제 종료 → 재실행 시 남은 파일부터 이어받는지 |
| 다크/라이트 | 시스템 테마 전환 시 WebView 안쪽 CSS까지 함께 바뀌는지 |
| 3555개 트리 | 구독 관리 화면의 스크롤이 끊기지 않는지 (FlashList 검증) |
| 콜아웃 | 1693개가 쓰이는 문법이므로 다크/라이트 양쪽에서 타입별 색이 맞는지 |
| 대용량 노트 렌더 | 최대 `kafka/02-design/02-protocol.md` 633KB, 600KB급 2개·200KB급 3개. markdown-it 파싱 + highlight.js 소요 시간과 스크롤 반응성을 확인한다. 느리면 청크 렌더나 하이라이팅 지연을 검토한다 |

**에뮬레이터로 검증할 수 없는 것:** 실제 LTE 환경에서의 동시성 튜닝. 에뮬레이터는
호스트 네트워크를 쓰므로 429나 모바일 회선 특성이 재현되지 않는다. 이 부분은
실기기에서 확인한다.

### 10.3 컴포넌트 테스트를 넣지 않는 이유

"컴포넌트를 검증하지 않는다"가 아니라 **검증할 가치가 있는 로직을 컴포넌트 밖으로
빼내어, 컴포넌트에 테스트할 것이 남지 않게 한다**는 방침이다.

이 앱에서 실제로 깨질 위험이 있는 지점 — WebView의 `file://` 접근, mermaid 오프라인
렌더, 드래그 선택, 3555개 스크롤 성능, WebView 내부 다크모드 CSS — 은 **전부 네이티브
계층에서 발생한다.** 컴포넌트 테스트는 네이티브 모듈을 mock으로 치환한 환경에서
돌아가므로 WebView가 빈 껍데기가 되어, 뷰어 화면을 테스트해도 "WebView가 배치되어
있다" 이상을 확인할 수 없다. 이 항목들은 10.2의 에뮬레이터 확인으로 다룬다.

반대로 컴포넌트 테스트가 잡을 수 있는 로직 — 트리 펼침/접힘 상태, 구독 체크 시
부모→자식 전파 규칙, 진행률 표시 포맷 — 은 `src/` 아래 순수 함수로 분리한다. 남는
컴포넌트는 계산 결과를 그리는 얇은 층이 된다.

`jest-expo`가 이미 설치되어 있으므로 방침이 바뀌면
`@testing-library/react-native` 추가만으로 전환할 수 있다.

### 10.4 실기기 확인

`preview` 프로파일로 APK를 빌드해 사이드로드한 뒤 다음을 확인한다.

- 실제 볼트(3402개 대상 중 구독분)로 최초 동기화 및 소요 시간
- 오프라인에서 원격 URL 이미지가 자리표시로 나오는 것이 실사용에 견딜 만한지
  (텍스트 읽기에는 영향 없음. 답답하면 12장의 이미지 캐시로 승격)
- LTE 환경에서 적응형 동시성이 실제로 동작하는지 (429 발생 시 축소 여부)
- Device Flow 인증 전 과정
- 배터리·저장공간 사용량

## 11. 프로젝트 구조

```
notevault/
  app.config.ts         Expo 앱 설정 (패키지명, 플러그인, client_id 주입)
  eas.json              빌드 프로파일 (development / preview / production)
  babel.config.js       worklets 플러그인 (배열 마지막). v5 는 nativewind 설정 불필요
  metro.config.js       withNativewind + html asset. 다크모드 함정 주석 참조
  postcss.config.mjs    @tailwindcss/postcss (Tailwind v4 는 config 파일 없음)
  global.css            --nv- 토큰(light-dark) + @theme — 색의 단일 출처
  tsconfig.json         strict, @/* → src/*
  .env.example          GITHUB_CLIENT_ID

  src/app/              Expo Router 화면 (expo-project-structure skill 에 따라 src/ 하위)
    _layout.tsx         Drawer 레이아웃 + 자동 동기화 + ErrorBoundary export
    index.tsx           홈 (빈 상태)
    note/[...path].tsx  뷰어 (WebView)
    settings/           설정, 구독 관리
    auth.tsx            Device Flow 인증

  src/
    sync/
      planSync.ts       순수 함수. 테스트의 중심
      runSync.ts        다운로드·삭제 실행 오케스트레이션
      pool.ts           적응형 동시성 풀 (드레인 루프)
      github.ts         API 클라이언트 (오류 분류)
      vaultFs.ts        임시 파일 → rename 원자적 쓰기
      http.ts           expo/fetch 배선
    db/                 SQLite 스키마·쿼리
    auth/               Device Flow, SecureStore
    store/              zustand (동기화 상태, 설정·테마)
    tree/               로컬 트리·원격 집계·구독 전파 (순수 함수)
    tw/                 NativeWind v5 className 래퍼 (useCssElement)
    render/             렌더러 asset 로드
    components/         DrawerContent, ErrorBoundary

  테스트는 소스 옆에 콜로케이션한다 (*.test.ts) — 별도 __tests__/ 없음

  assets/renderer/
    src/                템플릿 + 위키임베드 플러그인, frontmatter 제거
    dist/               esbuild 결과물 (앱에 포함)

  __tests__/            planSync fixture 테스트
  docs/superpowers/specs/
```

## 12. 향후 확장 (v1 범위 밖)

| 항목 | 필요 조건 |
|---|---|
| 원격 푸시 알림 | VPS 릴레이(webhook → FCM) 필요. 인증 로직 변경 없음 — 릴레이는 GitHub 토큰을 다루지 않는다 |
| 백그라운드 자동 동기화 | 포그라운드 서비스 또는 배터리 최적화 예외 처리 필요 |
| 본문 전문검색 | SQLite FTS. 받아둔 노트가 많아져 폴더 탐색이 답답해질 때 |
| 파일 단위 구독 | 폴더 단위로 부족함이 실제로 확인될 때 |
| 원격 URL 이미지 캐시 | 오프라인에서 원격 이미지(1436개)가 안 보이는 것이 실사용에 걸릴 때. 본문에서 http(s) URL을 추출해 동기화 시 내려받고 렌더 시 로컬 경로로 치환한다. GitHub 외 네트워크 요청·캐시 테이블·용량 관리가 추가된다 |

## 13. 미결 사항

구현 중에 결정할 항목이다. v1 범위 안에 있으나 지금 결정할 근거가 부족하다.

| 항목 | 결정에 필요한 것 |
|---|---|
| **GitHub PKCE의 `client_secret` 요구 여부** | GitHub 문서 재확인. secret 없이 통과한다면 Device Flow 대신 PKCE가 UX상 낫다 (3장 참조) |
| **`.html` 렌더 실사용 검증** | 볼트에 `.html`이 본격적으로 추가된 뒤 6장 리소스 규약(`./assets/`)이 유지되는지 확인. 규약을 따르면 앱에 추가 작업이 없다 |

### 해소된 항목

**`.html`의 `.css` 참조 해석** — 6장의 리소스 규약으로 확정했다. `.html`은 같은
폴더 또는 그 하위 `./assets/`를 참조하며, 폴더 단위 구독이므로 같은 prefix에
포함되고 base URL로 해석된다. 경로 재작성이나 참조 파일 동반 다운로드가 필요 없다.
상위 폴더(`../`)나 원격 CDN 참조는 규약 위반으로 두고 앱에서 특별 처리하지 않는다.
