package com.aharou.feature.agent.domain.rule

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.provider.OneShotProviderFactory
import com.aharou.feature.agent.domain.model.AgentMessage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 规则整理器：把工作区里的规则文件读一遍，交给模型做「合并重复 / 删除过时 / 同主题归并」，
 * 再按既有文件结构写回。
 *
 * 三条原则与记忆整理一致：只做整理不新增、拿不准就保留、改之前留备份。
 * 落点由代码决定（主文件仍走 [RuleFileRepository.activeFile] 那套口径），模型只负责内容。
 */
@Singleton
class RuleCurator @Inject constructor(
    private val oneShotProviderFactory: OneShotProviderFactory,
    private val promptFileResolver: PromptFileResolver,
) {

    @Serializable
    private data class FragmentPayload(val name: String, val content: String)

    @Serializable
    private data class CuratorPayload(
        val main: String,
        val fragments: List<FragmentPayload> = emptyList(),
        val notes: List<String> = emptyList(),
    )

    data class Result(
        val changed: Boolean,
        val notes: List<String> = emptyList(),
        val backupDir: String? = null,
        val error: String? = null,
    )

    /**
     * 整理 [dir] 下的规则文件。没有任何规则文件、内容过小或模型不可用时直接返回，
     * 不改动磁盘。失败一律静默返回 [Result.error]，由调用方决定要不要提示。
     */
    suspend fun curate(dir: File): Result = withContext(Dispatchers.IO) {
        val mainFile = activeFile(dir)
        val fragments = fragmentsOf(dir)
        if (mainFile == null && fragments.isEmpty()) return@withContext Result(changed = false)

        val input = buildString {
            mainFile?.let { append("### ${it.name}\n").append(readText(it)).appendLine() }
            fragments.forEach { (name, file) ->
                append("### rules.d/$name\n").append(readText(file)).appendLine()
            }
        }
        if (input.length > MAX_INPUT_CHARS) {
            FileLogger.w(TAG, "规则内容超过整理上限，跳过：${input.length} 字符")
            return@withContext Result(changed = false, error = "规则内容过多，先手动精简再整理")
        }

        val provider = oneShotProviderFactory.create(null)
            ?: return@withContext Result(changed = false, error = "没有可用的 AI 供应商")
        val raw = runCatching {
            withTimeout(TIMEOUT_MS) {
                provider.complete(
                    systemPrompt = promptFileResolver.resolve(PROMPT_NAME),
                    messages = listOf(AgentMessage.UserMessage(content = input)),
                    tools = emptyList(),
                ).content
            }
        }.getOrElse { e ->
            FileLogger.w(TAG, "规则整理调用失败", e)
            return@withContext Result(changed = false, error = e.message ?: "整理失败")
        }

        val payload = parse(raw) ?: run {
            FileLogger.w(TAG, "规则整理结果无法解析，保持原样")
            return@withContext Result(changed = false, error = "整理结果格式不对，未改动")
        }
        val validFragments = payload.fragments.filter { isValidFragmentName(it.name) }
        if (validFragments.size != payload.fragments.size) {
            FileLogger.w(TAG, "丢弃了 ${payload.fragments.size - validFragments.size} 个非法片段名")
        }

        // 内容一字未变就不落盘：模型只是换了措辞时，用户不该被动接受一份重写。
        val mainText = payload.main.trim()
        if (mainText.isEmpty()) return@withContext Result(changed = false, error = "整理结果为空，未改动")
        if (mainText == mainFile?.let(::readText)?.trim().orEmpty() && sameFragments(fragments, validFragments)) {
            return@withContext Result(changed = false)
        }

        val backupRoot = runCatching { backup(dir, mainFile, fragments) }
            .onFailure { FileLogger.w(TAG, "规则备份失败，放弃本次整理", it) }
            .getOrNull() ?: return@withContext Result(changed = false, error = "备份失败，未改动")

        return@withContext runCatching {
            val target = mainFile ?: File(dir, AGENTS_FILE)
            target.parentFile?.mkdirs()
            target.writeText(mainText + "\n")

            val fragmentDir = File(dir, RULES_DIR).apply { mkdirs() }
            validFragments.forEach { File(fragmentDir, it.name).writeText(it.content.trimEnd() + "\n") }
            // 被合并掉的片段：新结果里不再出现，就按整理意图删掉（备份里还留着）。
            val kept = validFragments.map { it.name }.toSet()
            fragments.keys.filterNot { it in kept }.forEach { stale ->
                runCatching { File(fragmentDir, stale).delete() }
            }
            FileLogger.i(TAG, "规则整理完成：主文件 ${target.name}，片段 ${validFragments.size} 个")
            Result(changed = true, notes = payload.notes, backupDir = backupRoot.absolutePath)
        }.getOrElse { e ->
            FileLogger.e(TAG, "写入整理结果失败", e)
            Result(changed = false, error = "写入失败：${e.message ?: "未知错误"}")
        }
    }

    private fun parse(raw: String): CuratorPayload? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString<CuratorPayload>(raw.substring(start, end + 1))
        }.getOrNull()
    }

    private fun sameFragments(current: Map<String, File>, incoming: List<FragmentPayload>): Boolean {
        if (current.keys != incoming.map { it.name }.toSet()) return false
        return incoming.all { payload -> readText(current.getValue(payload.name)).trim() == payload.content.trim() }
    }

    private fun backup(dir: File, mainFile: File?, fragments: Map<String, File>): File {
        val stamp = System.currentTimeMillis()
        // 备份放 .aharou 下：工作区根会进 git 与文件树，规则副本不该被当成项目内容。
        val root = File(dir, ".aharou/rules-backup/$stamp")
        root.mkdirs()
        mainFile?.let { File(root, it.name).writeText(readText(it)) }
        if (fragments.isNotEmpty()) {
            val fragmentBackup = File(root, RULES_DIR).apply { mkdirs() }
            fragments.forEach { (name, file) -> File(fragmentBackup, name).writeText(readText(file)) }
        }
        return root
    }

    private fun fragmentsOf(dir: File): Map<String, File> =
        File(dir, RULES_DIR).listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.associateBy { it.name }
            .orEmpty()

    /** 与 [RuleFileRepository] 同一口径：三份主文件里只取优先级最高的那份。 */
    private fun activeFile(dir: File): File? =
        listOf(AGENTS_FILE, CLAUDE_FILE, RULES_FILE)
            .map { File(dir, it) }
            .firstOrNull { it.isFile }

    private fun readText(file: File): String = runCatching { file.readText() }.getOrDefault("")

    private fun isValidFragmentName(name: String): Boolean =
        FRAGMENT_NAME_REGEX.matches(name) && name !in setOf(AGENTS_FILE, CLAUDE_FILE, RULES_FILE)

    private companion object {
        const val TAG = "RuleCurator"
        const val PROMPT_NAME = "agent/rule-curator.md"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val RULES_FILE = "rules.md"
        const val RULES_DIR = "rules.d"

        /** 整理一次最多送进去多少字符；规则本来就该精简，超过说明该人先动手了。 */
        const val MAX_INPUT_CHARS = 60_000
        const val TIMEOUT_MS = 180_000L

        val FRAGMENT_NAME_REGEX = Regex("[A-Za-z0-9_-]+\\.md")
        val json = Json { ignoreUnknownKeys = true }
    }
}
