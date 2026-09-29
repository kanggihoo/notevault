plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    // 코드 패키지. applicationId 와 별개다.
    namespace = "com.ssafy.notevault"
    compileSdk = 37

    defaultConfig {
        // RN 앱(com.ssafy.notevault)과 나란히 설치하기 위해 다른 ID 를 쓴다.
        applicationId = "com.ssafy.notevault.kotlin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric 이 AndroidManifest·리소스를 읽을 수 있게 한다.
        unitTests.isIncludeAndroidResources = true
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
}
