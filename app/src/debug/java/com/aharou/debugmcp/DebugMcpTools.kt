package com.aharou.debugmcp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModelStoreOwner
import com.aharou.AIEditorApp
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 三个语义级 MCP 工具的实现（debug 变体专属）。
 *
 * 单例资源（WorkspaceRepository / ToolPermissionManager）经 [DebugMcpEntryPoint] 取；
 * 全局 object（[AgentRuntimeStatus] / [FileLogger]）与 [AIEditorApp] 静态字段直接用；
 * [AIAgentViewModel] 非单例（Activity 级），从 [DebugMcpActivityTracker] 记录的活动栈里按
 * ViewModelStore 捞取——不新增公共代码，也不改 Manifest 之外的共享文件。
 */
internal object DebugMcpTools {

    private const val TAG = "DebugMcp"

    /** 与 ConfigTool.readLogs 对齐的返回行数上限（防止 5MB 日志冲爆上下文）。 */
    private const val DEFAULT_LOG_LINES = 200
    private const val MAX_LOG_LINES = 2_000

    suspend fun uiState(context: Context): String {
        val entry = entryPoint(context)
        val workspace = entry.workspaceRepository().current.value
        val pending = entry.toolPermissionManager().pendingRequest.value
        val runtime = AgentRuntimeStatus.state.value
        // ViewModel / ViewModelStore 属主线程亲和，统一回主线程读。
        return withContext(Dispatchers.Main) {
            val vm = DebugMcpActivityTracker.currentAgentViewModel()
            buildJsonObject {
                put("workspacePath", workspace?.path.orEmpty())
                put("workspaceName", workspace?.name.orEmpty())
                put("workspaceMode", AIEditorApp.currentWorkspaceMode.orEmpty())
                put("sessionId", vm?.currentSessionId?.value.orEmpty())
                put("sessionTitle", vm?.currentSessionState?.value?.title.orEmpty())
                put("sessionWorkspace", vm?.currentSessionWorkspace?.value.orEmpty())
                put("route", AIEditorApp.currentRoute.orEmpty())
                put("ghostMode", vm?.currentSessionGhostMode?.value ?: false)
                put("agentRunning", runtime.active)
                put("agentBusy", runtime.busy)
                put("runningTool", runtime.toolName)
                if (pending != null) {
                    putJsonObject("pendingToolPermission") {
                        put("toolName", pending.toolName)
                        put("title", pending.title)
                        put("sessionId", pending.sessionId)
                    }
                }
            }.toString()
        }
    }

    suspend fun chatSend(context: Context, args: JsonObject?): String {
        val text = (args?.get("text") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(text.isNotEmpty()) { "缺少 text 参数" }
        return withContext(Dispatchers.Main) {
            val vm = DebugMcpActivityTracker.currentAgentViewModel()
                ?: error("聊天界面未就绪（AIAgentViewModel 尚未创建，请先把测试包切到前台）")
            val projectRoot = vm.currentSessionWorkspace.value
            check(projectRoot.isNotBlank()) { "工作区未就绪，无法发送（请先打开一个工作区）" }
            val expanded = vm.expandPastes(text).trim()
            require(expanded.isNotEmpty()) { "文本为空（粘贴标记展开后为空）" }
            // 1) 注入当前会话输入草稿——等价用户在输入框里打字（草稿是唯一事实源）。
            vm.updateInputDraft(expanded)
            // 2) 走真实发送链路：AI 空闲直接执行，忙则入队；斜杠命令由 ViewModel 内部正常分流。
            vm.enqueueAgentRequest(
                request = expanded,
                modelRequest = expanded,
                projectRoot = projectRoot,
            )
            // 3) 点发送后输入框清空——保持与 UI 一致。
            vm.clearInputDraft()
            FileLogger.i(TAG, "chat_send: ${expanded.length} 字 → 会话 ${vm.currentSessionId.value}")
            buildJsonObject {
                put("sent", true)
                put("sessionId", vm.currentSessionId.value.orEmpty())
                put("projectRoot", projectRoot)
                put("text", expanded)
            }.toString()
        }
    }

    suspend fun logs(args: JsonObject?): String {
        val date = (args?.get("date") as? JsonPrimitive)?.contentOrNull
        val query = (args?.get("query") as? JsonPrimitive)?.contentOrNull
        val limit = (args?.get("limit") as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
            ?: DEFAULT_LOG_LINES
        val take = limit.coerceIn(1, MAX_LOG_LINES)
        val read = withContext(Dispatchers.IO) { FileLogger.readLogTail(date, query, take) }
            ?: return "没有这份日志（日期用 yyyy-MM-dd，只保留最近 7 天）"
        val header = buildString {
            append(read.fileName).append("：共 ").append(read.totalLines).append(" 行")
            if (read.matchedLines != read.totalLines) {
                append("，命中 ").append(read.matchedLines).append(" 行")
            }
            append("，返回尾部 ").append(read.returnedLines).append(" 行")
            if (read.returnedLines < read.matchedLines) {
                append("（更早的没返回：加 query 缩小范围，或调大 limit）")
            }
        }
        return "$header\n\n${read.text}"
    }

    private fun entryPoint(context: Context): DebugMcpEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, DebugMcpEntryPoint::class.java)
}

/**
 * 记录进程内 Activity 栈，用于取 Activity 级 [AIAgentViewModel]。
 *
 * ViewModel 不是 Hilt 单例、也不在任何全局登记点，只能从 Activity 的 ViewModelStore 里捞；
 * 用 ActivityLifecycleCallbacks（ContentProvider 的 onCreate 在主线程注册）零侵入地拿到 Activity。
 */
internal object DebugMcpActivityTracker : Application.ActivityLifecycleCallbacks {

    private val activities = CopyOnWriteArrayList<Activity>()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    /** 从最近创建的活动往前找，返回第一个 ViewModelStore 里含 [AIAgentViewModel] 的实例。 */
    fun currentAgentViewModel(): AIAgentViewModel? {
        for (i in activities.indices.reversed()) {
            val vm = agentViewModelOf(activities[i])
            if (vm != null) return vm
        }
        return null
    }

    private fun agentViewModelOf(activity: Activity): AIAgentViewModel? {
        val owner = activity as? ViewModelStoreOwner ?: return null
        val store = runCatching { owner.viewModelStore }.getOrNull() ?: return null
        val keys = runCatching { store.keys() }.getOrNull() ?: return null
        for (key in keys) {
            val vm = runCatching { store.get(key) }.getOrNull()
            if (vm is AIAgentViewModel) return vm
        }
        return null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activities.add(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/** debug 源码集内的 Hilt 入口：只暴露本 server 需要的单例，不进公共代码。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugMcpEntryPoint {
    fun workspaceRepository(): WorkspaceRepository
    fun toolPermissionManager(): ToolPermissionManager
}
