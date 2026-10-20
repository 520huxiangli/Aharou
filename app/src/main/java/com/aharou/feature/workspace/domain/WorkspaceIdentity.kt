package com.aharou.feature.workspace.domain

import java.math.BigInteger
import java.security.MessageDigest

/**
 * 工作区的稳定标识。
 *
 * 跨工作区信箱（`~/shared/inbox/<标识>.md`）与项目级配置目录 [ProjectAharouRoot] 共用这一套
 * 「身份串 → 短哈希」算法：同一个工作区在任何地方都得到同一个标识，不再各写一份 MD5 逻辑。
 */
object WorkspaceIdentity {
    /** 短哈希长度：8 位十六进制，够区分同机的少量工作区，又短到能当文件名。 */
    private const val HASH_LENGTH = 8

    /** 身份串（工作区绝对路径，远程时为「IP:端口:路径」）的 MD5 十六进制前 8 位。 */
    fun shortHash(identity: String): String =
        BigInteger(1, MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8)))
            .toString(16)
            .padStart(32, '0')
            .take(HASH_LENGTH)

    /**
     * 工作区标识：`<目录名>-<短哈希>`，如 `workspace-1a2b3c4d`。
     *
     * 带目录名是为了信箱文件名可读（`ls ~/shared/inbox/` 一眼能认出是谁），哈希保证同名目录不撞。
     * 路径为空表示还没选工作区，返回 null。
     */
    fun idFor(workspacePath: String): String? {
        val path = workspacePath.trim().trimEnd('/')
        if (path.isEmpty()) return null
        val name = path.substringAfterLast('/').ifBlank { "workspace" }
        return "$name-${shortHash(path)}"
    }
}
