package com.ssafy.notevault.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.notevault.db.VaultDao
import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.github.describe
import com.ssafy.notevault.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SubscriptionsUiState {
    data object Loading : SubscriptionsUiState
    data class Error(val message: String) : SubscriptionsUiState
    data class Ready(
        val root: FolderNode,
        val selected: Set<String>,
        val expanded: Set<String> = emptySet(),
    ) : SubscriptionsUiState
}

/** 원격 트리를 받아 폴더를 고르게 하고, 저장하면 동기화를 시작한다. */
class SubscriptionsViewModel(
    private val settings: SettingsStore,
    private val dao: VaultDao,
    private val clientFactory: (token: String) -> GithubClient,
    private val startSync: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow<SubscriptionsUiState>(SubscriptionsUiState.Loading)
    val state: StateFlow<SubscriptionsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = SubscriptionsUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val token = settings.token()
                val repo = settings.settings.first().repo
                if (token == null || repo == null) {
                    SubscriptionsUiState.Error("설정에서 GitHub 토큰과 저장소를 먼저 입력하세요.")
                } else {
                    val client = clientFactory(token)
                    val tree = client.getTree(repo, client.getHeadSha(repo))
                    SubscriptionsUiState.Ready(buildFolderTree(tree.entries), dao.getSubscriptions().toSet())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SubscriptionsUiState.Error(describe(e))
            }
        }
    }

    /** 조상이 이미 선택된 폴더는 바꿀 수 없다 — 조상을 먼저 풀어야 한다. */
    fun toggle(path: String) = updateReady { ready ->
        val next = when {
            path in ready.selected -> ready.selected - path
            isCovered(path, ready.selected) -> return@updateReady ready
            else -> normalizeSubscriptions(ready.selected + path)
        }
        ready.copy(selected = next)
    }

    fun toggleExpand(path: String) = updateReady { ready ->
        ready.copy(expanded = if (path in ready.expanded) ready.expanded - path else ready.expanded + path)
    }

    fun save(onDone: () -> Unit) {
        val ready = _state.value as? SubscriptionsUiState.Ready ?: return
        viewModelScope.launch {
            dao.setSubscriptions(ready.selected.toList())
            startSync()
            onDone()
        }
    }

    private fun updateReady(transform: (SubscriptionsUiState.Ready) -> SubscriptionsUiState.Ready) =
        _state.update { if (it is SubscriptionsUiState.Ready) transform(it) else it }
}
