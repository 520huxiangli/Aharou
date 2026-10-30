package com.aharou.desktop

import com.aharou.feature.settings.domain.model.ProviderType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 桌面端的本地配置。与手机端互不相干，单独落在 `~/.aharou-desktop/`。 */
@Serializable
data class DesktopSettings(
    val providerType: ProviderType = ProviderType.OPENAI,
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    /** 文件树默认展开的根目录；空串表示用用户主目录。 */
    val workspacePath: String = ""
) {
    val isUsable: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

object DesktopSettingsStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(System.getProperty("user.home"), ".aharou-desktop/settings.json")

    fun load(): DesktopSettings = runCatching {
        if (file.exists()) json.decodeFromString<DesktopSettings>(file.readText()) else DesktopSettings()
    }.getOrElse { DesktopSettings() }

    fun save(settings: DesktopSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(settings))
        }
    }

    fun location(): File = file
}
