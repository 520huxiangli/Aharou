package com.aharou.core.config.confirm

import com.aharou.core.config.ConfigRisk
import java.util.UUID

/**
 * One row in a pending change request. A single CLI call may carry
 * multiple changes (`set-batch`); they're presented in a single dialog
 * and confirmed/rejected as a unit (with per-row reject toggles).
 */
data class PendingConfigChangeItem(
    val id: String = UUID.randomUUID().toString(),
    /** Label shown in the row. Comes from the field's [displayName]. */
    val displayName: String,
    /** Dot path; shown in fine print. */
    val path: String,
    /** Old value (humanized). Empty for add-collection rows. */
    val oldDisplay: String,
    /** New value (humanized). Empty for remove rows. */
    val newDisplay: String,
    /** Verb shown in the row (set / append / remove / add / hide / revert). */
    val verb: String,
    val risk: ConfigRisk,
    /** Per-row reject toggle, defaults true (allow). */
    val isApproved: Boolean = true,
)

/** A queued CLI request awaiting user confirmation. */
data class PendingConfigChange(
    val id: String = UUID.randomUUID().toString(),
    val items: List<PendingConfigChangeItem>,
    val caption: String?,
    /**
     * 为真表示这次请求必须由用户点击决定，任何自动批准策略都不生效。
     * 读取明文（`config reveal` 敏感字段）走这条路：它不改任何值，但会把密钥
     * 送进模型上下文，属于「读」的敏感动作，AUTO 模式也不该替用户点头。
     */
    val alwaysConfirm: Boolean = false,
)

/** Final disposition the gate reports back to the bridge. */
sealed class ConfirmOutcome {
    /** User approved with the given (possibly per-row toggled) item set. */
    data class Approved(
        val items: List<PendingConfigChangeItem>,
        /**
         * 未经用户点击，由 AUTO 模式策略在入队前自动批准（弹窗与 120 秒计时都没发生）。
         * 审计行据此打 [ConfigConfirmationGate.AUTO_APPROVE_CAPTION] 来源标记。
         */
        val auto: Boolean = false,
    ) : ConfirmOutcome()
    /** User cancelled. */
    object Rejected : ConfirmOutcome()
    /** No user response within the gate's timeout window. */
    object TimedOut : ConfirmOutcome()
}
