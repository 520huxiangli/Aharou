package com.aharou.feature.sandbox

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.ui.decodeSampledBitmap
import com.aharou.core.ui.rememberViewerDecodeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件预览页的图片格。
 *
 * 独立成文件是为了把整尺寸解码挡在这里：相机原图不分青红皂白地 `decodeFile` 在低端机上就是
 * 几十 MB，按当前窗口算出的查看器档位采样，OOM 时由解码器自动降档。
 */
@Composable
internal fun ImagePreview(item: FileItem) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var sourceSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val spec = rememberViewerDecodeSpec()

    LaunchedEffect(item.file, spec) {
        withContext(Dispatchers.IO) {
            val path = item.file.absolutePath
            val decoded = decodeSampledBitmap(path, spec.maxEdge, spec.maxPixels)
            if (decoded == null) {
                error = ctx.getString(R.string.filepreview_image_decode_error)
            } else {
                sourceSize = sourceImageSize(path)
                bitmap = decoded
            }
        }
    }

    when {
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        bitmap != null -> {
            val (width, height) = sourceSize ?: (bitmap!!.width to bitmap!!.height)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Image info
                Text(
                    text = "$width x $height px  |  ${item.formattedSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 只读原图宽高（不解码像素）：预览图已采样缩小，而面板上要显示的仍是原图尺寸。 */
private fun sourceImageSize(path: String): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, options) }
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}
