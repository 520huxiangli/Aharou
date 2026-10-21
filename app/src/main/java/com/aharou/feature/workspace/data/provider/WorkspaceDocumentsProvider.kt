package com.aharou.feature.workspace.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.aharou.R
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.LinkedList

/**
 * SAF DocumentsProvider，把 app 私有 `filesDir` 暴露为**单一根**到系统「文件」app 及其它 app 的
 * SAF 选择器，满足「对外可见」。根下只放出三个子目录：
 * - `projects`：工作区（各项目）；
 * - `aharou`：AI 配置目录（skills/ 与 mcp.json，容器内即 `/root/.aharou`）；
 * - `shared`：跨工作区共享区（容器内即 `/root/shared`）。
 *
 * 其余内部目录（rootfs、容器二进制、数据库、DataStore 等）刻意不在根下列出，避免对外泄露/误删。
 *
 * 工作区物理上在 app 私有 ext4（filesDir），换取 symlink 支持（npm/pnpm/yarn/git 零配置可用）；
 * emulated 存储虽天然可见但内核拒绝 symlink。可见性这里用官方 SAF API 在 API 层补回，与物理位置解耦。
 *
 * docId 用文件规范路径（canonicalPath），与 [fileForDocId] 的解析口径一致，并统一校验解析结果
 * 落在允许根（filesDir）之内，挡住 `..` 与 symlink 逃逸。
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private companion object {
        const val ALL_MIME_TYPES = "*/*"
        const val MAX_SEARCH_RESULTS = 50

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    /** 暴露的单一根：app 私有 filesDir。 */
    private fun baseDir(): File =
        ctx().filesDir

    /**
     * 根下唯一对外可见的子目录名白名单：工作区 `projects`、AI 配置 `aharou`
     *（容器内 /root/.aharou，含 skills/ 与 mcp.json）与跨工作区共享区 `shared`
     *（容器内 /root/shared）。三者首次访问即创建，其余 filesDir 内部目录不列出。
     *
     * `shared` 实际住在 `aharou-global/shared`（对齐上游 minis-global/shared），这里以目录对象
     * 入场，显示名仍是 `shared`（docId 用绝对路径）。刻意不整体放开 `aharou-global/`——
     * 它下面还有 memory，里面有凭据类文件。
     */
    private fun exposedChildren(): List<File> {
        ContainerInstaller.extractDocs(ctx())
        return listOf(
            File(baseDir(), "projects").apply { mkdirs() },
            File(baseDir(), "aharou").apply { mkdirs() },
            File(baseDir(), "aharou-global/shared").apply { mkdirs() },
        )
    }

    /** minSdk 26 上基类无 requireContext()，这里自取非空 context。 */
    private fun ctx() =
        context ?: throw IllegalStateException("Provider context unavailable")

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        addRoot(result, baseDir(), ctx().getString(R.string.app_name))
        return result
    }

    /** 往根游标追加一个 root：docId 用目录绝对路径，与 [docIdForFile] 一致。 */
    private fun addRoot(result: MatrixCursor, dir: File, title: String) {
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, docIdForFile(dir))
            add(Root.COLUMN_DOCUMENT_ID, docIdForFile(dir))
            add(Root.COLUMN_SUMMARY, null)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(Root.COLUMN_TITLE, title)
            add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
            add(Root.COLUMN_AVAILABLE_BYTES, dir.freeSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, null)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileForDocId(parentDocumentId)
        // 根目录（filesDir 本身）只放出白名单子目录，隐藏 rootfs/数据库等内部目录；
        // 其余层级照常列出全部内容。
        val children = if (parent.canonicalFile == rootDir()) {
            exposedChildren()
        } else {
            parent.listFiles()?.toList() ?: emptyList()
        }
        children.filter { isWithinExposedRoots(it) }.forEach { child -> includeFile(result, null, child) }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileForDocId(parentDocumentId)
        // 根的 CREATE flag 已去掉，但客户端仍可直接调到这里，必须再拦一次：
        // filesDir 根上随手建目录会混进内部文件之间，也会让后续白名单校验失效。
        if (parent.canonicalFile == rootDir()) {
            throw FileNotFoundException("Cannot create documents in the root directory")
        }
        // parent 已是校验过的规范路径；displayName 来自外部，只取 basename 再拼接，杜绝含 `/` 与 `..` 的逃逸。
        val safeName = File(displayName).name
        if (safeName.isEmpty() || safeName == "." || safeName == "..") {
            throw FileNotFoundException("Invalid display name: $displayName")
        }
        var newFile = File(parent, safeName)
        var conflictId = 2
        while (newFile.exists()) {
            newFile = File(parent, "$safeName ($conflictId)")
            conflictId++
        }
        val ok = try {
            if (Document.MIME_TYPE_DIR == mimeType) newFile.mkdir() else newFile.createNewFile()
        } catch (e: Exception) {
            throw FileNotFoundException("Failed to create document: ${newFile.path}")
        }
        if (!ok) throw FileNotFoundException("Failed to create document: ${newFile.path}")
        return docIdForFile(newFile)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForDocId(documentId)
        // 根 docId 就是 filesDir 本身，不拦的话一次 deleteRecursively 会连数据库、
        // rootfs、凭据一起清掉。
        if (file.canonicalFile == rootDir()) {
            throw FileNotFoundException("Cannot delete the root directory")
        }
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete document: $documentId")
        }
    }

    override fun getDocumentType(documentId: String): String =
        mimeType(fileForDocId(documentId))

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val root = fileForDocId(rootId)
        val rootCanonical = root.canonicalPath
        // 从根（filesDir）搜索时只下钻白名单子目录，避免扫到 rootfs 等内部目录；其余层级照常。
        val seeds = if (root.canonicalFile == rootDir()) exposedChildren() else listOf(root)
        val pending = LinkedList<File>().apply { addAll(seeds) }
        val visited = HashSet<String>()
        val needle = query.lowercase()

        while (pending.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            // 白名单 + 归属双重校验：symlink 指向外面、或自指成环都要在这里被挡住（visited 防环）。
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (!isWithinExposedRoots(file) ||
                (canonical != rootCanonical && !canonical.startsWith(rootCanonical + File.separator)) ||
                !visited.add(canonical)
            ) continue
            if (file.isDirectory) {
                file.listFiles()?.let { pending.addAll(it) }
            } else if (file.name.lowercase().contains(needle)) {
                includeFile(result, null, file)
            }
        }
        return result
    }

    /**
     * AOSP `enforceTree` 唯一会问的子类闸门，必须按规范路径比较：`<parent>/../../x` 这类输入只有解析掉
     * `..`（含已存在前缀里的 symlink）才看得出它已离开 parent 子树，纯字符串前缀会被绕过。比较带分隔符
     * 以排除同级 `/foo` 命中 `/foobar`；解析抛异常时 fail-closed 返回 false。
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { File(parentDocumentId).canonicalPath }.getOrNull() ?: return false
        val child = runCatching { File(documentId).canonicalPath }.getOrNull() ?: return false
        return child == parent || child.startsWith(parent + File.separator)
    }

    /** 允许根目录（filesDir）的规范路径，作为全部 docId 的包含校验基准。 */
    private fun rootDir(): File = baseDir().canonicalFile

    /**
     * 对外暴露的子目录名白名单（相对 filesDir），与 [exposedChildren] 一致。
     *
     * 刻意不整体放开 `aharou-global/`——它下面还有 memory，里面有凭据类文件。
     */
    private fun exposedRootNames(): List<String> = listOf("projects", "aharou", "aharou-global/shared")

    /**
     * [file] 是否在允许根（filesDir 本身）或其白名单子目录内。
     *
     * docId 就是绝对路径，单靠「在 filesDir 之内」拦不住构造出的
     * databases、shared_prefs、aharou-global/memory 等内部路径：
     * 拿到本 provider 树授权的客户端能直接读写它们。因此归属校验必须收敛到白名单。
     */
    private fun isWithinExposedRoots(file: File): Boolean {
        val base = runCatching { rootDir().path }.getOrNull() ?: return false
        val target = runCatching { file.canonicalPath }.getOrNull() ?: return false
        if (target == base) return true
        return exposedRootNames().any { name ->
            val rootPath = runCatching { File(base, name).canonicalPath }.getOrNull() ?: return@any false
            target == rootPath || target.startsWith(rootPath + File.separator)
        }
    }

    /** docId 统一按规范路径解析；解析结果必须落在白名单子目录内，否则拒绝（挡 `..`、symlink 与内部目录）。 */
    private fun fileForDocId(docId: String): File {
        val file = try {
            File(docId).canonicalFile
        } catch (e: IOException) {
            throw FileNotFoundException("Invalid document id: $docId")
        }
        if (!isWithinExposedRoots(file)) throw FileNotFoundException("$docId outside exposed roots")
        if (!file.exists()) throw FileNotFoundException("${file.absolutePath} not found")
        return file
    }

    private fun docIdForFile(file: File): String =
        try {
            file.canonicalPath
        } catch (e: IOException) {
            file.absolutePath
        }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun includeFile(result: MatrixCursor, docId: String?, file: File?) {
        val resolvedFile = file ?: docId?.let { fileForDocId(it) }
        ?: throw FileNotFoundException("docId and file both null")
        val resolvedDocId = docId ?: docIdForFile(resolvedFile)

        var flags = 0
        val isExposedRoot = resolvedFile.canonicalFile == rootDir()
        if (resolvedFile.isDirectory) {
            if (!isExposedRoot && resolvedFile.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (resolvedFile.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isExposedRoot && resolvedFile.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE
        }

        val mime = mimeType(resolvedFile)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, resolvedDocId)
            add(Document.COLUMN_DISPLAY_NAME, resolvedFile.name)
            add(Document.COLUMN_SIZE, resolvedFile.length())
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_LAST_MODIFIED, resolvedFile.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }
}
