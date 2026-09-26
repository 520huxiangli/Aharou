package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutputTest {

    @Test
    fun defaultMode_keepsHeadAndTailWithOmittedMarker() {
        val output = BoundedOutput(headLimit = 5, tailLimit = 5)

        output.append("aaaaabbbbbccccc")

        assertTrue(output.truncated)
        val built = output.build()
        assertTrue(built.startsWith("aaaaa"))
        assertTrue(built.endsWith("ccccc"))
        assertTrue(built.contains("省略中间"))
    }

    @Test
    fun hardCapped_keepsPrefixOnlyAndReportsTruncation() {
        val output = BoundedOutput.hardCapped(10)

        output.append("1234567890")
        assertFalse(output.truncated)

        output.append("XYZ")
        assertTrue(output.truncated)
        assertTrue(output.build().startsWith("1234567890"))
    }

    @Test
    fun hardCapped_omittedMarkerGoesToTheEnd() {
        val output = BoundedOutput.hardCapped(4)

        output.append("abcd")
        output.append("efgh")

        val built = output.build()
        assertTrue(built.startsWith("abcd"))
        assertTrue(built.contains("已省略后续"))
        assertFalse(built.contains("保留开头与结尾"))
    }

    @Test
    fun hardCapped_staysBoundedForHugeInput() {
        val output = BoundedOutput.hardCapped(1024)

        repeat(1000) { output.append("x".repeat(100)) }

        assertTrue(output.truncated)
        assertTrue(output.build().length < 2048)
    }
}
