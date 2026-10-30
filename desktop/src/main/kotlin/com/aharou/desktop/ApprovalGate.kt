package com.aharou.desktop

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ApprovalRequest(
    val toolName: String,
    val args: String,
    val answer: CompletableDeferred<Boolean>
)

/**
 * 把后台协程里的工具审批请求桥到 UI：协程挂起等用户点，界面读 [current] 渲染弹窗。
 *
 * 手机端这一步由 `ToolPermissionPolicyEngine` + 通知/弹层承担；桌面端第一版只做「要不要执行」
 * 这一档，没有「本会话始终允许」的记忆。
 */
class ApprovalGate {

    val current = mutableStateOf<ApprovalRequest?>(null)

    suspend fun ask(toolName: String, args: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            current.value = ApprovalRequest(toolName, args, answer)
        }
        return try {
            answer.await()
        } finally {
            withContext(Dispatchers.Main) { current.value = null }
        }
    }

    fun reply(allowed: Boolean) {
        current.value?.answer?.complete(allowed)
    }
}
