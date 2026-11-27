package com.aharou.feature.agent.presentation

import android.content.Context
import com.aharou.feature.agent.presentation.component.PastedText
import com.aharou.feature.agent.presentation.component.PendingUploadAttachment
import com.aharou.feature.agent.presentation.component.expandPastePlaceholders
import com.aharou.feature.agent.presentation.component.shouldPasteAsFile
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 输入栏这几份「跟会话走」的状态：草稿文字、折叠的粘贴块、待发附件。
 *
 * 三者生命周期一致（都按会话隔离、切换会话各自处理），原先堆在 [AIAgentViewModel] 里占了近 300 行。
 * 那个文件顶在架构门禁的行数基线上（零余量），所以整块搬到独立类；ViewModel 只保留同名转发方法，
 * 对外签名一字不改，UI 与 [MainActivity] 的调用点无需跟随改动。
 *
 * 草稿与粘贴块必须一起搬：草稿里留着 `[Pasted#N]` 标记、内容却在粘贴缓冲里，两者拆到不同对象再
 * 各自持久化会多出一层「谁先落盘」的隐式约定。附件反过来只活在内存，切会话时保留分享投递进来的
 * 那些（见 [retainSharedAttachments]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class ComposerStateController @Inject constructor(
    @param:ApplicationContext context: Context,
    private val fileAccess: FileAccessProvider,
) {

    private companion object {
        /** 尚无当前会话时的草稿哨兵键：用 UUID 不可能产出的控制字符开头，避免撞上真实会话 id。 */
        const val PENDING_DRAFT_KEY = "\u0000pending"

        /** 「加入输入栏」投递的文件落点：当前工作区里的附件目录，与分享进来的文件同处一地。 */
        val ATTACHMENTS_DIR = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/attachments"

        const val FALLBACK_MIME_TYPE = "application/octet-stream"

        /** 选中片段落盘的文件名格式（`20.22.txt`，当前时分）；同一分钟内的重名由序号兜底。 */
        val SELECTION_STAMP_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH.mm")

        /** 粘贴超长文本落盘时的文件名前缀，与选中片段区分开。 */
        const val PASTE_FILE_PREFIX = "pasted-"

        /** 附件预览的字节上限：超过就不预览，免得把大文件拉进内存。 */
        const val PREVIEW_MAX_BYTES = 256L * 1024

        /** 附件预览的字符上限。 */
        const val PREVIEW_MAX_CHARS = 200_000

        /** 草稿落盘防抖：每敲一个字就写盘会把主线程拖住，表现为吞字。 */
        const val DRAFT_SAVE_DEBOUNCE_MS = 400L
    }

    // ── 输入草稿 ──

    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val pastedPrefs = context.getSharedPreferences("agent_pasted_texts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )

    private var draftSaveJob: Job? = null

    /** 当前会话的草稿；无会话时读哨兵键那份（见 [adoptPendingDraft]）。 */
    fun inputDraft(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<String> = sessionIds
        .flatMapLatest { id -> _inputDrafts.map { it[id ?: PENDING_DRAFT_KEY].orEmpty() } }
        .stateIn(scope, SharingStarted.Eagerly, "")

    fun updateDraft(sessionId: String?, text: String, scope: CoroutineScope) {
        val id = sessionId ?: PENDING_DRAFT_KEY
        _inputDrafts.value = if (text.isEmpty()) _inputDrafts.value - id else _inputDrafts.value + (id to text)
        // 内存态保证输入跟手，磁盘写只在停下来后做一次。
        draftSaveJob?.cancel()
        draftSaveJob = scope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            draftPrefs.edit().apply { if (text.isEmpty()) remove(id) else putString(id, text) }.apply()
        }
    }

    fun draftOf(sessionId: String?): String = _inputDrafts.value[sessionId.orEmpty()].orEmpty()

    /** 清掉当前会话的草稿；[sessionId] 为 null（尚无会话）时只清哨兵键那份。 */
    fun clearDraft(sessionId: String?) {
        val id = sessionId ?: PENDING_DRAFT_KEY
        // 哨兵键一并清：发送后再有会话落定，不能把刚发出去的正文又当未归属草稿迁回来。
        _inputDrafts.value = _inputDrafts.value - id - PENDING_DRAFT_KEY
        draftPrefs.edit().remove(id).remove(PENDING_DRAFT_KEY).apply()
    }

    /** 删掉某个会话的草稿（会话被删时用），不动哨兵键。 */
    fun dropDraft(sessionId: String) {
        _inputDrafts.value = _inputDrafts.value - sessionId
    }

    /**
     * 会话就绪前打的字先挂在哨兵键下（冷启动 / 未选工作区时当前会话为 null），会话一出现就并入它。
     * 不能直接丢弃：单一事实源改造后输入框只认这份草稿，丢掉就表现为「打字没反应」。
     */
    fun adoptPendingDraft(sessionId: String) {
        val pending = _inputDrafts.value[PENDING_DRAFT_KEY]?.takeIf { it.isNotEmpty() } ?: return
        // 目标会话已有非空草稿时不覆盖（哨兵这份丢弃）；哨兵键两条分支都要清，否则下次会话落定会重复灌入。
        val keepTarget = !_inputDrafts.value[sessionId].isNullOrEmpty()
        _inputDrafts.value = if (keepTarget) {
            _inputDrafts.value - PENDING_DRAFT_KEY
        } else {
            _inputDrafts.value - PENDING_DRAFT_KEY + (sessionId to pending)
        }
        val editor = draftPrefs.edit().remove(PENDING_DRAFT_KEY)
        if (!keepTarget) editor.putString(sessionId, pending)
        editor.apply()
    }

    // ── 折叠粘贴块 ──

    /**
     * 折叠的粘贴块，按会话隔离。
     *
     * 与输入草稿共用同一生命周期：草稿持久化（draftPrefs），它也必须持久化，否则重启后
     * 草稿里留下的 `[Pasted#N]` 展开不出来，会把标记字面量发给模型。id 全局单调、不重用，
     * 免得旧标记撞上新内容。
     */
    private val _pastedTexts = MutableStateFlow(loadPastedTexts())

    private var nextPasteId: Int =
        (_pastedTexts.value.values.flatten().maxOfOrNull { it.id } ?: 0) + 1

    fun pastedTexts(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<List<PastedText>> = sessionIds
        .flatMapLatest { id -> _pastedTexts.map { it[id].orEmpty() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 缓冲 [text]，返回顶替它的 `[Pasted#N]` 标记。
     *
     * [currentSessionId] 用于判断落盘期间有没有切会话：粘贴一次要写文件，慢的话用户已经切走，
     * 此时不能再往新会话的输入栏里塞附件。
     */
    fun stashPastedText(
        text: String,
        sessionId: String?,
        scope: CoroutineScope,
        currentSessionId: () -> String?,
    ): String {
        val sid = sessionId ?: return PastedText.placeholderFor(nextPasteId++)
        // 一次粘贴会被 IME / Compose 送来两遍（第二遍带的是原文而非增量），照单全收就会折两块、
        // 落两份文件。同内容直接复用已有的块与标记。
        _pastedTexts.value[sid].orEmpty().firstOrNull { it.text == text }
            ?.let { return it.placeholder }
        val id = nextPasteId++
        val entries = _pastedTexts.value[sid].orEmpty() + PastedText(id, text)
        _pastedTexts.value = _pastedTexts.value + (sid to entries)
        savePastedTexts(_pastedTexts.value)
        // 超大粘贴额外落一份 .txt：写成功就把标记抹了，内容由附件带走；写失败则什么都不做，
        // 标记留着，发送时照旧展开回原文——不丢东西。
        if (shouldPasteAsFile(text)) stashPastedFile(sid, id, text, scope, currentSessionId)
        return PastedText.placeholderFor(id)
    }

    private fun stashPastedFile(
        sessionId: String,
        id: Int,
        text: String,
        scope: CoroutineScope,
        currentSessionId: () -> String?,
    ) {
        scope.launch {
            val attachment = withContext(Dispatchers.IO) {
                textAttachment(text, PASTE_FILE_PREFIX)
            } ?: return@launch
            // 期间切了会话就什么都不做：标记留在草稿里，发送时展开回原文
            if (currentSessionId() != sessionId) return@launch
            // 附件与抹标记必须在同一次主线程续体里落地，中间一旦让出，用户恰好发送就会变成
            // 「文件 + 展开的原文」两份内容。
            addPendingAttachments(listOf(attachment))
            val marker = PastedText.placeholderFor(id)
            val draft = _inputDrafts.value[sessionId].orEmpty()
            if (marker in draft) updateDraft(sessionId, draft.replace(marker, ""), scope)
            val remaining = _pastedTexts.value[sessionId].orEmpty().filterNot { it.id == id }
            _pastedTexts.value =
                if (remaining.isEmpty()) _pastedTexts.value - sessionId
                else _pastedTexts.value + (sessionId to remaining)
            savePastedTexts(_pastedTexts.value)
        }
    }

    fun removePastedText(id: Int, sessionId: String?) {
        val sid = sessionId ?: return
        val entries = _pastedTexts.value[sid].orEmpty().filterNot { it.id == id }
        _pastedTexts.value =
            if (entries.isEmpty()) _pastedTexts.value - sid
            else _pastedTexts.value + (sid to entries)
        savePastedTexts(_pastedTexts.value)
    }

    /** 发送时把标记还原成原文；[PastedText] 已被用户删掉的标记原样保留。 */
    fun expandPastes(text: String, sessionId: String?): String {
        val sid = sessionId ?: return text
        val (expanded, consumed) = expandPastePlaceholders(
            text,
            _pastedTexts.value[sid].orEmpty(),
        )
        if (consumed.isEmpty()) return text
        val remaining = _pastedTexts.value[sid].orEmpty().filterNot { it.id in consumed }
        _pastedTexts.value =
            if (remaining.isEmpty()) _pastedTexts.value - sid
            else _pastedTexts.value + (sid to remaining)
        savePastedTexts(_pastedTexts.value)
        return expanded
    }

    private fun loadPastedTexts(): Map<String, List<PastedText>> {
        val raw = pastedPrefs.getString("buffers", null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, List<PastedTextEntry>>>(raw)
                .mapValues { (_, list) -> list.map { PastedText(it.id, it.text) } }
        }.getOrDefault(emptyMap())
    }

    private fun savePastedTexts(buffers: Map<String, List<PastedText>>) {
        val encoded = buffers.mapValues { (_, list) ->
            list.map { PastedTextEntry(it.id, it.text) }
        }
        pastedPrefs.edit().putString("buffers", json.encodeToString(encoded)).apply()
    }

    @Serializable
    private data class PastedTextEntry(val id: Int, val text: String)

    // ── 待发附件 ──

    /**
     * 状态必须活得比聊天面板的 Composable 长：分享文件是跨 Activity 投递的，面板若在投递前
     * 被销毁（冷启动时序、切换路由），remember 局部状态会连同附件一起丢，表现为「点了插入，
     * 当次不显示；退出重进才出现」。这里不按会话隔离——分享发生在会话确定之前，硬绑会话会让
     * 附件无处安放；切换会话时的清空统一在 ViewModel 里走 [retainSharedAttachments]。
     */
    private val _pendingAttachments = MutableStateFlow<List<PendingUploadAttachment>>(emptyList())
    internal val pendingAttachments: StateFlow<List<PendingUploadAttachment>> = _pendingAttachments.asStateFlow()

    internal fun addPendingAttachments(items: List<PendingUploadAttachment>) {
        if (items.isEmpty()) return
        _pendingAttachments.value = _pendingAttachments.value + items
    }

    /** 外部分享投递的附件：标记来源，切会话时不被清掉。 */
    internal fun addSharedAttachments(items: List<PendingUploadAttachment>) {
        if (items.isEmpty()) return
        _pendingAttachments.value = _pendingAttachments.value + items.map { it.copy(fromShare = true) }
    }

    internal fun setPendingAttachments(items: List<PendingUploadAttachment>) {
        _pendingAttachments.value = items
    }

    internal fun removePendingAttachmentAt(index: Int) {
        if (index !in _pendingAttachments.value.indices) return
        _pendingAttachments.value = _pendingAttachments.value.filterIndexed { i, _ -> i != index }
    }

    /**
     * 切会话时清空附件，只留分享投递进来的那些。
     *
     * 分享投递要留住：冷启动时「会话就绪」紧接着分享投递，一律清空会让用户刚点进来的文件凭空消失。
     * 返回值是真正被清掉的条数，供调用方决定要不要留日志。
     */
    fun retainSharedAttachments(): Pair<Int, Int> {
        val before = _pendingAttachments.value
        val kept = before.filter { it.fromShare }
        _pendingAttachments.value = kept
        return (before.size - kept.size) to kept.size
    }

    /**
     * 「加入输入栏」：把文件树里的一个文件 / 目录投递进待发附件列表。
     *
     * 目录只作为单个条目投递，不递归展开——具体要读什么交给 AI。这里也不预先拉本地副本：
     * 远程工作区拖一份大文件下来纯属浪费，分享 / 打开时再按需取。
     */
    fun attachWorkspaceEntry(path: String, scope: CoroutineScope) {
        if (path.isBlank()) return
        scope.launch {
            val item = withContext(Dispatchers.IO) { entryAttachment(path) } ?: return@launch
            addPendingAttachments(listOf(item))
        }
    }

    /**
     * 「加入输入栏」：把编辑器里选中的片段落成文件再投递（附件通道只认文件）。
     *
     * 落在工作区 `.aharou/attachments/`（与分享进来的文件同目录，不污染源码树）。文件名取当前时间
     * （如 `20.22.txt`）——同一个文件选多少次都各成一个条目；同一分钟内选中多次时追加序号，
     * 后者不能把前者覆盖掉。
     */
    fun attachEditorSelection(text: String, scope: CoroutineScope) {
        if (text.isBlank()) return
        scope.launch {
            val item = withContext(Dispatchers.IO) { selectionAttachment(text) } ?: return@launch
            addPendingAttachments(listOf(item))
        }
    }

    /**
     * 读取待发附件的内容用于预览。
     *
     * 只给 UI 预览用，所以超过 [PREVIEW_MAX_CHARS] 就截断并在尾部标记——直接把几百 KB 的文本
     * 塞进 Compose 的 Text 会卡死主线程与测量。读不到（二进制/权限/已删）返回 null，由 UI 提示。
     */
    suspend fun readAttachmentText(containerPath: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val size = fileAccess.fileSize(containerPath)
            if (size > PREVIEW_MAX_BYTES) return@runCatching null
            val text = fileAccess.readFile(containerPath).take(PREVIEW_MAX_CHARS)
            if (!looksLikeText(text)) return@runCatching null
            text
        }.getOrNull()
    }

    /**
     * 二进制文件（zip / 图片 / 音频）读成字符串是一团乱码，比不预览还差。不维护后缀白名单，
     * 直接看内容：出现 NUL，或控制字符超过 5%，就当非文本——压缩包/媒体文件必定命中。
     */
    private fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return true
        var bad = 0
        for (c in text) {
            if (c == '\u0000') return false
            if (c.code < 0x09 || c.code in 0x0E..0x1F) bad++
        }
        return bad * 100 <= text.length * 5
    }

    private fun entryAttachment(path: String): PendingUploadAttachment? {
        val name = path.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return null
        return runCatching {
            val isDirectory = fileAccess.isDirectory(path)
            PendingUploadAttachment(
                fileName = name,
                containerPath = path,
                localPath = "",
                mimeType = if (isDirectory) DIRECTORY_MIME_TYPE else mimeTypeForFileName(name),
                sizeBytes = if (isDirectory) 0L else fileAccess.fileSize(path),
                image = null
            )
        }.getOrNull()
    }

    private fun selectionAttachment(text: String): PendingUploadAttachment? = textAttachment(text, "")

    /**
     * 把一段文本落成 `.txt` 附件。[prefix] 只影响文件名（如 `pasted-20.22.txt`），
     * 好让人在附件卡片上一眼看出它从哪来。
     */
    private fun textAttachment(text: String, prefix: String): PendingUploadAttachment? {
        val stamp = java.time.LocalTime.now().format(SELECTION_STAMP_FORMAT)
        return runCatching {
            var fileName = "$prefix$stamp.txt"
            var index = 1
            while (fileAccess.exists("$ATTACHMENTS_DIR/$fileName")) {
                fileName = "$prefix$stamp-$index.txt"
                index += 1
            }
            val containerPath = "$ATTACHMENTS_DIR/$fileName"
            fileAccess.writeFile(containerPath, text, overwrite = false)
            PendingUploadAttachment(
                fileName = fileName,
                containerPath = containerPath,
                localPath = "",
                mimeType = mimeTypeForFileName(fileName),
                sizeBytes = fileAccess.fileSize(containerPath),
                image = null
            )
        }.getOrNull()
    }

    /** 按扩展名查 MIME：只用于附件卡片决定点击后怎么打开，查不到归为二进制流。 */
    private fun mimeTypeForFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return FALLBACK_MIME_TYPE
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: FALLBACK_MIME_TYPE
    }
}
