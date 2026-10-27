package com.aharou.feature.pet

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PNG 导入的摆放界面：单指拖动挪位置、双指缩放，框里看到的就是最终立绘。
 *
 * 确定时只交出原图字节与 [CropSpec]（预览框像素），烘焙与落盘交给
 * [PetAvatarImporter.importImage]，这里不写盘。
 */
@Composable
internal fun PetCropDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray, CropSpec) -> Unit,
) {
    val context = LocalContext.current
    val importer = remember { PetAvatarImporter(context) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var isJpeg by remember { mutableStateOf(false) }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(uri) {
        val data = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }
        val decoded = data?.let { withContext(Dispatchers.Default) { importer.decode(it) } }
        if (data == null || decoded == null) {
            failed = true
            return@LaunchedEffect
        }
        isJpeg = context.contentResolver.getType(uri)
            ?.let { it.contains("jpeg", true) || it.contains("jpg", true) } == true
        preview = decoded.asImageBitmap()
        bytes = data
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.semanticColors.cardSurface,
            modifier = Modifier
                .padding(Spacing.xl)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.pet_crop_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.pet_crop_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.md)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.semanticColors.mutedSurface)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.semanticColors.subtleBorder,
                            shape = RoundedCornerShape(Radius.mdLarge),
                        )
                        .clip(RoundedCornerShape(Radius.mdLarge))
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(MIN_CROP_SCALE, MAX_CROP_SCALE)
                                offset += pan
                            }
                        }
                        .onSizeChanged { viewportPx = it.width.toFloat() },
                    contentAlignment = Alignment.Center,
                ) {
                    val image = preview
                    when {
                        image != null -> Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                        failed -> Text(
                            text = stringResource(R.string.pet_crop_failed),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> CircularProgressIndicator()
                    }
                }
                if (isJpeg) {
                    Text(
                        text = stringResource(R.string.pet_crop_jpeg),
                        fontSize = 12.sp,
                        color = MaterialTheme.semanticColors.warning,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    TextButton(
                        enabled = bytes != null && viewportPx > 0f,
                        onClick = {
                            val data = bytes ?: return@TextButton
                            if (viewportPx <= 0f) return@TextButton
                            onConfirm(data, CropSpec(viewportPx, scale, offset.x, offset.y))
                        },
                    ) {
                        Text(stringResource(R.string.pet_crop_use))
                    }
                }
            }
        }
    }
}

/** 重命名用户角色（改的是 `name.txt`，目录名即 id 不动）。 */
@Composable
internal fun PetRenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pet_role_rename_title)) },
        text = {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.pet_role_name_label),
                colors = dialogTextFieldColors(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 只能提示、不能交互的单按钮弹窗（导入失败、比例不对之类）。 */
@Composable
internal fun PetNoticeDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_ok))
            }
        },
    )
}

/** 删除类操作的二次确认：确认按钮用错误色。 */
@Composable
internal fun PetConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private const val MIN_CROP_SCALE = 0.2f
private const val MAX_CROP_SCALE = 5f
