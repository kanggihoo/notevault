package com.ssafy.notevault.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.ui.ExpiryBanner
import com.ssafy.notevault.ui.MessageCard
import com.ssafy.notevault.ui.NoteVaultTheme

/** ViewModel 을 만들고 상태를 구독하는 바깥 껍데기. 실제 그리기는 [SettingsContent] 가 한다. */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(container.settingsStore, container::githubClient) }
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        onTokenInput = vm::onTokenInput,
        onOwnerInput = vm::onOwnerInput,
        onRepoInput = vm::onRepoInput,
        onBranchInput = vm::onBranchInput,
        onSaveAndTest = vm::onSaveAndTest,
        onClearToken = vm::onClearToken,
        onBack = onBack,
    )
}

/**
 * 상태를 받아 그리기만 한다 (state hoisting). ViewModel 을 모르므로 아래 @Preview 처럼
 * 가짜 상태를 넣어 Android Studio 에서 바로 모양을 볼 수 있다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onTokenInput: (String) -> Unit,
    onOwnerInput: (String) -> Unit,
    onRepoInput: (String) -> Unit,
    onBranchInput: (String) -> Unit,
    onSaveAndTest: () -> Unit,
    onClearToken: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("GitHub 토큰", style = MaterialTheme.typography.titleMedium)
            state.savedTokenHint?.let { hint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("저장됨 ···$hint", modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearToken) { Text("삭제") }
                }
            }
            ExpiryBanner(state.expiry)
            OutlinedTextField(
                value = state.tokenInput,
                onValueChange = onTokenInput,
                label = { Text(if (state.savedTokenHint == null) "fine-grained PAT 붙여넣기" else "새 토큰으로 교체") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "권한: 저장소 하나 · Contents 읽기 전용이면 충분해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("저장소", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.ownerInput, onOwnerInput, label = { Text("owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.repoInput, onRepoInput, label = { Text("repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.branchInput, onBranchInput, label = { Text("branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = onSaveAndTest,
                enabled = state.connection != ConnectionState.Testing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("저장하고 연결 테스트") }

            ConnectionResult(state.connection)
        }
    }
}

@Composable
private fun ConnectionResult(connection: ConnectionState) {
    when (connection) {
        ConnectionState.Idle -> Unit
        ConnectionState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  확인 중…")
        }
        is ConnectionState.Ok -> MessageCard(connection.detail, isError = false)
        is ConnectionState.Failed -> MessageCard(connection.message, isError = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsFailedPreview() {
    NoteVaultTheme {
        SettingsContent(
            state = SettingsUiState(
                ownerInput = "kanggihoo",
                repoInput = "obsidian",
                savedTokenHint = "a1b2",
                connection = ConnectionState.Failed("접근이 거부되었습니다 (403).\nGitHub 메시지: Resource not accessible"),
            ),
            onTokenInput = {}, onOwnerInput = {}, onRepoInput = {}, onBranchInput = {},
            onSaveAndTest = {}, onClearToken = {}, onBack = {},
        )
    }
}
