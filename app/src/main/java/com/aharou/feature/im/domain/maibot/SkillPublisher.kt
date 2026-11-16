package com.aharou.feature.im.domain.maibot

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 把本地技能发布到社区仓库（CNB 的 aharou-plugins），并在技能市场索引里登记 community 源。
 *
 * 技能市场原生只认 github/gitee/gitlab 的地址格式，这里先把 CNB 的支持补进了
 * [com.aharou.feature.agent.domain.skill.market.SkillRepoAccess]，否则传上去也读不下来。
 */
@Singleton
class SkillPublisher @Inject constructor(
    private val engine: CommandEngine,
    private val installer: ContainerInstaller,
    private val skills: SkillRepository,
    @param:ApplicationContext private val context: Context,
) {
    data class Result(val success: Boolean, val message: String)

    private companion object {
        const val TAG = "SkillPublisher"
        const val INDEX_REPO = "https://cnb.cool/huxiangli/aharou-plugins.git"
        const val SKILLS_DIR = "community/skills"
        const val SOURCE_ID = "community"
        const val CLONE_TIMEOUT_MS = 120_000L
        const val PUSH_TIMEOUT_MS = 120_000L
    }

    private val workRoot: File get() = File(File(installer.aharouDir, "tmp"), "publish-skill")

    suspend fun publish(name: String, scope: SkillScope): Result = withContext(Dispatchers.IO) {
        val bytes = skills.exportZip(name, scope)
            ?: return@withContext Result(false, "技能目录为空或读取失败")
        val repoDir = File(workRoot, "repo")
        val archive = File(workRoot, "$name.zip")
        try {
            workRoot.mkdirs()
            repoDir.deleteRecursively()
            archive.writeBytes(bytes)

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
                    append("cd ${shellQuote(repoPath)} && ")
                    append("mkdir -p ${shellQuote(SKILLS_DIR)} && ")
                    append("cd ${shellQuote(SKILLS_DIR)} && ")
                    append("unzip -q -o ${shellQuote(installer.containerPathFor(archive))}")
                },
                timeoutMs = CLONE_TIMEOUT_MS,
            )
            if (unpack.exitCode != 0) {
                return@withContext Result(false, "解压技能包失败：${unpack.output.takeLast(300)}")
            }
            // 索引要在提交前改好，否则改完没进这次 push
            registerSource()
            val push = engine.runCommandSyncWithExit(
                buildString {
                    append("cd ${shellQuote(repoPath)} && git add -A && ")
                    append("git -c user.name=aharou -c user.email=aharou@local ")
                    append("commit -m ${shellQuote("feat: 上传技能 $name")} || true; ")
                    append("git push origin HEAD:main")
                },
                timeoutMs = PUSH_TIMEOUT_MS,
            )
            if (push.exitCode != 0) {
                return@withContext Result(false, pushHint(push.output))
            }
            FileLogger.i(TAG, "技能已发布：$name")
            Result(true, "已上传 $name")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "发布技能失败：$name", e)
            Result(false, e.message ?: "发布失败")
        } finally {
            repoDir.deleteRecursively()
            archive.delete()
        }
    }

    /** 技能市场索引里补一个 community 源，指向仓库的 community/skills 目录。 */
    private fun registerSource() {
        val index = File(installer.aharouDir, "tmp/publish-skill/repo/skills/index.json")
        if (!index.isFile) return
        runCatching {
            val root = JSONObject(index.readText())
            val sources = root.optJSONObject("sources") ?: JSONObject().also { root.put("sources", it) }
            if (sources.has(SOURCE_ID)) return@runCatching
            sources.put(SOURCE_ID, JSONObject().apply {
                put("name", JSONObject().apply {
                    put("zh", "社区技能")
                    put("en", "Community Skills")
                })
                put("kind", "directory")
                put("repo", "huxiangli/aharou-plugins")
                put("branch", "main")
                put("path", SKILLS_DIR)
                put("host", "cnb.cool")
            })
            index.writeText(root.toString(2))
        }.onFailure { FileLogger.w(TAG, "写入技能索引失败", it) }
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
