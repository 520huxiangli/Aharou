package com.aharou.feature.agent.domain.container

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连上 SSH 后把内置资源铺到远端：文档、内置子代理与团队定义。
 *
 * 远程模式下这些路径以远端为准，不铺过去就是空的——AI 读不到文档、内置 Explore 子代理不存在。
 * 原先这段挂在「全局模式切到远程」的回调里，但容器改成可按工作区指定后，那个回调不一定会触发
 * （全局仍可能是本地），于是走 SSH 的工作区连上去后 `~/.aharou` 下只有 `workspaces`。
 *
 * 每个连接只铺一次（按 host:port+用户名去重）：铺资源是若干次 SFTP 上传，不能每条命令都做一遍。
 */
@Singleton
class RemoteBootstrapSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connection: RemoteSshConnection
) {
    private companion object {
        const val TAG = "RemoteBootstrapSync"
        /** 共享区推送到远程的单文件上限：超过的通常是构建产物（APK 几十 MB），推过去无意义。 */
        const val SHARED_MAX_BYTES = 1L * 1024 * 1024
    }

    /** 已铺过的目标（host:port:user）；连接换了对端会重新铺。 */
    @Volatile
    private var syncedKey: String? = null

    private val lock = Any()

    /** 连接就绪后调用；同一目标只实际铺一次。失败只记日志，不影响命令执行。 */
    suspend fun syncIfNeeded() {
        val cfg = connection.config ?: return
        val key = "${cfg.host}:${cfg.port}:${cfg.username}"
        if (syncedKey == key) return

        val assets = context.assets
        runCatching {
            val docs = linkedMapOf<String, String>()
            collectAssetDocs(assets, "docs", "", docs)
            connection.uploadDocs(docs)
        }.onFailure { FileLogger.w(TAG, "同步内置文档到远程失败：$key", it) }

        runCatching {
            connection.uploadBuiltinFiles("agents", collectAssetBytes(assets, "agents"))
            connection.uploadBuiltinFiles("teams", collectAssetBytes(assets, "teams"))
            // 技能是目录结构（<name>/SKILL.md + 附属脚本），用带相对路径的名字传，
            // 否则远端 ~/.aharou/skills 为空，`loadSkill` 在远程工作区一个都找不到。
            connection.uploadBuiltinFiles("skills", collectAssetBytesRecursive(assets, "skills"))
        }.onFailure { FileLogger.w(TAG, "释放内置子代理/团队/技能到远程失败：$key", it) }

        // 共享区：远程模式下 shell 也访问不到 `~/shared`（它在手机上）。把**小文件**推过去
        // （信箱、待办、交接文档这些真要跨区共享的），大文件如 APK 跳过——
        // 共享区可能有几十 MB 构建产物，全量推既慢又会吃满服务器磁盘。
        runCatching {
            val sharedFiles = collectSharedFiles()
            if (sharedFiles.isNotEmpty()) connection.uploadBuiltinFiles("shared", sharedFiles, rootDir = "")
            FileLogger.i(TAG, "共享区小文件已同步 ${sharedFiles.size} 个（跳过 >1MB 的）")
            // 推送后立即回拉：远端可能也有别人/自己新写的（信箱、待办），只推不拉会让那些内容
            // 永远回不到手机，App 也不会投递，下次连上还会被本地旧版本盖掉。
            sharedDir()?.let { connection.pullSharedFiles(it) }
        }.onFailure { FileLogger.w(TAG, "同步共享区到远程失败：$key", it) }

        synchronized(lock) { syncedKey = key }
        FileLogger.i(TAG, "远程内置资源已同步：$key")
    }

    /** 递归收集 assets 下某目录的文本文件（相对路径 → 内容）。 */
    private fun collectAssetDocs(
        assets: android.content.res.AssetManager,
        dir: String,
        prefix: String,
        out: MutableMap<String, String>
    ) {
        val entries = runCatching { assets.list(dir).orEmpty() }.getOrDefault(emptyArray())
        if (entries.isEmpty()) {
            // 叶子：dir 本身就是文件完整路径，prefix 只用做写入用的相对路径。
            runCatching {
                assets.open(dir).use { out[prefix] = it.readBytes().decodeToString() }
            }.onFailure { FileLogger.w(TAG, "读取内置文档失败: $dir", it) }
            return
        }
        entries.forEach { entry ->
            collectAssetDocs(assets, "$dir/$entry", if (prefix.isEmpty()) entry else "$prefix/$entry", out)
        }
    }

    /** 收集 assets 目录下的扁平文件（name → 字节）。 */
    private fun collectAssetBytes(
        assets: android.content.res.AssetManager,
        assetDir: String
    ): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        assets.list(assetDir)?.forEach { entry ->
            runCatching { assets.open("$assetDir/$entry").use { out[entry] = it.readBytes() } }
                .onFailure { FileLogger.w(TAG, "读取内置资源失败: $assetDir/$entry", it) }
        }
        return out
    }

    /** 递归收集（相对路径 → 字节），用于技能这类带子目录的资源。 */
    private fun collectAssetBytesRecursive(
        assets: android.content.res.AssetManager,
        assetDir: String,
        prefix: String = "",
        out: MutableMap<String, ByteArray> = linkedMapOf()
    ): Map<String, ByteArray> {
        val entries = runCatching { assets.list(assetDir).orEmpty() }.getOrDefault(emptyArray())
        if (entries.isEmpty()) {
            if (prefix.isNotEmpty()) {
                runCatching { assets.open(assetDir).use { out[prefix] = it.readBytes() } }
                    .onFailure { FileLogger.w(TAG, "读取内置资源失败: $assetDir", it) }
            }
            return out
        }
        entries.forEach { entry ->
            collectAssetBytesRecursive(assets, "$assetDir/$entry", if (prefix.isEmpty()) entry else "$prefix/$entry", out)
        }
        return out
    }

    /**
     * 收集共享区里值得推到远程的小文件（相对路径 → 字节）。
     *
     * 只收 <= [SHARED_MAX_BYTES] 的普通文件：信箱/待办/交接文档都是几 KB，而构建产物动辄几十 MB。
     * 跳过大文件是刻意取舍——远程真正需要的是「能读到别人留的话」，不是把安装包搬过去。
     */
    private fun collectSharedFiles(): Map<String, ByteArray> {
        val root = sharedDir() ?: return emptyMap()
        val out = linkedMapOf<String, ByteArray>()
        runCatching {
            root.walkTopDown()
                .onEnter { dir -> dir.name != ".git" }
                .filter { it.isFile && it.length() in 1..SHARED_MAX_BYTES }
                .forEach { file ->
                    runCatching { out[file.relativeTo(root).path] = file.readBytes() }
                        .onFailure { FileLogger.w(TAG, "读取共享区文件失败: ${file.name}", it) }
                }
        }.onFailure { FileLogger.w(TAG, "遍历共享区失败", it) }
        return out
    }

    /** 共享区根目录：与容器内 `/root/shared` 指向同一个目录（见 LinuxContainerEngine 的绑定挂载）。 */
    private fun sharedDir(): java.io.File? =
        java.io.File(context.filesDir, "aharou-global/shared").takeIf { it.isDirectory }
}
