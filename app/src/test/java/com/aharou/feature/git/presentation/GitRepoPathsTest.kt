package com.aharou.feature.git.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仓库路径换算：显示名（顶栏 / 弹层）与「仓库相对路径 → 工作区相对路径」（diff 页「在编辑器中打开」）。
 *
 * 后者是真修过的 bug：选中子目录仓库时，diff 里的 `src/Main.kt` 若原样交给编辑器，会被按工作区根
 * 解析成另一个文件。
 */
class GitRepoPathsTest {

    private val root = "/data/user/0/com.aharou/files/projects/default"

    @Test
    fun repoDisplayName_lastSegment() {
        assertEquals("git1", repoDisplayName("$root/git1"))
        assertEquals("git1", repoDisplayName("$root/git1/"))
        assertEquals("default", repoDisplayName(root))
    }

    @Test
    fun repoDisplayPath_rootShowsWorkspaceNameOnly() {
        assertEquals("default", repoDisplayPath(root, root))
        assertEquals("default", repoDisplayPath("$root/", "$root/"))
    }

    @Test
    fun repoDisplayPath_subdirShowsRelativeSegments() {
        assertEquals("default/git1", repoDisplayPath("$root/git1", root))
        assertEquals("default/nested/git2", repoDisplayPath("$root/nested/git2", "$root/"))
    }

    @Test
    fun repoRelativeToWorkspace_repoIsRoot_keepsPath() {
        assertEquals("src/Main.kt", repoRelativeToWorkspace("src/Main.kt", root, root))
    }

    @Test
    fun repoRelativeToWorkspace_subdirRepo_prefixesRepoPath() {
        assertEquals("git1/src/Main.kt", repoRelativeToWorkspace("src/Main.kt", "$root/git1", root))
        assertEquals(
            "nested/git2/src/Main.kt",
            repoRelativeToWorkspace("src/Main.kt", "$root/nested/git2/", "$root/")
        )
    }

    @Test
    fun repoRelativeToWorkspace_noRepo_keepsPath() {
        assertEquals("src/Main.kt", repoRelativeToWorkspace("src/Main.kt", null, root))
    }
}
