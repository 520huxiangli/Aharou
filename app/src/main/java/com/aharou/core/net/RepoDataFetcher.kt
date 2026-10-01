package com.aharou.core.net

import android.content.Context
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

                // maxAgeMs == 0（强制对账）时不带 If-None-Match：CDN 可能因自身上游副本
                // 未刷新，拿旧 ETag 比出自家人为"未修改"而回 304，让我们继续沿用旧内容，
                // 与强制对账的意图相反。强制对账就一定要拿全量正文。
                if (maxAgeMs > 0 && !cachedEtag.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", cachedEtag)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    when {
                        // 304 未修改：原缓存依旧有效，更新文件修改时间后直接返回
                        response.code == 304 && cachedContent != null -> {
                            FileLogger.d(TAG, "304 命中缓存 url=$url")
                            cacheFile.setLastModified(now)
                            return@withContext FetchResult.Success(cachedContent, fromCache = true)
                        }

                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            // 中间镜像可能返回截断正文：实测 ghproxy.net 对 5MB 的 models.json
                            // 只回 1/10 而状态码仍是 200。正文不完整就继续试下一个节点——
                            // 一旦返回，调用方不会再去别的源。
                            if (body.isNotBlank() && (!cleanPath.endsWith(".json") || isParsableJson(body))) {
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
                                FileLogger.d(TAG, "拉取成功 $cleanPath url=$url chars=${body.length}")
                                return@withContext FetchResult.Success(body, fromCache = false)
                            }
                            FileLogger.d(TAG, "节点正文不可用（截断/空），继续试下一个 url=$url chars=${body.length}")
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
     * 生成候选节点 URL 列表，按「仓库改动后多久能拿到」排序：
     *
     * 1. gh-proxy / ghproxy.net：透传 GitHub raw，cache-control 只有 60 秒 / 5 分钟，
     *    仓库里改了清单很快就能拿到；
     * 2. jsDelivr 各节点：内容本身还好，但它对「分支名 → commit」的解析要缓 12 小时
     *    （s-maxage=43200），而 purge 只能清内容那层，于是改了分支后长时间拉到的
     *    仍是旧内容——只作兜底；
     * 3. raw.githubusercontent.com：无缓存但国内常不通，放最后。
     *
     * 顺序即优先级：第一个返回非空正文的节点即被采用（不比较内容新旧）。
     */
    fun buildCandidateUrls(path: String): List<String> {
        val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        return listOf(
            "https://gh-proxy.com/$raw",
            "https://ghproxy.net/$raw",
            "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://gcore.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            raw,
        )
    }

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

    /** JSON 文件的内容完整性校验：能解析才采纳，防中间镜像返回截断正文。 */
    private fun isParsableJson(text: String): Boolean =
        runCatching { Json.parseToJsonElement(text) }.isSuccess

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

        const val DEFAULT_OWNER = "520huxiangli"
        const val DEFAULT_REPO = "Aharou"
        const val DEFAULT_BRANCH = "master"

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
