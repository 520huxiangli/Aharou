package com.aharou.feature.im.domain.napcat

import com.aharou.core.util.ProcessIdentity
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.TrackedStdioProcess
import com.aharou.feature.im.data.napcat.NapCatPackageInstaller
import com.aharou.feature.im.data.napcat.NapCatWebUiClient
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class NapCatRuntimeManager @Inject constructor(
    private val installer: NapCatPackageInstaller,
    private val engine: LinuxContainerEngine,
    private val settings: NapCatSettingsRepository,
    private val web: NapCatWebUiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(NapCatRuntimeState(
        phase = if (installer.installedVersion().isEmpty()) "not_installed" else "installed",
        version = installer.installedVersion()
    ))
    val state: StateFlow<NapCatRuntimeState> = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _login = MutableStateFlow(NapCatLoginState())
    val login: StateFlow<NapCatLoginState> = _login.asStateFlow()
    private var handle: TrackedStdioProcess? = null
    private var processIdentity: ProcessIdentity.Handle? = null
    private var polling: Job? = null
    private var recoveryAttempted = false
    private var recoveryNotice = ""

    suspend fun install() = deploy(false)
    suspend fun update() = deploy(true)

    private suspend fun deploy(update: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val restart = handle?.process?.isAlive == true
        guarded {
            if (!update && installer.installedVersion().isNotEmpty()) return@guarded
            if (update) check(installer.installedVersion().isNotEmpty()) { "NapCat is not installed" }
            stopLocked()
            _state.value = _state.value.copy(phase = "installing", progress = 0f, error = "")
            installer.install(update) { progress -> _state.value = _state.value.copy(progress = progress) }
            _state.value = NapCatRuntimeState("installed", 1f, installer.installedVersion())
            appendLog("NapCat package installed")
            if (restart) startLocked()
        }
    } }

    suspend fun start() = withContext(Dispatchers.IO) { mutex.withLock { guarded { startLocked() } } }
    suspend fun stop() = withContext(Dispatchers.IO) { mutex.withLock { guarded { stopLocked() } } }

    private suspend fun startLocked() {
        if (handle?.process?.isAlive == true) return
        check(installer.installedVersion().isNotEmpty()) { "Install NapCat first" }
        installer.configure()
        val recoveryAccount = settings.current().lastAccountId
        recoveryAttempted = recoveryAccount.isNotEmpty()
        recoveryNotice = ""
        settings.setAccountId("")
        web.reset()
        _state.value = _state.value.copy(phase = "starting", error = "")
        _login.value = NapCatLoginState(accountId = recoveryAccount, status = "checking")
        val tracked = engine.startTrackedStdioProcess(
            "/bin/bash", listOf("/napcat/launch.sh"), null, "napcat:runtime",
            extraEnv = installer.runtimeEnvironment(recoveryAccount),
            profile = installer.profile(), inheritUserConfig = false
        )
        handle = tracked
        processIdentity = ProcessIdentity.capture(tracked.process, fallbackPid = tracked.pid)
        listOf(tracked.process.inputStream, tracked.process.errorStream).forEach { stream ->
            scope.launch {
                try { stream.bufferedReader().useLines { lines -> lines.forEach(::appendLog) } }
                catch (_: Exception) { }
            }
        }
        scope.launch {
            val exit = tracked.process.waitFor()
            tracked.clearLedger()
            mutex.withLock {
                if (handle === tracked) {
                    handle = null
                    processIdentity = null
                    polling?.cancel()
                    _login.value = NapCatLoginState(accountId = settings.current().lastAccountId, status = "stopped")
                    settings.setAccountId("")
                    _state.value = _state.value.copy(phase = if (exit == 0) "stopped" else "error",
                        error = if (exit == 0) "" else "NapCat process exited ($exit)")
                }
            }
        }
        polling?.cancel()
        polling = scope.launch {
            var ready = false
            var attempts = 0
            while (handle === tracked && tracked.process.isAlive) {
                try {
                    mutex.withLock { refreshLogin() }
                    ready = true
                    _state.value = _state.value.copy(phase = "running", error = recoveryNotice)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    attempts++
                    val message = "NapCat WebUI unavailable: ${safeError(e)}"
                    _login.value = NapCatLoginState(accountId = settings.current().lastAccountId,
                        status = "unavailable", error = message)
                    settings.setAccountId("")
                    _state.value = _state.value.copy(error = message)
                    if (ready || attempts >= 60) {
                        _state.value = _state.value.copy(phase = "error", error = if (ready) message else "NapCat WebUI startup timed out: ${safeError(e)}")
                    }
                }
                delay(2000)
            }
        }
    }

    private suspend fun stopLocked() = withContext(Dispatchers.IO) {
        polling?.cancelAndJoin()
        polling = null
        val tracked = handle
        if (tracked != null) {
            val identity = processIdentity
            if (identity != null) {
                check(ProcessIdentity.terminateTree(identity)) { "NapCat process tree did not stop" }
            } else {
                tracked.process.destroy()
                if (!tracked.process.waitFor(5, TimeUnit.SECONDS)) tracked.process.destroyForcibly()
            }
            check(tracked.process.waitFor(5, TimeUnit.SECONDS)) { "NapCat process did not stop" }
            tracked.clearLedger()
            if (handle === tracked) { handle = null; processIdentity = null }
        }
        web.reset()
        settings.setAccountId("")
        _login.value = NapCatLoginState(accountId = settings.current().lastAccountId, status = "stopped")
        _state.value = _state.value.copy(phase = if (installer.installedVersion().isEmpty()) "not_installed" else "stopped", error = "")
    }

    suspend fun refreshQrCode() = withContext(Dispatchers.IO) { mutex.withLock { loginGuarded {
        web.call(settings.current().webUiToken, "/api/QQLogin/RefreshQRcode")
        refreshLogin()
    } } }

    suspend fun passwordLogin(uin: String, password: String) = withContext(Dispatchers.IO) { mutex.withLock { loginGuarded {
        require(uin.matches(Regex("[0-9]{5,20}"))) { "Invalid QQ account" }
        require(password.isNotEmpty()) { "Password is empty" }
        val data = web.call(settings.current().webUiToken, "/api/QQLogin/PasswordLogin",
            JSONObject().put("uin", uin).put("passwordMd5", NapCatWebUiClient.digest("MD5", password)))
            .optJSONObject("data")
        if (data?.optBoolean("needCaptcha") == true || data?.optBoolean("needNewDevice") == true) {
            _login.value = _login.value.copy(error = "Complete verification in NapCat WebUI")
        } else refreshLogin()
    } } }

    suspend fun logout() = withContext(Dispatchers.IO) { mutex.withLock { guarded {
        stopLocked()
        installer.rotateLoginProfile()
        settings.clearLoginRecovery()
        recoveryAttempted = false
        recoveryNotice = ""
        _login.value = NapCatLoginState(status = "logged_out")
    } } }

    suspend fun updateConnection(field: String, value: String) = withContext(Dispatchers.IO) { mutex.withLock { guarded {
        check(installer.installedVersion().isNotEmpty()) { "Install NapCat before changing its connection" }
        val restart = handle?.process?.isAlive == true
        val account = settings.current().accountId
        stopLocked()
        try { installer.updateConnection(field, value, account) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (restart) {
                try { startLocked() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
            throw e
        }
        if (restart) startLocked()
    } } }

    suspend fun webUiUrl(): String? = withContext(Dispatchers.IO) {
        if (handle?.process?.isAlive != true) return@withContext null
        installer.syncConnection()
        val config = settings.current()
        config.webUiUrl.trimEnd('/') + "/webui?token=" + URLEncoder.encode(config.webUiToken, "UTF-8")
    }

    private suspend fun refreshLogin() {
        installer.syncConnection()
        val token = settings.current().webUiToken
        val status = web.call(token, "/api/QQLogin/CheckLoginStatus").getJSONObject("data")
        if (status.optBoolean("isLogin")) {
            val info = web.call(token, "/api/QQLogin/GetQQLoginInfo").getJSONObject("data")
            if (!info.optBoolean("online")) {
                settings.setAccountId("")
                _login.value = NapCatLoginState(accountId = settings.current().lastAccountId, status = "checking")
                return
            }
            val account = info.optString("uin")
            check(account.matches(Regex("[0-9]{5,20}"))) { "NapCat returned invalid login identity" }
            if (settings.current().accountId != account) settings.setAccountId(account)
            if (_login.value.accountId != account || !_login.value.loggedIn) {
                val accountConfig = installer.accountConfig(account)
                web.call(settings.current().webUiToken, "/api/OB11Config/SetConfig", JSONObject().put("config", accountConfig.toString()))
            }
            recoveryNotice = ""
            _login.value = NapCatLoginState(true, account, info.optString("nick"), status = "online")
        } else {
            if (settings.current().accountId.isNotEmpty()) settings.setAccountId("")
            if (!status.optBoolean("restarting")) recoverLogin(token)
            val lastAccount = settings.current().lastAccountId
            _login.value = NapCatLoginState(accountId = lastAccount,
                qrCode = status.optString("qrcodeurl"),
                status = if (status.optBoolean("restarting")) "checking" else "logged_out",
                error = recoveryNotice.ifEmpty {
                    if (lastAccount.isNotEmpty() && !status.optBoolean("restarting"))
                        "Quick login has not completed; complete verification in NapCat WebUI" else ""
                })
        }
    }

    private suspend fun recoverLogin(token: String) {
        if (recoveryAttempted) return
        val data = web.call(token, "/api/QQLogin/GetQuickLoginList").getJSONArray("data")
        val accounts = NapCatLoginState.recoveryAccounts(List(data.length()) { data.getString(it) })
        if (accounts.isEmpty()) return
        recoveryAttempted = true
        if (accounts.size != 1) {
            recoveryNotice = "Multiple saved QQ accounts; select an account in NapCat WebUI"
            return
        }
        val account = accounts.single()
        settings.setLastAccountId(account)
        try {
            web.call(token, "/api/QQLogin/SetQuickLogin", JSONObject().put("uin", account))
            recoveryNotice = "Quick login requested; complete verification in NapCat WebUI if needed"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            recoveryNotice = "Quick login failed: ${safeError(e)}; complete verification in NapCat WebUI"
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    stopLocked()
                    val version = installer.installedVersion()
                    _state.value = NapCatRuntimeState(
                        phase = if (version.isEmpty()) "not_installed" else "installed",
                        version = version
                    )
                } catch (cleanup: Exception) {
                    _state.value = _state.value.copy(phase = "error", error = safeError(cleanup))
                }
            }
            throw e
        }
        catch (e: Exception) {
            val message = safeError(e)
            _state.value = _state.value.copy(phase = "error", error = message)
            appendLog(message)
            throw IllegalStateException(message)
        }
    }

    private suspend fun loginGuarded(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = safeError(e)
            _login.value = _login.value.copy(error = message)
            throw IllegalStateException(message)
        }
    }

    private fun safeError(e: Exception): String = (e.message ?: e.javaClass.simpleName)
        .replace(Regex("https?://[^\\s]+"), "[url]")
        .replace(Regex("(?i)(token|password|credential|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
        .takeLast(1400)

    @Synchronized private fun appendLog(line: String) {
        val clean = line.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"), "").take(2000)
        if (Regex("token|password|credential|qrcode|二维码|密码|https?://", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return
        _logs.value = (_logs.value + clean).takeLast(300)
    }
}
