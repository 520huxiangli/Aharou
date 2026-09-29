package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * WorkspacePathMapper：容器路径 ↔ 宿主真实路径互转。
 * 固定环境：宿主工作区=/data/ws、AI 配置目录=/data/aicode、rootfs=/data/rootfs、容器 home=/root。
 * activeProfileIdFlow 用 emptyFlow()，让 init 协程的 collect 永不触发，避免真实 DataStore 调用
 * 与异步 profile 切换（extraBindings 挂载分支依赖该异步更新，纯 JVM 下竞态不稳定，不在本类覆盖）。
 */
class WorkspacePathMapperTest {

    private val workspaceRepository: WorkspaceRepository = mockk()
    private val containerInstaller: ContainerInstaller = mockk()
    private val containerSettingsRepository: ContainerSettingsRepository = mockk()
    private val pathHomeResolver: PathHomeResolver = mockk()

    private val wsRoot = "/data/ws"
    private val wsA = "/data/ws-a"
    private val wsB = "/data/ws-b"
    private val aicodeDir = "/data/aicode"
    private val rootfsDir = "/data/rootfs"
    private val sharedDir = "/data/shared"
    private val home = "/root"

    private fun newMapper(): WorkspacePathMapper {
        every { workspaceRepository.currentPath() } returns wsRoot
        every { containerInstaller.aharouDir } returns File(aicodeDir)
        every { containerInstaller.rootfsDirFor(any()) } returns File(rootfsDir)
        every { containerInstaller.sharedDir } returns File(sharedDir)
        every { containerSettingsRepository.activeProfileIdFlow } returns emptyFlow()
        every { pathHomeResolver.home() } returns home
        // expandHome 保持真实展开语义，让 toHostFile 里的 ~ 前缀与生产行为一致
        every { pathHomeResolver.expandHome(any()) } answers {
            val p = firstArg<String>()
            when {
                p == "~" -> pathHomeResolver.home()
                p.startsWith("~/") -> pathHomeResolver.home().trimEnd('/') + p.removePrefix("~")
                else -> p
            }
        }
        return WorkspacePathMapper(workspaceRepository, containerInstaller, containerSettingsRepository, pathHomeResolver)
    }

    // ---------- toHostFile：容器路径 → 宿主文件 ----------

    @Test
    fun toHostFile_workspaceRootTilde_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace/"))
    }

    @Test
    fun toHostFile_workspaceRootExpandedForm_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace/"))
    }

    @Test
    fun toHostFile_workspaceChild_mapsUnderHostWorkspace() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("~/workspace/src/Main.kt"))
        assertEquals(File(wsRoot, "app/build.gradle.kts"), newMapper().toHostFile("$home/workspace/app/build.gradle.kts"))
    }

    @Test
    fun toHostFile_aicodeRoot_mapsToAicodeDir() {
        assertEquals(File(aicodeDir), newMapper().toHostFile("/root/.aicode"))
    }

    @Test
    fun toHostFile_aicodeChild_mapsUnderAicodeDir() {
        assertEquals(File(aicodeDir, "skills/x/SKILL.md"), newMapper().toHostFile("/root/.aicode/skills/x/SKILL.md"))
    }

    @Test
    fun toHostFile_otherAbsolutePath_mapsToRootfs() {
        assertEquals(File(rootfsDir, "etc/hosts"), newMapper().toHostFile("/etc/hosts"))
        // /root 本身不在工作区/配置目录前缀下，走通用绝对路径规则落到 rootfs
        assertEquals(File(rootfsDir, "root"), newMapper().toHostFile("/root"))
    }

    @Test
    fun toHostFile_sharedRoot_mapsToSharedDir() {
        assertEquals(File(sharedDir), newMapper().toHostFile("/root/shared"))
        assertEquals(File(sharedDir), newMapper().toHostFile("/root/shared/"))
    }

    @Test
    fun toHostFile_sharedChild_mapsUnderSharedDir() {
        assertEquals(File(sharedDir, "notes/a.md"), newMapper().toHostFile("/root/shared/notes/a.md"))
    }

    @Test
    fun toHostFile_relativePath_hangsUnderWorkspaceRoot() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("src/Main.kt"))
    }

    @Test
    fun toHostFile_inputIsTrimmed() {
        assertEquals(File(wsRoot), newMapper().toHostFile("  ~/workspace  "))
    }

    // ---------- toContainerPath：宿主路径 → 容器路径 ----------

    @Test
    fun toContainerPath_workspaceRoot_mapsToContainerRoot() {
        assertEquals("~/workspace", newMapper().toContainerPath(wsRoot))
    }

    @Test
    fun toContainerPath_workspaceChild_mapsToContainerForm() {
        assertEquals("~/workspace/src/Main.kt", newMapper().toContainerPath("$wsRoot/src/Main.kt"))
    }

    @Test
    fun toContainerPath_expandedHomeForm_mapsToContainerForm() {
        assertEquals("~/workspace", newMapper().toContainerPath("$home/workspace"))
        assertEquals("~/workspace/a.txt", newMapper().toContainerPath("$home/workspace/a.txt"))
    }

    @Test
    fun toContainerPath_aharou_mapsToAharouRootForm() {
        assertEquals("/root/.aharou", newMapper().toContainerPath(aicodeDir))
        assertEquals("/root/.aharou/skills/x/SKILL.md", newMapper().toContainerPath("$aicodeDir/skills/x/SKILL.md"))
    }

    @Test
    fun toContainerPath_rootfs_mapsToContainerAbsolute() {
        assertEquals("/", newMapper().toContainerPath(rootfsDir))
        assertEquals("/etc/hosts", newMapper().toContainerPath("$rootfsDir/etc/hosts"))
    }

    @Test
    fun toContainerPath_shared_mapsToSharedRootForm() {
        assertEquals("/root/shared", newMapper().toContainerPath(sharedDir))
        assertEquals("/root/shared/notes/a.md", newMapper().toContainerPath("$sharedDir/notes/a.md"))
    }

    @Test
    fun toContainerPath_unmappedPath_unchanged() {
        assertEquals("/somewhere/else", newMapper().toContainerPath("/somewhere/else"))
    }

    // ---------- 会话绑定工作区 ----------
    // 本单例被多个会话共享，工作区必须由调用方传进来；全局「当前工作区」只是没传时的回退。

    @Test
    fun toHostFile_explicitWorkspace_overridesGlobalCurrent() {
        val mapper = newMapper()
        assertEquals(File(wsB, "src/Main.kt"), mapper.toHostFile("~/workspace/src/Main.kt", wsB))
        assertEquals(File(wsB), mapper.toHostFile("~/workspace", wsB))
    }

    @Test
    fun toHostFile_relativePath_usesExplicitWorkspace() {
        assertEquals(File(wsB, "a.txt"), newMapper().toHostFile("a.txt", wsB))
    }

    @Test
    fun toHostFile_blankOrNullWorkspace_fallsBackToGlobalCurrent() {
        val mapper = newMapper()
        assertEquals(File(wsRoot, "a.txt"), mapper.toHostFile("~/workspace/a.txt", null))
        assertEquals(File(wsRoot, "a.txt"), mapper.toHostFile("~/workspace/a.txt", ""))
        assertEquals(File(wsRoot, "a.txt"), mapper.toHostFile("~/workspace/a.txt", "   "))
    }

    @Test
    fun toHostFile_explicitWorkspace_stillRejectsEscape() {
        assertThrows(IllegalArgumentException::class.java) {
            newMapper().toHostFile("~/workspace/../../shared_prefs/x.xml", wsB)
        }
    }

    @Test
    fun toContainerPath_explicitWorkspace_mapsToContainerForm() {
        assertEquals("~/workspace/a.txt", newMapper().toContainerPath("$wsB/a.txt", wsB))
    }

    @Test
    fun toContainerPath_otherWorkspace_notTreatedAsCurrent() {
        // 既不是全局当前也不是绑定目标的工作区，不能被当成 ~/workspace 回显
        assertEquals("$wsA/a.txt", newMapper().toContainerPath("$wsA/a.txt", wsB))
    }
}
