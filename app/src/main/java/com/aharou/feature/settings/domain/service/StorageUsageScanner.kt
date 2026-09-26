package com.aharou.feature.settings.domain.service

import android.content.Context
import com.aharou.R
import com.aharou.core.util.AILogger
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.tool.ToolOutputStore
import com.aharou.feature.agent.domain.tool.file.VisionSessionStore
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.domain.model.CleanupKind
import com.aharou.feature.settings.domain.model.DeviceSpace
import com.aharou.feature.settings.domain.model.StorageCategory
import com.aharou.feature.settings.domain.model.StorageDetail
import com.aharou.feature.settings.domain.model.StorageDetailKey
import com.aharou.feature.settings.domain.model.StorageEntry
import com.aharou.feature.settings.domain.model.formatStorageSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统计 App 各部分磁盘占用，并清理可安全重建的临时数据。
 *
 * 分类之间互斥（见 [StorageCategory]），加总即总占用。**外部目录工作区不计入**：它物理上在设备共享
 * 存储里，可能是用户挑的任意大目录，扫进来既不属于 App 占用也会拖垮统计。
 */
@Singleton
class StorageUsageScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val toolOutputStore: ToolOutputStore,
    private val visionSessionStore: VisionSessionStore,
    private val agentMessageDao: AgentMessageDao
) {

    /**
     * 逐分类发射统计结果：数据库、缓存这类秒出的先算，rootfs 与工作区最后，界面可以边算边填。
     *
     * 收集方取消（如离开页面）后，正在进行的目录遍历会在下一个文件处中止，不会白跑完整棵树。
     */
    fun scan(): Flow<StorageEntry> = flow {
        val job = currentCoroutineContext()[Job]
        val cancelled: () -> Boolean = { job?.isActive == false }

        emit(chatEntry())
        emit(cacheEntry(cancelled))
        emit(logsEntry())
        emit(apkEntry())
        emit(checkpointsEntry(cancelled))
        emit(imagesEntry())
        emit(otherDataEntry(cancelled))
        emit(aiConfigEntry(cancelled))
        emit(workspacesEntry(cancelled))
        emit(containerEntry(cancelled))
    }.flowOn(Dispatchers.IO)

    /** App 私有目录所在存储卷的容量。 */
    fun deviceSpace(): DeviceSpace = runCatching {
        DeviceSpace(
            totalBytes = context.filesDir.totalSpace,
            availableBytes = context.filesDir.usableSpace
        )
    }.getOrDefault(DeviceSpace(0, 0))

    /** 执行一项清理，返回释放的字节数。 */
    suspend fun clean(kind: CleanupKind): Long = withContext(Dispatchers.IO) {
        when (kind) {
            CleanupKind.Caches -> clearDirContents(context.cacheDir) + clearDirContents(context.codeCacheDir)
            CleanupKind.Logs -> FileLogger.clearLogs() + AILogger.clearLogs()
            CleanupKind.ToolOutput -> clearDirContents(toolOutputStore.outputDir)
            CleanupKind.VisionSessions -> clearDirContents(visionSessionStore.sessionDir)
        }
    }

    // ── 各分类 ──────────────────────────────────────────────────────────────

    /** 聊天记录：整个 databases 目录（本项目只有一个 Room 库，WAL 的 -wal/-shm 也在其中）。 */
    private suspend fun chatEntry(): StorageEntry {
        val dbDir = context.getDatabasePath(AgentDatabase.DATABASE_NAME).parentFile
        val bytes = dbDir?.let { dirSize(it) } ?: 0L
        val details = runCatching { agentMessageDao.sessionStorageUsage(TOP_SESSIONS) }
            .getOrDefault(emptyList())
            .filter { it.bytes > 0 }
            .map { usage ->
                StorageDetail(
                    label = usage.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.storage_untitled_session),
                    bytes = usage.bytes,
                    note = context.getString(R.string.storage_chat_messages, usage.messageCount)
                )
            }
        return StorageEntry(StorageCategory.Chat, bytes, details)
    }

    /**
     * 容器：`filesDir/rootfs`（内置）与 `filesDir/rootfs_<profileId>`（自定义本地镜像）。
     *
     * 直接按目录扫而不是按 profile 列表扫，profile 删了但目录还在的残留也能露出来；profile 列表只用来取显示名。
     * 远程 SSH 的 profile 没有本地 rootfs，天然不会出现在这里。
     */
    private suspend fun containerEntry(cancelled: () -> Boolean): StorageEntry {
        val nameById = runCatching { containerSettingsRepository.customProfilesFlow.first() }
            .getOrDefault(emptyList())
            .associate { it.id to it.name }
        val dirs = context.filesDir.listFiles { f: File ->
            f.isDirectory && (f.name == ROOTFS_DIR || f.name.startsWith("${ROOTFS_DIR}_")) && f.name != IMAGES_DIR
        }?.sortedBy { it.name } ?: emptyList()

        val details = mutableListOf<StorageDetail>()
        var total = 0L
        var largest: Pair<File, Long>? = null
        dirs.forEach { dir ->
            val id = if (dir.name == ROOTFS_DIR) ContainerProfile.BUILTIN_ID else dir.name.removePrefix("${ROOTFS_DIR}_")
            val size = dirSize(dir, cancelled)
            total += size
            val label = nameById[id]
                ?: if (id == ContainerProfile.BUILTIN_ID) ContainerProfile.BUILTIN_ALPINE.name else dir.name
            details += StorageDetail(label = label, bytes = size, note = dir.name)
            if (size > (largest?.second ?: 0L)) largest = dir to size
        }

        // 占用最大的那个容器再下钻一层：容器里吃掉几个 G 的通常是 /usr 与 /root 下的构建缓存，
        // 只看总量看不出来。
        largest?.let { (dir, _) ->
            dir.listFiles { f: File -> f.isDirectory }
                ?.map { it.name to dirSize(it, cancelled) }
                ?.filter { it.second > 0 }
                ?.sortedByDescending { it.second }
                ?.take(TOP_SUBDIRS)
                ?.forEach { (name, size) ->
                    details += StorageDetail(label = "/$name", bytes = size, indent = true)
                }
        }
        return StorageEntry(StorageCategory.Container, total, details)
    }

    /** 容器镜像包：已下载或导入的 rootfs 压缩包，逐个文件列出。 */
    private fun imagesEntry(): StorageEntry {
        val dir = File(context.filesDir, IMAGES_DIR)
        val files = dir.listFiles { f: File -> f.isFile }?.sortedByDescending { it.length() } ?: emptyList()
        return StorageEntry(
            category = StorageCategory.ContainerImages,
            bytes = files.sumOf { it.length() },
            details = files.map { StorageDetail(label = it.name, bytes = it.length()) }
        )
    }

    /** 工作区：App 内置的 `filesDir/projects`，按项目列出，并标出其中的聊天附件。 */
    private fun workspacesEntry(cancelled: () -> Boolean): StorageEntry {
        val root = File(context.filesDir, PROJECTS_DIR)
        val projects = root.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val details = projects.map { project ->
            val attachments = dirSize(File(project, ATTACHMENTS_RELATIVE), cancelled)
            StorageDetail(
                label = project.name,
                bytes = dirSize(project, cancelled),
                note = attachments.takeIf { it > 0 }?.let {
                    context.getString(R.string.storage_workspace_attachments, formatStorageSize(it))
                }
            )
        }
        val looseFiles = root.listFiles { f: File -> f.isFile }?.sumOf { it.length() } ?: 0L
        return StorageEntry(StorageCategory.Workspaces, details.sumOf { it.bytes } + looseFiles, details)
    }

    /** AI 配置与产物：`filesDir/aicode`，按子目录拆开（工具输出与识图会话可清理）。 */
    private fun aiConfigEntry(cancelled: () -> Boolean): StorageEntry {
        val root = containerInstaller.aicodeDir
        val cleanableKeys = mapOf(
            toolOutputStore.outputDir.name to StorageDetailKey.TOOL_OUTPUT,
            visionSessionStore.sessionDir.name to StorageDetailKey.VISION_SESSIONS
        )
        val details = root.listFiles { f: File -> f.isDirectory }
            ?.map {
                StorageDetail(
                    label = it.name,
                    bytes = dirSize(it, cancelled),
                    key = cleanableKeys[it.name]
                )
            }
            ?.filter { it.bytes > 0 }
            ?.sortedByDescending { it.bytes }
            ?: emptyList()
        return StorageEntry(StorageCategory.AiConfig, dirSize(root, cancelled), details)
    }

    private fun checkpointsEntry(cancelled: () -> Boolean): StorageEntry =
        StorageEntry(StorageCategory.Checkpoints, dirSize(File(context.filesDir, CHECKPOINTS_DIR), cancelled))

    /**
     * 日志：直接问两个 logger 要文件清单，而不是猜目录——它们优先写外部私有目录，
     * 外部不可用时回退到内部，路径由 logger 自己决定。
     */
    private fun logsEntry(): StorageEntry {
        val appLogs = runCatching { FileLogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        val aiLogs = runCatching { AILogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        val details = buildList {
            if (appLogs > 0) add(StorageDetail(context.getString(R.string.storage_logs_app), appLogs))
            if (aiLogs > 0) add(StorageDetail(context.getString(R.string.storage_logs_ai), aiLogs))
        }
        return StorageEntry(StorageCategory.Logs, appLogs + aiLogs, details)
    }

    private fun cacheEntry(cancelled: () -> Boolean): StorageEntry =
        StorageEntry(
            category = StorageCategory.Caches,
            bytes = dirSize(context.cacheDir, cancelled) + dirSize(context.codeCacheDir, cancelled)
        )

    /** 其他数据：filesDir 里未被上述分类覆盖的部分（DataStore、背景图、字体、SSH 私钥等）加 shared_prefs。 */
    private fun otherDataEntry(cancelled: () -> Boolean): StorageEntry {
        val rest = context.filesDir.listFiles()?.filterNot { file ->
            file.name in CLASSIFIED_DIRS || file.name == ROOTFS_DIR || file.name.startsWith("${ROOTFS_DIR}_")
        }?.sumOf { dirSize(it, cancelled) } ?: 0L
        val prefs = context.filesDir.parentFile?.let { dirSize(File(it, PREFS_DIR), cancelled) } ?: 0L
        return StorageEntry(StorageCategory.OtherData, rest + prefs)
    }

    /** 应用安装包本体（base APK 与各 split）。 */
    private fun apkEntry(): StorageEntry {
        val info = context.applicationInfo
        val paths = (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()))
            .filterNotNull()
            .distinct()
        return StorageEntry(StorageCategory.Apk, paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) })
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 删除目录下所有内容但保留目录本身，返回释放字节数。 */
    private fun clearDirContents(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            val size = dirSize(child)
            if (runCatching { child.deleteRecursively() }.getOrDefault(false)) freed += size
        }
        return freed
    }

    companion object {
        private const val TAG = "StorageUsageScanner"

        private const val ROOTFS_DIR = "rootfs"
        private const val IMAGES_DIR = "rootfs_images"
        private const val PROJECTS_DIR = "projects"
        private const val CHECKPOINTS_DIR = "checkpoints"
        private const val AICODE_DIR = "aicode"
        private const val PREFS_DIR = "shared_prefs"
        private const val ATTACHMENTS_RELATIVE = ".aicode/attachments"

        /** 已被其它分类覆盖的 filesDir 子项，不再计入「其他数据」。 */
        private val CLASSIFIED_DIRS = setOf(
            PROJECTS_DIR, AICODE_DIR, IMAGES_DIR, CHECKPOINTS_DIR, "logs", "ai-logs"
        )

        private const val TOP_SESSIONS = 5
        private const val TOP_SUBDIRS = 5

        /**
         * 目录占用求和。
         *
         * 走 [Files.walkFileTree]（默认不跟随符号链接）：rootfs 与 node_modules 里到处是 symlink，
         * 跟随会重复计数甚至陷入环。单个条目读失败一律跳过，不让整次统计失败。
         */
        internal fun dirSize(target: File, cancelled: () -> Boolean = { false }): Long {
            if (!target.exists()) return 0L
            val path = target.toPath()
            if (runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)) return 0L
            if (target.isFile) return target.length()
            var total = 0L
            runCatching {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return if (cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                        if (cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                })
            }.onFailure { FileLogger.w(TAG, "统计目录占用失败: ${target.absolutePath}", it) }
            return total
        }
    }
}
