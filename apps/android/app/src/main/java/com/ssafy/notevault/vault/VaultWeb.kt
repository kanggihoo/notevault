package com.ssafy.notevault.vault

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * WebView 가 렌더러와 볼트 파일을 읽는 주소.
 *
 * file:// 을 열지 않고 WebViewAssetLoader 로 https 가짜 도메인에 매핑한다 (Android 권장 방식).
 *   https://appassets.androidplatform.net/assets/renderer/index.html → 앱 assets (렌더러)
 *   https://appassets.androidplatform.net/vault/spring/a.png         → filesDir/vault/spring/a.png
 * 그래서 html 의 ../assets/lesson.css 같은 상대 경로도 그대로 풀린다.
 */
object VaultWeb {
    const val HOST = "appassets.androidplatform.net"
    const val RENDERER_URL = "https://$HOST/assets/renderer/index.html"
    const val VAULT_BASE = "https://$HOST/vault/"

    fun vaultUrl(path: String): String = VAULT_BASE + path.split('/').joinToString("/") { Uri.encode(it) }

    fun assetLoader(context: Context, vaultRoot: File): WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/vault/", WebViewAssetLoader.InternalStoragePathHandler(context, vaultRoot))
        .build()

    /** 볼트 안 주소는 WebView 안에서, 그 밖(http 링크)은 시스템 브라우저로. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, loader: WebViewAssetLoader) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true // html 강의 파일의 퀴즈가 localStorage 를 쓴다
        webView.settings.allowFileAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                openInBrowser(view.context, request.url)
                return true
            }
        }
    }

    fun openInBrowser(context: Context, uri: Uri) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * 렌더러(JS) → 앱 통로. JS 에서 `window.NoteVault.postMessage(json)` 으로 부른다.
 * JS 스레드에서 불리므로 화면 작업은 [onMessage] 쪽에서 메인 스레드로 넘긴다.
 */
class RendererBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String) = onMessage(json)
}
