package com.ssafy.notevault.vault

import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import java.lang.ref.WeakReference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject

/** E2E 테스트가 지금 화면의 WebView 에 JS 를 실행하려고 쓴다. 앱 동작에는 쓰지 않는다. */
@VisibleForTesting
object CurrentWebView {
    @Volatile
    var ref: WeakReference<WebView>? = null
}

/** 렌더러에게 그려 달라고 보낼 내용. */
data class RenderRequest(val markdown: String, val noteDir: String, val images: Map<String, String>)

/** 렌더러가 알려오는 사건. */
sealed interface RendererEvent {
    data class Wikilink(val target: String) : RendererEvent
    data class Relative(val href: String) : RendererEvent
    data class External(val href: String) : RendererEvent
    data class Copy(val text: String) : RendererEvent
}

/**
 * 렌더러 페이지를 한 번 띄워 두고, 준비(ready)되면 render 메시지를 보낸다.
 * Compose 는 WebView 를 모르므로 AndroidView 로 기존 View 를 끼워 넣는다.
 */
private class RendererController(private val onEvent: (RendererEvent) -> Unit) {
    var webView: WebView? = null
    private var ready = false
    private var pending: String? = null

    fun onJsMessage(raw: String) {
        val message = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        fun field(name: String) = message[name]?.jsonPrimitive?.content.orEmpty()
        val event = when (field("type")) {
            "ready" -> {
                ready = true
                pending?.let(::send)
                null
            }
            "wikilink" -> RendererEvent.Wikilink(field("target"))
            "relative" -> RendererEvent.Relative(field("href"))
            "external" -> RendererEvent.External(field("href"))
            "copy" -> RendererEvent.Copy(field("text"))
            else -> null
        }
        event?.let(onEvent)
    }

    fun render(request: RenderRequest, dark: Boolean) {
        val json = buildJsonObject {
            put("type", "render")
            put("markdown", request.markdown)
            put("noteDir", request.noteDir)
            put("theme", if (dark) "dark" else "light")
            put("baseUrl", VaultWeb.VAULT_BASE)
            putJsonObject("images") { request.images.forEach { (k, v) -> put(k, v) } }
        }
        if (ready) send(json) else pending = json.toString()
    }

    private fun send(json: JsonObject) = send(json.toString())

    private fun send(json: String) {
        pending = null
        // JSONObject.quote: 문자열을 JS 문자열 리터럴로 안전하게 감싼다 (따옴표·줄바꿈 이스케이프).
        webView?.evaluateJavascript("window.nvReceive(${JSONObject.quote(json)})", null)
    }
}

@Composable
fun MarkdownView(
    request: RenderRequest,
    dark: Boolean,
    loader: WebViewAssetLoader,
    onEvent: (RendererEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { RendererController(onEvent) }
    AndroidView(
        modifier = modifier.testTag("note-webview"),
        factory = { context ->
            WebView(context).apply {
                VaultWeb.configure(this, loader)
                // JS 스레드에서 오므로 WebView 의 메인 스레드로 넘겨 처리한다.
                addJavascriptInterface(RendererBridge { raw -> post { controller.onJsMessage(raw) } }, "NoteVault")
                controller.webView = this
                CurrentWebView.ref = WeakReference(this)
                loadUrl(VaultWeb.RENDERER_URL)
            }
        },
        update = { controller.render(request, dark) },
    )
}

/** 볼트의 .html 파일을 그대로 띄운다. 상대 경로 css·js·이미지도 같은 도메인이라 함께 풀린다. */
@Composable
fun HtmlPageView(path: String, loader: WebViewAssetLoader, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.testTag("html-webview"),
        factory = { context ->
            WebView(context).apply {
                VaultWeb.configure(this, loader)
                CurrentWebView.ref = WeakReference(this)
                loadUrl(VaultWeb.vaultUrl(path))
            }
        },
    )
}
