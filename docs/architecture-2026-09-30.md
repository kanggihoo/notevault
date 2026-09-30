# NoteVault 구조 전환 기록 (2026-09-30)

모바일에서 Obsidian 볼트(`kanggihoo/obsidian`)를 보는 방법을 **Quartz 정적 사이트 → RN 앱 → Kotlin 네이티브 앱**으로 바꾼 기록이다.
결정 과정은 2026-09-29 설계 인터뷰(grilling)에서 합의했다.

---

## 1. 전환 전 구조

```
[Mac·Windows 의 Obsidian] --git push--> GitHub kanggihoo/obsidian
                                            │ (push 마다) GitHub Actions: sync-quartz-content.yml
                                            ▼
                                  GitHub kanggihoo/quartz-site-private (content/ 에 볼트 통째 복사)
                                            │ Cloudflare Pages 가 push 마다 전체 빌드
                                            ▼
                                  정적 웹사이트 ── 폰 브라우저로 열람
```

| 문제 | 원인 |
|---|---|
| 노트 하나만 고쳐도 전체 빌드, 오래 걸림 | 정적 사이트 생성기(Quartz)의 구조 |
| md 외 파일(html·pdf)을 자유롭게 못 봄 | Quartz 는 md 중심 |
| 2026-09-16 부터 동기화 중단 | `QUARTZ_PAT` 만료 → 워크플로 `Bad credentials` 로 24회 연속 실패 |
| 볼 때마다 네트워크 필요 | 웹사이트 |

같은 시기에 만든 **RN(Expo) 앱 `notevault`** 는 GitHub API 로 필요한 폴더만 받아 오프라인 저장하는 구조였지만,
- OAuth Device Flow 인증 + 2FA 때문에 재인증이 번거로웠고
- "GitHub 재연결 필요" 가 401·403·네트워크 오류를 구분하지 않아 원인을 알 수 없었고
- `![[Pasted image.png]]` 를 노트 폴더에서만 찾아 `attachments/` 의 이미지가 "이미지 없음" 으로 나왔다.

## 2. 전환 후 구조

```
[Mac·Windows 의 Obsidian] --git push--> GitHub kanggihoo/obsidian (단일 원천, 변경 없음)
                                            │  REST API (fine-grained PAT, Contents 읽기 전용)
                                            │  ① HEAD 커밋 SHA  ② 트리 1회  ③ 바뀐 파일만
                                            ▼
┌────────────── Kotlin 앱 (apps/android, com.ssafy.notevault.kotlin) ──────────────┐
│ GithubClient ─ SyncController/runSync ─ planSync(blob SHA 비교) ─ 적응형 동시 다운로드 │
│        │                    │                                                  │
│  SettingsStore          Room DB (files·subscriptions·meta·bookmarks·recents)    │
│  (DataStore+Keystore)   filesDir/vault/ (받은 파일, 원자적 쓰기)                  │
│        │                    │                                                  │
│  Compose 화면: 설정 · 구독 관리 · 홈(동기화) · 서랍(트리·검색·즐겨찾기·최근) · 노트   │
│                             │                                                  │
│  노트 화면 ── WebView ── https://appassets.androidplatform.net                    │
│               /assets/renderer/index.html  ← 렌더러(JS 번들, 아래 3장)             │
│               /vault/...                   ← 받은 파일 (이미지·html 자원)          │
└───────────────────────────────────────────────────────────────────────────────┘
```

| 이전 문제 | 지금 |
|---|---|
| 전체 빌드 | 빌드 없음. 바뀐 파일(blob SHA 다름)만 받는다. 변경 없으면 요청 1번 |
| 온라인 필요 | 구독한 폴더는 폰에 저장 → 오프라인 열람 |
| md 외 파일 | md·html·이미지는 앱 안에서, 그 외(PDF 등)는 FileProvider 로 다른 앱에 넘김 |
| Device Flow·2FA | PAT 를 한 번 붙여넣기. 폰에서 로그인하지 않는다. 만료 D-7 경고 |
| 원인 없는 "재연결 필요" | 401/403/404/rate limit/네트워크를 구분하고 GitHub 메시지를 그대로 표시 |
| 이미지 없음 | `LinkResolver` 가 볼트 전체에서 Obsidian 규칙(가까운 폴더 우선)으로 찾음 |

Syncthing·VPS 방식은 검토 후 기각했다 — PC 두 대가 git 으로 동기화하는데 Syncthing 을 더하면 동기화 경로가 이중이 되고, 교육장 방화벽 문제도 있다.

## 3. 이중 구조: Kotlin 과 JS(RN 툴체인)의 역할

지금 저장소에는 두 세계가 같이 있다. **폰에서 돌아가는 앱은 Kotlin 앱 하나**이고, JS 쪽은 **렌더러 번들을 만드는 도구**로만 쓰인다.

| 영역 | 담당 | 위치 | 비고 |
|---|---|---|---|
| 인증·설정 (PAT, 저장소, 만료 경고) | **Kotlin** | `apps/android/.../settings/` | |
| GitHub API·동기화·DB·파일 저장 | **Kotlin** | `.../github/`, `.../sync/`, `.../db/` | RN 설계(planSync·pool·스키마)를 이식 |
| 화면 전부 (Compose) | **Kotlin** | `.../ui/`, `.../settings/`, `.../sync/`, `.../vault/` | |
| 링크·이미지 해석 | **Kotlin** | `.../vault/LinkResolver.kt` | |
| WebView 호스팅·통로 | **Kotlin** | `.../vault/VaultWeb.kt`, `RendererViews.kt` | |
| **마크다운 → HTML 렌더링** | **JS** | `assets/renderer/src/` | markdown-it·callout·mermaid·KaTeX·highlight.js |
| 렌더러 빌드 | **JS(Node)** | `assets/renderer/build.mjs` (`npm run renderer:build`) | 결과물 `assets/renderer/dist/index.html` 하나 |
| 렌더러 테스트 | **JS(RN 도구)** | `assets/renderer/src/*.test.ts` | `jest-expo` 프리셋으로 돈다 |
| 렌더러 색 토큰 | **RN 파일** | `global.css` | `build.mjs` 가 `--nv-` 변수를 읽는다 |
| 렌더러를 앱에 넣기 | **Gradle** | `apps/android/app/build.gradle.kts` 의 `syncRenderer` | 빌드마다 `dist/index.html` → 앱 assets 자동 복사 |
| 옛 RN 앱 | (사용 중단) | `src/` 등, 4장 | 비교용으로만 남김 |

렌더러는 RN 앱과 Kotlin 앱이 **같은 번들을 공유**한다. 통로만 다르다.

| | RN 앱 | Kotlin 앱 |
|---|---|---|
| 앱 → 렌더러 | `postMessage` | `evaluateJavascript("window.nvReceive(...)")` |
| 렌더러 → 앱 | `window.ReactNativeWebView.postMessage` | `window.NoteVault.postMessage` (`@JavascriptInterface`) |
| 이미지 경로 | 노트 폴더 기준 | 앱이 넘긴 `images` 맵(볼트 전체 해석) 우선 |

## 4. 이제 쓰이지 않는 코드·디렉토리

Kotlin 앱은 아래를 **전혀 사용하지 않는다.** 옛 RN 앱을 비교용으로 남겨 둔 것뿐이다.

| 경로 | 무엇 | 지울 때 주의 |
|---|---|---|
| `src/` 전체 | RN 앱 (화면·Device Flow·동기화·DB·스토어·NativeWind 컴포넌트) | 없음 |
| `app.config.js`, `eas.json` | Expo 앱 설정·EAS 빌드 프로필 | 없음 |
| `babel.config.js`, `metro.config.js`, `postcss.config.mjs`, `nativewind-env.d.ts` | RN 번들러·스타일 설정 | 없음 |
| `.env`, `.env.example` | Device Flow 용 `GITHUB_CLIENT_ID` | GitHub 의 OAuth App(`notevault-oauth`)도 필요 없으면 폐기 |
| `android/` (git 무시됨) | RN prebuild 결과, `app-release.apk`(약 110MB) | 디스크만 차지 |
| `.expo/` (git 무시됨) | Expo 캐시 | 없음 |
| `design.md`, `docs/superpowers/specs/2026-08-14-notevault-design.md` | RN 판 설계 문서 | 참고 자료로 보관해도 됨 |
| `assets/renderer/dist/renderer.js`, `renderer.css` | 렌더러 빌드 중간 산출물 (앱은 `index.html` 만 씀) | `build.mjs` 가 다시 만든다 |
| `package.json` 의존성 중 `expo*`, `react*`, `nativewind`, `zustand` 등 | RN 앱용 | 아래 "지우면 안 되는 것" 먼저 옮길 것 |

**RN 을 지울 때 먼저 옮겨야 하는 것** (지금은 RN 파일에 기대고 있다)
- `global.css` 의 `--nv-` 색 토큰 → 렌더러 전용 파일로
- `jest-expo` 프리셋 → 렌더러 테스트를 순수 jest(또는 vitest) 설정으로
- `package.json` 에서 렌더러가 쓰는 것만 남기기: `esbuild`, `markdown-it`, `markdown-it-obsidian-callouts`, `highlight.js`, `katex`, `mermaid`, `@types/markdown-it`, 테스트 러너
- 그 뒤 `assets/renderer/` → `renderer/` 로 옮기고 `syncRenderer` 의 경로 한 줄을 고친다

## 5. 저장소 밖의 정리 상태

| 대상 | 상태 |
|---|---|
| 볼트의 `.github/workflows/sync-quartz-content.yml` | ✅ 삭제 (obsidian 저장소 `f84a6c1`) |
| 볼트 저장소 시크릿 `QUARTZ_PAT` | ⏳ 남아 있음 (자동 권한 검사로 막혀 사용자가 직접 삭제해야 함) |
| `kanggihoo/quartz-site-private` | ⏳ 보관(archive) 전 |
| Cloudflare Pages 프로젝트 | ⏳ 2026-09-15 내용으로 멈춘 채 공개 중 |
| VPS | Quartz 와 무관해짐 (사용자 확인) |
