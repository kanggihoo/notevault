package com.ssafy.notevault.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.notevault.github.ExpiryNotice

/** 오류·안내 카드. 글자를 길게 눌러 복사할 수 있다 — 오류 원인을 그대로 옮겨 적기 위해. */
@Composable
fun MessageCard(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val colors = if (isError) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Card(colors = colors, modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 토큰 만료 경고. 경고할 것이 없으면 아무것도 그리지 않는다. */
@Composable
fun ExpiryBanner(notice: ExpiryNotice?, modifier: Modifier = Modifier) {
    when (notice) {
        null -> Unit
        is ExpiryNotice.Soon -> MessageCard(
            "토큰 만료 D-${notice.daysLeft} (${notice.date}). PC 에서 새 토큰을 발급해 두세요.",
            isError = false,
            modifier = modifier,
        )
        is ExpiryNotice.Expired -> MessageCard(
            "토큰이 만료되었습니다 (${notice.date}). PC 에서 새 토큰을 발급해 설정에 붙여넣으세요.",
            isError = true,
            modifier = modifier,
        )
    }
}

/** 1536 → "1.5 KB". 구독 화면의 폴더 용량 표시용. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
