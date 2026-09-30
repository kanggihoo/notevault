package com.ssafy.notevault.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 서랍 안쪽. 검색어가 있으면 검색 결과를, 없으면 탭(파일·즐겨찾기·최근)을 보여준다. */
@Composable
fun VaultDrawerContent(
    state: BrowserUiState,
    onQuery: (String) -> Unit,
    onTab: (DrawerTab) -> Unit,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = { Text("파일 이름 검색") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )

        if (state.query.isNotBlank()) {
            PathList(state.searchResults, empty = "일치하는 파일이 없어요", onOpenFile = onOpenFile)
            return@Column
        }

        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            DrawerTab.entries.forEach { tab ->
                Tab(selected = state.tab == tab, onClick = { onTab(tab) }, text = { Text(tab.label) })
            }
        }
        when (state.tab) {
            DrawerTab.Files -> if (state.rows.isEmpty()) {
                EmptyText("받은 파일이 없어요. 구독 관리에서 폴더를 고르고 동기화하세요.")
            } else {
                LazyColumn {
                    items(state.rows, key = { it.node.path }) { row ->
                        TreeRow(row, expanded = row.node.path in state.expanded, onToggleFolder, onOpenFile)
                    }
                }
            }
            DrawerTab.Bookmarks -> PathList(state.bookmarks, empty = "노트 화면의 ☆ 로 즐겨찾기를 추가하세요", onOpenFile = onOpenFile)
            DrawerTab.Recents -> PathList(state.recents, empty = "최근에 연 파일이 없어요", onOpenFile = onOpenFile)
        }
    }
}

@Composable
private fun TreeRow(row: LocalRow, expanded: Boolean, onToggleFolder: (String) -> Unit, onOpenFile: (String) -> Unit) {
    val node = row.node
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (node.isFolder) onToggleFolder(node.path) else onOpenFile(node.path) }
            .padding(start = (12 + row.depth * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isFolder) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Text(
            if (node.isFolder) node.name else displayName(node.path),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 검색·즐겨찾기·최근: 이름과 그 아래 폴더 경로. */
@Composable
private fun PathList(paths: List<String>, empty: String, onOpenFile: (String) -> Unit) {
    if (paths.isEmpty()) return EmptyText(empty)
    LazyColumn {
        items(paths, key = { it }) { path ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(path) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(displayName(path), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val folder = path.substringBeforeLast('/', "")
                if (folder.isNotEmpty()) {
                    Text(
                        folder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
