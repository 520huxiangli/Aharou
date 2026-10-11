package com.aharou.feature.agent.domain.container

import java.util.Locale

/**
 * 容器环境清洗器：把「看起来是凭据」的环境变量挡在容器之外。
 *
 * 背景：PRoot 容器进程默认继承 App 进程的全部环境变量，容器内任何命令
 * （`env` / `cat /proc/self/environ`）都能读到形如 `*_KEY` / `*_TOKEN` 的凭据类变量。
 * 本清洗器在把父进程环境交给容器之前摘掉这些变量，规则对齐参考实现
 * nanomuse `tools/shell.py` 的 `scrubbed_env()`。
 *
 * 边界（重要）：
 *  - 只清洗「继承自 App 进程」的那一份（[scrubbedSystemEnv] / [scrubInherited]）。
 *    用户经 EnvVarRepository 显式注入的变量由调用方在清洗之后再叠加，不受影响——
 *    那是用户自己的意图，不该被静默丢弃。
 *  - 规则只命中带凭据语义的名字，**不会误伤容器运行必需的变量**：`PATH`、`HOME`、
 *    `TMPDIR`、`TERM`、`LANG`、`TZ`、`LD_LIBRARY_PATH`、`PROOT_*`、
 *    `ANDROID_ROOT` / `ANDROID_DATA` / `ANDROID_ASSETS` / `ANDROID_STORAGE` 等
 *    都不命中任何一条规则（`ANDROID_*` 也恰好不落在前缀列表里）。
 *  - 注意 `GIT_CONFIG_KEY_0` 这类「名字里带 KEY 但非凭据」的变量会被规则①命中，
 *    故本清洗器只允许作用于父进程环境，切勿对叠加了容器 env 的完整 map 调用。
 */
object ContainerEnvSanitizer {

    /** 规则①：名字本身即含凭据语义的变量（大小写不敏感，子串匹配）。 */
    private val CREDENTIAL_NAME_REGEX = Regex(
        "(KEY|TOKEN|SECRET|PASSW|PASSPHRASE|CREDENTIAL|_AUTH|AUTH_|COOKIE|SESSION)",
        RegexOption.IGNORE_CASE
    )

    /** 规则②：云厂商 / CI / 包管理器的凭据命名前缀（大小写不敏感）。 */
    private val CREDENTIAL_NAME_PREFIXES = listOf(
        "NANOMUSE_", "AWS_", "AZURE_", "GOOGLE_", "GH_", "GITHUB_", "NPM_"
    )

    /** 规则③：名字看不出是凭据、但本身就指向凭证代理的固定黑名单。 */
    private val ALWAYS_DROP = setOf("SSH_AUTH_SOCK", "GPG_AGENT_INFO")

    /** 该变量名是否应被清洗掉。 */
    fun isCredential(name: String): Boolean {
        if (name in ALWAYS_DROP) return true
        if (CREDENTIAL_NAME_REGEX.containsMatchIn(name)) return true
        val upper = name.uppercase(Locale.ROOT)
        return CREDENTIAL_NAME_PREFIXES.any { upper.startsWith(it) }
    }

    /** 父进程环境摘掉凭据类变量后的副本。 */
    fun scrubbedSystemEnv(source: Map<String, String> = System.getenv()): Map<String, String> =
        source.filterKeys { !isCredential(it) }

    /** 原地摘掉一个可变环境 map 里的凭据类变量（用于 [ProcessBuilder.environment] 这类活视图）。 */
    fun scrubInherited(env: MutableMap<String, String>) {
        env.keys.removeAll { isCredential(it) }
    }
}
