package com.aharou.feature.agent.domain.container

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.presentation.ContainerDnsMode
import com.aharou.feature.settings.presentation.ContainerMirrorMode
import com.aharou.feature.settings.presentation.ContainerSourcesRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 负责把打进 assets 的 Alpine rootfs 与 PRoot 二进制安装到 App 私有目录。
 *
 * 自动根据设备架构（ARM / x86）加载对应资源。targetSdk 锁定 28，数据目录文件才可执行（见 build.gradle.kts）。
 */
@Singleton
class ContainerInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerOsDetector: ContainerOsDetector,
    /**
     * 就绪探针。Lazy 破环：体检器要经引擎执行命令，而引擎又依赖本安装器；
     * 默认 null 只为让单测能直接构造（生产路径由 Hilt 注入，必不为 null）。
     */
    private val containerDoctor: Lazy<ContainerDoctor>? = null,
    /**
     * 「设置 → 镜像与 DNS」的已保存选择，用于在容器还不能执行命令的首次解压阶段就把源与解析写好。
     *
     * 默认 null 只为让单测能直接构造（生产路径由 Hilt 注入）。
     */
    private val sourcesRepository: ContainerSourcesRepository? = null
) {
    companion object {
        private const val TAG = "ContainerInstaller"

        /** 用户没手动指定镜像站时的默认值（与设置页候选列表第一个一致）。 */
        private const val DEFAULT_CHINA_MIRROR = "mirrors.huaweicloud.com"

        private val ALI_DNSPOD_DNS = listOf("223.5.5.5", "223.6.6.6", "119.29.29.29")
        private val CLOUDFLARE_GOOGLE_DNS = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8")
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** resolv.conf 只认前 3 个 nameserver，多写的不会用。 */
        private const val MAX_DNS_SERVERS = 3

        /**
         * 跨工作区共享区宿主目录（容器内 `/root/shared`）。静态入口给 Composable 等拿不到
         * 注入单例的地方用（同 [migrateLegacyDir]）——避免与实例属性各写一份路径而跑偏。
         */
        fun sharedDirOf(context: Context): File =
            File(File(context.filesDir, "aharou-global"), "shared").apply { mkdirs() }

        /** [Aharou 改名] 旧目录 filesDir/aicode 首次访问时迁移到 filesDir/aharou（只搬不删；rename 失败则复制兜底）。 */
        fun migrateLegacyDir(context: Context): File {
            val newDir = File(context.filesDir, "aharou")
            val oldDir = File(context.filesDir, "aicode")
            if (!newDir.exists() && oldDir.exists()) {
                if (!oldDir.renameTo(newDir)) {
                    runCatching { oldDir.copyRecursively(newDir, overwrite = false) }
                }
            }
            return newDir
        }

        /** [Aharou 改名] 工作区项目级 `.aicode` 目录批量迁移为 `.aharou`（只搬不删）。 */
        fun migrateProjectDirs(context: Context) {
            val projects = File(context.filesDir, "projects")
            projects.listFiles()?.forEach { project ->
                if (!project.isDirectory) return@forEach
                val oldDir = File(project, ".aicode")
                val newDir = File(project, ".aharou")
                if (!newDir.exists() && oldDir.exists()) {
                    if (!oldDir.renameTo(newDir)) {
                        runCatching { oldDir.copyRecursively(newDir, overwrite = false) }
                    }
                }
            }
        }

        @Volatile private var docsExtractedSession = false

        /**
         * 从 assets 提取文档到 ~/.aharou/docs (内置使用指导)，支持 guide/ advanced/ 等分类子目录。
         *
         * 提取前先整目录删除：文档按功能分类，版本间调整分类会改变文件路径，只覆盖不清理会让
         * 上个版本的同名旧文件残留在别的路径下，AI 可能读到过期内容。
         */
        fun extractDocs(context: Context) {
            if (docsExtractedSession) return
            val destDir = File(ContainerInstaller.migrateLegacyDir(context), "docs")
            runCatching {
                destDir.deleteRecursively()
                destDir.mkdirs()
                extractAssetsRecursive(context, "docs", destDir)
                docsExtractedSession = true
            }.onFailure {
                FileLogger.w(TAG, "提取内置文档失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取内置提示词到 ~/.aharou/prompts/，每次启动全量覆盖，使 App 升级后提示词随之更新。
         *
         * 用户自定义覆盖放在 ~/.aharou/prompts.custom/（同名即覆盖），本方法不触碰该目录，
         * 故用户重写的片段不会被升级覆盖。参见 [com.aharou.feature.agent.domain.prompt.SystemPromptProvider]。
         */
        fun extractPrompts(context: Context) {
            val destDir = File(ContainerInstaller.migrateLegacyDir(context), "prompts")
            destDir.mkdirs()
            runCatching {
                extractAssetsRecursive(context, "prompts", destDir)
            }.onFailure {
                FileLogger.w(TAG, "提取内置提示词失败: ${it.message}", it)
            }
        }

        /** 递归复制 assets 下的目录到目标目录，支持子目录（如 prompts/agent/、docs/guide/）。 */
        private fun extractAssetsRecursive(context: Context, assetDir: String, destDir: File) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val destFile = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: IOException) {
                    // 目录项：assets.open 对目录抛 IOException，递归处理
                    extractAssetsRecursive(context, assetPath, destFile)
                }
            }
        }

        /**
         * 从 assets 提取内置子代理定义（如 Explore）到 ~/.aharou/agents/。
         * 若文件已存在则不覆盖，以保留用户的修改与删除后的重建选择。
         */
        fun extractAgents(context: Context) {
            val destDir = File(ContainerInstaller.migrateLegacyDir(context), "agents")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("agents") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("agents/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "提取内置子代理定义失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取内置团队预设到 ~/.aharou/teams/。
         * 若文件已存在则不覆盖，保留用户对预设的修改。
         */
        fun extractTeams(context: Context) {
            val destDir = File(ContainerInstaller.migrateLegacyDir(context), "teams")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("teams") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("teams/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "提取内置团队预设失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取自定义 git credential helper 到 ~/.aharou/git-credential-aicode 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.aicode/git-credential-aicode，
         * 由容器初始化菜单（provision.sh）在 `.gitconfig` 里登记为第二个 credential.helper，
         * 排在 `store` 之后兜底未登录（双保险）。helper 详行为见 assets/aharou/git-credential-aicode。
         *
         * 启动即提取、独立于 provisioning 成败：provisioning 失败时 git 没装上，helper 配置不存在也无所谓；
         * 一旦 git 装好且配置登记，helper 立即可用。提取失败仅告警不抛（helper 缺席仅导致未登录时无弹窗，
         * git 仍能裸跑报认证失败，不致命）。
         */
        fun extractCredentialHelper(context: Context) {
            val dest = File(ContainerInstaller.migrateLegacyDir(context), "git-credential-aicode")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("aharou/git-credential-aicode").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // 对所有用户赋可执行位（proot 进程以 App uid 运行，参照 [copyAsset] 的 0o111 模式）。
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "提取 git credential helper 失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取容器初始化依赖安装脚本到 ~/.aharou/provision.sh 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.aicode/provision.sh，由
         * 首次进入终端的初始化菜单以 `sh` 执行——脚本按包管理器
         * （apk/apt-get/dnf/yum/pacman）安装基础包，包清单维护在脚本内而非代码里。
         * 启动即提取、每次覆盖写（脚本随 App 版本更新）。提取失败仅告警不抛：
         * 缺脚本时初始化菜单不会出现，用户可手动安装基础工具。
         */
        fun extractProvisionScript(context: Context) {
            val dest = File(ContainerInstaller.migrateLegacyDir(context), "provision.sh")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("aharou/provision.sh").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "提取 provision 脚本失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取环境工具（`aicode` 命令 + 共享库 lib/）到 ~/.aharou/bin 与 ~/.aharou/lib 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.aicode/bin/aicode（在 PATH 中，见
         * buildContainerEnv）与 /root/.aicode/lib/（env-common.sh、android-sdk.sh 与 scenarios/ 子目录），
         * 供用户随时执行 `aicode` 安装开发环境（场景化）。每次覆盖写（随 App 版本更新）；提取失败仅告警
         * 不抛：缺工具时容器初始化菜单（provision.sh）会提示未就绪，用户重启 App 即可重新提取。
         */
        fun extractEnvTool(context: Context) {
            runCatching {
                extractDirOverwrite(context, "aharou/bin", File(ContainerInstaller.migrateLegacyDir(context), "bin"), executable = true)
                extractDirOverwrite(context, "aharou/lib", File(ContainerInstaller.migrateLegacyDir(context), "lib"), executable = false)
            }.onFailure {
                FileLogger.w(TAG, "提取环境工具失败: ${it.message}", it)
            }
        }

        /** 递归复制 assets 下某目录到 [destDir]（每次覆盖，支持子目录如 lib/scenarios/）；[executable] 时对每个文件赋可执行位。 */
        private fun extractDirOverwrite(context: Context, assetDir: String, destDir: File, executable: Boolean) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val dest = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (executable && !dest.setExecutable(true, false)) {
                        FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                    }
                } catch (e: IOException) {
                    // 目录项：assets.open 对目录抛 IOException，递归复制（如 lib/scenarios/）
                    extractDirOverwrite(context, assetPath, dest, executable)
                }
            }
        }

        /**
         * **重装触发器**——它的值是「已安装」标记（`.installed`）的唯一判据（见 [isInstalled]），
         * **刻意与 assets 里放的 Alpine 版本解耦**。
         *
         * 值一变，所有已装用户下次进容器就会被整体清空重装：容器里 `apk add` 装的包、初始化菜单装的
         * Android SDK / Flutter SDK / JDK 全都没了（几百 MB 重新下载），而且清理前没有任何预警。
         * 所以：
         * - **只有安装逻辑本身变化才递增**：换 proot、改解压流程、换 apk 源分支；
         * - **assets 里换成新的 Alpine 补丁版时不要动它**。新装用户自然解压到新基线，已装用户保持
         *   原样不受影响，想换新基线由用户主动「重置容器」触发（重置确认框已写明会删除容器内已装工具）。
         *
         * 与 assets 实际版本的对应关系见 [ROOTFS_ASSET_VERSION]；容器内真实版本以 `/etc/alpine-release`
         * 为准，不要靠本标记推断。
         *
         * 内置 rootfs 固定在 3.24 分支，apk 源分支也据此固定（见 assets/aharou/lib/env-common.sh 的兜底 v3.24）。
         *
         * 3.23 起 Alpine 的 apk-tools 是 3.x：它用 memfd_create + execveat 跑包脚本，在 proot 下
         * 要靠 proot 对 `lib/apk/exec/` 的 memfd 过滤兜底（termux/proot-distro#595）。换 rootfs 时
         * 别把 proot 退到 5.1.107-69 之前，否则 apk 装包会炸。
         */
        private const val INSTALL_VERSION = "alpine-3.24.2-v7"

        /**
         * assets 里实际打包的 Alpine 版本，**仅用于日志与排查**，不参与是否需要重装的判定。
         * 换 assets 时同步更新这里，让日志里「重装标记」与「实际内容版本」都有据可查。
         */
        private const val ROOTFS_ASSET_VERSION = "3.24.2"

        /** 删 rootfs 时每删这么多条目回调一次进度（太密会刷爆 UI 状态流）。 */
        private const val DELETE_PROGRESS_STEP = 2000

        /** 匹配已有的 jvmargs 行（按行剔除用）：properties 允许等号两边带空格。 */
        private val JVMARGS_LINE = Regex("""^\s*org\.gradle\.jvmargs\s*=""")
    }

    /** assets 内的架构特定目录 */
    val ASSET_DIR: String
        get() {
            // 优先按设备 ABI 选对应镜像目录。
            // 但 release 包按 flavor 拆分后，单架构包只含一套镜像（armsolo→arm、x86solo→x86）：
            // 若该套不在 assets 里（例如 armsolo 包被装到只报 x86 的设备），下面会 fallback
            // 到实际存在的那套，避免 open() 直接崩溃——proot 能否真正运行由设备 ABI 决定，
            // 但至少 asset 查找层不会挂。
            val preferX86 = android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }
            val first = if (preferX86) "container/x86" else "container/arm"
            val fallback = if (preferX86) "container/arm" else "container/x86"
            return if (assetExists(first)) first else fallback
        }

    /** 轻量探测某 asset 路径是否被打进当前 APK（用于 [ASSET_DIR] 的 fallback 判断） */
    private fun assetExists(path: String): Boolean =
        context.assets.list(path.substringBeforeLast('/'))?.any { it == path.substringAfterLast('/') } == true

    // 故意用中性的 .bin 后缀：AGP 的 asset 合并会把 .tar.gz/.tgz 当归档自动解压并改名，
    // 导致运行时 open("...tar.gz") 找不到文件。.bin 让它当普通二进制原样打包。
    val ASSET_ROOTFS: String get() = "$ASSET_DIR/alpine-rootfs.bin"

    /** rootfs 解压根目录 */
    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * AI 配置数据根目录（skill 指令 + MCP 配置），固定在 app 私有 filesDir。
     *
     * 刻意**独立于 [rootfsDir]**：rootfs 在容器版本升级时会被整体删除重装（见 [installRootfsIfNeed]），
     * 而本目录承载用户数据，必须跨升级保留。它由 [com.aharou.feature.agent.domain.container.LinuxContainerEngine]
     * 绑定到容器内 `/root/.aicode`，故 AI / 终端看到的 `/root/.aicode` 实际落在这里。
     */
    val aharouDir: File
        get() = migrateLegacyDir(context)

    /**
     * 跨工作区共享区（容器内 `/root/shared`）：所有工作区共用同一份，切工作区不重置。
     *
     * 放在 `filesDir/aharou-global/` 下是照上游（Minis）的结构来的：那边是
     * `filesDir/minis-global/{skills,memory,shared}`，本项目已把 `memory` 落在
     * `filesDir/aharou-global/memory`（见 [com.aharou.core.memory.AharouMemoryStore]），shared 与之并列。
     * 刻意**不并入 [aharouDir]**：那儿是 skills / mcp.json 等配置，混进用户文件会污染备份与凭据扫描。
     */
    val sharedDir: File
        get() = sharedDirOf(context)

    /**
     * proot 全套所在目录：APK 内 `lib/<abi>/lib*.so` 由安装器解压到此（见 build.gradle.kts 的
     * jniLibs sourceSet 与 useLegacyPackaging）。**不能改回 filesDir**——它是 App 目录里唯一
     * 允许 execve 的位置，filesDir 下的文件在 targetSdk 29+ 会被 W^X 拒绝执行。
     */
    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    /** PRoot 可执行文件（Termux 构建，含 statx，动态链接 libtalloc/libandroid-shmem） */
    val prootBin: File
        get() = File(nativeLibDir, "libproot.so")

    /** PRoot 的 64/32 位 loader（Termux proot loader 分离，由 PROOT_LOADER/_32 指向）。 */
    val prootLoader: File
        get() = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32: File
        get() = File(nativeLibDir, "libproot-loader32.so")

    /** proot 的动态依赖库目录（libtalloc.so / libandroid-shmem.so），由 LD_LIBRARY_PATH 指向。 */
    val prootLibDir: File
        get() = nativeLibDir

    /**
     * 内置容器的 PRoot 临时目录（Android 没有 /tmp）。**私有**：容器启动一律走 [prootTmpDirFor]，
     * 免得又把别的容器指到内置 rootfs 里来。
     * 放在 rootfs 的 /tmp（宿主 filesDir/rootfs/tmp）：cache 目录会被系统清理（清缓存后 proot
     * 找不到临时目录报 can't canonicalize），files 目录稳定。
     */
    private val prootTmpDir: File
        get() = File(rootfsDir, "tmp")

    /**
     * [profile] 自己 rootfs 里的 /tmp，即该容器的 `PROOT_TMP_DIR`。
     *
     * 必须按 profile 取：这是宿主路径，各容器 rootfs 目录相互隔离，共用内置容器的 tmp 会在内置 rootfs
     * 被重置删掉后让其它容器一起报 can't canonicalize（内置 rootfs 存在时能跑通只是巧合）。
     */
    fun prootTmpDirFor(profile: ContainerProfile): File = File(rootfsDirFor(profile), "tmp")

    /** 标记文件，内容是已安装的版本号 */
    private val installedMarker: File
        get() = File(rootfsDir, ".installed")

    /** 检查 rootfs 是否已按当前版本解压就绪（proot 随 APK 安装，不属于安装状态） */
    fun isInstalled(): Boolean {
        if (!rootfsDir.isDirectory) return false
        val marker = installedMarker
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION
    }

    /**
     * 安装互斥。
     *
     * App 启动、进终端、每次执行命令前都可能调用安装；没有这把锁时，两个调用方会同时看到
     * `isInstalled() == false`，然后各自 purge + 解压 + 起 proot 探针——两个进程在同一个 rootfs
     * 上互相删文件、抢 apk 数据库锁（`Unable to lock database: Resource temporarily unavailable`），
     * 典型现场就是装完一次直接用不了。
     */
    private val installMutex = Mutex()

    /**
     * 若未安装（或版本不匹配）则从 assets 解压安装。幂等，可在每次执行命令前调用。
     *
     * [onProgress] 在真正解压的各阶段被回调以更新 [ContainerInitState]；已安装的快路径不会调用。
     */
    suspend fun installRootfsIfNeed(
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        installMutex.withLock { installBuiltinRootfs(onProgress) }
    }

    /**
     * 内置 rootfs 的实际安装。**不加锁**：调用方必须已持有 [installMutex]。
     *
     * Mutex 不可重入，所以锁只加在入口、主体走这里；profile 入口也复用这一段。
     */
    private suspend fun installBuiltinRootfs(onProgress: (ContainerInitState) -> Unit) {
        if (isInstalled()) {
            FileLogger.d(TAG, "rootfs 已是当前版本（$INSTALL_VERSION），跳过解压")
            return
        }

        val startedAt = System.currentTimeMillis()
        FileLogger.i(
            TAG,
            "开始安装容器 rootfs（标记版本 $INSTALL_VERSION，asset 内 Alpine $ROOTFS_ASSET_VERSION，" +
                "asset=$ASSET_ROOTFS，目标=${rootfsDir.absolutePath}，" +
                "父目录可用空间=${usableSpaceMb(rootfsDir.parentFile)}MB）"
        )
        try {
            // 版本不匹配时清掉旧的，保证干净安装（大 rootfs 删一遍很慢，带进度报给界面）
            purgeRootfs(rootfsDir, installedMarker) { onProgress(ContainerInitState.CleaningOldRootfs(it)) }
            rootfsDir.mkdirs()
            if (!rootfsDir.isDirectory) FileLogger.e(TAG, "rootfs 目录创建失败：${rootfsDir.absolutePath}")

            val entries = extractRootfs(onProgress)
            configureResolvConf()
            applySourcePreferences(rootfsDir)
            prootTmpDir.mkdirs()
            verifyRootfsReady(rootfsDir)
            installedMarker.writeText(INSTALL_VERSION)
            FileLogger.i(
                TAG,
                "容器 rootfs 安装完成：$entries 个条目，耗时 ${System.currentTimeMillis() - startedAt}ms，" +
                    "标记=${installedMarker.absolutePath}"
            )
        } catch (e: Exception) {
            FileLogger.e(
                TAG,
                "容器 rootfs 安装失败（耗时 ${System.currentTimeMillis() - startedAt}ms，" +
                    "剩余空间=${usableSpaceMb(rootfsDir.parentFile)}MB）",
                e
            )
            throw e
        }
    }

    /** 目录可用空间（MB），用于解压失败时判断是否磁盘不足；路径取不到时返回 -1。 */
    private fun usableSpaceMb(dir: File?): Long =
        runCatching { (dir ?: return -1).usableSpace / (1024 * 1024) }.getOrDefault(-1)

    /**
     * 按 [profile] 返回 rootfs 目录：内置仍是 [rootfsDir]（不动），自定义本地镜像用 filesDir/rootfs_<id>。
     * 远程 SSH profile 无本地 rootfs，返回一个占位目录（不会被使用/创建）。
     * 目录隔离——内置与自定义互不共享、互不删除，切回内置时其 rootfs 原封不动。
     */
    fun rootfsDirFor(profile: ContainerProfile): File =
        if (profile.isBuiltin) rootfsDir
        else File(context.filesDir, "rootfs_${profile.id}")

    /** 自定义镜像的已安装标记（独立于内置 .installed，避免混淆）。 */
    private fun customInstalledMarker(profile: ContainerProfile): File =
        File(rootfsDirFor(profile), ".installed_custom")

    /** 按 [profile] 判断是否已安装就绪：内置走现有版本校验，自定义本地看目录与标记，远程 SSH 恒就绪。 */
    fun isInstalledFor(profile: ContainerProfile): Boolean =
        when {
            profile.isBuiltin -> isInstalled()
            profile.rootfsSource is RootfsSource.RemoteSsh -> true
            else -> rootfsDirFor(profile).isDirectory && customInstalledMarker(profile).exists()
        }

    /**
     * 按 [profile] 解压安装 rootfs。内置走 assets 全流程（rootfs/resolv/apk 源）；自定义本地镜像只解压
     * tar.gz + 写 DNS（不写 apk 源）。两者都不自动装包——基础工具由进入终端时的
     * 初始化菜单（assets/aharou/provision.sh）引导用户选择安装。远程 SSH profile 无本地 rootfs，直接返回
     * （命令执行走 [RemoteSshEngine]，不需本地 rootfs）。
     */
    suspend fun installRootfsIfNeed(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledFor(profile)) return@withContext

        // rootfs 将被重新解压，容器系统可能变化，清掉旧的识别缓存让下次运行重新检测。
        containerOsDetector.clear(profile.id)

        installMutex.withLock {
            // 等锁期间别的协程可能已经把同一个 rootfs 装好了，重新判一次，别重复解压。
            if (isInstalledFor(profile)) return@withLock

            if (profile.isBuiltin) {
                installBuiltinRootfs(onProgress)
                return@withLock
            }

            // 远程 SSH profile：无本地 rootfs 可解压，视为就绪。
            if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withLock

            installCustomRootfs(profile, onProgress)
        }
    }

    /** 自定义（本地镜像）rootfs 的安装。**不加锁**：调用方必须已持有 [installMutex]。 */
    private suspend fun installCustomRootfs(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit
    ) {
        val dest = rootfsDirFor(profile)
        FileLogger.i(TAG, "安装自定义容器 rootfs：${profile.id} -> ${dest.absolutePath}")
        purgeRootfs(dest, customInstalledMarker(profile)) { onProgress(ContainerInitState.CleaningOldRootfs(it)) }
        dest.mkdirs()

        when (val src = profile.rootfsSource) {
            is RootfsSource.Asset -> context.assets.open("${ASSET_DIR}/${src.path}").use {
                extractRootfsTo(dest, it, CompressedFormat.GZIP, onProgress)
            }
            is RootfsSource.LocalFile -> {
                val uri = android.net.Uri.parse(src.uri)
                val format = if (src.uri.endsWith(".xz") || src.uri.endsWith(".txz"))
                    CompressedFormat.XZ else CompressedFormat.GZIP
                context.contentResolver.openInputStream(uri)?.use {
                    extractRootfsTo(dest, it, format, onProgress)
                } ?: FileLogger.w(TAG, "打开导入的 rootfs uri 失败: ${src.uri}")
            }
            is RootfsSource.RemoteSsh -> { /* 无本地 rootfs，上面已提前 return */ }
        }
        configureResolvConf(dest)
        prootTmpDirFor(profile).mkdirs()
        repairRootfsCompatibility(dest)
        verifyRootfsReady(dest)
        customInstalledMarker(profile).writeText("custom")
        FileLogger.i(TAG, "自定义容器 rootfs 安装完成：${profile.id}")
    }

    /**
     * 按「设置 → 镜像与 DNS」把源与解析写进刚解压出来的 rootfs。
     *
     * 时机必须在这里：设置页的换源/改 DNS 靠容器执行命令（`isContainerReady()`），而首次初始化
     * 恰恰是容器还不能用的时候。没有这一步，即使用户提前选了「国内镜像」，首次装包仍会走官方源，
     * 想快只能等初始化完再回头改，绕一圈。
     *
     * 纯文件操作，不启动 PRoot；读不到设置就保持 rootfs 自带配置，不阻断安装。
     */
    private suspend fun applySourcePreferences(rootfs: File) {
        val settings = runCatching { sourcesRepository?.read() }.getOrNull() ?: return
        if (settings.mirror == ContainerMirrorMode.CHINA) {
            writeChinaMirror(rootfs, settings.mirrorHost)
        }
        writeResolvConf(rootfs, dnsServersFor(settings.dns), settings.dns)
    }

    /** 把 apk 源改成国内镜像；分支沿用 rootfs 自带的（导入的其它 Alpine 版本也能对上）。 */
    private fun writeChinaMirror(rootfs: File, preferredHost: String) {
        val repositories = File(rootfs, "etc/apk/repositories")
        val branch = runCatching {
            if (repositories.isFile) {
                Regex("alpine/([^/]+)/").find(repositories.readText())?.groupValues?.get(1)
            } else null
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "v3.24"
        val host = preferredHost.ifBlank { DEFAULT_CHINA_MIRROR }
        runCatching {
            repositories.writeText(
                "http://$host/alpine/$branch/main\nhttp://$host/alpine/$branch/community\n"
            )
            FileLogger.i(TAG, "首次安装已按设置换成国内源：$host（$branch）")
        }.onFailure { FileLogger.w(TAG, "写容器 apk 源失败：${it.message}") }
    }

    /** 按设置写 resolv.conf（覆盖 [configureResolvConf] 的公共 DNS 默认值）。 */
    private fun writeResolvConf(rootfs: File, servers: List<String>, mode: ContainerDnsMode) {
        if (servers.isEmpty()) return
        writeResolvFile(File(rootfs, "etc/resolv.conf"), servers, mode)
    }

    /**
     * 「跟随系统」模式下把容器 DNS 刷成当前网络的 DNS（换 WiFi / 挂代理后宿主 DNS 会变）。
     * 只在文件里有 `aharou-dns-mode: SYSTEM` 标记时动，否则会冲掉用户选的固定 DNS。
     */
    fun refreshSystemDns(rootfs: File) {
        val conf = File(rootfs, "etc/resolv.conf")
        val text = runCatching { if (conf.isFile) conf.readText() else null }.getOrNull() ?: return
        if (!text.contains("aharou-dns-mode: ${ContainerDnsMode.SYSTEM.name}")) return
        val servers = systemDnsServers().ifEmpty { return }
        val current = text.lineSequence().map { it.trim() }.filter { it.startsWith("nameserver ") }.toList()
        if (current == servers.map { "nameserver $it" }) return
        writeResolvFile(conf, servers, ContainerDnsMode.SYSTEM)
    }

    private fun writeResolvFile(conf: File, servers: List<String>, mode: ContainerDnsMode) {
        runCatching {
            conf.parentFile?.mkdirs()
            // 容器里常见指向 systemd-resolved 的坏链，先删再写。
            conf.delete()
            conf.writeText(servers.joinToString("") { "nameserver $it\n" } + "# aharou-dns-mode: ${mode.name}\n")
            FileLogger.i(TAG, "容器 DNS 已写入：${servers.joinToString()}（$mode）")
        }.onFailure { FileLogger.w(TAG, "写容器 DNS 失败：${it.message}") }
    }

    private fun dnsServersFor(mode: ContainerDnsMode): List<String> = when (mode) {
        ContainerDnsMode.ALI_DNSPOD -> ALI_DNSPOD_DNS
        ContainerDnsMode.CLOUDFLARE_GOOGLE -> CLOUDFLARE_GOOGLE_DNS
        ContainerDnsMode.SYSTEM -> systemDnsServers().ifEmpty { ALI_DNSPOD_DNS }
    }

    /** 宿主当前网络的 IPv4 DNS。只取 IPv4：蜂窝/VPN 常「能配 v6 但出不去」，写进去会先等超时。 */
    private fun systemDnsServers(): List<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        manager.getLinkProperties(manager.activeNetwork)?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.filter { IPV4.matches(it) }
            ?.distinct()
            ?.take(MAX_DNS_SERVERS)
            .orEmpty()
    }.getOrElse { emptyList() }

    /**
     * 写就绪标记前的最后一道关：布局完整 + 真能起一条命令。
     *
     * 标记一旦落下，[isInstalled] 就会把这个 rootfs 当成装好的容器：后续每条命令都失败，而用户
     * 拿不到任何「装坏了」的提示，只能靠用户报障。宁可安装当次报错（可重试、可重置），也不要留下
     * 一个假就绪的容器。
     *
     * 失败时抛的文案走资源，由 UI 层展示。
     */
    private suspend fun verifyRootfsReady(rootfs: File) {
        val doctor = containerDoctor?.get() ?: return
        when (val verdict = doctor.verifyRootfs(rootfs)) {
            RootfsVerdict.Ready -> return
            is RootfsVerdict.LayoutMissing -> throw IllegalStateException(
                context.getString(
                    R.string.container_verify_layout_missing,
                    verdict.entries.joinToString("、")
                )
            )
            is RootfsVerdict.BootFailed -> throw IllegalStateException(
                context.getString(R.string.container_verify_boot_failed, verdict.detail)
            )
            is RootfsVerdict.BootTimeout -> throw IllegalStateException(
                context.getString(R.string.container_verify_boot_timeout, verdict.timeoutMs / 1000)
            )
        }
    }

    /**
     * rootfs 兼容性巡检（幂等，失败静默）：修复已知的 Ubuntu 25.10+ 布局问题。
     * 所有容器统一调用，作用面由内部存在性检测收敛——非命中容器零改动。
     */
    fun repairRootfsCompatibility(rootfs: File) {
        fixCoreutilsPermissions(rootfs)
        fixNodeGlobEntry(rootfs)
    }

    /**
     * 把 Gradle 堆上限写进容器用户的 `~/.gradle/gradle.properties`（用户级）。
     *
     * 用户级 gradle.properties 优先级高于项目根目录那份（Gradle 文档：GRADLE_USER_HOME > Build Root Dir >
     * GRADLE_HOME），所以项目里写死的 `org.gradle.jvmargs` 会被这里覆盖——这正是「按设备内存调上限」的落点，
     * 也让同一台机器上任意项目都能生效。文件里其它行（aapt2 覆盖等）原样保留；内容没变不写盘。
     *
     * @return 是否真的改动了文件
     */
    fun syncGradleHeap(rootfs: File, heapMb: Int): Boolean {
        val dir = File(File(rootfs, "root"), ".gradle")
        val file = File(dir, "gradle.properties")
        // Metaspace 不跟堆等比：2GB 档等比只剩 512m，同一工程加载依赖即 OutOfMemoryError: Metaspace。
        // 仓库根 gradle.properties 的 4096m/1024m 是 CI 反复 OOM 后收敛的配对，这里固定同一档。
        val jvmArgs = "-Xmx${heapMb}m -XX:MaxMetaspaceSize=1024m -XX:+UseParallelGC"
        val current = if (file.isFile) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()
        if (current.any { it.trim() == "org.gradle.jvmargs=$jvmArgs" }) return false
        val lines = current.filterNot { JVMARGS_LINE.containsMatchIn(it) } +
            "org.gradle.jvmargs=$jvmArgs"
        dir.mkdirs()
        // 先写同目录临时文件再 rename 覆盖：writeText 是清空后重写，多个进程并发起命令时
        // 另一个进程可能读到空文件或半截内容而拿不到 jvmargs。同目录保证 rename 不跨文件系统。
        val tmp = File.createTempFile("gradle.properties", ".tmp", dir)
        try {
            tmp.writeText(lines.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) throw IOException("rename 失败: ${tmp.absolutePath} -> ${file.absolutePath}")
        } finally {
            tmp.delete()
        }
        FileLogger.i(TAG, "容器 Gradle 堆上限已更新：${jvmArgs}（${file.absolutePath}）")
        return true
    }

    private fun fixCoreutilsPermissions(rootfs: File) {
        val coreutilsDir = File(rootfs, "usr/lib/cargo/bin/coreutils")
        if (!coreutilsDir.isDirectory) return
        val target = PosixFilePermissions.fromString("rwxr-xr-x")
        var fixed = 0
        fun fix(path: File) {
            runCatching {
                val p = path.toPath()
                if (Files.getPosixFilePermissions(p) != target) {
                    Files.setPosixFilePermissions(p, target)
                    fixed++
                }
            }.onFailure { FileLogger.w(TAG, "修复 coreutils 权限失败: ${path.absolutePath}", it) }
        }
        fix(File(rootfs, "usr/lib/cargo"))
        fix(File(rootfs, "usr/lib/cargo/bin"))
        fix(coreutilsDir)
        coreutilsDir.listFiles()?.forEach { fix(it) }
        if (fixed > 0) {
            FileLogger.i(TAG, "已修复容器 coreutils 权限: $fixed 个文件（${coreutilsDir.absolutePath}）")
        }
    }

    /**
     * 修复 Ubuntu 26.04 仓库打包 bug：node-glob 10.3.6 用 `dist/cjs/src -> .` 自引用软链做打包
     * 拍平，node 22+ 的 realpath 判定 ELOOP，npm 启动即报 MODULE_NOT_FOUND（与 PRoot 无关，
     * 原生 Ubuntu 26.04 同样中招）。真机布局下的修复：把 package.json 入口从 dist/cjs/src/
     * 改指真实打平文件所在 dist/cjs/。幂等：入口已正常则不动。
     */
    private fun fixNodeGlobEntry(rootfs: File) {
        val candidates = listOf(
            File(rootfs, "usr/share/nodejs/glob/package.json"),
            File(rootfs, "usr/share/node_modules/glob/package.json")
        )
        for (f in candidates) {
            if (!f.isFile) continue
            runCatching {
                val text = f.readText()
                if (text.contains("dist/cjs/src/")) {
                    f.writeText(text.replace("dist/cjs/src/", "dist/cjs/"))
                    FileLogger.i(TAG, "已修复 node-glob 入口: ${f.absolutePath}")
                }
            }.onFailure { FileLogger.w(TAG, "修复 node-glob 入口失败: ${f.absolutePath}", it) }
        }
    }

    /**
     * 删除自定义 profile 的 rootfs 目录（删 profile 时调用）。内置 rootfs 不可删，远程 SSH 无 rootfs 可删。
     * [onProgress] 报告已删条目数，供界面显示进度。
     */
    suspend fun deleteCustomRootfs(
        profile: ContainerProfile,
        onProgress: (Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        // profile 被删除或换镜像，缓存随之作废（重新运行时重新检测）。
        containerOsDetector.clear(profile.id)
        if (profile.isBuiltin) return@withContext
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext
        purgeRootfs(rootfsDirFor(profile), customInstalledMarker(profile), onProgress)
    }

    /**
     * 重置内置 Alpine 容器：删除其 rootfs 目录（含 .installed / .provisioned 标记），
     * 下次 [ensureInstalled] 会重新解压；进入终端时由初始化菜单重新引导安装。供内置镜像「重置」按钮调用。
     */
    suspend fun resetBuiltinRootfs(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        purgeRootfs(rootfsDir, installedMarker, onProgress)
    }

    /** 按 [profile] 统一重置 rootfs：内置走 [resetBuiltinRootfs]，自定义本地删其 rootfs 目录，远程 SSH 无本地数据不操作。 */
    suspend fun resetRootfs(profile: ContainerProfile, onProgress: (Int) -> Unit = {}) {
        if (profile.isBuiltin) {
            resetBuiltinRootfs(onProgress)
            return
        }
        deleteCustomRootfs(profile, onProgress)
    }

    /**
     * 删空一个 rootfs 目录，[onProgress] 报告累计已删条目数。
     *
     * 先删 [marker]：装满工具的 rootfs 有十万级 inode，删一遍要几十秒，万一中途进程被杀，标记
     * 已经没了，残缺目录不会再被 [isInstalledFor] 当成装好的容器，下次进容器会先删干净再重新解压。
     */
    private fun purgeRootfs(dir: File, marker: File, onProgress: (Int) -> Unit) {
        if (!dir.exists()) return
        marker.delete()
        val count = deleteTree(dir, 0, onProgress)
        FileLogger.i(TAG, "已删除 rootfs ${dir.name}，共 $count 项")
    }

    /**
     * 递归删除 [root]，返回从 [startCount] 起算的累计已删条目数，每 [DELETE_PROGRESS_STEP] 项回调一次。
     *
     * 不用 [File.deleteRecursively]：它按 `File.isDirectory` 判断，会跟随符号链接下钻——rootfs 的
     * /bin、/usr/lib 下满是 symlink，跟随不仅白跑一遍链接目标（大容器上慢出好几倍），指向自身祖先时
     * 还会一路下钻到路径超长才停，更别说链接指向 rootfs 外面时会删掉别处的数据。这里用
     * NOFOLLOW_LINKS 判定目录，符号链接一律当普通条目 unlink。
     */
    private fun deleteTree(root: File, startCount: Int, onProgress: (Int) -> Unit): Int {
        var count = startCount
        val pending = ArrayDeque<File>()
        val dirs = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (isRealDirectory(entry)) {
                dirs.addLast(entry)
                entry.listFiles()?.forEach { pending.addLast(it) }
            } else if (entry.delete()) {
                count++
                if (count % DELETE_PROGRESS_STEP == 0) onProgress(count)
            }
        }
        // 目录得等自己空了才删得掉：dirs 按自浅入深的发现顺序入队，从队尾往回删即先深后浅。
        while (dirs.isNotEmpty()) {
            if (dirs.removeLast().delete()) count++
        }
        onProgress(count)
        return count
    }

    /** 是否真目录：符号链接即便指向目录也返回 false，防止删除时跟着链接下钻。 */
    private fun isRealDirectory(file: File): Boolean = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
    }.getOrDefault(false)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            extractDocs(context)
            extractCredentialHelper(context)
            extractProvisionScript(context)
            extractEnvTool(context)
        }
    }

    /** 从 assets 提取文档到 ~/.aharou/docs (内置使用指导) */
    fun extractDocs() = extractDocs(context)

    /** 从 assets 提取 git credential helper 到 ~/.aharou/git-credential-aicode 并赋可执行位。 */
    fun extractCredentialHelper() = extractCredentialHelper(context)

    /** 从 assets 提取容器初始化依赖安装脚本到 ~/.aharou/provision.sh 并赋可执行位。 */
    fun extractProvisionScript() = extractProvisionScript(context)

    /** 从 assets 提取环境工具（aicode 命令 + 共享库）到 ~/.aharou/{bin,lib} 并赋可执行位。 */
    fun extractEnvTool() = extractEnvTool(context)

    /** 解压 alpine-minirootfs.tar.gz，正确处理目录/文件/符号链接/硬链接与权限位；返回解压条目数。 */
    private fun extractRootfs(onProgress: (ContainerInitState) -> Unit): Int {
        context.assets.open(ASSET_ROOTFS).use { rawIn ->
            return extractRootfsTo(rootfsDir, rawIn, CompressedFormat.GZIP, onProgress)
        }
    }

    /** 镜像压缩格式：内置 Alpine 用 gzip，用户导入的可能是 gzip 或 xz。 */
    enum class CompressedFormat { GZIP, XZ }

    /**
     * 把 tar.gz / tar.xz 流解压到 [destDir]，正确处理目录/文件/符号链接/硬链接与权限位。
     * 内置 Alpine（[extractRootfs] 传 assets 流）与用户自定义镜像（[installRootfsIfNeed] 传 content uri 流）共用。
     */
    fun extractRootfsTo(
        destDir: File,
        input: java.io.InputStream,
        format: CompressedFormat = CompressedFormat.GZIP,
        onProgress: (ContainerInitState) -> Unit = {}
    ): Int {
        var processed = 0
        val startedAt = System.currentTimeMillis()
        val decompressed = when (format) {
            CompressedFormat.GZIP -> GZIPInputStream(input)
            CompressedFormat.XZ -> XZCompressorInputStream(input)
        }
        decompressed.use { decompIn ->
            TarArchiveInputStream(decompIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    extractEntry(destDir, tarIn, entry)
                    processed++
                    onProgress(ContainerInitState.ExtractingRootfs(processed))
                    entry = tarIn.nextEntry
                }
            }
        }
        FileLogger.i(
            TAG,
            "解压到 ${destDir.absolutePath} 完成：$processed 个条目，耗时 ${System.currentTimeMillis() - startedAt}ms"
        )
        return processed
    }

    private fun extractEntry(
        destDir: File,
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry
    ) {
        val outFile = File(destDir, entry.name)

        // 防 zip-slip：确保解压目标落在 destDir 内
        val canonicalRoot = destDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator) &&
            outFile.canonicalPath != canonicalRoot
        ) {
            FileLogger.w(TAG, "跳过越界条目: ${entry.name}")
            return
        }

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                // symlink 的目标可能是相对/绝对路径，原样创建（在容器内由 proot 解析）
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    .onFailure { FileLogger.w(TAG, "symlink 失败 ${entry.name} -> ${entry.linkName}: ${it.message}") }
            }

            entry.isLink -> {
                // 硬链接：linkName 指向 tar 内已解压的另一文件
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(target.absolutePath, outFile.absolutePath) }
                    .onFailure {
                        // 退化为复制，保证文件存在
                        FileLogger.w(TAG, "hardlink 失败 ${entry.name} -> ${entry.linkName}，改为复制: ${it.message}")
                        runCatching { target.copyTo(outFile, overwrite = true) }
                    }
            }

            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tarIn.copyTo(it) }
                applyMode(outFile, entry.mode)
            }

            else -> FileLogger.d(TAG, "忽略不支持的条目类型: ${entry.name}")
        }
    }

    /** 按 tar entry 的 mode 设置可执行位（owner 有 x 位则对所有人开放执行） */
    private fun applyMode(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0 // 0100
        if (ownerExecutable) {
            file.setExecutable(true, false)
        }
        file.setReadable(true, false)
        // owner 写位
        if ((mode and 0b010_000_000) != 0) file.setWritable(true, false)
    }

    /**
     * 写入容器内 DNS（阿里云公共 DNS，8.8.8.8 在部分网络被拦）。
     * 有效配置（含 nameserver 且非 systemd-resolved stub）则不动；空文件/坏链/stub 先删再写。
     */
    private fun configureResolvConf(rootfs: File = rootfsDir) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        if (conf.isFile) {
            runCatching {
                val text = conf.readText()
                if (text.contains("nameserver") && !text.contains("127.0.0.53") && !text.contains("nameserver 127.0.0.1")) {
                    FileLogger.d(TAG, "resolv.conf 已有有效配置，保持不动：${conf.absolutePath}")
                    return
                }
            }
        }
        conf.delete()
        conf.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
        FileLogger.i(TAG, "已写入容器 DNS 配置：${conf.absolutePath}")
    }
}
