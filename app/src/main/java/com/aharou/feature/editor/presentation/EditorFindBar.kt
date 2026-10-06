package com.aharou.feature.editor.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Search
import compose.icons.feathericons.X
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

internal class EditorFindState {
    var visible by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var replacement by mutableStateOf("")
        private set
    var matchIndex by mutableStateOf(0)
        private set
    var matchCount by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    private var editor: CodeEditor? = null
    private var revision = 0L
    private var selectFirstMatch = false
    private var pendingFocus = false

    /** 供查找框在搜索跳转后把焦点要回来：sora 移光标时会抢焦点，否则后续按键会落进正文。 */
    val focusRequester = FocusRequester()
    private val subscriptions = mutableListOf<SubscriptionReceipt<*>>()

    fun attach(source: CodeEditor) {
        editor?.let(::release)
        editor = source
        source.searcher.setCyclicJumping(true)
        subscriptions += source.subscribeAlways(PublishSearchResultEvent::class.java) { event ->
            val expectedRevision = revision
            source.postInLifecycle {
                if (expectedRevision == revision && isActive(source, event.getSearcher())) {
                    searching = false
                    refresh(event.getSearcher())
                    if (selectFirstMatch && matchCount > 0 && matchIndex == 0) {
                        event.getSearcher().gotoNext()
                        refresh(event.getSearcher())
                    }
                    selectFirstMatch = false
                    if (pendingFocus) {
                        pendingFocus = false
                        source.post { runCatching { focusRequester.requestFocus() } }
                    }
                }
            }
        }
        subscriptions += source.subscribeAlways(SelectionChangeEvent::class.java) {
            if (!searching && isActive(source, source.searcher)) refresh(source.searcher)
        }
        subscriptions += source.subscribeAlways(ContentChangeEvent::class.java) {
            if (isActive(source, source.searcher)) {
                revision++
                searching = true
                selectFirstMatch = false
                matchIndex = 0
                matchCount = 0
            }
        }
    }

    fun open() {
        if (editor != null) visible = true
    }

    fun updateQuery(value: String) {
        query = value
        revision++
        matchIndex = 0
        matchCount = 0
        searching = value.isNotEmpty()
        selectFirstMatch = value.isNotEmpty()
        pendingFocus = value.isNotEmpty()
        val source = editor ?: return
        if (value.isEmpty()) {
            source.searcher.stopSearch()
            source.invalidate()
        } else {
            source.searcher.search(value, EditorSearcher.SearchOptions(true, false))
        }
    }

    fun navigate(previous: Boolean) {
        val source = editor ?: return
        val searcher = source.searcher
        if (searching || !isActive(source, searcher) || searcher.matchedPositionCount == 0) return
        pendingFocus = false
        if (previous) searcher.gotoPrevious() else searcher.gotoNext()
        refresh(searcher)
        source.post { runCatching { focusRequester.requestFocus() } }
    }

    fun updateReplacement(value: String) {
        replacement = value
    }

    /**
     * 替换当前匹配。结果还没出来、编辑器不可写、或一个匹配都没有时不动——避免在半成品状态上改文本。
     * 命中位置没被选中时先 `gotoNext` 选中它（库自带的 replaceCurrentMatch 也是这个兜底行为，
     * 但这里先跳一次，让「替换」始终真的替换到一个匹配）。
     */
    fun replaceCurrent() {
        val source = editor ?: return
        val searcher = source.searcher
        if (searching || !source.isEditable || !isActive(source, searcher)) return
        if (searcher.matchedPositionCount == 0) return
        if (!searcher.isMatchedPositionSelected()) searcher.gotoNext()
        searcher.replaceCurrentMatch(replacement)
        refresh(searcher)
        source.post { runCatching { focusRequester.requestFocus() } }
    }

    /**
     * 全部替换。库内部弹进度框并在后台线程批量改写，改完会重新搜索，计数经事件回调自然更新。
     */
    fun replaceAll() {
        val source = editor ?: return
        val searcher = source.searcher
        if (searching || !source.isEditable || !isActive(source, searcher)) return
        if (searcher.matchedPositionCount == 0) return
        searcher.replaceAll(replacement)
        source.post { runCatching { focusRequester.requestFocus() } }
    }

    fun close() {
        visible = false
        query = ""
        replacement = ""
        revision++
        searching = false
        selectFirstMatch = false
        pendingFocus = false
        matchIndex = 0
        matchCount = 0
        editor?.let {
            it.searcher.stopSearch()
            it.invalidate()
        }
    }

    fun release(source: CodeEditor) {
        if (source !== editor) return
        close()
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
        editor = null
    }

    private fun isActive(source: CodeEditor, searcher: EditorSearcher): Boolean =
        source === editor && !source.isReleased && visible && query.isNotEmpty() &&
            searcher === source.searcher && searcher.hasQuery()

    private fun refresh(searcher: EditorSearcher) {
        matchCount = searcher.matchedPositionCount
        matchIndex = (searcher.currentMatchedPositionIndex + 1).coerceIn(0, matchCount)
    }
}

@Composable
internal fun EditorFindBar(state: EditorFindState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                value = state.query,
                onValueChange = state::updateQuery,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(state.focusRequester),
                placeholder = stringResource(R.string.editor_find_hint),
                leadingIcon = {
                    Icon(FeatherIcons.Search, contentDescription = stringResource(R.string.editor_find))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.navigate(previous = false) }),
                singleLine = true
            )
            IconButton(onClick = state::close) {
                Icon(FeatherIcons.X, contentDescription = stringResource(R.string.editor_find_close))
            }
        }
        if (state.query.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTextField(
                    value = state.replacement,
                    onValueChange = state::updateReplacement,
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.editor_replace_hint),
                    singleLine = true
                )
                TextButton(
                    onClick = state::replaceCurrent,
                    enabled = !state.searching && state.matchCount > 0,
                    contentPadding = PaddingValues(horizontal = Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.editor_replace),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
                TextButton(
                    onClick = state::replaceAll,
                    enabled = !state.searching && state.matchCount > 0,
                    contentPadding = PaddingValues(horizontal = Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.editor_replace_all),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }
        }
        if (state.query.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.searching) {
                    Box(modifier = Modifier.weight(1f)) {
                        CircularProgressIndicator(modifier = Modifier.size(Spacing.lg), strokeWidth = 2.dp)
                    }
                } else {
                    Text(
                        text = if (state.matchCount == 0) {
                            stringResource(R.string.editor_find_no_results)
                        } else {
                            stringResource(R.string.editor_find_match_count, state.matchIndex, state.matchCount)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { state.navigate(previous = true) },
                    enabled = !state.searching && state.matchCount > 0
                ) {
                    Icon(FeatherIcons.ChevronUp, contentDescription = stringResource(R.string.editor_find_previous))
                }
                IconButton(
                    onClick = { state.navigate(previous = false) },
                    enabled = !state.searching && state.matchCount > 0
                ) {
                    Icon(FeatherIcons.ChevronDown, contentDescription = stringResource(R.string.editor_find_next))
                }
            }
        }
    }
}
