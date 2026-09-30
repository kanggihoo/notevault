# NoteVault Android 런북

코드를 고친 뒤 **빌드 → 테스트 → 폰에 설치**하는 방법. Play 스토어 배포는 하지 않고, 폰에 직접 설치한다.
구조 설명은 [architecture-2026-09-30.md](architecture-2026-09-30.md).

> 모든 명령은 Windows 기준. `gradlew` 명령은 `apps/android` 폴더에서, `npm` 명령은 저장소 루트(`notevault/`)에서 실행한다.
> Git Bash 에서는 `./gradlew`, PowerShell 에서는 `.\gradlew` 로 쓴다.

---

## 0. 처음 한 번만

| 필요한 것 | 확인 |
|---|---|
| Android Studio (AGP 9.3 지원 버전) | 더 오래된 버전이면 "incompatible AGP" 오류 → Studio 를 올리거나 `gradle/libs.versions.toml` 의 `agp` 를 맞춘다 |
| JDK 17+ | Android Studio 내장 JDK 로 충분 |
| Node.js | 렌더러(JS)를 고칠 때만. `npm install` 한 번 |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (PATH 에 없으면 전체 경로로) |

Android Studio 로 열 때는 **`apps/android` 폴더**를 연다. 저장소 루트나 `android/`(RN 이 만든 폴더)가 아니다.

## 1. 무엇을 고쳤나에 따라

| 고친 곳 | 할 일 |
|---|---|
| Kotlin (`apps/android/app/src/main`) | 2 → 3 → 5 |
| 렌더러 JS (`assets/renderer/src`) | **먼저 `npm run renderer:build`** → 렌더러 테스트 → 2 → 3 → 5 |
| 둘 다 | 렌더러 빌드 먼저, 나머지는 같음 |

렌더러를 고쳤는데 빌드를 안 하면 앱에는 **옛 `dist/index.html`** 이 들어간다. Gradle 의 `syncRenderer` 는 복사만 하고 JS 빌드는 하지 않는다.

```bash
# 저장소 루트
npm run renderer:build             # assets/renderer/dist/index.html 갱신
npx jest assets/renderer           # 렌더러 JS 테스트 (현재 28개)
```

## 2. 빌드

```bash
cd apps/android
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 에서는 **Build → Assemble Project** 또는 그냥 ▶ Run (빌드 + 설치 + 실행).

## 3. 테스트

| 명령 | 무엇 | 필요한 것 | 시간 |
|---|---|---|---|
| `./gradlew testDebugUnitTest` | 단위·슬라이스·통합 (현재 118개): 동기화·DB·오류 분류·링크 해석·ViewModel | 없음 (PC 의 JVM + Robolectric) | 수십 초 |
| `./gradlew e2e` | 앱 전체 + 기기 안 가짜 GitHub(MockWebServer) (현재 4개): 설정 → 구독 → 동기화 → 서랍 → 노트 렌더링 → 위키링크 | 켜진 에뮬레이터 또는 USB 연결된 폰 | 1분 안팎 |
| `npx jest assets/renderer` | 렌더러 JS (현재 28개) | Node | 수 초 |

결과 보고서
- 단위: `app/build/reports/tests/testDebugUnitTest/index.html`
- E2E: `app/build/reports/androidTests/connected/debug/index.html`

**E2E 주의**
- 테스트가 **앱 설정과 받은 파일을 지우고**, 끝나면 **앱을 삭제**한다. 실제 폰에서 돌리면 넣어 둔 PAT 가 사라진다 → E2E 는 에뮬레이터에서 돌리는 것을 권장.
- 앱을 남기고 싶으면 `./gradlew e2e -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
- 에뮬레이터는 **별도 창으로** 띄운다. Android Studio 안(Running Devices)에 끼워 띄우면 테스트 중 `device offline` 으로 결과 수집이 실패한 적이 있다.
  (Settings → Tools → Emulator → "Launch in the Running Devices tool window" 해제)
- E2E 코드에서 기다릴 때 `Thread.sleep` 을 쓰지 않는다. Compose 테스트의 화면 시계가 멈춰 화면 전환이 안 일어난다 → `compose.waitUntil { ... }`.

## 4. 에뮬레이터

1. Android Studio → Device Manager → 가상 기기(현재 `Pixel_8`, API 36) ▶
2. 연결 확인: `adb devices` → `emulator-5554  device`
3. 화면이 너무 크면: 창 모서리를 끌거나 `Ctrl + ↓`. 폰 테두리는 `%USERPROFILE%\.android\avd\Pixel_8.avd\config.ini` 의 `showDeviceFrame=no` (재시작 필요)

## 5. 실제 폰에 설치

### 5-1. 폰 준비 (처음 한 번)
1. 설정 → 휴대전화 정보 → 소프트웨어 정보 → **빌드번호 7번 탭** → 개발자 모드
2. 설정 → 개발자 옵션 → **USB 디버깅** 켜기
3. **데이터 케이블**로 PC 연결 → 폰의 "USB 디버깅 허용" 에서 **"이 컴퓨터에서 항상 허용"** 체크
4. `adb devices` 에 기기가 `device` 로 보이면 성공. `unauthorized` 면 폰 팝업을 다시 확인. 안 보이면 삼성폰은 Samsung Android USB Driver 설치.

### 5-2. 설치
- **Android Studio**: 기기 선택 칸에서 폰 선택 → ▶ Run
- **명령줄**:
  ```bash
  cd apps/android
  ./gradlew installDebug                                  # 빌드 + 연결된 기기에 설치
  # 또는 이미 만든 APK 를 직접
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **케이블 없이**: `app-debug.apk` 를 폰으로 옮겨 파일 앱에서 열기 → "출처를 알 수 없는 앱 설치" 허용
- **무선 디버깅**(Android 11+): 개발자 옵션 → 무선 디버깅 → Android Studio 의 "Pair Devices Using Wi-Fi" QR. 같은 Wi-Fi 필요 (교육장 Wi-Fi 는 막혀 있을 수 있다)

### 5-3. 알아둘 것
- 앱 ID `com.ssafy.notevault.kotlin` — RN 앱(`com.ssafy.notevault`)과 **따로** 설치된다.
- 디버그 빌드는 이 PC 의 debug 키로 서명된다. **다른 PC 에서 만든 APK 로 덮어쓰면 서명이 달라 설치가 거부**되고, 지웠다 다시 깔면 PAT·받은 파일이 사라진다. 한 PC 에서 빌드해 설치하는 것을 원칙으로 한다.
- `-r` 로 덮어쓰면 설정·받은 파일은 유지된다.

## 6. PAT 넣기

### 6-1. PAT 발급 (PC, 만료 시마다)
GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate
- Repository access: **Only select repositories → `obsidian`**
- Permissions → Repository → **Contents: Read-only** (Metadata 는 자동)
- Expiration: 원하는 기간. 앱이 만료 7일 전부터 경고한다.

### 6-2. 폰에 넣는 방법 (셋 중 하나)

**A. 에뮬레이터** — PC 에서 복사 → 앱 토큰 칸을 길게 눌러 붙여넣기 (클립보드 공유)

**B. USB 로 연결한 폰 (추가 설치 없음, 권장)**
1. 앱 설정 화면에서 **토큰 칸을 한 번 눌러** 커서를 둔다
2. PC 에서:
   ```bash
   adb shell input text github_pat_여기에_토큰
   ```
   PAT 는 영문·숫자·`_` 로만 되어 있어 그대로 입력된다. 명령이 셸 기록에 남으므로 입력 뒤 기록을 지우거나 새 터미널을 닫는다.

**C. QR 코드 (케이블이 없을 때)**
1. PC 에서 **로컬로** QR 을 만든다. ⚠️ 온라인 QR 생성 사이트는 쓰지 않는다 — 토큰이 외부 서버로 간다.
   ```bash
   pip install qrcode                 # 한 번만
   python -c "import qrcode,getpass; q=qrcode.QRCode(); q.add_data(getpass.getpass('PAT: ')); q.print_ascii(invert=True)"
   ```
   (토큰은 화면에 보이지 않게 입력되고, 터미널에 QR 이 그려진다)
2. 폰 **기본 카메라**의 QR 인식으로 찍는다 → "텍스트 복사"
3. 앱 토큰 칸을 길게 눌러 붙여넣기 → **저장하고 연결 테스트**
4. PC 터미널을 지워 QR 을 없앤다

앱 안에는 QR 스캐너가 없다 (합의: 1년에 한두 번 넣는 값이라 카메라 권한·ML Kit 까지는 과하다).

## 7. 문제 해결

| 증상 | 확인 |
|---|---|
| Android Studio: incompatible AGP | 1장 "처음 한 번만" |
| `./gradlew` 가 끝나지 않는 것처럼 보임 | 출력을 파이프로 걸러 볼 때 생겼다. `> build.log 2>&1` 로 파일에 남기고 확인 |
| 연결 테스트 401 | 토큰 오타·만료·폐기. 카드의 GitHub 메시지를 확인 |
| 연결 테스트 403 | 토큰에 `obsidian` 저장소 Contents 읽기 권한이 있는지 |
| 연결 테스트 404 | owner·repo·branch 이름. 권한 없는 private 저장소도 404 로 보인다 |
| "네트워크 연결 실패" | Wi-Fi 가 GitHub 을 막는지 LTE 로 비교 |
| 노트가 빈 화면 | 렌더러 빌드 여부(1장). `adb logcat | grep chromium` 으로 JS 오류 확인 |
| 이미지 "이미지 없음" | 그 이미지가 있는 폴더를 구독했는지. 앱은 **받은 파일 안에서만** 찾는다 |
| 단위 테스트가 Windows 에서만 실패 | Robolectric 은 SDK 35 로 고정(`src/test/resources/robolectric.properties`), DataStore 테스트는 메모리 구현 사용 — 이미 반영됨 |
