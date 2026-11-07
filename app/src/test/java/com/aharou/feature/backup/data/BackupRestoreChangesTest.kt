package com.aharou.feature.backup.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class BackupRestoreChangesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun changes(root: File) = BackupRestoreChanges(File(root, "staging").apply { mkdirs() })

    @Test
    fun repeatedWritesRollbackToOriginalAndRemoveOnlyNewFiles() = runBlocking {
        val root = temporary.newFolder("app")
        val target = File(root, "existing").apply { writeText("original") }
        val changes = changes(root)
        changes.write(target, "first".byteInputStream())
        changes.write(target, "second".byteInputStream())
        val added = File(root, "workspace/sub/file")
        changes.write(added, "new".byteInputStream())
        changes.rollback(IOException("failure"))
        assertEquals("original", target.readText())
        assertFalse(File(root, "workspace").exists())
        assertFalse(changes.rollbackFailed)
    }

    @Test
    fun externalEditIsNotOverwrittenAndRecoveryMappingRemains() = runBlocking {
        val root = temporary.newFolder("app")
        val target = File(root, "existing").apply { writeText("original") }
        val changes = changes(root)
        changes.write(target, "restored".byteInputStream())
        target.writeText("external edit")
        val error = IOException("failure")
        changes.rollback(error)
        assertTrue(changes.rollbackFailed)
        assertEquals("external edit", target.readText())
        assertTrue(File(changes.directory, "rollback.jsonl").readText().contains(target.path))
        assertTrue(changes.directory.listFiles()!!.any { it.name.startsWith("old-") && it.readText() == "original" })
        assertTrue(error.suppressed.isNotEmpty())
    }

    @Test
    fun cancellationDuringCopyDoesNotPublishAndRemovesCreatedParents() = runBlocking {
        val root = temporary.newFolder("app")
        val changes = changes(root)
        val target = File(root, "workspace/sub/file")
        val input = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancel copy")
        }
        val error = try {
            changes.write(target, input)
            error("Expected cancellation")
        } catch (failure: CancellationException) { failure }
        changes.rollback(error)
        assertFalse(target.exists())
        assertFalse(File(root, "workspace").exists())
    }

    @Test
    fun symlinkCannotRedirectGlobalOrRemoteProjectMemory() = runBlocking {
        val root = temporary.newFolder("app")
        val outside = temporary.newFolder("outside")
        val original = File(outside, "SOUL.md").apply { writeText("outside") }
        val linked = File(root, "memory")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val changes = changes(root)
        var rejected = false
        try { changes.write(File(linked, "SOUL.md"), "new".byteInputStream()) }
        catch (_: IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals("outside", original.readText())
    }

    @Test
    fun applicationRootAliasAndCanonicalRootAreBothAccepted() = runBlocking {
        val actual = temporary.newFolder("actual")
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val changes = changes(alias)
        changes.write(File(alias, "one"), "one".byteInputStream())
        changes.write(File(actual, "two"), "two".byteInputStream())
        assertEquals("one", File(actual, "one").readText())
        assertEquals("two", File(actual, "two").readText())
        changes.rollback(IOException("failure"))
        assertFalse(File(actual, "one").exists())
        assertFalse(File(actual, "two").exists())
    }

    @Test
    fun replacingExecutablePreservesPermissions() = runBlocking {
        val root = temporary.newFolder("app")
        val target = File(root, "script").apply { writeText("old") }
        val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        Files.setPosixFilePermissions(target.toPath(), permissions)
        val changes = changes(root)
        changes.write(target, "new".byteInputStream())
        assertEquals(permissions, Files.getPosixFilePermissions(target.toPath()))
        changes.rollback(IOException("failure"))
        assertEquals(permissions, Files.getPosixFilePermissions(target.toPath()))
        assertEquals("old", target.readText())
    }
}
