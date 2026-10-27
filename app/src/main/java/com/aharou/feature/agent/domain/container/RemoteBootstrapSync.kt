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
        }.onFailure { FileLogger.w(TAG, "释放内置子代理/团队到远程失败：$key", it) }

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
}
