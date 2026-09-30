package com.ssafy.notevault.sync

import com.ssafy.notevault.db.VaultDao
import com.ssafy.notevault.github.GithubClient
import com.ssafy.notevault.github.describe
import com.ssafy.notevault.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/** 화면이 보여줄 동기화 상태. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val progress: SyncProgress) : SyncStatus
    data class Finished(val outcome: SyncOutcome) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * 앱 전체에서 하나만 있는 동기화 실행기 (AppContainer 가 보관).
 *
 * 화면(ViewModel)이 아니라 앱 수명의 [scope] 에서 돌리는 이유: 동기화 도중 화면을 옮겨도
 * 끊기지 않아야 하고, 어느 화면에서든 같은 진행률을 봐야 하기 때문이다.
 */
class SyncController(
    private val settings: SettingsStore,
    private val dao: VaultDao,
    private val files: VaultFiles,
    private val clientFactory: (token: String) -> GithubClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    /** 이미 도는 중이면 무시한다 — 버튼을 연타해도 동기화는 하나만. */
    @Synchronized
    fun sync() {
        if (job?.isActive == true) return
        cancelRequested = false
        _status.value = SyncStatus.Running(SyncProgress.Checking)
        job = scope.launch { run() }
    }

    /** 받던 파일까지는 마치고 멈춘다. 받은 파일은 DB 에 남아 다음 동기화가 이어받는다. */
    fun cancel() {
        cancelRequested = true
    }

    /** 저장소를 바꿀 때: 이전 저장소의 파일·DB 를 모두 지운다. */
    suspend fun resetVault() {
        job?.cancel()
        job?.join()
        dao.clearAll()
        files.deleteAll()
        _status.value = SyncStatus.Idle
    }

    private suspend fun run() {
        val current = settings.settings.first()
        val token = settings.token()
        val repo = current.repo
        if (token == null || repo == null) {
            _status.value = SyncStatus.Failed("설정에서 GitHub 토큰과 저장소를 먼저 입력하세요.")
            return
        }
        val client = clientFactory(token)
        try {
            val outcome = runSync(
                client, repo, dao, files,
                isCancelled = { cancelRequested },
                onProgress = { _status.value = SyncStatus.Running(it) },
            )
            _status.value = SyncStatus.Finished(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed(describe(e, current.tokenExpiresAt, Instant.now()))
        } finally {
            // 응답이 알려준 만료 시각을 기록해 둔다 — 홈 화면의 D-7 경고에 쓴다.
            client.tokenExpiresAt?.let { settings.saveTokenExpiresAt(it) }
        }
    }
}
