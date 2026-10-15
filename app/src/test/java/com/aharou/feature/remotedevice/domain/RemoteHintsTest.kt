package com.aharou.feature.remotedevice.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远端动作的「下一步」提示：命中顺序、兜底与拼接格式。 */
class RemoteHintsTest {

    private fun action(name: String) = RemoteAction(
        name = name,
        kind = RemoteActionKind.INFO,
        description = "",
        toolName = null,
        allowedArgs = emptySet()
    )

    @Test
    fun forError_prefersCodeOverMessage() {
        // 两个条件都命中时，错误码优先——它比文案关键词稳定
        val hint = RemoteHints.forError("STALE_FILE", "路径越界")
        assertTrue(hint!!.contains("重新读"))
    }

    @Test
    fun forError_fallsBackToMessageKeyword() {
        val hint = RemoteHints.forError(null, "路径越界，拒绝访问")
        assertTrue(hint!!.contains("~/workspace"))
    }

    @Test
    fun forError_unknownCodeStillGivesFallback() {
        val hint = RemoteHints.forError("SOMETHING_NEW", "莫名其妙")
        assertTrue(hint!!.contains("info"))
    }

    @Test
    fun forSuccess_onlyForActionsWithFollowUp() {
        assertTrue(
            RemoteHints.forSuccess(action(RemoteActionCatalog.ACTION_WRITE_FILE))!!.contains("read_file")
        )
        assertNull(RemoteHints.forSuccess(action(RemoteActionCatalog.ACTION_INFO)))
    }

    @Test
    fun append_skipsBlankHint() {
        assertEquals("正文", RemoteHints.append("正文", null))
        assertEquals("正文", RemoteHints.append("正文", ""))
    }

    @Test
    fun append_putsHintOnItsOwnLine() {
        assertEquals("正文\nhint: 下一步", RemoteHints.append("正文", "下一步"))
    }
}
