package com.aharou.feature.agent.domain.inbox

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.domain.WorkspaceIdentity
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.domain.model.WorkspaceType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作区通讯录：共享区 `~/shared/inbox/workspaces.md`，把本机每个工作区的信箱地址摊成一张表。
 *
 * AI 在系统提示里只知道**自己**的信箱；别人的 id 是工作区绝对路径的 MD5 短哈希，它算不出来，
 * 于是要么猜错地址（消息写进没人读的文件），要么干脆留不了话。这张表把「给谁写哪个文件」摊开，
 * 人和 AI 都能直接查。
 *
 * 由 App 维护：打开工作区 / 工作区列表变化时刷新；内容没变就不重写（幂等），
 * 免得每次切工作区都产生一次没意义的写盘与文件监听事件。
 */
@Singleton
class InboxDirectory @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val sharedInbox: SharedInbox
) {
    /** 刷新通讯录；远程模式（没有本地共享区）或还没有本机工作区时什么都不做。 */
    fun refresh(workspaces: List<Workspace>) {
        if (!sharedInbox.enabled()) return
        val text = render(workspaces) ?: return
        val file = File(File(containerInstaller.sharedDir, INBOX_DIR), FILE_NAME)
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { FileLogger.w(TAG, "刷新工作区通讯录失败: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "InboxDirectory"
        private const val INBOX_DIR = "inbox"
        private const val FILE_NAME = "workspaces.md"

        /** 通讯录的容器内路径；提示词与文档里都用这个写法。 */
        const val CONTAINER_PATH = "${SharedInbox.CONTAINER_INBOX_DIR}/$FILE_NAME"

        /**
         * 生成通讯录正文；没有可列的本机工作区时返回 null（不写空表）。
         *
         * 只收本机工作区：远程工作区的 `~/shared` 在服务器上，与这里这份共享区不是同一份，
         * 列进来等于给出一个写了也没人读的地址。
         *
         * 排序按标识串（目录名在前），同一批工作区每次得到同一份正文，刷新才能判等跳过。
         */
        fun render(workspaces: List<Workspace>): String? {
            val rows = workspaces.asSequence()
                .filter { it.type != WorkspaceType.REMOTE && it.available && it.path.isNotBlank() }
                .mapNotNull { WorkspaceIdentity.idFor(it.path) }
                .distinct()
                .sorted()
                .map { id ->
                    "| ${id.substringBeforeLast('-')} | ${SharedInbox.CONTAINER_INBOX_DIR}/$id.md | 本机工作区 |"
                }
                .toList()
            if (rows.isEmpty()) return null
            return buildString {
                append("# 工作区通讯录\n\n")
                append("> 本文件由 Aharou 自动维护（本机工作区列表变化时刷新），手改会被覆盖。\n")
                append("> 给别的工区留话就往对方信箱文件末尾追加一行，格式见系统提示词的「跨工作区信箱」约定。\n\n")
                append("| 工作区 | 信箱 | 说明 |\n")
                append("| --- | --- | --- |\n")
                rows.forEach { append(it).append('\n') }
            }
        }
    }
}
