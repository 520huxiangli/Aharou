package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.X

/**
 * 用户粘贴的一大段文本，被折出输入框、在框内只留一个 `[Pasted#N]` 标记。
 *
 * 粘贴大段文字原本会把整块倒进输入框：光标被埋掉、框内要滚好几屏，用户看不见自己正在写什么。
 * 现在文本存在这里，输入框只显示一个短标记；发送时由 [expandPastePlaceholders] 还原回去，
 * 模型和落库历史拿到的都是完整原文，传输格式没有任何变化。
 *
 * 只放内存、按 ViewModel（即按会话）隔离，刻意不持久化：输入框自身的草稿文字也不持久化，
 * 两者共用同一生命周期、重启一起清空。只持久化其中一个才会产生坏状态——
 * 草稿里全是 `[Pasted#N]` 标记，却没有东西能展开它们。
 */
data class PastedText(
    val id: Int,
    val text: String,
) {
    /** 写进输入框的标记。 */
    val placeholder: String get() = placeholderFor(id)

    companion object {
        fun placeholderFor(id: Int): String = "[Pasted#$id]"
    }
}

/**
 * 匹配标记字面量。
 *
 * 只认 `\d+`：没有空白、没有正负号、不做前导零特判。损坏的标记（`[Pasted#3`、`[Pasted#]`、
 * `[Pasted# 3]`）直接不匹配、原样留在文本里——这是「用户手改过标记」应有的行为：不崩、不猜。
 *
 * 首字母大小写都收：我们只会写出 `[Pasted#N]`，但用户手敲回来时更可能写小写，
 * 静默不展开会看起来像丢了内容。读得宽松、写得严格，代价只是一个字符类。
 */
private val PLACEHOLDER_REGEX = Regex("""\[[Pp]asted#(\d+)]""")

/**
 * 把每个 `[Pasted#N]` 替换回它缓冲的原文，**单遍**完成。
 *
 * 单遍是关键性质而非优化：若粘贴内容本身就长得像 `[Pasted#2]`（用户粘一份讨论这个功能的
 * 聊天记录时正是如此），递归或反复替换的实现会把内层字面量也展开，把无关缓冲文本注进消息。
 * 这里只扫原始字符串一遍，展开出来的内容永不重新扫描。
 *
 * 未知 id 原样保留：手打的 `[Pasted#99]` 就是普通文字，不能因此让发送失败。
 * 用户从缓冲里删掉条目却留着标记的情况也由它兜住。
 *
 * @return 展开后的文本，以及实际被消费的 id，供调用方精确移除这些条目、不误伤孤立标记。
 */
fun expandPastePlaceholders(
    text: String,
    buffer: List<PastedText>,
): Pair<String, Set<Int>> {
    if (text.isEmpty() || buffer.isEmpty()) return text to emptySet()
    val byId = buffer.associateBy { it.id }
    val consumed = mutableSetOf<Int>()
    val out = StringBuilder(text.length)
    var cursor = 0
    for (match in PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        val entry = id?.let { byId[it] } ?: continue
        out.append(text, cursor, match.range.first)
        out.append(entry.text)
        cursor = match.range.last + 1
        consumed += entry.id
    }
    if (consumed.isEmpty()) return text to emptySet()
    out.append(text, cursor, text.length)
    return out.toString() to consumed
}

/** 超过这个字符数就不再折成标记，改为落成真的 `.txt` 附件。 */
const val PASTE_AS_FILE_THRESHOLD = 15_000

/**
 * 文本里引用到的粘贴块 id。
 *
 * 用于把输入框与缓冲区对齐：用户直接删掉 `[Pasted#N]` 字面量时，对应的块也该跟着消失，
 * 否则 chip 会残留、点开还能看到一段已经不在输入框里的内容。
 */
fun pastedIdsIn(text: String): Set<Int> =
    PLACEHOLDER_REGEX.findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toSet()

/**
 * [inserted] 是否长到需要折成标记。
 *
 * 中英文分开判：英文散文每单位语义的字符数远多于中日韩，单一阈值要么把短英文整段折掉，
 * 要么放长中文过去。ASCII 字母占半数以上即视为「英文为主」。
 *
 *   英文为主 → 超过 1000 个空白分隔的词
 *   其余（中日韩/混排）→ 超过 1200 个字符
 */
fun isLongPastedText(inserted: String): Boolean {
    val asciiLetters = inserted.count { it in 'A'..'Z' || it in 'a'..'z' }
    val englishDominant = asciiLetters > inserted.length / 2
    return if (englishDominant) {
        inserted.split(' ', '\n', '\t', '\r').count { it.isNotEmpty() } > 1000
    } else {
        inserted.length > 1200
    }
}

/**
 * 若 [new] 相对 [old] 只多出一大段插入内容，就把这段换成 `[Pasted#N]` 标记。
 *
 * 不是大段插入时原样返回 [new]，普通输入、删除、输入法组词都不受影响。
 *
 * 定位插入片段的方式：从左取公共前缀、从右取公共后缀，剩下的中间部分就是变化的内容。
 * 这样粘贴发生在任意光标位置都能处理，而且不同于朴素的「新文本以旧文本结尾」判断，
 * 它还能处理覆盖选中内容粘贴（被替换的文字会同时从两端消失）。
 *
 * 用长度差而不是新文本总长来判定：必须由**插入的那段**触发，否则输入框里已有一大块内容后，
 * 之后每敲一个键都会重新触发。
 */
fun foldLongPasteIfNeeded(
    old: TextFieldValue,
    new: TextFieldValue,
    stash: (String) -> String,
): TextFieldValue {
    val oldText = old.text
    val newText = new.text
    val delta = newText.length - oldText.length
    // 先做廉价拦截：删除或小改动不可能是长粘贴，而绝大多数按键都走这个分支。
    if (delta <= 0) return new

    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

    var suffix = 0
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    while (
        suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++

    val insertedStart = prefix
    val insertedEnd = newText.length - suffix
    if (insertedEnd <= insertedStart) return new
    val inserted = newText.substring(insertedStart, insertedEnd)
    if (!isLongPastedText(inserted)) return new

    val marker = stash(inserted)
    val folded = newText.substring(0, insertedStart) + marker + newText.substring(insertedEnd)
    // 光标落在标记之后——那是用户下一个字该出现的地方。
    return TextFieldValue(text = folded, selection = TextRange(insertedStart + marker.length))
}

/**
 * 折叠后的粘贴块在输入框里的方块标记，与附件 chip 同一套视觉。
 *
 * 标签是 id 与字符数：一眼能把两块粘贴区分开。点一下看全文。
 */
@Composable
internal fun PastedTextChip(
    pasted: PastedText,
    onRemove: () -> Unit,
) {
    var showPreview by remember(pasted.id) { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(Radius.sm)
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(chipShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, chipShape)
                .clickable { showPreview = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                FeatherIcons.FileText,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "#${pasted.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "${pasted.text.length}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // 移除角标半挂在右上角外侧，与附件 chip 一致。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.chat_pasted_remove),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }

    if (showPreview) {
        PastedTextPreviewDialog(pasted = pasted, onDismiss = { showPreview = false })
    }
}

/**
 * 只读的全文预览。
 *
 * 刻意只读：缓冲里的文本就是原样要发出去的内容，做成可编辑会引出一串这个功能回答不了的问题
 * ——改动后要不要重新过一遍长度阈值、内容缩到阈值以下时标记怎么办、同一条目被引用两次时改动如何生效。
 * 用户真实的需求只是「我刚才粘了啥」。
 *
 * 体量按定义就是数千字符，所以正文必须能滚，用普通 Dialog 而不是统一弹窗组件。
 */
@Composable
private fun PastedTextPreviewDialog(
    pasted: PastedText,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = "${pasted.placeholder}  ·  ${pasted.text.length}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pasted.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        // 限高，避免超大粘贴把关闭按钮顶出屏幕；正文在限高内滚动。
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
            }
        }
    }
}
