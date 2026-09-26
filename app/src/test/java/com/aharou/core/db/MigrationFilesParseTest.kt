package com.aharou.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移文件静态校验（纯 JVM，不依赖 Robolectric——本容器 JVM 加载不了 Robolectric 的 conscrypt native 库）。
 *
 * 直接读文件系统上的迁移脚本（Gradle 单测工作目录 = 模块根 app/）：
 * - 每个非空脚本经 [SqlScriptSplitter] 切分后必须产出语句（防解析器回归）；
 * - 版本号严格递增连续无重复（缺档会让 Room 走 fallback 清库）；
 * - 与 AgentDatabase.kt 的 SCHEMA_VERSION 对齐。
 * CI 另由 scripts/check_migrations.py 做 git refs 级别的对账（已发布 tag 冻结等），此处互补。
 */
class MigrationFilesParseTest {

    private val migrationsDir = File("src/main/assets/migrations")
    private val dbSource = File("src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt")

    private fun migrationFiles(): List<File> =
        migrationsDir.listFiles { f -> f.name.endsWith(".sql") }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun every_non_blank_script_splits_into_statements() {
        assertTrue("找不到迁移目录 ${migrationsDir.absolutePath}", migrationsDir.isDirectory)
        for (file in migrationFiles()) {
            val script = file.readText()
            if (script.isBlank()) continue // 允许空迁移（如 35 号只记 history）
            val stmts = SqlScriptSplitter.split(script)
            assertTrue("${file.name} 解析后没有语句", stmts.isNotEmpty())
            assertTrue("${file.name} 解析出空白语句", stmts.none { it.isBlank() })
        }
    }

    @Test
    fun versions_are_contiguous_unique_and_match_schema_version() {
        val versions = migrationFiles().mapNotNull { file ->
            Regex("(\\d+)_").find(file.name)?.groupValues?.get(1)?.toInt()
        }.sorted()
        assertTrue("没有解析到任何迁移版本号", versions.isNotEmpty())
        assertEquals("版本号必须严格递增连续（允许从 8 起步，但不能缺档）",
            (versions.first()..versions.last()).toList(), versions)
        val schemaVer = Regex("const val SCHEMA_VERSION = (\\d+)")
            .find(dbSource.readText())?.groupValues?.get(1)?.toInt()
        assertEquals("SCHEMA_VERSION 必须等于最大迁移版本", versions.last(), schemaVer)
    }
}