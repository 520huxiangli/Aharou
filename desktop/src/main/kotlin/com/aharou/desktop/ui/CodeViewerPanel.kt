package com.aharou.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import java.io.File

/**
 * 文件查看/编辑区。
 *
 * 用 Swing 的 RSyntaxTextArea 而不是自研：语法高亮、折叠、大文件滚动这些都现成，
 * 而且它是纯 JVM 库，不像 Monaco 那样要嵌一整个 Chromium（也不存在 linux-arm64 缺产物的问题）。
 */
@Composable
fun CodeViewerPanel(file: File?, content: String, modifier: Modifier = Modifier) {
    val textArea = remember {
        RSyntaxTextArea().apply {
            isCodeFoldingEnabled = true
            antiAliasingEnabled = true
            tabSize = 4
        }.also { applyDarkTheme(it) }
    }

    LaunchedEffect(file, content) {
        textArea.text = content
        textArea.syntaxEditingStyle = styleFor(file?.name)
        textArea.caretPosition = 0
    }

    SwingPanel(
        factory = { RTextScrollPane(textArea) },
        modifier = modifier
    )
}

private fun applyDarkTheme(area: RSyntaxTextArea) {
    runCatching {
        val stream = RSyntaxTextArea::class.java
            .getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")
        if (stream != null) {
            stream.use { Theme.load(it).apply(area) }
        }
    }
}

private fun styleFor(name: String?): String = when (name?.substringAfterLast('.', "")?.lowercase()) {
    "kt", "kts" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN
    "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA
    "xml", "html", "htm" -> SyntaxConstants.SYNTAX_STYLE_XML
    "json" -> SyntaxConstants.SYNTAX_STYLE_JSON
    "gradle" -> SyntaxConstants.SYNTAX_STYLE_GROOVY
    "md", "markdown" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN
    "py" -> SyntaxConstants.SYNTAX_STYLE_PYTHON
    "js", "mjs" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT
    "ts" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT
    "sh", "bash" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL
    "yml", "yaml" -> SyntaxConstants.SYNTAX_STYLE_YAML
    "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL
    "c", "h" -> SyntaxConstants.SYNTAX_STYLE_C
    "cpp", "hpp", "cc" -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS
    "rs" -> SyntaxConstants.SYNTAX_STYLE_RUST
    "go" -> SyntaxConstants.SYNTAX_STYLE_GO
    "toml", "ini", "properties", "conf" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
    else -> SyntaxConstants.SYNTAX_STYLE_NONE
}
