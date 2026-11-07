package com.aharou.feature.backup.data

import com.aharou.core.util.LogLevel
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.permission.PermissionRulesRepository
import com.aharou.feature.backup.domain.BackupMetadata
import com.aharou.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.LogSettingsRepository
import com.aharou.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aharou.feature.settings.data.repository.StartupSessionMode
import com.aharou.feature.settings.data.repository.SyncSettingsRepository
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

class BackupRestoreSettings @Inject constructor(
    private val mcp: McpConfigRepository,
    private val permissions: PermissionRulesRepository,
    private val theme: ThemeSettingsRepository,
    private val keepalive: KeepaliveSettingsRepository,
    private val screen: ScreenOnSettingsRepository,
    private val sound: AgentSoundSettingsRepository,
    private val general: GeneralSettingsRepository,
    private val log: LogSettingsRepository,
    private val vision: VisionModelSettingsRepository,
    private val compaction: CompactionModelSettingsRepository,
    private val sync: SyncSettingsRepository
) {
    internal suspend fun apply(meta: BackupMetadata, changes: BackupRestoreChanges) {
        fun recovery(name: String) = java.io.File(changes.directory, "settings-$name.json")
        suspend fun change(action: suspend () -> (suspend () -> Unit)) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { changes.record(action()) }
            currentCoroutineContext().ensureActive()
        }
        if (meta.mcpServers.isNotEmpty()) change {
            changes.afterRollback { mcp.refreshGlobalAfterBackupRollback() }
            mcp.restoreGlobalForBackup(meta.mcpServers) { file, content ->
                content.byteInputStream().use { changes.write(file, it) }
            }
        }
        if (meta.globalPermissionRules.isNotEmpty()) change {
            changes.afterRollback { permissions.refreshGlobalAfterBackupRollback() }
            permissions.restoreGlobalForBackup(meta.globalPermissionRules) { file, content ->
                content.byteInputStream().use { changes.write(file, it) }
            }
        }
        if (!meta.includesAppSettings) return
        val colors = mutableMapOf<String, Any?>(
            "theme_skin_id" to meta.themePresetId,
            "theme_preset_id" to null,
            "dynamic_color_enabled" to meta.dynamicColorEnabled
        )
        meta.themeMode?.let { colors["theme_mode"] = it }
        change { theme.restoreBackupState(colors, recovery("theme_prefs")) }
        change { keepalive.restoreBackupState(mapOf("keepalive_enabled" to meta.keepaliveEnabled), recovery("keepalive_prefs")) }
        change { screen.restoreBackupState(mapOf("screen_on_enabled" to meta.screenOnEnabled), recovery("screen_on_prefs")) }
        change { sound.restoreBackupState(mapOf("agent_sound_enabled" to meta.agentSoundEnabled), recovery("agent_sound_prefs")) }
        change { general.restoreBackupState(mapOf(
            "auto_remove_stale_models" to meta.autoRemoveStaleModels,
            "startup_session_mode" to (StartupSessionMode.entries.firstOrNull { it.name == meta.startupSessionMode }
                ?: StartupSessionMode.NEW_SESSION).name,
            "first_byte_timeout_sec" to meta.firstByteTimeoutSec.coerceAtLeast(0),
            "stream_idle_timeout_sec" to meta.streamIdleTimeoutSec.coerceAtLeast(0),
            "max_network_retries" to meta.maxNetworkRetries.coerceAtLeast(0),
            "enter_to_send" to meta.enterToSend,
            "compaction_threshold_percent" to meta.compactionThresholdPercent.coerceIn(1, 100),
            "soft_compaction_threshold_percent" to meta.softCompactionThresholdPercent.coerceIn(1, 100),
            "sendfile_max_size_mb" to meta.sendFileMaxSizeMb.coerceAtLeast(1),
            "delete_external_workspace_sessions" to meta.deleteExternalWorkspaceSessions
        ), recovery("general_prefs")) }
        meta.logLevel?.takeIf { value -> LogLevel.entries.any { it.name == value } }?.let { value ->
            change { log.restoreBackupState(mapOf("log_min_level" to value), recovery("log_prefs")) }
        }
        change { vision.restoreBackupSelection(meta.visionProviderId, meta.visionModel, recovery("vision_model_prefs")) }
        change { compaction.restoreBackupSelection(meta.compactionProviderId, meta.compactionModel, recovery("compaction_model_prefs")) }
        meta.syncSettings?.let { snapshot -> change { sync.restoreBackupState(snapshot, recovery("sync_settings")) } }
    }
}
