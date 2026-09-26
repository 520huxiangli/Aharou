package com.aharou.feature.agent.domain.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** Shizuku 可用状态，供设置页展示与工具执行前判定。 */
enum class ShizukuState {
    /** 未安装 Shizuku（或 Sui）。 */
    NOT_INSTALLED,

    /** 已安装但服务未运行，需用户先在 Shizuku 内启动服务。 */
    NOT_RUNNING,

    /** 服务运行中，但本应用尚未获得授权。 */
    PERMISSION_DENIED,

    /** 就绪，可执行命令。 */
    READY
}

/** 一次 Shizuku 命令执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class ShizukuCommandResult(val output: String, val exitCode: Int)

/**
 * Shizuku 后端：以 adb shell（uid 2000）身份执行命令。
 *
 * 通过 UserService（[ShizukuShellService]）而非已弃用的 `Shizuku#newProcess` 执行命令：
 * App 绑定一个运行在 shell 进程的服务，调用其 AIDL 接口代执行，进程间只传命令与结果。
 *
 * 状态由 Shizuku 的 binder 存活/死亡与授权结果驱动，[state] 变化时设置页与工具据此响应。
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ShizukuManager"

        /** Shizuku 官方应用包名（Sui 也用它作为入口）。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        const val PERMISSION_REQUEST_CODE = 1001

        /** 服务身份 tag：不设时用类名，类名经 R8 混淆后不稳定，故显式固定。 */
        const val SERVICE_TAG = "shizuku_shell"

        /** 绑定 UserService 的等待上限（毫秒）。Shizuku 启动服务自身超时为 30 秒。 */
        const val BIND_TIMEOUT_MS = 30_000L

        /** 命令超时上限（毫秒），与 [com.aharou.feature.agent.domain.container.CommandEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val bindMutex = Mutex()

    @Volatile
    private var shellService: IShizukuShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IShizukuShellService>? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku 服务已断开"))
        pendingBind = null
        refreshState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshState()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = IShizukuShellService.Stub.asInterface(service)
            shellService = bound
            pendingBind?.complete(bound)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            pendingBind?.completeExceptionally(IllegalStateException("Shizuku UserService 已断开"))
            pendingBind = null
            refreshState()
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .tag(SERVICE_TAG)
            .processNameSuffix("shizuku_shell")
            .debuggable(isDebuggable)
            .version(appVersionCode)
    }

    /** 项目未开启 BuildConfig，版本号从 PackageManager 读取（升级后自动重建 UserService）。 */
    @Suppress("DEPRECATION")
    private val appVersionCode: Int by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                info.versionCode
            }
        }.getOrDefault(1)
    }

    private val isDebuggable: Boolean by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    init {
        runCatching {
            // Sticky：binder 已就绪时立即回调，无需额外探测首帧状态。
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { FileLogger.w(TAG, "注册 Shizuku 监听失败: ${it.message}") }
        refreshState()
    }

    /** 重新计算并发布当前状态。 */
    fun refreshState() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState {
        if (!isShizukuInstalled()) return ShizukuState.NOT_INSTALLED
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return ShizukuState.NOT_RUNNING
        // pre-v11 无 UserService，等同不可用。
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return ShizukuState.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.PERMISSION_DENIED
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 申请 Shizuku 授权。需在主线程调用（Shizuku 内部要求）。 */
    fun requestPermission() {
        if (computeState() != ShizukuState.PERMISSION_DENIED) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { FileLogger.w(TAG, "申请 Shizuku 授权失败: ${it.message}") }
    }

    /** 打开 Shizuku 应用；未安装则跳转官方下载页。 */
    fun openShizukuApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        runCatching {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.onFailure { FileLogger.w(TAG, "打开 Shizuku 失败: ${it.message}") }
    }

    /** 执行 shell 命令。未就绪或绑定失败时抛异常，由调用方转成工具错误。 */
    suspend fun runCommand(command: String, timeoutMs: Long): ShizukuCommandResult {
        val service = ensureBound()
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS).toInt()
        return withContext(Dispatchers.IO) {
            val bundle = service.exec(command, timeout)
            ShizukuCommandResult(
                output = bundle.getString(ShizukuShellService.KEY_OUTPUT).orEmpty(),
                exitCode = bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_FAILURE)
            )
        }
    }

    /** 幂等绑定 UserService，返回可用的 AIDL 代理。 */
    private suspend fun ensureBound(): IShizukuShellService {
        shellService?.let { return it }
        return bindMutex.withLock {
            shellService?.let { return@withLock it }
            if (computeState() != ShizukuState.READY) {
                throw IllegalStateException("Shizuku 未就绪（${_state.value}）")
            }
            val deferred = CompletableDeferred<IShizukuShellService>()
            pendingBind = deferred
            withContext(Dispatchers.Main) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                ?: throw IllegalStateException("绑定 Shizuku 服务超时")
        }
    }
}
