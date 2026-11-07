package com.aharou.feature.workspace.domain

import java.io.IOException
import java.util.UUID

internal interface SftpReplacementOperations {
    val supportsAtomicOverwrite: Boolean
    fun exists(path: String): Boolean
    fun rename(from: String, to: String, atomicOverwrite: Boolean = false)
    fun remove(path: String)
    fun isDefiniteFailure(error: Exception): Boolean
    fun isOverwriteUnsupported(error: Exception): Boolean
    fun cleanupFailed(error: Exception)
}

internal class SftpFileReplacement(private val operations: SftpReplacementOperations) {
    fun replace(temporary: String, target: String, overwrite: Boolean) {
        val targetExists = operations.exists(target)
        if (targetExists && !overwrite) throw java.nio.file.FileAlreadyExistsException(target)
        if (!targetExists) {
            commit(temporary, target)
            return
        }
        if (operations.supportsAtomicOverwrite) {
            try {
                operations.rename(temporary, target, atomicOverwrite = true)
                return
            } catch (error: Exception) {
                if (!operations.isOverwriteUnsupported(error)) {
                    throw recoveryError(temporary, target, null, error)
                }
            }
        }
        val backup = "$target.aicode-backup-${UUID.randomUUID()}"
        try {
            operations.rename(target, backup)
        } catch (error: Exception) {
            throw recoveryError(temporary, target, backup, error)
        }
        try {
            operations.rename(temporary, target)
        } catch (error: Exception) {
            val failure = recoveryError(temporary, target, backup, error)
            // 只有明确失败且目标仍空缺时才回滚；响应丢失不能靠猜测覆盖可能已提交的新文件。
            if (operations.isDefiniteFailure(error)) {
                try {
                    if (!operations.exists(target)) operations.rename(backup, target)
                } catch (rollbackError: Exception) {
                    failure.addSuppressed(rollbackError)
                }
            }
            throw failure
        }
        try {
            operations.remove(backup)
        } catch (error: Exception) {
            operations.cleanupFailed(error)
        }
    }

    private fun commit(temporary: String, target: String) {
        try {
            operations.rename(temporary, target)
        } catch (error: Exception) {
            throw recoveryError(temporary, target, null, error)
        }
    }

    private fun recoveryError(temporary: String, target: String, backup: String?, cause: Exception) =
        IOException(
            "SFTP replacement failed; target=$target; candidate=$temporary" +
                (backup?.let { "; backup=$it" } ?: ""),
            cause
        )
}
