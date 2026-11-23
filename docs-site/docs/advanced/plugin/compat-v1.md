# 兼容 Operit 插件与 v1 / v2 契约

Aharou 的插件体系与 Operit 的 ToolPkg 有同源的部分：都是「清单 + 容器内的 JS 进程 + 宿主桥」。为了能把已有插件跑起来，仓库提供了兼容层；为了让新插件不必背历史包袱，我们的接口以自己的声明为准。本文说明两者的边界、`api_version` 的含义，以及从旧插件迁移该怎么改。

## 两套类型声明

| 目录 | 内容 | 用途 |
| --- | --- | --- |
| `plugins/sdk/types/host.d.ts` | Aharou 自己的宿主方法声明 | **写新插件只看这一份** |
| `plugins/types/v1/`、`plugins/types/v2/` | Operit2 声明的收录快照（v2 已收 20 个文件，v1 待补） | 移植旧插件时对照接口形状，不参与编译与运行 |

`plugins/types/` 只是接口事实的参考：方法名、参数、返回结构。它不含任何实现代码，也不随 APK 发布。为什么分两套：Operit 在 v2 里重构了桥的形状（返回值更结构化、去掉了 QuickJS 专属原语），旧插件按 v1 的形状写，新插件按 v2 的形状写，对照时不能混着看。收录规则见 `plugins/types/README.md`。

## `api_version` 是什么意思

Operit 的 `manifest.hjson` 用 `api_version` 声明自己按哪一套契约写：

| 取值 | 含义 |
| --- | --- |
| 缺省或 `1.0.0` | 按 v1 契约：v1 声明里的全局函数、`Tools.*` 的旧返回形状 |
| `2.0.0` | 按 v2 契约：v2 声明里的结构化返回、`registerUiRoute` 等新入口 |

Aharou 导入 `.toolpkg` 时会读这个字段，并把它记进转换后的清单（`apiVersion`）。**运行期我们只跑一套兼容层**，不为 v1 / v2 各准备一套运行时：两个版本的调用都经过同一个 shim 翻译成 `host.*`。因此 `api_version` 的作用是「告诉作者与读者这段代码按哪套声明写」，而不是切换宿主行为；真正的差别体现在哪个方法可用、返回什么形状。

## 兼容层怎么工作

兼容层是 `operit-shim.js`，在插件进程里被 `require`：

```js
const operit = require(process.env.AHAROU_OPERIT_SHIM || '/root/.aharou/plugins/sdk/operit-shim.js');
const runtime = operit.createRuntime({configDir: process.env.AICODE_DATA_DIR});
runtime.registerHandlers({tools: {'tools.echo': async (args) => ({text: String(args.text || '')})}});
runtime.serve();
```

它做两件事：把 Operit 风格的调用翻译成 `host.*` 请求，把宿主结果整理回 Operit 的形状。`Tools.Files` / `Tools.Net` / `Tools.System` / `Tools.UI` / `Tools.Chat` 等域的方法按三种状态处理：

| 状态 | 含义 |
| --- | --- |
| 可用 | 走宿主已有方法，语义等价 |
| 降级 | 能用但语义有损，返回里带 `degraded` 字段说明替身是什么 |
| 报错 | 没有等价能力，抛 `NOT_SUPPORTED` 并在文案里指出替代做法，**不会静默返回空** |

常见的降级与缺口：

| Operit 能力 | 在 Aharou 的状态 |
| --- | --- |
| `Tools.Files.*` 读写、删改、二进制、下载 | 可用，映射到 `fs.*` |
| `Tools.Files.zip` / `unzip` / `grepContext` | 前两个指向 `Tools.System.shell`，第三个指向 `grep` |
| `Tools.Net.http` / `uploadFile` | 可用（`http.request`）；`visit` 只拿原始 HTML |
| 22 个 `browser*` 自动化方法 | 报错，改用内置浏览器的页面能力或让用户手动操作 |
| `Tools.System.shell` / `terminal.*` | 可用（`bash` / `terminal.*`） |
| `Tools.System` 的装删应用、通知、定位、`intent` | 报错并给出等价 shell 命令（`pm list packages`、`am start …`） |
| `Tools.UI.*`（点按、截图、页面信息） | 可用，落到 `device.*`；`clickElement` 需要自己先找节点 |
| `Tools.Chat.*` 会话与消息 | 可用；流式回复退化成「发完取回复」 |
| `Tools.Memory.*` / `Tools.Workflow.*` / `Tools.Tasker.*` | 走宿主方法（`memory.*`、`workflow.*`），不再经 shim 的旧接口 |
| `Tools.System.music.*` / `bluetooth.*` / `ble.*` | 走宿主方法 `music.*` / `bluetooth.*` / `ble.*` |

## 能导入什么

| 形态 | 特征 | 转换结果 |
| --- | --- | --- |
| 单个 `.js` | 文件头带 `METADATA` 块 | 一个带工具声明的插件 |
| `.toolpkg` | ZIP，根部（或仅多套一层目录）有 `manifest.hjson` / `manifest.json`，加若干 JS | 一个完整插件，主入口取 `main` 字段 |
| 技能 JSON | 顶层有 `commands[]` | 转成技能，`{name, description, command}` 逐条对应 |

上限：归档 16 MiB、条目 512 个、单条 4 MiB、解压后总量 32 MiB；只扫描 512 KiB 以内的脚本。根部有 `plugin.json` 的 ZIP 会被当作 Aharou 插件，不进入这条兼容路径。

导入时会跑一次兼容扫描，产出三类结果并显示在导入确认里：

| 级别 | 含义 |
| --- | --- |
| `available` | 调用能被完整翻译 |
| `degraded` | 能跑但语义有损（如 `Tools.Net` → `http.request`、`Tools.UI` → `device.*`） |
| `unavailable` | 宿主没有对应能力（如 `Tools.Memory` 旧接口、`Tools.Tasker`） |

按级别决定要不要装：`unavailable` 的那部分功能装上去也是报错，别指望它「大概能用」。

## 从旧插件迁到原生插件

| 旧写法 | Aharou 写法 |
| --- | --- |
| `manifest.hjson` + `toolpkg_id` | `plugin.json`（`manifestVersion: 1`、`id: 发布者.插件名`、`publisher`、`repository`） |
| `Tools.Files.read(path)` | `host.fs.read {path}` 或页面 `aharou.fs.read` |
| `Tools.System.shell(cmd)` | `host.bash {command}` |
| `Tools.UI.tap(x, y)` | `host.device.tap {x, y}` |
| `ToolPkg.registerUiRoute` + Compose DSL | `contributes.uiEntries[].screen`，进程里注册同名 `screen` 路径的路线 |
| `ToolPkg.ui.floatingWindows.*` | `contributes.overlay` + `overlay.*` |
| 面板里 `registerXmlRenderPlugin` | 不支持，改用页面或 Compose 面板 |
| `complete({success, message})` | 直接返回 `result`；失败抛 JSON-RPC `error` |
| 角色卡、工作流、记忆的旧 `Tools.*` | `characterCard.*`、`workflow.*`、`memory.*` 宿主方法 |

迁移后建议把 `api_version` 留在清单里当注释用，但真正生效的是 `engines.pluginApi`（宿主只接受包含 `1.0.0` 的范围）。

## 常见问题

| 现象 | 原因 |
| --- | --- |
| 导入后工具全报 `NOT_SUPPORTED` | 用了 `unavailable` 级别的旧接口，看导入时的兼容报告 |
| 面板打开白屏或报「no compose route」 | 清单 `screen` 与进程注册的路线路径不一致 |
| 明明有 `Tools.System.toast` 却没提示 | 进程侧没有 toast 通道，降级成系统通知，看返回值里的 `degraded` |
| 旧插件的多段式返回值被当成失败 | 兼容层把 `success: false` 翻译成 JSON-RPC error；新插件请直接用 `error` |
| 聊天里发出去没反应 | `sendMessage` 只表示入队；用 `waitForRun` 或 `chat.run.finished` 事件等结束 |

::: tip 许可证
Operit2 以 AGPL-3.0 发布，本仓以 GPL-3.0 发布。`plugins/types/` 收录的是接口声明（事实性内容）并逐文件标注来源；文档按 Aharou 的术语重写；实现代码一行未取。
:::

> 部分内容参照 Operit2 文档改写（AGPL-3.0）；导入规则、兼容分级与上限取自 Aharou 的插件导入实现。
