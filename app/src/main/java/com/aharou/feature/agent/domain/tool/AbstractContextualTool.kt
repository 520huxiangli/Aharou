package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentContext
import kotlinx.serialization.json.JsonElement

/**
 * 仅靠会话上下文（[com.aharou.feature.agent.domain.model.AgentContext]）才能执行的工具基类。
 *
 * 统一工作流 `StatefulAgentWorkflow` 对所有工具都走 [executeWithContext]，从不直接调用 [execute]。
 * 因此子类只需重写 [executeWithContext]：本基类把它声明为 abstract，漏重写在编译期就报错；
 * 同时把 [execute] 实现为「抛 `UnsupportedOperationException`」，用以在调用期立即暴露误调用
 * （如未来有人新增非 context 的执行分支却忘了走 context），不会把「缺 context」伪装成一个
 * 看似正常的工具失败结果。
 */
abstract class AbstractContextualTool : AgentTool() {

    /**
     * 本工具只支持带上下文的执行。子类必须重写：本基类不提供委托回 [execute] 的默认实现，
     * 否则漏重写时会静默落到 [execute] 的兜底路径，直到运行期才炸。
     */
    abstract override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult

    /**
     * 本工具只支持 [executeWithContext]，此路径不会被提供，调用即视为编程错误。
     * 保留实现以满足 [AgentTool.execute] 的 abstract 签名。
     */
    final override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        throw UnsupportedOperationException(
            "${javaClass.simpleName} 只能通过 executeWithContext 执行，需 AgentContext。"
        )
    }
}
