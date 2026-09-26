package com.aharou.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** [normalizeRemoteWorkspacePath]：空值与历史默认值归一化为 [DEFAULT_REMOTE_WORKSPACE_ROOT]。 */
class RemoteWorkspacePathNormalizationTest {

    @Test
    fun blank_falls_back_to_default() {
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath(null, "root"))
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("", "root"))
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("   ", "root"))
    }

    @Test
    fun legacy_hardcoded_default_is_normalized() {
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("/home/root/workspace", "root"))
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("/home/dev/workspace", "dev"))
    }

    @Test
    fun tilde_workspace_is_normalized() {
        // ~/workspace 是符号链接占用的路径，不能当工作区根
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("~/workspace", "root"))
    }

    @Test
    fun explicit_path_is_kept() {
        assertEquals("/data/projects", normalizeRemoteWorkspacePath("/data/projects", "root"))
        assertEquals("~/proj", normalizeRemoteWorkspacePath("~/proj", "root"))
        // 用户名不匹配时不误判为用户自己的默认值
        assertEquals("/home/dev/workspace", normalizeRemoteWorkspacePath("/home/dev/workspace", "root"))
    }
}
