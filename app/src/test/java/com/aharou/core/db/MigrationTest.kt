package com.aharou.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aharou.feature.agent.data.local.database.AgentDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room 官方迁移测试（Robolectric，JVM 上跑真实的 Android SQLite）。
 *
 * 本容器（Android 宿主上的 PRoot）JVM 加载不了 Robolectric 的 conscrypt native 库
 * （UnsatisfiedLinkError，java.library.path 指向 /data/app、/system 等 Android 路径），
 * 故用 [guardEnvironment] 在非标准 Linux 环境跳过——跳过不算失败；
 * CI（ubuntu x86_64）与普通开发机上真实执行。
 *
 * 历史版本（8~49）的 schema json 当年未导出，[MigrationTestHelper] 只能覆盖
 * 「当前版本及以后」的路径；历史路径的连续/无重复由 [MigrationFilesParseTest] 与
 * scripts/check_migrations.py 双向兜底。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Test
    fun open_current_schema_passes_identity_check() {
        helper.createDatabase(dbName, AgentDatabase.SCHEMA_VERSION).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        // 打开即触发 Room 的 identity/schema 校验，entity 与迁移产物不符会抛异常
        db.openHelper.writableDatabase
        db.close()
    }

    /**
     * 回归：v2.39.0 的 74 号迁移用 `ADD COLUMN customIcon TEXT` 建了可空列，而 Entity 是非空 String，
     * 老库升级上来一律在 Room 表校验处崩（IllegalStateException: Migration didn't properly handle: ai_providers）。
     * 这条用例走 73 → 当前的完整升级路径，顺带验证旧行的 customIcon 被补成空串。
     */
    @Test
    fun migrate_73_to_current_fills_provider_custom_icon() {
        helper.createDatabase(dbName, 73).apply {
            execSQL(
                """
                INSERT INTO `ai_providers` (
                  `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
                  `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`,
                  `defaultModel`, `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`,
                  `anthropicCacheBreakpoints`, `openaiChatCacheKey`, `balanceScriptPath`,
                  `balanceRefreshInterval`, `customHeaders`, `sortOrder`, `proxyEnabled`, `proxyType`,
                  `proxyHost`, `proxyPort`, `proxyUsername`, `proxyPassword`, `scriptParams`
                ) VALUES (
                  'p1', 'old', 'OPENAI', 'k', 0, '', 'SEQUENTIAL', 2, 5, '', 'https://example.com',
                  'm', 'm', 'm', 1, 0, 0, 1, 0, '', 5, '', 0, 0, 'HTTP', '', 0, '', '', ''
                )
                """.trimIndent()
            )
            close()
        }

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        // 打开即触发 Room 的表结构校验，customIcon 不是 NOT NULL 就在这一步抛异常
        db.openHelper.writableDatabase
        // 走 openHelper 的 SupportSQLiteDatabase.query：Room 的 query() 在主线程调用会抛
        // IllegalStateException（Robolectric 用例就跑在主线程）
        db.openHelper.readableDatabase.query(
            "SELECT `customIcon` FROM `ai_providers` WHERE `id` = 'p1'"
        ).use { cursor ->
            assertTrue("升级后旧供应商记录应保留", cursor.moveToFirst())
            assertEquals("旧行的 customIcon 应补成空串", "", cursor.getString(0))
        }
        db.close()
    }

    /**
     * 未来新增迁移（51+）时把 @AutoMigration/新迁移加进来后在此补升级用例：
     * createDatabase(50) → 跑迁移数组 → 断言新列/新表存在。
     */
}