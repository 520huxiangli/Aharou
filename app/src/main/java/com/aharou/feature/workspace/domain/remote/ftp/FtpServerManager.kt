package com.aharou.feature.workspace.domain.remote.ftp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import com.aharou.R
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ftpServerDataStore by preferencesDataStore(
    name = "ftp_server_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class FtpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "FtpServerManager"
        val PORT_KEY = intPreferencesKey("port")
        val USERNAME_KEY = stringPreferencesKey("username")
        val PASSWORD_KEY = stringPreferencesKey("password")
        val ANONYMOUS_KEY = booleanPreferencesKey("anonymous")
        val AUTO_START_KEY = booleanPreferencesKey("auto_start")

        /** 历史默认口令。读到它按「从未设置过」处理并换成随机值——它同时留在代码与旧数据里，不换等于没修。 */
        const val LEGACY_DEFAULT_PASSWORD = "123456"

        /** 随机口令：62 字符表取 12 位，约 71 bit，够挡住在线爆破，也便于手动抄。 */
        const val RANDOM_PASSWORD_LENGTH = 12
        const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        val secureRandom = SecureRandom()

        fun randomPassword(): String = buildString(RANDOM_PASSWORD_LENGTH) {
            repeat(RANDOM_PASSWORD_LENGTH) {
                append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)])
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ftpServer: FtpServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(2121)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _username = MutableStateFlow("aicode")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isAnonymous = MutableStateFlow(false)
    val isAnonymous: StateFlow<Boolean> = _isAnonymous.asStateFlow()

    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val defaultSharedPath: String by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }.absolutePath
    }

    init {
        scope.launch {
            val prefs = context.ftpServerDataStore.data.first()
            _port.value = prefs[PORT_KEY] ?: 2121
            _username.value = prefs[USERNAME_KEY] ?: "aicode"
            _password.value = resolvePassword(prefs[PASSWORD_KEY])
            _isAnonymous.value = prefs[ANONYMOUS_KEY] ?: false
            _autoStart.value = prefs[AUTO_START_KEY] ?: false

            updateServerUrl()

            if (_autoStart.value) {
                startServer()
            }
        }
    }

    /**
     * 取可用口令：没有、解不开、或仍是历史默认值时生成随机口令并加密落盘。
     * 返回值始终是明文——UI 要显示、Apache FtpServer 也要用它做比对。
     */
    private suspend fun resolvePassword(stored: String?): String {
        val plain = stored?.let { KeystoreCipher.decryptString(it) }
        if (!plain.isNullOrEmpty() && plain != LEGACY_DEFAULT_PASSWORD) return plain

        val generated = randomPassword()
        context.ftpServerDataStore.edit { prefs ->
            prefs[PASSWORD_KEY] = KeystoreCipher.encryptString(generated)
        }
        return generated
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "获取本机 IP 失败: ${e.message}", e)
        }
        return "127.0.0.1"
    }

    private fun updateServerUrl() {
        val ip = getLocalIpAddress()
        _serverUrl.value = "ftp://$ip:${_port.value}"
    }

    suspend fun saveConfig(port: Int, username: String, password: String, isAnonymous: Boolean, autoStart: Boolean) = withContext(Dispatchers.IO) {
        val trimmedName = username.trim()
        // 保留名与空口令在这里挡住：内存用户表按名字存键，用户名写成 anonymous 会和匿名入口撞名。
        if (trimmedName.equals("anonymous", ignoreCase = true)) {
            _errorMessage.value = context.getString(R.string.ftp_reserved_username)
            return@withContext
        }
        if (trimmedName.isNotEmpty() && password.isBlank()) {
            _errorMessage.value = context.getString(R.string.ftp_password_required)
            return@withContext
        }
        val wasRunning = _isRunning.value
        _errorMessage.value = null
        if (wasRunning) {
            stopServerInternal()
        }

        _port.value = port
        _username.value = trimmedName
        _password.value = password
        _isAnonymous.value = isAnonymous
        _autoStart.value = autoStart
        updateServerUrl()

        context.ftpServerDataStore.edit { prefs ->
            prefs[PORT_KEY] = port
            prefs[USERNAME_KEY] = trimmedName
            // 只保护静态存储；Apache FtpServer 比对用的是内存里的明文。
            prefs[PASSWORD_KEY] = KeystoreCipher.encryptString(password)
            prefs[ANONYMOUS_KEY] = isAnonymous
            prefs[AUTO_START_KEY] = autoStart
        }

        if (wasRunning) {
            startServerInternal()
        }
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) {
            stopServerInternal()
            false
        } else {
            startServerInternal()
        }
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext true
        startServerInternal()
    }

    private fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()

            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = _port.value
            serverFactory.addListener("default", listenerFactory.createListener())

            val userManager = InMemoryUserManager()
            // 旧版本把账号（含明文口令）写在这里，启动时清掉，免得继续被别人读到。
            runCatching { File(context.cacheDir, "ftp_users.properties").delete() }

            val authorities = ArrayList<Authority>()
            authorities.add(WritePermission())
            authorities.add(ConcurrentLoginPermission(20, 20))
            authorities.add(TransferRatePermission(0, 0))

            // 正常用户
            if (_username.value.isNotBlank()) {
                val user = BaseUser()
                user.name = _username.value
                user.password = _password.value
                user.homeDirectory = defaultSharedPath
                user.authorities = authorities
                userManager.save(user)
            }

            // 匿名用户
            if (_isAnonymous.value) {
                val anonUser = BaseUser()
                anonUser.name = "anonymous"
                anonUser.homeDirectory = defaultSharedPath
                anonUser.authorities = authorities
                userManager.save(anonUser)
            }

            serverFactory.userManager = userManager
            val server = serverFactory.createServer()
            server.start()
            ftpServer = server
            _isRunning.value = true
            updateServerUrl()
            FileLogger.i(TAG, "内置 FTP 服务端已启动: ${_serverUrl.value}, 共享目录: $defaultSharedPath")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动内置 FTP 服务端失败: ${e.message}", e)
            _errorMessage.value = e.message ?: "启动失败，可能端口被占用"
            _isRunning.value = false
            return false
        }
    }

    /**
     * 账号只存内存：旧实现用 PropertiesUserManager 把明文口令写进 cacheDir，且改用户名后
     * 旧条目不会被清理（旧账号仍能登录）。内存表随服务重启重建，撤销即时生效。
     */
    private class InMemoryUserManager : UserManager {
        private val users = ConcurrentHashMap<String, User>()

        override fun getUserByName(name: String?): User? = name?.let { users[it] }
        override fun getAllUserNames(): Array<String> = users.keys.toTypedArray()
        override fun delete(name: String?) { name?.let { users.remove(it) } }
        override fun save(user: User) { users[user.name] = user }
        override fun doesExist(name: String?): Boolean = name != null && users.containsKey(name)
        override fun getAdminName(): String = "admin"
        override fun isAdmin(login: String?): Boolean = false

        override fun authenticate(authentication: Authentication?): User? {
            val auth = authentication as? UsernamePasswordAuthentication ?: return null
            val user = users[auth.username] ?: return null
            return if (auth.password == user.password) user else null
        }
    }

    private fun stopServerInternal() {
        try {
            ftpServer?.stop()
            ftpServer = null
            _isRunning.value = false
            FileLogger.i(TAG, "内置 FTP 服务端已停止")
        } catch (e: Exception) {
            FileLogger.e(TAG, "停止内置 FTP 服务端出错: ${e.message}", e)
        }
    }
}
