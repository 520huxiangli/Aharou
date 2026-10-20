package com.aharou.feature.git.domain

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.any
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GitRepository] 的多目录仓库发现与「当前仓库」切换（mock 掉 CommandEngine 与 WorkspaceRepository）。
 *
 * 关心的行为：工作区根 + 一层子目录的仓库识别、非仓库目录的识别依据（扫描结果里的 `d` 标记）、
 * 以及 [GitRepository.setRepoPath] 之后 git 命令的 cwd 是否真的落到选中的仓库。
 */
class GitRepositoryRepoDiscoveryTest {

    private val root = "/root/workspace"

    /**
     * 扫描命令（`for d in` 加通配符那条）回 [scanResult]；`rev-parse --is-inside-work-tree` 按 [insideWorkTree]
     * 判定；其余 git 命令回 [gitOutput]。
     */
    private fun createEngine(
        scanResult: CommandResult = CommandResult("", 0),
        insideWorkTree: (path: String?) -> Boolean = { false },
        gitOutput: String = ""
    ): CommandEngine {
        val engine = mockk<CommandEngine>()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } answers {
            val command = invocation.args[0].toString()
            val path = invocation.args[1] as String?
            when {
                command.contains("for d in") -> scanResult
                command.contains("--is-inside-work-tree") ->
                    CommandResult(if (insideWorkTree(path)) "true" else "false", 0)

                else -> CommandResult(gitOutput, 0)
            }
        }
        return engine
    }

    private fun createRepo(engine: CommandEngine, workspacePath: String = root): GitRepository {
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workspacePath
        return GitRepository(engine, workspace)
    }

    // ── discoverRepos：根 + 一层子目录 ────────────────────────────

    @Test
    fun discoverRepos_rootIsRepo_listsRootFirstThenSubdirReposSorted() = runTest {
        val repo = createRepo(
            createEngine(
                scanResult = CommandResult("r git2\nd docs\nr git1\n", 0),
                insideWorkTree = { it == root }
            )
        )

        assertEquals(listOf(root, "$root/git1", "$root/git2"), repo.discoverRepos())
    }

    @Test
    fun discoverRepos_rootNotRepo_picksSubdirRepo() = runTest {
        val repo = createRepo(
            createEngine(
                scanResult = CommandResult("d docs\nr git1\n", 0),
                insideWorkTree = { false }
            )
        )

        assertEquals(listOf("$root/git1"), repo.discoverRepos())
    }

    /**
     * 根是仓库时，扫描里标 `d`（自身不是仓库根）的子目录不能被当成仓库——
     * `git rev-parse --is-inside-work-tree` 在仓库内任意子目录都回 true，故扫描按 `<dir>/.git` 打标。
     */
    @Test
    fun discoverRepos_rootIsRepo_nonRepoSubdirsExcluded() = runTest {
        val repo = createRepo(
            createEngine(
                scanResult = CommandResult("d docs\nd src\n", 0),
                insideWorkTree = { it == root }
            )
        )

        assertEquals(listOf(root), repo.discoverRepos())
    }

    @Test
    fun discoverRepos_nothingFound_returnsEmpty() = runTest {
        val repo = createRepo(createEngine(scanResult = CommandResult("d docs\n", 0)))

        assertTrue(repo.discoverRepos().isEmpty())
    }

    @Test
    fun discoverRepos_scanFails_degradesToRootOnly() = runTest {
        val repo = createRepo(
            createEngine(scanResult = CommandResult("", 1), insideWorkTree = { it == root })
        )

        assertEquals(listOf(root), repo.discoverRepos())
    }

    // ── listSubdirectories：弹层展示的扫描范围 ────────────────────

    @Test
    fun listSubdirectories_includesNonRepoDirs() = runTest {
        val repo = createRepo(createEngine(scanResult = CommandResult("r git1\nd docs\n", 0)))

        assertEquals(listOf("$root/docs", "$root/git1"), repo.listSubdirectories())
    }

    @Test
    fun listSubdirectories_trailingSlashRoot_noDoubleSlash() = runTest {
        val repo = createRepo(
            createEngine(scanResult = CommandResult("d docs\n", 0)),
            workspacePath = "$root/"
        )

        assertEquals(listOf("$root/docs"), repo.listSubdirectories())
    }

    @Test
    fun listSubdirectories_noSubdirs_returnsEmpty() = runTest {
        val repo = createRepo(createEngine(scanResult = CommandResult("", 0)))

        assertTrue(repo.listSubdirectories().isEmpty())
    }

    // ── setRepoPath：git 命令的 cwd ──────────────────────────────

    @Test
    fun setRepoPath_commandsRunInSelectedRepo() = runTest {
        val engine = mockk<CommandEngine>()
        val cwd = slot<String?>()
        coEvery { engine.runCommandSyncUnbounded(any(), capture(cwd), any()) } returns CommandResult("", 0)
        val repo = createRepo(engine)

        repo.status()
        assertEquals(root, cwd.captured)

        repo.setRepoPath("$root/git1")
        repo.status()
        assertEquals("$root/git1", cwd.captured)
        assertEquals("$root/git1", repo.currentRepoPath)
    }

    @Test
    fun setRepoPathNull_fallsBackToWorkspaceRoot() = runTest {
        val engine = mockk<CommandEngine>()
        val cwd = slot<String?>()
        coEvery { engine.runCommandSyncUnbounded(any(), capture(cwd), any()) } returns CommandResult("", 0)
        val repo = createRepo(engine)

        repo.setRepoPath("$root/git1")

        repo.setRepoPath(null)
        repo.status()
        assertEquals(root, cwd.captured)
    }

    @Test
    fun setRepoPath_outsideWorkspace_ignored() = runTest {
        val engine = mockk<CommandEngine>()
        val cwd = slot<String?>()
        coEvery { engine.runCommandSyncUnbounded(any(), capture(cwd), any()) } returns CommandResult("", 0)
        val repo = createRepo(engine)

        // 切了工作区但选中路径还是旧工作区的：不能把 git 命令发过去。
        repo.setRepoPath("/root/other-workspace/git1")
        repo.status()
        assertEquals(root, cwd.captured)
    }

    @Test
    fun workspaceRoot_isWorkspaceCurrentPath() = runTest {
        val repo = createRepo(createEngine(), workspacePath = "$root/")

        assertEquals("$root/", repo.workspaceRoot())
    }
}
