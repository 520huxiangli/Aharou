package com.aharou.feature.workspace.domain

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SftpFileReplacementTest {
    private class Rejected : IOException()
    private class Unsupported : IOException()

    private class Files(override val supportsAtomicOverwrite: Boolean = false) : SftpReplacementOperations {
        val contents = linkedMapOf("target" to "old", "candidate" to "new")
        val removed = mutableListOf<String>()
        val renames = mutableListOf<Triple<String, String, Boolean>>()
        var beforeRename: (String, String, Boolean) -> Unit = { _, _, _ -> }
        var afterRename: (String, String, Boolean) -> Unit = { _, _, _ -> }
        var removeFailure = false
        var cleanupError: Exception? = null
        override fun exists(path: String): Boolean = contents.containsKey(path)
        override fun rename(from: String, to: String, atomicOverwrite: Boolean) {
            renames += Triple(from, to, atomicOverwrite)
            beforeRename(from, to, atomicOverwrite)
            if (!atomicOverwrite && exists(to)) throw Rejected()
            contents[to] = contents.remove(from) ?: throw Rejected()
            afterRename(from, to, atomicOverwrite)
        }
        override fun remove(path: String) {
            if (removeFailure) throw IOException("cleanup")
            removed += path
            contents.remove(path)
        }
        override fun isDefiniteFailure(error: Exception): Boolean = error is Rejected || error is Unsupported
        override fun isOverwriteUnsupported(error: Exception): Boolean = error is Unsupported
        override fun cleanupFailed(error: Exception) { cleanupError = error }
    }

    private fun failure(block: () -> Unit): IOException {
        try { block() } catch (error: IOException) { return error }
        fail("Expected IOException")
        throw AssertionError()
    }

    @Test fun atomicOverwriteDoesNotMoveOldFileToBackup() {
        val files = Files(true)
        SftpFileReplacement(files).replace("candidate", "target", true)
        assertEquals(mapOf("target" to "new"), files.contents)
        assertEquals(listOf(Triple("candidate", "target", true)), files.renames)
        assertTrue(files.removed.isEmpty())
    }

    @Test fun unsupportedAtomicOverwriteUsesBackup() {
        val files = Files(true)
        files.beforeRename = { _, _, atomic -> if (atomic) throw Unsupported() }
        SftpFileReplacement(files).replace("candidate", "target", true)
        assertEquals(mapOf("target" to "new"), files.contents)
        assertEquals(1, files.removed.size)
        assertTrue(files.removed.single().startsWith("target.aicode-backup-"))
    }

    @Test fun atomicTransportOrPermissionFailureDoesNotStartFallback() {
        for (error in listOf(IOException("transport"), Rejected())) {
            val files = Files(true)
            files.beforeRename = { _, _, _ -> throw error }
            failure { SftpFileReplacement(files).replace("candidate", "target", true) }
            assertEquals(mapOf("target" to "old", "candidate" to "new"), files.contents)
            assertEquals(1, files.renames.size)
            assertTrue(files.removed.isEmpty())
        }
    }

    @Test fun backupRenameFailureNeverDeletesOldFile() {
        val files = Files()
        files.beforeRename = { from, _, _ -> if (from == "target") throw Rejected() }
        failure { SftpFileReplacement(files).replace("candidate", "target", true) }
        assertEquals("old", files.contents["target"])
        assertEquals("new", files.contents["candidate"])
        assertTrue(files.removed.isEmpty())
    }

    @Test fun rejectedCommitRestoresOldTargetAndRetainsCandidate() {
        val files = Files()
        files.beforeRename = { from, _, _ -> if (from == "candidate") throw Rejected() }
        failure { SftpFileReplacement(files).replace("candidate", "target", true) }
        assertEquals(mapOf("candidate" to "new", "target" to "old"), files.contents)
        assertTrue(files.removed.isEmpty())
    }

    @Test fun failedRollbackRetainsBackupAndCandidate() {
        val files = Files()
        files.beforeRename = { from, _, _ -> if (from != "target") throw Rejected() }
        val error = failure { SftpFileReplacement(files).replace("candidate", "target", true) }
        assertEquals(1, error.suppressed.size)
        assertEquals("old", files.contents.entries.single { it.key.startsWith("target.aicode-backup-") }.value)
        assertEquals("new", files.contents["candidate"])
        assertFalse(files.exists("target"))
        assertTrue(error.message.orEmpty().contains("backup="))
        assertTrue(files.removed.isEmpty())
    }

    @Test fun lostCommitResponseKeepsCommittedTargetAndBackup() {
        val files = Files()
        files.afterRename = { from, _, _ -> if (from == "candidate") throw IOException("response lost") }
        failure { SftpFileReplacement(files).replace("candidate", "target", true) }
        assertEquals("new", files.contents["target"])
        assertEquals("old", files.contents.entries.single { it.key.startsWith("target.aicode-backup-") }.value)
        assertEquals(2, files.renames.size)
        assertTrue(files.removed.isEmpty())
    }

    @Test fun transportFailureBeforeCommitKeepsBothRecoverableCopies() {
        val files = Files()
        files.beforeRename = { from, _, _ -> if (from == "candidate") throw IOException("connection lost") }
        failure { SftpFileReplacement(files).replace("candidate", "target", true) }
        assertEquals("new", files.contents["candidate"])
        assertTrue(files.contents.containsValue("old"))
        assertEquals(2, files.renames.size)
        assertTrue(files.removed.isEmpty())
    }

    @Test fun cleanupFailureDoesNotUndoSuccessfulCommit() {
        val files = Files().apply { removeFailure = true }
        SftpFileReplacement(files).replace("candidate", "target", true)
        assertEquals("new", files.contents["target"])
        assertTrue(files.contents.containsValue("old"))
        assertNotNull(files.cleanupError)
    }

    @Test fun noOverwriteRejectsBeforeMovingAnyFile() {
        val files = Files()
        val error = failure { SftpFileReplacement(files).replace("candidate", "target", false) }
        assertTrue(error is FileAlreadyExistsException)
        assertTrue(files.renames.isEmpty())
        assertEquals("old", files.contents["target"])
    }

    @Test fun newTargetUsesPlainRename() {
        val files = Files().apply { contents.remove("target") }
        SftpFileReplacement(files).replace("candidate", "target", false)
        assertEquals(mapOf("target" to "new"), files.contents)
        assertEquals(listOf(Triple("candidate", "target", false)), files.renames)
    }
}
