# 文件索引（FILE_INDEX）

> **用途**：接手代码时先在这里定位「该看哪个文件」，再进去读实现。只收录关键入口与职责，不追求覆盖全部文件；行号会随代码漂移，位置以文件为准（配合 `search` 用符号名精确定位）。

## 仓库布局

| 路径 | 职责 |
| --- | --- |
| `app/` | 主应用（Android 模块，包名 `com.aharou`） |
| `terminal-emulator/`、`terminal-view/` | Termux 终端组件（上游代码，包名 `com.termux.*`，改终端功能优先动 `feature/terminal/`） |
| `skills/` | 官方技能定义（`SKILL.md`）；`evals/skills/` 放对应验收用例 |
| `scripts/` | 构建与校验脚本：`check_migrations.py`、`check_skills.py`、`check_architecture.py`、模型数据同步 |
| `docs-site/` | 用户文档站（唯一事实源），构建时由 `syncAiDocs` 同步进 APK 的 `assets/docs/` |
| `docs/` | 面向开发者与 AI 的文档（调用链、文件索引、发版与签名） |
| `architecture-policy.json`、`.architecture-baseline.json` | 架构门禁规则与棘轮基线（`scripts/check_architecture.py` 读取） |

## 入口与装配（`app/src/main/java/com/aharou`）

| 文件 | 职责 |
| --- | --- |
| `AIEditorApp.kt` | Application：FileLogger 初始化、保活服务、McpManager、内置子代理与凭据 helper 提取、WorkManager 配置 |
| `MainActivity.kt` | 单 Activity：`NavHost` 路由、侧边抽屉、大屏双栏 |
| `WorkbenchPane.kt` | 大屏右栏内容（编辑器 / 终端 / Git / 浏览器） |
| `di/AgentModule.kt` | 集中 Hilt 装配：数据库、网络、工具注册（`provideToolRegistry`）、`AgentWorkflow` |
| `di/RepositoryModule.kt`、`di/BackupModule.kt` | 仓储与备份绑定 |

## core/（跨 feature 基础设施）

| 目录 | 职责 | 关键文件 |
| --- | --- | --- |
| `core/config/` | 配置通道：AI 修改 Aharou 自身设置的能力与审计 | `ConfigRegistry.kt`、`ConfigSchema.kt`、`audit/` |
| `core/db/` | 文件式数据库迁移加载 | `MigrationLoader.kt`、`SqlScriptSplitter.kt` |
| `core/memory/` | 长期记忆存储 | `AharouMemoryStore.kt` |
| `core/net/` | 网络策略 | `AppProxy.kt`、`GitHubDnsFallback.kt`、`UrlSafetyPolicy.kt` |
| `core/security/` | 密钥加密 | `KeystoreCipher.kt` |
| `core/soul/` | 人格（名字/图标/风格/正文） | `SoulStore.kt` |
| `core/theme/` | 主题与设计 token（`Spacing` / `Radius` / `semanticColors`） | `AIEditorTheme.kt`、`AppThemePreset.kt` |
| `core/ui/` | 全局通用组件（项目硬规则要求优先复用） | `AppTextField.kt`、`AppSwitch.kt`、`AdaptiveModalBottomSheet.kt`、`SwipeToDeleteRow.kt`、`ImageViewer.kt`、`FloatingTabBar.kt`、`WindowSize.kt` |
| `core/util/` | 日志与工具 | `FileLogger.kt`、`AILogger.kt`、`BoundedLineReader.kt`、`LineDiff.kt`、`SafeFileWrite.kt`、`CrashRecorder.kt`、`PerformanceMonitor.kt` |
| `core/watch/` | 文件变更监听总线 | `FileChangeHub.kt` |
| `core/datastore/` | 偏好存储损坏处理 | `PreferencesCorruption.kt` |

## feature/（业务）

| 模块 | 职责 | 关键入口 |
| --- | --- | --- |
| `agent/` | AI 核心：会话、提示词、工具、MCP、子代理、检查点 | 见下 |
| `terminal/` | 终端会话、容器保活服务（`specialUse` 前台服务） | `TerminalSessionManager`、`TerminalKeepaliveService`、`KeepaliveWorker` |
| `workspace/` | 工作区与文件访问抽象（本地 / 远程） | `FileAccessProvider`、`WorkspaceRepository`、`RemoteSftpFileAccess` |
| `editor/` | sora-editor 代码编辑器与 Markdown 预览 | `CodeEditorScreen`、`EditorSessionManager` |
| `git/` | Git 操作（JGit）：状态、提交、分支、日志 | `GitRepository`、`GitViewModel` |
| `browser/` | 内置 WebView 浏览器与网页自动化 | `BrowserTabPool`、`BrowserUseManager`、`BrowserTool` |
| `sandbox/` | 容器文件浏览与预览 | `FileBrowserScreen`、`FilePreviewScreen` |
| `settings/` | 设置页（`SettingsScreen` 内部 Section 状态机）与各类 Section | `presentation/component/SettingsScreen.kt`、`SettingsGroupScreens.kt` |
| `backup/` | 加密备份导出/导入 | `BackupManagerImpl`、`BackupSnapshot` |
| `credentials/` | 凭据管理 | `CredentialScreen` |
| `voice/` | 语音：识别、合成、通话、唤醒 | `call/`、`assistant/` |
| `pet/` | 桌面伙伴小染（悬浮窗、动作、语音） | `PetOverlayService`、`PetOverlay` |
| `onboarding/` | 首次启动引导 | `OnboardingStep` |

### feature/agent 内部

| 子包 | 职责 |
| --- | --- |
| `domain/workflow/` | **Agent 循环引擎**（`AgentWorkflow` / `StatefulAgentWorkflow`），非用户可见的「工作流」功能 |
| `domain/runner/` | `AgentTurnRunner`：非 UI 的一轮编排入口（聊天与语音共用） |
| `domain/session/` | 会话与消息持久化 UseCase（`SessionUseCase`、`MessagePersistenceUseCase`） |
| `domain/container/` | `CommandEngine` 接口与本地 PRoot（`LinuxContainerEngine`）/ 远程 SSH 实现、`ContainerInstaller` |
| `domain/checkpoint/` | `CheckpointManager`：文件快照与回退 |
| `domain/tool/` | `AgentTool` 抽象、`ToolRegistry`、权限（`ToolPermissionManager`、策略引擎）与各工具实现 |
| `domain/mcp/` | `McpManager`：MCP server 连接与工具注册 |
| `domain/subagent/` | 子代理定义（`AgentDefinition`）、`SubAgentEventBus` |
| `domain/permission/` | 工具授权策略评估 |
| `domain/prompt/` | 系统提示词组装（`SystemPromptProvider`）、片段目录（`PromptFragmentCatalog`） |
| `domain/rule/` | 按需规则块仓库（`RuleRepository`）：低频规则只进清单，配 `loadRule` 工具按需取正文 |
| `domain/provider/` | 模型供应商适配（OpenAI / Anthropic / Gemini） |
| `domain/command/` | 斜杠命令注册与实现 |
| `domain/memory/` | 记忆抽取与蒸馏 |
| `domain/notification/` | 会话级待送通知队列（非系统通知） |
| `domain/vdisplay/` | 影子屏（虚拟显示屏）控制 |
| `presentation/` | `AIAgentViewModel` 与聊天界面组件（`component/`） |

## 数据层与迁移

| 位置 | 说明 |
| --- | --- |
| `feature/agent/data/local/database/AgentDatabase.kt` | 单库 `aicode_agent_db`，`SCHEMA_VERSION` 与 Entity 注册 |
| `feature/agent/data/local/entity/`、`dao/` | Entity 与 DAO（无 TypeConverter，枚举/JSON 存 String 并手工映射） |
| `app/src/main/assets/migrations/*.sql` | 文件式迁移，编号连续，由 `MigrationLoader` 整体包事务执行 |
| `scripts/check_migrations.py` | 迁移对账（编号连续、`SCHEMA_VERSION` 一致、已发布冻结） |

## 工具系统

| 位置 | 说明 |
| --- | --- |
| `feature/agent/domain/tool/AgentTool.kt` | 工具抽象（`executeWithContext` / `toJsonSchema` / 权限策略）与 `StreamingAgentTool` |
| `feature/agent/domain/tool/ToolRegistry.kt` | 工具注册表（含 `deferredLoading` 按需展开） |
| `feature/agent/domain/tool/<分类>/` | `container`（Bash/后台终端）、`explorer`（search/list）、`file`、`mcp`、`subagent`（task）、`todo`、`mode`、`question`、`shizuku`、`a11y`、`vdisplay`、`skill`（loadSkill）、`rule`（loadRule）、`memory`、`config`、`app`、`editor`、`search` |
| `di/AgentModule.kt` 的 `provideToolRegistry` | 新工具必须在此登记才会进模型工具清单 |

## 关键约定（改动前必读）

- 构建与校验命令、CI 全景见仓库根 `CLAUDE.md`。
- 运行时调用链见 [`docs/EXECUTION_TRACES.md`](EXECUTION_TRACES.md)。
- 用户文档改动要同步 `docs-site/`（含 `.vitepress/config.ts` 侧栏与 `guide/overview.md` 索引）。
- 用户可见中文文案必须进双语 `values/strings.xml` 与 `values-en/strings.xml`（架构门禁会校验一致性）。
- 内置资产：`app/src/main/assets/agents/`（子代理预设，启动释放到 `~/.aharou/agents/`）、`assets/rules/`（按需规则块，可被 `~/.aharou/rules/` 同名覆盖）、`assets/prompts/`（提示词片段）。
