package com.aicode.feature.browser

import android.content.Context
import java.io.File

/**
 * 沙箱路径解析（Aharou 移植版 · 占位）。
 *
 * Minis 原版通过 PRootKernel 把 `minis://<会话>/<路径>` 映射到宿主文件系统；
 * 本移植版暂未接通容器路径映射（返回 null → 对应分支退化为 404）。
 * 待接入 AiCode 容器后再实现：参考 LinuxContainerEngine 的 rootfs 目录
 * 与 PathHomeResolver 的 home 映射（详见 fusion-plan P3）。
 */
object SandboxPathResolver {

    /** 解析会话内路径到宿主文件（v1 未接通 → null）。TODO: 接容器挂载表。 */
    @Suppress("unused")
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? = null

    /** 解析全局沙箱路径到宿主文件（v1 未接通 → null）。TODO: 接容器挂载表。 */
    @Suppress("unused")
    fun resolveHostPath(linuxPath: String): File? = null
}
