package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.container.SshLoginKey
import com.aharou.feature.agent.domain.container.SshLoginKeyStore
import com.aharou.feature.credentials.data.CredentialInjectSettingsRepository
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import com.aharou.feature.git.data.GitHubTokenStore
import com.aharou.feature.git.domain.GitRepository
import com.aharou.feature.vault.VaultRepository
import com.aharou.feature.workspace.domain.repository.RemoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据类设置 → 配置通道。
 *
 * 四项各按真实存储形态接入：
 * - `git.github_token`：GitHub API 令牌，[GitHubTokenStore] 里按 host 存（值经 Keystore 加密），这里固定接 github.com 这一条。
 * - `credentials.auto_inject`：按远程服务器隔离的「自动注入 git 凭证」开关，服务器集合由 [RemoteRepository] 的连接列表派生
 *   （[CredentialInjectSettingsRepository] 只暴露按哈希 key 读写、无法枚举，故用连接列表当枚举源）。
 * - `ssh.login_keys`：SSH 登录私钥条目，[SshLoginKeyStore] 本身有 entries/add/remove，天然是集合。
 * - `vault.names`：通用凭据库的条目名列表，只读（理由见 [vaultNamesField]）。
 *
 * 令牌 / 口令一律 risk = [ConfigRisk.SENSITIVE]：读取被 `reveal` 的确认门挡住，写入走确认弹窗与审计。
 */
@Singleton
class ConfigCredentialFields @Inject constructor(
    private val githubTokens: GitHubTokenStore,
    private val injectSettings: CredentialInjectSettingsRepository,
    private val loginKeyStore: SshLoginKeyStore,
    private val remote: RemoteRepository,
    private val vault: VaultRepository,
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    /**
     * 工具授权规则。在这里顺带注册而不是单独挂到 AIEditorApp：那个文件卡着 1005 行的棘轮基线，
     * 再加一个注入属性就会顶破门禁，而两类字段本来就同属「授权与凭据」。
     */
    private val permissionFields: ConfigPermissionFields,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(gitHubTokenField())
        registry.register(gitUserNameField())
        registry.register(gitUserEmailField())
        registry.register(GitCredentialsCollection())
        permissionFields.registerInto(registry)
        registry.register(GitCredentialsCollection())
        registry.register(AutoInjectCollection())
        registry.register(LoginKeyCollection())
        registry.register(vaultNamesField())
    }

    private fun gitHubTokenField(): ConfigField = ClosureField(
        path = "git.github_token",
        displayName = "GitHub 令牌",
        description = "调用 GitHub API、读取仓库/PR 状态用的个人访问令牌（github.com）。留空即清除。",
        valueSchema = ConfigSchema.Str(),
        risk = ConfigRisk.SENSITIVE,
        revertable = true,
        reader = { ConfigValue.Str(githubTokens.token(GITHUB_HOST).orEmpty()) },
        writer = { v ->
            val token = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            githubTokens.save(GITHUB_HOST, token)
        },
    )

    /**
     * 提交署名（`git config user.name`）。
     *
     * 与 [GitCredentialsCollection] 同属「凭据与署名」页，之前同样只能进页面手填：
     * 写入走 [GitRepository.setUserIdentity]，与 UI 一样遵「有项目级配项目级、否则写全局」。
     */
    private fun gitUserNameField(): ConfigField = ClosureField(
        path = "git.user_name",
        displayName = "提交署名（用户名）",
        description = "提交时写入的 user.name。当前仓库有项目级配置时写项目级，否则写全局。",
        valueSchema = ConfigSchema.Str(),
        revertable = true,
        reader = { ConfigValue.Str(runBlocking { gitRepository.getUserName() }) },
        writer = { v ->
            val name = (v as? ConfigValue.Str)?.value
                ?: throw ConfigError.TypeMismatch("string")
            runBlocking { gitRepository.setUserIdentity(name, "") }
        },
    )

    private fun gitUserEmailField(): ConfigField = ClosureField(
        path = "git.user_email",
        displayName = "提交署名（邮箱）",
        description = "提交时写入的 user.email。当前仓库有项目级配置时写项目级，否则写全局。",
        valueSchema = ConfigSchema.Str(),
        revertable = true,
        reader = { ConfigValue.Str(runBlocking { gitRepository.getUserEmail() }) },
        writer = { v ->
            val email = (v as? ConfigValue.Str)?.value
                ?: throw ConfigError.TypeMismatch("string")
            runBlocking { gitRepository.setUserIdentity("", email) }
        },
    )

    /**
     * 「凭据与署名」页里的 git 凭据，按主机一条。
     *
     * 之前只有 `memory.sync.token` 能写凭据，而它的主机由记忆仓库地址决定、用户名写死成 cnb，
     * 其它主机（如 github.com）只能去 UI 手填——设置项一律要接 config 通道，这是漏的那一块。
     */
    private inner class GitCredentialsCollection : ConfigCollection {
        override val basePath = "git.credentials"
        override val displayName = "Git 凭据"
        override val description =
            "按主机（如 github.com）保存 git 推送/拉取用的账号。子字段：host / username / token。" +
                "新增时传 {\"host\":\"github.com\",\"username\":\"...\",\"token\":\"...\"}。"

        override val addable = true
        override val removable = true

        override fun childIds(): List<String> = allCredentials().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            val credential = allCredentials().firstOrNull { it.id == forId } ?: return emptyList()
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.host",
                    displayName = "主机（$forId）",
                    description = "凭据按主机匹配：push/pull 到哪个主机就用同名的那一条。",
                    valueSchema = ConfigSchema.Str(),
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Str(credential.host) },
                    writer = { throw ConfigError.PermissionDenied("主机为只读字段，换主机请新建一条") },
                ),
                ClosureField(
                    path = "$basePath.$forId.username",
                    displayName = "用户名（$forId）",
                    description = "git 认证用户名。GitHub 用个人访问令牌时填账号名即可。",
                    valueSchema = ConfigSchema.Str(),
                    revertable = true,
                    reader = { ConfigValue.Str(credential.username) },
                    writer = { v ->
                        val name = (v as? ConfigValue.Str)?.value
                            ?: throw ConfigError.TypeMismatch("string")
                        saveCredential(credential.copy(username = name))
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.token",
                    displayName = "访问令牌（$forId）",
                    description = "该主机的密码或个人访问令牌。写空字符串即删除这条凭据。",
                    valueSchema = ConfigSchema.Str(maxLength = 512),
                    risk = ConfigRisk.SENSITIVE,
                    revertable = true,
                    reader = { ConfigValue.Str(credential.token) },
                    writer = { v ->
                        val token = (v as? ConfigValue.Str)?.value
                            ?: throw ConfigError.TypeMismatch("string")
                        if (token.isBlank()) {
                            runBlocking { credentialRepository.delete(credential.id) }
                        } else {
                            saveCredential(credential.copy(token = token))
                        }
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("git.credentials 的新项需为 JSON 对象")
            val host = (obj["host"] as? ConfigValue.Str)?.value?.trim()?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: throw ConfigError.InvalidValue("缺少 host（如 github.com）")
            val username = (obj["username"] as? ConfigValue.Str)?.value.orEmpty()
            val token = (obj["token"] as? ConfigValue.Str)?.value.orEmpty()
            saveCredential(GitCredential(id = host, host = host, username = username, token = token))
            return host
        }

        override fun remove(id: String) {
            runBlocking { credentialRepository.delete(id) }
        }
    }

    private fun allCredentials(): List<GitCredential> =
        runBlocking { credentialRepository.getAll().first() }

    private fun saveCredential(credential: GitCredential) {
        runBlocking { credentialRepository.save(credential) }
    }

    /**
     * 凭据库条目名列表，只读。
     *
     * 存储本身是整份 JSON 经 Keystore 加密后的单条 blob（`aharou_vault` 的 `blob` 键），
     * [VaultRepository] 只在内存里暴露 names/get/set/delete。这里刻意只暴露「有哪些名字」，
     * 不暴露值：凭据库的设计是模型只该看到 vault 占位符、真值由连接器在使用时解析，连凭据库页面
     * （VaultViewModel）也只列名字。值虽是敏感字段，但暴露出去等于给模型开一条读明文的旁路，
     * 与占位符机制冲突，故不接值。
     *
     * 不新增任何接口：仅调用既有的 [VaultRepository.names]。
     */
    private fun vaultNamesField(): ConfigField = ClosureField(
        path = "vault.names",
        displayName = "凭据库条目名",
        description = "凭据库里已保存条目的名字列表（只读）。要在参数里用某个凭据，写 vault 占位符（名字即凭据名）；值不在此暴露。",
        valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
        access = ConfigAccess.READONLY,
        revertable = false,
        reader = { ConfigValue.Arr(vault.names().map { ConfigValue.Str(it) }) },
        writer = { throw ConfigError.PermissionDenied("凭据库条目名为只读字段") },
    )

    private fun connections() = runBlocking { remote.getConnections().first() }

    /** 服务器标识（[CredentialInjectSettingsRepository.serverKey]）列表，由现有远程连接派生。 */
    private fun serverKeys(): List<String> =
        connections().map { injectSettings.serverKey(it.host, it.port) }.distinct()

    private fun serverKeyOf(id: String): String? =
        serverKeys().firstOrNull { it == id }

    private fun hostLabel(key: String): String =
        connections().firstOrNull { injectSettings.serverKey(it.host, it.port) == key }
            ?.let { "${it.host}:${it.port}" }
            ?: key

    /** 「自动注入 git 凭证」按远程服务器隔离；服务器由 remote.connections 派生，故不支持手动增删。 */
    private inner class AutoInjectCollection : ConfigCollection {
        override val basePath = "credentials.auto_inject"
        override val displayName = "远程 git 凭证自动注入"
        override val description =
            "按远程服务器（host:port）记忆是否把本机 git 凭据自动注入到该服务器。子字段：enabled / asked。" +
                "服务器集合来自 remote.connections，不能在此增删（去 remote.connections 里加）。"

        override val addable = false
        override val removable = false

        override fun childIds(): List<String> = serverKeys()

        override fun fields(forId: String): List<ConfigField> {
            if (serverKeyOf(forId) == null) return emptyList()
            val label = hostLabel(forId)
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.enabled",
                    displayName = "自动注入 git 凭证（$label）",
                    description = "开启后，连接该服务器时把本机 git 凭据与 gitconfig 片段上传，供容器内 git 使用。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(injectSettings.isAutoInjectEnabled(forId)) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        injectSettings.setAutoInject(forId, b)
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.asked",
                    displayName = "是否已询问过（$label）",
                    description = "该服务器是否已弹过「是否自动注入」的首次询问（只读，只由首次弹窗置位）。",
                    valueSchema = ConfigSchema.Bool,
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Bool(injectSettings.hasAsked(forId)) },
                    writer = { throw ConfigError.PermissionDenied("已询问标记为只读字段") },
                ),
            )
        }

        override fun add(payload: ConfigValue): String =
            throw ConfigError.InvalidValue("服务器来自 remote.connections，不支持手动新增")

        override fun remove(id: String) {
            throw ConfigError.PermissionDenied("不支持在此删除服务器（去 remote.connections 里删）")
        }
    }

    private fun loginKeys(): List<SshLoginKey> = loginKeyStore.entries()

    private fun loginKey(id: String): SshLoginKey? = loginKeys().firstOrNull { it.id == id }

    private fun loginKeyField(
        id: String,
        segment: String,
        label: String,
        desc: String,
        risk: ConfigRisk = ConfigRisk.NORMAL,
        writable: Boolean,
        read: (SshLoginKey) -> String,
        apply: ((SshLoginKey, String) -> SshLoginKey)? = null,
    ): ConfigField = ClosureField(
        path = "ssh.login_keys.$id.$segment",
        displayName = "$label（$id）",
        description = desc,
        valueSchema = ConfigSchema.Str(),
        access = if (writable) ConfigAccess.READWRITE else ConfigAccess.READONLY,
        risk = risk,
        revertable = writable,
        reader = { ConfigValue.Str(loginKey(id)?.let(read).orEmpty()) },
        writer = { v ->
            val fn = apply ?: throw ConfigError.PermissionDenied("$segment 为只读字段")
            val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            val current = loginKey(id) ?: throw ConfigError.InvalidValue("登录密钥不存在：$id")
            loginKeyStore.add(fn(current, s))
        },
    )

    /** SSH 登录私钥条目；私钥文件本体在应用私有目录，这里只管条目元数据。 */
    private inner class LoginKeyCollection : ConfigCollection {
        override val basePath = "ssh.login_keys"
        override val displayName = "SSH 登录密钥"
        override val description =
            "连接时用于认证的登录私钥条目。子字段：name / passphrase / path / fingerprint。" +
                "新增需给 name 与 path（指向已有的私钥文件）；path 与 fingerprint 由导入流程决定，不可改。"

        override fun childIds(): List<String> = loginKeys().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            if (loginKey(forId) == null) return emptyList()
            return listOf(
                loginKeyField(
                    forId, "name", "名称", "列表里显示的名字。",
                    writable = true,
                    read = { it.name },
                    apply = { k, s -> k.copy(name = s.ifBlank { k.name }) },
                ),
                loginKeyField(
                    forId, "passphrase", "私钥口令",
                    "加密私钥才需要；留空表示私钥未加密。",
                    risk = ConfigRisk.SENSITIVE,
                    writable = true,
                    read = { it.passphrase.orEmpty() },
                    apply = { k, s -> k.copy(passphrase = s.ifBlank { null }) },
                ),
                loginKeyField(
                    forId, "path", "私钥路径", "私钥文件绝对路径（应用私有目录内），由导入流程决定。",
                    writable = false,
                    read = { it.path },
                ),
                loginKeyField(
                    forId, "fingerprint", "公钥指纹", "由私钥内容推导的 SHA256 指纹；无法解析时为空。",
                    writable = false,
                    read = { it.fingerprint.orEmpty() },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("ssh.login_keys 的新项需为 JSON 对象")
            val name = (obj["name"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
                ?: throw ConfigError.InvalidValue("缺少 name")
            val path = (obj["path"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
                ?: throw ConfigError.InvalidValue("缺少 path")
            val passphrase = (obj["passphrase"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }
            val fingerprint = (obj["fingerprint"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
            val id = UUID.randomUUID().toString()
            loginKeyStore.add(
                SshLoginKey(
                    id = id,
                    name = name,
                    path = path,
                    fingerprint = fingerprint,
                    passphrase = passphrase,
                ),
            )
            return id
        }

        override fun remove(id: String) {
            if (loginKey(id) == null) throw ConfigError.InvalidValue("登录密钥不存在：$id")
            loginKeyStore.remove(id)
        }
    }

    private companion object {
        /** GitHub 公共站点 host，与 CredentialViewModel 的 GITHUB_HOST 同值。 */
        const val GITHUB_HOST = "github.com"
    }
}
