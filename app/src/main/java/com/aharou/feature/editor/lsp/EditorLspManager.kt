package com.aharou.feature.editor.lsp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.workspace.domain.PathHomeResolver
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「跳转到定义」的目标位置。[path] 已还原成 App 的编辑路径写法，[line] / [column] 均为 0 基
 * （与 LSP 一致），由调用方决定怎么落到界面上。
 */
data class DefinitionTarget(val path: String, val line: Int, val column: Int)

/**
 * 编辑器侧的语言服务器（LSP）管理器：把官方 editor-lsp 接到 Aharou 容器里跑的语言服务器上。
 *
 * 分工：editor-lsp 负责协议本身（initialize / didOpen / didChange / publishDiagnostics …），
 * 进程与流由 [ContainerStdioConnectionProvider] 提供，语言服务器本体与安装/探活由
 * [LanguageServerInstaller] 管。本类只做「哪个文件该用哪个语言」与工程实例的持有。
 *
 * 语言服务器只在**本地容器**里跑：远程 SSH 模式下工作区在远端、本地容器并未挂载它，
 * 因此不做挂载（日志里记一笔），避免给出错误路径的诊断。
 */
@Singleton
class EditorLspManager @Inject constructor(
    private val engine: LinuxContainerEngine,
    private val installer: LanguageServerInstaller,
    private val pathHomeResolver: PathHomeResolver
) {

    private companion object {
        const val TAG = "EditorLsp"

        /** 查定义的最长等待：语言服务器没响应时不能把 UI 吊死，超时当没找到处理。 */
        const val DEFINITION_TIMEOUT_SECONDS = 5L
    }

    /** 容器内项目根（LSP 的 rootUri）：`~/workspace` 展开成 `/root/workspace`。 */
    private val projectRoot: String
        get() = pathHomeResolver.expandHome(WorkspacePathMapper.CONTAINER_ROOT)

    private val projects = ConcurrentHashMap<String, LspProject>()

    /**
     * 串行化挂载：同一编辑器被组合两次时会连着触发两回，而 stdio 名额是全局的，
     * 第二次再抢必然失败并把第一次的成功结果覆盖成 false（2026-10-07 实测：顶栏箭头不出现）。
     */
    private val attachLock = Mutex()

    /** 已挂到语言服务器上的文件：容器内绝对路径 → 编辑器实例，供主动请求（如查定义）复用同一条连接。 */
    private val editors = ConcurrentHashMap<String, LspEditor>()

    /**
     * 把编辑器挂到语言服务器上，成功返回 true。顺序由 editor-lsp 的设计定死，不能改：
     * 1. `wrapperLanguage = wrapper`——LSP 语言内部会转发给原本的 TextMate，着色不断；
     * 2. `editor = codeEditor`——绑定 View，内部换上 LspLanguage 并挂 UI 代理；
     * 3. `connect()`——连服务器，**内部才发 `didOpen`**，诊断从此开始推。
     *
     * 少了绑定或 connect 就会出现「服务器装好了但一条诊断都没有」（2026-10-05 实测踩过）。
     * 绑定与 connect 会碰编辑器 View，需在主线程发起；连不上不致命，着色照旧。
     */
    suspend fun attach(editor: CodeEditor, containerFilePath: String, wrapper: Language): Boolean {
        val entry = LspServerCatalog.byPath(containerFilePath) ?: return false
        val cached = installer.cachedStatus(entry.extension)
        val status = cached ?: installer.probe(entry.extension)
        if (status !is ExtensionStatus.Installed) {
            FileLogger.d(TAG, "${entry.languageId} 语言服务器不可用（$status），保持 TextMate")
            return false
        }
        val absolutePath = pathHomeResolver.expandHome(containerFilePath)
        return attachLock.withLock {
            // 已经挂上就直接算成功：重复触发不能再抢名额，也不能把已有连接推翻。
            editors[absolutePath]?.takeIf { it.isConnected }?.let { return@withLock true }
            runCatching {
                val lspEditor = withContext(Dispatchers.Main) {
                    project().getOrCreateEditor(absolutePath).also { lspEditor ->
                        lspEditor.wrapperLanguage = wrapper
                        lspEditor.editor = editor
                        editors[absolutePath] = lspEditor
                    }
                }
                lspEditor.connect(throwException = false).also { ok ->
                    // connect 失败是静默的（不抛异常也不返回原因），这里补上，否则挂不上时无从查起。
                    if (!ok) FileLogger.w(TAG, "挂载 LSP 未成功（connect 返回 false）: $containerFilePath")
                }
            }.getOrElse { e ->
                FileLogger.w(TAG, "挂载 LSP 失败: $containerFilePath", e)
                false
            }
        }
    }

    /**
     * 查询光标处符号的定义位置。没挂语言服务器、没找到定义、服务器未响应都会返回 null，由调用方决定怎么提示。
     *
     * 走的是 editor-lsp 底层的 `RequestManager`（该模块只做了悬停/补全/代码操作，没提供跳定义的上层封装）。
     * uri 必须与它 `didOpen` 时发的保持一致——`FileUri` 就是 `"file://" + 绝对路径`，这里照此拼。
     */
    suspend fun findDefinition(containerFilePath: String, line: Int, column: Int): DefinitionTarget? {
        val absolutePath = pathHomeResolver.expandHome(containerFilePath)
        val lspEditor = editors[absolutePath]?.takeIf { it.isConnected } ?: return null
        val params = DefinitionParams(
            TextDocumentIdentifier("file://$absolutePath"),
            Position(line.coerceAtLeast(0), column.coerceAtLeast(0))
        )
        val response = runCatching {
            val future = lspEditor.requestManager.definition(params) ?: return null
            withContext(Dispatchers.IO) { future.get(DEFINITION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }.getOrElse { e ->
            FileLogger.w(TAG, "查询定义失败: $absolutePath", e)
            return null
        }
        // 服务器可以返回 Location 列表或 LocationLink 列表，两种都要认。
        val location: Location? = if (response.isLeft) {
            response.left?.firstOrNull()
        } else {
            response.right?.firstOrNull()?.let { link ->
                val range = link.targetRange
                if (range == null) null else Location(link.targetUri, range)
            }
        }
        if (location == null) return null
        val target = location.uri?.let(::uriToFilePath) ?: return null
        val start = location.range?.start ?: return null
        return DefinitionTarget(displayPath(target), start.line, start.character)
    }

    /**
     * 把容器内路径统一成 App 的编辑路径写法：工作区内用 `~/workspace/…`（与文件树打开时一致），
     * 其余保留绝对路径。同一个文件写法不同会在标签栏里开出两份，比对与打开都先过它。
     */
    fun displayPath(path: String): String {
        val absolute = pathHomeResolver.expandHome(path).replace('\\', '/')
        val workspaceRoot = pathHomeResolver.home().trimEnd('/') + "/workspace"
        return when {
            absolute == workspaceRoot -> WorkspacePathMapper.CONTAINER_ROOT
            absolute.startsWith("$workspaceRoot/") ->
                WorkspacePathMapper.CONTAINER_ROOT + "/" + absolute.removePrefix("$workspaceRoot/")
            else -> absolute
        }
    }

    /** `file://` URI 还原成容器内绝对路径；非本地文件（其它 scheme）返回 null。 */
    private fun uriToFilePath(uri: String): String? {
        if (!uri.startsWith("file://")) return null
        return runCatching { URI(uri).path }.getOrNull() ?: uri.removePrefix("file://")
    }

    /** 语言服务器进程的持有者（每个容器内项目根一个）。首次使用时注册收录的服务器定义。 */
    private fun project(): LspProject = projects.getOrPut(projectRoot) {
        LspProject(projectRoot).apply {
            LspServerCatalog.ALL.forEach { entry ->
                runCatching { addServerDefinition(ContainerLanguageServerDefinition(entry, engine, projectRoot)) }
                    .onFailure { FileLogger.w(TAG, "注册语言服务器定义失败: ${entry.languageId}", it) }
            }
        }
    }

    /** 停掉所有语言服务器（容器 profile 变化、工作区切换时调用，避免旧进程钉在旧 rootfs 上）。 */
    fun closeAll() {
        projects.values.forEach { runCatching { it.closeAllEditors() } }
        projects.clear()
        editors.clear()
    }
}
