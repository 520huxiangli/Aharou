package com.aharou.feature.editor.lsp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.workspace.domain.PathHomeResolver
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
    }

    /** 容器内项目根（LSP 的 rootUri）：`~/workspace` 展开成 `/root/workspace`。 */
    private val projectRoot: String
        get() = pathHomeResolver.expandHome(WorkspacePathMapper.CONTAINER_ROOT)

    private val projects = ConcurrentHashMap<String, LspProject>()

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
        return runCatching {
            val lspEditor = withContext(Dispatchers.Main) {
                project().getOrCreateEditor(pathHomeResolver.expandHome(containerFilePath)).also { lspEditor ->
                    lspEditor.wrapperLanguage = wrapper
                    lspEditor.editor = editor
                }
            }
            lspEditor.connect(throwException = false)
        }.getOrElse { e ->
            FileLogger.w(TAG, "挂载 LSP 失败: $containerFilePath", e)
            false
        }
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
    }
}
