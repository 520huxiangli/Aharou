<!-- 项目上下文分层索引：仅在工作区被识别为本仓库（settings.gradle.kts + com/aharou/feature）时注入 -->
# 项目上下文索引（Aharou）

仓库：Aharou —— Android AI agent 应用（Kotlin + Compose + Hilt，包名 `com.aharou`）。
用法：每行一个模块，「模块路径 — 覆盖什么 — 细节去哪读」。**只读与当前任务相关的行**，不涉及的模块不要主动打开，也不要整份通读下面提到的文档；定位到具体文件时先用 `search` 按符号名找再读命中文件。

## 工程骨架

- `settings.gradle.kts` — 模块清单：`:app`、`:terminal-emulator`、`:terminal-view`、`:benchmark` — `settings.gradle.kts`
- `app/build.gradle.kts` — flavor（universal / armsolo / x86solo）、buildType、SDK 与签名配置 — `app/build.gradle.kts`
- `app/src/main/java/com/aharou/AIEditorApp.kt` — Application 初始化：日志、保活服务、MCP 管理器 — `docs/FILE_INDEX.md`
- `app/src/main/java/com/aharou/MainActivity.kt` — 单 Activity 与 `NavHost` 路由 — `docs/FILE_INDEX.md`
- `app/src/main/java/com/aharou/di/` — Hilt 装配：数据库、网络、工具注册、工作流 — `docs/FILE_INDEX.md`

## core（跨 feature 基础设施）

- `core/config/` — 配置通道：字段注册、确认与审计 — `docs/FILE_INDEX.md`
- `core/db/` — 文件式数据库迁移加载 — `docs/FILE_INDEX.md`
- `core/memory/` — 长期记忆存储 — `docs/FILE_INDEX.md`
- `core/net/` — 代理、DNS 回退、URL 安全策略 — `docs/FILE_INDEX.md`
- `core/ui/` — 通用组件（输入框 / 开关 / 模态底栏 / 左滑删除），新增 UI 前先看有没有现成组件 — `docs/FILE_INDEX.md`
- `core/theme/` — 设计 token：`Spacing`、`Radius`、`semanticColors` — `docs/FILE_INDEX.md`
- `core/util/` — 日志、限长读、安全写盘等工具 — `docs/FILE_INDEX.md`
- `core/watch/` — 文件变更监听总线 — `docs/FILE_INDEX.md`
- `core/soul/`、`core/identity/`、`core/privacy/`、`core/security/`、`core/datastore/` — 人格、身份、隐私过滤、密钥加密、本地存储 — `docs/FILE_INDEX.md`

## feature（按业务模块）

- `feature/agent/` — agent 内核：提示词、工具注册与权限、MCP、provider 适配、会话、调度、子代理、工作流（内部仍分 data / domain / presentation） — `docs/FILE_INDEX.md`
- `feature/settings/` — 模型 provider 与全部设置页 — `docs/FILE_INDEX.md`
- `feature/workspace/` — 工作区管理与 DocumentsProvider — `docs/FILE_INDEX.md`
- `feature/terminal/` — 终端会话：PRoot 本地容器与 sshj 远程 — `docs/FILE_INDEX.md`
- `feature/editor/` — sora-editor 编辑器与 LSP — `docs/FILE_INDEX.md`
- `feature/git/` — Git 操作 — `docs/FILE_INDEX.md`
- `feature/browser/` — 内置 WebView 与网页自动化 — `docs/FILE_INDEX.md`
- `feature/sandbox/` — 容器文件浏览与预览 — `docs/FILE_INDEX.md`
- `feature/voice/` — 语音识别、合成、通话、唤醒 — `docs/FILE_INDEX.md`
- `feature/pet/` — 桌面伙伴：悬浮窗、形象、日常 — `docs/FILE_INDEX.md`
- `feature/im/` — 微信 / 飞书 / QQ / Telegram 机器人接入 — `docs/FILE_INDEX.md`
- `feature/remotedevice/` — 远程设备配对与控制 — `docs/FILE_INDEX.md`
- `feature/backup/`、`feature/credentials/`、`feature/vault/` — 备份恢复、凭据注入、敏感信息脱敏 — `docs/FILE_INDEX.md`
- `feature/connectors/`、`feature/plugin/`、`feature/extension/` — 外部连接器与插件扩展 — `docs/FILE_INDEX.md`
- `feature/onboarding/`、`feature/account/`、`feature/widget/` — 首启引导、账号、桌面小组件 — `docs/FILE_INDEX.md`
- `terminal-emulator/`、`terminal-view/` — Termux 终端组件（上游代码，改终端功能优先动 `feature/terminal/`） — `docs/FILE_INDEX.md`

## 资产与工具链

- `app/src/main/assets/prompts/` — AI 提示词：顶层 `NN-*.md` 常驻，`agent/` 按需注入 — `docs-site/docs/guide/custom-prompts.md`
- `app/src/main/assets/migrations/` — 文件式数据库迁移（编号连续，已发布即冻结） — `docs/FILE_INDEX.md`
- `skills/`、`evals/` — 官方技能定义与对应验收用例 — `docs/FILE_INDEX.md`
- `scripts/` — 校验脚本：迁移对账、技能自检、架构门禁、任务清单 — `docs/FILE_INDEX.md`
- `docs/` — 面向开发者与 AI 的文档：文件索引、调用链、签名与发版 — `docs/FILE_INDEX.md`
- `docs-site/docs/` — 用户文档站唯一事实源（构建时同步进 APK） — `docs-site/docs/index.md`
- `.githooks/` — 提交信息与任务清单校验钩子 — `.githooks/`

需要运行时调用链（谁调用谁、消息怎么流转）时读 `docs/EXECUTION_TRACES.md`；构建、发版与环境约定以项目规则为准。
