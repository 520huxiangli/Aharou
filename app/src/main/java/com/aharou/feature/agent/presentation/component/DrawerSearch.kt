package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.FileSearchState
import com.aharou.feature.settings.presentation.component.ModelSearchField
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.workspace.domain.WorkspaceSearchHit
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.X

private const val HITS_PER_FILE_BATCH_SIZE = 10

/**
 * 侧栏搜索框：「会话」「文件」两个 Tab 共用，复用设置页的胶囊搜索框，有内容时带清除按钮。
 */
@Composable
internal fun DrawerSearchField(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    ModelSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth(),
        trailing = if (query.isEmpty()) null else {
            {
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_clear),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** 把片段中所有命中词标为高亮样式。 */
internal fun highlightSnippet(snippet: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        if (query.isBlank()) return@buildAnnotatedString
        var start = 0
        while (true) {
            val index = snippet.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            addStyle(highlight, index, index + query.length)
            start = index + query.length
        }
    }

/**
 * 工作区搜索结果：按文件分组，组头是文件名 + 所在目录，可展开看命中行。
 * 只被文件名命中的文件点组头直接打开；有内容命中的文件展开后点某一行跳到编辑器对应行。
 */
@Composable
internal fun FileSearchResults(
    state: FileSearchState,
    onOpenHit: (WorkspaceSearchHit) -> Unit
) {
    when {
        state.hits.isEmpty() && state.loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.hits.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.drawer_file_search_no_result),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }

        else -> {
            val groups = remember(state.hits) { state.hits.groupBy { it.path } }
            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            val visibleHits = remember(state.query, state.hits) { mutableStateMapOf<String, Int>() }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "file-search-summary") {
                    Text(
                        text = if (state.truncated) {
                            stringResource(R.string.drawer_file_search_truncated, state.hits.size)
                        } else {
                            stringResource(R.string.drawer_file_search_summary, groups.size, state.hits.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    )
                }
                groups.forEach { (path, fileHits) ->
                    val nameHit = fileHits.firstOrNull { it.isFileName }
                    val contentHits = fileHits.filterNot { it.isFileName }
                    val isCollapsed = collapsed[path] == true
                    // 只有文件名命中时没有可展开的行，点组头就是打开文件本身。
                    val toggle = {
                        if (contentHits.isEmpty()) {
                            nameHit?.let(onOpenHit)
                        } else {
                            collapsed[path] = !isCollapsed
                        }
                        Unit
                    }
                    item(key = "file:$path") {
                        FileSearchResultHeader(
                            name = fileHits.first().name,
                            directory = fileHits.first().directory,
                            hitCount = contentHits.size,
                            collapsible = contentHits.isNotEmpty(),
                            collapsed = isCollapsed,
                            onClick = toggle
                        )
                    }
                    if (!isCollapsed) {
                        val shownCount = visibleHits[path] ?: HITS_PER_FILE_BATCH_SIZE
                        items(
                            items = contentHits.take(shownCount),
                            key = { hit -> "hit:$path:${hit.line}:${hit.column}" }
                        ) { hit ->
                            FileSearchHitRow(
                                hit = hit,
                                query = state.query,
                                onClick = { onOpenHit(hit) }
                            )
                        }
                        if (shownCount < contentHits.size) {
                            item(key = "more:$path") {
                                TextButton(
                                    onClick = {
                                        visibleHits[path] = shownCount + HITS_PER_FILE_BATCH_SIZE
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.drawer_file_search_show_more,
                                            minOf(HITS_PER_FILE_BATCH_SIZE, contentHits.size - shownCount)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    item(key = "divider:$path") { SettingsDivider() }
                }
            }
        }
    }
}

@Composable
private fun FileSearchResultHeader(
    name: String,
    directory: String,
    hitCount: Int,
    collapsible: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (directory.isNotEmpty()) {
                Text(
                    text = directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (hitCount > 0) {
            Text(
                text = hitCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (collapsible) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = if (collapsed) FeatherIcons.ChevronRight else FeatherIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileSearchHitRow(
    hit: WorkspaceSearchHit,
    query: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = hit.line.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(36.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = highlightSnippet(
                snippet = hit.text.trim(),
                query = query,
                highlight = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 搜索结果点击到打开文件之间的行号中转。
 *
 * 侧栏打开文件走的是 `onOpenFile(path)` 这条既有链路（文件树点击共用），它不带行号；
 * 搜索结果要定位到命中行，于是在调用前把行号放这里，打开动作取出即清——
 * 既不会与文件树点击混淆，也不会把上一次的行号残留给下一次打开。
 */
internal object DrawerSearchOpenRequest {
    private var pendingPath: String? = null
    private var pendingLine: Int = 0

    fun request(path: String, line: Int) {
        pendingPath = path
        pendingLine = line
    }

    /** [path] 对应的待跳行号（0 表示不跳）；只对刚请求的那条路径有效，取出即清。 */
    fun lineFor(path: String): Int {
        val line = if (pendingPath == path) pendingLine else 0
        pendingPath = null
        pendingLine = 0
        return line
    }
}
