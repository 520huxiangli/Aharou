package com.aharou.feature.agent.domain.common

/**
 * 名称合法性校验：技能目录名与子代理文件名共用的字符规则。
 *
 * 名称直接参与文件/目录路径，禁掉路径分隔符与保留字符；长度上限由各子系统按目录深度自定
 * （技能 64、子代理 40），故作为参数传入而非内建常量。
 */
internal object NameValidation {
    private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun isValid(name: String, maxLength: Int): Boolean {
        if (name.isBlank() || name.length > maxLength) return false
        if (name == "." || name == "..") return false
        return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
    }
}
