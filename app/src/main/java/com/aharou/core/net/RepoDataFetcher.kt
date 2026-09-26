package com.aharou.core.net

import android.content.Context
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 仓库远程数据拉取通用组件：
 * 专门从本仓库（或分支/Tag）拉取 JSON/文本静态数据（如供应商预设、模型列表等）。
 *
 * 节点调度与高可用策略：
 * 1. 优先使用 Fastly CDN / jsDelivr 等公共加速节点（国内直连访问速度快、免翻墙）；
 * 2. 依次降级到其他 CDN 镜像；
 * 3. 最终回退直连 GitHub raw 源站兜底；
 * 4. 内置本地磁盘持久化缓存（TTL）与轻量 ETag / If-None-Match 条件请求（防无脑重复下载）；
 * 5. 网络全挂时优雅回退本地已有磁盘缓存或内置 assets。
 */
class RepoDataFetcher(
    private val context: Context,
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val defaultMaxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS,
    private val client: OkHttpClient = DEFAULT_CLIENT
) {

    /**
     * 单个数据文件的拉取结果
     */
    sealed class FetchResult {
        /** 从网络拉取成功或 304 命中有效缓存 */
        data class Success(val content: String, val fromCache: Boolean) : FetchResult()
        /** 网络请求失败但成功读取本地磁盘缓存 */
        data class FallbackDiskCache(val content: String, val error: Throwable) : FetchResult()
        /** 彻底失败（无本地缓存可用） */
        data class Failure(val error: Throwable) : FetchResult()
    }

    /**
     * 获取数据：若本地缓存未过期直接使用；若已过期则按节点优先级测活并拉取，支持 304 与磁盘兜底。
     *
     * @param pathInRepo 仓库相对路径，如 "data/providers.json"
     * @param maxAgeMs 缓存有效期（毫秒），不传则取构造方法设定的 [defaultMaxAgeMs]；传 0L 则跳过本地有效期检查强制网络对账
     */
    suspend fun fetch(
        pathInRepo: String,
        maxAgeMs: Long = defaultMaxAgeMs
    ): FetchResult = withContext(Dispatchers.IO) {
        val cleanPath = pathInRepo.trim().removePrefix("/")
        val cacheFile = getCacheFile(cleanPath)
        val etagFile = getEtagFile(cleanPath)

        val now = System.currentTimeMillis()
        val cachedContent = if (cacheFile.isFile) runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull() else null
        val isCacheFresh = cachedContent != null && (now - cacheFile.lastModified() < maxAgeMs)

        // 1. 本地缓存仍在有效期内，直接返回
        if (isCacheFresh) {
            return@withContext FetchResult.Success(cachedContent!!, fromCache = true)
        }

        val cachedEtag = if (etagFile.isFile) runCatching { etagFile.readText(Charsets.UTF_8).trim() }.getOrNull() else null
        val candidateUrls = buildCandidateUrls(cleanPath)

        var lastError: Throwable? = null

        // 2. 依次遍历候选节点
        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "aharou-android")
                    .get()

                if (!cachedEtag.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", cachedEtag)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    when {
                        // 304 未修改：原缓存依旧有效，更新文件修改时间后直接返回
                        response.code == 304 && cachedContent != null -> {
                            cacheFile.setLastModified(now)
                            return@withContext FetchResult.Success(cachedContent, fromCache = true)
                        }

                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                // 写入磁盘缓存
                                runCatching {
                                    cacheFile.parentFile?.mkdirs()
                                    cacheFile.writeText(body, Charsets.UTF_8)
                                    val newEtag = response.header("ETag")
                                    if (!newEtag.isNullOrBlank()) {
                                        etagFile.writeText(newEtag.trim(), Charsets.UTF_8)
                                    } else {
                                        etagFile.delete()
                                    }
                                }
                                return@withContext FetchResult.Success(body, fromCache = false)
                            }
                        }

                        else -> {
                            val errCode = response.code
                            FileLogger.d(TAG, "节点请求失败 url=$url code=$errCode")
                        }
                    }
                }
            } catch (e: Throwable) {
                lastError = e
                FileLogger.d(TAG, "节点连接异常 url=$url: ${e.message}")
            }
        }

        // 3. 所有节点均不可用时：如果磁盘上有旧缓存，降级使用旧缓存
        if (cachedContent != null) {
            FileLogger.w(TAG, "所有网络节点拉取失败，降级使用磁盘旧缓存: $cleanPath", lastError)
            return@withContext FetchResult.FallbackDiskCache(
                cachedContent,
                lastError ?: IllegalStateException("All remote endpoints failed")
            )
        }

        // 4. 彻底失败
        FetchResult.Failure(lastError ?: IllegalStateException("Failed to fetch $cleanPath from all sources"))
    }

    /**
     * 生成候选节点 URL 列表：
     * 1. jsDelivr (Fastly CDN 加速)
     * 2. jsDelivr (通用 CDN)
     * 3. raw.githubusercontent.com (GitHub 官方直连兜底)
     */
    fun buildCandidateUrls(path: String): List<String> = listOf(
        // Fastly 加速的 jsDelivr 节点
        "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // 通用 CDN 镜像
        "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // jsDelivr GCore 节点
        "https://gcore.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // GitHub 源站兜底
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
    )

    /**
     * 同步读取磁盘缓存（不发网络）：存在则返回文本，否则 null。
     * 供「纯只读链路」复用统一缓存目录，避免调用方硬编码缓存路径。
     */
    fun readLocalCache(pathInRepo: String): String? {
        val file = getCacheFile(pathInRepo.trim().removePrefix("/"))
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun clearCache(pathInRepo: String? = null) {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) return
        if (pathInRepo == null) {
            dir.deleteRecursively()
        } else {
            val cleanPath = pathInRepo.trim().removePrefix("/")
            getCacheFile(cleanPath).delete()
            getEtagFile(cleanPath).delete()
        }
    }

    private fun getCacheFile(path: String): File {
        val safeName = path.replace('/', '_')
        return File(File(context.filesDir, CACHE_DIR_NAME), safeName)
    }

    private fun getEtagFile(path: String): File {
        val safeName = path.replace('/', '_') + ".etag"
        return File(File(context.filesDir, CACHE_DIR_NAME), safeName)
    }

    companion object {
        private const val TAG = "RepoDataFetcher"
        private const val CACHE_DIR_NAME = "repo_data_cache"

        const val DEFAULT_OWNER = "jieapi"
        const val DEFAULT_REPO = "aicode"
        const val DEFAULT_BRANCH = "main"

        /** 默认缓存 12 小时 */
        const val DEFAULT_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
