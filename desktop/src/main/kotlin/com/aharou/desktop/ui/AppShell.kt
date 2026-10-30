package com.aharou.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aharou.desktop.DesktopSettings
import com.aharou.desktop.DesktopSettingsStore
import java.io.File

/** 三栏外壳：左文件树、中预览、右对话。 */
@Composable
fun AppShell() {
    var settings by remember { mutableStateOf(DesktopSettingsStore.load()) }
    var showSettings by remember { mutableStateOf(false) }
    var openedFile by remember { mutableStateOf<File?>(null) }
    var openedText by remember { mutableStateOf("") }

    val root = remember(settings.workspacePath) {
        File(settings.workspacePath.ifBlank { System.getProperty("user.home") })
    }

    Row(Modifier.fillMaxSize()) {
        FileTreePanel(
            root = root,
            onOpen = { file ->
                openedFile = file
                openedText = readPreview(file)
            },
            modifier = Modifier.width(280.dp).fillMaxHeight()
        )
        DividerLine(vertical = true)
        FilePreviewPanel(openedFile, openedText, Modifier.weight(1f).fillMaxHeight())
        DividerLine(vertical = true)
        ChatPanel(
            settings = settings,
            onOpenSettings = { showSettings = true },
            modifier = Modifier.width(440.dp).fillMaxHeight()
        )
    }

    if (showSettings) {
        SettingsDialog(
            initial = settings,
            onDismiss = { showSettings = false },
            onSave = {
                settings = it
                DesktopSettingsStore.save(it)
                showSettings = false
            }
        )
    }
}

private fun readPreview(file: File): String = runCatching {
    val limit = 512 * 1024L
    if (file.length() > limit) {
        "（文件大于 512KB，只显示开头部分）\n\n" + file.readText().take(limit.toInt())
    } else {
        file.readText()
    }
}.getOrElse { "读不出来：${it.message}" }
