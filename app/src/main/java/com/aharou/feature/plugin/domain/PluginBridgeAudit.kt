package com.aharou.feature.plugin.domain

import java.io.File

/**
 * 插件页面里直接写死的传输层方法名是否仍被宿主实现。
 *
 * 页面 SDK（`assets/plugin-ui/aicode.js`）的别名与传输层方法名不是一一对应（`ui.setOverlay`
 * 对应 `ui.overlay`），安装期只能检查页面自己写的方法名。这里**只报告不阻断**：插件照样装上、
 * 照样运行，问题落到应用日志里供排查，不因为对不上就把插件挡在门外。
 */
object PluginBridgeAudit {

    /** 一处「页面用了、宿主没有（或页面根本不该调）」的方法。 */
    data class Finding(val pageFile: String, val method: String)

    private val CALL_SITE = Regex("""call\(\s*['"]([A-Za-z][A-Za-z0-9_.]*)['"]""")
    private const val MAX_PAGE_BYTES = 512L * 1024

    fun audit(manifest: PluginManifest, dir: File): List<Finding> {
        val pages = buildList {
            manifest.configPage?.let(::add)
            manifest.uiEntries.mapNotNullTo(this) { it.page }
            manifest.workspaceTools.mapTo(this) { it.pageFile }
            manifest.overlay?.surfaces?.mapTo(this) { it.page }
        }
        val findings = mutableListOf<Finding>()
        pages.distinct().forEach { rel ->
            val text = readPage(dir, rel) ?: return@forEach
            CALL_SITE.findAll(text).map { it.groupValues[1] }.distinct().forEach { method ->
                val spec = PluginHostApiCatalog.find(method)
                if (spec == null || PluginHostApiOrigin.PAGE !in spec.origins) {
                    findings += Finding(rel, method)
                }
            }
        }
        return findings
    }

    private fun readPage(dir: File, relativePath: String): String? {
        val file = File(dir, relativePath)
        if (!file.isFile || file.length() > MAX_PAGE_BYTES) return null
        if (!file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }
}
