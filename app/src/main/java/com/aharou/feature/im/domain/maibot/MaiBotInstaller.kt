package com.aharou.feature.im.domain.maibot

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.im.data.napcat.NapCatPackageInstaller
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在机器人环境里安装 MaiBot（Python 程序）并接上 NapCat 的 OneBot 通道。
 *
 * 安装物放 `/opt/aharou-napcat/maibot`，与 NapCat 同一个挂载目录，换镜像不丢。
 * 模型直接复用 App 里已启用的供应商配置，不再让用户另配一套 Key。
 */
@Singleton
class MaiBotInstaller @Inject constructor(
    private val engine: LinuxContainerEngine,
    private val napCatInstaller: NapCatPackageInstaller,
    private val providers: AIProviderRepository,
    @param:ApplicationContext private val context: Context,
) {
    data class Result(val success: Boolean, val message: String)

    private companion object {
        const val TAG = "MaiBotInstaller"
        const val ROOT_HOST = "napcat/maibot"
        const val ROOT_GUEST = "/opt/aharou-napcat/maibot"
        const val MAIBOT_DIR = "$ROOT_GUEST/MaiBot-main"
        const val ADAPTER_ID = "MaiBot-SnowLuma-Adapter"
        const val ADAPTER_WS_PORT = 3003
        const val INSTALL_TIMEOUT_MS = 15 * 60 * 1000L
        const val PROGRESS_MARK = "@@P:"
    }

    private val napCatBase: File get() = File(context.filesDir, "aharou-global/napcat")
    private val appDir: File get() = File(napCatBase, "maibot")
    private val botDir: File get() = File(appDir, "MaiBot-main")

    fun isInstalled(): Boolean = File(botDir, ".venv/bin/python").isFile

    suspend fun install(onLog: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        if (!napCatInstaller.isEnvironmentReady()) {
            return@withContext Result(false, "先安装机器人环境（个人 QQ 的 NapCat）")
        }
        onLog("开始安装 MaiBot")
        val exit = runScript(INSTALL_SCRIPT, onLog, onProgress)
        if (exit != 0) {
            FileLogger.w(TAG, "MaiBot 安装脚本退出码 $exit")
            return@withContext Result(false, "MaiBot 安装失败（退出码 $exit），看日志定位")
        }
        val model = injectModel()
        configureAdapter()
        onLog("MaiBot 安装完成")
        Result(true, model ?: "已安装")
    }

    /** 把 App 里首个启用的供应商写进 MaiBot 的模型目录，并把它分配给三个必配任务。 */
    private suspend fun injectModel(): String? = runCatching {
        val provider = providers.getAllProviders().first()
            .firstOrNull { it.isEnabled && it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() }
            ?: return@runCatching null
        val modelName = provider.selectedModel.ifBlank { provider.defaultModel }
            .ifBlank { provider.models.firstOrNull().orEmpty() }
        if (modelName.isBlank()) return@runCatching null
        val baseUrl = provider.baseUrl.trimEnd('/').let { url ->
            if (url.endsWith("/v1") || provider.useFullUrl) url else "$url/v1"
        }
        File(botDir, "host").mkdirs()
        File(botDir, "host/model_catalog.toml").writeText(
            buildString {
                appendLine("[inner]")
                appendLine("version = \"3.0.0\"")
                appendLine()
                appendLine("[[api_providers]]")
                appendLine("name = \"${provider.id}\"")
                appendLine("base_url = \"$baseUrl\"")
                appendLine("api_key = \"${provider.apiKey}\"")
                appendLine("client_type = \"openai\"")
                appendLine()
                appendLine("[[models]]")
                appendLine("model_type = \"llm\"")
                appendLine("model_identifier = \"$modelName\"")
                appendLine("name = \"$modelName\"")
                appendLine("api_provider = \"${provider.id}\"")
            }
        )
        botConfigs().forEach { config ->
            val text = config.readText()
            val replaced = Regex("model_list = \\[[^\\]]*]")
                .replace(text) { "model_list = [\"$modelName\"]" }
            // 嵌入/图片嵌入不能用对话模型，保持留空。
            config.writeText(
                replaced.replace(
                    Regex("(\\[model_task_config\\.(?:embedding|image_embedding)][^\\[]*)model_list = \\[[^\\]]*]"),
                ) { m -> m.groupValues[1] + "model_list = []" }
            )
        }
        "已绑定模型 $modelName"
    }.getOrNull()

    /** 把连接器装进每个 Bot 的插件目录，并给 NapCat 增加一条给 MaiBot 的正向 WS。 */
    private fun configureAdapter() {
        val adapterSource = File(appDir, "MaiBot-SnowLuma-Adapter-main")
        botDirs().forEach { bot ->
            val target = File(bot, "plugins/$ADAPTER_ID")
            runCatching {
                if (!target.isDirectory) adapterSource.copyRecursively(target, overwrite = true)
                File(target, "config.toml").writeText(
                    buildString {
                        appendLine("[plugin]")
                        appendLine("enabled = true")
                        appendLine("config_version = \"2.8.0\"")
                        appendLine()
                        appendLine("[client]")
                        appendLine("client_type = \"auto\"")
                        appendLine("server = \"127.0.0.1\"")
                        appendLine("port = $ADAPTER_WS_PORT")
                        appendLine("token = \"\"")
                    }
                )
            }.onFailure { FileLogger.w(TAG, "写入连接器配置失败", it) }
        }
        addNapCatWebSocket()
    }

    private fun addNapCatWebSocket() {
        val config = File(napCatBase, "data/config/onebot11.json")
        if (!config.isFile) return
        runCatching {
            val root = JSONObject(config.readText())
            val network = root.optJSONObject("network") ?: return@runCatching
            val servers = network.optJSONArray("websocketServers") ?: JSONArray().also {
                network.put("websocketServers", it)
            }
            val exists = (0 until servers.length()).any {
                servers.optJSONObject(it)?.optInt("port") == ADAPTER_WS_PORT
            }
            if (exists) return@runCatching
            servers.put(JSONObject().apply {
                put("name", "maibot-ws")
                put("enable", true)
                put("host", "127.0.0.1")
                put("port", ADAPTER_WS_PORT)
                put("token", "")
                put("reportSelfMessage", true)
                put("enableForcePushEvent", true)
                put("messagePostFormat", "array")
            })
            config.writeText(root.toString())
        }.onFailure { FileLogger.w(TAG, "写入 NapCat 网络配置失败", it) }
    }

    private fun botDirs(): List<File> =
        File(botDir, "bots").listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun botConfigs(): List<File> =
        botDirs().map { File(it, "config/model_config.toml") }.filter { it.isFile }

    private suspend fun runScript(script: String, onLog: (String) -> Unit, onProgress: (Float) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val reportLine: (String) -> Unit = { line ->
                val marker = line.substringAfter(PROGRESS_MARK, "")
                if (line.startsWith(PROGRESS_MARK)) {
                    marker.trim().toFloatOrNull()?.let { onProgress((it / 100f).coerceIn(0f, 1f)) }
                } else {
                    onLog(line)
                }
            }
            val tracked = engine.startTrackedStdioProcess(
                "/bin/bash",
                listOf("-c", script),
                null,
                "maibot:install",
                profile = napCatInstaller.profile(),
                inheritUserConfig = false,
            )
            try {
                coroutineScope {
                    tracked.process.outputStream.close()
                    val out = async {
                        tracked.process.inputStream.bufferedReader().useLines { lines -> lines.forEach(reportLine) }
                    }
                    val err = async {
                        tracked.process.errorStream.bufferedReader().useLines { lines -> lines.forEach(reportLine) }
                    }
                    val finished = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                        runInterruptible { tracked.process.waitFor() }
                    }
                    out.await()
                    err.await()
                    if (finished == null) {
                        tracked.process.destroyForcibly()
                        -1
                    } else {
                        tracked.process.exitValue()
                    }
                }
            } finally {
                tracked.clearLedger()
            }
        }

    private val INSTALL_SCRIPT = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        echo "@@P:5"
        command -v python3 >/dev/null 2>&1 || {
          apt-get update -o Acquire::ForceIPv4=true
          echo "@@P:10"
          apt-get install -y -qq --no-install-recommends python3 python3-venv python3-pip python3-dev build-essential ca-certificates
        }
        echo "@@P:20"
        mkdir -p $ROOT_GUEST
        cd $ROOT_GUEST
        [ -f MaiBot.zip ] || python3 -c "import urllib.request;urllib.request.urlretrieve('https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/maibot/MaiBot.zip','MaiBot.zip')" || python3 -c "import urllib.request;urllib.request.urlretrieve('https://gh-proxy.com/https://github.com/Mai-with-u/MaiBot/archive/refs/heads/main.zip','MaiBot.zip')"
        echo "@@P:35"
        [ -f Adapter.zip ] || python3 -c "import urllib.request;urllib.request.urlretrieve('https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/maibot/Adapter.zip','Adapter.zip')" || python3 -c "import urllib.request;urllib.request.urlretrieve('https://gh-proxy.com/https://github.com/Mai-with-u/MaiBot-SnowLuma-Adapter/archive/refs/heads/main.zip','Adapter.zip')"
        unzip -q -o MaiBot.zip
        unzip -q -o Adapter.zip
        echo "@@P:45"
        cd $MAIBOT_DIR
        [ -d .venv ] || python3 -m venv .venv
        echo "@@P:55"
        .venv/bin/pip install -q -i https://mirrors.aliyun.com/pypi/simple/ --upgrade pip
        echo "@@P:60"
        .venv/bin/pip install -q -i https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt
        echo "@@P:90"
        EULA_AGREE=8e6e7d647f7f82d6ea98456b73908656 PRIVACY_AGREE=91e5db7659c560bc3545e63859b6ebc0 timeout 40 .venv/bin/python bot.py || true
        echo "@@P:100"
        echo MAIBOT_INSTALL_DONE
    """.trimIndent()
}
