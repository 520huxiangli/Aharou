package com.aharou.feature.voice.assistant

import android.service.voice.VoiceInteractionService
import com.aharou.core.util.FileLogger

/**
 * 把 Aharou 注册成系统数字助手（可选，默认不启用）。
 *
 * 为什么值得单独做这一层：一旦用户把本应用选为默认助手，系统会**一直保活**这个服务
 * （官方原话："kept always running by the system"），用于后台听唤醒词——比普通前台服务
 * 更不容易被内存回收，也能接长按电源键 / 锁屏唤起。代价是系统同一时刻只允许一个默认
 * 助手，设成本应用就会顶掉原有的（如 ColorOS 的小布）。
 *
 * 这里刻意保持极轻：真正的唤醒检测仍在 [com.aharou.feature.voice.call.VoiceCallService]
 * （它才是持有麦克风的那个），本服务只承担「被系统绑定为助手」这件事本身，
 * 重活按官方要求放到 [AharouVoiceSessionService] 里。
 */
class AharouVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        FileLogger.i(TAG, "已作为系统助手被绑定")
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "助手服务创建")
    }

    companion object {
        private const val TAG = "AharouVoiceInteraction"
    }
}
