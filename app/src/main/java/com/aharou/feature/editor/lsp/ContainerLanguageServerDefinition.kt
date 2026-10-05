package com.aharou.feature.editor.lsp

import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.LanguageServerDefinition

/**
 * editor-lsp 的服务器定义：把 [LspServerCatalog] 里的一条语言扩展接到容器内的语言服务器上。
 *
 * editor-lsp 只要求 `createConnectionProvider` 给出 `(InputStream, OutputStream)`，
 * 因此进程侧完全由 [ContainerStdioConnectionProvider] 决定——服务器跑在 Aharou 容器里，
 * 与编辑器看到的路径（`/root/workspace/...`）一致。
 */
class ContainerLanguageServerDefinition(
    private val entry: LspServerCatalog.Entry,
    private val engine: LinuxContainerEngine,
    private val projectPath: String?
) : LanguageServerDefinition() {

    init {
        ext = entry.fileExtensions.first()
    }

    override val exts: List<String>
        get() = entry.fileExtensions

    override val name: String
        get() = entry.languageId

    override fun createConnectionProvider(workingDir: String): StreamConnectionProvider =
        ContainerStdioConnectionProvider(
            serverKey = "lsp:${entry.languageId}",
            engine = engine,
            program = entry.program,
            programArgs = entry.args,
            // workingDir 由 LspProject 传入（容器内项目根），优先用它当作容器内工作目录。
            projectPath = workingDir.ifBlank { projectPath.orEmpty() }.takeIf { it.isNotBlank() }
        )
}
