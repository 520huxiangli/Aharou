package com.aharou.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** 文件树节点；[depth] 只用于缩进，树本身用扁平列表渲染。 */
private data class TreeNode(val file: File, val depth: Int)

private fun flatten(dir: File, depth: Int, expanded: Set<String>, maxDepth: Int = 3): List<TreeNode> {
    if (depth > maxDepth) return emptyList()
    val children = dir.listFiles()
        ?.filter { !it.name.startsWith(".") }
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?: return emptyList()
    return children.take(300).flatMap { child ->
        val node = TreeNode(child, depth)
        if (child.isDirectory && child.absolutePath in expanded) {
            listOf(node) + flatten(child, depth + 1, expanded)
        } else {
            listOf(node)
        }
    }
}

@Composable
fun FileTreePanel(root: File, onOpen: (File) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(root) { mutableStateOf(setOf(root.absolutePath)) }
    val nodes = remember(root, expanded) { flatten(root, 0, expanded) }

    Column(modifier) {
        Text(
            text = root.name.ifEmpty { root.absolutePath },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        )
        DividerLine()
        LazyColumn(Modifier.fillMaxSize()) {
            items(nodes, key = { it.file.absolutePath }) { node ->
                val indent = (node.depth * 12).dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (node.file.isDirectory) {
                                expanded = if (node.file.absolutePath in expanded) {
                                    expanded - node.file.absolutePath
                                } else {
                                    expanded + node.file.absolutePath
                                }
                            } else {
                                onOpen(node.file)
                            }
                        }
                        .padding(start = indent + 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (node.file.isDirectory) "▸ ${node.file.name}" else node.file.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun FilePreviewPanel(file: File?, content: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = file?.absolutePath ?: "未打开文件",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        )
        DividerLine()
        CodeViewerPanel(file, content, Modifier.fillMaxSize())
    }
}

@Composable
fun DividerLine(vertical: Boolean = false) {
    Box(
        Modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
