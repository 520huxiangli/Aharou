package com.aharou.feature.agent.domain.mcp

import com.aharou.core.watch.FileChangeHub
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 标准 JSON 导入：结构校验（非 JSON / 缺字段 / 空 / 无可识别条目）、非法名跳过、
 * 同名保留 oauth、上游 `auth` 字段被忽略；并用真实 [McpConfigRepository.parse] 验证 stdio/http 解析。
 */
class McpJsonImporterTest {

    private val repo = mockk<McpConfigRepository>(relaxed = true)
    private val manager = mockk<McpManager>(relaxed = true)
    private val importer = McpJsonImporter(repo, manager)

    /** 真实解析器：只验证 parse 的读法，写入路径一律用 mock。 */
    private val realRepo = McpConfigRepository(
        containerInstaller = mockk(relaxed = true),
        workspaceRepository = mockk(relaxed = true),
        projectAicodeRoot = mockk(relaxed = true),
        fileChangeHub = mockk(relaxed = true).also { hub ->
            every { hub.watchAicode() } returns emptyFlow()
            every { hub.watchWorkspace(any()) } returns emptyFlow()
        }
    )

    private fun stubNewGlobal() {
        coEvery { repo.findEntry(any()) } returns null
        coEvery { repo.getGlobalServers() } returns emptyList()
        coEvery { repo.setGlobalServers(any()) } returns Unit
    }

    // ---- 真实 parse 的读法 ----

    @Test
    fun parse_stdio() {
        val servers = realRepo.parse(
            """{"mcpServers":{"foo":{"command":"npx","args":["-y","pkg"],"env":{"A":"1"}}}}"""
        )
        val s = servers.single()
        assertEquals("foo", s.name)
        assertTrue(s.isStdio)
        assertEquals("npx", s.command)
        assertEquals(listOf("-y", "pkg"), s.args)
        assertEquals(mapOf("A" to "1"), s.env)
    }

    @Test
    fun parse_http() {
        val servers = realRepo.parse(
            """{"mcpServers":{"bar":{"url":"https://example.com/mcp","headers":{"X":"y"}}}}"""
        )
        val s = servers.single()
        assertEquals("bar", s.name)
        assertEquals("https://example.com/mcp", s.url)
        assertEquals(mapOf("X" to "y"), s.headers)
        assertNull(s.command)
    }

    @Test
    fun parse_ignoresUnknownAuthField() {
        val servers = realRepo.parse(
            """{"mcpServers":{"svc":{"url":"https://x/mcp","auth":{"type":"oauth","clientId":"cid"}}}}"""
        )
        // auth 是上游字段，ignoreUnknownKeys 直接丢弃 —— 不产出半配置的 oauth 块。
        assertNull(servers.single().oauth)
    }

    // ---- 结构与写入 ----

    @Test
    fun import_notJson_fails() = runTest {
        assertEquals(McpImportOutcome.Failed(McpImportError.NOT_JSON), importer.import("hello"))
    }

    @Test
    fun import_missingServers_fails() = runTest {
        assertEquals(McpImportOutcome.Failed(McpImportError.MISSING_SERVERS), importer.import("""{"servers":{}}"""))
    }

    @Test
    fun import_emptyServers_fails() = runTest {
        assertEquals(McpImportOutcome.Failed(McpImportError.EMPTY_SERVERS), importer.import("""{"mcpServers":{}}"""))
    }

    @Test
    fun import_noRecognizableEntry_fails() = runTest {
        every { repo.parse(any()) } returns emptyList()
        assertEquals(
            McpImportOutcome.Failed(McpImportError.INVALID_ENTRIES),
            importer.import("""{"mcpServers":{"a":{}}}""")
        )
    }

    @Test
    fun import_stdio_writesToGlobalAndReloads() = runTest {
        val server = McpServerConfig(name = "foo", command = "npx", args = listOf("-y", "pkg"))
        every { repo.parse(any()) } returns listOf(server)
        stubNewGlobal()

        val outcome = importer.import("""{"mcpServers":{"foo":{"command":"npx","args":["-y","pkg"]}}}""")

        assertEquals(McpImportOutcome.Imported(listOf("foo"), emptyList()), outcome)
        coVerify { repo.setGlobalServers(match { it.size == 1 && it[0] == server }) }
        coVerify { manager.reloadServer("foo") }
    }

    @Test
    fun import_http_writesToGlobalAndReloads() = runTest {
        val server = McpServerConfig(name = "bar", url = "https://example.com/mcp")
        every { repo.parse(any()) } returns listOf(server)
        stubNewGlobal()

        val outcome = importer.import("""{"mcpServers":{"bar":{"url":"https://example.com/mcp"}}}""")

        assertEquals(McpImportOutcome.Imported(listOf("bar"), emptyList()), outcome)
        coVerify { repo.setGlobalServers(match { it.single().url == "https://example.com/mcp" }) }
        coVerify { manager.reloadServer("bar") }
    }

    @Test
    fun import_invalidName_skippedButValidImported() = runTest {
        every { repo.parse(any()) } returns listOf(
            McpServerConfig(name = "good", command = "npx"),
            McpServerConfig(name = "bad name!", command = "npx")
        )
        stubNewGlobal()

        val outcome = importer.import(
            """{"mcpServers":{"good":{"command":"npx"},"bad name!":{"command":"npx"}}}"""
        )

        assertEquals(McpImportOutcome.Imported(listOf("good"), listOf("bad name!")), outcome)
        coVerify { repo.setGlobalServers(match { it.map { s -> s.name } == listOf("good") }) }
        coVerify(exactly = 0) { manager.reloadServer("bad name!") }
    }

    @Test
    fun import_sameName_keepsExistingOauthAndEnabled() = runTest {
        val previous = McpServerConfig(
            name = "bar",
            url = "https://old.example.com/mcp",
            enabled = false,
            oauth = McpOAuthConfig(clientId = "cid", authorizationEndpoint = "a", tokenEndpoint = "t")
        )
        val incoming = McpServerConfig(name = "bar", url = "https://new.example.com/mcp")
        every { repo.parse(any()) } returns listOf(incoming)
        coEvery { repo.findEntry("bar") } returns McpServerEntry(previous, McpScope.GLOBAL)
        coEvery { repo.getGlobalServers() } returns listOf(previous)
        coEvery { repo.setGlobalServers(any()) } returns Unit

        val outcome = importer.import("""{"mcpServers":{"bar":{"url":"https://new.example.com/mcp"}}}""")

        assertEquals(McpImportOutcome.Imported(listOf("bar"), emptyList()), outcome)
        coVerify {
            repo.setGlobalServers(match {
                val merged = it.single()
                merged.url == "https://new.example.com/mcp" &&   // 传输配置更新
                    merged.oauth == previous.oauth &&            // 已授权令牌保留
                    !merged.enabled                              // 用户启停设置保留
            })
        }
    }

    @Test
    fun import_authFieldDoesNotProduceOauth() = runTest {
        every { repo.parse(any()) } returns listOf(McpServerConfig(name = "svc", url = "https://x/mcp"))
        stubNewGlobal()

        val outcome = importer.import(
            """{"mcpServers":{"svc":{"url":"https://x/mcp","auth":{"type":"oauth","clientId":"cid"}}}}"""
        )

        assertEquals(McpImportOutcome.Imported(listOf("svc"), emptyList()), outcome)
        coVerify { repo.setGlobalServers(match { it.single().oauth == null }) }
        coVerify(exactly = 0) { repo.updateServerOAuth(any(), any(), any()) }
    }
}
