package com.aharou.feature.settings.presentation

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ContainerSources"

/** 容器软件源：发行版自带源，或自动探测出来的国内镜像。 */
enum class ContainerMirrorMode {
    OFFICIAL,
    CHINA
}

/** 容器 DNS：跟随手机当前网络，或两套固定公共 DNS。 */
enum class ContainerDnsMode {
    SYSTEM,
    CLOUDFLARE_GOOGLE,
    ALI_DNSPOD
}

/** 正在执行的动作。UI 据此只在被点的那一行转圈，而不是整页禁用。 */
sealed interface ContainerSourcesBusy {
    data class Mirror(val mode: ContainerMirrorMode) : ContainerSourcesBusy
    data class Dns(val mode: ContainerDnsMode) : ContainerSourcesBusy

    /** 打开分区时的静默重放（容器重建后对齐配置）。 */
    data object Replay : ContainerSourcesBusy
}

/**
 * 「镜像与 DNS」分区状态。
 *
 * [mirror] / [dns] 只反映**已经成功写进容器**的选择：切换失败时保持原值，避免界面显示「国内镜像」
 * 而容器里还是官方源。持久化的一份仅用于容器重建后重放。
 */
data class ContainerSourcesUiState(
    val mirror: ContainerMirrorMode = ContainerMirrorMode.OFFICIAL,
    /** 上次实际选中的镜像站主机名，仅用于副标题展示（为空说明还没换过或已切回官方）。 */
    val mirrorHost: String = "",
    val dns: ContainerDnsMode = ContainerDnsMode.SYSTEM,
    val busy: ContainerSourcesBusy? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val loaded: Boolean = false
)

/** 持久化的选择。容器重建后按这份重放。 */
data class ContainerSourcesSettings(
    val mirror: ContainerMirrorMode = ContainerMirrorMode.OFFICIAL,
    val mirrorHost: String = "",
    val dns: ContainerDnsMode = ContainerDnsMode.SYSTEM
)

private val Context.containerSourcesDataStore by preferencesDataStore(
    name = "container_sources_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 「镜像与 DNS」选择的持久化。
 *
 * 单独一个 store：这份设置决定容器内被改写成什么，和容器列表、执行模式互不牵连，
 * 重建容器时不能被一起清掉。
 */
@Singleton
class ContainerSourcesRepository @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val dataStore = context.containerSourcesDataStore
    private val mirrorKey = stringPreferencesKey("mirror_mode")
    private val mirrorHostKey = stringPreferencesKey("mirror_host")
    private val dnsKey = stringPreferencesKey("dns_mode")

    /** 读失败一直重试：流一旦结束，收它的 UI 就再也拿不到值（同 VoiceWakeSettingsRepository）。 */
    val settingsFlow: Flow<ContainerSourcesSettings> = dataStore.data
        .map { prefs ->
            ContainerSourcesSettings(
                mirror = prefs[mirrorKey].toMirrorMode(),
                mirrorHost = prefs[mirrorHostKey].orEmpty(),
                dns = prefs[dnsKey].toDnsMode()
            )
        }
        .retryWhen { cause, attempt ->
            FileLogger.w(TAG, "容器源设置读取失败（第 ${attempt + 1} 次），重试", cause)
            delay(if (attempt < 3) 500 else 2000)
            true
        }

    suspend fun read(): ContainerSourcesSettings = settingsFlow.first()

    suspend fun save(settings: ContainerSourcesSettings) {
        dataStore.edit { prefs ->
            prefs[mirrorKey] = settings.mirror.name
            prefs[mirrorHostKey] = settings.mirrorHost
            prefs[dnsKey] = settings.dns.name
        }
    }

    private fun String?.toMirrorMode(): ContainerMirrorMode =
        ContainerMirrorMode.entries.firstOrNull { it.name == this } ?: ContainerMirrorMode.OFFICIAL

    private fun String?.toDnsMode(): ContainerDnsMode =
        ContainerDnsMode.entries.firstOrNull { it.name == this } ?: ContainerDnsMode.SYSTEM
}

/** 一次容器内改写的结局。 */
sealed interface ApplyResult {
    /** [detail] 对换源是实际选中的镜像站；[warning] 表示改动已落盘但校验没通过。 */
    data class Ok(val detail: String = "", val warning: String? = null) : ApplyResult
    data class Failure(val message: String, val notReady: Boolean = false) : ApplyResult
}

/** 重放结局。[None] 表示容器内已经一致，不需要动。 */
sealed interface ReplayOutcome {
    data object None : ReplayOutcome
    data class Applied(val mirrorHost: String) : ReplayOutcome
    data class Failed(val message: String) : ReplayOutcome
}

private enum class SourcesStep { MIRROR, OFFICIAL, DNS }

/**
 * 真正的容器内改动：换源复用 env-common.sh，DNS 写 /etc/resolv.conf，并把「当前已应用的选择」
 * 记进容器自己的 /etc 里。
 *
 * 单独抽成 [Singleton] 而不是塞进 ViewModel：容器重置/换镜像发生在设置页之外，将来 App 启动时
 * 想主动重放，注入这个类即可，不必依赖设置页被打开。
 */
@Singleton
class ContainerSourcesApplier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: LinuxContainerEngine
) {
    private companion object {
        // 两个标记文件分开写：换源和改 DNS 是两次独立动作，各写各的才不会把「没实际做过的那半」也记成已完成。
        const val MIRROR_MARKER = "/etc/aharou/container-mirror.state"
        const val DNS_MARKER = "/etc/aharou/container-dns.state"

        /** 换源要跑 apt-get update / dnf makecache，慢网络下几十秒很正常。 */
        const val MIRROR_TIMEOUT_MS = 180_000L

        /** 官方源分支：不探测镜像，只改配置 + 一次带 timeout 的 update。 */
        const val OFFICIAL_MIRROR_TIMEOUT_MS = 90_000L
        const val QUICK_TIMEOUT_MS = 30_000L

        /** resolv.conf 的实现只认前 3 个 nameserver，多写的会被忽略。 */
        const val MAX_DNS_SERVERS = 3

        val CLOUDFLARE_GOOGLE_DNS = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8")
        val ALI_DNSPOD_DNS = listOf("223.5.5.5", "223.6.6.6", "119.29.29.29")

        /** 读不到系统 DNS 时的兜底，与 ContainerInstaller 写进新容器的默认值一致。 */
        val FALLBACK_SYSTEM_DNS = listOf("223.5.5.5", "223.6.6.6")

        val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }

    /**
     * 宿主当前网络的 DNS。只取 IPv4：手机蜂窝/VPN 常见「能配 IPv6 但出不去」，
     * 写进 resolv.conf 会让容器里的 apt/pip 先等 v6 超时（env-common 强制 apt 走 IPv4 同因）。
     */
    fun systemDnsServers(): List<String> = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val props = network?.let { manager.getLinkProperties(it) }
        props?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.filter { IPV4_REGEX.matches(it) }
            ?.distinct()
            ?.take(MAX_DNS_SERVERS)
            .orEmpty()
    } catch (e: Exception) {
        FileLogger.w(TAG, "读取系统 DNS 失败", e)
        emptyList()
    }

    suspend fun isContainerReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching { engine.isContainerInstalled() }.getOrDefault(false)
    }

    /** 换源。[mode] 为 [ContainerMirrorMode.CHINA] 时由 env-common.sh 逐个探测镜像站。 */
    suspend fun applyMirror(mode: ContainerMirrorMode): ApplyResult {
        val script = when (mode) {
            ContainerMirrorMode.OFFICIAL -> OFFICIAL_MIRROR_SCRIPT
            ContainerMirrorMode.CHINA -> CHINA_MIRROR_SCRIPT
        }
        val step = if (mode == ContainerMirrorMode.CHINA) SourcesStep.MIRROR else SourcesStep.OFFICIAL
        // 官方源只是改文件 + 一次带 timeout 的 update，不需要给足探测镜像的 3 分钟；
        // 窗口太长时用户只能看着转圈，分档后最坏 90 秒内一定有结果。
        val timeout = if (mode == ContainerMirrorMode.CHINA) MIRROR_TIMEOUT_MS else OFFICIAL_MIRROR_TIMEOUT_MS
        val result = run(script, timeout) ?: return notReady()
        val markers = markers(result.output)
        markers["ERROR"]?.let { return ApplyResult.Failure(errorText(it, step, result)) }
        if (result.exitCode != 0) return ApplyResult.Failure(errorText(null, step, result))

        val warning = if (markers["VERIFY"] == "failed") {
            context.getString(R.string.container_sources_verify_failed, tailDetail(result.output))
        } else {
            null
        }
        val host = if (mode == ContainerMirrorMode.CHINA) markers["MIRROR"].orEmpty() else ""
        FileLogger.i(TAG, "换源完成：mode=$mode host=$host verify=${markers["VERIFY"]}")
        return ApplyResult.Ok(host, warning)
    }

    suspend fun applyDns(mode: ContainerDnsMode): ApplyResult {
        val servers = dnsServersFor(mode)
        val result = run(dnsScript(servers), QUICK_TIMEOUT_MS) ?: return notReady()
        if (result.exitCode != 0) return ApplyResult.Failure(errorText(null, SourcesStep.DNS, result))

        // 只认退出码不够：Ubuntu/Debian base 的 /etc/resolv.conf 常是指向 systemd-resolved 的坏链，
        // 重定向写入可能「成功」却落不到实体文件上，必须把读回的内容与原样对比。
        val written = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("nameserver ") }
            .map { it.removePrefix("nameserver ").trim() }
            .toList()
        if (written != servers) {
            return ApplyResult.Failure(
                context.getString(
                    R.string.container_sources_error_dns_verify,
                    written.joinToString(", ").ifBlank { "-" }
                )
            )
        }
        FileLogger.i(TAG, "DNS 已写入：${servers.joinToString()}")
        return ApplyResult.Ok()
    }

    /** 读容器里的应用标记；null 表示容器未就绪（不可读）。 */
    suspend fun readMarker(): Map<String, String>? {
        val result = run(sh("cat $MIRROR_MARKER $DNS_MARKER 2>/dev/null; true"), QUICK_TIMEOUT_MS) ?: return null
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** 把镜像源记为「已在容器内生效」。写失败只告警，不影响已完成的改动。 */
    suspend fun markMirror(settings: ContainerSourcesSettings): Boolean =
        writeMarker(MIRROR_MARKER, "mirror", mirrorSignal(settings))

    /** 把 DNS 记为「已在容器内生效」。 */
    suspend fun markDns(settings: ContainerSourcesSettings): Boolean =
        writeMarker(DNS_MARKER, "dns", dnsSignal(settings))

    private suspend fun writeMarker(path: String, key: String, value: String): Boolean {
        val script = sh(
            """
            mkdir -p /etc/aharou
            printf '%s\n' '$key=$value' > $path
            """.trimIndent()
        )
        val result = run(script, QUICK_TIMEOUT_MS) ?: return false
        val ok = result.exitCode == 0
        if (!ok) FileLogger.w(TAG, "写应用标记失败：${tailDetail(result.output)}")
        return ok
    }

    /**
     * 容器重建后对齐配置。
     *
     * 判断依据是容器内标记而不是宿主侧记录：标记随 rootfs 一起被换掉，正好是「这个容器还是新的」的
     * 真相。可选容器刚建好时 /etc 本来就是官方源，不必为「保持官方」再重写一遍（省掉一次 apt update）。
     */
    suspend fun replayIfNeeded(settings: ContainerSourcesSettings): ReplayOutcome {
        val marker = readMarker() ?: return ReplayOutcome.None
        val desiredMirror = mirrorSignal(settings)
        val desiredDns = dnsSignal(settings)
        val mirrorDirty = marker["mirror"] != desiredMirror &&
            !(settings.mirror == ContainerMirrorMode.OFFICIAL && marker["mirror"].isNullOrBlank())
        val dnsDirty = marker["dns"] != desiredDns
        if (!mirrorDirty && !dnsDirty) return ReplayOutcome.None

        var host = settings.mirrorHost
        if (mirrorDirty) {
            when (val applied = applyMirror(settings.mirror)) {
                is ApplyResult.Failure -> return ReplayOutcome.Failed(applied.message)
                is ApplyResult.Ok -> host = applied.detail
            }
            markMirror(settings.copy(mirrorHost = host))
        }
        if (dnsDirty) {
            when (val applied = applyDns(settings.dns)) {
                is ApplyResult.Failure -> return ReplayOutcome.Failed(applied.message)
                is ApplyResult.Ok -> Unit
            }
            markDns(settings)
        }
        FileLogger.i(TAG, "已按保存的选择重放容器源配置：mirror=${settings.mirror} dns=${settings.dns}")
        return ReplayOutcome.Applied(host)
    }

    private fun dnsServersFor(mode: ContainerDnsMode): List<String> = when (mode) {
        ContainerDnsMode.SYSTEM -> systemDnsServers().ifEmpty { FALLBACK_SYSTEM_DNS }
        ContainerDnsMode.CLOUDFLARE_GOOGLE -> CLOUDFLARE_GOOGLE_DNS
        ContainerDnsMode.ALI_DNSPOD -> ALI_DNSPOD_DNS
    }

    /** 标记里的源指纹（只存值，键名由 writeMarker 拼）。系统 DNS 的具体地址也写进去：换网络后打开设置页会自动跟上。 */
    private fun mirrorSignal(settings: ContainerSourcesSettings): String =
        when (settings.mirror) {
            ContainerMirrorMode.OFFICIAL -> "official"
            ContainerMirrorMode.CHINA -> "china:${settings.mirrorHost.ifBlank { "auto" }}"
        }

    private fun dnsSignal(settings: ContainerSourcesSettings): String =
        when (settings.dns) {
            ContainerDnsMode.CLOUDFLARE_GOOGLE -> "cloudflare_google"
            ContainerDnsMode.ALI_DNSPOD -> "ali_dnspod"
            ContainerDnsMode.SYSTEM -> "system:${systemDnsServers().ifEmpty { FALLBACK_SYSTEM_DNS }.joinToString(",")}"
        }

    private fun dnsScript(servers: List<String>): String {
        val args = servers.joinToString(" ") { "'$it'" }
        return sh(
            """
            rm -f /etc/resolv.conf
            printf 'nameserver %s\n' $args > /etc/resolv.conf
            cat /etc/resolv.conf
            """.trimIndent()
        )
    }

    /** 只在容器已安装时执行；未安装返回 null，由调用方报「请先去终端页初始化」。 */
    private suspend fun run(script: String, timeoutMs: Long): CommandResult? =
        runCatching { engine.runCommandSyncIfReady(script, projectPath = null, timeoutMs = timeoutMs) }
            .onFailure { FileLogger.e(TAG, "容器命令执行异常", it) }
            .getOrNull()

    private fun notReady() =
        ApplyResult.Failure(context.getString(R.string.container_not_ready_hint), notReady = true)

    private fun markers(output: String): Map<String, String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("AHAROU_") && '=' in it }
            .associate { it.substring(7, it.indexOf('=')) to it.substring(it.indexOf('=') + 1) }

    /** 失败时给用户看的那半句：脚本自己的中文提示优先，否则取输出末尾几行。 */
    private fun tailDetail(output: String): String =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("AHAROU_") && !it.startsWith("[命令执行超时") }
            .toList()
            .takeLast(3)
            .joinToString(" / ")
            .take(300)

    private fun errorText(code: String?, step: SourcesStep, result: CommandResult): String {
        val detail = tailDetail(result.output)
        return when (code) {
            "no_env_lib" -> context.getString(R.string.container_sources_error_no_env_lib)
            "no_pm" -> context.getString(R.string.container_sources_error_no_pm)
            "unsupported_pm" -> context.getString(R.string.container_sources_error_unsupported_pm)
            "no_codename" -> context.getString(R.string.container_sources_error_no_codename)
            "unsupported_distro" -> context.getString(R.string.container_sources_error_unsupported_distro)
            else -> {
                if (result.exitCode == null) {
                    context.getString(R.string.container_sources_error_timeout)
                } else {
                    val template = when (step) {
                        SourcesStep.MIRROR -> R.string.container_sources_error_mirror
                        SourcesStep.OFFICIAL -> R.string.container_sources_error_official
                        SourcesStep.DNS -> R.string.container_sources_error_dns
                    }
                    context.getString(template, detail.ifBlank { "exit ${result.exitCode}" })
                }
            }
        }
    }

    /**
     * 切国内镜像：直接复用容器里的 env-common.sh（provision.sh 与 `aicode` 命令用的同一套），
     * 选了哪个站由它按当前网络探测，App 侧不重复实现镜像列表。
     */
    private val CHINA_MIRROR_SCRIPT = sh(
        """
        LIB=
        if [ -f /root/.aharou/lib/env-common.sh ]; then LIB=/root/.aharou/lib/env-common.sh; fi
        if [ -z "§LIB" ] && [ -f /root/.aicode/lib/env-common.sh ]; then LIB=/root/.aicode/lib/env-common.sh; fi
        if [ -z "§LIB" ]; then echo "AHAROU_ERROR=no_env_lib"; exit 10; fi
        . "§LIB" >/dev/null 2>&1
        detect_pmgr
        echo "AHAROU_PMGR=§{PMGR:-none}"
        if [ -z "§{PMGR:-}" ]; then echo "AHAROU_ERROR=no_pm"; exit 11; fi
        if [ "§PMGR" = yum ]; then echo "AHAROU_ERROR=unsupported_pm"; exit 13; fi
        if setup_mirror; then
            echo "AHAROU_MIRROR=§{MIRROR:-}"
            if [ "§PMGR" = apk ]; then
                if timeout 30 apk update >/dev/null 2>&1; then echo "AHAROU_VERIFY=ok"; else echo "AHAROU_VERIFY=failed"; fi
            fi
            exit 0
        fi
        echo "AHAROU_ERROR=setup_failed"
        exit 12
        """.trimIndent()
    )

    /**
     * 恢复发行版官方源。
     *
     * env-common.sh 只做「换成国内源」，没留反操作；换源时它的备份目录也会在成功后删掉，靠备份回滚
     * 不可行。这里按包管理器重写官方配置，并跑一次索引刷新确认真的能用。
     *
     * URL 都拼 `%s://` 而不是写字面量：LinuxContainerEngine 会扫命令里的 `http(s)://域名` 注入
     * /etc/hosts 兜底解析，镜像站/CDN 的 IP 是轮换的，钉死反而会把源弄挂。
     */
    private val OFFICIAL_MIRROR_SCRIPT = sh(
        """
        if command -v apk >/dev/null 2>&1; then PMGR=apk
        elif command -v apt-get >/dev/null 2>&1; then PMGR=apt
        elif command -v dnf >/dev/null 2>&1; then PMGR=dnf
        elif command -v yum >/dev/null 2>&1; then PMGR=yum
        elif command -v pacman >/dev/null 2>&1; then PMGR=pacman
        else PMGR=
        fi
        echo "AHAROU_PMGR=§{PMGR:-none}"
        S=http
        case "§PMGR" in
            apk)
                branch=
                if [ -f /etc/apk/repositories ]; then
                    branch=${'$'}(sed -n 's#.*alpine/\([^/]*\)/.*#\1#p' /etc/apk/repositories 2>/dev/null | head -1)
                fi
                if [ -z "§branch" ] && [ -f /etc/os-release ]; then
                    . /etc/os-release 2>/dev/null
                    if [ "§{ID:-}" = alpine ] && [ -n "§{VERSION_ID:-}" ]; then
                        branch=v${'$'}(echo "§{VERSION_ID:-}" | cut -d. -f1-2)
                    fi
                fi
                if [ -z "§branch" ]; then branch=v3.24; fi
                mkdir -p /etc/apk
                printf '%s://dl-cdn.alpinelinux.org/alpine/%s/main\n%s://dl-cdn.alpinelinux.org/alpine/%s/community\n' "§S" "§branch" "§S" "§branch" > /etc/apk/repositories
                echo "AHAROU_MIRROR=official"
                if timeout 45 apk update; then echo "AHAROU_VERIFY=ok"; else echo "AHAROU_VERIFY=failed"; fi
                exit 0
                ;;
            apt)
                . /etc/os-release 2>/dev/null
                codename="§{VERSION_CODENAME:-}"
                if [ -z "§codename" ]; then echo "AHAROU_ERROR=no_codename"; exit 14; fi
                arch=${'$'}(dpkg --print-architecture 2>/dev/null || uname -m)
                security_uri=
                keyring=
                case "§{ID:-}" in
                    ubuntu)
                        case "§arch" in
                            amd64|i386) uri="§S://archive.ubuntu.com/ubuntu/" ;;
                            *) uri="§S://ports.ubuntu.com/ubuntu-ports/" ;;
                        esac
                        suites="§codename §codename-updates §codename-backports §codename-security"
                        components="main restricted universe multiverse"
                        keyring=/usr/share/keyrings/ubuntu-archive-keyring.gpg
                        ;;
                    debian)
                        uri="§S://deb.debian.org/debian/"
                        suites="§codename §codename-updates §codename-backports"
                        components="main contrib non-free non-free-firmware"
                        security_uri="§S://security.debian.org/debian-security/"
                        keyring=/usr/share/keyrings/debian-archive-keyring.gpg
                        ;;
                    *) echo "AHAROU_ERROR=unsupported_distro"; exit 15 ;;
                esac
                signed_by=
                if [ -n "§keyring" ] && [ -f "§keyring" ]; then signed_by="Signed-By: §keyring"; fi
                mkdir -p /etc/apt/sources.list.d
                rm -f /etc/apt/sources.list
                rm -f /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list.d/ubuntu.sources
                rm -f /etc/apt/sources.list.d/aicode-mirror.sources /etc/apt/sources.list.d/aicode-mirror-security.sources
                {
                    echo "Types: deb"
                    echo "URIs: §uri"
                    echo "Suites: §suites"
                    echo "Components: §components"
                    if [ -n "§signed_by" ]; then echo "§signed_by"; fi
                } > /etc/apt/sources.list.d/aicode-official.sources
                if [ -n "§security_uri" ]; then
                    {
                        echo "Types: deb"
                        echo "URIs: §security_uri"
                        echo "Suites: §codename-security"
                        echo "Components: §components"
                        if [ -n "§signed_by" ]; then echo "§signed_by"; fi
                    } > /etc/apt/sources.list.d/aicode-official-security.sources
                else
                    rm -f /etc/apt/sources.list.d/aicode-official-security.sources
                fi
                echo "AHAROU_MIRROR=official"
                if timeout 90 apt-get update -y; then echo "AHAROU_VERIFY=ok"; else echo "AHAROU_VERIFY=failed"; fi
                exit 0
                ;;
            dnf)
                mkdir -p /etc/yum.repos.d
                rm -f /etc/yum.repos.d/fedora.repo /etc/yum.repos.d/fedora-updates.repo
                printf '[fedora]\nname=Fedora ${'$'}releasever - ${'$'}basearch\nmetalink=%s://mirrors.fedoraproject.org/metalink?repo=fedora-${'$'}releasever&arch=${'$'}basearch\nenabled=1\ngpgcheck=1\n' "https" > /etc/yum.repos.d/fedora.repo
                printf '[updates]\nname=Fedora ${'$'}releasever - ${'$'}basearch - Updates\nmetalink=%s://mirrors.fedoraproject.org/metalink?repo=updates-released-f${'$'}releasever&arch=${'$'}basearch\nenabled=1\ngpgcheck=1\n' "https" > /etc/yum.repos.d/fedora-updates.repo
                echo "AHAROU_MIRROR=official"
                if timeout 90 dnf makecache -y; then echo "AHAROU_VERIFY=ok"; else echo "AHAROU_VERIFY=failed"; fi
                exit 0
                ;;
            pacman)
                mkdir -p /etc/pacman.d
                printf 'Server = %s://geo.mirror.pkgbuild.com/${'$'}repo/os/${'$'}arch\n' "https" > /etc/pacman.d/mirrorlist
                echo "AHAROU_MIRROR=official"
                if timeout 90 pacman -Sy --noconfirm; then echo "AHAROU_VERIFY=ok"; else echo "AHAROU_VERIFY=failed"; fi
                exit 0
                ;;
            yum) echo "AHAROU_ERROR=unsupported_pm"; exit 13 ;;
            *) echo "AHAROU_ERROR=no_pm"; exit 11 ;;
        esac
        """.trimIndent()
    )
}

/**
 * 设置页「镜像与 DNS」分区的状态与操作入口。
 *
 * 改写都落在容器内（源配置 + /etc/resolv.conf），立即生效、不用重装容器；选择另存一份在宿主，
 * 容器重建后由 [ContainerSourcesApplier.replayIfNeeded] 重新写回去。
 */
@HiltViewModel
class ContainerSourcesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ContainerSourcesRepository,
    private val applier: ContainerSourcesApplier
) : ViewModel() {

    private val _state = MutableStateFlow(ContainerSourcesUiState())
    val state: StateFlow<ContainerSourcesUiState> = _state.asStateFlow()

    /** 串行化所有容器改写：两个动作并发跑会互相覆盖源配置与 DNS。 */
    private val mutex = Mutex()

    init {
        viewModelScope.launch {
            val saved = runCatching { repository.read() }.getOrDefault(ContainerSourcesSettings())
            _state.update {
                it.copy(
                    mirror = saved.mirror,
                    mirrorHost = saved.mirrorHost,
                    dns = saved.dns,
                    loaded = true
                )
            }
        }
    }

    fun selectMirror(mode: ContainerMirrorMode) {
        val snapshot = _state.value
        if (!snapshot.loaded || snapshot.busy != null || snapshot.mirror == mode) return
        _state.update {
            it.copy(busy = ContainerSourcesBusy.Mirror(mode), message = null, messageIsError = false)
        }
        viewModelScope.launch {
            mutex.withLock {
                // 先落盘再应用：容器还没初始化时写不进去，但选择必须记住——首次解压 rootfs 时
                // ContainerInstaller 会读它直接写源，否则「先选源再初始化」永远是个死循环。
                val saved = ContainerSourcesSettings(mode, _state.value.mirrorHost, _state.value.dns)
                runCatching { repository.save(saved) }
                    .onFailure { FileLogger.w(TAG, "保存镜像源选择失败", it) }
                when (val applied = applier.applyMirror(mode)) {
                    is ApplyResult.Failure ->
                        if (applied.notReady) finishSavedPending(saved) else finishError(applied.message)
                    is ApplyResult.Ok -> {
                        val updated = ContainerSourcesSettings(mode, applied.detail, _state.value.dns)
                        runCatching { repository.save(updated) }
                            .onFailure { FileLogger.w(TAG, "保存镜像源选择失败", it) }
                        applier.markMirror(updated)
                        val message = if (mode == ContainerMirrorMode.OFFICIAL) {
                            context.getString(R.string.container_sources_applied_mirror_official)
                        } else {
                            // 探测不出具体站点时 setup_mirror 实际也不会成功，这里只防文案错位。
                            context.getString(R.string.container_sources_applied_mirror, applied.detail.ifBlank { "-" })
                        }
                        finishOk(updated, message, applied.warning)
                    }
                }
            }
        }
    }

    fun selectDns(mode: ContainerDnsMode) {
        val snapshot = _state.value
        if (!snapshot.loaded || snapshot.busy != null || snapshot.dns == mode) return
        _state.update {
            it.copy(busy = ContainerSourcesBusy.Dns(mode), message = null, messageIsError = false)
        }
        viewModelScope.launch {
            mutex.withLock {
                val saved = ContainerSourcesSettings(
                    mirror = _state.value.mirror,
                    mirrorHost = _state.value.mirrorHost,
                    dns = mode
                )
                runCatching { repository.save(saved) }
                    .onFailure { FileLogger.w(TAG, "保存 DNS 选择失败", it) }
                when (val applied = applier.applyDns(mode)) {
                    is ApplyResult.Failure ->
                        if (applied.notReady) finishSavedPending(saved) else finishError(applied.message)
                    is ApplyResult.Ok -> {
                        val updated = ContainerSourcesSettings(
                            mirror = _state.value.mirror,
                            mirrorHost = _state.value.mirrorHost,
                            dns = mode
                        )
                        runCatching { repository.save(updated) }
                            .onFailure { FileLogger.w(TAG, "保存 DNS 选择失败", it) }
                        applier.markDns(updated)
                        finishOk(updated, context.getString(R.string.container_sources_applied_dns), applied.warning)
                    }
                }
            }
        }
    }

    /**
     * 容器重建（重置/换镜像）后把保存的选择重新写回容器。每次进入本分区时调一次。
     *
     * 不能只放在 init 里：设置页是单个路由，ViewModel 活到离开设置为止，用户在容器页重置容器后
     * 再切回来重新组合的是同一个 ViewModel，init 不会再跑。
     */
    fun replayIfNeeded() {
        viewModelScope.launch {
            mutex.withLock {
                val snapshot = _state.value
                if (!snapshot.loaded) return@withLock
                // 容器没建好就不打扰用户：这里不是切换动作，没必要弹「请先初始化终端」。
                if (!applier.isContainerReady()) return@withLock
                val settings = ContainerSourcesSettings(snapshot.mirror, snapshot.mirrorHost, snapshot.dns)
                _state.update { it.copy(busy = ContainerSourcesBusy.Replay) }
                when (val outcome = applier.replayIfNeeded(settings)) {
                    ReplayOutcome.None -> _state.update { it.copy(busy = null) }
                    is ReplayOutcome.Applied -> {
                        val updated = settings.copy(mirrorHost = outcome.mirrorHost)
                        runCatching { repository.save(updated) }
                        _state.update { it.copy(busy = null, mirrorHost = outcome.mirrorHost) }
                    }

                    is ReplayOutcome.Failed -> _state.update {
                        it.copy(
                            busy = null,
                            message = context.getString(R.string.container_sources_replay_failed, outcome.message),
                            messageIsError = true
                        )
                    }
                }
            }
        }
    }

    private fun finishOk(settings: ContainerSourcesSettings, message: String, warning: String?) {
        _state.update {
            it.copy(
                mirror = settings.mirror,
                mirrorHost = settings.mirrorHost,
                dns = settings.dns,
                busy = null,
                message = warning ?: message,
                messageIsError = warning != null
            )
        }
    }

    private fun finishError(message: String) {
        _state.update { it.copy(busy = null, message = message, messageIsError = true) }
    }

    /**
     * 容器还没初始化：选择已经保存，只是暂时没地方应用。
     *
     * 不当错误处理——首次装包时本来就不存在可写的容器，这里报红只会让用户以为设置没生效，
     * 而他其实已经可以进终端了（安装时会按这份选择写源）。
     */
    private fun finishSavedPending(settings: ContainerSourcesSettings) {
        _state.update {
            it.copy(
                mirror = settings.mirror,
                mirrorHost = settings.mirrorHost,
                dns = settings.dns,
                busy = null,
                message = context.getString(R.string.container_sources_saved_pending_init),
                messageIsError = false,
            )
        }
    }
}

/**
 * Kotlin raw string 里的 `$` 必须写成 `${'$'}`，满屏转义会把 shell 逻辑淹掉；
 * 脚本里统一用 `§` 表示 `$`，[sh] 一次替换回真身。
 */
private fun sh(script: String): String = script.replace('§', '$')
