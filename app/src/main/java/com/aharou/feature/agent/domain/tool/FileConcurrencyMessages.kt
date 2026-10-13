package com.aharou.feature.agent.domain.tool

/**
 * 并发编辑冲突的共享契约：错误码与提示语。
 *
 * 单独成文件而不是放在某个工具的源文件里，是因为 `editFile` 与 `writeFile` **两个工具**都要用
 * 同一套语义：模型看到的错误码与措辞一致，它才能学到"看到 STALE_FILE 就重新读取"这一条统一规则，
 * 而不是对两个工具各记一种说法。
 */

/**
 * 写前版本校验失败的错误码。模型据此判断"我的内容过期了"，应重新 `readFile` 后重做。
 *
 * 与 `FILE_NOT_FOUND`、`NO_MATCH` 等区分开很重要：那些是"编辑本身有问题"，这个是"编辑没错、
 * 但基线过期了"，重试策略完全不同。
 */
const val STALE_FILE_CODE = "STALE_FILE"

/**
 * 并发编辑冲突的提示语。
 *
 * 措辞刻意写成**操作指引**而非单纯报错：模型看到这条必须知道下一步该做什么（重新读取再重做），
 * 否则它可能反复重试同一个过期编辑而陷入死循环。
 *
 * @param displayPath 给模型看的逻辑路径（`~/workspace/...`），不是宿主物理路径。
 */
fun staleFileMessage(displayPath: String): String =
    "文件已被其他会话或用户修改，你手上的内容已过期，本次修改未写入。" +
        "请先重新读取该文件，再基于最新内容重做修改。（文件：$displayPath）"
