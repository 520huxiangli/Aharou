package com.aharou.feature.plugin.domain

import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.plugin.data.PluginDevPackageBuilder
import com.aharou.feature.plugin.data.PluginPackageReader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 把已安装插件发布到官方索引仓库。
 *
 * 走容器内的 git：凭据由用户在「凭据与署名」里配好，经 credential helper 自动带上，
 * 这里不接触 token 明文。仓库没有写权限时（其他用户）push 会被拒，返回值里说明原因。
 */
@Singleton
class PluginPublisher @Inject constructor(
    private val engine: CommandEngine,
    private val installer: ContainerInstaller,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 发布结果。[submissionZip] 非空表示没推上去、已导出成可提交的压缩包，
     * UI 应给出分享入口而不是只显示一句话。
     */
    data class PublishResult(
        val success: Boolean,
        val message: String,
        val submissionZip: java.io.File? = null
    )

    suspend fun publish(pluginId: String, displayName: String = ""): PublishResult = withContext(Dispatchers.IO) {
        val pluginDir = File(File(installer.aharouDir, PUBLISH_PLUGINS), pluginId)
        val manifestFile = File(pluginDir, PluginPackageReader.MANIFEST_FILE)
        if (!pluginDir.isDirectory || !manifestFile.isFile) {
            return@withContext PublishResult(false, "插件没有安装或清单文件缺失")
        }
        val manifest = (PluginManifestParser.parse(manifestFile.readText(), PluginApi.VERSION) as? ManifestParseResult.Success)
            ?.manifest ?: return@withContext PublishResult(false, "插件清单无法解析")
        if (!manifest.repository.startsWith("https://github.com/")) {
            return@withContext PublishResult(false, "清单里的 repository 必须是 GitHub 地址")
        }

        val version = manifest.version.toString()
        val workRoot = File(File(installer.aharouDir, "tmp"), "publish")
        val repoDir = File(workRoot, "repo")
        val archive = File(workRoot, "${pluginId}_$version.zip")
        try {
            workRoot.mkdirs()
            repoDir.deleteRecursively()
            PluginDevPackageBuilder.zip(pluginDir, archive)

            val repoPath = installer.containerPathFor(repoDir)
            val clone = engine.runCommandSyncWithExit(
                "rm -rf ${shellQuote(repoPath)} && git clone $INDEX_REPO ${shellQuote(repoPath)}",
                timeoutMs = CLONE_TIMEOUT_MS,
            )
            if (clone.exitCode != 0) {
                return@withContext PublishResult(false, "拉取索引仓库失败：${clone.output.takeLast(400)}")
            }

            val indexPath = File(File(repoDir, "plugins"), "index.json")
            if (!indexPath.isFile) return@withContext PublishResult(false, "索引仓库结构异常：缺少 plugins/index.json")
            writeIndexEntry(indexPath, manifest, version, displayName)

            val packagePath = "$PACKAGES_DIR/$pluginId/$version.zip"
            val commit = engine.runCommandSyncWithExit(
                buildString {
                    append("cd ${shellQuote(repoPath)} && ")
                    append("mkdir -p ${shellQuote("$PACKAGES_DIR/$pluginId")} && ")
                    append("cp ${shellQuote(installer.containerPathFor(archive))} ${shellQuote(packagePath)} && ")
                    append("git add -A && ")
                    append("git -c user.name=aharou -c user.email=aharou@local ")
                    append("commit -m ${shellQuote("feat: 上传插件 $pluginId $version")} && ")
                    append("git push origin HEAD:main")
                },
                timeoutMs = PUSH_TIMEOUT_MS,
            )
            if (commit.exitCode != 0) {
                // 没有索引仓库写权限时不当作失败：导出成可提交的压缩包，
                // 由维护者审核后合入，比让用户对着一句「没有权限」无从下手好。
                if (isPermissionDenied(commit.output)) {
                    val submission = writeSubmission(pluginDir, manifest, version, displayName)
                    return@withContext PublishResult(
                        success = false,
                        message = "已导出提交包，可发给维护者审核：${submission.name}",
                        submissionZip = submission
                    )
                }
                return@withContext PublishResult(false, pushFailureHint(commit.output))
            }
            FileLogger.i(TAG, "插件已发布：$pluginId $version")
            return@withContext PublishResult(true, "已上传 $pluginId $version")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "发布插件失败：$pluginId", e)
            return@withContext PublishResult(false, e.message ?: "发布失败")
        } finally {
            repoDir.deleteRecursively()
            archive.delete()
        }
    }


    /** 判断推送失败是不是「没有写权限」这一类，而不是网络或凭据缺失。 */
    private fun isPermissionDenied(output: String): Boolean {
        val text = output.lowercase()
        return "permission" in text || "403" in text || "not authorized" in text ||
            "protected branch" in text || "pre-receive hook declined" in text
    }

    /**
     * 生成「提交包」：插件包本体 + 索引条目 + 提交指引，打成一个 zip。
     *
     * 为什么要打包而不是直接给插件 zip：维护者拿到一个文件就能照 SUBMIT.md 合入，
     * 不用回头再问「索引条目该长什么样、放哪个目录」。
     */
    private fun writeSubmission(
        pluginDir: File,
        manifest: PluginManifest,
        version: String,
        displayName: String,
    ): File {
        val exportDir = File(installer.aharouDir, "exports")
        exportDir.mkdirs()
        val target = File(exportDir, "${manifest.id}-$version-submission.zip")

        val entry = buildJsonObject {
            put("id", manifest.id)
            put("name", localized(manifest.name))
            put("description", localized(manifest.description))
            put("author", manifest.publisher)
            if (displayName.isNotBlank()) put("author_display", displayName)
            put("repository", manifest.repository)
            if (manifest.license.isNotBlank()) put("license", manifest.license)
            put("version", version)
            put("package", "$INDEX_RAW/$PACKAGES_DIR/${manifest.id}/$version.zip")
        }
        val guide = buildString {
            appendLine("# 插件提交说明：${manifest.name.values.values.firstOrNull() ?: manifest.id}")
            appendLine()
            appendLine("这个压缩包供维护者审核合入，包含三样东西：")
            appendLine()
            appendLine("| 文件 | 用途 |")
            appendLine("| --- | --- |")
            appendLine("| `package.zip` | 插件包本体 |")
            appendLine("| `index-entry.json` | 需要并入索引的条目 |")
            appendLine("| `SUBMIT.md` | 本说明 |")
            appendLine()
            appendLine("## 合入步骤")
            appendLine()
            appendLine("1. 把 `package.zip` 放到索引仓库的 `$PACKAGES_DIR/${manifest.id}/$version.zip`；")
            appendLine("2. 打开 `plugins/index.json`，用 `index-entry.json` 的内容替换或追加 `plugins` 数组里的同名条目；")
            appendLine("3. 提交并推送，市场刷新后即可看到。")
            appendLine()
            appendLine("## 该插件信息")
            appendLine()
            appendLine("- 插件 id：`${manifest.id}`")
            appendLine("- 版本：`$version`")
            appendLine("- 发布者：`${manifest.publisher}`")
            appendLine("- 仓库：${manifest.repository}")
            if (manifest.license.isNotBlank()) appendLine("- 许可：${manifest.license}")
        }

        java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            // 插件包本体按原样打进 package.zip，避免二次嵌套目录
            val tmp = File(exportDir, ".pkg-${System.nanoTime()}.zip")
            try {
                com.aharou.feature.plugin.data.PluginDevPackageBuilder.zip(pluginDir, tmp)
                put("package.zip", tmp.readBytes())
            } finally {
                tmp.delete()
            }
            put("index-entry.json", json.encodeToString(JsonObject.serializer(), entry).toByteArray())
            put("SUBMIT.md", guide.toByteArray())
        }
        return target
    }

    private fun writeIndexEntry(
        indexPath: File,
        manifest: PluginManifest,
        version: String,
        displayName: String,
    ) {
        val root = json.parseToJsonElement(indexPath.readText()).jsonObject
        val existing = root["plugins"] as? JsonArray ?: JsonArray(emptyList())
        val others = existing.filterNot { entry ->
            (entry as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == manifest.id
        }
        val entry = buildJsonObject {
            put("id", manifest.id)
            put("name", localized(manifest.name))
            put("description", localized(manifest.description))
            put("author", manifest.publisher)
            if (displayName.isNotBlank()) put("author_display", displayName)
            put("repository", manifest.repository)
            if (manifest.license.isNotBlank()) put("license", manifest.license)
            put("version", version)
            put("package", "$INDEX_RAW/$PACKAGES_DIR/${manifest.id}/$version.zip")
        }
        val updated = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("plugins", JsonArray(others + entry))
        }
        indexPath.writeText(json.encodeToString(JsonObject.serializer(), updated))
    }

    /** 与索引序列化口径一致：只有 default 时写字符串，否则写语言映射。 */
    private fun localized(text: LocalizedText): JsonElement =
        if (text.values.keys == setOf(LocalizedText.DEFAULT_KEY)) {
            JsonPrimitive(text.values.getValue(LocalizedText.DEFAULT_KEY))
        } else {
            JsonObject(text.values.mapValues { JsonPrimitive(it.value) })
        }

    /** 把 git 的报错翻成用户能照做的提示。 */
    private fun pushFailureHint(output: String): String {
        val text = output.lowercase()
        return when {
            "permission" in text || "403" in text || "not authorized" in text ->
                "没有官方索引仓库的写权限。请在设置 → 凭据与署名里配置有权限的账号，或先 fork 仓库再提交。"
            "authentication" in text || "could not read username" in text ->
                "Git 凭据缺失或失效，请先在设置 → 凭据与署名里配置账号。"
            else -> "推送失败：${output.takeLast(400)}"
        }
    }

    private companion object {
        const val TAG = "PluginPublisher"
        const val INDEX_REPO = "https://cnb.cool/huxiangli/aharou-plugins.git"
        const val INDEX_RAW = "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main"
        const val PACKAGES_DIR = "plugins/packages"
        const val PUBLISH_PLUGINS = "plugins"
        const val CLONE_TIMEOUT_MS = 120_000L
        const val PUSH_TIMEOUT_MS = 120_000L
    }
}
