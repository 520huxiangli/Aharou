package com.aharou.feature.settings.data.repository

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerProfile
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ProotSeccompPolicy"
private const val PROOT_NO_SECCOMP = "PROOT_NO_SECCOMP"
private const val SECCOMP_EPERM = "Operation not permitted"

/**
 * 失败输出里出现这些词才算「seccomp 相关」：只凭 EPERM 会把磁盘满、权限不足这类无关失败误判成
 * seccomp 问题，进而白白把整个容器切到更慢的全量 ptrace 模式。
 */
private val SECCOMP_HINT = Regex("(execve|execvp|seccomp|ptrace)", RegexOption.IGNORE_CASE)

/**
 * PRoot seccomp 兼容策略：决定拼给 proot 的环境里要不要加 `PROOT_NO_SECCOMP=1`。
 *
 * 为什么需要：陌生 ROM 上 proot 的 seccomp 加速可能翻译不了客户机 execve（`execve: Operation not
 * permitted`），容器里什么都跑不起来；此时强制全量 ptrace 是唯一逃生口。但该变量在本设备触发过
 * `ptrace(PEEKDATA) I/O error`，绝不能默认加——所以 AUTO 只在「真实目录操作探针失败、且失败特征
 * 确实是 seccomp 相关」时才切，无关失败（cd 失败、磁盘满、超时）一律保持默认。
 *
 * 结论按「profile + 发行版 + 系统指纹」缓存，换 ROM / OTA 后自动失效；并发启动由 [probeMutex] +
 * [inFlight] 保证同一个容器只探一次，已有结论直接复用。
 */
@Singleton
class ProotSeccompPolicy @Inject constructor(
    private val settings: ContainerSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前模式：由 [ContainerSettingsRepository.prootSeccompModeFlow] 持续同步。
     * 拼环境时是同步调用，读这份快照；真正探测前会再以 DataStore 为准刷新一次。
     */
    @Volatile
    private var mode: ProotSeccompMode = ProotSeccompMode.AUTO

    /** 已得结论（profile id → 是否用兼容模式）；命中即不再探测。 */
    private val verdicts = ConcurrentHashMap<String, Boolean>()

    /** 正在探测的 profile：并发启动的多个会话只让第一个真正去探。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val probeMutex = Mutex()

    init {
        scope.launch {
            settings.prootSeccompModeFlow.collect { mode = it }
        }
    }

    /**
     * 把 seccomp 决策拼进容器环境。[forceCompat] 只给探针本身用（探针要指定「这次用不用兼容」），
     * 业务调用不传即走当前模式。不需要兼容时原样返回入参 map——默认路径拼出的环境与加本功能前一致。
     */
    fun withPolicy(env: Map<String, String>, profileId: String, forceCompat: Boolean? = null): Map<String, String> =
        if (forceCompat ?: useCompat(profileId)) env + (PROOT_NO_SECCOMP to "1") else env

    /** 该容器当前是否要加 PROOT_NO_SECCOMP=1。AUTO 且还没有结论时返回 false（即默认行为）。 */
    fun useCompat(profileId: String): Boolean = when (mode) {
        ProotSeccompMode.FORCE_DEFAULT -> false
        ProotSeccompMode.COMPAT -> true
        ProotSeccompMode.AUTO -> verdicts[profileId] == true
    }

    /**
     * 容器就绪时调用，**不阻塞调用方**。远程容器、用户显式选了模式、已有结论、正在探测中——都直接返回。
     * [probe] 由引擎注入：入参为「本次是否强制兼容」，返回容器内真实执行结果。
     */
    fun onContainerReady(
        profile: ContainerProfile,
        distroId: String?,
        probe: suspend (forceCompat: Boolean) -> CommandResult,
    ) {
        if (profile.mode == ExecutionMode.REMOTE_SSH) return
        if (verdicts.containsKey(profile.id)) return
        if (!inFlight.add(profile.id)) return
        scope.launch {
            try {
                resolve(profile.id, distroId, probe)
            } catch (e: Exception) {
                // 探测只是陌生 ROM 的逃生口，不是必要条件：任何异常都只记日志，按默认模式继续用。
                FileLogger.w(TAG, "seccomp 探测异常（忽略，保持默认模式）: profile=${profile.id}", e)
            } finally {
                inFlight.remove(profile.id)
            }
        }
    }

    private suspend fun resolve(
        profileId: String,
        distroId: String?,
        probe: suspend (forceCompat: Boolean) -> CommandResult,
    ) = probeMutex.withLock {
        // 等锁期间别的协程可能已经探完（并发启动的多个会话）。
        if (verdicts.containsKey(profileId)) return@withLock
        // 模式以 DataStore 为准：刚在设置里切过时，内存快照可能还没跟上。
        mode = settings.prootSeccompModeFlow.first()
        if (mode != ProotSeccompMode.AUTO) {
            FileLogger.i(TAG, "seccomp 模式为 $mode，跳过自动探测: profile=$profileId")
            return@withLock
        }
        val key = settings.seccompCacheKey(profileId, distroId)
        val cached = settings.seccompVerdict(key)
        if (cached != null) {
            verdicts[profileId] = cached
            FileLogger.i(TAG, "seccomp 结论命中缓存: profile=$profileId distro=${distroId ?: "?"} compat=$cached")
            return@withLock
        }

        val defaultOutcome = probe(false)
        when {
            defaultOutcome.exitCode == 0 -> {
                record(profileId, key, compat = false, persist = true, reason = "默认模式探针通过")
            }
            !looksLikeSeccompFailure(defaultOutcome) -> {
                // 与 seccomp 无关的失败（cd 失败、磁盘满、超时）绝不触发切换；本次会话不再重试，
                // 但也不落盘——下次启动环境可能已经恢复。
                verdicts[profileId] = false
                FileLogger.i(
                    TAG,
                    "seccomp 探针失败但不像 seccomp 问题，保持默认模式: profile=$profileId 输出=${defaultOutcome.output.take(200)}"
                )
            }
            else -> {
                FileLogger.w(
                    TAG,
                    "默认模式命中 seccomp 失败，改用兼容模式重探: profile=$profileId 输出=${defaultOutcome.output.take(200)}"
                )
                val compatOutcome = probe(true)
                if (compatOutcome.exitCode == 0) {
                    record(profileId, key, compat = true, persist = true, reason = "兼容模式探针通过")
                } else {
                    // 兼容也救不回来：回退默认（与旧行为一致），用户仍可在设置里手动选兼容。
                    verdicts[profileId] = false
                    FileLogger.w(
                        TAG,
                        "兼容模式探针仍失败，回退默认模式: profile=$profileId 输出=${compatOutcome.output.take(200)}"
                    )
                    // 两种模式的失败特征一致（都是 seccomp 过不去）才落盘，避免把偶发故障钉死。
                    if (looksLikeSeccompFailure(compatOutcome)) settings.putSeccompVerdict(key, compat = false)
                }
            }
        }
    }

    /** 丢掉某个容器已得的探测结论（用户在设置里切了模式后调）：下次容器就绪会按新选择重新判断。 */
    fun forgetVerdict(profileId: String) {
        verdicts.remove(profileId)
    }

    private suspend fun record(profileId: String, key: String, compat: Boolean, persist: Boolean, reason: String) {
        verdicts[profileId] = compat
        if (persist) settings.putSeccompVerdict(key, compat)
        FileLogger.i(TAG, "seccomp 探测结论: profile=$profileId compat=$compat（$reason）")
    }

    /** 退出码为 null 表示超时 / 进程异常：那是「放弃」，不算 seccomp 失败特征。 */
    private fun looksLikeSeccompFailure(outcome: CommandResult): Boolean =
        outcome.exitCode != 0 &&
            outcome.output.contains(SECCOMP_EPERM) &&
            SECCOMP_HINT.containsMatchIn(outcome.output)
}
