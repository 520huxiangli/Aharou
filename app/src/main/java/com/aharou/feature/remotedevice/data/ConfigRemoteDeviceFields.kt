package com.aharou.feature.remotedevice.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.remotedevice.RemoteDeviceController
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.server.RemoteServerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 「跨设备协作」→ 配置通道。
 *
 * 全部经 [RemoteDeviceController] 走——它是这项功能的统一入口（开关会一并启停服务、改端口会重启服务），
 * 绕过它直接写 [com.aharou.feature.remotedevice.domain.RemoteDeviceSettings] 会让服务状态和配置对不上。
 */
@Singleton
class ConfigRemoteDeviceFields @Inject constructor(
    private val controller: RemoteDeviceController,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            ClosureField(
                path = "remote.device.enabled",
                displayName = "开启跨设备协作",
                description = "本机作为 MCP 服务端，供已配对设备调用 read_file / write_file / shell / screenshot 等动作。" +
                    "开启即启动服务，关闭只停服务、保留已授权设备（要断某台设备走 remote.devices 的 remove）。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(state().config.enabled) },
                writer = { v ->
                    val enabled = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    runBlocking { controller.setEnabled(enabled) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.port",
                displayName = "端口",
                description = "服务监听端口，默认 47822（避开 debug 变体内置 MCP server 的 47821）。改动会重启服务。",
                valueSchema = ConfigSchema.Int(min = 1024, max = 65535),
                revertable = true,
                reader = { ConfigValue.Int(state().config.port) },
                writer = { v ->
                    val port = (v as? ConfigValue.Int)?.value
                        ?: throw ConfigError.TypeMismatch("integer")
                    runBlocking { controller.setPort(port) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.device_name",
                displayName = "设备名",
                description = "展示给对端的设备名；留空则用机型名。",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                revertable = true,
                reader = { ConfigValue.Str(state().config.deviceName) },
                writer = { v ->
                    val name = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    runBlocking { controller.setDeviceName(name) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.always_ask",
                displayName = "远端动作每次都需确认",
                description = "远端进来的动作即便被策略自动放行（如读文件）也强制弹一次审批。默认关闭——协助场景下用户常不在手机跟前，" +
                    "每次弹窗等于把功能废掉；介意时可打开。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(state().config.alwaysAskRemote) },
                writer = { v ->
                    val flag = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    runBlocking { controller.setAlwaysAskRemote(flag) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.allow_public",
                displayName = "允许公网对端",
                description = "关闭时只接受同一局域网/回环来源；开启后才接受跨网络（IPv6 全球单播等）的对端。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(state().config.allowPublicPeer) },
                writer = { v ->
                    val flag = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    runBlocking { controller.setAllowPublicPeer(flag) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.auto_pair_back",
                displayName = "一次配对即互通",
                description = "对端用配对码连上本机时，本机顺带回连一次、把对端也登记成本机的 MCP 服务器（双方各多一条授权），" +
                    "不用再反向配一次。回连地址只认对端的来源 IP。关闭后每次配对只开通一个方向。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(state().config.autoPairBack) },
                writer = { v ->
                    val flag = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    runBlocking { controller.setAutoPairBack(flag) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.allow_ask_ai",
                displayName = "允许已配对设备向本机 AI 提问",
                description = "开启后，已配对设备能用 ask_ai **以本机用户的口吻**把问题交给本机 AI——它会当成用户说的来回答。" +
                    "等于让它们借你的 AI 办事（它们本来就能 shell / write_file，这里多出来的是「AI 被指挥做你没打算的事」），故默认关闭；" +
                    "工具调用仍走本机权限引擎，该审批的照旧。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(state().config.allowRemoteAskAi) },
                writer = { v ->
                    val flag = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    runBlocking { controller.setAllowRemoteAskAi(flag) }
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.pair_code",
                displayName = "配对码",
                description = "念给对端的 6 位码（不做废，配对成功后自动换新）。读取时若还没有码会自动生成一枚；" +
                    "对端在「连接到另一台设备」里填 协助地址 + 这枚码。",
                valueSchema = ConfigSchema.Str(maxLength = 8),
                access = ConfigAccess.READONLY,
                revertable = false,
                reader = {
                    ConfigValue.Str(runBlocking { controller.livePairingCode()?.first ?: controller.pairingCode() })
                },
                writer = { throw ConfigError.PermissionDenied("配对码为只读字段（要换一枚需在对端配对成功后自动轮换）") },
            ),
        )

        registry.register(
            ClosureField(
                path = "remote.device.state",
                displayName = "服务状态",
                description = "服务是否在监听、端口，以及本机可被访问的协助地址（念给对端/生成二维码都用它）。",
                valueSchema = ConfigSchema.Str(maxLength = 4000),
                access = ConfigAccess.READONLY,
                revertable = false,
                reader = { ConfigValue.Str(describeState()) },
                writer = { throw ConfigError.PermissionDenied("服务状态为只读字段") },
            ),
        )

        registry.register(DeviceCollection())
    }

    private fun state() = runBlocking { controller.state.first() }

    private fun describeState(): String {
        val snapshot = state()
        val head = when (val server = snapshot.server) {
            is RemoteServerState.Running -> "running port=${server.port}"
            is RemoteServerState.Failed -> "failed ${server.message}"
            else -> "stopped"
        }
        val urls = snapshot.endpoints.joinToString(" ")
        return if (urls.isEmpty()) head else "$head | $urls"
    }

    private fun devices(): List<RemotePairedDevice> = runBlocking { controller.devices() }

    private fun device(id: String): RemotePairedDevice? = devices().firstOrNull { it.deviceId == id }

    private fun formatTime(epochMs: Long): String =
        if (epochMs <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    /** 设备里的一项；全部只读——授权由配对流程产生，撤销走集合的 remove。 */
    private fun deviceField(
        id: String,
        segment: String,
        label: String,
        desc: String,
        read: (RemotePairedDevice) -> String,
    ): ConfigField = ClosureField(
        path = "remote.devices.$id.$segment",
        displayName = "$label（$id）",
        description = desc,
        valueSchema = ConfigSchema.Str(),
        access = ConfigAccess.READONLY,
        revertable = false,
        reader = { ConfigValue.Str(device(id)?.let(read).orEmpty()) },
        writer = { throw ConfigError.PermissionDenied("$segment 为只读字段") },
    )

    private inner class DeviceCollection : ConfigCollection {
        override val basePath = "remote.devices"
        override val displayName = "已配对设备"
        override val description = "有权调用本机动作的远端设备。子字段 device_name / approved_at / last_seen_at / token_hint 全为只读；" +
            "remove 即撤销该设备的授权（下次调用会拿到 401，需重新配对）。"

        /** 授权只能由配对流程产生，不支持手动新增。 */
        override val addable = false

        override fun childIds(): List<String> = devices().map { it.deviceId }

        override fun fields(forId: String): List<ConfigField> {
            if (device(forId) == null) return emptyList()
            return listOf(
                deviceField(forId, "device_name", "设备名", "对端自报的名字。") { it.deviceName },
                deviceField(forId, "approved_at", "授权时间", "配对成功的时间。") { formatTime(it.approvedAt) },
                deviceField(forId, "last_seen_at", "最近调用", "该设备最近一次调用本机能力的时间。") { formatTime(it.lastSeenAt) },
                deviceField(forId, "token_hint", "令牌标识", "令牌前 4 位，仅用于区分是哪条授权。") { it.tokenHint },
            )
        }

        override fun add(payload: ConfigValue): String =
            throw ConfigError.InvalidValue("已配对设备由配对流程创建，不支持手动新增")

        override fun remove(id: String) {
            if (device(id) == null) throw ConfigError.InvalidValue("设备不存在：$id")
            val revoked = runBlocking { controller.revoke(id) }
            if (!revoked) throw ConfigError.InvalidValue("撤销失败：$id")
        }
    }
}
