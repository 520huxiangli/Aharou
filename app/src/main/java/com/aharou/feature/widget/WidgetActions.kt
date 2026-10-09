package com.aharou.feature.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aharou.MainActivity

/**
 * 桌面小组件用到的跳转 action、Intent extra 键，以及 PendingIntent 构造。
 *
 * [ACTION_NEW_CHAT] / [ACTION_START_VOICE] 与 MainActivity 内的同名私有常量、`res/xml/shortcuts.xml`
 * 共同构成「桌面入口协议」，三处必须保持一致：
 * - `com.aharou.intent.NEW_CHAT`：新建对话
 * - `com.aharou.intent.START_VOICE`：开始语音通话
 * - `com.aharou.intent.OPEN_CHAT`：打开指定会话（本组件新增，需 MainActivity 增补一个分支，见交付说明）
 *
 * 全部走**显式** Intent（指定 MainActivity 组件），因此不需要在清单里为它们声明 intent-filter。
 *
 * requestCode 按 `appWidgetId` 派生：桌面上可放多个组件实例，若不区分实例，不同组件的同行会共用
 * 同一个 PendingIntent（相同 requestCode + 相同 Intent 视为等同），导致点谁都进同一个会话。
 */
internal object WidgetActions {

    const val ACTION_NEW_CHAT = "com.aharou.intent.NEW_CHAT"
    const val ACTION_START_VOICE = "com.aharou.intent.START_VOICE"
    const val ACTION_OPEN_CHAT = "com.aharou.intent.OPEN_CHAT"

    /** 会话 id 的 extra 键；MainActivity 侧用 [sessionId] 读取。 */
    const val EXTRA_SESSION_ID = "com.aharou.intent.extra.SESSION_ID"

    // 每个组件实例占用 16 个 requestCode 号段：0/1/2 给三个入口，3..7 给五行会话。
    private const val SLOT_OPEN_APP = 0
    private const val SLOT_NEW_CHAT = 1
    private const val SLOT_VOICE = 2
    private const val SLOT_SESSION_BASE = 3
    private const val SLOTS_PER_WIDGET = 16

    /** 打开应用（默认落到聊天页）。 */
    fun openApp(context: Context, appWidgetId: Int): PendingIntent =
        pending(context, requestCode(appWidgetId, SLOT_OPEN_APP), action = null, sessionId = null)

    /** 新建对话。 */
    fun newChat(context: Context, appWidgetId: Int): PendingIntent =
        pending(context, requestCode(appWidgetId, SLOT_NEW_CHAT), ACTION_NEW_CHAT, null)

    /** 开始语音通话。 */
    fun startVoice(context: Context, appWidgetId: Int): PendingIntent =
        pending(context, requestCode(appWidgetId, SLOT_VOICE), ACTION_START_VOICE, null)

    /** 打开第 [slot] 行的会话（用于会话列表点击）。 */
    fun openSession(context: Context, appWidgetId: Int, slot: Int, sessionId: String): PendingIntent =
        pending(context, requestCode(appWidgetId, SLOT_SESSION_BASE + slot), ACTION_OPEN_CHAT, sessionId)

    /** MainActivity 侧判定：该 Intent 是否为「打开指定会话」。 */
    fun isOpenChat(intent: Intent?): Boolean = intent?.action == ACTION_OPEN_CHAT

    /** MainActivity 侧读取会话 id；缺失或空白时返回 null。 */
    fun sessionId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }

    private fun requestCode(appWidgetId: Int, slot: Int): Int =
        appWidgetId * SLOTS_PER_WIDGET + slot

    private fun pending(
        context: Context,
        requestCode: Int,
        action: String?,
        sessionId: String?
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (action != null) this.action = action
            if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
            // NEW_TASK：从 App Widget 宿主（Launcher）进程拉起 Activity 必须带；
            // SINGLE_TOP：应用已在栈顶时复用实例，交给 onNewIntent 处理，避免重复开页。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
