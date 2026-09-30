package com.ssafy.notevault

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStore
import com.ssafy.notevault.db.VaultDatabase
import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.settings.KeystoreTokenCipher
import com.ssafy.notevault.settings.SettingsStore
import com.ssafy.notevault.sync.SyncController
import com.ssafy.notevault.sync.VaultFiles
import com.ssafy.notevault.vault.VaultWeb
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 수동 DI. 앱 전체에서 하나씩만 있어야 하는 객체를 여기서 만든다 (Spring 의 @Configuration 역할).
 * `by lazy` — 처음 쓰일 때 한 번만 만든다.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val database: VaultDatabase by lazy { VaultDatabase.create(app) }

    /** 앱 전용 폴더라 저장소 권한이 필요 없다. 앱을 지우면 같이 지워진다. */
    val vaultFiles: VaultFiles by lazy { VaultFiles(File(app.filesDir, "vault").apply { mkdirs() }) }

    val settingsStore: SettingsStore by lazy { SettingsStore(app.settingsDataStore, KeystoreTokenCipher()) }

    val syncController: SyncController by lazy {
        SyncController(settingsStore, database.dao(), vaultFiles, ::githubClient)
    }

    /** WebView 가 렌더러(assets)와 볼트 파일을 https 주소로 읽게 해 준다. */
    val webAssetLoader by lazy { VaultWeb.assetLoader(app, vaultFiles.root) }

    /** E2E 테스트가 가짜 GitHub(MockWebServer) 주소로 바꿔 끼운다. 앱에서는 건드리지 않는다. */
    @VisibleForTesting
    var githubBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl()

    /** 연결 풀을 공유하도록 OkHttpClient 는 하나만 둔다. */
    private val http: OkHttpClient by lazy { OkHttpClient() }

    fun githubClient(token: String): GithubClient = GithubClient(token, http, githubBaseUrl)
}
