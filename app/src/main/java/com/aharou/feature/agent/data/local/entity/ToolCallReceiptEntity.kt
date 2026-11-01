package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 回执类别：区分「工具真的跑了一次」「用户在审批里拒了」「跑的是受门阀约束的编译」。
 * 三类混在同一张表里，会话活动面板才能按时间顺序还原出完整的一条线。
 */
enum class ReceiptCategory {
    /** 工具正常执行并返回。 */
    TOOL,

    /** 审批被拒，工具根本没执行——不记这一条，面板上就看不到「你拒了哪一步」。 */
    DENIED,

    /** 编译任务：多带一份授权明细（谁批的、原话、门阀当时的任务名）。 */
    BUILD
}

/** 单条回执的结果。DENIED 不进这个枚举——拒绝本身就是类别，不需要再分成败。 */
enum class ReceiptStatus {
    SUCCESS,
    ERROR,
    PARTIAL
}

/**
 * 工具调用回执：一次工具执行 = 一条记录。
 *
 * 与聊天记录的区别在于它记的是**机器的事实**（工具真的执行了、返回了什么、耗时多久），
 * 而不是模型的叙述；与 [com.aharou.feature.agent.domain.permission.DecisionAuditLog]
 * 的区别在于它落库、可按会话查询、并且有界面可翻。
 *
 * 参数与结果只存脱敏后的摘要，完整大输出靠 [outputPath] 指向 tool-output 存档。
 */
@Entity(
    tableName = "tool_call_receipts",
    indices = [
        Index(value = ["sessionId", "createdAt"]),
        Index(value = ["createdAt"])
    ]
)
data class ToolCallReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** 取值见 [ReceiptCategory]。 */
    val category: String = ReceiptCategory.TOOL.name,
    val toolName: String,
    /** 参数摘要（已脱敏、已截断）。 */
    val argsPreview: String = "",
    /** 取值见 [ReceiptStatus]；DENIED 类别下固定为空字符串。 */
    val status: String = "",
    val errorCode: String? = null,
    /** 结果摘要（已脱敏、已截断）。 */
    val resultPreview: String = "",
    /** 完整输出在 tool-output 存档里的路径；输出未超限时为 null。 */
    val outputPath: String? = null,
    val durationMs: Long = 0,
    /** 编译回执的授权明细（JSON：authorized_by / quote / at）；非编译为 null。 */
    val buildAuthJson: String? = null,
    val createdAt: Long
)
