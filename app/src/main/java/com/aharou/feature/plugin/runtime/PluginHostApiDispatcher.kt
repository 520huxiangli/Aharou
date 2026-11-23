package com.aharou.feature.plugin.runtime

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.plugin.character.PluginCharacterCardService
import com.aharou.feature.plugin.domain.PluginHostApiCatalog
import com.aharou.feature.plugin.domain.PluginHostApiOrigin
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.memory.PluginMemoryService
import com.aharou.feature.plugin.overlay.PluginOverlayException
import com.aharou.feature.plugin.overlay.PluginOverlayManager
import com.aharou.feature.plugin.workflow.WorkflowHostApi
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 页面与插件进程共用的宿主能力分发器。
 *
 * 各传输桥只负责协议编解码、生命周期与自身专属能力（页面上下文、文件订阅、浮层窗口等），
 * 文件、会话、模型、工具、终端等公共能力在这里完成方法归一化、参数组装、业务调用与错误码转换。
 * 迁移时刻意保留了两侧原有的 `METHOD_UNKNOWN` 文案：插件作者是按文案排查问题的。
 */
@Singleton
class PluginHostApiDispatcher @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val commandEngine: CommandEngine,
    private val notifier: PluginNotifier,
    private val terminalRunner: PluginTerminalRunner,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    private val overlayManager: PluginOverlayManager,
    private val chatService: PluginChatService,
    private val modelService: PluginModelService,
    private val secretStore: PluginSecretStore,
    private val systemService: PluginSystemService,
    private val introspectionService: PluginIntrospectionService,
    private val toolService: PluginToolService,
    private val httpService: PluginHttpService,
    private val portService: PluginPortService,
    private val envStore: PluginEnvStore,
    private val fsService: PluginFsService,
    private val deviceService: PluginDeviceService,
    private val appService: PluginAppService,
    private val webSocketService: PluginWebSocketService,
    private val catalogService: PluginCatalogService,
    private val modelCallService: PluginModelCallService,
    private val sttService: PluginSttService,
    private val agentService: PluginAgentService,
    private val musicService: PluginMusicService,
    private val memoryService: PluginMemoryService,
    private val characterCardService: PluginCharacterCardService,
    private val softwareSettingsService: PluginSoftwareSettingsService,
    private val bluetoothService: PluginBluetoothService,
    private val browserService: PluginBrowserService,
    private val workflowHostApi: WorkflowHostApi
) {

    /**
     * 分发一次宿主调用。
     *
     * [workspaceRoot] 是调用方视角的工作区根：页面用页面所属的工作区，进程用自己的执行环境。
     * [modelOrigin] 决定模型句柄的生命周期归属：页面传 `page:<缓存键>`，进程传 `process`。
     */
    suspend fun dispatch(
        pluginId: String,
        method: String,
        params: JsonObject,
        origin: PluginHostApiOrigin,
        workspaceRoot: () -> String,
        modelOrigin: String
    ): JsonElement = try {
        route(pluginId, method, params, origin, workspaceRoot, modelOrigin)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PluginHostException) {
        throw e
    } catch (e: PluginProcessException) {
        throw PluginHostException(e.code, e.message)
    } catch (e: PluginOverlayException) {
        throw PluginHostException(e.code, e.message.orEmpty())
    } catch (e: PluginTerminalException) {
        throw PluginHostException(e.code, e.message)
    } catch (e: PluginShizukuException) {
        throw PluginHostException(e.code, e.message.orEmpty())
    } catch (e: PluginTipException) {
        throw PluginHostException(e.code, e.message.orEmpty())
    }

    private suspend fun route(
        pluginId: String,
        method: String,
        params: JsonObject,
        origin: PluginHostApiOrigin,
        workspaceRoot: () -> String,
        modelOrigin: String
    ): JsonElement {
        val name = normalize(origin, method)
        val spec = PluginHostApiCatalog.find(name)
        if (spec == null || origin !in spec.origins) throw unknownMethod(origin, method)
        val paths = PluginHostPaths(
            fileAccess = fileAccess,
            homeResolver = homeResolver,
            workspaceRoot = workspaceRoot,
            pluginId = pluginId
        )
        return when (name) {
            "fs.read" -> paths.read(PluginHostPaths.requireString(params, "path"))
            "fs.write" -> paths.write(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "content")
            )
            "fs.list" -> paths.list(PluginHostPaths.requireString(params, "path"))
            "fs.delete" -> paths.delete(PluginHostPaths.requireString(params, "path"))
            "fs.stat" -> paths.stat(PluginHostPaths.requireString(params, "path"))
            "fs.mkdir" -> paths.mkdir(PluginHostPaths.requireString(params, "path"))
            "fs.rename" -> paths.rename(
                PluginHostPaths.requireString(params, "from"),
                PluginHostPaths.requireString(params, "to")
            )
            "fs.copy" -> paths.copy(
                PluginHostPaths.requireString(params, "from"),
                PluginHostPaths.requireString(params, "to"),
                (params["overwrite"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
            "fs.append" -> paths.append(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "content")
            )
            "fs.readBinary" -> paths.readBinary(PluginHostPaths.requireString(params, "path"))
            "fs.writeBinary" -> paths.writeBinary(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "base64")
            )
            "fs.edit" -> fsService.edit(paths, params)
            "fs.readRange" -> fsService.readRange(paths, params)
            "fs.find" -> fsService.find(paths, params)
            "fs.grep" -> fsService.grep(paths, params)
            "fs.unzip" -> fsService.unzip(paths, params)
            "fs.zip" -> fsService.zip(paths, params)
            "fs.open" -> appService.openFile(pluginId, params)
            "net.cookies" -> appService.cookies(pluginId, params)
            "catalog.toolSources" -> catalogService.readToolSources()
            "chat.callModel" -> modelCallService.call(pluginId, params)
            "package.readResource" -> appService.readResourceFromPackage(pluginId, params)
            "stt.transcribe" -> sttService.transcribe(params)
            "net.websocket" -> webSocketService.dispatch(pluginId, params)
            "media.register" -> appService.registerMedia(pluginId, params)
            "tts.speak" -> appService.speak(pluginId, params)
            "ui.windows" -> appService.listWindows(pluginId)
            "ui.focusWindow" -> appService.focusWindow(pluginId, params)

            "app.isForeground" -> JsonPrimitive(overlayManager.isAppForeground())
            "app.info" -> systemService.appInfo(
                pluginId,
                (params["package"] as? JsonPrimitive)?.contentOrNull
            )
            "app.start" -> appService.startApp(pluginId, params)
            "app.stop" -> appService.stopApp(pluginId, params)
            "app.list" -> appService.listApps(pluginId, params)
            "notification.list" -> appService.listNotifications(pluginId, params)
            "system.settingsGet" -> appService.settingsGet(pluginId, params)
            "system.settingsSet" -> appService.settingsSet(pluginId, params)
            "intent.start" -> appService.startIntent(pluginId, params)
            "system.usage" -> appService.getAppUsageTime(pluginId, params)
            "system.installApp" -> appService.installApp(pluginId, params)
            "system.uninstallApp" -> appService.uninstallApp(pluginId, params)
            "system.location" -> appService.getLocation(pluginId, params)
            "system.broadcast" -> appService.sendBroadcast(pluginId, params)
            "intent.broadcast" -> appService.broadcastIntent(pluginId, params)

            "device.tap" -> deviceService.tap(pluginId, params)
            "device.longPress" -> deviceService.longPress(pluginId, params)
            "device.swipe" -> deviceService.swipe(pluginId, params)
            "device.key" -> deviceService.key(pluginId, params)
            "device.setText" -> deviceService.setText(pluginId, params)
            "device.pageInfo" -> deviceService.pageInfo(pluginId)
            "device.screenshot" -> deviceService.screenshot(pluginId)
            "bash" -> bash(params, workspaceRoot)
            "shizuku.exec" -> shizukuExec(pluginId, params)
            "notify" -> notifier.notify(pluginId, params)
            "tip" -> tipManager.show(pluginId, params)

            "overlay.create" -> overlayManager.create(
                pluginId,
                (params["surface"] as? JsonPrimitive)?.contentOrNull,
                params
            )
            "overlay.createDsl" -> overlayManager.createDsl(
                pluginId,
                PluginHostPaths.requireString(params, "overlayId"),
                PluginHostPaths.requireString(params, "route"),
                params
            )
            "overlay.update" -> overlayManager.update(
                pluginId,
                PluginHostPaths.requireString(params, "overlayId"),
                params
            )
            "overlay.close" -> overlayClose(pluginId, params, origin)
            "overlay.postMessage" -> overlayPostMessage(pluginId, params, origin)

            "terminal.run" -> terminalRun(pluginId, params)
            "terminal.poll" -> terminalPoll(pluginId, params)
            "terminal.stop" -> terminalStop(pluginId, params, close = false)
            "terminal.close" -> terminalStop(pluginId, params, close = true)
            "terminal.write" -> terminalWrite(pluginId, params)
            "terminal.list" -> terminalRunner.list(pluginId)
            "terminal.hiddenExec" -> terminalHiddenExec(pluginId, params, workspaceRoot)

            "chat.listSessions" -> chatService.listSessions(pluginId, params)
            "chat.createSession" -> chatService.createSession(pluginId, params)
            "chat.listMessages" -> chatService.listMessages(pluginId, params)
            "chat.sendMessage" -> chatService.sendMessage(pluginId, params)
            "chat.sendMessageAndWait" -> chatService.sendMessageAndWait(pluginId, params)
            "chat.setDraft" -> chatService.setDraft(pluginId, params)
            "chat.stop" -> chatService.stop(pluginId, params)
            "chat.openSession" -> chatService.openSession(pluginId, params)
            "chat.getSession" -> chatService.getSession(pluginId, params)
            "chat.renameSession" -> chatService.renameSession(pluginId, params)
            "chat.pinSession" -> chatService.pinSession(pluginId, params)
            "chat.deleteSession" -> chatService.deleteSession(pluginId, params)
            "chat.getRunState" -> chatService.getRunState(pluginId, params)
            "chat.waitForRun" -> chatService.waitForRun(pluginId, params)
            "chat.listApprovals" -> chatService.listApprovals(pluginId, params)
            "chat.resolveToolApproval" -> chatService.resolveToolApproval(pluginId, params)
            "chat.resolveQuestion" -> chatService.resolveQuestion(pluginId, params)
            "chat.resolvePlanApproval" -> chatService.resolvePlanApproval(pluginId, params)

            "model.listProviders" -> modelService.listProviders(pluginId)
            "model.listModels" -> modelService.listModels(pluginId, params)
            "model.create" -> modelService.create(pluginId, params)
            "model.close" -> modelService.close(pluginId, params)
            "model.start" -> modelService.start(pluginId, params, modelOrigin)
            "model.read" -> modelService.read(pluginId, params)
            "model.cancel" -> modelService.cancel(pluginId, params)

            "clipboard.read" -> systemService.clipboardRead(pluginId)
            "clipboard.write" -> systemService.clipboardWrite(
                pluginId,
                PluginHostPaths.requireString(params, "text")
            )
            "share.text" -> systemService.shareText(
                pluginId,
                PluginHostPaths.requireString(params, "text"),
                (params["title"] as? JsonPrimitive)?.contentOrNull
            )
            "ui.openUrl" -> systemService.openUrl(
                pluginId,
                PluginHostPaths.requireString(params, "url")
            )
            // 页面记 log.write、进程记 log：两个名字历史上就各走一边，合并会多出新的可用方法。
            "log", "log.write" -> systemService.log(
                pluginId,
                PluginHostPaths.requireString(params, "level"),
                PluginHostPaths.requireString(params, "message"),
                (params["tag"] as? JsonPrimitive)?.contentOrNull
            )

            "secrets.get" -> secretStore.get(pluginId, PluginHostPaths.requireString(params, "key"))
            "secrets.set" -> secretStore.set(
                pluginId,
                PluginHostPaths.requireString(params, "key"),
                PluginHostPaths.requireString(params, "value")
            )
            "secrets.delete" -> secretStore.delete(pluginId, PluginHostPaths.requireString(params, "key"))
            "secrets.list" -> secretStore.list(pluginId)

            "env.get" -> envStore.get(
                pluginId,
                PluginHostPaths.requireString(params, "key"),
                (params["default"] as? JsonPrimitive)?.contentOrNull
            )
            "env.set" -> envStore.set(
                pluginId,
                PluginHostPaths.requireString(params, "key"),
                PluginHostPaths.requireString(params, "value")
            )
            "env.list" -> envStore.list(
                pluginId,
                (params["prefix"] as? JsonPrimitive)?.contentOrNull
            )

            "tools.list" -> introspectionService.listTools(
                pluginId,
                includeSchema = (params["includeSchema"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
            "tools.execute" -> toolService.execute(pluginId, params)
            "tools.pending" -> toolService.pending(pluginId, params)
            "tools.resolve" -> toolService.resolve(pluginId, params)
            "mcp.status" -> introspectionService.mcpStatus(pluginId)
            "http.request" -> httpService.request(pluginId, params)
            "agent.runSubAgent" -> agentService.runSubAgent(pluginId, params)

            "music.play" -> musicService.play(pluginId, params)
            "music.playQueue" -> musicService.playQueue(pluginId, params)
            "music.pause" -> musicService.pause(pluginId, params)
            "music.resume" -> musicService.resume(pluginId, params)
            "music.stop" -> musicService.stop(pluginId, params)
            "music.seek" -> musicService.seek(pluginId, params)
            "music.setVolume" -> musicService.setVolume(pluginId, params)
            "music.status" -> musicService.status(pluginId, params)

            "memory.query" -> memoryService.query(pluginId, params)
            "memory.getByTitle" -> memoryService.getByTitle(pluginId, params)
            "memory.create" -> memoryService.create(pluginId, params)
            "memory.update" -> memoryService.update(pluginId, params)
            "memory.deleteMemory" -> memoryService.deleteMemory(pluginId, params)
            "memory.move" -> memoryService.move(pluginId, params)
            "memory.link" -> memoryService.link(pluginId, params)
            "memory.queryLinks" -> memoryService.queryLinks(pluginId, params)
            "memory.updateLink" -> memoryService.updateLink(pluginId, params)
            "memory.deleteLink" -> memoryService.deleteLink(pluginId, params)

            "characterCard.list" -> characterCardService.list(pluginId, params)
            "characterCard.get" -> characterCardService.get(pluginId, params)
            "characterCard.create" -> characterCardService.create(pluginId, params)
            "characterCard.update" -> characterCardService.update(pluginId, params)
            "characterCard.delete" -> characterCardService.delete(pluginId, params)
            "characterCard.setActive" -> characterCardService.setActive(pluginId, params)
            "characterCard.clearActive" -> characterCardService.clearActive(pluginId, params)
            "characterCard.importTavern" -> characterCardService.importTavern(pluginId, params)
            "characterCard.exportTavern" -> characterCardService.exportTavern(pluginId, params)

            "softwareSettings.getSpeechServicesConfig" ->
                softwareSettingsService.getSpeechServicesConfig(pluginId, params)
            "softwareSettings.setSpeechServicesConfig" ->
                softwareSettingsService.setSpeechServicesConfig(pluginId, params)
            "softwareSettings.testTtsPlayback" -> softwareSettingsService.testTtsPlayback(pluginId, params)
            "softwareSettings.listModelConfigs" -> softwareSettingsService.listModelConfigs(pluginId, params)
            "softwareSettings.createModelConfig" -> softwareSettingsService.createModelConfig(pluginId, params)
            "softwareSettings.updateModelConfig" -> softwareSettingsService.updateModelConfig(pluginId, params)
            "softwareSettings.deleteModelConfig" -> softwareSettingsService.deleteModelConfig(pluginId, params)
            "softwareSettings.listFunctionModelConfigs" ->
                softwareSettingsService.listFunctionModelConfigs(pluginId, params)
            "softwareSettings.getFunctionModelConfig" ->
                softwareSettingsService.getFunctionModelConfig(pluginId, params)
            "softwareSettings.setFunctionModelConfig" ->
                softwareSettingsService.setFunctionModelConfig(pluginId, params)
            "softwareSettings.testModelConfigConnection" ->
                softwareSettingsService.testModelConfigConnection(pluginId, params)
            "softwareSettings.listSandboxPackages" ->
                softwareSettingsService.listSandboxPackages(pluginId, params)
            "softwareSettings.setSandboxPackageEnabled" ->
                softwareSettingsService.setSandboxPackageEnabled(pluginId, params)
            "softwareSettings.executeSandboxScriptDirect" ->
                softwareSettingsService.executeSandboxScriptDirect(pluginId, params)
            "softwareSettings.restartMcpWithLogs" ->
                softwareSettingsService.restartMcpWithLogs(pluginId, params)

            "bluetooth.permission" -> bluetoothService.permission(pluginId, params)
            "bluetooth.state" -> bluetoothService.state(pluginId, params)
            "bluetooth.requestEnable" -> bluetoothService.requestEnable(pluginId, params)
            "bluetooth.bondedDevices" -> bluetoothService.bondedDevices(pluginId, params)
            "bluetooth.scan" -> bluetoothService.scan(pluginId, params)
            "bluetooth.connect" -> bluetoothService.connect(pluginId, params)
            "bluetooth.listen" -> bluetoothService.listen(pluginId, params)
            "bluetooth.accept" -> bluetoothService.accept(pluginId, params)
            "bluetooth.send" -> bluetoothService.send(pluginId, params)
            "bluetooth.read" -> bluetoothService.read(pluginId, params)
            "bluetooth.sendAndRead" -> bluetoothService.sendAndRead(pluginId, params)
            "bluetooth.close" -> bluetoothService.close(pluginId, params)

            "ble.connect" -> bluetoothService.bleConnect(pluginId, params)
            "ble.discoverServices" -> bluetoothService.bleDiscoverServices(pluginId, params)
            "ble.readCharacteristic" -> bluetoothService.bleReadCharacteristic(pluginId, params)
            "ble.writeCharacteristic" -> bluetoothService.bleWriteCharacteristic(pluginId, params)
            "ble.writeAndReadCharacteristic" ->
                bluetoothService.bleWriteAndReadCharacteristic(pluginId, params)
            "ble.subscribe" -> bluetoothService.bleSubscribe(pluginId, params)
            "ble.readNotifications" -> bluetoothService.bleReadNotifications(pluginId, params)

            // 浏览器自动化（Operit 的 Tools.Net.browser*）：全部交同一个服务，参数与错误码在那里归一
            "browser.start", "browser.stop", "browser.close", "browser.closeAll",
            "browser.tabs", "browser.navigate", "browser.navigateBack", "browser.click",
            "browser.type", "browser.press", "browser.select", "browser.hover",
            "browser.resize", "browser.evaluate", "browser.waitFor", "browser.snapshot",
            "browser.screenshot", "browser.console", "browser.fillForm", "browser.handleDialog",
            "browser.networkRequests", "browser.drag", "browser.fileUpload", "browser.runCode",
            "browser.userscriptInstall", "browser.userscriptList", "browser.userscriptStart",
            "browser.userscriptStop", "browser.userscriptUninstall" ->
                browserService.handle(pluginId, name, params, paths)

            // Aharou 的事件触发只认通知 / 开机 / 电量 / 网络 / 应用启动，没有插件自定义事件
            "tasker.triggerEvent" -> throw PluginHostException(
                "UNSUPPORTED",
                "tasker.triggerEvent 不受支持：Aharou 的事件触发只有通知 / 开机 / 电量 / 网络 / 应用启动五类，" +
                    "不接受插件自定义事件。要跑一段流程用 workflow.trigger（先 create 一个工作流），" +
                    "要让 AI 干活用 tools.execute 或 chat.sendMessageAndWait"
            )

            "workflow.getAll",
            "workflow.create",
            "workflow.get",
            "workflow.update",
            "workflow.delete",
            "workflow.setEnabled",
            "workflow.trigger",
            "workflow.registerTemplate" -> workflowHostApi.handle(name, params)

            "ports.forward" -> portService.forward(pluginId, params)
            "ports.close" -> portService.close(pluginId, params)
            "ports.list" -> portService.list(pluginId)

            else -> throw unknownMethod(origin, method)
        }
    }

    /** 进程侧方法名带 `host.` 前缀，页面侧不带；报错文案沿用调用方送来的原始方法名。 */
    private fun normalize(origin: PluginHostApiOrigin, method: String): String =
        if (origin == PluginHostApiOrigin.PROCESS && method.startsWith(METHOD_PREFIX)) {
            method.removePrefix(METHOD_PREFIX)
        } else {
            method
        }

    private fun unknownMethod(origin: PluginHostApiOrigin, method: String): PluginHostException =
        PluginHostException(
            "METHOD_UNKNOWN",
            if (origin == PluginHostApiOrigin.PROCESS) {
                "unknown host method: $method"
            } else {
                "unknown method: $method"
            }
        )

    /**
     * 浮层关闭与发消息在两处语义不同：页面只认 `overlayId`，插件进程还能按 `surface` 定位。
     * 文案分开保留，不合并成一句，避免插件作者看到的报错变样。
     */
    private fun overlayClose(
        pluginId: String,
        params: JsonObject,
        origin: PluginHostApiOrigin
    ): JsonElement {
        if (origin != PluginHostApiOrigin.PROCESS) {
            return overlayManager.close(pluginId, PluginHostPaths.requireString(params, "overlayId"))
        }
        val overlayId = (params["overlayId"] as? JsonPrimitive)?.contentOrNull
        val surfaceId = (params["surface"] as? JsonPrimitive)?.contentOrNull
        return when {
            !overlayId.isNullOrBlank() -> overlayManager.close(pluginId, overlayId)
            !surfaceId.isNullOrBlank() -> overlayManager.closeBySurface(pluginId, surfaceId)
            else -> throw PluginHostException("PARAM_INVALID", "overlayId or surface required")
        }
    }

    private fun overlayPostMessage(
        pluginId: String,
        params: JsonObject,
        origin: PluginHostApiOrigin
    ): JsonElement {
        val payload = params["payload"] ?: JsonNull
        if (origin != PluginHostApiOrigin.PROCESS) {
            return overlayManager.postMessage(
                pluginId,
                PluginHostPaths.requireString(params, "overlayId"),
                payload
            )
        }
        val overlayId = (params["overlayId"] as? JsonPrimitive)?.contentOrNull
        val surfaceId = (params["surface"] as? JsonPrimitive)?.contentOrNull
        return when {
            !overlayId.isNullOrBlank() -> overlayManager.postMessage(pluginId, overlayId, payload)
            !surfaceId.isNullOrBlank() -> overlayManager.postMessageBySurface(pluginId, surfaceId, payload)
            else -> throw PluginHostException("PARAM_INVALID", "overlayId or surface required")
        }
    }

    private suspend fun shizukuExec(pluginId: String, params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: DEFAULT_SHIZUKU_TIMEOUT_SECONDS
        val result = shizukuRunner.exec(pluginId, command, timeoutSeconds)
        return buildJsonObject {
            put("output", result.output)
            put("exitCode", result.exitCode)
            put("truncated", result.truncated)
        }
    }

    private suspend fun bash(params: JsonObject, workspaceRoot: () -> String): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        if (command.isBlank()) throw PluginHostException("PARAM_INVALID", "empty command")
        if (command.length > PluginHostPaths.MAX_COMMAND_CHARS) {
            throw PluginHostException("PARAM_INVALID", "command too long")
        }
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: PluginHostPaths.DEFAULT_BASH_TIMEOUT_SECONDS
        val timeoutMs = timeoutSeconds.coerceIn(1L, PluginHostPaths.MAX_BASH_TIMEOUT_SECONDS) * 1000L
        val result = commandEngine.runCommandSyncWithExit(command, workspaceRoot(), timeoutMs)
        return buildJsonObject {
            put("output", result.output)
            put("exitCode", result.exitCode)
            put("truncated", result.outputTruncated)
        }
    }

    private suspend fun terminalRun(pluginId: String, params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command").trim()
        val title = (params["title"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TERMINAL_TITLE_CHARS)
        val tabId = terminalRunner.start(pluginId, command, title)
        return buildJsonObject {
            put("tabId", tabId)
            put("state", "running")
        }
    }

    private suspend fun terminalPoll(pluginId: String, params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val offset = (params["offset"] as? JsonPrimitive)?.longOrNull ?: 0L
        val poll = terminalRunner.poll(pluginId, tabId, offset)
        return buildJsonObject {
            put("output", poll.output)
            put("offset", poll.offset)
            put("state", poll.state)
            poll.exitCode?.let { put("exitCode", it) }
            put("truncated", poll.truncated)
        }
    }

    private suspend fun terminalStop(pluginId: String, params: JsonObject, close: Boolean): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        if (close) terminalRunner.close(pluginId, tabId) else terminalRunner.stop(pluginId, tabId)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun terminalWrite(pluginId: String, params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val text = PluginHostPaths.requireString(params, "text")
        val appendNewline = (params["appendNewline"] as? JsonPrimitive)?.booleanOrNull ?: true
        val written = terminalRunner.write(pluginId, tabId, text, appendNewline)
        return buildJsonObject { put("ok", written) }
    }

    private suspend fun terminalHiddenExec(
        pluginId: String,
        params: JsonObject,
        workspaceRoot: () -> String
    ): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: PluginHostPaths.DEFAULT_BASH_TIMEOUT_SECONDS
        return terminalRunner.hiddenExec(pluginId, command, workspaceRoot(), timeoutSeconds)
    }

    private companion object {
        const val METHOD_PREFIX = "host."
        const val MAX_TERMINAL_TITLE_CHARS = 120
        const val DEFAULT_SHIZUKU_TIMEOUT_SECONDS = 120L
    }
}
