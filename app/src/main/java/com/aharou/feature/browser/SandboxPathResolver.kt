package com.aharou.feature.browser

import android.content.Context
import java.io.File

/**
 * 沙箱路径解析（Aharou 容器版）。
 *
 * 把容器内逻辑路径映射到宿主文件系统的物理路径，供浏览器 `aharou://` URL 拦截与
 * 文件浏览器正确展示容器目录内容。
 *
 * 支持两套路径前缀：
 * - 新式 `/root/…`：当前 PRoot 容器的真实挂载点。
 * - 旧式 `/var/aharou/…`：Minis 时代的逻辑路径，`aharou://` URL 仍以此构造，
 *   此处做兼容翻译，保持浏览器工具正常工作。
 *
 * 挂载关系（来自 LinuxContainerEngine / ContainerInstaller）：
 * ```
 * /root/workspace        → filesDir/aharou-sessions/<sid>/workspace （session 范围）
 * /root/.aharou          → filesDir/aharou
 * /root/.aharou/memory   → filesDir/aharou-global/memory
 * /root/shared           → filesDir/aharou-global/shared
 * /root/<其他>           → filesDir/rootfs/root/<其他>
 * ```
 */
object SandboxPathResolver {

    /**
     * 解析当前会话内的容器路径到宿主文件。
     *
     * 优先把 `/root/workspace`（及旧式 `/var/aharou/workspace`）路由到该 session 专属的
     * `filesDir/aharou-sessions/<sessionId>/workspace/` 目录，避免多 session 并发时
     * 全局挂载点 last-writer-wins 导致路径串会话。
     *
     * 其余路径委托 [resolveGlobalHostPath]。
     */
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? {
        val canonical = toCanonical(linuxPath) ?: return null
        // /root/workspace 及其子路径 → session-scoped workspace 目录
        val workspaceRelative = stripPrefix(canonical, "/root/workspace")
        if (workspaceRelative != null) {
            val sessionWorkspace = File(
                File(File(context.filesDir, "aharou-sessions"), sessionId),
                "workspace"
            )
            return if (workspaceRelative.isEmpty()) sessionWorkspace
            else File(sessionWorkspace, workspaceRelative)
        }
        return resolveGlobalHostPath(canonical, context)
    }

    /**
     * 解析全局容器路径到宿主文件（无 session 上下文时使用）。
     *
     * 由于无法注入 [Context]，此方法需要调用方传入 context；
     * 不带 context 的 [resolveHostPath] 返回 null（退化为 404），
     * 调用方应优先使用带 context 的重载。
     */
    fun resolveHostPath(linuxPath: String): File? = null

    /**
     * 带 context 的全局路径解析，供调用方已有 context 时直接使用。
     */
    fun resolveHostPath(linuxPath: String, context: Context): File? {
        val canonical = toCanonical(linuxPath) ?: return null
        return resolveGlobalHostPath(canonical, context)
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────────

    /**
     * 将各类容器路径前缀归一化为 `/root/…` 形式：
     * - `/var/aharou/<subdir>` → `/root/<mapped>`（旧式 Minis 路径兼容翻译）
     * - `~/…` → `/root/…`（波浪号展开）
     * - `/root/…` → 原样
     * - 其他 → null（不认识的路径前缀，不处理）
     */
    private fun toCanonical(linuxPath: String): String? {
        // 旧式 /var/aharou/<subdir> 映射表
        val varAharouMappings = mapOf(
            "workspace"   to "/root/workspace",
            "attachments" to "/root/workspace",   // attachments 与 workspace 同目录
            "offloads"    to "/root/workspace",
            "browser"     to "/root/workspace",
            "skills"      to "/root/.aharou/skills",
            "memory"      to "/root/.aharou/memory",
            "shared"      to "/root/shared",
        )
        return when {
            linuxPath.startsWith("/var/aharou/") || linuxPath == "/var/aharou" -> {
                val rest = linuxPath.removePrefix("/var/aharou").trimStart('/')
                val topDir = rest.substringBefore('/')
                val mappedRoot = varAharouMappings[topDir] ?: return null
                val tail = rest.removePrefix(topDir)
                mappedRoot + tail
            }
            linuxPath == "~" -> "/root"
            linuxPath.startsWith("~/") -> "/root/" + linuxPath.removePrefix("~/")
            linuxPath.startsWith("/root/") || linuxPath == "/root" -> linuxPath
            else -> null
        }
    }

    /**
     * 把归一化后的 `/root/…` 路径映射到宿主物理目录。
     * bind mount 优先，其余落 rootfs。
     */
    private fun resolveGlobalHostPath(canonical: String, context: Context): File? {
        val filesDir = context.filesDir

        // bind mount：/root/.aharou/memory → filesDir/aharou-global/memory
        stripPrefix(canonical, "/root/.aharou/memory")?.let { rel ->
            val base = File(File(filesDir, "aharou-global"), "memory")
            return if (rel.isEmpty()) base else File(base, rel)
        }

        // bind mount：/root/.aharou → filesDir/aharou
        stripPrefix(canonical, "/root/.aharou")?.let { rel ->
            val base = File(filesDir, "aharou")
            return if (rel.isEmpty()) base else File(base, rel)
        }

        // bind mount：/root/shared → filesDir/aharou-global/shared
        stripPrefix(canonical, "/root/shared")?.let { rel ->
            val base = File(File(filesDir, "aharou-global"), "shared")
            return if (rel.isEmpty()) base else File(base, rel)
        }

        // 其余 /root/… → rootfs/root/…（含 /root/workspace 的 rootfs 副本）
        stripPrefix(canonical, "/root")?.let { rel ->
            val base = File(File(filesDir, "rootfs"), "root")
            return if (rel.isEmpty()) base else File(base, rel)
        }

        return null
    }

    /**
     * 若 [path] 以 [prefix] 开头（完整路径段，不截断），返回剩余部分（含前导 `/`，
     * 若为精确匹配则返回空字符串）；否则返回 null。
     */
    private fun stripPrefix(path: String, prefix: String): String? {
        if (!path.startsWith(prefix)) return null
        val rest = path.removePrefix(prefix)
        // 必须是路径边界：剩余为空、或以 / 开头（不允许 /root.foo 匹配 /root）
        return if (rest.isEmpty() || rest.startsWith('/')) rest else null
    }
}
