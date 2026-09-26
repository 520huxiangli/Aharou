package com.aharou.feature.workspace.domain

import net.schmizz.sshj.xfer.FilePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程模式下 AI 路径 ↔ 远程真实路径的映射，以及 SFTP 权限位格式化（[RemoteSftpFileAccess] 的纯逻辑部分）。
 */
class RemoteSftpFileAccessMappingTest {

    private val wsRoot = "/data/user/0/com.aharou/files/projects/demo"
    private val home = "/home/dev"

    // ---------- remotePathFor：AI 路径 → 远程真实路径 ----------

    @Test
    fun workspace_root_maps_to_configured_root() {
        assertEquals(wsRoot, remotePathFor("~/workspace", wsRoot, home))
        assertEquals(wsRoot, remotePathFor("~/workspace/", wsRoot, home))
        assertEquals(wsRoot, remotePathFor("/home/dev/workspace", wsRoot, home))
        assertEquals(wsRoot, remotePathFor("/home/dev/workspace/", wsRoot, home))
    }

    @Test
    fun workspace_child_maps_under_root() {
        assertEquals("$wsRoot/src/Main.kt", remotePathFor("~/workspace/src/Main.kt", wsRoot, home))
        assertEquals("$wsRoot/app/build.gradle.kts", remotePathFor("/home/dev/workspace/app/build.gradle.kts", wsRoot, home))
        assertEquals("$wsRoot/a/b/c.txt", remotePathFor("~/workspace/a/b/c.txt", wsRoot, home))
    }

    @Test
    fun absolute_path_passes_through() {
        assertEquals("/etc/nginx/nginx.conf", remotePathFor("/etc/nginx/nginx.conf", wsRoot, home))
        assertEquals("/root/.aicode/skills/x/SKILL.md", remotePathFor("/root/.aicode/skills/x/SKILL.md", wsRoot, home))
    }

    @Test
    fun relative_path_hangs_under_root() {
        assertEquals("$wsRoot/src/Main.kt", remotePathFor("src/Main.kt", wsRoot, home))
    }

    @Test
    fun input_is_trimmed() {
        assertEquals(wsRoot, remotePathFor("  ~/workspace  ", wsRoot, home))
    }

    @Test
    fun home_unknown_falls_back_to_tilde_form() {
        // remoteHome 未获取到时 ~/workspace 前缀仍能映射（wsRoot 回退为字面 ~/workspace）
        assertEquals(wsRoot, remotePathFor("~/workspace", wsRoot, null))
        assertEquals("$wsRoot/foo.txt", remotePathFor("~/workspace/foo.txt", wsRoot, null))
        // 非工作区前缀的 ~ 路径不会展开，按现状挂到根下
        assertEquals("$wsRoot/~/other", remotePathFor("~/other", wsRoot, null))
    }

    // ---------- displayPathFor：远程真实路径 → AI 视角路径 ----------

    @Test
    fun display_root_maps_to_container_root() {
        assertEquals("~/workspace", displayPathFor(wsRoot, wsRoot))
        assertEquals("~/workspace/src/Main.kt", displayPathFor("$wsRoot/src/Main.kt", wsRoot))
    }

    @Test
    fun display_other_absolute_path_unchanged() {
        assertEquals("/etc/passwd", displayPathFor("/etc/passwd", wsRoot))
    }

    // ---------- formatPermissions：SFTP 权限集合 → rwx 字符串 ----------

    @Test
    fun format_permissions_typical_file() {
        assertEquals(
            "rw-r--r--",
            formatPermissions(
                setOf(FilePermission.USR_R, FilePermission.USR_W, FilePermission.GRP_R, FilePermission.OTH_R)
            )
        )
    }

    @Test
    fun format_permissions_directory() {
        assertEquals(
            "rwxr-xr-x",
            formatPermissions(
                setOf(
                    FilePermission.USR_R, FilePermission.USR_W, FilePermission.USR_X,
                    FilePermission.GRP_R, FilePermission.GRP_X,
                    FilePermission.OTH_R, FilePermission.OTH_X
                )
            )
        )
    }

    @Test
    fun format_permissions_empty() {
        assertEquals("---------", formatPermissions(emptySet()))
    }
}
