package com.aharou.feature.backup.data

import com.aharou.feature.backup.data.local.dao.BackupTargetDao
import com.aharou.feature.backup.data.local.entity.BackupTargetEntity
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.BackupTargetType
import com.aharou.feature.backup.domain.remote.RemoteSecretCipher
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackupTargetRepository 的纯 JVM 测试：用假 DAO + 假加解密口子（同样带 `AICODEENC1:` 前缀语义，
 * 但不依赖 Android Keystore）验证「敏感字段落库加密、回读解密一致」。
 */
class BackupTargetRepositoryTest {

    private class FakeDao : BackupTargetDao {
        val rows = LinkedHashMap<String, BackupTargetEntity>()
        private val changes = MutableStateFlow<List<BackupTargetEntity>>(emptyList())

        override fun getAll(): Flow<List<BackupTargetEntity>> = changes
        override suspend fun getById(id: String): BackupTargetEntity? = rows[id]
        override suspend fun upsert(target: BackupTargetEntity) {
            rows[target.id] = target
            changes.value = rows.values.toList()
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
            changes.value = rows.values.toList()
        }
    }

    /** 与 KeystoreCipher 同前缀、空串直通的假口子（不加背景反转即可逆）。 */
    private object FakeCipher : RemoteSecretCipher {
        private const val PREFIX = "AICODEENC1:"

        override fun encrypt(plain: String): String =
            if (plain.isEmpty()) plain
            else PREFIX + Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

        override fun decrypt(value: String): String =
            if (!value.startsWith(PREFIX)) value
            else String(Base64.getDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8)
    }

    private fun repository(dao: FakeDao) = BackupTargetRepository(dao, OkHttpClient(), FakeCipher)

    @Test
    fun save_storesEncryptedSecrets_andReadsBackPlain() = runTest {
        val dao = FakeDao()
        val repo = repository(dao)
        val target = BackupTarget(
            name = "my-dav",
            type = BackupTargetType.WEBDAV,
            endpoint = "https://dav.example.com/remote.php/dav/files/user/",
            username = "user",
            password = "p@ssw0rd",
            sessionToken = "session-token"
        )

        repo.save(target)

        val stored = dao.getById(target.id)!!
        assertTrue(stored.password.startsWith("AICODEENC1:"))
        assertTrue(stored.secretKey.isEmpty())
        assertTrue(stored.sessionToken.startsWith("AICODEENC1:"))
        assertNotEquals("p@ssw0rd", stored.password)
        assertFalse(stored.sessionToken == "session-token")

        val loaded = repo.getTarget(target.id)!!
        assertEquals("p@ssw0rd", loaded.password)
        assertEquals("session-token", loaded.sessionToken)
        assertEquals("user", loaded.username)
        assertEquals(BackupTargetType.WEBDAV, loaded.type)
    }

    @Test
    fun save_s3Target_keepsAccessKeyPlain_encryptsSecretKey() = runTest {
        val dao = FakeDao()
        val repo = repository(dao)
        val target = BackupTarget(
            name = "my-s3",
            type = BackupTargetType.S3,
            bucket = "backups",
            accessKey = "AKIA-PUBLIC",
            secretKey = "secret-key",
            region = "",
            pathStyle = true
        )

        repo.save(target)

        val stored = dao.getById(target.id)!!
        assertEquals("S3", stored.type)
        assertEquals("AKIA-PUBLIC", stored.accessKey)
        assertTrue(stored.secretKey.startsWith("AICODEENC1:"))
        // region 留空时落库回退默认 us-east-1
        assertEquals("us-east-1", stored.region)
        assertTrue(stored.pathStyle)

        val loaded = repo.getTarget(target.id)!!
        assertEquals("secret-key", loaded.secretKey)
        assertEquals(BackupTargetType.S3, loaded.type)
        assertTrue(loaded.pathStyle)
    }

    @Test
    fun getTarget_unknownId_returnsNull() = runTest {
        assertNull(repository(FakeDao()).getTarget("missing"))
    }

    @Test
    fun delete_removesRow() = runTest {
        val dao = FakeDao()
        val repo = repository(dao)
        val target = BackupTarget(name = "tmp", password = "x")
        repo.save(target)
        repo.delete(target.id)
        assertNull(dao.getById(target.id))
        assertNull(repo.getTarget(target.id))
    }
}
