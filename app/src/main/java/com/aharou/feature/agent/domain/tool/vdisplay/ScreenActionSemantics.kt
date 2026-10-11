package com.aharou.feature.agent.domain.tool.vdisplay

/**
 * 「操作屏幕」的两个工具（vscreen / a11y）共用的语义风控与截图 caption。
 *
 * 语义风控参考 nanoMuse（docs/gui.md 的 sensitive words）：点按/输入本身看不出会不会提交，
 * 于是让调用方带上手指下/目标元素的文字（label），命中敏感词即按敏感档处理——这一步要用户
 * 逐次确认、不可记忆。caption 参考其 Screen.render()，把「哪个 App、哪一屏、多大、键盘是否在」
 * 拼成一行，随截图一起给模型。
 */
object ScreenActionSemantics {

    /**
     * 命中即视为「付款/转账/发送/删除」这类按下后无法撤销的动作。中文为主，附英文变体，
     * 与 nanoMuse 默认词表对齐；要扩词改这一处即可。
     */
    val SENSITIVE_WORDS: List<String> = listOf(
        "确认支付", "立即支付", "转账", "提交订单", "立即购买", "付款",
        "发送", "删除", "退出登录", "确认收货", "免密支付",
        "pay now", "place order", "buy now", "transfer", "send", "delete", "log out", "sign out",
    )

    /** label 命中的敏感词（去重，保持词表顺序）；空列表表示不敏感。 */
    fun matchSensitive(label: String): List<String> {
        val hay = label.lowercase()
        return SENSITIVE_WORDS.filter { hay.contains(it.lowercase()) }.distinct()
    }

    /**
     * 一次带 label 动作的语义评估。
     *
     * @param sensitiveHits 命中的敏感词；空表示不敏感。
     * @param warning 需要摆到用户面前的一句说明；无需说明时为 null。
     */
    data class Assessment(val sensitiveHits: List<String>, val warning: String?) {
        val isSensitive: Boolean get() = sensitiveHits.isNotEmpty()
    }

    /**
     * 评估 label：为空时给一条告警（手指下是什么无从得知，不能当无事发生）；命中敏感词时
     * 给出「这个动作退不回来」的说明。
     */
    fun assess(label: String?): Assessment {
        val text = label?.trim().orEmpty()
        if (text.isEmpty()) {
            return Assessment(
                sensitiveHits = emptyList(),
                warning = "这次动作没有提供 label（手指下/目标元素的文字），无从判断它会提交什么；" +
                    "请先读到按钮或输入框的文字再操作。",
            )
        }
        val hits = matchSensitive(text)
        if (hits.isEmpty()) return Assessment(emptyList(), null)
        return Assessment(
            sensitiveHits = hits,
            warning = "目标「${text.take(60)}」命中敏感词：${hits.joinToString("、")}。" +
                "付款、转账、发送、删除这类动作按下后退不回来，已按敏感档处理，本次必须逐次确认且不会被记忆。",
        )
    }

    /**
     * 截图 caption 一行：`App 名/包名 · Activity/路由 · 宽×高 · keyboard shown|hidden`。
     * 拿不到的字段直接略去，不留空占位。
     */
    fun caption(
        appName: String? = null,
        packageName: String? = null,
        activity: String? = null,
        width: Int = 0,
        height: Int = 0,
        keyboardShown: Boolean? = null,
    ): String {
        val parts = ArrayList<String>(4)
        appLabel(appName, packageName)?.let { parts.add(it) }
        activity?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (width > 0 && height > 0) parts.add("${width}×$height")
        keyboardShown?.let { parts.add(if (it) "keyboard shown" else "keyboard hidden") }
        return parts.joinToString(" · ")
    }

    /** `名 (包名)`；只有名/只有包名时用其一；两者相同只留一个；都空返回 null。 */
    private fun appLabel(appName: String?, packageName: String?): String? {
        val name = appName?.trim().orEmpty()
        val pkg = packageName?.trim().orEmpty()
        return when {
            name.isNotEmpty() && pkg.isNotEmpty() && name != pkg -> "$name ($pkg)"
            name.isNotEmpty() -> name
            pkg.isNotEmpty() -> pkg
            else -> null
        }
    }
}
