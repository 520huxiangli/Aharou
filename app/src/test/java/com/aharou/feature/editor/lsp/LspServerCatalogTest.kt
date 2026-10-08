package com.aharou.feature.editor.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LspServerCatalog：容器路径 → 语言服务器条目的扩展名匹配。
 *
 * 覆盖大小写、容器绝对路径、无扩展名、尾点与空串等边界，并核对唯一收录项（Lua）的元数据
 * （program / args / 扩展名 / 语言标识）——这些字段直接拼进容器里的启动命令与语言客户端。
 */
class LspServerCatalogTest {

    @Test
    fun byPath_luaExtension_returnsLuaEntry() {
        assertEquals(LspServerCatalog.LUA, LspServerCatalog.byPath("a.lua"))
    }

    @Test
    fun byPath_isCaseInsensitive() {
        assertEquals(LspServerCatalog.LUA, LspServerCatalog.byPath("A.LUA"))
    }

    @Test
    fun byPath_containerAbsolutePath_returnsEntry() {
        assertEquals(LspServerCatalog.LUA, LspServerCatalog.byPath("/root/workspace/src/main.lua"))
    }

    @Test
    fun byPath_unsupportedExtensions_returnNull() {
        assertNull(LspServerCatalog.byPath("a.py"))
        assertNull(LspServerCatalog.byPath("a.kt"))
        assertNull(LspServerCatalog.byPath("a.cpp"))
    }

    @Test
    fun byPath_noExtensionOrTrailingDot_returnsNull() {
        assertNull(LspServerCatalog.byPath("Makefile"))
        assertNull(LspServerCatalog.byPath("a."))
        assertNull(LspServerCatalog.byPath(""))
    }

    @Test
    fun luaEntry_metadataIsConsistent() {
        val lua = LspServerCatalog.LUA
        assertEquals(LanguageExtension.LUA, lua.extension)
        assertEquals("lua", lua.languageId)
        assertEquals(listOf("lua"), lua.fileExtensions)
        assertEquals("lua-language-server", lua.program)
        assertEquals(listOf("--locale=zh-cn"), lua.args)
        assertTrue(LspServerCatalog.ALL.contains(lua))
    }
}
