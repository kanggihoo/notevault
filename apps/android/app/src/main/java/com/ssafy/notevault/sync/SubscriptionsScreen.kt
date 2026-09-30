package com.ssafy.notevault.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssafy.notevault.AppContainer
import com.ssafy.notevault.ui.MessageCard
import com.ssafy.notevault.ui.formatBytes

@Composable
fun SubscriptionsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = viewModel {
        SubscriptionsViewModel(
            settings = container.settingsStore,
            dao = container.database.dao(),
            clientFactory = container::githubClient,
            startSync = container.syncController::sync,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    SubscriptionsContent(
        state = state,
        onToggle = vm::toggle,
        onToggleExpand = vm::toggleExpand,
        onRetry = vm::load,
        onSave = { vm.save(onDone = onBack) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsContent(
    state: SubscriptionsUiState,
    onToggle: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("구독 관리") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
        bottomBar = {
            if (state is SubscriptionsUiState.Ready) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("저장하고 동기화 (${state.selected.size}개 선택)") }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                SubscriptionsUiState.Loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("원격 폴더 목록을 받는 중…", modifier = Modifier.padding(top = 12.dp))
                }
                is SubscriptionsUiState.Error -> Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MessageCard(state.message, isError = true)
                    OutlinedButton(onClick = onRetry) { Text("다시 시도") }
                }
                is SubscriptionsUiState.Ready -> LazyColumn {
                    items(visibleRows(state.root, state.expanded), key = { it.node.path }) { row ->
                        FolderRow(row, state, onToggle, onToggleExpand)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 화면에 펼쳐 보일 행. 루트(볼트 전체)는 항상 보이고, 펼친 폴더의 자식만 이어 붙인다. */
data class FolderRowItem(val node: FolderNode, val depth: Int)

fun visibleRows(root: FolderNode, expanded: Set<String>): List<FolderRowItem> = buildList {
    fun visit(node: FolderNode, depth: Int) {
        add(FolderRowItem(node, depth))
        if (node.path.isEmpty() || node.path in expanded) node.children.forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
}

@Composable
private fun FolderRow(
    row: FolderRowItem,
    state: SubscriptionsUiState.Ready,
    onToggle: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
) {
    val node = row.node
    val checked = isCovered(node.path, state.selected)
    val lockedByAncestor = checked && node.path !in state.selected // 조상이 골라서 켜진 것
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !lockedByAncestor) { onToggle(node.path) }
            .padding(start = (row.depth * 16).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.children.isNotEmpty() && node.path.isNotEmpty()) {
            IconButton(onClick = { onToggleExpand(node.path) }) {
                Icon(
                    if (node.path in state.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "${node.name} 펼치기",
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = !lockedByAncestor, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(if (node.path.isEmpty()) "볼트 전체" else node.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "노트 ${node.noteCount} · 파일 ${node.fileCount} · ${formatBytes(node.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
