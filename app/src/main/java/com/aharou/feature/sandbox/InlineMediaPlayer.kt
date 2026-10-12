package com.aharou.feature.sandbox

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Inline audio player composable for embedding in chat messages.
 * Shows play/pause/stop controls and a progress bar.
 */
@Composable
fun InlineAudioPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableStateOf("0:00") }
    var position by remember { mutableStateOf("0:00") }
    var error by remember { mutableStateOf<String?>(null) }

    val mediaPlayer = remember { MediaPlayer() }

    DisposableEffect(filePath) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                mediaPlayer.setDataSource(file.absolutePath)
            } else {
                // Try as content URI
                mediaPlayer.setDataSource(context, Uri.parse(filePath))
            }
            mediaPlayer.prepare()
            duration = formatDuration(mediaPlayer.duration)

            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                progress = 0f
                position = "0:00"
            }
        } catch (e: Exception) {
            error = e.message
        }

        onDispose {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            try {
                val pos = mediaPlayer.currentPosition
                val dur = mediaPlayer.duration.coerceAtLeast(1)
                progress = pos.toFloat() / dur
                position = formatDuration(pos)
            } catch (_: Exception) {}
            delay(250)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (error != null) {
            Text(
                "Audio error: $error",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        } else {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Play/Pause button
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                mediaPlayer.pause()
                                isPlaying = false
                            } else {
                                mediaPlayer.start()
                                isPlaying = true
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(position, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(duration, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Stop button
                    if (isPlaying) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                mediaPlayer.seekTo(0)
                                mediaPlayer.pause()
                                isPlaying = false
                                progress = 0f
                                position = "0:00"
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // File name
                val fileName = filePath.substringAfterLast("/")
                Text(
                    fileName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Inline video player composable. On Android without ExoPlayer,
 * this uses a VideoView via AndroidView.
 */
@Composable
fun InlineVideoPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    val file = File(filePath)
                    val uri = if (file.exists()) Uri.fromFile(file) else Uri.parse(filePath)
                    setVideoURI(uri)

                    // Add basic media controller
                    val controller = android.widget.MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)

                    setOnPreparedListener { mp ->
                        // Auto-size to video dimensions
                        val videoWidth = mp.videoWidth
                        val videoHeight = mp.videoHeight
                        if (videoWidth > 0 && videoHeight > 0) {
                            val ratio = videoHeight.toFloat() / videoWidth
                            layoutParams = layoutParams.apply {
                                height = (width * ratio).toInt().coerceIn(100, 800)
                            }
                        }
                        // Auto-play and surface the controller so the user sees
                        // a live first frame instead of a black surface waiting
                        // for an extra tap.
                        start()
                        controller.show(0)
                    }
                    setOnErrorListener { _, what, extra ->
                        android.util.Log.w("InlineVideoPlayer",
                            "VideoView error what=$what extra=$extra path=$filePath")
                        false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )
    }
}

private fun formatDuration(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
