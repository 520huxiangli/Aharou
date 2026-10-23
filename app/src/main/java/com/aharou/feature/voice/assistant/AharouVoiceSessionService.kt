package com.aharou.feature.voice.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * 语音交互会话的宿主，由系统在真正发生交互时绑定（长按电源 / 主屏助手手势 / 锁屏唤起）。
 *
 * 系统只保证它在单次会话期间活着，所以这里不持有任何常驻状态；
 * 会话本身负责接住 Assist 数据并把界面交给浮层 Activity，见 [AharouAssistantSession]。
 */
class AharouVoiceSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AharouAssistantSession(this)
}
