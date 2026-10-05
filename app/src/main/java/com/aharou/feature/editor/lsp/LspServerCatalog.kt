package com.aharou.feature.editor.lsp

/**
 * 语言扩展目录：扩展名 → 语言标识 / 语言服务器命令 / 启动参数。
 *
 * 只放「能在容器里跑起来」的服务器。C++（clangd）暂不收录：包体积上百 MB，且没有
 * compile_commands.json 时基本全是假报错，投入产出比太低。
 */
object LspServerCatalog {

    /** 一条语言扩展：一个语言标识 + 一组扩展名 + 容器内可执行文件与参数。 */
    data class Entry(
        val extension: LanguageExtension,
        val languageId: String,
        val fileExtensions: List<String>,
        val program: String,
        val args: List<String> = emptyList()
    )

    /** Lua：Alpine 自带包（`apk add lua-language-server`），无额外运行时，最省。 */
    val LUA = Entry(
        extension = LanguageExtension.LUA,
        languageId = "lua",
        fileExtensions = listOf("lua"),
        program = "lua-language-server",
        // 中文消息：locale 只能走命令行（.luarc.json 里没有这个字段），官方支持 zh-cn。
        args = listOf("--locale=zh-cn")
    )

    val ALL: List<Entry> = listOf(LUA)

    /** 按文件路径（容器路径或普通路径）取对应扩展；不在收录范围内返回 null。 */
    fun byPath(path: String): Entry? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return ALL.firstOrNull { ext in it.fileExtensions }
    }
}
