package com.aharou.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aharou.feature.agent.data.local.database.AgentDatabase
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
     * 未来新增迁移（51+）时把 @AutoMigration/新迁移加进来后在此补升级用例：
     * createDatabase(50) → 跑迁移数组 → 断言新列/新表存在。
     */
}