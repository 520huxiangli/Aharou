package com.aharou.feature.workspace.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [SyncIndexStore] 持久化与 [SyncDecision] 判定逻辑（纯本地，无网络）。 */
class SyncIndexStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store get() = SyncIndexStore(File(tmp.root, "sync-index"))

    private fun identity(
        connectionId: String = "c1",
        host: String = "example.com",
        remotePath: String = "/remote/ws"
    ) = SyncIndexIdentity(connectionId, host, remotePath)

    // ---------- 持久化 ----------

    @Test
    fun saveThenLoad_roundTrip() {
        store.save("m1", identity(), mapOf("a/b.txt" to FileFingerprint(10, 1000, "abc")))
        val loaded = store.load("m1", identity())
        assertEquals(1, loaded.size)
        assertEquals(FileFingerprint(10, 1000, "abc"), loaded["a/b.txt"])
    }

    @Test
    fun load_missingFile_returnsEmpty() {
        assertTrue(store.load("nope", identity()).isEmpty())
    }

    @Test
    fun load_corruptFile_returnsEmpty() {
        val dir = File(tmp.root, "sync-index").apply { mkdirs() }
        File(dir, "m1.json").writeText("{ this is not json")
        assertTrue(store.load("m1", identity()).isEmpty())
    }

    @Test
    fun clear_removesIndex() {
        store.save("m1", identity(), mapOf("x" to FileFingerprint(1, 2, null)))
        assertFalse(store.load("m1", identity()).isEmpty())
        store.clear("m1")
        assertTrue(store.load("m1", identity()).isEmpty())
    }

    @Test
    fun save_isAtomic_oldContentNotLeftHalfWritten() {
        store.save("m1", identity(), mapOf("x" to FileFingerprint(1, 2, null)))
        store.save("m1", identity(), mapOf("x" to FileFingerprint(3, 4, "z"), "y" to FileFingerprint(5, 6, null)))
        assertEquals(2, store.load("m1", identity()).size)
    }

    // ---------- 远端身份（不匹配即全量上传） ----------

    @Test
    fun load_remotePathChanged_treatedAsEmpty_forcingFullUpload() {
        store.save("m1", identity(remotePath = "/remote/old"), mapOf("a.txt" to FileFingerprint(1, 2, null)))
        assertTrue(store.load("m1", identity(remotePath = "/remote/new")).isEmpty())
    }

    @Test
    fun load_connectionChanged_treatedAsEmpty_forcingFullUpload() {
        store.save("m1", identity(connectionId = "c1", host = "a.com"), mapOf("a.txt" to FileFingerprint(1, 2, null)))
        assertTrue(store.load("m1", identity(connectionId = "c2", host = "b.com")).isEmpty())
    }

    // ---------- 判定逻辑（保守：不确定即上传） ----------

    @Test
    fun decide_noRecord_uploads() {
        assertEquals(SyncDecision.Action.UPLOAD, SyncDecision.decide(null, 10, 100) { "x" })
    }

    @Test
    fun decide_sizeChanged_uploads_withoutHashing() {
        var hashed = false
        val action = SyncDecision.decide(FileFingerprint(10, 100, "old"), 11, 100) {
            hashed = true; "new"
        }
        assertEquals(SyncDecision.Action.UPLOAD, action)
        assertFalse("size 不同不应计算哈希", hashed)
    }

    @Test
    fun decide_sameSizeSameMtime_skips_withoutHashing() {
        var hashed = false
        val action = SyncDecision.decide(FileFingerprint(10, 100, "old"), 10, 100) {
            hashed = true; "old"
        }
        assertEquals(SyncDecision.Action.SKIP_UNCHANGED, action)
        assertFalse("mtime 一致不应计算哈希", hashed)
    }

    @Test
    fun decide_sameSizeDiffMtime_sameHash_skips() {
        val action = SyncDecision.decide(FileFingerprint(10, 100, "same"), 10, 200) { "same" }
        assertEquals(SyncDecision.Action.SKIP_UNCHANGED, action)
    }

    @Test
    fun decide_sameSizeDiffMtime_diffHash_uploads() {
        val action = SyncDecision.decide(FileFingerprint(10, 100, "old"), 10, 200) { "new" }
        assertEquals(SyncDecision.Action.UPLOAD, action)
    }

    @Test
    fun decide_storedShaNull_conservativeUpload() {
        val action = SyncDecision.decide(FileFingerprint(10, 100, null), 10, 200) { "new" }
        assertEquals(SyncDecision.Action.UPLOAD, action)
    }

    @Test
    fun decide_hashComputeFails_conservativeUpload() {
        val action = SyncDecision.decide(FileFingerprint(10, 100, "old"), 10, 200) { null }
        assertEquals(SyncDecision.Action.UPLOAD, action)
    }

    @Test
    fun fingerprint_shaNull_isAllowed() {
        assertNull(FileFingerprint(1, 2, null).sha256)
    }
}
