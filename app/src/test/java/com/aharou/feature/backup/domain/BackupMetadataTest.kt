package com.aharou.feature.backup.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMetadataTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun decode(text: String): BackupMetadata =
        json.decodeFromString(BackupMetadata.serializer(), text)

    /** 单会话导出的元数据不含应用设置，恢复时不应改动本机设置。 */
    @Test
    fun chatBackupDoesNotIncludeAppSettings() {
        val metadata = BackupMetadata(schemaVersion = 55, createdAt = 0)
        val restored = decode(json.encodeToString(BackupMetadata.serializer(), metadata))
        assertFalse(restored.includesAppSettings)
    }

    /** 旧备份没有 includesAppSettings 字段，按 themeMode 是否为 null 推断范围。 */
    @Test
    fun legacyMetadataInfersSettingsFromThemeMode() {
        assertTrue(decode("""{"schemaVersion":55,"createdAt":0,"themeMode":"DARK"}""").includesAppSettings)
        assertFalse(decode("""{"schemaVersion":55,"createdAt":0,"themeMode":null}""").includesAppSettings)
    }

    /** 新备份写入了显式范围，优先于按 themeMode 的推断。 */
    @Test
    fun explicitSettingsScopeOverridesLegacyInference() {
        assertFalse(
            decode("""{"schemaVersion":55,"createdAt":0,"themeMode":"DARK","includesAppSettings":false}""")
                .includesAppSettings
        )
    }

    /** 旧的单文件 snapshot.json 经 toMetadata() 还原时同样保留设置范围。 */
    @Test
    fun legacySnapshotPreservesSettingsScope() {
        assertFalse(BackupSnapshot(schemaVersion = 55, createdAt = 0).toMetadata().includesAppSettings)
        assertTrue(BackupSnapshot(schemaVersion = 55, createdAt = 0, themeMode = "AUTO").toMetadata().includesAppSettings)
    }
}
