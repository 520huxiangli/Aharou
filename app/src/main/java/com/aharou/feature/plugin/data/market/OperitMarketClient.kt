package com.aharou.feature.plugin.data.market

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Operit 插件市场的数据入口。
 *
 * 行为对齐 AAswordman/Operit（LGPL-3.0）的 `data/api/MarketStatsApiService.kt`：
 * 列表走 `market/v2/lists/...` 静态分页、分类清单走 `market/v2/manifest.json`、
 * 分页按 `totalPages` 停止、404 当作分页结束。改动：只保留浏览所需的最小集合；
 * 不接 `api.operit.app` 的下载统计与鉴权接口，避免把用户的下载行为上报给第三方。
 *
 * 缓存落应用私有目录而不是 Room：市场数据是只读快照，重建成本只有一次请求，
 * 没必要为它维护迁移。
 */
@Singleton
class OperitMarketClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mirror: OperitMirrorIndex,
    client: OkHttpClient
) {
    private val http = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cache(null)
        .build()

    /** 镜像专用超时：CNB 正常不到一秒返回，卡住就尽快回退上游，别把用户的等待时间叠成两份。 */
    private val mirrorHttp = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .cache(null)
        .build()

    /** 下载单独一套超时：GitHub Release 直链在国内常被挂住不发数据，连接与读超时卡住即可；
     *  不设整体 callTimeout，慢网络下 16 MB 的包也能流完。 */
    private val downloadHttp = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cache(null)
        .build()

    private val cacheRoot = File(context.filesDir, CACHE_DIR)

    suspend fun loadManifest(forceRefresh: Boolean = false): List<OperitMarketCategoryOption> =
        withContext(Dispatchers.IO) {
            val body = File(cacheRoot, "manifest.json")
            val meta = File(cacheRoot, "manifest.meta.json")
            if (!forceRefresh && body.isFile && meta.isFile && isFresh(readFetchedAt(meta))) {
                readCachedManifest(body)?.let { return@withContext it }
            }
            val bytes = try {
                fetchMarketBytes(MANIFEST_PATH, MAX_PAGE_BYTES, preferDirect = forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 分类清单拿不到不影响浏览，调用方会退回已加载条目里的分类
                readCachedManifest(body)?.let { return@withContext it }
                throw OperitMarketException("读取 Operit 市场分类失败：${e.message}", e)
            }
            val categories = parseManifest(bytes)
            writeCache(body, bytes)
            runCatching { meta.writeText(System.currentTimeMillis().toString()) }
            categories
        }

    suspend fun loadPage(
        scope: OperitMarketListScope,
        page: Int,
        forceRefresh: Boolean = false,
        expectedGeneratedAt: String? = null
    ): OperitMarketPageResult = withContext(Dispatchers.IO) {
        if (page < 1) throw OperitMarketException("页码非法：$page")
        val body = File(cacheRoot, "page-${scope.cacheKey}-$page.json")
        val metaFile = File(cacheRoot, "page-${scope.cacheKey}-$page.meta.json")
        val cached = readCachedPage(body, metaFile, page)
        if (!forceRefresh && cached != null &&
            isFresh(cached.second.fetchedAt, expectedGeneratedAt, cached.second.generatedAt)
        ) {
            return@withContext OperitMarketPageResult.Loaded(cached.first)
        }

        val url = listUrl(scope, page)
        val bytes = try {
            fetchMarketBytes(url, MAX_PAGE_BYTES, preferDirect = forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OperitMarketHttpNotFoundException) {
            // 市场快照会整体换代，旧页号可能直接消失；有缓存就继续给旧内容，没有才当作结尾
            if (cached != null) return@withContext OperitMarketPageResult.Loaded(cached.first.copy(stale = true))
            if (page == 1) throw OperitMarketException("Operit 市场首页不存在（HTTP 404）", e)
            return@withContext OperitMarketPageResult.End
        } catch (e: Exception) {
            if (cached != null) {
                FileLogger.w(TAG, "读取 Operit 市场失败，使用本地缓存: ${scope.cacheKey} page=$page", e)
                return@withContext OperitMarketPageResult.Loaded(cached.first.copy(stale = true))
            }
            throw OperitMarketException("读取 Operit 市场失败：${e.message}", e)
        }

        val parsed = parsePage(bytes, page)
        val batch = parsed.toBatch(requestedPage = page, stale = false)
        writeCache(body, bytes)
        runCatching { metaFile.writeText(OperitMarketJson.encodeToString(batch.meta())) }
        OperitMarketPageResult.Loaded(batch)
    }

    /**
     * Operit2 官方插件：56 个包由我们自己的 CNB 镜像分发（上游在 GitHub Release 上，
     * 国内拉取极慢）。索引一次取全量，不分页；下载地址由 [assetBase] + 包路径拼出。
     */
    suspend fun loadOperit2(forceRefresh: Boolean = false): OperitMarketPageResult =
        withContext(Dispatchers.IO) {
            val cache = File(cacheRoot, "operit2-index.json")
            val cachedAt = if (cache.isFile) cache.lastModified() else 0L
            val fresh = !forceRefresh && cache.isFile &&
                System.currentTimeMillis() - cachedAt < OPERIT2_CACHE_TTL_MS
            val bytes = if (fresh) {
                runCatching { cache.readBytes() }.getOrNull()
            } else null
            val payload = bytes ?: try {
                getBytes(OPERIT2_INDEX_URL, MAX_PAGE_BYTES).also { cache.writeBytes(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stale = runCatching { cache.readBytes() }.getOrNull()
                if (stale != null) {
                    FileLogger.w(TAG, "Operit2 索引读取失败，使用本地缓存", e)
                    stale
                } else {
                    throw OperitMarketException("读取 Operit2 插件索引失败：${e.message}", e)
                }
            }

            val root = OperitMarketJson.parseToJsonElement(payload.decodeToString()).jsonObject
            val entries = root["items"]?.jsonArray ?: return@withContext OperitMarketPageResult.End
            val listings = entries.mapNotNull { element ->
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val assetName = item["assetName"]?.jsonPrimitive?.contentOrNull
                val path = item["path"]?.jsonPrimitive?.contentOrNull
                val available = item["available"]?.jsonPrimitive?.booleanOrNull ?: true
                val asset = if (available && assetName != null && path != null) {
                    OperitMarketAsset(
                        name = assetName,
                        url = assetBase(path),
                        sha256 = item["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        versionId = "operit2:$id"
                    )
                } else null
                OperitMarketListing(
                    id = id,
                    title = item["title"]?.jsonPrimitive?.contentOrNull ?: id,
                    description = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    detail = item["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    type = OperitMarketType.PACKAGE,
                    categoryId = item["category"]?.jsonPrimitive?.contentOrNull,
                    author = item["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    version = null,
                    downloadCount = 0,
                    asset = asset
                )
            }
            OperitMarketPageResult.Loaded(
                OperitMarketBatch(
                    listings = listings,
                    page = 1,
                    pageSize = listings.size,
                    total = listings.size,
                    totalPages = 1,
                    generatedAt = root["generatedAt"]?.jsonPrimitive?.contentOrNull,
                    fetchedAt = System.currentTimeMillis(),
                    stale = fresh
                )
            )
        }

    /**
     * 先直连；直连失败时用 gh-proxy 前缀重试一次（与 `RepoDataFetcher` 同一写法）。
     * 校验不通过就丢弃整份数据，不写任何落盘文件，调用方拿不到半成品。
     */
    suspend fun download(asset: OperitMarketAsset): OperitMarketDownload = withContext(Dispatchers.IO) {
        val url = asset.url.trim()
        if (url.isEmpty()) throw OperitMarketNoAssetException()
        if (!url.startsWith("https://")) throw OperitMarketException("不安全的下载地址：$url")

        // 先走 CNB 镜像：同一份文件在 CNB 上通常一瞬间就好，拿不到或校验不过再回退 GitHub
        mirror.resolveUrl(asset.name)?.let { mirrorUrl ->
            try {
                val mirrored = getBytes(mirrorUrl, MAX_PACKAGE_BYTES, downloadHttp)
                verifyChecksum(mirrored, asset)
                FileLogger.i(TAG, "从 CNB 镜像下载: ${asset.name}")
                return@withContext OperitMarketDownload(bytes = mirrored, assetName = asset.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OperitMarketTooLargeException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "CNB 镜像下载失败，回退 GitHub: ${asset.name}", e)
            }
        }

        val bytes = try {
            getBytes(url, MAX_PACKAGE_BYTES, downloadHttp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OperitMarketTooLargeException) {
            throw e
        } catch (e: Exception) {
            if (!isGitHubHost(url)) throw e
            FileLogger.w(TAG, "GitHub 直连失败，改用 gh-proxy: $url", e)
            getBytes(GITHUB_PROXY_PREFIX + url, MAX_PACKAGE_BYTES, downloadHttp)
        }
        verifyChecksum(bytes, asset)
        OperitMarketDownload(bytes = bytes, assetName = asset.name)
    }

    private fun verifyChecksum(bytes: ByteArray, asset: OperitMarketAsset) {
        val expected = asset.sha256.trim().lowercase()
        // Operit 在哈希不符时直接删文件报错；我们连内存里的字节都不再往外传
        if (expected.isEmpty()) throw OperitMarketException("市场没有提供该文件的 SHA-256，无法校验")
        val actual = sha256(bytes)
        if (actual != expected) throw OperitMarketChecksumException(expected, actual)
    }

    private fun parseManifest(bytes: ByteArray): List<OperitMarketCategoryOption> {
        val parsed = try {
            OperitMarketJson.decodeFromString<OperitMarketManifest>(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw OperitMarketException("Operit 市场分类清单无法解析", e)
        }
        if (!parsed.ok) throw OperitMarketException("Operit 市场分类清单返回 ok=false")
        return parsed.toCategories()
    }

    private fun parsePage(bytes: ByteArray, page: Int): OperitMarketPage {
        val parsed = try {
            OperitMarketJson.decodeFromString<OperitMarketPage>(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // 404 页、反代错误页都是 HTML，按 JSON 解析会失败；这里必须报错而不是当成空列表
            throw OperitMarketException("Operit 市场返回的内容无法解析（第 $page 页）", e)
        }
        if (!parsed.ok) throw OperitMarketException("Operit 市场返回 ok=false（第 $page 页）")
        return parsed
    }

    private fun OperitMarketPage.toBatch(requestedPage: Int, stale: Boolean): OperitMarketBatch {
        val resolvedPage = page.takeIf { it > 0 } ?: requestedPage
        val resolvedSize = pageSize.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE
        val resolvedTotal = total.coerceAtLeast(0)
        return OperitMarketBatch(
            listings = items.orEmpty().mapNotNull { it.toListing() },
            page = resolvedPage,
            pageSize = resolvedSize,
            total = resolvedTotal,
            totalPages = if (resolvedTotal == 0) 1 else (resolvedTotal + resolvedSize - 1) / resolvedSize,
            generatedAt = generatedAt,
            fetchedAt = System.currentTimeMillis(),
            stale = stale
        )
    }

    private fun readCachedManifest(body: File): List<OperitMarketCategoryOption>? {
        if (!body.isFile) return null
        return runCatching { parseManifest(body.readBytes()) }.getOrNull()
    }

    private fun readCachedPage(
        body: File,
        metaFile: File,
        page: Int
    ): Pair<OperitMarketBatch, OperitMarketCacheMeta>? {
        if (!body.isFile || !metaFile.isFile) return null
        val meta = runCatching {
            OperitMarketJson.decodeFromString<OperitMarketCacheMeta>(metaFile.readText())
        }.getOrNull() ?: return null
        val parsed = runCatching { parsePage(body.readBytes(), page) }.getOrNull() ?: return null
        val batch = parsed.toBatch(requestedPage = page, stale = false).copy(fetchedAt = meta.fetchedAt)
        return batch to meta
    }

    private fun OperitMarketBatch.meta() = OperitMarketCacheMeta(
        fetchedAt = fetchedAt,
        generatedAt = generatedAt,
        pageSize = pageSize,
        total = total
    )

    private fun readFetchedAt(meta: File): Long =
        runCatching { meta.readText().trim().toLong() }.getOrDefault(0L)

    private fun isFresh(fetchedAt: Long, expectedGeneratedAt: String? = null, generatedAt: String? = null): Boolean {
        if (System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS) return false
        // 市场每个分页文件是独立生成的快照，generatedAt 对不上就说明跨了换代，宁可重拉
        if (expectedGeneratedAt != null && generatedAt != null && generatedAt != expectedGeneratedAt) {
            return false
        }
        return true
    }

    private fun writeCache(file: File, bytes: ByteArray) {
        runCatching {
            cacheRoot.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(bytes)
            temp.renameTo(file)
        }.onFailure { FileLogger.w(TAG, "写入 Operit 市场缓存失败: ${file.name}", it) }
    }

    private fun listUrl(scope: OperitMarketListScope, page: Int): String {
        val category = scope.categoryId
        return if (category.isNullOrBlank()) {
            "lists/all/$SORT/page-$page.json"
        } else {
            "lists/category/${category.lowercase()}/$SORT/page-$page.json"
        }
    }

    /**
     * 先问 CNB 镜像，再回退上游静态站。
     *
     * 上游 static.operit.app 在国内时常几十秒才超时，镜像每天 04:17 由定时任务同步一次。
     * 镜像缺失（新页还没同步）或不可用时安静回退，404 与网络错误的语义仍由上游那一侧决定，
     * 调用方的缓存与分页收尾逻辑不受影响。
     *
     * [preferDirect] 用于用户主动刷新：先试着直连拿最新，失败再退回镜像的隔夜快照，
     * 不让刷新按钮变成「只能看昨天的数据」。
     */
    private fun fetchMarketBytes(
        path: String,
        maxBytes: Long,
        preferDirect: Boolean = false
    ): ByteArray {
        if (preferDirect) {
            try {
                return getBytes("$BASE_URL/$path", maxBytes, mirrorHttp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OperitMarketHttpNotFoundException) {
                throw e
            } catch (e: Exception) {
                FileLogger.i(TAG, "上游刷新失败，回退镜像: $path")
            }
        }
        try {
            return getBytes("$MIRROR_BASE/$path", maxBytes, mirrorHttp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.i(TAG, "CNB 镜像不可用，回退上游: $path")
        }
        return getBytes("$BASE_URL/$path", maxBytes)
    }

    private fun getBytes(url: String, maxBytes: Long, client: OkHttpClient = http): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw OperitMarketHttpNotFoundException(url)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
            val body = response.body ?: throw IOException("空响应: $url")
            val declared = body.contentLength()
            if (declared > maxBytes) throw OperitMarketTooLargeException(maxBytes)
            val stream = body.byteStream()
            val output = ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 8192)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw OperitMarketTooLargeException(maxBytes)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun isGitHubHost(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() == "github.com" }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class OperitMarketHttpNotFoundException(url: String) : IOException("HTTP 404: $url")

    @Serializable
    private data class OperitMarketCacheMeta(
        val fetchedAt: Long = 0,
        val generatedAt: String? = null,
        val pageSize: Int = 0,
        val total: Int = 0
    )

    companion object {
        private const val BASE_URL = "https://static.operit.app/market/v2"

        /** Operit2 官方插件镜像（我们自己的 CNB 仓库，带许可与出处说明）。 */
        private const val OPERIT2_INDEX_URL =
            "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/mirror/operit2/index.json"
        private const val OPERIT2_RAW_BASE =
            "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/"
        private const val OPERIT2_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        private fun assetBase(path: String): String = OPERIT2_RAW_BASE + path.trimStart('/')

        /** 市场列表快照的 CNB 镜像，由 `aharou-plugins` 仓库的定时任务同步。 */
        private const val MIRROR_BASE =
            "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/mirror/operit-market"
        private const val MANIFEST_PATH = "manifest.json"

        /** 安装来源里记录的地址；也是本模块唯一对外暴露的市场入口。 */
        const val INDEX_URL = "$BASE_URL/lists/all/downloads"

        /** 与 `RepoDataFetcher` / `SkillRepoAccess` 用的是同一个反代前缀，失败语义也一致。 */
        private const val GITHUB_PROXY_PREFIX = "https://gh-proxy.com/"
        private const val TAG = "OperitMarketClient"
        private const val USER_AGENT = "aharou-operit-market"
        private const val CACHE_DIR = "operit-market-cache"

        /** 排序与任务约定一致：下载量。Operit 还支持 updated / likes，这里不需要。 */
        private const val SORT = "downloads"

        /** 单页实测最大约 750 KiB；留到 2 MiB 足够容纳市场扩容。 */
        private const val MAX_PAGE_BYTES = 2L * 1024 * 1024

        /** 与 Operit 导入器共用的包体积上限。 */
        private const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024

        private const val DEFAULT_PAGE_SIZE = 100

        /** 市场数据每天只更新几次，6 小时缓存足够，失败时会退回旧缓存。 */
        private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    }
}
