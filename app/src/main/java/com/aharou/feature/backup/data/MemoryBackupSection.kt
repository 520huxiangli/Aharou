package com.aharou.feature.backup.data

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.backup.domain.RestoreStats
import com.aharou.feature.workspace.domain.model.Workspace
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局记忆（记忆与灵魂）段的 tar 前缀：`memory/<文件名>`，归档为 `memory/archive/<文件名>`。 */
internal const val GLOBAL_MEMORY_PREFIX = "memory/"

/** 项目级记忆段的 tar 前缀：本地 `memory-project/<工作区名>/memory/<相对路径>`、远程 `memory-project/remote/<key>/<相对路径>`。 */
internal const val PROJECT_MEMORY_PREFIX = "memory-project/"

/** 单条记忆文件体积上限（超过则不写入，避免个别超大文件把整包撑爆）。 */
internal const val MAX_MEMORY_FILE_BYTES = 8L * 1024 * 1024

/** 归档子目录名，与 [com.aharou.feature.agent.domain.memory.MemorySource] 保持一致。 */
internal const val MEMORY_ARCHIVE_DIR = "archive"

/** 记忆文件到 tar 的通用写入：条目名 + 文件，交给调用方决定前缀与上限判断。 */
internal fun writeTarFileEntry(tar: TarArchiveOutputStream, name: String, file: File) {
    val entry = TarArchiveEntry(name).apply { size = file.length() }
    tar.putArchiveEntry(entry)
    FileInputStream(file).use { it.copyTo(tar) }
    tar.closeArchiveEntry()
}

/**
 * 记忆段的打包与还原：全局记忆、项目级记忆（本地随工作区目录、远程落私有 projects 目录）。
 *
 * 与工作区文件段不同，记忆是「一个名字一条」的覆盖语义——还原同名条目会抹掉本机旧版，
 * 所以覆盖前先把旧版留进 `archive/`（[MemorySource][com.aharou.feature.agent.domain.memory.MemorySource] 也是这么做的），
 * 让用户对「记忆被备份还原覆盖」这件事有后悔药。
 *
 * 单独成文件而非并入 [BackupManagerImpl]：后者已逼近单文件行数门禁，且记忆的路径规则集中一处更易核对。
 */
internal class MemoryBackupSection(
    private val context: Context,
    /** 参与备份的内部本地工作区；外部本地目录不属于 App 私有数据，远程工区的记忆由私有 projects 目录单独采集。 */
    private val internalWorkspaces: () -> List<Workspace>,
    /** 按工作区名解析还原目标：优先复用本轮导入已解析的映射，缺席时建同名内部工作区。 */
    private val resolveWorkspace: suspend (String, MutableMap<String, Workspace>) -> Workspace?
) {

    // ── 路径 ──────────────────────────────────────────────────

    /** 全局记忆目录：与 [com.aharou.core.memory.AharouMemoryStore] 同一路径（filesDir/aharou-global/memory）。 */
    private fun globalMemoryDir(): File = File(File(context.filesDir, "aharou-global"), "memory")

    /**
     * 远程项目级记忆的落盘根：与 [com.aharou.feature.agent.domain.memory.ProjectMemorySource] 一致
     * （filesDir/aharou/memory/projects），子目录名即项目 key。
     */
    private fun remoteProjectsDir(): File =
        File(File(ContainerInstaller.migrateLegacyDir(context), "memory"), "projects")

    /** 本地工作区的项目记忆目录：`<工作区路径>/.aharou/memory`。 */
    private fun localProjectMemoryDir(workspace: Workspace): File =
        File(File(workspace.path, ".aharou"), "memory")

    // ── 打包 ──────────────────────────────────────────────────

    /** 全局记忆：顶层文件 → `memory/<名>`，`archive/` 下的一层 → `memory/archive/<名>`。 */
    fun writeGlobalMemory(tar: TarArchiveOutputStream) {
        val dir = globalMemoryDir()
        if (!dir.isDirectory) return
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile) writeMemoryFile(tar, "$GLOBAL_MEMORY_PREFIX${f.name}", f)
        }
        val archive = File(dir, MEMORY_ARCHIVE_DIR)
        archive.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile) writeMemoryFile(tar, "$GLOBAL_MEMORY_PREFIX$MEMORY_ARCHIVE_DIR/${f.name}", f)
        }
    }

    /** 项目级记忆：本地工作区随目录走，远程工程落私有 projects 目录。 */
    fun writeProjectMemory(tar: TarArchiveOutputStream) {
        internalWorkspaces().sortedBy { it.name }.forEach { ws ->
            val root = localProjectMemoryDir(ws)
            walkMemoryFiles(root, "") { file, rel ->
                writeMemoryFile(tar, "$PROJECT_MEMORY_PREFIX${ws.name}/memory/$rel", file)
            }
        }
        val projects = remoteProjectsDir()
        val keyDirs = if (projects.isDirectory) projects.listFiles()?.filter { it.isDirectory } else null
        keyDirs?.sortedBy { it.name }?.forEach { keyDir ->
            walkMemoryFiles(keyDir, "") { file, rel ->
                writeMemoryFile(tar, "$PROJECT_MEMORY_PREFIX$REMOTE_SCOPE/${keyDir.name}/$rel", file)
            }
        }
    }

    /** 单文件超过上限就跳过并记日志：静默丢会让用户以为备份完整，真出问题时无从追查。 */
    private fun writeMemoryFile(tar: TarArchiveOutputStream, entryName: String, file: File) {
        if (file.length() > MAX_MEMORY_FILE_BYTES) {
            FileLogger.w(TAG, "记忆文件超过 ${MAX_MEMORY_FILE_BYTES / 1024 / 1024}MiB 上限，跳过备份：$entryName（${file.length()} 字节）")
            return
        }
        writeTarFileEntry(tar, entryName, file)
    }

    /** 深度优先遍历记忆目录，产出相对路径（含 `archive/` 等子目录）；符号链接跳过，防环。 */
    private fun walkMemoryFiles(root: File, prefix: String, onFile: (File, String) -> Unit) {
        val children = root.listFiles() ?: return
        children.sortedBy { it.name }.forEach { f ->
            if (Files.isSymbolicLink(f.toPath())) return@forEach
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) walkMemoryFiles(f, rel, onFile) else onFile(f, rel)
        }
    }

    // ── 还原 ──────────────────────────────────────────────────

    /** 还原一条全局记忆条目：顶层 `memory/<名>` 或归档 `memory/archive/<名>`。 */
    fun restoreGlobalMemoryEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry): RestoreStats {
        val rel = entry.name.removePrefix(GLOBAL_MEMORY_PREFIX)
        val isArchive = rel.startsWith("$MEMORY_ARCHIVE_DIR/")
        val fileName = if (isArchive) rel.removePrefix("$MEMORY_ARCHIVE_DIR/") else rel
        // 只接受顶层文件与 archive/ 之下的一层：更深层级不是本项目写出的结构，一律拒绝（顺带挡住 `..` 穿越）。
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains("..")) return RestoreStats()
        if (entry.size > MAX_MEMORY_FILE_BYTES) return RestoreStats()
        val bytes = tar.readBytes()
        return runCatching {
            val root = globalMemoryDir()
            val dir = if (isArchive) File(root, MEMORY_ARCHIVE_DIR) else root
            dir.mkdirs()
            // 归档目录里的旧版本本身就是底稿，再覆盖它没有意义；只有顶层同名条目才先留底。
            if (!isArchive) archiveBeforeOverwrite(root, fileName)
            File(dir, fileName).writeBytes(bytes)
            RestoreStats(memoryFiles = 1)
        }.getOrElse { e ->
            FileLogger.w(TAG, "恢复记忆文件失败 ${entry.name}: ${e.message}")
            RestoreStats()
        }
    }

    /** 还原一条项目记忆条目：本地 `<工作区名>/memory/<相对路径>` 或远程 `remote/<key>/<相对路径>`。 */
    suspend fun restoreProjectMemoryEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        selectedWorkspaces: Set<String>?,
        restoreMapping: MutableMap<String, Workspace>
    ): RestoreStats {
        if (entry.size > MAX_MEMORY_FILE_BYTES) return RestoreStats()
        val segments = entry.name.removePrefix(PROJECT_MEMORY_PREFIX).split("/")
        // 远程 key 形如「项目名-8 位哈希」，不可能恰为字面量 memory，故用第二段是否为 memory 区分本地与远程。
        val isRemote = segments.size >= 2 && segments[0] == REMOTE_SCOPE && segments[1] != "memory"

        val root: File
        val relPath: String
        if (isRemote) {
            if (segments.size < 3) return RestoreStats()
            val key = segments[1]
            if (key.isEmpty() || key.contains("..")) return RestoreStats()
            root = File(remoteProjectsDir(), key)
            relPath = segments.drop(2).joinToString("/")
        } else {
            if (segments.size < 3 || segments[1] != "memory") return RestoreStats()
            val wsName = segments[0]
            if (wsName.isEmpty() || wsName.contains("..")) return RestoreStats()
            if (selectedWorkspaces != null && wsName !in selectedWorkspaces) return RestoreStats()
            val ws = resolveWorkspace(wsName, restoreMapping) ?: return RestoreStats()
            root = localProjectMemoryDir(ws)
            relPath = segments.drop(2).joinToString("/")
        }

        val target = resolveInside(root, relPath) ?: return RestoreStats()
        val bytes = tar.readBytes()
        return runCatching {
            target.parentFile?.mkdirs()
            archiveBeforeOverwrite(root, relPath)
            target.writeBytes(bytes)
            RestoreStats(projectMemoryFiles = 1)
        }.getOrElse { e ->
            FileLogger.w(TAG, "恢复项目记忆失败 ${entry.name}: ${e.message}")
            RestoreStats()
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────

    /** 防路径穿越：相对路径规范化后仍须落在 [root] 之内。 */
    private fun resolveInside(root: File, relPath: String): File? {
        if (relPath.isEmpty() || relPath.contains("..")) return null
        val base = root.canonicalPath
        val target = File(root, relPath).canonicalPath
        return if (target == base || target.startsWith(base + File.separator)) File(root, relPath) else null
    }

    /** 覆盖写之前把 [root] 顶层同名文件原样留一份到 `archive/`；同秒重复归档追加序号，绝不覆盖已有归档。 */
    private fun archiveBeforeOverwrite(root: File, relPath: String) {
        if (relPath.contains('/')) return  // 只有顶层条目会被覆盖，归档子目录内的版本无需再留底
        val existing = File(root, relPath)
        if (!existing.isFile) return
        val archiveDir = File(root, MEMORY_ARCHIVE_DIR)
        if (!archiveDir.exists() && !archiveDir.mkdirs()) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var target = File(archiveDir, "$relPath-$stamp.md")
        var seq = 1
        while (target.exists()) {
            target = File(archiveDir, "$relPath-$stamp-$seq.md")
            seq++
        }
        runCatching { existing.copyTo(target, overwrite = false) }
            .onFailure { FileLogger.w(TAG, "归档旧版记忆失败：$relPath", it) }
    }

    private companion object {
        const val TAG = "BackupManager"
        const val REMOTE_SCOPE = "remote"
    }
}
