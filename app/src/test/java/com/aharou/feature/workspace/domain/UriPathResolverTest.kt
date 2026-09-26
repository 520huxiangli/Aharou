package com.aharou.feature.workspace.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UriPathResolverTest {

    @Test
    fun primaryRoot_mapsToEmulatedRoot() {
        assertEquals("/storage/emulated/0/", UriPathResolver.resolveDocId("primary:"))
    }

    @Test
    fun primaryRoot_withCustomRoot_usesProvidedRoot() {
        assertEquals("/storage/emulated/42/", UriPathResolver.resolveDocId("primary:", "/storage/emulated/42"))
        assertEquals("/storage/emulated/42/Docs", UriPathResolver.resolveDocId("primary:Docs", "/storage/emulated/42"))
    }

    @Test
    fun primarySubdir_mapsToEmulated() {
        assertEquals("/storage/emulated/0/Download", UriPathResolver.resolveDocId("primary:Download"))
    }

    @Test
    fun primaryDeepPath_mapsWithSlash() {
        assertEquals("/storage/emulated/0/My Project/src", UriPathResolver.resolveDocId("primary:My Project/src"))
    }

    @Test
    fun primaryLeadingSlash_isTrimmed() {
        assertEquals("/storage/emulated/0/Documents", UriPathResolver.resolveDocId("primary:/Documents"))
    }

    @Test
    fun volumeRoot_mapsToStorageVolume() {
        assertEquals("/storage/1234-ABCD/", UriPathResolver.resolveDocId("1234-ABCD:"))
    }

    @Test
    fun volumeSubdir_mapsToStorageVolume() {
        assertEquals("/storage/1234-ABCD/Download", UriPathResolver.resolveDocId("1234-ABCD:Download"))
    }

    @Test
    fun lowercaseVolume_mapsToStorageVolume() {
        assertEquals("/storage/ab12-cd34/Docs", UriPathResolver.resolveDocId("ab12-cd34:Docs"))
    }

    @Test
    fun invalidVolume_returnsNull() {
        assertNull(UriPathResolver.resolveDocId("abc:Download"))
    }

    @Test
    fun malformedNoColon_returnsNull() {
        assertNull(UriPathResolver.resolveDocId("primary"))
    }

    @Test
    fun colonAtStart_returnsNull() {
        assertNull(UriPathResolver.resolveDocId(":Download"))
    }

    @Test
    fun unknownShape_returnsNull() {
        // 非 externalstorage docId 形态（如多用户 home:）不在白名单
        assertNull(UriPathResolver.resolveDocId("home:Download"))
    }

    @Test
    fun emptyDocId_returnsNull() {
        assertNull(UriPathResolver.resolveDocId(""))
    }
}