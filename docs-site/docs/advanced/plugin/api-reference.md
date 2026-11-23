# 宿主方法总览

插件能调用的宿主能力只有一份清单：当前共 **179 个方法**，下表按域分组列出方法名与可调用来源，参数细节见各专题页；方法增删以宿主实现为准，本文跟着更新。

## 怎么调用

| 插件形态 | 写法 | 例子 |
| --- | --- | --- |
| HTML 页面 / 浮层页面 | `window.aharou.*`，方法名不带前缀 | `await aharou.fs.read('data://config.json')` |
| 插件进程（node / python） | 按行 JSON-RPC，方法名带 `host.` 前缀 | `host.fs.read`、`host.chat.sendMessage` |

页面桥由宿主注入（`/__aharou/aharou.js`），进程侧自己做 JSON-RPC 收发。两条路的语义一致：都返回 Promise（进程侧是一次请求-响应），失败一律带 `{code, message}`。

「来源」列表示该方法在哪些来源可用：**页** = 插件页面，**进** = 插件进程，**浮** = 系统浮层页面。调用不可用的来源会得到 `METHOD_UNKNOWN`。

## 文件系统（18）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `fs.read` | 页 进 | 读文本，单次上限 512 KiB |
| `fs.write` | 页 进 | 原子写入，上限 1 MiB |
| `fs.append` | 页 进 | 追加文本，文件总量上限 1 MiB |
| `fs.list` | 页 进 | 列目录，最多 500 条 |
| `fs.stat` | 页 进 | 存在性、是否目录、大小 |
| `fs.mkdir` | 页 进 | 建目录（含父目录） |
| `fs.delete` | 页 进 | 删除文件或目录 |
| `fs.rename` | 页 进 | 移动/重命名，目标存在返回 `FILE_EXISTS` |
| `fs.copy` | 页 进 | 复制，`overwrite` 默认 false |
| `fs.readBinary` / `fs.writeBinary` | 页 进 | Base64 二进制读写 |
| `fs.edit` | 页 进 | 按旧文本替换，未命中返回 `EDIT_NOT_FOUND` |
| `fs.readRange` | 页 进 | 按行区间读取（1 基闭区间） |
| `fs.find` | 页 进 | 按通配符/名字查找 |
| `fs.grep` | 页 进 | 内容检索 |
| `fs.unzip` | 页 进 | 解压包内归档 |
| `fs.watch` / `fs.unwatch` | 页 | 订阅文件变化，回调 `fs.changed` |

路径是当前执行环境的真实路径：本地模式是容器内路径，SSH 模式是远端路径。`~` 展开到当前 home，裸相对路径挂到工作区根，插件私有数据用稳定的 `data://` 虚拟根。详见[插件页面与宿主接口](/advanced/plugin/ui-pages)。

## 应用、系统与设备（17）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `app.isForeground` | 页 进 浮 | App 是否有 Activity 在前台 |
| `app.info` | 页 进 | 包名、版本、SDK、语言、前后台状态 |
| `app.start` / `app.stop` | 页 进 | 启动/停止其它应用 |
| `app.list` | 页 进 | 已安装应用列表 |
| `notification.list` | 页 进 | 读取通知 |
| `system.settingsGet` / `system.settingsSet` | 页 进 | 读写系统设置项，需要 Shizuku 授权 |
| `intent.start` / `intent.broadcast` | 页 进 | 发 Intent 或广播 |
| `device.tap` / `device.longPress` / `device.swipe` | 进 | 点按、长按、滑动，依赖无障碍服务 |
| `device.key` / `device.setText` | 进 | 按键、向焦点框写文字 |
| `device.pageInfo` / `device.screenshot` | 进 | 读当前界面结构、截屏（Base64） |

设备类方法只对进程开放，细节与报错见[设备能力与系统工具](/advanced/plugin/device-tools)。

## 命令、终端与端口（12）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `bash` | 页 进 | 一次性命令，返回 `{output, exitCode, truncated}` |
| `terminal.run` / `terminal.poll` / `terminal.write` | 页 进 | 共享终端标签的创建、增量读、写入 |
| `terminal.stop` / `terminal.close` / `terminal.list` | 页 进 | 中断、关闭、列出自己的标签 |
| `terminal.hiddenExec` | 进 | 后台执行，不在终端页显示 |
| `shizuku.exec` | 页 进 | 经 Shizuku 在 Android 系统执行命令，需插件级授权 |
| `ports.forward` / `ports.close` / `ports.list` | 进 | 把服务器端口映射到手机 loopback |

`bash` 默认 30 秒、上限 300 秒，适合版本检测与状态查询；常驻或交互式程序用 `terminal.*`。

## 会话与消息（18）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `chat.listSessions` / `chat.getSession` | 页 进 | 列会话（`limit` 上限 200）、取单个会话 |
| `chat.createSession` | 页 进 | 建会话，`mode` 只接受 `build` / `plan` |
| `chat.listMessages` | 页 进 | 读消息，`before` / `after` 互斥 |
| `chat.sendMessage` / `chat.sendMessageAndWait` | 页 进 | 发一条用户消息；后者等本轮结束 |
| `chat.setDraft` | 页 进 | 把文本写进输入框 |
| `chat.stop` / `chat.openSession` | 页 进 | 停轮次、切会话 |
| `chat.renameSession` / `chat.pinSession` / `chat.deleteSession` | 页 进 | 重命名、置顶、删除 |
| `chat.getRunState` / `chat.waitForRun` | 页 进 | 读运行状态、等待本轮结束 |
| `chat.listApprovals` / `chat.resolveToolApproval` | 页 进 | 待确认工具审批的读取与回传 |
| `chat.resolveQuestion` / `chat.resolvePlanApproval` | 页 进 | 回答问题、审批计划 |

发送消息与手动输入完全同路径：正常落库、消耗 token，经过工具授权与 PLAN/AUTO 限制，不会绕过审批。

## 模型句柄（7）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `model.listProviders` / `model.listModels` | 页 进 | 已配置供应商与模型（不含 Key） |
| `model.create` / `model.close` | 页 进 | 创建句柄（每插件最多 16 个）、关闭 |
| `model.start` / `model.read` / `model.cancel` | 页 进 | 发起流式请求、按游标读事件、取消 |

需要清单声明 `contributes.modelAccess`，未声明时调用返回 `MODEL_ACCESS_NOT_DECLARED`。完整消息格式见[模型句柄与流式调用](/advanced/plugin/model-handles)。

## 工具、MCP 与子代理（6）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `tools.list` | 页 进 | 列出宿主注册的工具，可选带参数 schema |
| `tools.execute` | 页 进 | 代执行一次内置/MCP 工具调用 |
| `tools.pending` / `tools.resolve` | 页 进 | 轮询待审批调用、回传 `once` / `always` / `reject` |
| `mcp.status` | 页 进 | MCP server 连接状态 |
| `agent.runSubAgent` | 进 | 起一个子代理跑一段目标 |

`tools.execute` 只接受内置与 MCP 工具：`plugin__` 前缀的插件工具、以及依赖聊天会话的 `askUserQuestion` / `planMode` / `goalStatus` / `todo` / `task` / `messageParent` 返回 `TOOL_NOT_ALLOWED`。审批流程见[工具授权与代审核](/advanced/plugin/tool-permissions)。

## 自动化、音乐、蓝牙与角色卡（44）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `workflow.getAll` / `workflow.get` | 页 进 | 读工作流定义 |
| `workflow.create` / `workflow.update` / `workflow.delete` | 页 进 | 增改删工作流 |
| `workflow.setEnabled` / `workflow.trigger` | 页 进 | 启停、手动触发 |
| `workflow.registerTemplate` | 页 进 | 注册可被用户新建时选用的模板 |
| `music.play` / `music.playQueue` | 页 进 | 播放单曲或队列 |
| `music.pause` / `music.resume` / `music.stop` | 页 进 | 暂停、继续、停止 |
| `music.seek` / `music.setVolume` / `music.status` | 页 进 | 定位、音量、状态 |
| `bluetooth.permission` / `bluetooth.state` / `bluetooth.requestEnable` | 页 进 | 蓝牙授权状态、开关、请求开启 |
| `bluetooth.bondedDevices` / `bluetooth.scan` | 页 进 | 已配对设备、扫描 |
| `bluetooth.connect` / `bluetooth.listen` / `bluetooth.accept` | 页 进 | 建立连接、监听、接受 |
| `bluetooth.send` / `bluetooth.read` / `bluetooth.sendAndRead` / `bluetooth.close` | 页 进 | 经典蓝牙收发与关闭 |
| `ble.connect` / `ble.discoverServices` | 页 进 | BLE 连接与服务发现 |
| `ble.readCharacteristic` / `ble.writeCharacteristic` / `ble.writeAndReadCharacteristic` | 页 进 | 特征读写 |
| `ble.subscribe` / `ble.readNotifications` | 页 进 | 订阅通知、读取通知 |
| `characterCard.list` / `characterCard.get` | 页 进 | 列角色卡、取详情 |
| `characterCard.create` / `characterCard.update` / `characterCard.delete` | 页 进 | 增改删角色卡 |
| `characterCard.setActive` / `characterCard.clearActive` | 页 进 | 指定当前生效角色卡 |
| `characterCard.importTavern` / `characterCard.exportTavern` | 页 进 | 导入导出酒馆格式卡片 |

## 软件设置、记忆与杂项（26）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `softwareSettings.getSpeechServicesConfig` / `setSpeechServicesConfig` / `testTtsPlayback` | 页 进 | 语音服务配置与试听 |
| `softwareSettings.listModelConfigs` / `createModelConfig` / `updateModelConfig` / `deleteModelConfig` | 页 进 | 模型渠道配置 |
| `softwareSettings.listFunctionModelConfigs` / `getFunctionModelConfig` / `setFunctionModelConfig` | 页 进 | 功能位（如标题生成）绑定的模型 |
| `softwareSettings.testModelConfigConnection` | 页 进 | 连通性测试 |
| `softwareSettings.listSandboxPackages` / `setSandboxPackageEnabled` / `executeSandboxScriptDirect` | 页 进 | 沙盒脚本包管理 |
| `softwareSettings.restartMcpWithLogs` | 页 进 | 重启 MCP 并取日志 |
| `memory.query` / `memory.getByTitle` | 页 进 | 检索记忆、按标题取 |
| `memory.create` / `memory.update` / `memory.deleteMemory` / `memory.move` | 页 进 | 增改删与移动 |
| `memory.link` / `memory.queryLinks` / `memory.updateLink` / `memory.deleteLink` | 页 进 | 记忆间的链接 |
| `tasker.triggerEvent` | 页 进 | 外部事件触发；宿主没有自定义事件通道，恒返回 `UNSUPPORTED` |

## 界面、系统服务与网络（22）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `context` | 页 浮 | 当前插件、页面、槽位、路径、主题、工作区等上下文 |
| `ui.close` / `ui.overlay` | 页 | 关闭插件页、页面内全屏弹层挖孔 |
| `ui.openUrl` | 页 进 | 用系统浏览器打开 http/https 链接 |
| `toast` | 页 | 轻量提示 |
| `tip` | 页 进 | 在对话中显示可关闭提示条 |
| `notify` | 进 | 发系统通知 |
| `log` / `log.write` | 进 / 页 | 写宿主日志（debug/info/warn/error） |
| `clipboard.read` / `clipboard.write` | 页 进 | 系统剪贴板 |
| `share.text` | 页 进 | 拉起系统分享面板 |
| `secrets.get` / `secrets.set` / `secrets.delete` / `secrets.list` | 页 进 | 插件私有加密键值存储，值上限 64 KiB |
| `env.get` / `env.set` / `env.list` | 页 进 | 插件环境变量 |
| `http.request` | 页 进 | 宿主代理 HTTP，响应体上限 1 MiB |
| `cookies.get` | 页 | 读取虚拟域 cookie 罐 |
| `plugin.invoke` | 页 | 等同页面侧 `aharou.invoke`，调同插件进程的 handler |

## 浮层（9）

| 方法 | 来源 | 说明 |
| --- | --- | --- |
| `overlay.create` / `overlay.update` | 页 进 | 创建、更新浮层 |
| `overlay.close` / `overlay.postMessage` | 页 进 浮 | 关闭、回传交互消息 |
| `overlay.self` | 浮 | 读自身 id、尺寸、位置与密度 |
| `overlay.setVisibilityPolicy` | 浮 | `always` / `foreground` / `background` |
| `overlay.setDragRegion` / `overlay.moveBy` / `overlay.resize` | 浮 | 拖拽区域、移动、改尺寸 |

浮层页面不得调用 Shizuku、Shell 与终端，详见[系统浮层](/advanced/plugin/overlay)。

## 常见错误码

| 错误码 | 含义与处理 |
| --- | --- |
| `METHOD_UNKNOWN` | 该方法不向当前来源开放，或名字拼错；对照本文「来源」列 |
| `PARAM_INVALID` / `PARAM_MISSING` | 参数类型或必填项不对 |
| `PLUGIN_INACTIVE` | 插件已停用或被替换安装，旧页面/旧进程不能继续调用 |
| `STALE_CONTEXT` | 带了已过期的 `contextKey`，重新取 `context()` 后再试 |
| `FILE_EXISTS` / `EDIT_NOT_FOUND` | 目标已存在 / 旧文本没命中 |
| `TOOL_NOT_ALLOWED` | `tools.execute` 不接受该工具（插件工具或会话型工具） |
| `SESSION_FORBIDDEN` / `SESSION_NOT_FOUND` | 会话不属于当前工作区 / 不存在 |
| `CHAT_UI_UNAVAILABLE` / `CHAT_BUSY` | 聊天页不在，或请求队列已满 |
| `MODEL_ACCESS_NOT_DECLARED` | 未声明 `contributes.modelAccess` |
| `A11Y_NOT_ENABLED` | 无障碍服务没开，设备类方法全部不可用 |
| `UNSUPPORTED` | 宿主没有对应能力（如 `tasker.triggerEvent`），不要当成功处理 |

## 尚未写进类型声明的域

`host.d.ts` 目前还没有蓝牙 / BLE、音乐、角色卡、软件设置、记忆、工作流与 `tasker` 的声明；这些方法已进宿主方法表与分发器，**调用可用**，只是编辑器补全还没跟上。以本文与运行时返回为准。

## 查接口时的先后顺序

1. 本文与各专题页：手上要做什么、大概用哪个域；
2. 仓库内 `plugins/sdk/types/host.d.ts`：参数与返回结构的权威类型参考，与宿主方法表逐项对应；
3. `plugins/types/` 下收录的 Operit2 声明快照：移植旧插件时对照上游接口形状，见[兼容 Operit 插件与 v1 / v2 契约](/advanced/plugin/compat-v1)。

> 部分内容参照 Operit2 文档改写（AGPL-3.0）；方法清单、来源与错误码取自 Aharou 宿主的插件方法表实现。
