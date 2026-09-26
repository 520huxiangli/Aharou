package com.aharou.feature.agent.presentation.component

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.Code
import compose.icons.feathericons.Database
import compose.icons.feathericons.File
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import compose.icons.feathericons.Music
import compose.icons.feathericons.Package
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Video

/**
 * 文件浏览行的单色线性图标，按文件语义和扩展名挑选合适的 Feather 线性图标。
 * 风格与 App 全局保持一致的极简线性视觉，渲染时跟随主题色着色。
 */
fun fileTypeIconFor(name: String): ImageVector {
    val lower = name.lowercase()
    if (lower in SPECIAL_TERMINAL_FILES) return FeatherIcons.Terminal
    if (lower in SPECIAL_TEXT_FILES || lower.startsWith(".env")) return FeatherIcons.FileText

    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in CODE_EXTS -> FeatherIcons.Code
        in TERMINAL_EXTS -> FeatherIcons.Terminal
        in TEXT_AND_CONFIG_EXTS -> FeatherIcons.FileText
        in IMAGE_EXTS -> FeatherIcons.Image
        in VIDEO_EXTS -> FeatherIcons.Video
        in AUDIO_EXTS -> FeatherIcons.Music
        in ARCHIVE_EXTS -> FeatherIcons.Package
        in DATABASE_EXTS -> FeatherIcons.Database
        else -> FeatherIcons.File
    }
}

/** 源代码文件扩展名。 */
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "pyw", "pyi",
    "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "cs", "go", "rs", "php", "rb", "swift", "dart", "lua",
    "scala", "groovy", "vue", "svelte", "astro",
    "html", "htm", "xhtml", "css", "scss", "sass", "less", "styl",
    "r", "pl", "pm", "tcl", "hs", "fs", "fsx", "clj", "cljs",
    "ex", "exs", "erl", "v", "zig", "nim", "d", "jl"
)

/** 脚本与命令行文件扩展名。 */
private val TERMINAL_EXTS = setOf(
    "sh", "bash", "zsh", "ksh", "csh", "fish", "bat", "cmd", "ps1", "awk", "sed"
)

/** 常见无后缀脚本或构建文件。 */
private val SPECIAL_TERMINAL_FILES = setOf(
    "dockerfile", "containerfile", "makefile", "rakefile", "vagrantfile", "justfile"
)

/** 文档、纯文本与各类配置文件（json、yaml、xml、properties、env 等统一作为文本呈现）。 */
private val TEXT_AND_CONFIG_EXTS = setOf(
    "md", "markdown", "mdown", "txt", "rst", "asciidoc", "adoc",
    "doc", "docx", "rtf", "log", "csv", "tsv", "pdf",
    "json", "jsonc", "json5", "yaml", "yml", "toml", "xml",
    "ini", "cfg", "conf", "properties", "gradle", "pro", "plist",
    "lock", "editorconfig"
)

/** 常见独立文本或配置规则文件名。 */
private val SPECIAL_TEXT_FILES = setOf(
    ".gitignore", ".gitattributes", ".gitmodules", ".dockerignore",
    ".npmrc", ".prettierrc", ".eslintrc", ".clang-format",
    "license", "notice", "authors", "contributors", "changelog"
)

/** 图片格式。 */
private val IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico",
    "heic", "heif", "avif", "tif", "tiff"
)

/** 视频媒体格式。 */
private val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm"
)

/** 音频格式。 */
private val AUDIO_EXTS = setOf(
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"
)

/** 压缩包与安装包。 */
private val ARCHIVE_EXTS = setOf(
    "zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z", "rar",
    "apk", "apks", "xapk", "aab", "jar", "war", "ear", "deb", "rpm", "ipa", "dmg", "iso"
)

/** 数据库文件。 */
private val DATABASE_EXTS = setOf(
    "sql", "db", "sqlite", "sqlite3", "mdb"
)
