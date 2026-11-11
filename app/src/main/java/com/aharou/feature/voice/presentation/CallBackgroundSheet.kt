package com.aharou.feature.voice.presentation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.voice.data.CallBackgroundRepository
import java.io.File
import kotlinx.coroutines.launch

/**
 * 通话背景设置：选图、模糊、压暗，只作用于语音通话全屏页。
 *
 * 与聊天背景分开是刻意的：通话页整屏都是字，聊天那张往往不够暗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallBackgroundSheet(
    current: CallBackgroundRepository.Config,
    onDismiss: () -> Unit,
    onSave: (CallBackgroundRepository.Config) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CallBackgroundRepository(context) }
    var path by remember { mutableStateOf(current.imagePath) }
    var blur by remember { mutableFloatStateOf(current.blurDp.toFloat()) }
    var dim by remember { mutableFloatStateOf(current.dim) }
    var clearing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                repository.saveImage(uri)?.let { path = it }
            }
        }
    }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_call_background),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.call_bg_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val preview = remember(path) {
                path?.let { File(repository.dir(), it).takeIf { file -> file.exists() } }
            }
            if (preview != null) {
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(Radius.mdLarge)),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(stringResource(R.string.call_bg_pick))
                }
                if (preview != null) {
                    TextButton(onClick = {
                        clearing = true
                        scope.launch {
                            repository.clearImage()
                            path = null
                            clearing = false
                        }
                    }) {
                        Text(stringResource(R.string.call_bg_clear))
                    }
                }
            }

            Text(
                text = stringResource(R.string.call_bg_blur) + "  " + blur.toInt().toString(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(value = blur, onValueChange = { blur = it }, valueRange = 0f..40f)

            Text(
                text = stringResource(R.string.call_bg_dim) + "  " + (dim * 100).toInt() + "%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(value = dim, onValueChange = { dim = it }, valueRange = 0f..0.9f)

            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(
                    enabled = !clearing,
                    onClick = {
                        onSave(
                            CallBackgroundRepository.Config(
                                imagePath = path,
                                blurDp = blur.toInt(),
                                dim = dim,
                            )
                        )
                    },
                ) {
                    Text(stringResource(R.string.call_bg_save))
                }
            }
        }
    }
}
