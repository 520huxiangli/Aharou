package com.aharou.feature.editor.lsp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 语言服务器在容器里的安装方式。 */
enum class InstallKind {
    /** 走容器自带的包管理器（apk / pacman / dnf / yum / zypper）。 */
    APK,

    /** 走全局 npm 包。 */
    NPM,

    /** 走 pip3 包。 */
    PIP,

    /** 预留，暂未实现。 */
    COMPOSER,

    /** 不走包管理器，直接下官方 release 静态二进制。 */
    RELEASE
}

/**
 * 一条语言服务器的元数据：把原先散在 [LanguageExtension] 各 `when` 分支里的信息集中到一处。
 *
 * [packageName] 对 NPM 允许用逗号分隔多个包（如 `typescript-language-server,typescript`）。
 * [releaseAssetFor] 给出「容器架构 → release 资产文件名」的映射，供 [InstallKind.RELEASE]
 * 或包管理器失败后的兜底路径使用；[releaseEntryRelPath] 是解压目录内可执行文件的相对路径。
 */
data class ServerSpec(
    val id: String,
    val command: String,
    val displayName: String,
    val installKind: InstallKind,
    val packageName: String? = null,
    val releaseRepo: String? = null,
    val releaseVersion: String? = null,
    val releaseAssetFor: ((arch: String) -> String)? = null,
    val releaseEntryRelPath: String? = null
)

/**
 * 编辑器支持的语言扩展（语言服务器）。
 *
 * 枚举只做常量持有，全部信息放在 [spec] 里；[id] / [command] / [displayName] 作为委托属性保留，
 * 现有调用点（[EditorLspManager]、编辑器设置页、探活等）无需改动。
 */
enum class LanguageExtension(val spec: ServerSpec) {
    LUA(
        ServerSpec(
            id = "lua",
            command = "lua-language-server",
            displayName = "Lua Language Server",
            installKind = InstallKind.APK,
            packageName = "lua-language-server",
            releaseRepo = "LuaLS/lua-language-server",
            releaseVersion = "3.19.1",
            releaseAssetFor = { arch -> "lua-language-server-3.19.1-$arch.tar.gz" },
            releaseEntryRelPath = "bin/lua-language-server"
        )
    ),
    PYTHON(
        ServerSpec(
            id = "python",
            command = "pylsp",
            displayName = "Python Language Server",
            installKind = InstallKind.PIP,
            packageName = "python-lsp-server"
        )
    ),
    TYPESCRIPT(
        ServerSpec(
            id = "typescript",
            command = "typescript-language-server",
            displayName = "TypeScript Language Server",
            installKind = InstallKind.NPM,
            // typescript-language-server 依赖 typescript 本体，必须一并装（逗号分隔多包）。
            packageName = "typescript-language-server,typescript"
        )
    ),
    SHELL(
        ServerSpec(
            id = "shell",
            command = "bash-language-server",
            displayName = "Bash Language Server",
            installKind = InstallKind.NPM,
            packageName = "bash-language-server"
        )
    ),
    YAML(
        ServerSpec(
            id = "yaml",
            command = "yaml-language-server",
            displayName = "YAML Language Server",
            installKind = InstallKind.NPM,
            packageName = "yaml-language-server"
        )
    );

    /** 状态文件里的键，也是扩展标识。 */
    val id: String get() = spec.id

    /** 容器内可执行名，用于 `command -v` 与 `--version` 探活。 */
    val command: String get() = spec.command

    /** 展示名。 */
    val displayName: String get() = spec.displayName
}

/** 语言扩展在容器内的安装/可用状态。 */
sealed interface ExtensionStatus {
    /** 命令不存在，需要安装。 */
    object NotInstalled : ExtensionStatus

    /** 命令存在且可用。 */
    data class Installed(val version: String) : ExtensionStatus

    /** 无法判断（容器未就绪、命令未能执行）。 */
    data class Unavailable(val reason: String) : ExtensionStatus

    /** 命令存在但探活结果无法解析（如版本命令异常退出）。 */
    data class Unknown(val detail: String) : ExtensionStatus
}

/**
 * 一次安装的结果。[output] 为命令输出尾部（截断到 [LanguageServerInstaller.MAX_OUTPUT_CHARS]），供 UI 展示进度。
 */
data class InstallResult(
    val success: Boolean,
    val exitCode: Int?,
    val output: String,
    val status: ExtensionStatus
)

/**
 * 语言扩展在容器内的安装与探活。
 *
 * 安装方式由 [ServerSpec.installKind] 决定：apk / pacman 等包管理器（APK）、全局 npm（NPM）、
 * pip3（PIP），或直接下官方 release 静态二进制（RELEASE）。探活靠 `command -v <命令>` +
 * 按安装方式取版本。已装状态缓存到容器持久目录 `/root/.aharou/lsp-extensions.json`
 * （宿主 filesDir/aharou，容器升级不丢），探活成功时顺带落盘；写盘失败只记日志、不抛给调用方。
 *
 * 容器未就绪时（[CommandEngine.runCommandSyncIfReady] 返回 null）一律返回
 * [ExtensionStatus.Unavailable]，不硬报错，由调用方引导用户先初始化容器。
 */
@Singleton
class LanguageServerInstaller @Inject constructor(
    private val commandEngine: CommandEngine,
    private val fileAccess: FileAccessProvider
) {

    companion object {
        private const val TAG = "LspInstaller"

        /** 状态文件（容器路径）。 */
        const val STATUS_FILE = "${WorkspacePathMapper.AHAROU_ROOT}/lsp-extensions.json"

        /** 探活命令超时。 */
        private const val PROBE_TIMEOUT_MS = 15_000L

        /** 安装命令超时：拉包 + 解包 + （npm/pip 首次）拉元数据，给足余量。 */
        private const val INSTALL_TIMEOUT_MS = 300_000L

        /** 返回给 UI 的输出上限（字符）。 */
        const val MAX_OUTPUT_CHARS = 1000

        /** 版本号解析失败时的占位。 */
        const val UNKNOWN_VERSION = "unknown"

        /**
         * 非包管理器路线的官方静态二进制镜像。GitHub 直连在国内很慢，先走反代、失败回退原链。
         * 官方包只有 glibc 构建，musl 系（Alpine）用不了，必须走包管理器。
         */
        private const val RELEASE_MIRROR = "https://v6.gh-proxy.org/"

        /**
         * npm 走国内镜像。只给这一条安装命令加 `--registry`，不改容器的全局 npm 配置——
         * 改全局会连累用户在终端里的其它 npm 用法。
         */
        private const val NPM_REGISTRY = "https://registry.npmmirror.com"

        /**
         * pip 走国内镜像。PyPI 官方源在国内是纯网络阻塞（实测装 python-lsp-server 八分钟只堆了
         * 7MB 缓存、CPU 累计仅 6 秒），同样只加在这一条命令上、不改容器全局 pip 配置。
         */
        private const val PYPI_MIRROR = "https://pypi.tuna.tsinghua.edu.cn/simple"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }
        private val VERSION_REGEX = Regex("""\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.\-]+)?""")
    }

    /** 语言服务器的最小运行时前置：npm 或 pip3。 */
    private enum class RuntimeKind { NPM, PIP }

    /**
     * 探活：命令是否存在 + 版本。容器未就绪返回 [ExtensionStatus.Unavailable]。
     * 探活成功时把版本写入状态文件（失败只记日志）。
     */
    suspend fun probe(extension: LanguageExtension): ExtensionStatus = withContext(Dispatchers.IO) {
        val cmd = extension.command
        val which = runIfReady("command -v $cmd") ?: return@withContext unavailable()

        if (which.exitCode != 0 || which.output.isBlank()) return@withContext ExtensionStatus.NotInstalled

        // 版本优先问安装来源：Alpine 的 lua-language-server 只是个转发脚本，`--version` 会走到
        // LSP 启动逻辑、又因包内缺 changelog.md 而打成 `<Unknown>`（2026-10-05 实测），不能当依据。
        // 「装没装」仍以 command -v 为准（上面已判）。
        val status = ExtensionStatus.Installed(detectVersion(extension) ?: UNKNOWN_VERSION)
        persist(extension, status)
        status
    }

    /**
     * 安装：按 [ServerSpec.installKind] 选路线；成功后再探活一次，回读真实版本并落盘。
     * 容器未就绪或方式暂未实现时返回失败 + [ExtensionStatus.Unavailable]。
     */
    suspend fun install(extension: LanguageExtension): InstallResult = withContext(Dispatchers.IO) {
        val result = installCommand(extension) ?: return@withContext InstallResult(
            success = false,
            exitCode = null,
            output = "",
            status = unavailable()
        )

        val output = tail(result.output)
        if (result.exitCode != 0) {
            FileLogger.w(TAG, "安装 ${extension.displayName} 失败，exit=${result.exitCode}")
            return@withContext InstallResult(
                success = false,
                exitCode = result.exitCode,
                output = output,
                status = ExtensionStatus.NotInstalled
            )
        }

        // 安装成功后再探活，拿到真实版本并写入状态文件
        InstallResult(success = true, exitCode = 0, output = output, status = probe(extension))
    }

    /**
     * 读已装状态（来自状态文件）。无记录返回 null；容器未就绪也能读到缓存。
     */
    suspend fun cachedStatus(extension: LanguageExtension): ExtensionStatus? = withContext(Dispatchers.IO) {
        val node = readStatusRoot()[extension.id]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return@withContext null

        when (node["installed"]?.jsonPrimitive?.booleanOrNull) {
            true -> {
                val version = node["version"]?.jsonPrimitive?.contentOrNull ?: UNKNOWN_VERSION
                ExtensionStatus.Installed(version)
            }
            false -> ExtensionStatus.NotInstalled
            null -> null
        }
    }

    // ── 内部实现 ──

    /**
     * 按 [InstallKind] 分派安装：
     * - [InstallKind.APK]：先试容器包管理器，失败（或 Alpine 系本来就没有官方二进制）再落官方二进制；
     * - [InstallKind.RELEASE]：直接走官方二进制；
     * - [InstallKind.NPM]：先确保 npm 可用，再全局安装（多包用逗号分隔）；
     * - [InstallKind.PIP]：先确保 pip3 可用，再用户级安装；
     * - [InstallKind.COMPOSER]：暂未实现，返回 null（上层表现为安装失败）。
     */
    private suspend fun installCommand(extension: LanguageExtension) = run {
        val spec = extension.spec
        when (spec.installKind) {
            InstallKind.APK -> installViaPackageManager(spec)
            InstallKind.RELEASE -> {
                val script = buildReleaseTarballScript(spec) ?: return@run null
                runIfReady(script, INSTALL_TIMEOUT_MS)
            }
            InstallKind.NPM -> {
                if (!ensureRuntime(RuntimeKind.NPM)) return@run null
                val script = buildNpmInstallScript(spec) ?: return@run null
                runIfReady(script, INSTALL_TIMEOUT_MS)
            }
            InstallKind.PIP -> {
                if (!ensureRuntime(RuntimeKind.PIP)) return@run null
                val script = buildPipInstallScript(spec) ?: return@run null
                runIfReady(script, INSTALL_TIMEOUT_MS)
            }
            InstallKind.COMPOSER -> null
        }
    }

    /**
     * 包管理器路线：不按发行版枚举、只按容器实际有什么来判断（apk / pacman / dnf / yum / zypper）。
     *
     * Alpine（musl）没有可用的官方二进制，所以包管理器失败就直接返回，不再白跑一轮。
     * apt 系故意不试：Ubuntu/Debian 源里没有这些语言服务器包，试了只是白等。
     */
    private suspend fun installViaPackageManager(spec: ServerSpec) = run {
        val pmCommand = packageManagerCommand(spec)
        if (pmCommand != null) {
            val pmResult = runIfReady(pmCommand, INSTALL_TIMEOUT_MS)
            if (pmResult != null && pmResult.exitCode == 0) return@run pmResult
            if (hasCommand("apk")) return@run pmResult
            FileLogger.w(TAG, "包管理器安装失败（exit=${pmResult?.exitCode}），改用官方二进制")
        }
        val script = buildReleaseTarballScript(spec) ?: return@run null
        runIfReady(script, INSTALL_TIMEOUT_MS)
    }

    /** 容器里认识的包管理器给出的安装命令；都不认识返回 null（交给二进制路线）。 */
    private suspend fun packageManagerCommand(spec: ServerSpec): String? {
        val pkg = spec.packageName ?: return null
        return when {
            hasCommand("apk") -> "apk add --no-cache $pkg"
            hasCommand("pacman") -> "pacman -Sy --noconfirm $pkg"
            hasCommand("dnf") -> "dnf install -y $pkg"
            hasCommand("yum") -> "yum install -y $pkg"
            hasCommand("zypper") -> "zypper -n install $pkg"
            else -> null
        }
    }

    /**
     * 语言服务器的运行时前置：NPM 需要 npm、PIP 需要 pip3；缺了就在容器里按包管理器补装。
     *
     * 这份映射刻意内联、只留最小子集，与 assets 里 `env-common.sh` 的 `runtime_pkgs` 是两套：
     * 那份服务于「进入终端」时的一次性环境准备（含宿主动态库、可选工具、用户可增删），
     * 这里只覆盖「装某个语言服务器前必须先有的运行时」，且要能在探活/安装路径上独立跑。
     *
     * 装不上（认不出包管理器、命令失败、装完仍探不到）返回 false，由 [install] 表现为安装失败。
     */
    private suspend fun ensureRuntime(kind: RuntimeKind): Boolean {
        val probe = when (kind) {
            RuntimeKind.NPM -> "npm"
            RuntimeKind.PIP -> "pip3"
        }
        if (hasCommand(probe)) return true
        val command = runtimeInstallCommand(kind) ?: return false
        FileLogger.i(TAG, "容器缺 $probe，尝试安装运行时：$command")
        val result = runIfReady(command, INSTALL_TIMEOUT_MS) ?: return false
        if (result.exitCode != 0) {
            FileLogger.w(TAG, "$probe 运行时安装失败（exit=${result.exitCode}）")
            return false
        }
        return hasCommand(probe)
    }

    /** 按容器里实际有的包管理器给出运行时安装命令；包名随发行版不同（Alpine 的 pip 包是 py3-pip）。 */
    private suspend fun runtimeInstallCommand(kind: RuntimeKind): String? {
        val node = "nodejs npm"
        val pip = when {
            hasCommand("apk") -> "python3 py3-pip"
            hasCommand("pacman") -> "python python-pip"
            else -> "python3 python3-pip"
        }
        val packages = if (kind == RuntimeKind.NPM) node else pip
        return when {
            hasCommand("apk") -> "apk add --no-cache $packages"
            hasCommand("apt-get") -> "(apt-get update || true) && apt-get install -y $packages"
            hasCommand("pacman") -> "pacman -Sy --noconfirm $packages"
            hasCommand("dnf") -> "dnf install -y $packages"
            hasCommand("yum") -> "yum install -y $packages"
            hasCommand("zypper") -> "zypper -n install $packages"
            else -> null
        }
    }

    /**
     * 全局 npm 安装脚本。`--registry` 只作用这一条命令，不改容器全局 npm 配置。
     *
     * 尾部兜底：Debian 系 apt 版 npm 的全局 bin 目录（/usr/local/bin）不在容器 PATH 上，
     * 装完后若 `command -v` 仍找不到，就从 npm 全局前缀补一个软链到已在 PATH 的 `~/.aharou/bin`。
     */
    private fun buildNpmInstallScript(spec: ServerSpec): String? {
        val packages = spec.packageName?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (packages.isEmpty()) return null
        val cmd = spec.command
        return listOf(
            "set -e",
            "npm install -g --no-audit --no-fund --registry $NPM_REGISTRY ${packages.joinToString(" ")}",
            "if ! command -v $cmd >/dev/null 2>&1; then",
            "  prefix=\"\$(npm prefix -g 2>/dev/null || true)\"",
            "  if [ -n \"\$prefix\" ] && [ -x \"\$prefix/bin/$cmd\" ]; then",
            "    mkdir -p ~/.aharou/bin",
            "    ln -sf \"\$prefix/bin/$cmd\" ~/.aharou/bin/$cmd",
            "  fi",
            "fi"
        ).joinToString("\n")
    }

    /**
     * pip3 用户级安装脚本。
     *
     * 取舍：优先 `pip3 install --user`，落到 `~/.local/bin`——不动系统包、也不与容器自带 python 打架。
     * 但容器的 PATH 是引擎里固定的 `/root/.aharou/bin:/usr/bin:/bin:…`，无法在不改引擎常量的前提下把
     * `~/.local/bin` 加进去，故把入口软链到已在 PATH 的 `~/.aharou/bin`。
     * 包体走 [PYPI_MIRROR] 国内镜像，与 npm 那条一样只作用本条命令。
     * PEP 668 externally-managed 环境（Debian 系与 Alpine 都有）下 `--user` 同样会被拒，需加
     * `--break-system-packages`；这里按标记文件判断而不按发行版猜，末尾再兜底重试一次。
     * 始终带 `--user`，保证只装进用户目录、不碰系统包。
     */
    private fun buildPipInstallScript(spec: ServerSpec): String? {
        val pkg = spec.packageName?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cmd = spec.command
        return listOf(
            "set -e",
            "PEP_FLAG=\"\"",
            "if ls /usr/lib/python3*/EXTERNALLY-MANAGED >/dev/null 2>&1; then PEP_FLAG=\"--break-system-packages\"; fi",
            "pip3 install --user -i $PYPI_MIRROR \$PEP_FLAG $pkg || " +
                "pip3 install --user -i $PYPI_MIRROR --break-system-packages $pkg",
            "mkdir -p ~/.aharou/bin",
            "if [ -x \"\$HOME/.local/bin/$cmd\" ]; then",
            "  ln -sf \"\$HOME/.local/bin/$cmd\" ~/.aharou/bin/$cmd",
            "fi"
        ).joinToString("\n")
    }

    /**
     * 官方静态二进制的安装脚本：按容器架构挑包 → 下载（GitHub 直连在国内很慢，先走反代镜像、
     * 失败回退原链）→ 解压到 `~/.aharou/lsp/<command>`，再在已在 PATH 里的 `~/.aharou/bin`
     * 放个同名入口。资产名与入口相对路径全部来自 [ServerSpec]，Lua 的行为与参数化前完全一致。
     */
    private fun buildReleaseTarballScript(spec: ServerSpec): String? {
        val repo = spec.releaseRepo ?: return null
        val version = spec.releaseVersion ?: return null
        val entry = spec.releaseEntryRelPath ?: return null
        val pkg = spec.command
        val lines = mutableListOf(
            "set -e",
            "case \"\$(uname -m)\" in",
            "  aarch64|arm64) asset_arch=linux-arm64 ;;",
            "  x86_64|amd64)  asset_arch=linux-x64 ;;",
            "  armv7l|armhf)  asset_arch=linux-armhf ;;",
            "  *) echo \"不支持的容器架构：\$(uname -m)\"; exit 1 ;;",
            "esac",
            "case \"\$asset_arch\" in"
        )
        listOf("linux-arm64", "linux-x64", "linux-armhf").forEach { arch ->
            val asset = spec.releaseAssetFor?.invoke(arch) ?: "$pkg-$version-$arch.tar.gz"
            lines.add("  $arch) asset=\"$asset\" ;;")
        }
        lines += listOf(
            "esac",
            "origin=\"https://github.com/$repo/releases/download/$version/\$asset\"",
            "curl -fsSL -o /tmp/aharou-lsp.tgz \"$RELEASE_MIRROR\$origin\" || curl -fsSL -o /tmp/aharou-lsp.tgz \"\$origin\"",
            "mkdir -p ~/.aharou/lsp/$pkg",
            "tar -xzf /tmp/aharou-lsp.tgz -C ~/.aharou/lsp/$pkg",
            "mkdir -p ~/.aharou/bin",
            "printf '#!/bin/sh\\nexec ~/.aharou/lsp/$pkg/$entry \"\$@\"\\n' > ~/.aharou/bin/$pkg",
            "chmod +x ~/.aharou/bin/$pkg",
            "rm -f /tmp/aharou-lsp.tgz",
            "echo \"已安装 $pkg $version（\$asset_arch）\""
        )
        return lines.joinToString("\n")
    }

    /**
     * 探测已装版本：按安装方式问最可靠的来源，拿不到再退回 `--version` 输出里找 semver。
     * - APK：问包管理器（Alpine 的 apk / Debian 系的 dpkg），`apk` 的 `-rN` 打包后缀会去掉；
     * - NPM：`npm ls -g --depth=0 <包>` 的输出里取 `包@版本`；
     * - PIP：`pip3 show <包>` 的 `Version:` 行；
     * - RELEASE / COMPOSER：只有 `<命令> --version`。
     */
    private suspend fun detectVersion(extension: LanguageExtension): String? {
        val spec = extension.spec
        val viaManager = when (spec.installKind) {
            InstallKind.APK -> detectVersionViaPackageManager(spec)
            InstallKind.NPM -> detectVersionViaNpm(spec)
            InstallKind.PIP -> detectVersionViaPip(spec)
            InstallKind.RELEASE, InstallKind.COMPOSER -> null
        }
        if (viaManager != null) return viaManager
        val fallback = runIfReady("${spec.command} --version") ?: return null
        return VERSION_REGEX.find(fallback.output)?.value
    }

    private suspend fun detectVersionViaPackageManager(spec: ServerSpec): String? {
        val pkg = spec.packageName ?: return null
        for (query in listOf("apk info -v $pkg", "dpkg-query -W -f='\${Version}' $pkg")) {
            val result = runIfReady(query) ?: continue
            if (result.exitCode != 0) continue
            VERSION_REGEX.find(result.output)?.let { return it.value.replace(Regex("-r\\d+$"), "") }
        }
        return null
    }

    private suspend fun detectVersionViaNpm(spec: ServerSpec): String? {
        val pkg = spec.packageName?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val result = runIfReady("npm ls -g --depth=0 $pkg") ?: return null
        if (result.exitCode != 0) return null
        // 输出形如 "<path>\n└── typescript-language-server@4.3.3"
        return VERSION_REGEX.find(result.output.substringAfter("$pkg@", ""))?.value
    }

    private suspend fun detectVersionViaPip(spec: ServerSpec): String? {
        val pkg = spec.packageName?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val result = runIfReady("pip3 show $pkg") ?: return null
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .firstOrNull { it.startsWith("Version:") }
            ?.substringAfter("Version:")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 命令是否存在（容器未就绪返回 false）。 */
    private suspend fun hasCommand(name: String): Boolean {
        val result = runIfReady("command -v $name") ?: return false
        return result.exitCode == 0 && result.output.isNotBlank()
    }

    /** 仅在后端就绪时执行；未就绪返回 null（不触发容器初始化）。 */
    private suspend fun runIfReady(
        command: String,
        timeoutMs: Long = PROBE_TIMEOUT_MS
    ) = commandEngine.runCommandSyncIfReady(
        command = command,
        projectPath = null,
        timeoutMs = timeoutMs
    )

    private fun unavailable(): ExtensionStatus.Unavailable {
        val hint = commandEngine.notReadyHint()?.takeIf { it.isNotBlank() } ?: "容器未就绪"
        return ExtensionStatus.Unavailable(hint)
    }

    /** 输出尾部截断，前缀 `...` 提示被截。 */
    private fun tail(output: String): String =
        if (output.length <= MAX_OUTPUT_CHARS) output else "..." + output.takeLast(MAX_OUTPUT_CHARS)

    private fun readStatusRoot(): JsonObject = runCatching {
        if (fileAccess.isFile(STATUS_FILE)) {
            JSON.parseToJsonElement(fileAccess.readFile(STATUS_FILE)).jsonObject
        } else {
            JsonObject(emptyMap())
        }
    }.getOrElse {
        FileLogger.w(TAG, "读取 $STATUS_FILE 失败: ${it.message}")
        JsonObject(emptyMap())
    }

    /** 合并写入已装状态；任何失败只记日志，绝不抛给调用方。 */
    private fun persist(extension: LanguageExtension, status: ExtensionStatus) {
        if (status !is ExtensionStatus.Installed) return
        runCatching {
            val node = buildJsonObject {
                put("installed", true)
                put("version", status.version)
                put("at", System.currentTimeMillis())
            }
            val merged = JsonObject(readStatusRoot() + (extension.id to node))
            fileAccess.writeFile(STATUS_FILE, PRETTY_JSON.encodeToString(JsonObject.serializer(), merged))
        }.onFailure {
            FileLogger.w(TAG, "写入 $STATUS_FILE 失败: ${it.message}")
        }
    }
}
