package com.aharou.feature.vault

/**
 * 进程级脱敏出口：日志与审计这类非 DI 组件（[com.aharou.core.util.FileLogger]、
 * [com.aharou.feature.agent.domain.permission.DecisionAuditLog]）注入不到 [VaultRepository]，
 * 故由后者在构造时把它的 `redact` 注册到这里，让落盘文本再过一层凭据脱敏。
 *
 * 线程安全：`delegate` 用 `@Volatile` 发布（只在 VaultRepository 构造时写一次，之后只读）；
 * `inRedact` 用 [ThreadLocal] 防自递归——`redact` 内部若经 FileLogger 落盘，会再次回到这里，
 * 此时直接原样返回，避免解密失败时「日志 → 脱敏 → 再日志」的无限递归。
 * delegate 尚未注册（VaultRepository 未构造）时不做任何改动，调用按原文放行。
 */
object VaultRedactor {

    @Volatile
    var delegate: ((String) -> String)? = null

    private val inRedact = ThreadLocal.withInitial { false }

    /** 对文本做凭据库脱敏；未注册或当前正处在一次脱敏中时原样返回。 */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val redactFn = delegate ?: return text
        if (inRedact.get()) return text
        inRedact.set(true)
        return try {
            redactFn(text)
        } finally {
            inRedact.set(false)
        }
    }
}
