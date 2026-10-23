package com.aharou.feature.voice.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.aharou.core.util.FileLogger
import com.aharou.feature.voice.assistant.presentation.AssistantOverlayActivity

/**
 * 系统助手唤起后的会话：接住 Assist 数据，并把界面交给浮层 Activity。
 *
 * 为什么界面不自己画：`onCreateContentView` 走的是系统给的窗口，Compose 的
 * Lifecycle/ViewModelStore 要手写宿主，软键盘在系统窗口里能否弹起也没有保证。
 * 官方给的另一条路是 `startAssistantActivity`，窗口由系统按助手规则摆好，
 * Activity 里 Compose、主题、输入法、返回键全是现成环境，第一版走这条更稳。
 */
class AharouAssistantSession(private val host: Context) : VoiceInteractionSession(host) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // 关掉系统给会话本身准备的窗口：界面由浮层 Activity 承担。
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantContextHolder.reset()
        val intent = Intent(host, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantOverlayActivity.EXTRA_SHOW_FLAGS, showFlags)
        runCatching { startAssistantActivity(intent) }
            .onFailure { FileLogger.w(TAG, "拉起助手浮层失败：${it.message}") }
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        val packageName = structure?.activityComponent?.packageName
        AssistantContextHolder.updateAssist(structure, AssistStructureReader.appLabel(host, packageName))
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        AssistantContextHolder.updateScreenshot(screenshot)
    }

    override fun onHide() {
        super.onHide()
        // 位图已经从系统拿不回来了，浮层关掉就立刻还内存。
        AssistantContextHolder.releaseScreenshot()
    }

    private companion object {
        const val TAG = "AharouAssistantSession"
    }
}
