# 架构地图与数据迁移

## 架构地图

feature-based 分层 + DDD。入口 `AIEditorApp` 初始化 `FileLogger`、`TerminalKeepaliveService`、`McpManager`。

- **`core/`**：跨 feature 基础设施，常改的是 `db/`（含 `MigrationLoader.kt`）、`net/`、`theme/`、`ui/`（项目自定义组件都在这）、`util/`（含 `FileLogger`）、`security/`、`memory/`。
- **`feature/`**（`app/src/main/java/com/aharou/feature/`）：
  - `agent`：AI agent 核心 —— 提示词、工具注册与权限、MCP、provider 适配（`data/remote/` 下 `anthropic` / `openai` / `gemini`）。
  - `terminal`：终端与会话。本地模式 Termux 组件 + PRoot（`LinuxContainerEngine`）；远程模式 sshj。
  - `workspace`：工作区与 DocumentsProvider，远程走 `RemoteSftpFileAccess`。
  - `editor`：sora-editor 编辑器。`git`：Git 操作。`settings`：provider、日志、保活等设置。
  - `browser`：内置 WebView 浏览器与网页自动化（`BrowserUseManager` / `BrowserTool`）；`sandbox`：容器文件浏览与预览（`FileBrowserScreen` / `FilePreviewScreen`）。
  - `voice`：语音识别、合成、通话、唤醒（`call/` / `assistant/`）；`pet`：桌面伙伴小染（`PetOverlayService`）；`onboarding`：首次启动引导。
  - `backup`：备份恢复与加密。`credentials`：凭据管理与注入容器。`vault`：敏感信息脱敏（`VaultRedactor`）。
  - `im`：微信 / 飞书 / QQ / Telegram 机器人接入（凭证一律用户自填，禁止内置）；`remotedevice`：远程设备配对与控制；`connectors` / `plugin` / `extension`：外部连接器与插件。
- **远程 SSH 链路**：`RemoteSshConnection`（共享 sshj client）+ `RemoteSshEngine`（执行命令）+ `RemoteSftpFileAccess`（文件）+ `RemoteTerminalSessionManager`（终端）。
- **工具系统**：`feature/agent/domain/tool/` 下各工具经 `ToolRegistry` 注册，执行权限由 `ToolPermissionManager` 与 `ToolPermissionPolicyEngine` 管控。
- **MCP**：`feature/agent/domain/mcp/`，连接远端 server 并动态注册其工具。**DI**：Hilt，各 feature 自带 DI 模块。
- **延伸阅读**：主要运行时调用链见 `docs/EXECUTION_TRACES.md`，文件定位见 `docs/FILE_INDEX.md`；agent 工具与用户能力的同权对照见 `docs/agent-user-parity.md`。

### 单文件行数上限

`scripts/check_architecture.py` 有 1000 行上限（`architecture-policy.json` 的 `maxFileLines`），超限文件登记在 `.architecture-baseline.json` 且只许缩减。基线到了上限的文件（如 `ContextCompactor.kt`、`ScheduledTasksSection.kt`）再加任何逻辑前，先把新逻辑拆到同包新文件，**不要跑 `--sync-baseline` 抬基线**。

## 数据库与迁移

Room（`feature/agent/data/local/database/AgentDatabase.kt` + 各 DAO），迁移分两套，**一个版本只能二选一**：

- **文件式（默认）**：`core/db/MigrationLoader.kt` 读 `app/src/main/assets/migrations/{VERSION}_description.sql`，由 `SqlScriptSplitter` 按语句切分（识别注释与字符串字面量，**字符串里可放心写 `;`**，不再需要 `char(59)` 绕行）；整段迁移包事务、任一条失败整体回滚，成功记入 `migration_history` 表。
- **AutoMigration**：纯 schema 变更（加列/建表/索引）可用 `@AutoMigration(from = N-1, to = N)` 编译期自动生成，改 entity 忘写迁移会直接编译失败；含数据清理/重命名/改约束的版本必须走文件式。

改 schema 三步：

1. 递增 `AgentDatabase.kt` 的 `SCHEMA_VERSION`（当前值看文件，它必须等于 `migrations/` 里最大的编号）。
2. 文件式：在 `app/src/main/assets/migrations/` 新建 `{VERSION}_description.sql`（如 `46_add_provider_multi_key.sql`），**编号必须连续**；AutoMigration：加注解，保证 `to == SCHEMA_VERSION` 且 `from` 衔接文件式最大版本。
3. 写入 DDL/SQL，启动时自动执行并记入 `migration_history` 表。

**跨分支冲突硬规则**（RC/hotfix 与 `master` 并行推进时）

- **发布即冻结**：已打 `v*` tag 的迁移文件内容与编号不可再改，补丁只能靠新增迁移修正。
- **合流后移**：RC/hotfix 若带数据库迁移，其迁移号一旦随 tag 发布即占用；合回 `master` 时，`master` 上所有编号 ≤ 已发布最大版本号的未发布迁移必须整体重编号到该上限之后（内容一字不改、编号连续），并同步递增 `SCHEMA_VERSION`。已发布号一律不可复用。
- **对账脚本**：`python3 scripts/check_migrations.py`（或 `./gradlew checkMigrations`）校验编号连续、SCHEMA_VERSION 一致、已发布迁移未被篡改/复用，**推送前必跑**；CI（ci.yml 与 android-release.yml）已在构建前挂载，合流后未后移会被直接拦下。
