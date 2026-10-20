package com.aharou.feature.workspace.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作区标识：信箱文件名与项目级配置目录都依赖它，必须稳定、可读、不撞车。 */
class WorkspaceIdentityTest {

    @Test
    fun idUsesDirectoryNameAndIgnoresTrailingSlash() {
        val plain = WorkspaceIdentity.idFor("/data/data/com.aharou/files/projects/fox")
        val trailing = WorkspaceIdentity.idFor("/data/data/com.aharou/files/projects/fox/")

        assertEquals(plain, trailing)
        assertTrue(plain!!.startsWith("fox-"))
    }

    @Test
    fun sameDirectoryNameInDifferentPlacesDoesNotCollide() {
        val left = WorkspaceIdentity.idFor("/srv/a/fox")
        val right = WorkspaceIdentity.idFor("/srv/b/fox")

        assertNotEquals(left, right)
    }

    @Test
    fun blankPathHasNoIdentity() {
        assertNull(WorkspaceIdentity.idFor(""))
        assertNull(WorkspaceIdentity.idFor("   "))
    }

    @Test
    fun shortHashIsEightLowercaseHexChars() {
        val hash = WorkspaceIdentity.shortHash("/srv/a/fox")

        assertEquals(8, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
