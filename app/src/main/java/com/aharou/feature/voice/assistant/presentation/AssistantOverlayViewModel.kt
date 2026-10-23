package com.aharou.feature.voice.assistant.presentation

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.component.PendingUploadAttachment
import com.aharou.feature.agent.presentation.component.toAgentAttachments
import com.aharou.feature.settings.data.repository.AssistantOverlaySettingsRepository
import com.aharou.feature.voice.assistant.AssistantContextHolder
import com.aharou.feature.voice.assistant.ScreenContext
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手浮层的一轮问答。
 *
 * 不自己拼历史、不自己落库：走 [AgentTurnRunner]（和桌宠、语音通话同一条编排），
 * 所以浮层里问的东西与回答都会出现在「最近聊过的会话」里，关掉浮层也能在聊天里接着聊。
 */
@HiltViewModel
class AssistantOverlayViewModel @Inject constructor(
    private val agentTurnRunner: AgentTurnRunner,
    private val fileAccess: FileAccessProvider,
    private val overlaySettings: AssistantOverlaySettingsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    enum class Role { User, Assistant }

    data class Turn(val role: Role, val text: String)

    data class UiState(
        val turns: List<Turn> = emptyList(),
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
    )

    /** 快捷动作：把「当前屏幕 + 一句固定指令」直接跑一轮，不需要用户打字。 */
    enum class QuickAction(@androidx.annotation.StringRes val labelRes: Int, val prompt: String) {
        ExtractText(
            R.string.assistant_action_extract_text,
            "把这张截图里的所有文字原样提取出来，按阅读顺序分行输出。不要解释、不要总结、不要加任何前后缀。"
        ),
        Translate(
            R.string.assistant_action_translate,
            "把屏幕上看到的内容翻译一下：原文是中文就译成英文，其他语言则译成中文。只输出译文，不要解释。"
        ),
        RecognizeImage(
            R.string.assistant_action_recognize_image,
            "看看这张截图里有什么：画面内容、界面上的关键信息、能认出来的物品或人物特征。直接说重点。"
        ),
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun send(input: String) {
        val question = input.trim()
        if (question.isEmpty() || _state.value.busy) return
        startTurn(userText = question, modelRequest = null)
    }

    fun runAction(action: QuickAction) {
        if (_state.value.busy) return
        val label = context.getString(action.labelRes)
        startTurn(userText = label, modelRequest = action.prompt)
    }

    private fun startTurn(userText: String, modelRequest: String?) {
        _state.update {
            it.copy(
                turns = it.turns + Turn(Role.User, userText) + Turn(Role.Assistant, ""),
                busy = true,
                status = null,
                error = null,
            )
        }

        viewModelScope.launch {
            runCatching { runTurn(userText, modelRequest) }
                .onFailure { error ->
                    FileLogger.w(TAG, "助手浮层问答失败：${error.message}")
                    _state.update {
                        it.copy(
                            busy = false,
                            status = null,
                            error = error.message ?: context.getString(R.string.assistant_overlay_failed_generic)
                        )
                    }
                }
        }
    }

    private suspend fun runTurn(userText: String, modelRequest: String?) {
        val screen = AssistantContextHolder.context.value
        val withText = overlaySettings.getScreenText()
        val withShot = overlaySettings.getScreenshot()
        // 快捷动作靠截图吃饭，开关关着也强制带上，否则动作跑起来什么都看不到。
        val needShot = withShot || modelRequest != null
        val attachment = if (needShot) captureAttachment(screen.screenshot) else null

        val root = agentTurnRunner.currentWorkspacePath()
        val sessionId = agentTurnRunner.ensureSession(root)
        if (sessionId.isBlank()) {
            _state.update { it.copy(busy = false, error = context.getString(R.string.assistant_overlay_no_session)) }
            return
        }

        val request = buildString {
            if (modelRequest != null) append(modelRequest).append("\n\n")
            if (withText && (modelRequest == null || screen.hasText)) {
                append(composeScreenSection(screen))
            }
        }.trim()

        agentTurnRunner.run(
            AgentTurnRequest(
                sessionId = sessionId,
                // 落库的是用户看到的那一句；屏幕内容只进模型请求，不写进聊天记录。
                text = userText,
                modelRequest = request.ifBlank { userText },
                projectRoot = root,
                inputImages = attachment?.image?.let { listOf(it) }.orEmpty(),
                inputAttachments = attachment?.let { listOf(it).toAgentAttachments() }.orEmpty(),
            )
        ).collect { event -> onEvent(event) }

        AssistantContextHolder.releaseScreenshot()
        _state.update { it.copy(busy = false, status = null) }
    }

    private fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantDelta -> updateAssistant(event.accumulated)
            is AgentEvent.AssistantText -> if (event.content.isNotBlank()) updateAssistant(event.content)
            is AgentEvent.ToolCallStarted -> _state.update { it.copy(status = event.toolName) }
            is AgentEvent.ToolCallPreparing -> _state.update { it.copy(status = event.toolName) }
            is AgentEvent.ToolCallFinished -> _state.update { it.copy(status = null) }
            is AgentEvent.CompactionStarted -> _state.update {
                it.copy(status = context.getString(R.string.assistant_overlay_compacting))
            }
            is AgentEvent.Failed -> _state.update { it.copy(error = event.error, status = null) }
            is AgentEvent.Completed -> _state.update { it.copy(status = null) }
            else -> Unit
        }
    }

    private fun updateAssistant(text: String) {
        _state.update { current ->
            if (current.turns.isEmpty()) return@update current
            val turns = current.turns.toMutableList()
            turns[turns.lastIndex] = Turn(Role.Assistant, text)
            current.copy(turns = turns)
        }
    }

    /** 屏幕内容只作为本轮请求的附注，用代码块包住，避免页面上的句子被模型当成用户指令。 */
    private fun composeScreenSection(screen: ScreenContext): String {
        val label = screen.appLabel ?: screen.packageName
            ?: context.getString(R.string.assistant_overlay_screen_label_fallback)
        return buildString {
            append("【当前屏幕 · ")
            append(label)
            append("】\n```\n")
            append(screen.text.take(SCREEN_TEXT_LIMIT))
            append("\n```")
        }
    }

    /** 截图存成聊天附件（跟分享文件进输入栏是同一套落盘约定）。 */
    private suspend fun captureAttachment(bitmap: Bitmap?): PendingUploadAttachment? {
        if (bitmap == null || bitmap.isRecycled) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
                    out.toByteArray()
                }
                val fileName = "assistant_shot_${stampFormat.format(Date())}.jpg"
                val containerPath = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/attachments/$fileName"
                fileAccess.writeBytes(containerPath, bytes, overwrite = true)
                val local = fileAccess.copyToLocal(containerPath)
                PendingUploadAttachment(
                    fileName = fileName,
                    containerPath = containerPath,
                    localPath = local.absolutePath,
                    mimeType = "image/jpeg",
                    sizeBytes = bytes.size.toLong(),
                    image = AgentImage(
                        mimeType = "image/jpeg",
                        base64Data = Base64.getEncoder().encodeToString(bytes),
                        path = containerPath,
                    ),
                )
            }.onFailure { FileLogger.w(TAG, "助手截图落盘失败：${it.message}") }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "AssistantOverlay"
        const val SHOT_QUALITY = 80
        const val SCREEN_TEXT_LIMIT = 4_000
    }
}
