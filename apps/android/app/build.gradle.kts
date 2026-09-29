plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
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
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
