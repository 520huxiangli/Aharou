package com.aicode.feature.sandbox

/** 预览传递：文件浏览器 → 预览界面之间共享当前文件（避免走导航参数序列化）。 */
object FilePreviewHolder {
    var currentItem: FileItem? = null
}
