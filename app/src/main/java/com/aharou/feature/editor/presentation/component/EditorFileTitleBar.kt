package com.aharou.feature.editor.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Spacing

/** 文件名小栏高度与左右内边距。 */
private val FILE_TITLE_BAR_HEIGHT = 16.dp
private val FILE_TITLE_HORIZONTAL_PADDING = 12.dp

/** 文件读写统一按 UTF-8，小栏右侧展示用。 */
private const val FILE_ENCODING = "UTF-8"

/** 导航栏下方的文件名小栏：左侧文件名（未保存时名前加星号），右侧行:列与文件编码。 */
@Composable
internal fun EditorFileTitleBar(fileName: String, dirty: Boolean, line: Int, column: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(FILE_TITLE_BAR_HEIGHT)
            .padding(horizontal = FILE_TITLE_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dirty) {
            Text(
                text = "*",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$line:$column",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
        Text(
            text = FILE_ENCODING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md)
        )
    }
}
