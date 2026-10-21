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

        /**
         * 兼容分支的包名。Shevery 改了包名并强制卸载官方版，只认官方包名会把它误判成未安装。
         * binder 通道本身与管理器包名无关，这里只用于展示状态与跳转入口。
         */
        const val SHEVERY_PACKAGE = "com.hamondev.shevery"

        val MANAGER_PACKAGES = listOf(SHIZUKU_PACKAGE, SHEVERY_PACKAGE)

        /**
         * Shizuku 对外暴露的 API 权限名。改包名的 fork 依然声明这个名字（权限名与包名解耦），
         * 所以它能用来反查「哪个应用是 Shizuku 管理器」。
         */
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

        const val PERMISSION_REQUEST_CODE = 1001

        /** 服务身份 tag：不设时用类名，类名经 R8 混淆后不稳定，故显式固定。 */
        const val SERVICE_TAG = "shizuku_shell"

        /** 绑定 UserService 的等待上限（毫秒）。低端机冷启动/版本升级重建 UserService 可能较慢，给足 60 秒。 */
        const val BIND_TIMEOUT_MS = 60_000L

        /** 首次绑定超时后的重试次数。 */
        const val BIND_MAX_RETRIES = 1

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

    /** 反查命中过的管理器包名。扫描要遍历全部已装应用，命中后缓存，避免每次刷新状态重扫。 */
    @Volatile
    private var discoveredManagerPackage: String? = null

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
        // 先判 binder：分支版换了包名照样推 binder，以包名开闸会把可用通道误判成「未安装」。
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return if (findManagerPackage() != null) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        // pre-v11 无 UserService，等同不可用。
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return ShizukuState.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.PERMISSION_DENIED
    }

    /**
     * 已安装的管理器包名，都没装返回 null。
     *
     * 两级判定：先查白名单里的已知包名（常见情况，无需遍历已装应用），
     * 再按 [SHIZUKU_PERMISSION] 反查——换过包名的 fork 不在白名单里，
     * 但只要它还声明 Shizuku 的 API 权限就能被认出来。
     */
    private fun findManagerPackage(): String? {
        MANAGER_PACKAGES.firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }?.let { return it }
        return findPackageDeclaringShizukuPermission()
    }

    /**
     * 按权限名反查声明了 Shizuku API 权限的包。
     *
     * 看的是「声明的权限」（[android.content.pm.PackageInfo.permissions]）而不是「请求的权限」：
     * 权限是管理器自己定义的，而请求它的应用（包括本 App，依赖库的 manifest 会合并出这条
     * uses-permission）用请求列表会被误判成管理器。自身包名仍显式排除，防御本 App 将来自己声明同权限。
     *
     * targetSdk 30+ 的包可见性限制下，未在 `<queries>` 声明该权限时 getInstalledPackages 只能看到
     * 本 App 与系统应用，所以 manifest 里同步加了对应声明。
     */
    @Suppress("DEPRECATION")
    private fun findPackageDeclaringShizukuPermission(): String? {
        discoveredManagerPackage?.let { return it }
        val installed = runCatching {
            context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return null
        return installed.firstOrNull { info ->
            info.packageName != context.packageName &&
                info.permissions?.any { it.name == SHIZUKU_PERMISSION } == true
        }?.packageName?.also { discoveredManagerPackage = it }
    }

    /** 申请 Shizuku 授权。需在主线程调用（Shizuku 内部要求）。 */
    fun requestPermission() {
        if (computeState() != ShizukuState.PERMISSION_DENIED) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { FileLogger.w(TAG, "申请 Shizuku 授权失败: ${it.message}") }
    }

    /** 打开 Shizuku 应用；未安装则跳转官方下载页。 */
    fun openShizukuApp() {
        val launch = findManagerPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
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

    /** 幂等绑定 UserService，返回可用的 AIDL 代理。超时后重试一次再报错。 */
    private suspend fun ensureBound(): IShizukuShellService {
        shellService?.let { return it }
        return bindMutex.withLock {
            shellService?.let { return@withLock it }
            if (computeState() != ShizukuState.READY) {
                throw IllegalStateException("Shizuku 未就绪（${_state.value}）")
            }
            var lastError: Throwable = IllegalStateException("绑定 Shizuku 服务超时")
            repeat(BIND_MAX_RETRIES + 1) { attempt ->
                val deferred = CompletableDeferred<IShizukuShellService>()
                pendingBind = deferred
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                }
                val result = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                    runCatching { deferred.await() }.getOrNull()
                }
                if (result != null) return@withLock result
                FileLogger.w(TAG, "绑定 Shizuku UserService 超时（第 ${attempt + 1} 次），${if (attempt < BIND_MAX_RETRIES) "重试中…" else "放弃"}")
                pendingBind = null
            }
            throw lastError
        }
    }
}
