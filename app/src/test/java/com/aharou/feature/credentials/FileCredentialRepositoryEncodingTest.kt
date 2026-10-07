package com.aharou.feature.credentials

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import com.aharou.feature.credentials.data.repository.FileCredentialRepository
import com.aharou.feature.credentials.domain.model.GitCredential
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * git 凭据文件「编码 / 旧版明文」判定与迁移的回归测试。
 *
 * 回归点：`android.util.Base64` 的 DEFAULT 解码对 `:`、`/`、`@` 等非法字符按 SKIP 忽略、不抛异常，
 * 旧实现据此把明文也当成编码串「解码」成乱码，导致凭据列表读空、并被 migrateToEncoded 以空列表回写覆盖。
 *
 * `android.util.Base64` 是 native 实现，纯 JVM（无 Robolectric）跑不了，故以 java.util.Base64 等价替换：
 * NO_WRAP 编码 = getEncoder()，DEFAULT 的「宽容」= getMimeDecoder()（同样忽略非字母表字符）。
 */
class FileCredentialRepositoryEncodingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir: File get() = tmp.root
    // 必须延迟到 @Before 之后再取：TemporaryFolder 规则要到那时才把目录建出来。
    private val context: Context by lazy { FakeContext(filesDir) }

    private val plain =
        "https://alice:ghp_abcdef@github.com\nhttps://bob:tok-123@gitlab.com\n"
    private val expected = listOf(
        GitCredential(id = "github.com", host = "github.com", username = "alice", token = "ghp_abcdef"),
        GitCredential(id = "gitlab.com", host = "gitlab.com", username = "bob", token = "tok-123")
    )

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().encodeToString(arg<ByteArray>(0))
        }
        every { Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getMimeDecoder().decode(arg<String>(0))
        }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun credFile(): File = File(File(filesDir, "aharou"), "git-credentials")

    private fun writeRaw(text: String) {
        credFile().apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun encode(text: String): String =
        StringBuilder(java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
            .reverse().toString()

    private fun decode(text: String): String =
        String(java.util.Base64.getMimeDecoder().decode(StringBuilder(text.trim()).reverse().toString()), Charsets.UTF_8)

    @Test
    fun encoded_file_decodes_to_credentials() = runTest {
        writeRaw(encode(plain))
        assertEquals(expected, FileCredentialRepository(context).getAll().first())
    }

    @Test
    fun plaintext_file_is_not_misjudged_as_encoded() = runTest {
        writeRaw(plain)
        assertEquals(expected, FileCredentialRepository(context).getAll().first())
    }

    @Test
    fun migrateToEncoded_keeps_credentials_from_plaintext_file() = runTest {
        writeRaw(plain)
        FileCredentialRepository(context).migrateToEncoded()

        val onDisk = credFile().readText()
        assertNotEquals("旧版明文文件应已被改写为编码格式", plain, onDisk)
        assertEquals("改写后的内容解码必须与原明文一致（凭据不能丢）", plain, decode(onDisk))
        // 新实例回读：文件已是编码格式，凭据完好
        assertEquals(expected, FileCredentialRepository(context).getAll().first())
    }

    @Test
    fun migrateToEncoded_leaves_already_encoded_file_untouched() = runTest {
        val encoded = encode(plain)
        writeRaw(encoded)
        FileCredentialRepository(context).migrateToEncoded()
        assertEquals("已编码的文件不应被二次改写", encoded, credFile().readText())
    }

    private class FakeContext(private val files: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
    }
}
