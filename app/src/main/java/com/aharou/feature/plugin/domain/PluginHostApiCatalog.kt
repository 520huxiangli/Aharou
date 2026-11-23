package com.aharou.feature.plugin.domain

/** 宿主 API 的传输来源；决定方法可用性、工作区根与模型句柄的归属。 */
enum class PluginHostApiOrigin {
    PAGE,
    PROCESS,
    OVERLAY
}

/**
 * 宿主插件 API 的方法表。
 *
 * `when` 分支是实现事实，这张表是「哪个来源能调哪个方法」的声明：运行期按它做可用性拦截，
 * `scripts/check_plugin_host_api.py` 按它跟三处桥、页面 SDK 与插件包里的调用点对账。
 * 新增方法必须同时改这里和 `PluginHostApiDispatcher`，漏改会被脚本拦下。
 */
object PluginHostApiCatalog {

    /** 方法的废弃信息。`replacedBy` 与 `removedIn` 必填，逼着写清迁移路径。 */
    data class Deprecated(val replacedBy: String, val removedIn: String)

    data class HostMethod(
        val name: String,
        val origins: Set<PluginHostApiOrigin>,
        val deprecated: Deprecated? = null
    )

    private val PAGE_AND_PROCESS = setOf(PluginHostApiOrigin.PAGE, PluginHostApiOrigin.PROCESS)
    private val PAGE_AND_OVERLAY = setOf(PluginHostApiOrigin.PAGE, PluginHostApiOrigin.OVERLAY)
    private val ALL_ORIGINS = setOf(
        PluginHostApiOrigin.PAGE,
        PluginHostApiOrigin.PROCESS,
        PluginHostApiOrigin.OVERLAY
    )
    private val PAGE_ONLY = setOf(PluginHostApiOrigin.PAGE)
    private val PROCESS_ONLY = setOf(PluginHostApiOrigin.PROCESS)
    private val OVERLAY_ONLY = setOf(PluginHostApiOrigin.OVERLAY)

    private fun m(name: String, origins: Set<PluginHostApiOrigin>) = HostMethod(name, origins)

    private fun d(name: String, replacedBy: String, removedIn: String, origins: Set<PluginHostApiOrigin>) =
        HostMethod(name, origins, Deprecated(replacedBy, removedIn))

    val ALL: List<HostMethod> = listOf(
        m("fs.read", PAGE_AND_PROCESS),
        m("fs.write", PAGE_AND_PROCESS),
        m("fs.list", PAGE_AND_PROCESS),
        m("fs.delete", PAGE_AND_PROCESS),
        m("fs.stat", PAGE_AND_PROCESS),
        m("fs.mkdir", PAGE_AND_PROCESS),
        m("fs.rename", PAGE_AND_PROCESS),
        m("fs.copy", PAGE_AND_PROCESS),
        m("fs.append", PAGE_AND_PROCESS),
        m("fs.readBinary", PAGE_AND_PROCESS),
        m("fs.writeBinary", PAGE_AND_PROCESS),
        m("fs.edit", PAGE_AND_PROCESS),
        m("fs.readRange", PAGE_AND_PROCESS),
        m("fs.find", PAGE_AND_PROCESS),
        m("fs.grep", PAGE_AND_PROCESS),
        m("fs.unzip", PAGE_AND_PROCESS),
        m("fs.zip", PAGE_AND_PROCESS),
        m("fs.open", PROCESS_ONLY),
        m("fs.watch", PAGE_ONLY),
        m("fs.unwatch", PAGE_ONLY),

        m("app.isForeground", ALL_ORIGINS),
        m("app.info", PAGE_AND_PROCESS),
        m("app.start", PAGE_AND_PROCESS),
        m("app.stop", PAGE_AND_PROCESS),
        m("app.list", PAGE_AND_PROCESS),
        m("notification.list", PAGE_AND_PROCESS),
        m("system.settingsGet", PAGE_AND_PROCESS),
        m("system.settingsSet", PAGE_AND_PROCESS),
        m("system.installApp", PAGE_AND_PROCESS),
        m("system.uninstallApp", PAGE_AND_PROCESS),
        m("system.usage", PAGE_AND_PROCESS),
        m("intent.start", PAGE_AND_PROCESS),
        m("system.broadcast", PAGE_AND_PROCESS),
        m("system.location", PAGE_AND_PROCESS),
        m("intent.broadcast", PAGE_AND_PROCESS),

        m("device.tap", PROCESS_ONLY),
        m("device.longPress", PROCESS_ONLY),
        m("device.swipe", PROCESS_ONLY),
        m("device.key", PROCESS_ONLY),
        m("device.setText", PROCESS_ONLY),
        m("device.pageInfo", PROCESS_ONLY),
        m("device.screenshot", PROCESS_ONLY),
        m("bash", PAGE_AND_PROCESS),
        m("shizuku.exec", PAGE_AND_PROCESS),
        m("notify", PROCESS_ONLY),
        m("tip", PAGE_AND_PROCESS),

        m("overlay.createDsl", PROCESS_ONLY),
        m("overlay.create", PAGE_AND_PROCESS),
        m("overlay.update", PAGE_AND_PROCESS),
        m("overlay.close", ALL_ORIGINS),
        m("overlay.postMessage", ALL_ORIGINS),
        m("overlay.self", OVERLAY_ONLY),
        m("overlay.setVisibilityPolicy", OVERLAY_ONLY),
        m("overlay.setDragRegion", OVERLAY_ONLY),
        m("overlay.moveBy", OVERLAY_ONLY),
        m("overlay.resize", OVERLAY_ONLY),

        m("terminal.run", PAGE_AND_PROCESS),
        m("terminal.poll", PAGE_AND_PROCESS),
        m("terminal.stop", PAGE_AND_PROCESS),
        m("terminal.close", PAGE_AND_PROCESS),
        m("terminal.write", PAGE_AND_PROCESS),
        m("terminal.list", PAGE_AND_PROCESS),
        m("terminal.hiddenExec", PROCESS_ONLY),

        m("chat.listSessions", PAGE_AND_PROCESS),
        m("chat.createSession", PAGE_AND_PROCESS),
        m("chat.listMessages", PAGE_AND_PROCESS),
        m("chat.sendMessage", PAGE_AND_PROCESS),
        m("chat.sendMessageAndWait", PAGE_AND_PROCESS),
        m("chat.setDraft", PAGE_AND_PROCESS),
        m("chat.stop", PAGE_AND_PROCESS),
        m("chat.openSession", PAGE_AND_PROCESS),
        m("chat.getSession", PAGE_AND_PROCESS),
        m("chat.renameSession", PAGE_AND_PROCESS),
        m("chat.pinSession", PAGE_AND_PROCESS),
        m("chat.deleteSession", PAGE_AND_PROCESS),
        m("chat.getRunState", PAGE_AND_PROCESS),
        m("chat.waitForRun", PAGE_AND_PROCESS),
        m("chat.listApprovals", PAGE_AND_PROCESS),
        m("chat.resolveToolApproval", PAGE_AND_PROCESS),
        m("chat.resolveQuestion", PAGE_AND_PROCESS),
        m("chat.resolvePlanApproval", PAGE_AND_PROCESS),

        m("model.listProviders", PAGE_AND_PROCESS),
        m("model.listModels", PAGE_AND_PROCESS),
        m("model.create", PAGE_AND_PROCESS),
        m("model.close", PAGE_AND_PROCESS),
        m("model.start", PAGE_AND_PROCESS),
        m("model.read", PAGE_AND_PROCESS),
        m("model.cancel", PAGE_AND_PROCESS),

        m("clipboard.read", PAGE_AND_PROCESS),
        m("clipboard.write", PAGE_AND_PROCESS),
        m("share.text", PAGE_AND_PROCESS),
        m("ui.openUrl", PAGE_AND_PROCESS),
        m("ui.overlay", PAGE_ONLY),
        m("ui.windows", PROCESS_ONLY),
        m("ui.focusWindow", PROCESS_ONLY),
        m("package.readResource", PROCESS_ONLY),
        m("stt.transcribe", PROCESS_ONLY),
        m("chat.callModel", PROCESS_ONLY),
        m("catalog.toolSources", PROCESS_ONLY),
        m("net.websocket", PROCESS_ONLY),
        m("net.cookies", PROCESS_ONLY),
        m("media.register", PROCESS_ONLY),
        m("tts.speak", PROCESS_ONLY),
        m("ui.close", PAGE_ONLY),
        m("toast", PAGE_ONLY),
        m("cookies.get", PAGE_ONLY),

        m("log", PROCESS_ONLY),
        m("log.write", PAGE_ONLY),

        m("secrets.get", PAGE_AND_PROCESS),
        m("secrets.set", PAGE_AND_PROCESS),
        m("secrets.delete", PAGE_AND_PROCESS),
        m("secrets.list", PAGE_AND_PROCESS),

        m("env.get", PAGE_AND_PROCESS),
        m("env.set", PAGE_AND_PROCESS),
        m("env.list", PAGE_AND_PROCESS),

        m("tools.list", PAGE_AND_PROCESS),
        m("tools.execute", PAGE_AND_PROCESS),
        m("tools.pending", PAGE_AND_PROCESS),
        m("tools.resolve", PAGE_AND_PROCESS),
        m("agent.runSubAgent", PROCESS_ONLY),
        m("workflow.getAll", PAGE_AND_PROCESS),
        m("workflow.create", PAGE_AND_PROCESS),
        m("workflow.get", PAGE_AND_PROCESS),
        m("workflow.update", PAGE_AND_PROCESS),
        m("workflow.delete", PAGE_AND_PROCESS),
        m("workflow.setEnabled", PAGE_AND_PROCESS),
        m("workflow.trigger", PAGE_AND_PROCESS),
        m("workflow.registerTemplate", PAGE_AND_PROCESS),
        m("music.play", PAGE_AND_PROCESS),
        m("music.playQueue", PAGE_AND_PROCESS),
        m("music.pause", PAGE_AND_PROCESS),
        m("music.resume", PAGE_AND_PROCESS),
        m("music.stop", PAGE_AND_PROCESS),
        m("music.seek", PAGE_AND_PROCESS),
        m("music.setVolume", PAGE_AND_PROCESS),
        m("music.status", PAGE_AND_PROCESS),

        // 经典蓝牙（Operit 的 Tools.System.bluetooth）
        m("bluetooth.permission", PAGE_AND_PROCESS),
        m("bluetooth.state", PAGE_AND_PROCESS),
        m("bluetooth.requestEnable", PAGE_AND_PROCESS),
        m("bluetooth.bondedDevices", PAGE_AND_PROCESS),
        m("bluetooth.scan", PAGE_AND_PROCESS),
        m("bluetooth.connect", PAGE_AND_PROCESS),
        m("bluetooth.listen", PAGE_AND_PROCESS),
        m("bluetooth.accept", PAGE_AND_PROCESS),
        m("bluetooth.send", PAGE_AND_PROCESS),
        m("bluetooth.read", PAGE_AND_PROCESS),
        m("bluetooth.sendAndRead", PAGE_AND_PROCESS),
        m("bluetooth.close", PAGE_AND_PROCESS),

        // BLE（Operit 的 Tools.System.bluetooth.ble）
        m("ble.connect", PAGE_AND_PROCESS),
        m("ble.discoverServices", PAGE_AND_PROCESS),
        m("ble.readCharacteristic", PAGE_AND_PROCESS),
        m("ble.writeCharacteristic", PAGE_AND_PROCESS),
        m("ble.writeAndReadCharacteristic", PAGE_AND_PROCESS),
        m("ble.subscribe", PAGE_AND_PROCESS),
        m("ble.readNotifications", PAGE_AND_PROCESS),

        // 浏览器自动化（Operit 的 Tools.Net.browser* 与 startBrowser/stopBrowser）：驱动内置浏览器引擎
        m("browser.start", PROCESS_ONLY),
        m("browser.stop", PROCESS_ONLY),
        m("browser.close", PROCESS_ONLY),
        m("browser.closeAll", PROCESS_ONLY),
        m("browser.tabs", PROCESS_ONLY),
        m("browser.navigate", PROCESS_ONLY),
        m("browser.navigateBack", PROCESS_ONLY),
        m("browser.click", PROCESS_ONLY),
        m("browser.type", PROCESS_ONLY),
        m("browser.press", PROCESS_ONLY),
        m("browser.select", PROCESS_ONLY),
        m("browser.hover", PROCESS_ONLY),
        m("browser.resize", PROCESS_ONLY),
        m("browser.evaluate", PROCESS_ONLY),
        m("browser.waitFor", PROCESS_ONLY),
        m("browser.snapshot", PROCESS_ONLY),
        m("browser.screenshot", PROCESS_ONLY),
        m("browser.console", PROCESS_ONLY),
        m("browser.fillForm", PROCESS_ONLY),
        m("browser.handleDialog", PROCESS_ONLY),
        m("browser.networkRequests", PROCESS_ONLY),
        m("browser.drag", PROCESS_ONLY),
        m("browser.fileUpload", PROCESS_ONLY),
        m("browser.runCode", PROCESS_ONLY),
        m("browser.userscriptInstall", PROCESS_ONLY),
        m("browser.userscriptList", PROCESS_ONLY),
        m("browser.userscriptStart", PROCESS_ONLY),
        m("browser.userscriptStop", PROCESS_ONLY),
        m("browser.userscriptUninstall", PROCESS_ONLY),

        // 角色卡（Operit 的 Tools.SoftwareSettings / Tools.Chat 里的角色卡部分）
        m("characterCard.list", PAGE_AND_PROCESS),
        m("characterCard.get", PAGE_AND_PROCESS),
        m("characterCard.create", PAGE_AND_PROCESS),
        m("characterCard.update", PAGE_AND_PROCESS),
        m("characterCard.delete", PAGE_AND_PROCESS),
        m("characterCard.setActive", PAGE_AND_PROCESS),
        m("characterCard.clearActive", PAGE_AND_PROCESS),
        m("characterCard.importTavern", PAGE_AND_PROCESS),
        m("characterCard.exportTavern", PAGE_AND_PROCESS),

        // 软件设置（Operit 的 Tools.SoftwareSettings）
        m("softwareSettings.getSpeechServicesConfig", PAGE_AND_PROCESS),
        m("softwareSettings.setSpeechServicesConfig", PAGE_AND_PROCESS),
        m("softwareSettings.testTtsPlayback", PAGE_AND_PROCESS),
        m("softwareSettings.listModelConfigs", PAGE_AND_PROCESS),
        m("softwareSettings.createModelConfig", PAGE_AND_PROCESS),
        m("softwareSettings.updateModelConfig", PAGE_AND_PROCESS),
        m("softwareSettings.deleteModelConfig", PAGE_AND_PROCESS),
        m("softwareSettings.listFunctionModelConfigs", PAGE_AND_PROCESS),
        m("softwareSettings.getFunctionModelConfig", PAGE_AND_PROCESS),
        m("softwareSettings.setFunctionModelConfig", PAGE_AND_PROCESS),
        m("softwareSettings.testModelConfigConnection", PAGE_AND_PROCESS),
        m("softwareSettings.listSandboxPackages", PAGE_AND_PROCESS),
        m("softwareSettings.setSandboxPackageEnabled", PAGE_AND_PROCESS),
        m("softwareSettings.executeSandboxScriptDirect", PAGE_AND_PROCESS),
        m("softwareSettings.restartMcpWithLogs", PAGE_AND_PROCESS),

        // Tasker 事件（Operit 的 Tools.Tasker）：Aharou 没有自定义事件触发，方法存在但明确报 UNSUPPORTED
        m("tasker.triggerEvent", PAGE_AND_PROCESS),

        m("memory.query", PAGE_AND_PROCESS),
        m("memory.getByTitle", PAGE_AND_PROCESS),
        m("memory.create", PAGE_AND_PROCESS),
        m("memory.update", PAGE_AND_PROCESS),
        m("memory.deleteMemory", PAGE_AND_PROCESS),
        m("memory.move", PAGE_AND_PROCESS),
        m("memory.link", PAGE_AND_PROCESS),
        m("memory.queryLinks", PAGE_AND_PROCESS),
        m("memory.updateLink", PAGE_AND_PROCESS),
        m("memory.deleteLink", PAGE_AND_PROCESS),

        m("mcp.status", PAGE_AND_PROCESS),
        m("http.request", PAGE_AND_PROCESS),

        m("ports.forward", PROCESS_ONLY),
        m("ports.close", PROCESS_ONLY),
        m("ports.list", PROCESS_ONLY),

        m("plugin.invoke", PAGE_ONLY),

        m("context", PAGE_AND_OVERLAY)
    )

    private val BY_NAME = ALL.associateBy { it.name }

    init {
        // 表里重复的方法名会让脚本与运行期都看不出问题，这里直接暴露出来。
        require(BY_NAME.size == ALL.size) { "宿主 API 方法表有重复方法名" }
    }

    fun find(name: String): HostMethod? = BY_NAME[name]

    fun names(): Set<String> = BY_NAME.keys

    fun deprecated(): List<HostMethod> = ALL.filter { it.deprecated != null }
}
