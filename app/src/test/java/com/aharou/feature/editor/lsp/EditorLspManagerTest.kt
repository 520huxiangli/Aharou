package com.aharou.feature.editor.lsp

import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.PathHomeResolver
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EditorLspManager.displayPath：容器内绝对路径 → 编辑器标签统一写法。
 *
 * 只测这一个纯函数：把 `$HOME/workspace` 下的文件折回 `~/workspace/…`（与文件树打开时一致），
 * 其余路径保留绝对形式；反斜杠一律归一成正斜杠。同一文件两种写法会在标签栏里开出两份，
 * 所以边界（工作区根本身、同名前缀如 `/root/workspaceX`、home 带尾斜杠）都要卡住。
 * 引擎与安装器仅为构造占位，用 mockk；行为只依赖真实的 PathHomeResolver。
 */
class EditorLspManagerTest {

    private val containerRoot = WorkspacePathMapper.CONTAINER_ROOT // "~/workspace"

    private fun managerWithHome(home: String): EditorLspManager {
        val resolver = PathHomeResolver(
            ExecutionModeHolder().apply { setMode(ExecutionMode.LOCAL_PROOT) },
            mockk<RemoteSshConnection>(relaxed = true),
        ).apply { containerHome = home }
        return EditorLspManager(
            mockk<LinuxContainerEngine>(relaxed = true),
            mockk<LanguageServerInstaller>(relaxed = true),
            resolver,
        )
    }

    @Test
    fun displayPath_fileUnderWorkspace_usesContainerRootPrefix() {
        assertEquals(
            "$containerRoot/src/Main.kt",
            managerWithHome("/root").displayPath("/root/workspace/src/Main.kt"),
        )
    }

    @Test
    fun displayPath_workspaceRootItself_returnsContainerRoot() {
        assertEquals(containerRoot, managerWithHome("/root").displayPath("/root/workspace"))
    }

    @Test
    fun displayPath_alreadyTildeForm_isNormalizedToContainerRoot() {
        assertEquals(
            "$containerRoot/src/Main.kt",
            managerWithHome("/root").displayPath("~/workspace/src/Main.kt"),
        )
    }

    @Test
    fun displayPath_outsideWorkspace_keepsAbsolutePath() {
        assertEquals("/etc/hosts", managerWithHome("/root").displayPath("/etc/hosts"))
    }

    @Test
    fun displayPath_siblingWithSamePrefix_isNotTreatedAsWorkspace() {
        assertEquals(
            "/root/workspaceX/a.kt",
            managerWithHome("/root").displayPath("/root/workspaceX/a.kt"),
        )
    }

    @Test
    fun displayPath_backslashesAreNormalized() {
        assertEquals(
            "$containerRoot/src/a.kt",
            managerWithHome("/root").displayPath("/root\\workspace\\src\\a.kt"),
        )
    }

    @Test
    fun displayPath_homeWithTrailingSlash_stillMatches() {
        assertEquals(
            "$containerRoot/src/a.kt",
            managerWithHome("/root/").displayPath("/root/workspace/src/a.kt"),
        )
    }
}
