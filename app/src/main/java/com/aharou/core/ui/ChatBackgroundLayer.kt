package com.aharou.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * 聊天页背景层：一张可选图片 + 模糊 + 压暗。
 *
 * 图片存在私有目录（见 `ChatAppearanceSheet` 的 `saveChatBackground`），只存文件名。
 * 没设背景时什么都不画，由调用方自己铺底色——纯色下模糊毫无意义，不必多叠一层。
 */
@Composable
internal fun ChatBackgroundLayer(
    imageName: String?,
    blurDp: Int,
    dim: Float,
    modifier: Modifier = Modifier
) {
    if (imageName.isNullOrBlank()) return
    val context = LocalContext.current
    val file = remember(imageName) {
        File(context.filesDir, "aharou/chat_bg/$imageName").takeIf { it.exists() }
    }
    if (file == null) return

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // API 31 以下 Modifier.blur 是空操作，不会崩，所以不必再判版本
                .blur(blurDp.dp)
        )
        if (dim > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f)))
            )
        }
    }
}
