package com.aharou.feature.editor.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSaveStateTest {
    private val request = EditorSaveRequest("/one", "one:profile", 1, 1, "~/workspace/a", "saved")

    @Test
    fun conflict_retainsOriginalPathAndContentAfterAnotherRequest() {
        val state = EditorSaveState()
        state.conflict = SaveResult.Conflict(request, "disk")
        state.track(request.copy(id = 2, path = "~/workspace/b", content = "other"), null)
        assertEquals("~/workspace/a", state.conflict?.request?.path)
        assertEquals("saved", state.conflict?.request?.content)
    }

    @Test
    fun completion_afterFurtherTyping_doesNotLeave() {
        val state = EditorSaveState()
        var left = false
        state.track(request, PendingLeave(0) { left = true })
        state.complete(request, request.path, "newer")?.invoke()
        assertFalse(left)
    }

    @Test
    fun completion_forInactivePath_doesNotLeave() {
        val state = EditorSaveState()
        state.track(request, PendingLeave(0) { error("unexpected leave") })
        assertNull(state.complete(request, "~/workspace/b", "saved"))
    }

    @Test
    fun oldCompletion_doesNotConsumeNewRequestLeave() {
        val state = EditorSaveState()
        val newer = request.copy(id = 2)
        var left = false
        state.track(newer, PendingLeave(0) { left = true })
        assertNull(state.complete(request, request.path, request.content))
        state.complete(newer, newer.path, newer.content)?.invoke()
        assertTrue(left)
    }

    @Test
    fun discardedSnapshot_isSkippedOnlyForItsOriginalRelease() {
        val state = EditorSaveState()
        state.discardSnapshot(request.path)
        assertTrue(state.retainSnapshot("~/workspace/b"))
        assertFalse(state.retainSnapshot(request.path))
        assertTrue(state.retainSnapshot(request.path))
    }
}
