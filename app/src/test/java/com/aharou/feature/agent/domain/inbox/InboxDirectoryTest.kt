package com.aharou.feature.agent.domain.inbox

import com.aharou.feature.workspace.domain.WorkspaceIdentity
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.domain.model.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxDirectoryTest {
    private fun workspace(
        path: String,
        type: WorkspaceType = WorkspaceType.INTERNAL,
        available: Boolean = true
    ) = Workspace(
        name = path.trimEnd('/').substringAfterLast('/'),
        path = path,
        type = type,
        available = available
    )

    @Test
    fun noLocalWorkspaceMeansNoFile() {
        assertNull(InboxDirectory.render(emptyList()))
        assertNull(InboxDirectory.render(listOf(workspace("", type = WorkspaceType.INTERNAL))))
    }

    @Test
    fun remoteAndMissingWorkspacesAreLeftOut() {
        val text = InboxDirectory.render(
            listOf(
                workspace("/data/user/0/com.aharou.agent/files/projects/Aharou"),
                workspace("/srv/app", type = WorkspaceType.REMOTE),
                workspace("/storage/emulated/0/gone", type = WorkspaceType.EXTERNAL_LOCAL, available = false)
            )
        )

        val body = text.orEmpty()
        assertTrue(body.contains("| Aharou |"))
        assertTrue(!body.contains("/srv/"))
        assertTrue(!body.contains("gone"))
    }

    @Test
    fun everyRowCarriesTheInboxIdFromWorkspaceIdentity() {
        val path = "/data/user/0/com.aharou.agent/files/projects/fox"
        val body = InboxDirectory.render(listOf(workspace(path))).orEmpty()

        val id = WorkspaceIdentity.idFor(path)!!
        assertTrue(body.contains("| fox | ~/shared/inbox/$id.md | 本机工作区 |"))
    }

    @Test
    fun rowsAreSortedSoRepeatedRefreshesRenderIdentically() {
        val a = "/data/user/0/com.aharou.agent/files/projects/Aharou"
        val b = "/data/user/0/com.aharou.agent/files/projects/fox"

        val forward = InboxDirectory.render(listOf(workspace(a), workspace(b)))
        val backward = InboxDirectory.render(listOf(workspace(b), workspace(a)))

        assertEquals(forward, backward)
        val text = forward!!
        assertTrue(text.indexOf("| Aharou |") < text.indexOf("| fox |"))
    }

    @Test
    fun duplicatePathsAreListedOnce() {
        val path = "/data/user/0/com.aharou.agent/files/projects/Aharou"
        val body = InboxDirectory.render(listOf(workspace(path), workspace(path))).orEmpty()

        val id = WorkspaceIdentity.idFor(path)!!
        assertEquals(1, Regex(Regex.escape(id)).findAll(body).count())
    }

    @Test
    fun tableHeaderIsPresentAndPathIsContainerRelative() {
        val body = InboxDirectory.render(
            listOf(workspace("/data/user/0/com.aharou.agent/files/projects/Aharou"))
        ).orEmpty()

        assertTrue(body.startsWith("# 工作区通讯录\n"))
        assertTrue(body.contains("| 工作区 | 信箱 | 说明 |"))
        assertTrue(body.contains("| --- | --- | --- |"))
        // AI 看到的是容器视角：绝对宿主路径不能出现在表里。
        assertTrue(!body.contains("/data/user/0"))
        assertEquals("~/shared/inbox/workspaces.md", InboxDirectory.CONTAINER_PATH)
    }
}
