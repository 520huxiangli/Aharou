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

/**
 * 编辑器支持的语言扩展（语言服务器）。新语言在此追加，并补全 [command]/[apkPackage]/[displayName]。
 */
enum class LanguageExtension {
    LUA;

    /** 状态文件里的键，也是扩展标识。 */
    val id: String get() = name.lowercase()

    /** 容器内可执行名，用于 `command -v` 与 `--version` 探活。 */
    val command: String
        get() = when (this) {
            LUA -> "lua-language-server"
        }

    /** Alpine（apk）包名。 */
    val apkPackage: String
        get() = when (this) {
            LUA -> "lua-language-server"
        }

    /** 展示名。 */
    val displayName: String
        get() = when (this) {
            LUA -> "Lua Language Server"
        }
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
 * 语言服务器跑在 Alpine 容器里，用 `apk add --no-cache <包名>` 安装；探活靠
 * `command -v <命令>` + `<命令> --version`。已装状态缓存到容器持久目录
 * `/root/.aharou/lsp-extensions.json`（宿主 filesDir/aharou，容器升级不丢），
 * 探活成功时顺带落盘；写盘失败只记日志、不抛给调用方。
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

        /** 安装命令超时：apk 拉包 + 解包，给足余量。 */
        private const val INSTALL_TIMEOUT_MS = 300_000L

        /** 返回给 UI 的输出上限（字符）。 */
        const val MAX_OUTPUT_CHARS = 1000

        /** 版本号解析失败时的占位。 */
        const val UNKNOWN_VERSION = "unknown"

        /**
         * 非包管理器路线（Debian/Ubuntu 等源里没有这个包）改用官方 release 的静态二进制，
         * 这里固定版本与镜像。官方包只有 glibc 构建，musl 系（Alpine）用不了，必须走包管理器。
         */
        private const val RELEASE_VERSION = "3.19.1"
        private const val RELEASE_MIRROR = "https://v6.gh-proxy.org/"
        private const val RELEASE_REPO = "LuaLS/lua-language-server"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }
        private val VERSION_REGEX = Regex("""\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.\-]+)?""")
    }

    /**
     * 探活：命令是否存在 + 版本。容器未就绪返回 [ExtensionStatus.Unavailable]。
     * 探活成功时把版本写入状态文件（失败只记日志）。
     */
    suspend fun probe(extension: LanguageExtension): ExtensionStatus = withContext(Dispatchers.IO) {
        val cmd = extension.command
        val which = runIfReady("command -v $cmd") ?: return@withContext unavailable()

        if (which.exitCode != 0 || which.output.isBlank()) return@withContext ExtensionStatus.NotInstalled

        // 版本优先问包管理器：Alpine 的 lua-language-server 只是个转发脚本，`--version` 会走到
        // LSP 启动逻辑、又因包内缺 changelog.md 而打成 `<Unknown>`（2026-10-05 实测），不能当依据。
        // 「装没装」仍以 command -v 为准（上面已判）。
        val status = ExtensionStatus.Installed(detectVersion(extension) ?: UNKNOWN_VERSION)
        persist(extension, status)
        status
    }

    /**
     * 探测已装版本：先问包管理器（Alpine 的 apk / Debian 系的 dpkg），拿不到再退回 `--version` 输出里
     * 找 semver。包管理器的记录才是可靠来源；`apk` 的 `-rN` 打包后缀会去掉。
     */
    private suspend fun detectVersion(extension: LanguageExtension): String? {
        val pkg = extension.apkPackage
        for (query in listOf("apk info -v $pkg", "dpkg-query -W -f='\${Version}' $pkg")) {
            val result = runIfReady(query) ?: continue
            if (result.exitCode != 0) continue
            VERSION_REGEX.find(result.output)?.let { return it.value.replace(Regex("-r\\d+$"), "") }
        }
        val fallback = runIfReady("${extension.command} --version") ?: return null
        return VERSION_REGEX.find(fallback.output)?.value
    }

    /**
     * 安装：容器自带的包管理器能装就装，装不到（如 Debian/Ubuntu 源里没这个包）就下官方静态二进制。
     * 容器未就绪时返回失败 + [ExtensionStatus.Unavailable]。成功后再探活一次，回读真实版本并落盘。
     */
    suspend fun install(extension: LanguageExtension): InstallResult = withContext(Dispatchers.IO) {
        val result = installCommand(extension)
            ?: return@withContext InstallResult(
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
     * 选安装路径，不按发行版枚举、只按容器实际有什么来判断：
     * 先试包管理器（apk / pacman / dnf / yum / zypper 的源里都有这个包），没装成再落官方二进制。
     *
     * Alpine（musl）没有可用的官方二进制，所以包管理器失败就直接返回，不再白跑一轮。
     * apt 系故意不试：Ubuntu/Debian 源里没有 lua-language-server 这个包，试了只是白等。
     */
    private suspend fun installCommand(extension: LanguageExtension) = run {
        val pmCommand = packageManagerCommand(extension)
        if (pmCommand != null) {
            val pmResult = runIfReady(pmCommand, INSTALL_TIMEOUT_MS)
            if (pmResult != null && pmResult.exitCode == 0) return@run pmResult
            if (hasCommand("apk")) return@run pmResult
            FileLogger.w(TAG, "包管理器安装失败（exit=${pmResult?.exitCode}），改用官方二进制")
        }
        runIfReady(buildReleaseTarballScript(extension), INSTALL_TIMEOUT_MS)
    }

    /** 容器里认识的包管理器给出的安装命令；都不认识返回 null（交给二进制路线）。 */
    private suspend fun packageManagerCommand(extension: LanguageExtension): String? {
        val pkg = extension.apkPackage
        return when {
            hasCommand("apk") -> "apk add --no-cache $pkg"
            hasCommand("pacman") -> "pacman -Sy --noconfirm $pkg"
            hasCommand("dnf") -> "dnf install -y $pkg"
            hasCommand("yum") -> "yum install -y $pkg"
            hasCommand("zypper") -> "zypper -n install $pkg"
            else -> null
        }
    }

    /** 命令是否存在（容器未就绪返回 false）。 */
    private suspend fun hasCommand(name: String): Boolean {
        val result = runIfReady("command -v $name") ?: return false
        return result.exitCode == 0 && result.output.isNotBlank()
    }

    /**
     * 官方静态二进制的安装脚本：按容器架构挑包 → 下载（GitHub 直连在国内很慢，先走反代镜像、
     * 失败回退原链）→ 解压到 `~/.aharou/lsp/<名字>`，再在已在 PATH 里的 `~/.aharou/bin` 放个同名入口。
     */
    private fun buildReleaseTarballScript(extension: LanguageExtension): String {
        val pkg = extension.command
        val ver = RELEASE_VERSION
        return """
            set -e
            case "$(uname -m)" in
              aarch64|arm64) asset_arch=linux-arm64 ;;
              x86_64|amd64)  asset_arch=linux-x64 ;;
              armv7l|armhf)  asset_arch=linux-armhf ;;
              *) echo "不支持的容器架构：$(uname -m)"; exit 1 ;;
            esac
            asset="$pkg-$ver-${'$'}asset_arch.tar.gz"
            origin="https://github.com/$RELEASE_REPO/releases/download/$ver/${'$'}asset"
            curl -fsSL -o /tmp/aharou-lsp.tgz "$RELEASE_MIRROR${'$'}origin" || curl -fsSL -o /tmp/aharou-lsp.tgz "${'$'}origin"
            mkdir -p ~/.aharou/lsp/$pkg
            tar -xzf /tmp/aharou-lsp.tgz -C ~/.aharou/lsp/$pkg
            mkdir -p ~/.aharou/bin
            printf '#!/bin/sh\nexec ~/.aharou/lsp/$pkg/bin/$pkg "${'$'}@"\n' > ~/.aharou/bin/$pkg
            chmod +x ~/.aharou/bin/$pkg
            rm -f /tmp/aharou-lsp.tgz
            echo "已安装 $pkg $ver（${'$'}asset_arch）"
        """.trimIndent()
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
