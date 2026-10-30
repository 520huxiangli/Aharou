package com.aharou.feature.agent.domain.tool.question

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.aharou.feature.agent.presentation.notification.AgentPermissionActionReceiver

/**
 * 提问通知里的「按选项直接作答」动作，走与权限通知相同的广播回填链路。
 *
 * 只对「单个问题」的提问挂动作：一次问多个问题时，通知里只能为第一个问题选一个选项，
 * 直接作答会让其余问题空着，不如点通知回面板一次答完。
 *
 * 动作里把请求 id 与选项序号同时编进 extras 与 [Intent.data]：data 参与 PendingIntent 的
 * 等价性比较，只靠 extras 会让 `FLAG_UPDATE_CURRENT` 复用旧 PendingIntent，陈旧通知就会答到新问题上
 * （与工具授权通知踩过的同一个坑）。
 */
object QuestionNotificationActions {

    const val ACTION_ANSWER = "com.aharou.action.QUESTION_ANSWER"
    const val EXTRA_REQUEST_ID = "com.aharou.extra.QUESTION_REQUEST_ID"
    const val EXTRA_OPTION_INDEX = "com.aharou.extra.QUESTION_OPTION_INDEX"

    /** 通知动作栏放不下 4 个按钮；第 4 个选项只能回面板选。 */
    private const val MAX_ACTIONS = 3

    private const val SCHEME = "aharou-question"
    private const val AUTHORITY = "question"

    /** 给待作答通知挂上选项动作；不满足「单问题」时返回空表（通知只做提示）。 */
    fun buildActions(context: Context, pending: PendingUserQuestion): List<NotificationCompat.Action> {
        val question = pending.questions.singleOrNull() ?: return emptyList()
        return question.options.take(MAX_ACTIONS).mapIndexed { index, option ->
            NotificationCompat.Action(
                android.R.drawable.ic_menu_send,
                option.label,
                answerIntent(context, pending.id, index)
            )
        }
    }

    /**
     * Receiver 侧入口：把通知里的选项作答回填到与面板完全相同的 [AskUserQuestionManager.resolve]。
     *
     * @return 是否命中当前问题；请求 id 对不上（陈旧通知）或序号越界时返回 false，不做任何决策。
     */
    fun handleAnswer(manager: AskUserQuestionManager, intent: Intent): Boolean {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val optionIndex = intent.getIntExtra(EXTRA_OPTION_INDEX, -1)
        if (requestId.isNullOrBlank() || optionIndex < 0) return false
        val pending = manager.pendingQuestion.value ?: return false
        // 只认当前展示的那个问题：进程重启后 id 会变，旧通知点下来不能答到新问题上。
        if (pending.id != requestId) return false
        val question = pending.questions.singleOrNull() ?: return false
        val option = question.options.getOrNull(optionIndex) ?: return false
        return manager.resolve(
            requestId,
            UserQuestionAnswer(
                answers = listOf(
                    SingleAnswer(question = question.question, selected = listOf(option.label))
                )
            )
        )
    }

    private fun answerIntent(context: Context, requestId: String, optionIndex: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "question|$requestId|$optionIndex".hashCode(),
            Intent(context, AgentPermissionActionReceiver::class.java)
                .setAction(ACTION_ANSWER)
                .setData(
                    Uri.Builder().scheme(SCHEME).authority(AUTHORITY)
                        .appendPath(requestId)
                        .appendPath(optionIndex.toString())
                        .build()
                )
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_INDEX, optionIndex),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
