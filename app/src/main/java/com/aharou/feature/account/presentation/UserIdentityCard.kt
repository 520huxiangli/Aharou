package com.aharou.feature.account.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.identity.UserIdentity
import com.aharou.core.identity.UserIdentityStore
import com.aharou.core.soul.SoulIcon
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.decodeSampledAndroidBitmap
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import com.aharou.feature.settings.presentation.component.SoulEmojiPickerSheet
import com.aharou.feature.settings.presentation.component.SoulIconGlyph
import compose.icons.FeatherIcons
import compose.icons.feathericons.Image
import compose.icons.feathericons.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户头像未设置时的占位图标；和助手的 ✨ 区分开。 */
private const val USER_AVATAR_FALLBACK = "\uD83D\uDE42"

/** 头像落库才 96px，采样到 512 已远高于展示需求，又不至于把相册原图整份解开。 */
private const val AVATAR_DECODE_MAX_EDGE = 512

/**
 * 「我的身份」卡片：头像 + 显示名。
 *
 * 头像与助手共用一套表示法（短 emoji 或 PNG data URI）和同一张 emoji 推荐表，所以在聊天里两条
 * 身份行看起来是一对，而不是两种设计。改动即时落盘，没有保存按钮 —— 这一项不值得再要一次确认。
 */
@Composable
internal fun UserIdentityCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity by remember(context) { UserIdentityStore.observe(context) }
        .collectAsStateWithLifecycle(UserIdentity())

    // 以已落盘的名字为 key：外部改了名字（另一处入口）时草稿跟着对齐。
    var nameDraft by rememberSaveable(identity.name) { mutableStateOf(identity.name) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }

    val unreadableMsg = stringResource(R.string.account_avatar_error_unreadable)
    val tooLargeMsg = stringResource(R.string.account_avatar_error_too_large)

    // 相册选图：解码 → 裁方 → 缩小 → PNG data URI，与助手图标走同一条流水线（原图可能很大）。
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // 相册里随手一张就是几千万像素，先取字节再按头像档位采样解码；OOM 时解码器会自动降档
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                val bitmap = bytes?.let { decodeSampledAndroidBitmap(it, AVATAR_DECODE_MAX_EDGE) }
                if (bitmap == null) {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                } else {
                    SoulIcon.encode(bitmap)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> UserIdentityStore.setIcon(context, result.dataUri)
                is SoulIcon.EncodeResult.Failure -> pickError = when (result.reason) {
                    SoulIcon.Rejection.TOO_LARGE -> tooLargeMsg
                    SoulIcon.Rejection.UNREADABLE -> unreadableMsg
                }
            }
        }
    }

    SettingsGroupHeader(stringResource(R.string.account_identity_title))
    SettingsGroup {
        SettingsRow(
            icon = FeatherIcons.User,
            title = stringResource(R.string.account_avatar),
            subtitle = stringResource(R.string.account_avatar_hint),
            onClick = { showEmojiSheet = true },
            trailing = {
                SoulIconGlyph(
                    icon = identity.icon,
                    sizeDp = 24.dp,
                    emojiSp = 18.sp,
                    fallbackEmoji = USER_AVATAR_FALLBACK,
                )
            }
        )
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Image,
            title = stringResource(R.string.account_avatar_gallery),
            onClick = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
        SettingsDivider()
        Column(modifier = Modifier.padding(Spacing.md)) {
            AppTextField(
                value = nameDraft,
                onValueChange = {
                    nameDraft = it
                    scope.launch { UserIdentityStore.setName(context, it) }
                },
                label = stringResource(R.string.account_name_label),
                placeholder = stringResource(R.string.account_name_placeholder)
            )
        }
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(identity.icon)) "" else identity.icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen ->
                scope.launch { UserIdentityStore.setIcon(context, chosen) }
                showEmojiSheet = false
            },
        )
    }

    pickError?.let { error ->
        AlertDialog(
            onDismissRequest = { pickError = null },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { pickError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }
}
