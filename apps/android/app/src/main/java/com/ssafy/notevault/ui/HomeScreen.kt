package com.ssafy.notevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.github.describe
import com.ssafy.notevault.github.expiryNotice
import com.ssafy.notevault.sync.SyncOutcome
import com.ssafy.notevault.sync.SyncProgress
import com.ssafy.notevault.sync.SyncStatus
import com.ssafy.notevault.vault.VaultBrowserViewModel
import com.ssafy.notevault.vault.VaultDrawerContent
import kotlinx.coroutines.launch
import java.time.Instant

/** 홈: 왼쪽 서랍(파일 트리·검색·즐겨찾기·최근) + 저장소 정보·동기화·구독 관리. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val browser = viewModel { VaultBrowserViewModel(container.database.dao()) }
    val browserState by browser.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                VaultDrawerContent(
                    state = browserState,
                    onQuery = browser::onQuery,
                    onTab = browser::onTab,
                    onToggleFolder = browser::toggleFolder,
                    onOpenFile = { path ->
                        scope.launch { drawerState.close() }
                        onOpenFile(path)
                    },
                )
            }
        },
    ) {
        HomeBody(
            container,
            onOpenSettings,
            onOpenSubscriptions,
            onOpenDrawer = { scope.launch { drawerState.open() } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBody(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by container.syncController.status.collectAsStateWithLifecycle()
    val fileCount by container.database.dao().observeFileCount().collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NoteVault") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "메뉴") }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "설정") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = settings
            val repo = current?.repo
            if (current?.tokenHint == null || repo == null) {
                Text("GitHub 토큰과 저장소를 먼저 설정하세요.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenSettings) { Text("설정 열기") }
                return@Column
            }

            Text("${repo.owner}/${repo.repo} @ ${repo.branch}", style = MaterialTheme.typography.titleMedium)
            ExpiryBanner(expiryNotice(current.tokenExpiresAt, Instant.now()))
            Text("받은 파일 ${fileCount}개", style = MaterialTheme.typography.bodyMedium)

            val running = status is SyncStatus.Running
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = container.syncController::sync, enabled = !running) { Text("동기화") }
                OutlinedButton(onClick = onOpenSubscriptions, enabled = !running) { Text("구독 관리") }
            }
            SyncStatusView(status, onCancel = container.syncController::cancel)
            if (fileCount > 0) {
                TextButton(onClick = onOpenDrawer) { Text("☰ 파일 목록 열기") }
            }
        }
    }
}

@Composable
private fun SyncStatusView(status: SyncStatus, onCancel: () -> Unit) {
    when (status) {
        SyncStatus.Idle -> Unit
        is SyncStatus.Running -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val progress = status.progress
            if (progress is SyncProgress.Downloading) {
                LinearProgressIndicator(progress = { progress.done / progress.total.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row {
                Text(progressLabel(progress), modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = onCancel) { Text("취소") }
            }
        }
        is SyncStatus.Finished -> MessageCard(outcomeLabel(status.outcome), isError = hasFailures(status.outcome))
        is SyncStatus.Failed -> MessageCard(status.message, isError = true)
    }
}

fun progressLabel(progress: SyncProgress): String = when (progress) {
    SyncProgress.Checking -> "변경 확인 중…"
    SyncProgress.Planning -> "받을 목록 계산 중…"
    is SyncProgress.Downloading -> "받는 중 ${progress.done} / ${progress.total}\n${progress.currentPath}"
    SyncProgress.Deleting -> "정리 중…"
}

fun outcomeLabel(outcome: SyncOutcome): String = when (outcome) {
    SyncOutcome.UpToDate -> "이미 최신입니다"
    is SyncOutcome.Synced -> when {
        outcome.cancelled -> "취소됨 · 받은 파일 ${outcome.downloaded}개는 남아 있어요. 다시 동기화하면 이어서 받아요."
        outcome.failed.isNotEmpty() ->
            "${outcome.failed.size}개 실패 — 다시 동기화하면 실패한 것만 다시 받아요.\n" +
                "예: ${outcome.failed.first().path}\n${describe(outcome.failed.first().error)}"
        else -> "새로 ${outcome.downloaded} · 삭제 ${outcome.deleted}"
    }
}

private fun hasFailures(outcome: SyncOutcome) = outcome is SyncOutcome.Synced && outcome.failed.isNotEmpty()
