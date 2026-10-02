package com.aharou.feature.voice.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * 语音交互会话的宿主，由系统在真正发生语音交互时绑定。
 *
 * 系统只保证它在单次会话期间活着，所以这里不持有任何常驻状态。
 * 当前实现不做独立的话术界面：长按唤起后仍由 [com.aharou.feature.voice.call.VoiceCallService]
 * 与桌宠小染承担听说的全部流程，这个会话只负责把系统的调用接住，避免默认助手
 * 因缺少 sessionService 而不可用。
 */
class AharouVoiceSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VoiceInteractionSession(this)
}
