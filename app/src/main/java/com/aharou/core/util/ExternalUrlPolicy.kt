package com.aharou.core.util

import android.net.Uri

/**
 * 外部链接放行策略：面板脚本、搜索结果、远端 PR 数据给出的 URL 都不可信，
 * 交给 ACTION_VIEW 时若是 intent:、file:、content:、javascript: 等 scheme，
 * 可能拉起任意组件或读本地文件，所以只放行带 host 的 http / https。
 */
object ExternalUrlPolicy {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    fun isSafeExternalUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in ALLOWED_SCHEMES) return false
        return !uri.host.isNullOrBlank()
    }
}
