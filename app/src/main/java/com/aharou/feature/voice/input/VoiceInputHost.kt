package com.aharou.feature.voice.input

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R

/** 输入栏拿到的语音输入句柄：状态给界面，两个动作给手势。 */
internal class VoiceInputHost(
    val state: VoiceInputState,
    private val startAction: () -> Unit,
    private val finishAction: () -> Unit,
) {
    fun start() = startAction()
    fun finish() = finishAction()
}

/**
 * 语音输入的接线点：取 ViewModel、把识别文本实时写回输入框、按下时按需申请录音权限。
 *
 * 收在一处而不是散在调用方：输入栏在面板、全屏输入、浏览器浮层都有实例，
 * 各写一遍必然有一处漏掉草稿回写或权限申请。
 */
@Composable
internal fun rememberVoiceInputHost(
    currentInput: String,
    onDraftChange: (String) -> Unit,
): VoiceInputHost {
    val viewModel: VoiceInputViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    // 输入栏在面板、全屏输入、浏览器浮层都有实例，同一个 ViewModelStoreOwner 下会共用一个 ViewModel；
    // 只让「按下麦克风的那个实例」回写，不然识别文本会同时灌进好几个输入框。
    var ownsSession by remember { mutableStateOf(false) }
    LaunchedEffect(draft, ownsSession) {
        if (ownsSession) draft?.let(onDraftChange)
    }

    val context = LocalContext.current
    val latestInput = rememberUpdatedState(currentInput)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        VoiceInputPermission.grantedResult(granted) { allowed ->
            Toast.makeText(context, if (allowed) R.string.voice_input_permission_granted_retry else R.string.voice_input_no_permission,
                Toast.LENGTH_SHORT).show()
        }
    }
    return VoiceInputHost(
        state = state,
        startAction = {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            VoiceInputPermission.requestStart(granted, start = {
                ownsSession = true
                viewModel.start(latestInput.value)
            }, request = {
                ownsSession = false
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            })
        },
        finishAction = { viewModel.finish() },
    )
}
