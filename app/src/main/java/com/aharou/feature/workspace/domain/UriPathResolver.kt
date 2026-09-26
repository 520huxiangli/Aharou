package com.aharou.feature.workspace.domain

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * 把 SAF tree uri（系统目录选择器返回）解析为宿主真实路径。
 *
 * 仅支持 Android 外部存储 provider 的明确 docId 形态：
 * - `primary:<相对路径>` → `<主存储根>/<相对路径>`（主存储根取 `Environment.getExternalStorageDirectory()`，多用户/工作资料下不是固定 `/storage/emulated/0`）
 * - `<卷 UUID>:<相对路径>` → `/storage/<卷 UUID>/<相对路径>`（例如 `XXXX-XXXX:Download`）
 *
 * 其它 provider 自定义 docId 一律返回 null，避免把 provider 定义的 opaque ID 当成宿主路径。
 */
object UriPathResolver {

    /** tree uri → 宿主真实目录路径；无法解析返回 null。 */
    fun toFilePath(context: Context, uri: Uri): String? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault("/storage/emulated/0")
        return resolveDocId(DocumentsContract.getTreeDocumentId(uri), primaryRoot)
    }

    /** 纯函数：externalstorage docId → 宿主真实目录路径；无法解析返回 null。
     *  [primaryRoot] 为 primary: 分支映射的主存储根，默认主用户常用路径，测试可注入。 */
    fun resolveDocId(docId: String, primaryRoot: String = "/storage/emulated/0"): String? {
        if (docId.startsWith("primary:")) {
            val sub = docId.substringAfter("primary:", "")
            if (!isSafeSubpath(sub)) return null
            return primaryRoot.trimEnd('/') + "/" + sub.trimStart('/')
        }
        val separator = docId.indexOf(':')
        if (separator <= 0) return null
        val volume = docId.substring(0, separator)
        val sub = docId.substring(separator + 1)
        if (!volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) || !isSafeSubpath(sub)) return null
        return "/storage/$volume/" + sub.trimStart('/')
    }

    private fun isSafeSubpath(subpath: String): Boolean =
        subpath.trimStart('/').split('/').none { it == ".." }
}
