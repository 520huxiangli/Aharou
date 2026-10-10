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

    /** Python：走 pip3 装 python-lsp-server，入口命令 `pylsp`。 */
    val PYTHON = Entry(
        extension = LanguageExtension.PYTHON,
        languageId = "python",
        fileExtensions = listOf("py", "pyw", "pyi"),
        program = "pylsp"
    )

    /** TypeScript / JavaScript：走 npm 装 typescript-language-server + typescript，需 `--stdio`。 */
    val TYPESCRIPT = Entry(
        extension = LanguageExtension.TYPESCRIPT,
        languageId = "typescript",
        fileExtensions = listOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs"),
        program = "typescript-language-server",
        args = listOf("--stdio")
    )

    /** Bash / Shell：走 npm 装 bash-language-server，需 `start` 子命令。 */
    val SHELL = Entry(
        extension = LanguageExtension.SHELL,
        languageId = "shell",
        fileExtensions = listOf("sh", "bash", "zsh", "ksh"),
        program = "bash-language-server",
        args = listOf("start")
    )

    /** YAML：走 npm 装 yaml-language-server，需 `--stdio`。 */
    val YAML = Entry(
        extension = LanguageExtension.YAML,
        languageId = "yaml",
        fileExtensions = listOf("yaml", "yml"),
        program = "yaml-language-server",
        args = listOf("--stdio")
    )

    val ALL: List<Entry> = listOf(LUA, PYTHON, TYPESCRIPT, SHELL, YAML)

    init {
        // 条目的 program 与枚举的 command 必须一致：前者拼进容器启动命令，后者用于探活与安装，
        // 一旦分叉就会出现「装好了却起不来」且难查。这里在类初始化时直接拦下。
        ALL.forEach { entry ->
            require(entry.program == entry.extension.command) {
                "LspServerCatalog 条目与 LanguageExtension 不一致：${entry.languageId} " +
                    "program=${entry.program} command=${entry.extension.command}"
            }
        }
    }

    /** 按文件路径（容器路径或普通路径）取对应扩展；不在收录范围内返回 null。 */
    fun byPath(path: String): Entry? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return ALL.firstOrNull { ext in it.fileExtensions }
    }
}
