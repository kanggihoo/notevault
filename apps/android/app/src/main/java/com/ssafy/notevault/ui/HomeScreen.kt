package com.ssafy.notevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.github.expiryNotice
import java.time.Instant

/** 3a 단계 자리표시자. 3b·3c 에서 파일 트리·동기화 버튼이 들어온다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NoteVault") },
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
            } else {
                Text("${repo.owner}/${repo.repo} @ ${repo.branch}", style = MaterialTheme.typography.titleMedium)
                ExpiryBanner(expiryNotice(current.tokenExpiresAt, Instant.now()))
                Text("다음 단계에서 구독 관리와 동기화가 들어옵니다.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
