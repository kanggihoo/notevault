package com.ssafy.notevault.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.notevault.db.VaultDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class DrawerTab(val label: String) { Files("파일"), Bookmarks("즐겨찾기"), Recents("최근") }

data class BrowserUiState(
    val query: String = "",
    val tab: DrawerTab = DrawerTab.Files,
    val rows: List<LocalRow> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val searchResults: List<String> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
)

/**
 * 서랍(파일 트리·검색·즐겨찾기·최근)의 상태.
 *
 * DB 의 Flow 들과 사용자 입력(검색어·탭·펼침)을 [combine] 으로 묶는다 —
 * 어느 하나가 바뀌면 새 화면 상태가 다시 계산된다. 동기화로 파일이 늘어도 트리가 저절로 갱신된다.
 */
class VaultBrowserViewModel(dao: VaultDao) : ViewModel() {

    private val query = MutableStateFlow("")
    private val tab = MutableStateFlow(DrawerTab.Files)
    private val expanded = MutableStateFlow(emptySet<String>())

    /** 약 4천 개 파일의 트리 만들기는 메인 스레드 밖(Default)에서 한다. */
    private val tree = dao.observeFilePaths()
        .map { paths -> paths to buildLocalTree(paths) }
        .flowOn(Dispatchers.Default)

    private data class Controls(val query: String, val tab: DrawerTab, val expanded: Set<String>)

    private val controls = combine(query, tab, expanded, ::Controls)

    val state: StateFlow<BrowserUiState> =
        combine(tree, dao.observeBookmarks(), dao.observeRecents(), controls) { (paths, root), bookmarks, recents, c ->
            BrowserUiState(
                query = c.query,
                tab = c.tab,
                rows = visibleLocalRows(root, c.expanded),
                expanded = c.expanded,
                searchResults = searchByName(paths, c.query),
                bookmarks = bookmarks,
                recents = recents,
            )
        }
            .flowOn(Dispatchers.Default)
            // 화면이 구독하는 동안만 계산한다. 회전 등으로 잠깐 끊겨도 5초는 유지한다.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    fun onQuery(value: String) {
        query.value = value
    }

    fun onTab(value: DrawerTab) {
        tab.value = value
    }

    fun toggleFolder(path: String) = expanded.update { if (path in it) it - path else it + path }
}
