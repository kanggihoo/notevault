package com.ssafy.notevault.vault

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.notevault.db.VaultDao
import com.ssafy.notevault.sync.VaultFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface NoteContent {
    data object Loading : NoteContent
    data class Text(val text: String) : NoteContent
    data class Image(val bitmap: ImageBitmap) : NoteContent
    /** 앱이 직접 보여주지 않는 형식(PDF 등) — 다른 앱으로 넘긴다. */
    data object External : NoteContent
    data class Error(val message: String) : NoteContent
}

/** 파일 하나를 연다. 열면 최근 기록에 남긴다. 4단계에서 md·html 은 렌더러로 바뀐다. */
class NoteViewModel(
    val path: String,
    private val dao: VaultDao,
    private val files: VaultFiles,
    now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _content = MutableStateFlow<NoteContent>(NoteContent.Loading)
    val content: StateFlow<NoteContent> = _content.asStateFlow()

    val bookmarked: StateFlow<Boolean> = dao.observeBookmarks()
        .map { path in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            dao.touchRecent(path, now())
            _content.value = load()
        }
    }

    fun toggleBookmark() {
        viewModelScope.launch { dao.toggleBookmark(path, System.currentTimeMillis()) }
    }

    private suspend fun load(): NoteContent = try {
        if (!files.exists(path)) {
            NoteContent.Error("아직 받지 않은 파일이에요. 구독 관리에서 이 폴더를 구독하고 동기화하세요.")
        } else {
            when (fileKind(path)) {
                FileKind.Markdown, FileKind.Html, FileKind.Text -> NoteContent.Text(files.readText(path))
                FileKind.Image -> withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(files.resolve(path).path)
                    if (bitmap == null) NoteContent.Error("이미지를 열 수 없어요.") else NoteContent.Image(bitmap.asImageBitmap())
                }
                FileKind.Other -> NoteContent.External
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NoteContent.Error("파일을 읽지 못했어요: ${e.message}")
    }
}
