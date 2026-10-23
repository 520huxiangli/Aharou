package com.aharou.feature.voice.assistant.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aharou.MainActivity
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import com.aharou.feature.settings.presentation.component.AppThemeHost
import com.aharou.feature.settings.presentation.component.rememberAppAppearance
import com.aharou.feature.voice.assistant.AssistStructureReader
import com.aharou.feature.voice.assistant.AssistantContextHolder
import com.aharou.feature.voice.assistant.ShizukuScreenContext
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 系统助手唤起后显示的浮层。
 *
 * 由 [com.aharou.feature.voice.assistant.AharouAssistantSession] 通过
 * `startAssistantActivity` 拉起：窗口属性由系统按助手规则摆好，这里只画内容。
 * 透明主题 + 底部卡片，点卡片外的空白关闭。
 */
@AndroidEntryPoint
class AssistantOverlayActivity : ComponentActivity() {

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    @Inject
    lateinit var shizukuScreen: ShizukuScreenContext

    private val viewModel: AssistantOverlayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.aharou.core.util.FileLogger.i("AssistantOverlay", "浮层 onCreate")
        val fromTile = intent?.getBooleanExtra(EXTRA_FROM_TILE, false) == true
        setContent {
            val appearance = rememberAppAppearance(themeSettings)
            val screen by AssistantContextHolder.context.collectAsStateWithLifecycle()
            val uiState by viewModel.state.collectAsStateWithLifecycle()
            AppThemeHost(appearance) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.aharou.core.util.FileLogger.i("AssistantOverlay", "Compose 首帧")
                }
                AssistantOverlayScreen(
                    screen = screen,
                    state = uiState,
                    fromTile = fromTile,
                    onSend = viewModel::send,
                    onAction = viewModel::runAction,
                    onOpenChat = ::openFullChat,
                    onClose = ::finish,
                )
            }
        }
        if (fromTile) captureScreenForTile()
    }

    /**
     * 磁贴 / 长按电源唤起时没有系统投递的 Assist 数据，屏幕内容用 Shizuku 现抓。
     *
     * 截图与 dump 前先把浮层窗口透明掉，否则抓到的就是自己的卡片。
     * Shizuku 未就绪就跳过，界面照常能聊，只是没带屏幕内容。
     */
    private fun captureScreenForTile() {
        lifecycleScope.launch {
            val attrs = window.attributes
            val originalAlpha = attrs.alpha
            attrs.alpha = 0f
            window.attributes = attrs
            delay(HIDE_BEFORE_CAPTURE_MS)
            val result = shizukuScreen.capture()
            attrs.alpha = if (originalAlpha == 0f) 1f else originalAlpha
            window.attributes = attrs
            result.screenshot?.let { AssistantContextHolder.updateScreenshot(it) }
            if (result.text.isNotBlank()) {
                AssistantContextHolder.updateText(
                    result.text,
                    AssistStructureReader.appLabel(this@AssistantOverlayActivity, result.packageName),
                    result.packageName,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.aharou.core.util.FileLogger.i("AssistantOverlay", "浮层 onResume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.aharou.core.util.FileLogger.i("AssistantOverlay", "浮层窗口获得焦点（已可见）")
    }

    override fun onDestroy() {
        // 用户直接按返回键时不会走 session 的 onHide，这里兜一次，别把整屏位图留在内存里。
        if (isFinishing) AssistantContextHolder.releaseScreenshot()
        super.onDestroy()
    }

    private fun openFullChat() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    companion object {
        const val EXTRA_SHOW_FLAGS = "assistant_show_flags"

        /** 从下拉磁贴唤起：没有系统投递的 Assist 数据，界面不展示「正在读取屏幕」。 */
        const val EXTRA_FROM_TILE = "assistant_from_tile"

        private const val HIDE_BEFORE_CAPTURE_MS = 150L
    }
}
