package com.aharou.feature.browser.presentation

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField

/**
 * 页内查找条：直接驱动 WebView 原生 find API（findAllAsync / findNext / clearMatches）。
 * 高亮由 WebView 自己绘制，Compose 侧只负责输入与命中计数。
 */
@Composable
fun BrowserFindInPageBar(
    webView: WebView,
    onClose: () -> Unit,
) {
    var query by remember(webView) { mutableStateOf("") }
    var activeMatch by remember(webView) { mutableStateOf(0) }
    var totalMatches by remember(webView) { mutableStateOf(0) }

    DisposableEffect(webView) {
        webView.installFindListener { ordinal, count ->
            activeMatch = ordinal
            totalMatches = count
        }
        onDispose { webView.clearMatches() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AppTextField(
            value = query,
            onValueChange = { newQuery ->
                query = newQuery
                webView.runFind(newQuery)
            },
            placeholder = stringResource(R.string.browser_find_placeholder),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                query.isBlank() -> ""
                totalMatches == 0 -> stringResource(R.string.browser_find_no_matches)
                else -> stringResource(R.string.browser_find_matches, activeMatch, totalMatches)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { webView.findNextMatch(false) }) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.browser_find_prev),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { webView.findNextMatch(true) }) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.browser_find_next),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.browser_find_close),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun WebView.installFindListener(onResult: (activeMatch: Int, totalMatches: Int) -> Unit) {
    setFindListener { ordinal, count, _ -> onResult(ordinal, count) }
}

@Suppress("DEPRECATION")
private fun WebView.runFind(query: String) {
    if (query.isBlank()) clearMatches() else findAllAsync(query)
}

@Suppress("DEPRECATION")
private fun WebView.findNextMatch(forward: Boolean) {
    findNext(forward)
}
