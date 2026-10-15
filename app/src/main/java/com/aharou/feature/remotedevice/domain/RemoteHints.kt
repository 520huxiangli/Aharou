package com.aharou.feature.remotedevice.domain

/**
 * 远端动作的「下一步」提示。
 *
 * 调用方多半是另一个 AI：失败时最常见的表现是换几个参数反复撞同一条错路。这里按
 * 「错误码 → 文案关键词 → 兜底」给一句可执行的话，让一次失败就能改对。
 *
 * 约束：一条、一行、尽量短。它会拼在返回正文末尾，说长了反而挤占对端的上下文；
 * 没有合适的话可说时返回 null，不硬凑。
 */
internal object RemoteHints {

    private const val FALLBACK =
        "若参数或路径可疑，可先调 info 看本机可用动作；写入类失败请确认路径落在允许的根之内"

    /** 失败时的下一步。 */
    fun forError(code: String?, message: String): String? =
        byCode(code) ?: byMessage(message) ?: FALLBACK

    /** 成功之后仍有明确后续的动作才给提示，其余返回 null。 */
    fun forSuccess(action: RemoteAction): String? = when (action.name) {
        RemoteActionCatalog.ACTION_WRITE_FILE -> "写入已生效，可再用 read_file 复核内容"
        RemoteActionCatalog.ACTION_SHELL ->
            "单次上限 ${RemoteActionCatalog.REMOTE_SHELL_MAX_TIMEOUT_SECONDS} 秒，长任务请拆成几步"
        else -> null
    }

    /** 把提示拼到正文末尾；没有提示时原样返回。 */
    fun append(text: String, hint: String?): String =
        if (hint.isNullOrBlank()) text else "$text\nhint: $hint"

    private fun byCode(code: String?): String? = when (code?.uppercase()) {
        "STALE_FILE" -> "文件已被本机其它会话或用户改过：先 read_file 重新读一次，再重写"
        "MISSING_PATH" -> "缺少必填参数 path：补上路径再调用"
        "MISSING_TEXT" -> "缺少必填参数 text：补上正文再调用"
        "CONTEXT_REQUIRED" -> "该动作缺执行上下文：改用 read_file / write_file / shell 这类常规动作"
        "LOG_NOT_FOUND" -> "日志只保留最近 7 天：date 传 yyyy-MM-dd，不传则读今天"
        else -> null
    }

    private fun byMessage(message: String): String? = when {
        message.contains("只支持") -> "换成列出的取值重试；stop 与 task 不开放给远端（可用 start 先拉起影子屏）"
        message.contains("Shizuku") || message.contains("root") ->
            "本机需要 root 或 Shizuku 就绪才能驱动屏幕：可请本机用户到设置里检查通道"
        message.contains("影子屏") || message.contains("vscreen") ->
            "影子屏没启动时先 ui action=start，再 ui action=launch 起目标 App，然后才能 tap / type"
        message.contains("路径越界") ->
            "只允许落在这些根之内：~/workspace（工作区）、容器 rootfs、~/.aharou（AI 配置）、记忆、~/shared（共享区）"
        message.contains("本机未提供能力") -> "先调 info 看本机当前可用的动作清单"
        message.contains("未批准") ->
            "本机用户没批准这次调用：协作开关若关着请对方打开，或改用 send_message 让对方直接看到"
        message.contains("执行失败") -> "可用 read_logs 读本机日志定位原因"
        else -> null
    }
}
