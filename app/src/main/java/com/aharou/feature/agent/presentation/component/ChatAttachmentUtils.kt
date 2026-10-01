package com.aharou.feature.agent.presentation.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aharou.R
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.DIRECTORY_MIME_TYPE
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class UploadedWorkspaceFile(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null
)

internal data class PendingUploadAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null,
)

internal val PendingUploadAttachment.isImage: Boolean
    get() = image != null

private fun List<PendingUploadAttachment>.toAttachmentText(context: Context): String {
    if (isEmpty()) return ""
    val hasDirectory = any { it.mimeType == DIRECTORY_MIME_TYPE }
    return buildString {
        append(context.getString(R.string.chat_attachment_prefix))
        this@toAttachmentText.forEach { attachment ->
            append('\n')
            append("- ")
            append(attachment.fileName)
            if (attachment.mimeType == DIRECTORY_MIME_TYPE) {
                append(context.getString(R.string.chat_attachment_directory_suffix))
            }
            append("：")
            append(attachment.containerPath)
        }
        // 目录附件只有路径、没有内容，点明一句，免得模型当成空文件或以为内容已在上下文里。
        if (hasDirectory) {
            append('\n')
            append(context.getString(R.string.chat_attachment_directory_hint))
        }
    }
}

internal fun appendAttachmentsToRequest(
    context: Context,
    request: String,
    attachments: List<PendingUploadAttachment>
): String {
    val attachmentText = attachments.toAttachmentText(context)
    if (attachmentText.isBlank()) return request
    if (request.isBlank()) return attachmentText
    return request.trimEnd() + "\n\n" + attachmentText
}

internal fun UploadedWorkspaceFile.toPendingAttachment(): PendingUploadAttachment =
    PendingUploadAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )

internal fun AgentAttachment.toPendingAttachment(): PendingUploadAttachment {
    val image = if (isImage && localPath.isNotBlank()) {
        val file = File(localPath)
        if (file.exists() && file.isFile && file.length() > 0) {
            try {
                val bytes = file.readBytes()
                val base64 = Base64.getEncoder().encodeToString(bytes)
                AgentImage(
                    mimeType = mimeType.ifBlank { "image/jpeg" },
                    base64Data = base64,
                    path = containerPath
                )
            } catch (e: Exception) {
                null
            }
        } else null
    } else null

    return PendingUploadAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )
}

private fun PendingUploadAttachment.toAgentAttachment(): AgentAttachment =
    AgentAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        isImage = isImage
    )

internal fun List<PendingUploadAttachment>.toAgentAttachments(): List<AgentAttachment> =
    map { it.toAgentAttachment() }

private fun PendingUploadAttachment.toAgentImage(): AgentImage? = image

internal fun List<PendingUploadAttachment>.toAgentImages(): List<AgentImage> =
    mapNotNull { it.toAgentImage() }

internal fun maxAttachmentMessage(context: Context, max: Int): String =
    context.getString(R.string.chat_attachment_max, max)

internal fun uploadSuccessMessage(context: Context, count: Int): String =
    context.getString(R.string.chat_attachment_uploaded, count)

internal fun partialUploadMessage(context: Context, count: Int): String =
    context.getString(R.string.chat_attachment_uploaded_partial, count)

internal fun selectedAttachments(
    uris: List<Uri>,
    currentCount: Int
): List<Uri> = uris.take((MAX_PENDING_ATTACHMENTS - currentCount).coerceAtLeast(0))

internal fun hasAttachmentSlots(currentCount: Int): Boolean =
    currentCount < MAX_PENDING_ATTACHMENTS

private fun imageLimitError(context: Context): String =
    context.getString(R.string.chat_image_too_large, formatBytes(MAX_IMAGE_UPLOAD_BYTES))

private fun pickedFileToastPath(context: Context, path: String): String =
    context.getString(R.string.chat_uploaded_to, path)

internal fun emptyWorkspaceMessage(context: Context): String =
    context.getString(R.string.chat_select_workspace_first)

internal fun unreadableFileMessage(context: Context): String =
    context.getString(R.string.chat_read_file_failed)

internal fun uploadFallbackError(context: Context): String =
    context.getString(R.string.chat_upload_failed)

private fun unsupportedImageTypeError(context: Context): String =
    context.getString(R.string.chat_unsupported_image_type)

private fun uploadFileName(context: Context, uri: Uri): String =
    context.displayName(uri).ifBlank { "upload" }

private fun safeUploadFileName(context: Context, uri: Uri): String =
    sanitizeUploadFileName(uploadFileName(context, uri))

private fun imageMimeType(context: Context, uri: Uri, fileName: String): String =
    resolveImageMimeType(context, uri, fileName)

private fun fileMimeType(context: Context, uri: Uri, fileName: String): String =
    context.contentResolver.getType(uri)?.lowercase()
        ?: when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

internal suspend fun copyUriToWorkspace(
    context: Context,
    uri: Uri,
    fileAccess: FileAccessProvider,
    includeImageData: Boolean = false
): UploadedWorkspaceFile = withContext(Dispatchers.IO) {
    val attachmentsDir = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/attachments"
    val fileName = uniqueUploadName(fileAccess, attachmentsDir, safeUploadFileName(context, uri))
    val containerPath = "$attachmentsDir/$fileName"

    val mimeType = if (includeImageData) {
        imageMimeType(context, uri, fileName)
    } else {
        fileMimeType(context, uri, fileName)
    }

    // 图片要整份读进来做 base64，先按 provider 报的大小拦一道；SIZE 拿不到时下面还有兜底判断。
    if (includeImageData) {
        val declared = context.contentSize(uri)
        if (declared != null && declared > MAX_IMAGE_UPLOAD_BYTES) error(imageLimitError(context))
    }

    val sizeBytes: Long
    val image: AgentImage?
    if (includeImageData) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error(unreadableFileMessage(context))
        if (bytes.size > MAX_IMAGE_UPLOAD_BYTES) error(imageLimitError(context))
        fileAccess.writeBytes(containerPath, bytes, overwrite = true)
        sizeBytes = bytes.size.toLong()
        image = AgentImage(
            mimeType = mimeType,
            base64Data = Base64.getEncoder().encodeToString(bytes),
            path = containerPath
        )
    } else {
        // 普通附件流式落盘：不再整份读进内存，于是文件多大都不会顶爆堆
        sizeBytes = context.contentResolver.openInputStream(uri)?.use { input ->
            fileAccess.writeStream(containerPath, input, overwrite = true)
        } ?: error(unreadableFileMessage(context))
        image = null
    }

    // 缩略图与图片数据需要本地文件：本地模式直接给宿主文件，远程模式下载到临时文件
    val localFile = fileAccess.copyToLocal(containerPath)

    UploadedWorkspaceFile(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localFile.absolutePath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )
}

private fun Context.displayName(uri: Uri): String {
    val fromProvider = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
    }.orEmpty()
    if (fromProvider.isNotBlank()) return fromProvider
    // file:// 之类没有真实 provider（如分享 intent 直接给路径），退回路径末段。
    return uri.lastPathSegment.orEmpty()
}

private fun Context.contentSize(uri: Uri): Long? {
    return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
    }
}

private fun sanitizeUploadFileName(name: String): String {
    val cleaned = name
        .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
        .joinToString("")
        .trim()
        .trim('.')
    return cleaned.ifBlank { "upload" }.take(160)
}

private fun uniqueUploadName(fileAccess: FileAccessProvider, dir: String, fileName: String): String {
    if (!fileAccess.exists("$dir/$fileName")) return fileName

    val dotIndex = fileName.lastIndexOf('.')
    val stem = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (fileAccess.exists("$dir/$stem-$index$extension")) {
        index += 1
    }
    return "$stem-$index$extension"
}

private fun resolveImageMimeType(context: Context, uri: Uri, fileName: String): String {
    val mime = context.contentResolver.getType(uri)?.lowercase()
    if (mime != null && mime in SUPPORTED_IMAGE_MIME_TYPES) return mime

    val byExtension = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
    if (byExtension != null) return byExtension
    error(unsupportedImageTypeError(context))
}

private val SUPPORTED_IMAGE_MIME_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp"
)

private const val MAX_IMAGE_UPLOAD_BYTES = 5L * 1024 * 1024
internal const val MAX_PENDING_ATTACHMENTS = 8

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
