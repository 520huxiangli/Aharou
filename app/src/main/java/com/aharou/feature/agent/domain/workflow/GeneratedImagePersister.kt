package com.aharou.feature.agent.domain.workflow

import android.util.Base64
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.workspace.domain.FileAccessProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把模型直出的图片（base64）落盘到 `~/.aharou/generated-images/`，返回带容器路径的 images
 * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
 *
 * 抽成独立类是为了给 StatefulAgentWorkflow 腾出行数（单文件行数棘轮）。
 */
@Singleton
class GeneratedImagePersister @Inject constructor(
    private val fileAccess: FileAccessProvider
) {

    suspend fun persist(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        withContext(Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private companion object {
        const val DIR = "~/.aharou/generated-images"

        /** 单张直出图片上限：超过就丢弃（base64 落盘会撑爆工作区，也没有展示价值）。 */
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
