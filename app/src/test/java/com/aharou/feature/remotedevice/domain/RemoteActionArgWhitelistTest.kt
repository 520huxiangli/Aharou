package com.aharou.feature.remotedevice.domain

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远端动作的参数取值白名单：允许的过、越权的拒、没声明的不限制。 */
class RemoteActionArgWhitelistTest {

    private fun uiAction() = RemoteAction(
        name = RemoteActionCatalog.ACTION_UI,
        kind = RemoteActionKind.UI,
        description = "",
        toolName = RemoteActionCatalog.TOOL_VSCREEN,
        allowedArgs = setOf("action", "text"),
        argAllowedValues = mapOf("action" to RemoteActionCatalog.UI_ALLOWED_ACTIONS)
    )

    @Test
    fun allowListedValue() {
        assertNull(uiAction().firstViolatedArg(mapOf("action" to JsonPrimitive("tap"))))
    }

    @Test
    fun rejectValueOutsideWhitelist() {
        // 只允许 vscreen 自己的操作；`shell` 不是其中之一，必须挡住
        val violated = uiAction().firstViolatedArg(mapOf("action" to JsonPrimitive("shell")))
        assertEquals("action", violated?.first)
        assertTrue(violated!!.second.contains("shot"))
        assertTrue(!violated.second.contains("shell"))
    }

    @Test
    fun allowTaskAfterWhitelistOpened() {
        // 2026-10-25 远程协助重做时把 stop / task / trace 一并放开：这几个必须放行
        assertNull(uiAction().firstViolatedArg(mapOf("action" to JsonPrimitive("task"))))
        assertNull(uiAction().firstViolatedArg(mapOf("action" to JsonPrimitive("stop"))))
        assertNull(uiAction().firstViolatedArg(mapOf("action" to JsonPrimitive("trace"))))
    }

    @Test
    fun ignoreMissingArgButRejectBlankValue() {
        // 没传 action：交给工具自己报「缺少 action」；但传了空白等于给了非法值，这一层就该拦
        assertNull(uiAction().firstViolatedArg(emptyMap()))
        assertEquals(
            "action",
            uiAction().firstViolatedArg(mapOf("action" to JsonPrimitive("   ")))?.first
        )
    }

    @Test
    fun actionWithoutWhitelistRestrictsNothing() {
        val plain = RemoteAction(
            name = "info",
            kind = RemoteActionKind.INFO,
            description = "",
            toolName = null,
            allowedArgs = emptySet()
        )
        assertNull(plain.firstViolatedArg(mapOf("whatever" to JsonPrimitive("x"))))
    }
}
