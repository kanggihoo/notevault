package com.ssafy.notevault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.notevault.github.ExpiryNotice
import com.ssafy.notevault.github.GithubClient
import kotlinx.coroutines.CancellationException
import com.ssafy.notevault.github.RepoRef
import com.ssafy.notevault.github.describe
import com.ssafy.notevault.github.expiryNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Ok(val detail: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** 설정 화면이 그리는 모든 것. 화면은 이 값만 보고 그린다. */
data class SettingsUiState(
    val tokenInput: String = "",
    val ownerInput: String = "",
    val repoInput: String = "",
    val branchInput: String = "main",
    val savedTokenHint: String? = null,
    val expiry: ExpiryNotice? = null,
    val connection: ConnectionState = ConnectionState.Idle,
)

/**
 * 설정 화면의 상태와 동작. 화면 회전 등으로 Activity 가 다시 만들어져도 ViewModel 은 살아남는다.
 *
 * [clientFactory] 로 GithubClient 생성을 주입받는 이유: 테스트에서 MockWebServer 주소를 쓰기 위해서다.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val clientFactory: (token: String) -> GithubClient,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // 저장된 값으로 입력칸을 채운다.
        viewModelScope.launch {
            val saved = store.settings.first()
            _state.update {
                it.copy(
                    ownerInput = saved.repo?.owner ?: it.ownerInput,
                    repoInput = saved.repo?.repo ?: it.repoInput,
                    branchInput = saved.repo?.branch ?: it.branchInput,
                    savedTokenHint = saved.tokenHint,
                    expiry = expiryNotice(saved.tokenExpiresAt, clock()),
                )
            }
        }
    }

    fun onTokenInput(value: String) = _state.update { it.copy(tokenInput = value) }
    fun onOwnerInput(value: String) = _state.update { it.copy(ownerInput = value) }
    fun onRepoInput(value: String) = _state.update { it.copy(repoInput = value) }
    fun onBranchInput(value: String) = _state.update { it.copy(branchInput = value) }

    fun onClearToken() {
        viewModelScope.launch {
            store.clearToken()
            _state.update { it.copy(savedTokenHint = null, expiry = null, connection = ConnectionState.Idle) }
        }
    }

    /** 입력값을 저장하고, HEAD 커밋 조회로 저장소·브랜치·토큰을 한 번에 확인한다. */
    fun onSaveAndTest() {
        val input = _state.value
        viewModelScope.launch {
            _state.update { it.copy(connection = ConnectionState.Testing) }
            try {
                saveAndTest(input)
            } catch (e: CancellationException) {
                throw e // 화면을 떠나 취소된 것 — 오류가 아니다
            } catch (e: Exception) {
                // GitHub 오류든 저장 실패든, "확인 중" 에 영원히 머물지 않게 한다.
                fail(describe(e, store.settings.first().tokenExpiresAt, clock()))
            }
        }
    }

    private suspend fun saveAndTest(input: SettingsUiState) {
        val typedToken = input.tokenInput.trim()
        if (typedToken.isNotEmpty()) {
            store.saveToken(typedToken)
            _state.update { it.copy(tokenInput = "", savedTokenHint = typedToken.takeLast(4), expiry = null) }
        }
        val token = store.token() ?: return fail("GitHub 토큰을 먼저 붙여넣으세요.")

        val owner = input.ownerInput.trim()
        val repoName = input.repoInput.trim()
        if (owner.isEmpty() || repoName.isEmpty()) return fail("저장소 owner 와 repo 를 입력하세요.")
        val repo = RepoRef(owner, repoName, input.branchInput.trim().ifEmpty { "main" })
        store.saveRepo(repo)

        val client = clientFactory(token)
        val head = client.getHeadSha(repo) // 실패하면 GithubException → onSaveAndTest 가 문구로 바꾼다
        store.saveTokenExpiresAt(client.tokenExpiresAt)
        _state.update {
            it.copy(
                expiry = expiryNotice(client.tokenExpiresAt, clock()),
                connection = ConnectionState.Ok(okDetail(head, client)),
            )
        }
    }

    private fun fail(message: String) = _state.update { it.copy(connection = ConnectionState.Failed(message)) }

    private fun okDetail(head: String, client: GithubClient): String = buildString {
        append("연결됨 · 최신 커밋 ${head.take(7)}")
        client.rateLimit?.let { append(" · 남은 요청 ${it.remaining}/${it.limit}") }
        append(if (client.tokenExpiresAt == null) " · 만료 없는 토큰" else " · 만료 ${client.tokenExpiresAt}")
    }
}
