package com.aharou.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aharou.desktop.ui.AppShell

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Aharou",
        state = rememberWindowState(size = DpSize(1360.dp, 860.dp))
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppShell()
            }
        }
    }
}
