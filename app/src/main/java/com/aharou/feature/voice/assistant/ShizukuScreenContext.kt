package com.aharou.feature.voice.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁贴 / 长按电源唤起时，用 Shizuku 的 shell 身份抓「当前屏幕」。
 *
 * 不走无障碍：无障碍要用户一直手动保持开启，而这个 App 本来就需要 Shizuku（电源键监听也靠它）。
 * shell 身份能直接跑 `screencap` 与 `uiautomator dump`，拿到的就是同一屏的截图与文本。
 *
 * 两次命令都会扫一遍当前窗口，所以调用方应在浮层窗口不可见时调用（否则抓到的是自己）。
 */
@Singleton
class ShizukuScreenContext @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
) {

    data class Result(val text: String, val screenshot: Bitmap?, val packageName: String? = null)

    suspend fun capture(maxChars: Int = AssistStructureReader.MAX_CHARS): Result {
        if (shizukuManager.state.value != ShizukuState.READY) return Result("", null)
        val dir = context.getExternalFilesDir(null) ?: return Result("", null)
        val shot = File(dir, "assistant_screen.png")
        val dump = File(dir, "assistant_ui.xml")
        runCatching {
            shot.delete()
            dump.delete()
        }

        val command = "screencap -p '${shot.absolutePath}' >/dev/null 2>&1; " +
            "uiautomator dump '${dump.absolutePath}' >/dev/null 2>&1"
        val result = runCatching { shizukuManager.runCommand(command, CAPTURE_TIMEOUT_MS) }.getOrNull()
        if (result == null || result.exitCode != 0) {
            FileLogger.w(TAG, "抓屏命令返回 ${result?.exitCode}")
        }

        val bitmap = runCatching {
            if (shot.exists() && shot.length() > 0) BitmapFactory.decodeFile(shot.absolutePath) else null
        }.getOrNull()
        val text = runCatching {
            if (dump.exists() && dump.length() > 0) extractText(dump.readText(), maxChars) else ""
        }.getOrDefault("")
        val packageName = runCatching {
            if (dump.exists() && dump.length() > 0) extractPackage(dump.readText()) else null
        }.getOrNull()

        runCatching {
            shot.delete()
            dump.delete()
        }
        return Result(text, bitmap, packageName)
    }

    /** 层级里第一个 package 属性就是当前前台应用。 */
    internal fun extractPackage(xml: String): String? =
        PACKAGE.find(xml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** 从 uiautomator 的层级 XML 里按行收集 text / content-desc，去重后拼成一段。 */
    internal fun extractText(xml: String, maxChars: Int): String {
        val out = LinkedHashSet<String>()
        var total = 0
        for (match in NODE_TEXT.findAll(xml)) {
            val raw = match.groupValues[1].unescapeXml().trim()
            if (raw.isEmpty() || raw.length > MAX_LINE_CHARS) continue
            if (!out.add(raw)) continue
            total += raw.length
            if (total >= maxChars) break
        }
        return out.joinToString("\n").take(maxChars)
    }

    private fun String.unescapeXml(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    private companion object {
        const val TAG = "ShizukuScreen"
        const val CAPTURE_TIMEOUT_MS = 25_000L

        /** 一行超过这个长度多半是整段长文本（或垃圾数据），跳过避免把上下文挤爆。 */
        const val MAX_LINE_CHARS = 500

        val NODE_TEXT = Regex("(?:text|content-desc)=\"([^\"]*)\"")
        val PACKAGE = Regex("package=\"([^\"]*)\"")
    }
}
