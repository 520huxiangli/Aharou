# Operit 插件兼容层（`operit-shim.js`）

让按 [Operit](https://github.com/AAswordman/Operit) ToolPkg 规范写的 JavaScript 插件，在 Aharou 的插件进程里跑起来。
本文件只讲这个 shim 怎么用、哪些能力可用、哪些明确报错，以及宿主侧还差哪些接口。

- **许可证**：Operit 是 LGPL-3.0，Aharou 是 GPL-3.0，二者兼容。`operit-shim.js` 移植了 Operit 的 JS 包装层
  （`JsTools.kt` 的 `Tools.*` 参数拼装与返回形变、`JsExecutionScriptBuilder.kt` 的运行时函数契约、
  `JsToolPkgRegistration.kt` 的 23 个注册方法清单）；Compose DSL 的 JS 运行时单独放在
  `compose-dsl-runtime.js`（同源移植 `JsComposeDslBridge.kt` + `JsComposeDslRuntimeScript.kt`，见第 11 节）。
  QuickJS 宿主、`NativeInterface` 与 Java/Android 桥**没有**移植，对应能力一律转成明确报错，不伪造成功。
- **纯 CommonJS，零 npm 依赖**：只用 `crypto` / `path` / `module` / `async_hooks`。要求容器里 node ≥ 16
  （`AsyncLocalStorage` + `crypto.randomUUID`）。容器内 node 24.19.0 实测通过。
- 页面侧的 `aicode.js` 与本文件无关，两者互不引用。

---

## 1. 文件结构

```
app/src/main/assets/plugin-ui/operit-shim.js            兼容层本体（插件进程 require）
app/src/main/assets/plugin-ui/compose-dsl-runtime.js    Compose DSL JS 运行时（路由、渲染树、action）
app/src/main/assets/plugin-ui/operit-README.md          本文档
plugins/sdk/operit-selftest.cjs                         自测脚本（假宿主桩，不需要真机）
plugins/sdk/compose-dsl-selftest.cjs                    Compose DSL 自测脚本
```

## 2. 注入方式

插件进程是 node，宿主通过 stdin/stdout 的按行 JSON-RPC 与它通信；进程侧调宿主时方法名带 `host.` 前缀。
shim 不改变这套协议，只做两件事：把 Operit 风格的调用翻译成 `host.*` 请求，把宿主结果整理成 Operit 的形状。

### 模式 A：插件自己 require（推荐）

宿主把 shim 落到容器里（例如 `<aharouDir>/plugins/sdk/operit-shim.js`，宿主目录已挂载到容器），
插件入口这样写：

```js
const operit = require(process.env.AHAROU_OPERIT_SHIM || '/root/.aharou/plugins/sdk/operit-shim.js');

const runtime = operit.createRuntime({
  env: <可选：宿主透传的环境变量对象>,
  configDir: <可选：本插件的数据目录>
});

runtime.registerHandlers({
  tools: {
    'tools.echo': async (args) => ({ text: String(args.text || '') }),
    'tools.readNote': async (args) => {
      const file = await runtime.Tools.Files.read({ path: args.path });
      return { success: true, content: file.content, size: file.size };
    }
  }
});

runtime.serve();
```

`serve()` 接管 stdin 上的 `tool.invoke` / `hook.invoke` / `ui.invoke`，按 `params.handler` 找 handler 执行，
并把返回值按 Operit 的 `success` 语义回给宿主。

不传 `env` / `configDir` 时走这些默认值（宿主 `PluginRuntimeManager` 已经通过 `extraEnv` 传了前三项）：

| 来源 | 用途 |
| --- | --- |
| `AICODE_PLUGIN_ID` | `runtime.pluginId` |
| `AICODE_DATA_DIR` | `getPluginConfigDir()` 的返回值；`PluginHostPaths` 里 `data://` 指向同一个目录 |
| `AICODE_WORKSPACE` | 当前工作区根 |
| `AHAROU_PLUGIN_ENV`（JSON） | `getEnv(key)` 的数据源，不必为它新增宿主方法 |
| `AHAROU_LANG` | `getLang()` 的兜底 |
| `AHAROU_TERMINAL_SHELL` | `Tools.System.terminal.create` 起的交互 shell，默认 `sh -i` |

```bash
# 宿主侧示意：extraEnv 里带上环境变量即可
AHAROU_PLUGIN_ENV='{"MY_TOKEN":"..."}' node dist/main.js
```

### 模式 B：预加载（插件自己写消息循环）

宿主把启动命令改成 `["node", "-r", "/root/.aharou/plugins/sdk/operit-shim.js", "dist/main.js"]`
并设 `AHAROU_OPERIT_AUTOLOAD=1`。模块求值阶段就会：

- 把 `Tools` / `ToolPkg` / `toolCall` / `getEnv` / `console` 等挂到 `globalThis`；
- 安上 `lodash` / `uuid` / `axios` 的 require 兜底；
- **不接管 stdin**（插件自己的循环照旧收请求）。

插件收到调用后用 shim 导出的回执函数回结果，语义与 `complete()` 完全一致：

```js
const shim = require('/root/.aharou/plugins/sdk/operit-shim.js');
// 自己的 readline 循环里
shim.reply(message.id, { success: false, message: '权限不足' }); // → JSON-RPC error
shim.reply(message.id, { text: 'ok' });                          // → JSON-RPC result
```

两种模式共用同一条 stdio 通道；模式 B 下再调 `createRuntime()` 会复用那条通道，不会出现两个读数循环抢行。

## 3. `complete` 的 success / error 转换

宿主只把 JSON-RPC 的 `result` 当成功、`error` 当失败。Operit 的约定是返回值里带 `success` 字段，
所以必须在这里做一次翻译，否则 AI 会看到「成功但内容说失败」。

| handler 返回值 | 发给宿主 |
| --- | --- |
| `{ success: false, message: '...' }`（或 `'false'` / `0`） | `error: { code: 'TOOL_FAILED', message }` |
| `{ success: false }`（无 message） | `error: { code: 'TOOL_FAILED', message: '插件返回 success:false 但没有给出 message' }` |
| `{ success: true, ... }` | `result` 原样，`success` 字段保留 |
| 普通对象 / 数组 / 字符串 / 数字 | `result` 原样 |
| `undefined` 或 `null` | `result: null` |
| handler 抛异常 | `error: { code: <err.code 或 'PLUGIN_ERROR'>, message }` |
| `params.handler` 在表里找不到 | `error: { code: 'HANDLER_NOT_FOUND' }` |
| 收到了 `tool.invoke` 之外的方法 | `error: { code: 'METHOD_UNKNOWN' }` |

`message` 的取值顺序：`message` → `error` → `detail` → `msg`。
`complete()` 是「本次调用到此为止」：同一调用里第二次 `complete()` 返回 `false`，不会重复回包；
handler 先 `complete()` 再返回别的值，也不会覆盖已回的那个包。

## 4. `Tools.*` 支持矩阵

图例：**可用** = 走宿主已有方法；**降级** = 能用但语义有损，返回里带 `degraded` 字段；**报错** = 明确抛
`NOT_SUPPORTED` 或指路的错误，不静默返回空。

### `Tools.Files`

| 方法 | 状态 | 映射 / 说明 |
| --- | --- | --- |
| `list(path)` | 可用 | `host.fs.list` → `{path, entries:[{name,isDir,isDirectory,size,path}]}` |
| `read(path \| {path})` | 可用 | `host.fs.read` → `{path, content, size}`；宿主上限 512 KiB |
| `readBinary(path)` | 可用 | `host.fs.readBinary` → `{path, base64, base64Content, content, size}` |
| `readPart(path,start,end)` | 可用 / 降级 | `host.fs.readRange`（1 基、闭区间）；宿主没有时整读后本地切行，返回 `degraded:'local-line-slice'` |
| `write(path,content,append)` | 可用 | `append` 为真走 `host.fs.append`，否则 `host.fs.write` |
| `writeBinary(path,base64)` | 可用 | `host.fs.writeBinary` |
| `deleteFile(path)` | 可用 | `host.fs.delete`，**恒为递归删除**（宿主实现如此，`recursive:false` 无效） |
| `exists(path)` | 可用 | `host.fs.stat` → `{exists, isDir, isDirectory, size, path}` |
| `move(source,dest)` | 可用 | `host.fs.rename` |
| `copy(source,dest)` | 可用 | `host.fs.copy`，`overwrite:false`：目标已存在时宿主回 `FILE_EXISTS` |
| `mkdir(path)` | 可用 | `host.fs.mkdir`（自动建父目录） |
| `find(path,pattern,opts)` | 可用 | `host.fs.find`（宿主待实现） |
| `grep(path,pattern,opts)` | 可用 | `host.fs.grep`（宿主待实现） |
| `grepContext(path,intent)` | 报错 | 按意图检索没有等价实现，错误里指向 `grep` / `read` |
| `info(path)` | 可用 | `host.fs.stat` |
| `apply(path,type,old,new)` | 可用 / 降级 | `host.fs.edit`；没有时读-改-写，老内容必须命中，否则 `EDIT_NOT_FOUND` |
| `create(path,content)` | 可用 | `host.fs.write` |
| `edit(path,old,new)` | 可用 / 降级 | 同上，`old` 为空直接 `PARAM_INVALID` |
| `zip` / `unzip` | 报错 | 指向 `Tools.System.shell("zip …")` |
| `open(path)` | 报错 | 没有「用系统应用打开」通道，指向 `read` |
| `share(path,title)` | 降级 | 读出文本后走 `host.share.text`，返回 `degraded:'share-text-content'`；空文件报 `EMPTY_FILE` |
| `download(url,dest,env,headers)` | 可用 | `host.http.request` + `responseBodyBase64` → `host.fs.writeBinary`，二进制安全 |

`environment` 参数：只接受空或 `primary`（映射到当前工作区），其它值抛 `ENVIRONMENT_UNSUPPORTED`
—— Aharou 插件只有一个执行环境，把 `secondary` 当同一棵树会读错地方。

### `Tools.Net`

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `http(options)` | 可用 | `host.http.request`；透传 `body` / `bodyBase64` / `multipart` / `headers` / `timeout` / `followRedirects` / `ignoreSsl` / `maxBytes` / `responseBodyBase64`，snake_case 与 camelCase 都收 |
| `httpGet(url,ignoreSsl)` / `httpPost(url,body,ignoreSsl)` | 可用 | 同上；`ignoreSsl=true` 会真的传给宿主（宿主只认显式 true） |
| `visit(url \| {url})` | 降级 | 取 HTML 原文，不渲染 JS、不转 markdown，返回 `degraded:'raw-html'` |
| `uploadFile({url,files,form_data})` | 可用 | `files` 逐项 `fs.readBinary` 成 base64，拼成宿主原生 `multipart`（`{fields, files:[{name,fileName,mimeType,base64}]}`） |
| `cookies.get/set/clear` | 报错 | 宿主没有 cookie 罐，指向「自己在 headers 里带 Cookie」 |
| 22 个 `browser*` 方法 | 报错 | 统一提示「Aharou 暂不支持浏览器自动化，请改用 Tools.UI 或让用户手动操作」 |

### `Tools.System`

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `sleep(ms)` | 可用 | 本地 `setTimeout`，不占宿主 |
| `getSetting(setting,namespace)` / `setSetting(setting,value,namespace)` | 可用 | 经 `host.shizuku.exec` 调 `settings get/put`，需要 Shizuku 授权 |
| `getDeviceInfo()` | 可用 | `host.app.info`，额外补 `sdk/sdkVersion/language` |
| `toast(msg)` | 降级 | `host.toast` 目前只对页面来源开放，进程侧会拿到 `METHOD_UNKNOWN`，于是改发通知，返回 `degraded:'notification'` |
| `sendNotification(msg,title)` | 可用 | `host.notify {title, body}` |
| `shell(cmd)` | 可用 | `host.bash` → `{output, exitCode, truncated}` |
| `terminal.create(name)` | 可用 | `host.terminal.run {command:'sh -i', title}` → `{session_id, sessionId, tabId}` |
| `terminal.exec(sessionId,cmd,timeout)` | 可用 | `terminal.write` + 轮询 `terminal.poll` 收输出；交互式 shell 没有退出码，出过输出后静默约 600ms 判完 |
| `terminal.execStreaming(...)` | 降级 | 宿主没有流式通道，用 `terminal.poll` 增量逐段回调 `onIntermediateResult`，返回 `degraded:'polling-stream'` |
| `terminal.screen/input/close` | 可用 | `poll` / `terminal.write`（含 `C-c`、`UP` 等控制符映射）/ `terminal.close` |
| `terminal.hiddenExec(cmd,{timeoutMs})` | 可用 | `host.bash` |
| `usePackage` / `installApp` / `uninstallApp` / `startApp` / `stopApp` / `listApps` / `getNotifications` / `getAppUsageTime` / `getLocation` | 报错 | 错误里给出等价 shell 命令（`pm list packages`、`am start …` 等）或「让用户自己操作」 |
| `music.*` / `bluetooth.*` / `ble.*` | 报错 | 宿主没有媒体与蓝牙通道 |
| `intent(options)` / `sendBroadcast(options)` | 报错 | 指向 `Tools.System.shell("am start/broadcast …")` |

### `Tools.SoftwareSettings`

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `readEnvironmentVariable(key)` | 可用 | `host.env.get`（宿主待实现）；没有时回落到注入的 env / 进程环境，返回 `degraded:'injected-env'` |
| `writeEnvironmentVariable(key,value)` | 可用 | `host.env.set`（宿主待实现）；没有时明确报错，指向让用户在设置里加 |

### `Tools.UI`

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `getPageInfo()` / `captureScreenshot()` | 可用 | `host.device.pageInfo` / `host.device.screenshot {format:'png'}` |
| `tap(x,y)` / `swipe(sx,sy,ex,ey,dur)` / `pressKey(code)` | 可用 | `host.device.tap/swipe/pressKey` |
| `setText(text[,resourceId])` | 可用 | 带 `resourceId` 时先 `pageInfo` 定位并点中该输入框，再 `host.device.setText`；不带就直接写焦点框 |
| `longPress(x,y)` | 可用 / 降级 | `host.device.longPress`；没有时用同点 `swipe` 800ms 代替 |
| `clickElement(obj \| id \| (type,value) \| (type,value,index))` | 可用 | **两步**：`device.pageInfo` 找节点（resourceId / className / text / bounds / index）→ 算中心点 → `device.tap`；找不到回 `ELEMENT_NOT_FOUND` |
| `runSubAgent(intent,maxSteps,agentId,targetApp)` | 可用 | `host.agent.runSubAgent`（宿主待实现）；没有时明确报错并指向手写 `Tools.UI` 步骤 |

### `Tools.Chat`

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `sendMessage(msg,chatId)` | 可用 | `host.chat.sendMessage`，发完即返回（fire-and-forget） |
| `sendMessageAndWait(msg,chatId,opts)` | 可用 | shim 内组合：`chat.sendMessage` → `chat.waitForRun` → `chat.listMessages` 取最后一条 assistant |
| `sendMessageStreaming(...)` | 降级 | 宿主没有单条流式回复通道：退化成「发完取回复」，整段只回调一次，返回 `degraded:'no-streaming'` |
| `listAll()` / `listChats(opts)` | 可用 | `host.chat.listSessions`（limit 上限 200） |
| `getMessages(chatId,opts)` | 可用 | `host.chat.listMessages` |
| `switchTo(id)` / `updateTitle(id,t)` / `deleteChat(id)` / `agentStatus(id)` | 可用 | `chat.openSession` / `renameSession` / `deleteSession` / `getRunState` |
| `createNew` / `startService` / `getMessagesRange` / `findChat` / `call` / `listCharacterCards` / 角色卡导入导出 | 报错 | 无等价宿主能力；`createNew` 的替代是 `host.chat.createSession` |

`chatId` 省略时会取当前工作区最新的会话；一个会话都没有时抛 `NO_SESSION`。

### 其余

| 域 | 状态 |
| --- | --- |
| `Tools.Memory.*` | 报错，指向 `host.tools.list` + `host.tools.execute` |
| `Tools.Workflow.*` | 报错，指向应用界面配置 |
| `Tools.Tasker.*` | 报错 |
| `Tools.calc(expr)` | 可用：本地递归下降求值，支持 `+ - * / % ^`、括号、`pi/e`、`sqrt/abs/sin/cos/tan/log/ln/exp/round/floor/ceil/min/max/pow`；非算术输入抛 `PARAM_INVALID` |
| `Tools.FFmpeg.*` | 可用：`execute/info/convert` 都转成 `host.bash` 里的 `ffmpeg`，容器里没装就由退出码报错 |

## 5. 运行时函数与全局

| 全局 | 行为 |
| --- | --- |
| `complete(v)` / `done(v)` | 见第 3 节；没有活动调用时记一条 warn 并返回 `false` |
| `emit`/`delta`/`log`/`update`/`sendIntermediateResult`(v) | 走 `host.log`（200ms 时间窗限流，省略条数会写进文案）；`createRuntime({notifyEvents:true})` 时改发 `plugin.event` 通知；也可传 `onIntermediate` 回调 |
| `getEnv(key)` | 注入的 env 优先，其次进程环境；空字符串与未声明都返回 `undefined` |
| `getPluginConfigDir([id])` | 返回本插件数据目录；传别的插件 ID 会被拒绝并返回 `''` |
| `getState()` | 调用上下文里的 `state`，默认 `undefined` |
| `getLang()` | `meta.lang` → 注入的 `lang` → `AHAROU_LANG` → `'en'` |
| `getCallerName()` / `getChatId()` / `getCallerCardId()` | 调用上下文里有就返回，没有返回 `null` |
| `console.log/info/warn/error` | 转发到 `host.log`（**不写 stdout**，否则会污染 JSON-RPC 行） |
| `toolCall(name, params)` | 自动补 `host.` 前缀；同时支持 `toolCall('default', name, params)` 三参数写法；宿主错误按 `{code, message}` 抛出 |
| `ToolPkg.register*` | 见第 6 节 |
| `_`（lodash） | 兜底实现，见第 7 节 |

调用上下文来自宿主 `tool.invoke` 的 `params.meta`，支持 `lang` / `state` / `chatId` / `callerName` / `callerCardId`。
多个调用可以并发：上下文用 `AsyncLocalStorage` 隔离，不会串台。

## 6. `ToolPkg.register*`

23 个注册方法全部「记录声明 + 返回 `true`」——很多插件在 `main.js` 里无条件调用它们，抛错会让插件直接起不来。
不支持的方法，每条记录形如：

```js
{ api: 'registerXmlRenderPlugin', ok: false, reason: 'Aharou 不支持聊天内 XML 渲染', id: 'xml' }
```

**`registerUiRoute` 已经落地**（`ok: true`，交给 `compose-dsl-runtime.js` 执行，见第 11 节）：
`runtime` 必须是 `compose_dsl`，否则记为 `ok: false` 并说明原因。

宿主或兼容性报告用 `__aharouTakeUnsupportedRegistrations()`（也在 `runtime.takeUnsupportedRegistrations()`）
一次取走并清空，**只返回 `ok: false` 的条目**，所以已支持的注册不会混进「不支持」清单。
路由清单用 `__aharouListUiRoutes()` / `runtime.listUiRoutes()` 取（不清理，渲染时还要用）。

`ToolPkg.getConfigDir()` 返回数据目录；`ToolPkg.readResource(rel)` 按进程 cwd（插件安装目录）解析绝对路径后
走 `host.fs.read`。`ToolPkg.ui.floatingWindows.*` 明确报错并指向 `host.overlay.*`。

## 7. 内置库兜底

真实 node 里相对 `require` 天然可用；下面三个名字若容器里没装，`installGlobals()` 会装一个 require 钩子兜底
（装了真包就让位给真包）：

| 名字 | 兜底实现 |
| --- | --- |
| `lodash` | 约 25 个常用函数（`map/filter/reduce/find/get/merge/cloneDeep/pick/omit/groupBy/clamp/…`）；缺的方法调用时抛 `NOT_SUPPORTED` 并带方法名 |
| `uuid` | `v4` 走 `crypto.randomUUID`；`v3/v5` 用 crypto 哈希；`validate`/`version`/`parse`/`stringify`/`NIL`/`MAX`；`v1` 退化为随机 v4（不伪造时间语义） |
| `axios` | 薄封到 `host.http.request`：支持 `get/post/put/patch/delete/head/options`、`create({baseURL})`、`interceptors.request/response`、非 2xx 抛 `isAxiosError` 错误 |

## 8. 自测

```bash
node --check app/src/main/assets/plugin-ui/operit-shim.js
node --check app/src/main/assets/plugin-ui/compose-dsl-runtime.js
node plugins/sdk/operit-selftest.cjs
node plugins/sdk/compose-dsl-selftest.cjs
```

自测用假宿主桩（实现了 `transport` 接口）跑 23 条用例，覆盖 `complete` 的四种转换、`getEnv`、`Tools.Files.read/list/readPart/edit/write`、
`Tools.Net.http/uploadFile`、`Tools.Files.download`、三参数 `toolCall`、`environment` 拒绝、`ToolPkg` 记录、`calc`、`sleep`、
axios/lodash/uuid 兜底，以及「不支持的能力必须明确报错」。

`compose-dsl-selftest.cjs` 另跑 14 条用例，覆盖渲染树形状、`ui.dispatch` 改 state 后重渲染、未知节点类型、
具名插槽、Modifier / 单位 / 颜色 token、宿主回灌 state（含热实例防回退）、async Screen 与 async 动作、
未知 action、`registerUiRoute` 记成已支持、`ui.routes` / `ui.render` / `ui.dispatch` 三个 `ui.invoke` 入口。

真实 stdio 通道的联调（不落仓库，需要时自己搭）：把 shim 当预加载模块或库，宿主脚本按行写 `tool.invoke`、
按 id 回 `host.*` 的结果，即可覆盖 `serve()` 与 `AHAROU_OPERIT_AUTOLOAD` 两种模式。

## 9. 需要宿主配合的接口

### 9.1 已经在用、宿主已实现（进程来源可用）

均已在 `PluginHostApiCatalog` 与 `PluginHostApiDispatcher` 里核对过，方法名带 `host.` 前缀：

```
fs.read/write/list/stat/mkdir/delete/rename/copy/append/readBinary/writeBinary
http.request        bash        shizuku.exec        notify        share.text
app.info            log         terminal.run/poll/write/close
chat.listSessions   chat.listMessages   chat.sendMessage   chat.openSession
chat.renameSession  chat.deleteSession  chat.getRunState   chat.waitForRun
```

### 9.2 需要新增（shim 已按此契约调用，宿主侧还没有）

| 方法 | 参数 | 返回 | 用途 / 缺了会怎样 |
| --- | --- | --- | --- |
| `host.fs.edit` | `{path, old, new, type?}` | `{ok, replaced}` | `Files.edit/apply`；缺了退化为读-改-写 |
| `host.fs.readRange` | `{path, startLine, endLine}`（1 基闭区间） | `{content, size, startLine, endLine, totalLines, truncated}` | `Files.readPart`；缺了退化整读切行 |
| `host.fs.find` | `{path, pattern, ...options}` | `{entries}` 或 `{matches}` | `Files.find`；缺了报 `METHOD_UNKNOWN` |
| `host.fs.grep` | `{path, pattern, ...options}` | `{matches}` | `Files.grep`；同上 |
| `host.device.pageInfo` | `{}` | `{nodes:[{text, className, resourceId, bounds:{left,top,right,bottom}}]}`（也接受数组 / `{elements}`） | `Tools.UI.getPageInfo`、`clickElement`、`setText` 定位 |
| `host.device.tap` | `{x, y}` | `{ok}` | `Tools.UI.tap`、`clickElement` |
| `host.device.swipe` | `{x, y, x2, y2, duration}` | `{ok}` | `Tools.UI.swipe`、`longPress` 降级 |
| `host.device.longPress` | `{x, y, duration}` | `{ok}` | 可选；缺了用同点 swipe 代替 |
| `host.device.pressKey` | `{keyCode}` | `{ok}` | `Tools.UI.pressKey` |
| `host.device.setText` | `{text, resourceId?}` | `{ok}` | `Tools.UI.setText` |
| `host.device.screenshot` | `{format:'png'}` | `{base64, width, height, format}` | `Tools.UI.captureScreenshot` |
| `host.agent.runSubAgent` | `{goal, maxSteps?, agentId?, targetApp?}` | 结果对象 | `Tools.UI.runSubAgent`；缺了明确报错 |
| `host.env.get` | `{key}` | `{value}` 或字符串 | `SoftwareSettings.readEnvironmentVariable`；缺了用注入 env |
| `host.env.set` | `{key, value}` | `{ok}` | `SoftwareSettings.writeEnvironmentVariable`；缺了明确报错 |
| `host.env.getAll` | `{}` | `{values:{...}}` | 可选，`runtime.refreshEnv()` 用 |
| `host.toast` | `{message}` | `{ok}` | 可选：把 `toast` 从 `PAGE_ONLY` 提到进程可用，`Tools.System.toast` 就不再降级成通知 |

另外两件宿主侧的事（不属于方法契约）：

1. **把 shim 落到容器**：`app/src/main/assets/plugin-ui/operit-shim.js` 打包进 APK 后不会自动出现在容器里，
   需要宿主在准备插件运行时把它复制到 `<aharouDir>/plugins/sdk/`（已挂载进容器），或由插件作者自行 require 绝对路径。
   `compose-dsl-runtime.js` 必须与它同目录（或用 `AHAROU_COMPOSE_DSL_RUNTIME` 指路径），否则只有 UI 路线不可用。
2. **`AHAROU_PLUGIN_ENV`**：想把环境变量交给 `getEnv`，宿主在 `extraEnv` 里塞一段 JSON 即可，不需要新方法。
3. **Compose 渲染器**：`ui.render` / `ui.dispatch` 只产出 `{tree, state, memo}`，把树画到屏幕上是宿主侧的新组件（尚未实现）。

## 10. 未验证项

- Compose DSL 只在 node 里用假宿主桩跑过：真实 Operit 示例的 6 个 `*.ui.js`（记账本、世界书、待办、
  深度搜索、消息翻译、模型站点）能渲染出树，记账本的 `onLoad` 能走完一次 `ui.dispatch` 并重渲染；
  **没有**在真机 App 里被宿主渲染过（渲染器尚未实现）。
- `Java`（Operit 的 Java/Android 桥）与 `ctx.measureText` / WebView 控制器 / 工具包导入在 Aharou 里没有实现，
  用到它们的界面会报错；这是设计上的缺口，不是 bug。
- 所有 `host.device.*`、`host.env.*`、`host.agent.runSubAgent` 路径只验证了「调用发出、参数形状正确、缺方法时明确报错」，
  **没有**在真实宿主上跑过（这些方法尚未实现）。
- `Tools.System.terminal.*` 的判停策略（出过输出后静默约 600ms）只在假宿主上推演，未在真 PTY 上长跑验证。
- 依赖外部命令的降级路径（`ffmpeg`、`settings get/put`、`zip`）要求容器/设备上确有对应命令与 Shizuku 授权，未实跑。
- 未跑 Gradle、未改任何 Kotlin 代码；本 shim 不进 `aicode.js` 的方法表，`scripts/check_plugin_host_api.py` 不受影响。

## 11. Compose DSL 运行时（插件面板）

Operit 的插件面板不是 HTML，而是 JS 直接产出 Compose 渲染树。Aharou 保留了这层的 JS 部分：
`registerUiRoute` 声明的路线、`ui.render` / `ui.dispatch` 两个入口都由 `compose-dsl-runtime.js` 执行，
产物是纯 JSON 树，交给宿主渲染（宿主侧渲染器尚未实现，见第 10 节）。

来源：同源移植 `JsComposeDslBridge.kt` + `JsComposeDslRuntimeScript.kt`（LGPL-3.0）。

### 11.1 注册与两个入口

```js
const operit = require('/root/.aharou/plugins/sdk/operit-shim.js');
const runtime = operit.createRuntime({ pluginDir: __dirname /* ... */ });
runtime.serve();

runtime.ToolPkg.registerUiRoute({
  id: 'dashboard',
  route: 'toolpkg:com.example.demo:ui:dashboard',
  runtime: 'compose_dsl',
  screen: require('./ui/dashboard.ui.js'), // 函数，或包内相对路径字符串
  title: { zh: '面板', en: 'Dashboard' }
});
```

`screen` 接受三种写法：函数、包内相对路径（按 `createRuntime({ pluginDir })`、`AICODE_PLUGIN_DIR`
或进程 cwd 解析）、`{ default }` / `{ Screen }` 模块对象。注册时不 require，渲染时才解析。

宿主通过 `ui.invoke` 进入（插件自己注册了同名 handler 时以插件为准）：

| `params.handler` | `params.args` | 返回 |
| --- | --- | --- |
| `ui.routes` | `{}` | `{ routes: [{ id, route, runtime, title, screen }] }` |
| `ui.render` | `{ routeId \| route, state?, memo?, replaceState? }` | `{ tree, state, memo, route }` |
| `ui.dispatch` | `{ routeId, actionId, payload?, state?, memo?, replaceState? }` | `{ tree, state, memo, route, actionResult }` |

- `routeId` 可以传注册时的 `id`，也可以传 `route` 全名。
- `state` / `memo` 是宿主手里那一份：冷启动时整份写入；已有实例只用来补**缺失的键**，
  防止宿主回传的旧快照盖掉 JS 刚改的值。要整份回灌（进程重启恢复）就传 `replaceState: true`。
- `payload` 可以是任意值；`{ __composeTextFieldPayload: true, value }` 这类输入框回执会自动取 `value`。
- `ui.dispatch` 执行完 handler 一定重渲染并返回新树，不推中间态。Operit 会按微任务回推中间渲染，
  这里删掉了：宿主一次 `ui.invoke` 就看最新结果，省掉一路 JSON-RPC 往返。
- 失败走 JSON-RPC error：`ROUTE_NOT_FOUND` / `ACTION_NOT_FOUND` / `SCREEN_NOT_FOUND` /
  `RENDER_EMPTY` / `NOT_SUPPORTED` / `PARAM_INVALID`。

同一进程内也能直接调：`runtime.listUiRoutes()`、`runtime.renderUiRoute(id, opts)`、
`runtime.dispatchUiAction(request)`；全局另有 `__aharouListUiRoutes()`（不清理）。

### 11.2 节点形状

```js
{ __composeNode: true, type: 'Column', props: { ... }, children: [ ... ], slots: { ... } }
```

- `ctx.UI.<任意名字>(props, children)` 都能产出节点，**不做类型白名单**；未知类型原样输出，由渲染层降级。
- `props` 里塞的节点（`AlertDialog.text`、`DropdownMenu.content` 这类具名插槽）挪进 `slots`。
- `props` 里的函数换成 `{ __actionId: 'action_1' }`，handler 留在 JS 侧的 action 表里。
  action id 每轮渲染按顺序重建，宿主必须用**最新那棵树**里的 id，否则报 `ACTION_NOT_FOUND`。
- 颜色 token 序列化成 `{ __colorToken: 'primary', alpha? }`，Modifier 链序列化成
  `{ __modifierOps: [{ name, args }] }`，单位值序列化成 `{ __unit: 'dp', value: 4 }`。

### 11.3 `ctx` 提供哪些能力

| 能力 | 状态 |
| --- | --- |
| `UI` / `h(type, props, children)` | 可用（无白名单） |
| `useState(key, initial)` | 可用；setter 直接赋值（与 Operit 一致，不解析函数 updater），写完标记需重渲染 |
| `useMutable` / `useRef` / `useMemo` | 可用，落在 `memo`，不触发重渲染；`useMemo` 忽略 deps |
| `MaterialTheme.colorScheme.<name>`（含 `.copy({ alpha })`） | 可用，产出颜色 token |
| `Modifier.<name>(...args)` 链 + `.toJSON()` | 可用；参数里的函数同样换成 action |
| `Icons.<Name>` | 可用（全局注入，返回名字字符串） |
| `toolCall(name, params)` / `callTool` | 走 `host.tools.execute`（`{ name, arguments }`），异步 |
| `getEnv(key)` | 读创建上下文时的 env 快照，同步；要新值先 `await ctx.refreshEnv()` |
| `setEnv` / `setEnvs` | 走 `host.env.set`，异步，成功后更新快照 |
| `showToast(msg)` | 走 `Tools.System.toast`；宿主没有该通道时降级成 `host.log`（返回里带 `degraded: 'log'`） |
| `reportError(err)` | 写 `host.log`（error 级，tag `compose-dsl`） |
| `formatTemplate` / `getModuleSpec` / `getCurrentPackageName` / `getCurrentToolPkgId` / `getCurrentUiModuleId` / `listRoutes` | 可用 |
| `getHostRoutes()` | 恒为空数组（宿主没有对外路线清单） |
| `resolveToolName({ toolName })` | 直接回 `toolName`（没有工具包命名空间） |
| `navigate` / `createWebViewController` / `measureText` / `openFilePicker` / `isPackageImported` / `importPackage` / `removePackage` / `usePackage` / `listImportedPackages` | 明确 `NOT_SUPPORTED` |

### 11.4 同步改异步的地方（与 Operit 的差异）

| Operit（QuickJS + `NativeInterface` 同步桥） | Aharou（node + JSON-RPC） |
| --- | --- |
| `ctx.getEnv` 实时读宿主 | 读渲染前的 env 快照，`refreshEnv()` 刷新 |
| `ctx.toolCall` / `ctx.callTool` 直接拿返回值 | 返回 Promise，必须 `await` |
| `ctx.setEnv` 同步写完 | `await`，写完才更新快照 |
| 渲染中间态按微任务回推宿主 | 不推；`ui.dispatch` 一次返回最终树与 state |
| `measureText` / `openFilePicker` / WebView 控制器 / 工具包导入 | 没有宿主通道，明确报错 |
