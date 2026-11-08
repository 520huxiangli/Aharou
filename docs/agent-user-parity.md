# Agent 与用户同权能力台账

这张表把“用户在界面上能做的事”和“agent 有没有对应工具”逐条对齐，用来暴露缺口，不用来论证应该少给 agent 工具。**每次新增用户可见功能（设置页新分区、编辑器新操作）或增删工具时都要回来核一遍**，等新增工具后再把对应行的“无”划掉。

## 0. 工具集合与事实来源

- 内置工具的唯一注册点是 `app/src/main/java/com/aharou/di/AgentModule.kt:453-489`，共 **37 个**：`readFile` `sendFile` `sendToChat` `viewImage` `writeFile` `editFile` `generateImage` `Bash` `Shizuku` `terminal` `list` `search` `loadSkill` `loadRule` `knowledge_search` `askUserQuestion` `manageMcp` `tool_search` `websearch` `webfetch` `planMode` `todo` `memory` `task` `messageParent` `browser` `config` `vscreen` `a11y` `open_app` `check_dependencies` `sessions` `device` `personal_context` `petAvatar` `diagnostics` `git_blame`。
- MCP 工具在连接时动态注册（`feature/agent/domain/mcp/McpManager.kt:266`，卸载见 `:272` / `:354`）。
- 每轮注入：`StatefulAgentWorkflow.kt:317` 取全部工具，`:568` 只过滤 `deferredLoading`（当前只有 MCP 工具延迟）；所以**内置的 37 个工具都在第一轮索引里**，MCP 工具要靠 `tool_search` 展开（`ToolSearchTool.kt:48`）。
- `config`（`di/AgentModule.kt:475`，实现 `feature/agent/domain/tool/config/ConfigTool.kt`）是一层**通用配置接口**：agent 能改什么，取决于 `ConfigRegistry` 里注册了哪些字段，注册入口集中在 `AIEditorApp.kt:409-434`（24 个 `Config*Fields`）。下文写“config `path`”即指这条路径。
- 权限档只影响“执行时要不要弹确认”（`ToolPermissionPolicyEngine`），**不影响工具有没有**；本表只统计有没有。

## 1. 自动化任务

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 查看任务列表（名称/启停/周期/下次运行/上次结果） | `feature/settings/presentation/component/ScheduledTasksSection.kt:79`、行渲染 `:132`、`:297` | 有 | `config` `action=get path=scheduledTasks`（`AgentModule.kt:475`；集合 `feature/agent/data/ConfigScheduledTaskFields.kt:75`） | 无独立任务工具，只有 config 集合；要先 `get scheduledTasks` 拿 id 再逐条 `get`，没有“一屏看全表”的动作 |
| 新建任务 | `ScheduledTasksSection.kt:147`（新建入口）、`:161`（`viewModel.save`）、编辑器 `:464` | 有 | `config` `action=add path=scheduledTasks`（`ConfigScheduledTaskFields.kt:341`） | add 只收 `name/prompt/schedule_type/...` 白名单字段，其余沿用实体默认值；漏传 `once_at_millis` 时 once 类型会被拒（`:365`） |
| 编辑任务（指令/周期/每天时刻/单次时刻/运行方式/触发方式/触发条件/目标会话/工作区/运行上限/防抖） | `ScheduledTasksSection.kt:133`（点行进编辑器） | 有 | `config` `action=set path=scheduledTasks.<id>.<字段>`（字段清单 `ConfigScheduledTaskFields.kt:88-338`） | 字段级写入会顺带重排调度（`:104`），无缺口 |
| 暂停 / 启用 | `ScheduledTasksSection.kt:132` | 有 | `config` `action=set path=scheduledTasks.<id>.enabled`（`ConfigScheduledTaskFields.kt:230` 区段） | 无 |
| 删除任务 | `ScheduledTasksSection.kt:197`（确认框）、滑删 `:238` | 有 | `config` `action=remove path=scheduledTasks id=<id>`（`ConfigScheduledTaskFields.kt:421`） | 无 |
| 看上次运行结果 / 已运行次数 / 下次运行 | `ScheduledTasksSection.kt:297`（`outcomeLabel(task.lastOutcome)`） | 有 | `config` `action=get` 只读字段 `run_count` / `next_run_at` / `last_run_at` / `last_outcome`（`ConfigScheduledTaskFields.kt:322-338`） | 无 |
| 手动跑一次 | 未找到（UI 无入口；`ScheduledTasksViewModel.kt:24-98` 只有 `save/delete/setEnabled`） | 无 | 未找到（config 通道也没有 run 动作，`ConfigTool.kt:113-150`） | 双方都没有：这条不是 agent 缺口，是功能缺口 |

## 2. 记忆

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 查看记忆清单（全局/项目切换） | `component/MemorySection.kt:348`（列表）、作用域切换 `:467` | 有 | `memory` `action=list`（`AgentModule.kt:471`；`feature/agent/domain/tool/memory/MemoryTool.kt:96`、`:305`） | 无 |
| 读取正文 | `MemorySection.kt:348`（点行进 `MemoryEditorPane` `:575`） | 有 | `memory` `action=read`（`MemoryTool.kt:438`） | 无；read 会回 `version` 供乐观锁 |
| 保存（新建/覆盖） | `MemorySection.kt:624`、`:632` | 有 | `memory` `action=save`（`MemoryTool.kt:497`） | 无；覆盖必须带 `version` |
| 局部编辑 | `MemorySection.kt:575`（同一编辑器改正文） | 有 | `memory` `action=edit`（`MemoryTool.kt:606`） | 无 |
| 删除 | `MemorySection.kt:156`、`:160` | 有 | `memory` `action=delete`（`MemoryTool.kt:648`） | 无 |
| 合并压缩（compact） | `MemorySection.kt:282` → `SettingsViewModel.kt:304` | 无 | 未找到（`memory` 的 action 枚举 `MemoryTool.kt:135` 无 compact；config 无对应字段） | **缺口**：只能等后台 `MemoryMaintenanceWorker`，无法即时触发 |
| 蒸馏（distill） | `MemorySection.kt:311` → `SettingsViewModel.kt:320` | 无 | 未找到 | **缺口**：同上 |
| 自动整理开关 | `MemorySection.kt:294` | 有 | `config` `memory.auto_organize`（`feature/agent/data/ConfigMemorySyncFields.kt:42`） | 无 |
| 立即同步 | `component/MemorySyncSection.kt:97`（`syncNow`） | 有 | `memory` `action=sync`（`MemoryTool.kt:250`） | 无；有 60 秒限频 |
| 同步配置（仓库地址/令牌/分支/间隔/自动同步） | `MemorySyncSection.kt:81`、`:89`、`:108`、`:118` | 有 | `config` `memory.sync.repo_url` / `.token` / `.branch` / `.auto` / `.interval_minutes`（`ConfigMemorySyncFields.kt:59` 起） | 无；token 是 SENSITIVE，需确认 |
| 看上次同步结果 / 时间 | `MemorySyncSection.kt:53` | 有 | `config` 只读 `memory.sync.last_result` / `.last_sync_at`（`ConfigMemorySyncFields.kt:128`、`:143`） | 无 |
| 回滚到归档 | `MemorySection.kt:392`（`restoreMemoryArchive` → `SettingsViewModel.kt:302`，可选具体归档） | 部分 | `memory` `action=undo`（`MemoryTool.kt:420`） | 只能回滚“最近一次归档”，不能像 UI 那样在归档列表里挑某一份 |
| 记忆总开关 | `MemorySection.kt:264` | 有 | `config` `memory.enabled`（`core/config/ConfigBuiltins.kt:167`） | 无 |
| 召回测试 | `MemorySection.kt:318`（`MemoryRecallTestSheet`） | 无 | 未找到 | **缺口**：用户能验证“某句话能不能召回”，agent 只能用 `memory search` 近似 |
| 使用统计/冷记忆清单 | 未找到独立界面（只在 `memory` 工具的 `stats` 里） | 有 | `memory` `action=stats` | 反向：agent 有、用户没有 |

## 3. 工作区 / 文件

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 切换工作区 | `feature/workspace/presentation/component/WorkspacePicker.kt:70`（顶栏图标）、`:128`（`selectWorkspace`） | 无 | 未找到（config 无“当前工作区”字段，`AIEditorApp.kt:409-434` 全部注册项里没有） | **缺口**：会话绑死自己的 `projectRoot`（`feature/agent/domain/tool/file/FileTools.kt:98` 注释明示“本会话的文件操作只落在自己的工作区”） |
| 新建工作区 | `WorkspacePicker.kt:179` | 无 | 未找到 | **缺口** |
| 重命名工作区 | `WorkspacePicker.kt:186` | 无 | 未找到 | **缺口** |
| 删除工作区 | `WorkspacePicker.kt:184` | 无 | 未找到 | **缺口** |
| 添加外部目录作为工作区 | `WorkspacePicker.kt:151`（系统目录选择器） | 无 | 未找到 | **缺口**，且需系统 SAF 选择器，agent 原理上办不到，只能由用户点 |
| 新建文件 / 文件夹 | `feature/agent/presentation/component/ChatDrawer.kt:1004`、`:1009` → `FileBrowseController.kt:294`、`:307` | 有 | `writeFile`（`AgentModule.kt:453`）、`Bash`（`:456`） | 无；`writeFile` 的 `overwrite=false` 对应“新建”语义 |
| 重命名文件 | `ChatDrawer.kt:1014`、`:1050` → `FileBrowseController.kt:320` → `MainActivity.kt:807-811` | 部分 | 无专用 rename；只能 `Bash` `mv`（`ExecuteCommandTool`） | 无原子重命名工具，文件名校验/冲突处理要自己写命令 |
| 删除文件 | `ChatDrawer.kt:1018`、`:1075` → `FileBrowseController.kt:332`；沙盒浏览器 `feature/sandbox/FileBrowserScreen.kt:363` | 部分 | 无专用 delete；只能 `Bash` `rm` | 同上；且 `rm` 走 shell，绕开了 UI 的删除确认 |
| 复制 / 剪切 / 粘贴 | `ChatDrawer.kt:1024`、`:1028`、`:1035`（粘贴覆盖 `:404`）→ `FileBrowseController.kt:345`、`:350`、`:365` | 部分 | 无专用工具；`Bash` `cp`/`mv` | 同上 |
| 打开 / 预览文件 | `ChatDrawer.kt:938`（`onOpenFile`）→ `MainActivity.kt:795`；沙盒 `FileBrowserScreen.kt:195` → `FilePreviewScreen.kt:99` | 有 | `readFile`（`AgentModule.kt:449`）、`viewImage`（`:452`） | 无；图片走 `viewImage`，文本走 `readFile` |

## 4. 模型与供应商配置

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 新增供应商 | `component/SettingsScreen.kt:745-752`（顶栏 +） | 有 | `config` `action=add path=providers`（`domain/tool/config/ConfigTool.kt:349`；集合 `feature/settings/data/ConfigProviderFields.kt:86`、`:446`） | 无；api_key 类字段是 SENSITIVE，需弹确认 |
| 编辑供应商（baseUrl/key/多 key/模型/面板脚本/代理/自定义头/缓存开关） | `component/ProvidersAndLogSection.kt:113` → `ProviderEditorScreen.kt:169`（`:398` 保存） | 有 | `config` `action=set path=providers.<id>.<字段>`（字段表 `ConfigProviderFields.kt:85-100` 描述行） | 无 |
| 删除供应商 | `ProvidersAndLogSection.kt:114` | 有 | `config` `action=remove path=providers id=<id>`（`ConfigProviderFields.kt:470`） | 无 |
| 选当前会话的模型 | `component/ChatModelSheet.kt:56`（`ModelIconButton`）、`:89`（`onSelect`）→ `ChatInputBar.kt:584` → `AIChatPanel.kt:1505` | 无 | 未找到（`sessions` 只读，`SessionTool.kt:31`；config 无会话级模型字段） | **缺口**：只能改全局默认/角色模型，不能改当前会话 |
| 设全局角色模型（默认/标题/压缩/视觉/生图） | `component/DefaultModelsSection.kt:93`、挂载 `SettingsScreen.kt:1024` | 有 | `config` `model.roles.default` / `.default_effort` / `.title` / `.compaction` / `.vision` / `.image_gen`（`feature/settings/data/ConfigModelRoleFields.kt:41`、`:51`、`:82`、`:92`、`:102`、`:112`） | 无 |
| 改采样温度 | 未找到（用户界面也没有） | 无 | 未找到 | 双方都没有：温度由固定表决定（`feature/agent/domain/provider/AIProvider.kt:251` `fixedTemperature`），不是可配置项 |
| 改思考强度（reasoning effort） | `ChatModelSheet.kt:107`（`ReasoningEffortSelector`）→ `AIChatPanel.kt:1511` | 无 | 未找到（无 `reasoning_effort` 配置字段） | **缺口**：会话级设置，agent 无法改 |
| 测试模型/供应商连通性 | `component/ProviderModelComponents.kt`、`DefaultModelsSection.kt` | 有 | `config action=test_model provider_id=<id> model=<model>`，复用 `ModelApiService` | 极短真实请求，可能计费；不改配置、不返回凭据 |

## 5. 技能

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 从本机导入/安装技能 | `SettingsScreen.kt:830-838`（`showSkillAddSheet`），导入解析在 `feature/agent/domain/skill/SkillImport.kt` | 部分 | 无安装工具；按 `feature/agent/data/ConfigSkillFields.kt:22-24` 的注释，“新建/删除技能该走文件工具”——用 `writeFile` 在 `~/.aharou/skills/<名>/SKILL.md` 建目录（全局技能根见 `SkillRepository.kt:14`） | 没有“解析 zip/md 并校验 frontmatter”的能力，agent 只能手写文件，格式错了不会报 |
| 从技能市场搜索/安装 | `SettingsScreen.kt:1121`、`:1139`（`installFromMarket`） | 无 | 未找到（只能 `webfetch` 市场页面） | **缺口** |
| 启用/停用 | `component/SkillDetailSection.kt:70`、`:72` → `SettingsScreen.kt:1148` | 有 | `config` `action=set path=skill.skills.<名>.disabled`（`ConfigSkillFields.kt:54`） | 写入只改全局禁用名单，项目级名单不动（`ConfigSkillFields.kt:66`） |
| 读取技能正文 | `SkillDetailSection.kt:39`、`:121`（详情页正文） | 有 | `loadSkill`（`AgentModule.kt:461`；`domain/tool/skill/LoadSkillTool.kt:30`） | 无 |
| 编辑技能正文 | `SettingsScreen.kt:845`（编辑入口 → `SkillEditor`） | 有 | `writeFile` / `editFile`（`AgentModule.kt:453`、`:454`） | 无专用技能编辑器，但文件工具等价 |
| 删除技能 | `component/SkillsSection.kt:158`（批量删除确认） | 部分 | 无专用工具；`Bash` `rm -r` | 无目录合法性校验，容易误删（`SkillRepository.kt:247` 有“仅当目录含 SKILL.md 才删”的保护，agent 侧没有） |
| 导出技能 | `SkillDetailSection.kt:121`（`onExport`） | 无 | 未找到 | **缺口** |

## 6. MCP

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 添加 server（stdio / http） | `SettingsScreen.kt:767-775`（顶栏 + → `McpDialogRequest`） | 有 | `manageMcp` `action=add_stdio` / `add_http`（`AgentModule.kt:465`；`domain/tool/mcp/ManageMcpTool.kt:54`）；也可 `config` `action=add path=mcp.servers`（`feature/agent/data/ConfigMcpFields.kt:57`、`:202`） | 新配置下次会话生效（`ManageMcpTool.kt:117` 自述） |
| 移除 server | `component/McpSettingsSection.kt:246` | 有 | `manageMcp` `action=remove`、`config` `remove mcp.servers`（`ConfigMcpFields.kt:254`） | 无 |
| 列出 server | `McpSettingsSection.kt:245`（列表行） | 有 | `manageMcp` `action=list`、`config get mcp.servers` | 无 |
| 编辑 server 参数 | `McpSettingsSection.kt:245`（`onEdit`） | 有 | `config` `action=set path=mcp.servers.<id>.<段>`（`ConfigMcpFields.kt:67-98`） | 无 |
| 启用/停用 server | `SettingsScreen.kt:1077`（`setMcpServerEnabled`） | 有 | `config` `mcp.servers.<id>.enabled`（`ConfigMcpFields.kt:174`） | 无 |
| 重连接 / 刷新连接 | `SettingsScreen.kt:755`（`viewModel.reloadMcp()`） | 无 | 未找到（`config set enabled` 是否触发重连未核实） | **缺口**：连不上时只能改配置或等下轮会话，没有“立刻重连”的动作 |

## 7. 规则文件

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 编辑全局规则 | `component/RulesSection.kt:56`（列表）、`:71`（保存）、`:187-193`（停笔自动保存） | 有 | `config` `rules.global`（`feature/agent/data/ConfigRuleFields.kt:33`，写回生效那份 AGENTS.md/CLAUDE.md）；或用 `writeFile` 直写 `~/.aharou/AGENTS.md` | 无 |
| 编辑项目规则 | `RulesSection.kt:56`、作用域切换 `:96` | 有 | `config` `rules.project`（`ConfigRuleFields.kt:47`）+ `writeFile` 写 `<工作区>/AGENTS.md` | 无工作区时写入会报错（`ConfigRuleFields.kt:53`） |
| 新建 / 编辑 `rules.d` 片段 | `RulesSection.kt:106`、`:152`（新建）、`:187`（编辑自动存） | 部分 | 无专用工具；`config rules.fragments` 是只读清单（`ConfigRuleFields.kt:57`），写入靠 `writeFile` 到 `~/.aharou/rules.d/` 或 `<工作区>/rules.d/` | 片段命名规则（`RuleFileRepository.kt:122` 正则）要自己遵守，写错不报 |
| 删除规则片段 / 清空主规则 | `RulesSection.kt:221`（删）、`:304`（确认） | 部分 | 主规则清空可用 `config set rules.global ""`；片段删除只能 `Bash rm` | 无专用删除动作 |
| 读取生效规则正文 | `RulesSection.kt:56`（列表展示摘要与路径） | 有 | `loadRule`（`AgentModule.kt:462`；`domain/tool/rule/LoadRuleTool.kt:29`） | 无 |

## 8. 远程设备（跨设备协作）

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 开/关本机 MCP 服务 | `feature/remotedevice/presentation/RemoteDeviceSection.kt:105` | 有 | `config` `remote.device.enabled`（`feature/remotedevice/data/ConfigRemoteDeviceFields.kt:36`） | 无 |
| 查看配对码 | `RemoteDeviceSection.kt:119`（配对码面板 `:188`） | 有 | `config` `action=get path=remote.device.pair_code`（只读，`ConfigRemoteDeviceFields.kt:153`） | 只读；要换码得等对端配对成功后自动轮换 |
| 接受协助（主动连到别人） | `RemoteDeviceSection.kt:131`、`:139`（`acceptAssistance`） | 无 | 未找到（config 只有只读 state/pair_code） | **缺口**：agent 不能代用户发起一次协助连接 |
| 改端口 | 有意不做界面：设置页没有端口输入，需要时由 agent 走 `config` | 有 | `config` `remote.device.port`（`ConfigRemoteDeviceFields.kt:53`） | 只有端口撞车才需要改，不做入口（2026-11-03 定） |
| 查看已配对设备 | `RemoteDeviceSection.kt:135`（`devices.forEachIndexed` 列表） | 有 | `config` `action=get path=remote.devices.<设备id>`（`ConfigRemoteDeviceFields.kt:211`） | 有 |
| 撤销某台设备授权 | `RemoteDeviceSection.kt:144`（`viewModel.revoke`） | 有 | `config` `action=remove path=remote.devices id=<设备id>`（`ConfigRemoteDeviceFields.kt:246`） | 有 |

## 9. IM 接入

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 启用/停用渠道（微信/飞书/QQ/Telegram） | 挂载 `SettingsScreen.kt:1266-1269`；`component/TelegramBotSection.kt:86`（以 Telegram 为例） | 有 | `config` `im.telegram.enabled` / `im.weixin.enabled` / `im.feishu.enabled` / `im.qq.enabled`（`feature/im/data/ConfigImFields.kt:35`、`:84`、`:134`、`:184`） | 无 |
| 填 token / app_id / app_secret / base_url / 代理 | `TelegramBotSection.kt:121-140`（token 输入） | 有 | `config` `im.<渠道>.bot_token` / `.app_id` / `.app_secret` / `.base_url` / `.proxy`（`ConfigImFields.kt:45-221`） | 凭证是 SENSITIVE，写入需弹确认 |
| 看连接状态 | 各平台机器人设置页状态行 | 有 | `config get im.<渠道>.state` / `.error`，`ConfigImFields` 读取四个桥接实例的运行状态 | 只读，错误脱敏，不返回身份或凭据 |
| 向当前 IM 会话发文件/图片 | 用户在 IM 客户端聊天（无 App 内入口） | 有 | `sendToChat`（`SendImFileTool.kt`） | 按调用会话映射到来源聊天，不回退到最近活跃聊天；支持个人 QQ |
| 安装、更新个人 QQ 运行环境 | `NapCatBotSection.kt` 安装与更新 | 有 | `napcat action=install/update`（`feature/im/NapCatTool.kt`） | 按需下载独立 Debian、Linux QQ 和 NapCat，不更改默认开发容器 |
| 启动、停止个人 QQ | `NapCatBotSection.kt` 运行管理 | 有 | `napcat action=start/stop`；`config im.qq_napcat.enabled` | UI 与配置调用同一前台服务 |
| 个人 QQ 登录、账号状态与退出 | `NapCatBotSection.kt` 登录区域、`NapCatWebUiSheet.kt` | 有 | `napcat action=login/refresh_qr/logout/status` | 扫码、账密与验证码需用户在登录界面完成；工具不返回密码和登录二维码 |
| 个人 QQ 私聊、群聊响应与允许列表 | `NapCatBotSection.kt` 消息响应 | 有 | `config im.qq_napcat.respond_private/respond_groups/require_mention/respond_self`；`allowed_users/allowed_groups` 集合 add/remove | 自消息过滤保留防循环；绑定记录按登录账号隔离 |
| 个人 QQ 新会话工作区与模型 | `NapCatBotSection.kt` 工作区、模型选择 | 有 | `config im.qq_napcat.session_defaults` 对象 | 使用已有会话字段；未指定且没有当前工作区时自动创建机器人工作区，不切换当前项目 |
| 个人 QQ 日志与高级管理 | `NapCatBotSection.kt` 日志和 WebUI | 有 | `napcat action=logs/webui/status`；`config im.qq_napcat.runtime/login/state/version/error` | 工具提供脱敏日志与不含令牌的本机入口，完整交互在内嵌 WebUI |

## 10. 会话

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 新建会话 | `component/ChatHeaderComponents.kt:115` → `AIChatPanel.kt:1209`（`newSession`） | 无 | 未找到（`task` 派生的是子代理会话，不是用户会话） | **缺口** |
| 切换会话 | `MainActivity.kt:838`（`selectSession`） | 无 | `sessions` 只有 list/search/read | **缺口** |
| 重命名会话 | `ChatDrawer.kt:436`、`:508` → `MainActivity.kt:840` | 无 | 未找到 | **缺口** |
| 删除会话 | `ChatDrawer.kt:450`、`:471` → `MainActivity.kt:839` | 无 | 未找到 | **缺口** |
| 置顶/取消置顶 | `ChatDrawer.kt:1391` → `MainActivity.kt:841` | 无 | 未找到 | **缺口** |
| 导出会话 | `MainActivity.kt:842-845`（`sessionExportLauncher`） | 无 | 未找到 | **缺口** |
| 归档会话 | 未找到（`ChatSessionEntity.kt:19-52` 无归档字段，只有 `isPinned`/`ghostMode`） | 无 | 未找到 | 双方都没有：这条能力不存在 |
| 浏览/搜索其它会话内容 | `ChatDrawer.kt:337`、`:556`（列表）、搜索命中 `:348` | 有 | `sessions` `action=list/search/read`（`AgentModule.kt:480`；`domain/tool/session/SessionTool.kt:31`） | 严格只读，且限当前工作区（`:150` 跨工作区拒绝） |

## 11. 备份

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 导出备份到本地文件 | `feature/backup/presentation/BackupSection.kt:357`（`exportLauncher`）、`BackupViewModel.kt:133` | 无 | 未找到 | **缺口**：导出要系统文件选择器 + 加密口令，agent 无法代做 |
| 从备份导入/恢复 | `BackupSection.kt:380`（`viewModel.import`）、`BackupViewModel.kt:149` | 无 | 未找到 | **缺口** |
| 改导出范围（8 个开关） | `BackupSection.kt:135-195` | 有 | `config` `backup.providers` / `.remote_connections` / `.chat_history` / `.mcp_servers` / `.permission_rules` / `.app_settings` / `.workspace_files` / `.memory_and_soul`（`feature/settings/data/ConfigBackupFields.kt:54-71`） | 无 |
| 网络备份目标增删改/测试连通 | `BackupSection.kt:336`（上传入口）、`BackupViewModel.kt:345`、`:355`、`:359` | 无 | 未找到 | **缺口** |
| 上传到网络目标 / 从网络恢复 | `BackupSection.kt:336`、`:338`、`BackupViewModel.kt:374`、`:446` | 无 | 未找到 | **缺口** |

## 12. 浏览器

| 用户能做什么 | 界面入口（file:line） | Agent 有对应工具吗 | 工具名与入口（file:line） | 差距说明 |
|---|---|---|---|---|
| 新建标签页 | `feature/browser/presentation/BrowserSheet.kt:185`（`newTabFromUI`） | 有 | `browser` `action=new_tab`（`AgentModule.kt:474`；`feature/browser/BrowserTool.kt:34-40`） | 无；上限 3 个标签 |
| 切换 / 关闭标签页 | `BrowserSheet.kt:211`（`selectTab`）、`:212`（`closeTabFromUI`） | 有 | `browser` `list_tabs` / `close_tab` | 无 |
| 改 UA 档位 / 自定义 UA | `presentation/BrowserSettingsSheet.kt:183`、`:215` | 有 | `browser` `action=set_user_agent`；`config` `browser.user_agent_profile`（`feature/browser/data/ConfigBrowserFields.kt:34`）、`browser.custom_user_agent`（`:58`） | 无 |
| 改视口（宽/高或回默认） | `BrowserSettingsSheet.kt:229-280` | 有 | `browser` `action=set_viewport`；`config` `browser.viewport_width` / `.viewport_height`（`ConfigBrowserFields.kt:74`、`:91`） | 无 |
| 改标签空闲回收时长 | `BrowserSettingsSheet.kt:323` | 有 | `config` `browser.idle_timeout_minutes`（`ConfigBrowserFields.kt:108`） | 无 |
| 页内开发者工具开关 | `presentation/BrowserMenuSheet.kt:58-67`（Eruda） | 有 | `config` `browser.devtools_enabled`（`ConfigBrowserFields.kt:134`） | 无 |

## 13. 缺口汇总

统计（92 条能力）：**有对应工具 55 条 / 只能靠 Bash 或文件工具临时替代 8 条 / agent 完全没有 26 条 / 双方都没有 3 条**；另有 1 条反向缺口（远程设备端口，属有意不做界面）。

### A. 用户能做、agent 完全没有工具（26 条）

1. 手动跑一次自动化任务 —— 双方都无（不计入 26，列在此处便于对照）
2. 记忆：合并压缩（`MemorySection.kt:282`）
3. 记忆：蒸馏（`MemorySection.kt:311`）
4. 记忆：召回测试（`MemorySection.kt:318`）
5. 工作区：切换（`WorkspacePicker.kt:128`）
6. 工作区：新建（`WorkspacePicker.kt:179`）
7. 工作区：重命名（`WorkspacePicker.kt:186`）
8. 工作区：删除（`WorkspacePicker.kt:184`）
9. 工作区：添加外部目录（`WorkspacePicker.kt:151`）
10. 模型：选当前会话的模型（`ChatModelSheet.kt:89`）
11. 模型：改思考强度（`ChatModelSheet.kt:107`）
12. 供应商：测试连通性已由 `config action=test_model` 补齐
13. 技能：从市场搜索/安装（`SettingsScreen.kt:1121`）
14. 技能：导出（`SkillDetailSection.kt:121`）
15. MCP：重连接/刷新（`SettingsScreen.kt:755`）
16. 远程设备：接受协助（`RemoteDeviceSection.kt:139`）
17. IM：运行状态查询已由 `config im.<渠道>.state/error` 补齐
18. 会话：新建（`ChatHeaderComponents.kt:115`）
19. 会话：切换（`MainActivity.kt:838`）
20. 会话：重命名（`ChatDrawer.kt:508`）
21. 会话：删除（`ChatDrawer.kt:471`）
22. 会话：置顶（`ChatDrawer.kt:1391`）
23. 会话：导出（`MainActivity.kt:842`）
24. 备份：导出（`BackupSection.kt:357`）
25. 备份：导入/恢复（`BackupSection.kt:380`）
26. 备份：网络目标增删改/测试（`BackupViewModel.kt:345`、`:359`）
27. 备份：上传/从网络恢复（`BackupViewModel.kt:374`、`:446`）

（第 1 条属于“双方都没有”，不计入 26；26 条为第 2–27 条。）

按域看，缺口最集中的是**会话（6 条）**、**工作区（5 条）**、**备份（4 条）**：这三块都是“App 自身状态管理”，工具范围里只有文件、配置、只读会话浏览三类接口，没有“改 App 内数据”的写通道。

### B. 只能靠 `Bash` / 文件工具临时替代（8 条）

文件重命名、文件删除、复制/剪切/粘贴、`rules.d` 片段新建与编辑、规则片段/主规则删除、技能导入安装、技能删除、（补）文件新建虽已用 `writeFile` 覆盖，但“文件夹新建”同样只有 `Bash`（`writeFile` 不建目录）。

共同问题：这些动作**没有专用工具，所以不在工具名层面可发现**——agent 得先知道“可以用 shell 凑”，才做得出来；也没有 UI 那层的合法性校验（片段命名、技能目录必须含 SKILL.md）。

### C. 反向缺口：agent 有 `config` 字段，用户却没有界面入口（1 条，且属有意设计）

- 远程设备端口：`ConfigRemoteDeviceFields.kt:53` 可写；设置页没有端口输入——**有意不做界面**（只有端口撞车才需要改，不做入口），不算缺口。
- （已消）已配对设备列表与撤销设备授权均已接入设置页：`RemoteDeviceSection.kt:135` 列表、`:144` 撤销按钮（提交 `201e3c60`）。

### D. 双方都没有（3 条）

采样温度调节、会话归档。自动化任务「手动跑一次」已明确不做（2026-11-03 主人定，调试用单次任务凑合）。写在这里是为了说明：它们不是 agent 工具缺口，不要照着补工具。

## 14. 方法说明

- **“有工具”的判据**：`di/AgentModule.kt:453-489` 里出现注册名，且工具实现里确实有对应动作（逐个读了 `MemoryTool.kt`、`ConfigTool.kt`、`SessionTool.kt`、`ManageMcpTool.kt`、`BrowserTool.kt`、`LoadSkillTool.kt`、`LoadRuleTool.kt`、`SendImFileTool.kt`、`PlanModeTool.kt` 的 `name`/`description`/`parameters`）。
- **`config` 通道算不算“有工具”**：算。它同样是 agent 可调用的工具，只是能力面由字段注册决定；因此“有 config 字段”一律记为“有”，并在差距说明里注明“无独立工具、要靠 `config list` 才能在索引里发现”。
- **界面入口的判据**：从 `component/SettingsScreen.kt:123-178`（`SettingsSection` 枚举）与 `:963-1331`（分区到组件的映射）找到分区文件，再在该文件里定位真正触发调用的 `onClick`/`viewModel.*` 行；不按文件名猜。
- **没读到就不写“有”**：所有“无 / 未找到”都做了全仓 `rg`（关键词含 `rename`、`revoke`、`setPort`、`runNow`、`temperature`、`archived`、`reasoning_effort`、`testConnection` 等），并检查了 `Config*Fields` 的注册清单（`AIEditorApp.kt:409-434`）。

不确定项（列出来，不猜）：

1. **MCP 重连接**：`viewModel.reloadMcp()` 是否等价于“改 `mcp.servers.<id>.enabled` 后自动重连”，未核实 `McpManager` 的监听链路；表里按“无工具”记。
2. **技能导入**：`SkillImport.kt` 的 zip/md 解析没有对应工具，但“用 `writeFile` 手写 SKILL.md 能否被正确扫描并出现在 `skill.skills` 集合里”未实测（只读到 `SkillRepository.kt:14` 的目录约定与 `ConfigSkillFields.kt:22-24` 的注释）。
3. **记忆 compact / distill**：确认没有工具与 config 字段，但后台 `MemoryMaintenanceWorker` 的触发条件未逐行读，因此“agent 完全无法触发”是按工具范围下的结论，不等于“这两件事永远不会发生”。
4. **会话导出**（`MainActivity.kt:842-845`）的具体产物格式未核实，只确认入口存在且无对应工具。
5. `docs/` 下此前没有本文件；本表未接入任何索引（`docs/FILE_INDEX.md` 未改，任务只允许写一份文档）。
