package com.aharou.feature.agent.domain.dependency

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject

/**
 * 定位一个 Gradle 工程里与依赖版本相关的文件。
 *
 * 走 [FileAccessProvider]（本地与远程 SSH 工作区通用），不做 gradle 构建、也不解析 include，
 * 只按约定路径 + 有限深度递归找文件：
 *   - `gradle/libs.versions.toml`（版本目录）
 *   - `settings.gradle(.kts)`、根 `build.gradle(.kts)`
 *   - 子模块目录下的 build.gradle(.kts)（递归深度上限 3 层）
 */
class GradleProjectScanner @Inject constructor(
    private val fileAccess: FileAccessProvider
) {

    data class ProjectFiles(
        val rootPath: String,
        val catalogPath: String?,
        /** settings / build 脚本，按「settings 优先、根次之、子模块靠后」排列。 */
        val gradleFiles: List<String>,
        /** 实际读取过的全部文件。 */
        val scannedFiles: List<String>
    )

    fun scan(projectRoot: String, subPath: String?): ProjectFiles {
        val access = fileAccess.forWorkspace(projectRoot)
        // 文件访问层（WorkspacePathMapper）认的是容器路径：传宿主绝对路径会被当成 rootfs 内的路径，
        // 结果是 exists() 一律 false、整个工程扫不出任何 gradle 文件。
        val root = if (subPath.isNullOrBlank()) WorkspacePathMapper.CONTAINER_ROOT
        else join(WorkspacePathMapper.CONTAINER_ROOT, subPath)

        val catalog = CATALOG_PATHS.map { join(root, it) }.firstOrNull { fileExists(access, it) }

        val gradleFiles = LinkedHashSet<String>()
        (SETTINGS_PATHS + ROOT_BUILD_PATHS).forEach { rel ->
            val full = join(root, rel)
            if (fileExists(access, full)) gradleFiles.add(full)
        }
        runCatching { access.listFilesRecursive(root, MAX_DEPTH) }
            .getOrNull()
            .orEmpty()
            .filter { rel -> MODULE_BUILD_NAMES.any { rel == it || rel.endsWith("/$it") } }
            .forEach { rel -> gradleFiles.add(join(root, rel)) }

        val scanned = LinkedHashSet<String>()
        catalog?.let { scanned.add(it) }
        scanned.addAll(gradleFiles)

        FileLogger.d(TAG, "扫描 root=$root catalog=$catalog gradleFiles=${gradleFiles.size}")
        return ProjectFiles(root, catalog, gradleFiles.toList(), scanned.toList())
    }

    private fun fileExists(access: FileAccessProvider, path: String): Boolean =
        runCatching { access.exists(path) && access.isFile(path) }.getOrDefault(false)

    private fun join(root: String, rel: String): String = "${root.trimEnd('/')}/${rel.trimStart('/')}"

    private companion object {
        const val TAG = "GradleProjectScanner"
        const val MAX_DEPTH = 3

        val CATALOG_PATHS = listOf("gradle/libs.versions.toml")
        val SETTINGS_PATHS = listOf("settings.gradle.kts", "settings.gradle")
        val ROOT_BUILD_PATHS = listOf("build.gradle.kts", "build.gradle")
        val MODULE_BUILD_NAMES = setOf("build.gradle.kts", "build.gradle")
    }
}
