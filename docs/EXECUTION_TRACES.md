# 主要运行时调用链（EXECUTION_TRACES）

> **用途**：改代码前先看清一条链从哪进、经过谁、落到哪。
> 行号取自 **2026-10-04** 的代码快照，会随代码漂移；精确定位请以「类名 / 函数名」配合 `search` 为准。
> 文件定位见 [`FILE_INDEX.md`](FILE_INDEX.md)，构建命令与门禁见仓库根 `CLAUDE.md`。

## 1. 一次 AI 对话的完整链路

从用户按下发送到消息落库、界面出新气泡：

| # | 环节 | 关键位置 |
| --- | --- | --- |
| 1 | 输入框发送 | [AIChatPanel.kt:1313](app/src/main/java/com/aharou/feature/agent/presentation/component/AIChatPanel.kt:1313) → `viewModel.enqueueAgentRequest(...)` |
| 2 | 入队 / 直发 | [AIAgentViewModel.kt:1677](app/src/main/java/com/aharou/feature/agent/presentation/AIAgentViewModel.kt:1677) `enqueueAgentRequest`：忙碌进 `_queuedRequests`，空闲调 `executeAgentRequestStream` |
| 3 | 斜杠命令短路 | [AIAgentViewModel.kt:1847](app/src/main/java/com/aharou/feature/agent/presentation/AIAgentViewModel.kt:1847)：命中命令即执行并返回，不进循环 |
| 4 | 组装请求 | [AgentTurnRunner.kt:41](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:41) `AgentTurnRequest` |
| 5 | 跑一轮 | [AgentTurnRunner.kt:125](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:125) `run(turn): Flow<AgentEvent>` |
| 6 | **先取历史，再落用户消息** | [AgentTurnRunner.kt:142](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:142) `buildHistory(...)` → [147](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:147) `persist(用户消息)` |
| 7 | 装配工具 | [AgentTurnRunner.kt:196](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:196) `toolRegistry.getAvailableTools()`，按子代理规则过滤 |
| 8 | 进入循环 | [AgentWorkflow.kt:102](app/src/main/java/com/aharou/feature/agent/domain/workflow/AgentWorkflow.kt:102) 接口 → [StatefulAgentWorkflow.kt:519](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:519) 实现（`channelFlow`） |
| 9 | 主循环 | [StatefulAgentWorkflow.kt:561](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:561) `while (!isFinished && actionQueue.isNotEmpty())` → `reduce(state, action)` 产出 `effects` |
| 10 | 调模型 | [StatefulAgentWorkflow.kt:573](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:573) `CallLlm` → `providerInUse.completeStream(...)` |
| 11 | 模型回复入队 | [StatefulAgentWorkflow.kt:760](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:760) 发 `AssistantText` → `LlmResponse(...)` |
| 12 | 权限判定 | [StatefulAgentWorkflow.kt:846](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:846) `RequestPermission` |
| 13 | 执行工具批 | [StatefulAgentWorkflow.kt:876](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:876) `ExecuteToolBatch`：先记检查点，再并行执行、批内去重 |
| 14 | 结果回填 | [StatefulAgentWorkflow.kt:490](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:490) `ToolBatchFinished` → 追加 `ToolResultMessage` → 再 `CallLlm` |
| 15 | 落库（旁路） | [AgentTurnRunner.kt:213](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:213) `collect`：**先 emit 转发、后 persist** |
| 16 | 事件回界面 | [AIAgentViewModel.kt:1885](app/src/main/java/com/aharou/feature/agent/presentation/AIAgentViewModel.kt:1885) 映射为各 `StateFlow` → [AIChatPanel.kt:577](app/src/main/java/com/aharou/feature/agent/presentation/component/AIChatPanel.kt:577) 订阅渲染 |

**硬约束**

- **顺序**：`buildHistory` 必须在持久化本次用户消息**之前**（[:142](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:142) 先于 [:147](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:147)），否则用户消息重复进上下文。
- **先转发、后落库**：事件在落库前 emit，界面先清流式气泡再进落库消息；调用方 `collect` 里做耗时操作会拖住落库。
- **占位与配对**：`ToolCallStarted` 落 `PENDING_TOOL_MARKER` 占位 TOOL 消息，[MessagePersistenceUseCase.kt:239](app/src/main/java/com/aharou/feature/agent/domain/session/MessagePersistenceUseCase.kt:239) 回放时丢弃未配对的占位结果。
- **收尾兜底**：一轮 `Completed` 触发 `agentWorkflow.curateMemory(...)` 记忆抽取，同会话有冷却窗口。

## 2. 容器启动与初始化

| # | 环节 | 关键位置 |
| --- | --- | --- |
| 1 | 进入终端页 | [TerminalScreen.kt:117](app/src/main/java/com/aharou/feature/terminal/presentation/component/TerminalScreen.kt:117) 订阅 `containerInit`，[:493](app/src/main/java/com/aharou/feature/terminal/presentation/component/TerminalScreen.kt:493) `containerInitMessage(...)` |
| 2 | ViewModel 初始化 | [TerminalViewModel.kt:103](app/src/main/java/com/aharou/feature/terminal/presentation/TerminalViewModel.kt:103) `init { prepare() }` |
| 3 | 首次标签 | [TerminalSessionManager.kt:96](app/src/main/java/com/aharou/feature/terminal/domain/TerminalSessionManager.kt:96) `ensureInitialTab()` → [:109](app/src/main/java/com/aharou/feature/terminal/domain/TerminalSessionManager.kt:109) `createInteractiveTab()` → [:111](app/src/main/java/com/aharou/feature/terminal/domain/TerminalSessionManager.kt:111) `createTab()` |
| 4 | 确保容器就绪 | [TerminalSessionManager.kt:311](app/src/main/java/com/aharou/feature/terminal/domain/TerminalSessionManager.kt:311) `ensureContainer()` → `containerEngine.ensureInstalled()` |
| 5 | 接口 | [CommandEngine.kt:121](app/src/main/java/com/aharou/feature/agent/domain/container/CommandEngine.kt:121) `ensureInstalled()` / [:115](app/src/main/java/com/aharou/feature/agent/domain/container/CommandEngine.kt:115) `notReadyHint()` |
| 6 | 本地实现 | [LinuxContainerEngine.kt:539](app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt:539) `ensureInstalled()`：已装走快路径，否则 `initScope.launch { doInit(profile) }` |
| 7 | 真正安装 | [LinuxContainerEngine.kt:633](app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt:633) `doInit(profile)` → `ContainerInstaller.installRootfsIfNeed(profile)` |
| 8 | 安装步骤 | [ContainerInstaller.kt:424](app/src/main/java/com/aharou/feature/agent/domain/container/ContainerInstaller.kt:424)：`purgeRootfs` → `mkdirs` → `extractRootfs` → `configureResolvConf` → 写安装标记 |
| 9 | 进度状态 | [ContainerInitState.kt:7](app/src/main/java/com/aharou/feature/agent/domain/container/ContainerInitState.kt:7)（Idle / CleaningOldRootfs / ExtractingRootfs / InstallingPackages / Ready / Failed） |
| 10 | 拉起 proot | [LinuxContainerEngine.kt:728](app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt:728) `buildProotInvocation(...)` → [:741](app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt:741) `buildBaseProotArgv` |
| 11 | 进程启动 | [LinuxContainerEngine.kt:673](app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt:673) `buildProcessBuilder(...).start()`；命令路径 [:473](app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt:473) 未装时回退原生 shell |

**要点**

- **唯一初始化入口**：[CommandEngine.kt:120](app/src/main/java/com/aharou/feature/agent/domain/container/CommandEngine.kt:120) 注释约定 `ensureInstalled` 只由终端页调用，命令执行入口不再自动触发；未就绪时错误文案引导用户去终端页。
- **快慢路径**：已安装直接 Ready 且不回调进度；解压失败置 `Failed` 并抛异常，不会静默当成功。
- **初始化在引擎级 `initScope`**：退出终端页不中断解压。
- **基础包不在这一步装**：由进入终端时的初始化菜单执行容器内的 `provision.sh` 引导。
- 远程 SSH 模式下 `RemoteSshEngine.ensureInstalled()` 只建连接，`initProgress` 通常恒为 Ready。

## 3. MCP 连接与工具注册

| # | 环节 | 关键位置 |
| --- | --- | --- |
| 1 | 启动订阅 | [McpManager.kt:61](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpManager.kt:61) `start()`：跟随工作区、外部配置变更、运行时容器变化触发 reload |
| 2 | App 初始化 | [AIEditorApp.kt:411](app/src/main/java/com/aharou/AIEditorApp.kt:411) `mcpManager.start()` |
| 3 | 全量重连 | [McpManager.kt:93](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpManager.kt:93) `reload()`：`teardown()` → 并行 `connectOne(cfg)` |
| 4 | 单 server 连接 | [McpManager.kt:126](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpManager.kt:126) `connectOne`：stdio 型先查容器就绪（未就绪报错引导去终端页） |
| 5 | 注册工具 | [McpManager.kt:155](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpManager.kt:155) `McpTool(client, it)` → `toolRegistry.register(...)` |
| 6 | 断连清理 | [McpManager.kt:266](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpManager.kt:266) `removeServer` → [:271](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpManager.kt:271) `teardownServer`（关 client + 逐个 unregister） |
| 7 | 工具工厂 | [McpTool.kt:29](app/src/main/java/com/aharou/feature/agent/domain/mcp/McpTool.kt:29)：命名空间 `mcp__<server>__<tool>`，`permissionPolicy = ASK`，`deferredLoading = true` |
| 8 | 按需展开 | [ToolSearchTool.kt:82](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolSearchTool.kt:82) `toolRegistry.activate(...)`；每轮过滤见 [StatefulAgentWorkflow.kt:648](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:648) |
| 9 | 注册表 | [ToolRegistry.kt:14](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolRegistry.kt:14) register / [:19](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolRegistry.kt:19) getTool / [:43](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolRegistry.kt:43) getDeferredTools / [:49](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolRegistry.kt:49) activate |

**要点**

- **stdio server 跑在运行时容器上**，容器未就绪**不自动初始化**，直接失败并提示。
- **MCP 工具默认 `deferredLoading = true`**：不进每轮 tools 数组，模型用 `tool_search` 按需展开；权限 `ASK`，可「始终允许」记忆。
- `activate` 只增不清，卸载 server 时才移除对应工具。

## 4. 工具执行与权限

| # | 环节 | 关键位置 |
| --- | --- | --- |
| 1 | 模型返回 tool_call | [StatefulAgentWorkflow.kt:357](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:357) `LlmResponse`：有调用则入权限队列 |
| 2 | 到达调用上限 | [StatefulAgentWorkflow.kt:382](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:382) `MAX_TOTAL_TOOL_CALLS` → 补 `TOOL_CALL_LIMIT` 并收尾 |
| 3 | 查找工具 | [ToolRegistry.kt:19](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolRegistry.kt:19) `getTool(name)` |
| 4 | 权限入口 | [StatefulAgentWorkflow.kt:1510](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1510) `requestPermissionIfNeeded(...)` |
| 5 | 策略评估 | [ToolPermissionPolicyEngine.kt:132](app/src/main/java/com/aharou/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt:132) `evaluate(...)`（DENY / ALLOW / ASK） |
| 6 | 免问短路 | [StatefulAgentWorkflow.kt:1550](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1550) `AUTO_APPROVE` 直接放行 |
| 7 | 挂起等用户 | [ToolPermissionManager.kt:30](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolPermissionManager.kt:30) `awaitApproval(...)`，界面 [:48](app/src/main/java/com/aharou/feature/agent/domain/tool/ToolPermissionManager.kt:48) `resolve(id, choice)` 唤醒 |
| 8 | 「始终允许」 | [StatefulAgentWorkflow.kt:1572](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1572) → [ToolPermissionPolicyEngine.kt:266](app/src/main/java/com/aharou/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt:266) `remember(...)` |
| 9 | 执行 | [AgentTool.kt:119](app/src/main/java/com/aharou/feature/agent/domain/tool/AgentTool.kt:119) `executeWithContext(...)`；流式 [AgentTool.kt:178](app/src/main/java/com/aharou/feature/agent/domain/tool/AgentTool.kt:178) |
| 10 | 非流式 / 流式 | [StatefulAgentWorkflow.kt:1057](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1057) `runToolSync` / [:1325](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1325) `runToolStream` |
| 11 | 结果回填 | [StatefulAgentWorkflow.kt:490](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:490) `ToolBatchFinished` → 生成 `ToolResultMessage` → 下一轮 `CallLlm` |
| 12 | 结果落库 | [AgentTurnRunner.kt:279](app/src/main/java/com/aharou/feature/agent/domain/runner/AgentTurnRunner.kt:279) `persist(..., MessageRole.TOOL, ...)` |

**要点**

- **一次响应多个 tool_call 必须全部批准才执行**：首个用户拒绝即整批取消，且必须按原顺序补全 tool 消息，否则 OpenAI 报 `insufficient tool messages following tool_calls`。
- **批内去重**：同名同参只执行一次，重复项返回 `DUPLICATE_TOOL_CALL`，但仍占一条 tool 消息保持与 `assistant(toolCalls)` 对齐（[StatefulAgentWorkflow.kt:903](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:903)）。
- **模式切换串行化**：工具批执行完后在主协程处理；退出 PLAN 会挂起等计划审查面板批准（[StatefulAgentWorkflow.kt:930](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:930) 附近）。
- **流式优先**：实现了 `executeStream` 的工具永不走非流式路径（[StatefulAgentWorkflow.kt:918](app/src/main/java/com/aharou/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:918)）。
- **Shizuku 工具**一律 `ASK` 且不可记忆（[ToolPermissionPolicyEngine.kt:162](app/src/main/java/com/aharou/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt:162)）。

## 跨链速查

| 想改的东西 | 从哪进 |
| --- | --- |
| 模型请求参数 / 提示词 | `feature/agent/domain/prompt/`、`domain/provider/` |
| 新增一个工具 | `feature/agent/domain/tool/<分类>/` + `AgentModule.provideToolRegistry` |
| 工具授权规则 | `feature/agent/domain/permission/ToolPermissionPolicyEngine.kt` |
| 会话与消息持久化 | `feature/agent/domain/session/` |
| 容器命令执行 | `feature/agent/domain/container/CommandEngine.kt` |
| 文件改动前快照 | `feature/agent/domain/checkpoint/CheckpointManager.kt` |
