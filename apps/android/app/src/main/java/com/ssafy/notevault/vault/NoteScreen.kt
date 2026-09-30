package com.ssafy.notevault.vault

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.ui.MessageCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(container: AppContainer, path: String, onBack: () -> Unit, onOpenFile: (String) -> Unit) {
    val vm = viewModel(key = path) { NoteViewModel(path, container.database.dao(), container.vaultFiles) }
    val content by vm.content.collectAsStateWithLifecycle()
    val bookmarked by vm.bookmarked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    fun openOrToast(resolved: String?, label: String) {
        if (resolved != null) {
            onOpenFile(resolved)
        } else {
            Toast.makeText(context, "'$label' 을(를) 찾을 수 없어요. 받지 않은 폴더일 수 있어요.", Toast.LENGTH_SHORT).show()
        }
    }

    val onRendererEvent: (RendererEvent) -> Unit = { event ->
        when (event) {
            is RendererEvent.Wikilink -> openOrToast(vm.resolveWikilink(event.target), event.target)
            is RendererEvent.Relative -> openOrToast(vm.resolveRelative(event.href), event.href)
            is RendererEvent.External -> VaultWeb.openInBrowser(context, event.href.toUri())
            is RendererEvent.Copy -> context.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("code", event.text))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    IconButton(onClick = vm::toggleBookmark) {
                        // 테두리만 있는 별은 확장 아이콘 묶음에 있어서, 색으로 켜짐·꺼짐을 구분한다.
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = if (bookmarked) "즐겨찾기 해제" else "즐겨찾기 추가",
                            tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val c = content) {
                NoteContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is NoteContent.Markdown -> MarkdownView(
                    request = c.request,
                    dark = dark,
                    loader = container.webAssetLoader,
                    onEvent = onRendererEvent,
                    modifier = Modifier.fillMaxSize(),
                )
                NoteContent.HtmlPage -> HtmlPageView(path, container.webAssetLoader, Modifier.fillMaxSize())
                is NoteContent.Text -> SelectionContainer {
                    // 코드·설정 파일은 원문 그대로. 긴 줄은 가로로 스크롤한다.
                    Text(
                        c.text,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is NoteContent.Image -> Image(
                    c.bitmap,
                    contentDescription = displayName(path),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
                NoteContent.External -> Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("이 형식은 앱에서 바로 보여주지 않아요.")
                    Button(onClick = { openExternally(context, container.vaultFiles.resolve(path)) }) {
                        Text("다른 앱으로 열기")
                    }
                }
                is NoteContent.Error -> MessageCard(c.message, isError = true, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/**
 * 앱 전용 폴더의 파일을 다른 앱에 넘긴다. 파일 경로를 직접 주면 다른 앱이 읽을 수 없으므로
 * FileProvider 가 content:// 주소를 만들고, 그 파일 하나에만 임시 읽기 권한을 준다.
 */
private fun openExternally(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "다른 앱으로 열기"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "이 파일을 열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
    }
}
