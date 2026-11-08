package com.aharou.feature.agent.presentation.component

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.aharou.R
import java.io.File

@Composable
internal fun rememberChatCameraCapture(onCaptured: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    // 系统相机可能使宿主进程被回收，结果返回时仍要找得到对应的输出 URI。
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = photoUri
        photoUri = null
        if (success && uri != null) currentOnCaptured(Uri.parse(uri))
    }

    fun showFailure(message: Int) {
        photoUri = null
        Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
    }

    fun capture(hasPermission: Boolean, onPermissionRequired: () -> Unit) {
        runChatCameraAction(
            hasPermission = hasPermission,
            onPermissionRequired = onPermissionRequired,
            takePicture = {
                val uri = runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                    )
                }.getOrNull()
                if (uri == null) {
                    showFailure(R.string.chat_camera_file_failed)
                } else {
                    photoUri = uri.toString()
                    pictureLauncher.launch(uri)
                }
            },
            onFailure = ::showFailure
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        capture(granted) { showFailure(R.string.chat_camera_permission_denied) }
    }
    return {
        capture(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

internal fun runChatCameraAction(
    hasPermission: Boolean,
    onPermissionRequired: () -> Unit,
    takePicture: () -> Unit,
    onFailure: (Int) -> Unit
) {
    try {
        if (hasPermission) takePicture() else onPermissionRequired()
    } catch (_: SecurityException) {
        // 权限检查与启动相机之间仍可能撤权，不能只依赖前面的检查。
        onFailure(R.string.chat_camera_permission_denied)
    } catch (_: ActivityNotFoundException) {
        onFailure(R.string.chat_camera_launch_failed)
    }
}
