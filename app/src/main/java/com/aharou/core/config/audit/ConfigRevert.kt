package com.aharou.core.config.audit

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.core.util.FileLogger
import java.util.UUID

/**
 * 审计撤销：把某条 `applied` 记录改回旧值（对齐 原版 `ConfigBridge.auditRevert` 的用户路径）。
 *
 * 成功 = 写入旧值 + 追加一条撤销审计（actor=user-revert / agent-revert，revertOf=原记录）+
 * 把原记录标记为 [ConfigAuditStatus.REVERTED]（不删行，历史保留）。
 */
object ConfigRevert {

    private const val TAG = "ConfigRevert"

    /**
     * @return null = 成功；非空 = 用户可读的失败原因。
     */
    fun revert(entryId: String, actorRaw: String, sessionId: String?): String? {
        val log = ConfigAuditLog.get()
        val entry = log.get(entryId) ?: return "找不到该记录"
        if (entry.status != ConfigAuditStatus.APPLIED) {
            return "该记录不可撤销（当前状态：${entry.status.raw}）"
        }
        val registry = ConfigRegistry.get()
        val field = registry.resolveField(entry.key) ?: return "字段不存在：${entry.key}"
        if (!field.revertable) return "该字段不可撤销"
        if (field.access != ConfigAccess.READWRITE) return "该字段不可写"
        val target = ConfigValue.decode(entry.oldValueJSON) ?: return "旧值无法解析"
        return try {
            field.write(target)
            val now = System.currentTimeMillis()
            log.append(
                ConfigAuditEntry(
                    id = UUID.randomUUID().toString(),
                    at = now,
                    actor = ConfigAuditActor.fromRaw(actorRaw),
                    sessionId = sessionId,
                    scope = entry.scope,
                    key = entry.key,
                    oldValueJSON = entry.newValueJSON,
                    newValueJSON = entry.oldValueJSON,
                    confirmedAt = now,
                    status = ConfigAuditStatus.APPLIED,
                    revertOf = entry.id,
                    caption = null,
                )
            )
            log.markReverted(entry.id)
            null
        } catch (t: Throwable) {
            FileLogger.w(TAG, "revert failed: ${t.message}")
            t.message ?: "撤销失败"
        }
    }
}
