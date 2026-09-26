package com.aharou.feature.git.domain

/**
 * git 命令以非零退出码结束（真实失败），区别于进程异常崩溃。携带 git 自身输出的文本（stderr 优先），
 * UI 据此把「成功」误报纠正为「失败 + 原因」。代表场景：未配置署名提交、未授权、合并冲突、无远程等。
 *
 * @param output git stdout+stderr 合并文本，作 [message] 供 UI 展示与日志排查。
 */
open class GitCommandFailureException(val output: String) : Exception(output)

/**
 * git 输出超过引擎上限（[com.aharou.feature.agent.domain.container.MAX_UNBOUNDED_CHARS]）被截断。
 * 继承 [GitCommandFailureException]，既有的失败处理路径（toast）自动生效；需要完整内容的调用方
 * （diff 取文件内容）单独捕获它并降级为「文件过大」提示，而不是拿半截数据解析。
 */
class GitOutputTooLargeException : GitCommandFailureException("输出过大，已截断，请缩小范围后重试")
