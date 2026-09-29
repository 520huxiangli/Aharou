package com.aharou.feature.settings.domain.model

import androidx.annotation.StringRes
import com.aharou.R

/**
 * 存储占用分类。**各分类互斥**，加总即 App 总占用；枚举顺序即界面展示顺序。
 *
 * 分类边界按物理目录划定：日志与缓存所在目录不重复计入 [OtherData]，
 * 工作区内的聊天附件算在 [Workspaces] 里（只作为副标题提示，不单列分类）。
 */
enum class StorageCategory(@param:StringRes val labelRes: Int) {
    Chat(R.string.storage_category_chat),
    Container(R.string.storage_category_container),
    ContainerImages(R.string.storage_category_container_images),
    Workspaces(R.string.storage_category_workspaces),
    AiConfig(R.string.storage_category_ai_config),
    VoiceModels(R.string.storage_category_voice_models),
    Checkpoints(R.string.storage_category_checkpoints),
    Logs(R.string.storage_category_logs),
    Caches(R.string.storage_category_caches),
    OtherData(R.string.storage_category_other),
    Apk(R.string.storage_category_apk)
}

/**
 * 分类下的一条明细（容器名、项目名、会话标题、子目录名等）。
 *
 * @param key 稳定标识，供界面按项取值（如按 `tool-output` 找可清理大小）；纯展示项为 null。
 * @param indent 是否作为上一层明细的下钻项缩进显示。
 */
data class StorageDetail(
    val label: String,
    val bytes: Long,
    val note: String? = null,
    val key: String? = null,
    val indent: Boolean = false
)

data class StorageEntry(
    val category: StorageCategory,
    val bytes: Long,
    val details: List<StorageDetail> = emptyList()
)

/** [StorageDetail.key] 的取值：把“哪些明细项可清理”与它们的目录名解耦。 */
object StorageDetailKey {
    const val TOOL_OUTPUT = "toolOutput"
    const val VISION_SESSIONS = "visionSessions"
}

/** 可清理项：都是能自动重建或仅影响历史回看的临时数据，不含用户内容。 */
enum class CleanupKind(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descRes: Int
) {
    Caches(R.string.storage_clean_caches, R.string.storage_clean_caches_desc),
    Logs(R.string.storage_clean_logs, R.string.storage_clean_logs_desc),
    ToolOutput(R.string.storage_clean_tool_output, R.string.storage_clean_tool_output_desc),
    VisionSessions(R.string.storage_clean_vision, R.string.storage_clean_vision_desc)
}

/** 所在存储卷的容量信息（App 私有目录所在卷）。 */
data class DeviceSpace(
    val totalBytes: Long,
    val availableBytes: Long
)

/** 字节数转可读文本。固定用 [java.util.Locale.US] 的小数点，避免跟随系统区域变成逗号。 */
internal fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
