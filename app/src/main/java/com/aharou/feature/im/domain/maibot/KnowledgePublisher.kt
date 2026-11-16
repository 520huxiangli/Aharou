package com.aharou.feature.im.domain.maibot

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.io.path.relativeTo
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 把本地知识库目录发布到社区仓库，并在知识库索引里登记成一个源。
 *
 * 每个知识库目录一个源：路径写 `community/knowledge/<id>`，host 用 cnb.cool，
 * 客户端靠 [com.aharou.feature.agent.domain.skill.market.SkillRepoAccess] 的 CNB 分支读取。
 */
@Singleton
class KnowledgePublisher @Inject constructor(
    private val engine: CommandEngine,
    private val installer: ContainerInstaller,
    @param:ApplicationContext private val context: Context,
) {
    data class Result(val success: Boolean, val message: String)

    private companion object {
        const val TAG = "KnowledgePublisher"
        const val INDEX_REPO = "https://cnb.cool/huxiangli/aharou-plugins.git"
        const val KB_DIR = "community/knowledge"
        const val CLONE_TIMEOUT_MS = 120_000L
        const val PUSH_TIMEOUT_MS = 120_000L
    }

    private val workRoot: File get() = File(File(installer.aharouDir, "tmp"), "publish-kb")

    suspend fun publish(id: String, displayName: String, sourceDir: File): Result = withContext(Dispatchers.IO) {
        if (id.isBlank() || !sourceDir.isDirectory) {
            return@withContext Result(false, "知识库目录不存在")
        }
        val repoDir = File(workRoot, "repo")
        val archive = File(workRoot, "$id.zip")
        try {
            workRoot.mkdirs()
            repoDir.deleteRecursively()
            zipDirectory(sourceDir, archive, id)

            val repoPath = installer.containerPathFor(repoDir)
            val clone = engine.runCommandSyncWithExit(
                "rm -rf ${shellQuote(repoPath)} && git clone $INDEX_REPO ${shellQuote(repoPath)}",
                timeoutMs = CLONE_TIMEOUT_MS,
            )
            if (clone.exitCode != 0) {
                return@withContext Result(false, "拉取社区仓库失败：${clone.output.takeLast(300)}")
            }
            val unpack = engine.runCommandSyncWithExit(
                buildString {
                    append("cd ${shellQuote(repoPath)} && mkdir -p ${shellQuote(KB_DIR)} && ")
                    append("cd ${shellQuote(KB_DIR)} && unzip -q -o ${shellQuote(installer.containerPathFor(archive))}")
                },
                timeoutMs = CLONE_TIMEOUT_MS,
            )
            if (unpack.exitCode != 0) {
                return@withContext Result(false, "解压知识库失败：${unpack.output.takeLast(300)}")
            }
            registerSource(id, displayName)
            val push = engine.runCommandSyncWithExit(
                buildString {
                    append("cd ${shellQuote(repoPath)} && git add -A && ")
                    append("git -c user.name=aharou -c user.email=aharou@local ")
                    append("commit -m ${shellQuote("feat: 上传知识库 $id")} || true; ")
                    append("git push origin HEAD:main")
                },
                timeoutMs = PUSH_TIMEOUT_MS,
            )
            if (push.exitCode != 0) {
                return@withContext Result(false, pushHint(push.output))
            }
            FileLogger.i(TAG, "知识库已发布：$id")
            Result(true, "已上传 $id")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "发布知识库失败：$id", e)
            Result(false, e.message ?: "发布失败")
        } finally {
            repoDir.deleteRecursively()
            archive.delete()
        }
    }

    private fun registerSource(id: String, displayName: String) {
        val index = File(installer.aharouDir, "tmp/publish-kb/repo/knowledge/index.json")
        if (!index.isFile) return
        runCatching {
            val root = JSONObject(index.readText())
            val sources = root.optJSONObject("sources") ?: JSONObject().also { root.put("sources", it) }
            val key = "community-$id"
            if (sources.has(key)) return@runCatching
            sources.put(key, JSONObject().apply {
                put("repo", "huxiangli/aharou-plugins")
                put("branch", "main")
                put("path", "$KB_DIR/$id")
                put("host", "cnb.cool")
                put("name", JSONObject().apply {
                    put("zh", displayName)
                    put("en", displayName)
                })
                put("category", "community")
            })
            index.writeText(root.toString(2))
        }.onFailure { FileLogger.w(TAG, "写入知识库索引失败", it) }
    }

    private fun zipDirectory(dir: File, target: File, rootName: String) {
        val base = dir.canonicalFile.toPath()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.canonicalFile.toPath().relativeTo(base).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry("$rootName/$relative"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun pushHint(output: String): String {
        val text = output.lowercase()
        return when {
            "permission" in text || "403" in text || "not authorized" in text ->
                "没有社区仓库的写权限，先到设置 → 凭据与署名里配置 cnb.cool 账号"
            "authentication" in text || "could not read username" in text ->
                "Git 凭据缺失或失效，先到设置 → 凭据与署名里配置账号"
            else -> "推送失败：${output.takeLast(300)}"
        }
    }
}
