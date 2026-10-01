plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/**
 * 릴리스 버전. `./gradlew assembleRelease -PappVersion=0.2.0` 처럼 넘긴다 (없으면 0.1.0).
 * versionCode 는 버전에서 계산한다 — 0.2.0 → 200, 1.4.3 → 10403. 업데이트 설치는 이 값이 커져야만 된다.
 */
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

/**
 * 릴리스 서명 키. 저장소에 넣지 않고 사용자 홈의 ~/.gradle/gradle.properties (또는 같은 이름의 환경변수)에서 읽는다.
 *   notevault.keystore=C:/Users/me/.android-keys/notevault-release.jks
 *   notevault.keystorePassword=...
 *   notevault.keyAlias=notevault
 *   notevault.keyPassword=...
 */
fun secret(name: String): String? =
    (findProperty("notevault.$name") as String?) ?: System.getenv("NOTEVAULT_" + name.replace(Regex("([A-Z])"), "_$1").uppercase())

android {
    // 코드 패키지. applicationId 와 별개다.
    namespace = "com.ssafy.notevault"
    compileSdk = 37

    defaultConfig {
        // RN 앱(com.ssafy.notevault)과 나란히 설치하기 위해 다른 ID 를 쓴다.
        applicationId = "com.ssafy.notevault.kotlin"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val keystore = secret("keystore")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = secret("keystorePassword")
                keyAlias = secret("keyAlias")
                keyPassword = secret("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 키가 없으면 서명 안 된 APK 가 나온다 (폰에 설치 불가). 키 설정은 docs/runbook-android.md 8장.
            signingConfig = signingConfigs.findByName("release")
            // 첫 릴리스는 코드 축소(R8)를 끈다. 켜려면 Room·직렬화·@JavascriptInterface 보호 규칙이 필요하다.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric 이 AndroidManifest·리소스를 읽을 수 있게 한다.
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * `./gradlew e2e` — 연결된 에뮬레이터·폰에서 앱 전체 통합 테스트(src/androidTest)를 돌린다.
 * package.json 의 "scripts": { "e2e": ... } 에 해당한다.
 */
tasks.register("e2e") {
    group = "verification"
    description = "에뮬레이터/폰에서 앱 전체 + 가짜 GitHub 통합 테스트"
    dependsOn("connectedDebugAndroidTest")
}

/**
 * 렌더러 번들(markdown-it·mermaid·KaTeX·highlight.js 를 한 파일로 묶은 index.html)을 앱 assets 로 가져온다.
 * 원본은 저장소의 assets/renderer — `npm run renderer:build` 로 다시 만든다. 빌드할 때마다 최신본이 복사된다.
 */
abstract class SyncRendererTask : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve("renderer").apply { mkdirs() }
        source.get().asFile.copyTo(target.resolve("index.html"), overwrite = true)
    }
}

val syncRenderer = tasks.register<SyncRendererTask>("syncRenderer") {
    source.set(rootProject.layout.projectDirectory.file("../../assets/renderer/dist/index.html"))
    outputDir.set(layout.buildDirectory.dir("generated/renderer-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncRenderer, SyncRendererTask::outputDir)
    }
}

room {
    // 스키마 JSON 을 커밋해 두면 버전을 올릴 때 마이그레이션을 검증할 수 있다.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlin.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
