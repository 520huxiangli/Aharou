package com.aharou.feature.settings.presentation.component

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.soul.SoulBodyLimitCheck
import com.aharou.core.soul.SoulFile
import com.aharou.core.soul.SoulIcon
import com.aharou.core.soul.SoulMDParser
import com.aharou.core.soul.SoulMetadata
import com.aharou.core.soul.SoulStore
import com.aharou.core.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 人格（SOUL.md）设置页——自 Minis `SoulSettingsScreen` 移植适配。
 *
 * 预览卡片（图标 + 名字 + 风格）→ 身份字段（名字/风格/语言）→ 人格正文（带字/词数
 * 与超限提示）→ 恢复默认 / 保存。保存走 [SoulStore.save]，同时刷新
 * [SoulStore.cachedMetadata]，聊天里的身份行立刻跟着变，无需重启。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoulSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(SoulMetadata.DEFAULT.name) }
    var style by remember { mutableStateOf(SoulMetadata.DEFAULT.style) }
    var lang by remember { mutableStateOf(SoulMetadata.DEFAULT.lang) }
    var body by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(SoulMetadata.DEFAULT.icon) }
    // 保留原文里的 emoji 字段以做写回轮换（UI 不暴露，图标一律走 icon 字段）。
    var preservedEmoji by remember { mutableStateOf(SoulMetadata.DEFAULT.emoji) }
    var loaded by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<SoulFile?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var showIconMenu by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val iconTooLargeMsg = stringResource(R.string.soul_icon_error_too_large)

    // 相册选图：解码 → 裁方 → 缩小 → PNG data URI（IO 线程，整张原图可能很大）。
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp == null) {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                } else {
                    SoulIcon.encode(bmp)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> iconError = when (result.reason) {
                    SoulIcon.Rejection.TOO_LARGE -> iconTooLargeMsg
                    SoulIcon.Rejection.UNREADABLE -> iconUnreadableMsg
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val parsed = withContext(Dispatchers.IO) {
            SoulStore.ensureExists(context)
            SoulStore.load(context) ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        }
        name = parsed.metadata.name
        preservedEmoji = parsed.metadata.emoji
        icon = parsed.metadata.icon
        style = parsed.metadata.style
        lang = parsed.metadata.lang
        body = parsed.body
        // 基线与保存走同一套归一化，避免「打开就显示有未保存修改」。
        baseline = SoulFile(
            metadata = SoulMetadata(
                name = parsed.metadata.name.ifBlank { SoulMetadata.DEFAULT.name },
                emoji = parsed.metadata.emoji.ifBlank { SoulMetadata.DEFAULT.emoji },
                icon = parsed.metadata.icon,
                style = parsed.metadata.style,
                lang = parsed.metadata.lang.ifBlank { SoulMetadata.DEFAULT.lang },
            ),
            body = parsed.body,
        )
        loaded = true
    }

    val bodyLimitCheck by remember(body) { derivedStateOf { SoulStore.isOverLimit(body) } }

    val currentFile = SoulFile(
        metadata = SoulMetadata(
            name = name.ifBlank { SoulMetadata.DEFAULT.name },
            emoji = preservedEmoji.ifBlank { SoulMetadata.DEFAULT.emoji },
            icon = icon,
            style = style,
            lang = lang.ifBlank { SoulMetadata.DEFAULT.lang },
        ),
        body = body,
    )
    val isDirty = loaded && baseline != null && currentFile != baseline

    val save: () -> Unit = {
        if (!bodyLimitCheck.isOverLimit) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { SoulStore.save(context, currentFile) }
                    baseline = currentFile
                    justSaved = true
                } catch (t: Throwable) {
                    saveError = t.message ?: "save failed"
                }
            }
        }
        Unit
    }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(1600)
            justSaved = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        // ── 预览 ──
        SettingsGroupHeader(text = stringResource(R.string.soul_section_preview))
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { showIconMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        SoulIconGlyph(icon = icon, sizeDp = 30.dp, emojiSp = 22.sp)
                        // 铅笔角标：图标可点这件事要看得出来。
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(2.5.dp),
                        )
                    }
                    DropdownMenu(expanded = showIconMenu, onDismissRequest = { showIconMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.soul_icon_choose_emoji)) },
                            onClick = { showIconMenu = false; showEmojiSheet = true },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.soul_icon_choose_image))
                                    Text(
                                        text = stringResource(R.string.soul_icon_image_hint),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                showIconMenu = false
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        if (icon.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.soul_icon_use_default)) },
                                onClick = { showIconMenu = false; icon = "" },
                            )
                        }
                    }
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = name.ifBlank { SoulMetadata.DEFAULT.name },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (style.isNotBlank()) {
                        Text(
                            text = style,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── 身份 ──
        SettingsGroupHeader(text = stringResource(R.string.soul_section_identity))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text(stringResource(R.string.soul_field_style)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SoulLangPicker(lang = lang, onLangChange = { lang = it })
            }
        }

        // ── 人格正文 ──
        SettingsGroupHeader(text = stringResource(R.string.soul_section_personality))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
            ) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = { Text(stringResource(R.string.soul_body_placeholder)) },
                )
                Spacer(Modifier.height(6.dp))
                val overLimit = bodyLimitCheck.isOverLimit
                val indicatorText: String = when (val check = bodyLimitCheck) {
                    is SoulBodyLimitCheck.Ok -> soulBodyCountText(body)
                    is SoulBodyLimitCheck.OverLimitChinese ->
                        stringResource(R.string.soul_over_limit_chinese, check.chars, check.cap)
                    is SoulBodyLimitCheck.OverLimitEnglish ->
                        stringResource(R.string.soul_over_limit_english, check.words, check.cap)
                }
                Text(
                    text = indicatorText,
                    fontSize = 12.sp,
                    color = if (overLimit) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.soul_personality_footer),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ── 动作 ──
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_restore_default)) }
                Button(
                    onClick = save,
                    enabled = loaded && isDirty && !bodyLimitCheck.isOverLimit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (justSaved) stringResource(R.string.soul_saved)
                        else stringResource(R.string.soul_save)
                    )
                }
            }
        }
        if (isDirty) {
            Text(
                text = stringResource(R.string.soul_unsaved_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xl, top = 6.dp),
            )
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.soul_restore_confirm_title)) },
            text = { Text(stringResource(R.string.soul_restore_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
                    name = parsed.metadata.name
                    preservedEmoji = parsed.metadata.emoji
                    icon = parsed.metadata.icon
                    style = parsed.metadata.style
                    lang = parsed.metadata.lang
                    body = parsed.body
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.soul_restore_default)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    iconError?.let { err ->
        AlertDialog(
            onDismissRequest = { iconError = null },
            title = { Text(stringResource(R.string.soul_icon_error_title)) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { iconError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(icon)) "" else icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen -> icon = chosen; showEmojiSheet = false },
        )
    }
}

/**
 * 图标本体：解码后的位图（data URI）/ emoji / 默认 ✨，三处（设置预览、聊天身份行）共用。
 * 位图解码按 icon 串记忆化，避免流式跳动时反复解码 base64。
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
) {
    val bitmap = remember(icon) { SoulIcon.decode(icon) }
    when {
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)),
        )
        icon.isNotEmpty() -> Text(text = icon, fontSize = emojiSp)
        else -> Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}

/** 图标选择弹层：建议 emoji 两行（点选即填）+ 自由输入（逐键归一）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulEmojiPickerSheet(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.soul_icon_emoji_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = draft.ifEmpty { SoulMetadata.DISPLAY_EMOJI },
                    fontSize = 30.sp,
                )
            }
            Spacer(Modifier.height(20.dp))
            SoulIcon.SUGGESTED_EMOJI.chunked(8).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (e == draft) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .clickable { draft = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(text = e, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = SoulIcon.normalizeEmojiInput(it) },
                label = { Text(stringResource(R.string.soul_icon_emoji_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.soul_cancel))
                }
                Button(
                    onClick = { onPick(draft) },
                    enabled = draft.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_icon_set)) }
            }
        }
    }
}

/** 正文计数器：按与上限同一条 CJK 比例规则决定显示「字」还是「words」。 */
@Composable
private fun soulBodyCountText(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return stringResource(R.string.soul_count_zero)
    var cjk = 0
    var total = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        total += 1
        val isCJK =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x3040..0x309F ||
                cp in 0x30A0..0x30FF ||
                cp in 0xAC00..0xD7AF
        if (isCJK) cjk += 1
        i += Character.charCount(cp)
    }
    val ratio = if (total > 0) cjk.toDouble() / total else 0.0
    return if (ratio > SoulStore.CJK_RATIO_THRESHOLD) {
        val chars = trimmed.codePointCount(0, trimmed.length)
        stringResource(R.string.soul_count_chars, chars, SoulStore.CHINESE_CHAR_LIMIT)
    } else {
        val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        stringResource(R.string.soul_count_words, words, SoulStore.ENGLISH_WORD_LIMIT)
    }
}

/** 语言三选一（auto / 中文 / English）：直接平铺按钮，不依赖下拉组件。 */
@Composable
private fun SoulLangPicker(lang: String, onLangChange: (String) -> Unit) {
    val options = listOf(
        "auto" to stringResource(R.string.soul_lang_auto),
        "zh" to stringResource(R.string.soul_lang_zh),
        "en" to stringResource(R.string.soul_lang_en),
    )
    val current = options.firstOrNull { it.first == lang }?.first ?: "auto"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.soul_field_lang),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                if (key == current) {
                    Button(onClick = { onLangChange(key) }, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { onLangChange(key) }, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                }
            }
        }
    }
}
