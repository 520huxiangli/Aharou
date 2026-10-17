package com.aharou.feature.agent.domain.tool.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 宿主剪贴板读写（不需要权限；读取要求本应用在前台，这是 Android 10 起的系统限制）。 */
@Singleton
class ClipboardHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private fun manager(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    suspend fun read(): ToolResult = withContext(Dispatchers.Main) {
        val cm = manager() ?: return@withContext ToolResult.Error("剪贴板服务不可用", "NO_CLIPBOARD")
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return@withContext ToolResult.Success(buildJsonObject {
                put("text", "")
                put("empty", true)
            })
        }
        val text = buildString {
            for (i in 0 until clip.itemCount) {
                val part = clip.getItemAt(i).coerceToText(context)?.toString().orEmpty()
                if (part.isEmpty()) continue
                if (isNotEmpty()) append('\n')
                append(part)
            }
        }
        ToolResult.Success(buildJsonObject {
            put("text", text)
            put("empty", text.isEmpty())
            put("length", text.length)
        })
    }

    suspend fun write(text: String): ToolResult = withContext(Dispatchers.Main) {
        val cm = manager() ?: return@withContext ToolResult.Error("剪贴板服务不可用", "NO_CLIPBOARD")
        cm.setPrimaryClip(ClipData.newPlainText("aharou", text))
        ToolResult.Success(buildJsonObject {
            put("message", "已写入剪贴板")
            put("length", text.length)
        })
    }
}