# 全量改名：AiCode / Minis → Aharou（执行方案）

> 2026-09-26 主人钦定：「把 AIcode 跟 minis 全都换成 Aharou」。
> 原则：**改名 + 兼容迁移**——旧路径 / 旧变量在过渡期继续可用（符号链接 / 双前缀注入），保证已装用户的容器与数据不炸。
> 现状：运行时可见的「Minis Computer」已改「Aharou Computer」、关于页已去融合字样（v1.12.0）。

## 范围盘点

| # | 现在 | 改成 | 涉及位置 | 迁移 / 兼容策略 |
|---|---|---|---|---|
| 1 | 容器目录 `~/.aicode`（宿主 `filesDir/aicode`） | `~/.aharou`（宿主 `filesDir/aharou`） | `LinuxContainerEngine`（bind 参数）、`ContainerInstaller`、`ProviderDashboardRunner.scriptsDir`、`SystemPromptProvider`、prompts 文本 | 宿主目录一次性迁移（**只搬不删**：新目录优先、旧目录兜底）；容器内保留 `/root/.aicode → /root/.aharou` 符号链接，旧脚本继续可用 |
| 2 | 环境变量前缀 `AICODE_*` / `AICODE_KEY_*` | `AHAROU_*` / `AHAROU_KEY_*` | `ProviderDashboardRunner.buildEnvPrefix`、脚本文档、prompts | 过渡期**两套前缀同时注入**；文档与提示词先切 Aharou |
| 3 | 容器资产 `assets/aicode/**`（`provision.sh`、`bin/aicode`、`lib/*.sh`…） | `assets/aharou/**` | app assets + 提取逻辑 | 提取路径随改；旧容器不重提取（靠 #1 的兼容链接） |
| 4 | Kotlin 命名空间 `com.aicode` | `com.aharou` | **约 465 个文件**（package / imports / namespace / manifest / proguard / 字符串里的 FQN） | 一次性机械重构：目录迁移 + 全仓 sed + 多轮编译修复。**代价：彻底断开 AiCode 上游同步能力**（当时为上游同步保留了 com.aicode，主人已拍板改名） |
| 5 | 剩余 Minis 命名 | Aharou | 类/文件：`MinisComputerSheet`、`MinisButton`（browser/sandbox）、`MinisMenu` 等 | 随批重命名（引用面小，逐个 sed） |
| 6 | 仓库 / Release / Git | 已是 Aharou | — | ✅ 已完成（仓库公开、Release 均 Aharou 名下） |

## 执行顺序（分两批跑）

- **批 1（运行时可见，用户能直接感知）**：#1 目录 + #2 变量 + #3 资产（含迁移逻辑与兼容链接）→ 单一构建 → 实机冒烟 → 版本顺延发布。
- **批 2（代码层）**：#4 命名空间 + #5 类名 → 独立一轮，多轮编译修复。

## 风险备忘

- **容器 bind / 目录迁移是最脆弱的一环**：写错 = 容器起不来。迁移必须「新目录优先、旧目录兜底、只搬不删」，且在启动早期执行一次。
- prompts（`00-identity.md` 等）里含 `~/.aicode` 字样，随 #1 同步改；`AICODE_MEMORY` 等占位符随 #2 改。
- 每批发布前照例：单一 Gradle 构建（禁止并行）→ 实机冒烟 → tag 顺延（1.12.x / 1.13.x）。
