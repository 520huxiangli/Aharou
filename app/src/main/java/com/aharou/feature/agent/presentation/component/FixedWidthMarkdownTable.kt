package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/**
 * 表格渲染：每列给一个宽度下限，超出屏幕就整张横滑。
 *
 * 库自带的 `MarkdownTable` 按「列数 × 固定单元宽」算总宽，列一多每列被压到几十 dp，
 * 中文和长英文只能逐字换行，行高跟着炸开（右列还会挤成竖排）。这里改成先拿可用宽度再决定：
 * 够分就均分，不够分就按 [MIN_CELL_WIDTH] 固定列宽并让整张表横向滚动，保证每格至少能放下几个字。
 */
@Composable
fun FixedWidthMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    cellPadding: Dp = 6.dp,
) {
    val columns = remember(node) {
        node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
    }
    if (columns == 0) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxCellWidth = maxWidth / columns
        val cellWidth = if (maxCellWidth < MIN_CELL_WIDTH) MIN_CELL_WIDTH else maxCellWidth
        val totalWidth = cellWidth * columns
        val scrollable = totalWidth > maxWidth

        val container = if (scrollable) {
            Modifier.horizontalScroll(rememberScrollState()).width(totalWidth)
        } else {
            Modifier.fillMaxWidth()
        }

        Column(
            modifier = container
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(6.dp)
                )
                .padding(vertical = 4.dp)
        ) {
            node.children.forEach { row ->
                if (row.type != HEADER && row.type != ROW) return@forEach
                val isHeader = row.type == HEADER
                Row(modifier = Modifier.width(totalWidth)) {
                    row.children.filter { it.type == CELL }.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .width(cellWidth)
                                .padding(horizontal = cellPadding, vertical = 4.dp)
                        ) {
                            MarkdownTableBasicText(
                                content = content,
                                cell = cell,
                                style = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 每列最小宽度：低于这个值中文就只能一字一行了。 */
private val MIN_CELL_WIDTH = 96.dp
