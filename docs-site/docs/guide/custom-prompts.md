# 自定义提示词

Aharou 的系统提示词可以自己改。默认提示词随 App 内置、升级时自动更新；你只要把想改或想加的片段放进自定义目录就能覆盖或扩展，不用动 App 本体。

::: tip v1.12.0 起
本文的片段命名规则（`<两位数字>-<名称>.md`）、按数字新增片段、`.no-builtin` 完全禁用开关与 <span v-pre>`{{AICODE_*}}`</span> 变量自 **v1.12.0** 起支持。更早的版本只能放与默认片段**完全同名**的文件来覆盖，不支持按数字新增、`.no-builtin` 与变量。
:::

## 目录结构

提示词放在 AI 配置目录 `~/.aharou/` 下（容器内路径是 `/root/.aharou/`）：

```
~/.aharou/
├── prompts/          默认提示词（App 启动时全量释放，升级自动覆盖）
│   ├── 00-identity.md
│   ├── 10-communication.md
│   ├── ...           顶层是「静态基线」片段，按两位数字前缀排序
│   └── agent/        按需注入的片段（不进静态基线，用到时才读取）
│       ├── plan-mode.md           PLAN 模式提醒
│       ├── auto-mode.md           AUTO 模式提醒
│       ├── subagent-base.md       子代理基础运行规范
│       ├── compact-summary.md     长对话上下文压缩
│       ├── title-generator.md     会话标题生成
│       ├── commit-generator.md    Git 提交信息生成（Git 页的「AI 生成」）
│       └── init.md                /init 命令的指令正文
├── prompts.custom/   你的自定义片段（覆盖或新增，升级不动这里）
│   ├── 50-我的安全规则.md
│   ├── 25-额外要求.md
│   └── agent/
│       └── plan-mode.md           覆盖按需片段
├── skills/
└── docs/
```

片段分两类，命名规则不同：

- **静态基线**（`prompts/` 顶层）：`<两位数字>-<名称>.md`，数字是它的身份，决定在系统提示词里的位置。
- **按需片段**（`prompts/agent/`）：没有数字，用到时才读取（如切换模式、压缩上下文、派发子代理、生成标题）。

## 命名规则

自定义片段放在 `prompts.custom/`：

- **顶层文件**必须是 `<两位数字>-<名称>.md`（如 `05-开头.md`、`50-我的安全规则.md`）。数字即身份：
  - 数字与某个内置静态基线片段相同（内置数字为 `00`、`10`、`15`、`20`、`30`、`40`、`50`、`60`、`70`）→ **覆盖**该片段，尾部名称可随便取；
  - 数字不在上述集合里 → 作为**新增片段**，按数字大小插入基线的对应位置（如 `05-开头.md` 排在 `00` 与 `10` 之间）。
- 不符合该格式的文件（如 `notes.md`、`2024-备忘.md`）会被忽略。
- 同一数字有多个文件时只取文件名排最前的那个，其余忽略——请保持数字唯一。
- **`agent/` 子目录**里的片段没有数字身份，按**精确同名**覆盖（如 `prompts.custom/agent/plan-mode.md`）。

## 加载优先级

每个片段按这个顺序查找，找到就用，不再往后找：

1. 自定义版本：顶层数字片段按数字身份（`prompts.custom/` 顶层）、其余按同名（`prompts.custom/agent/xxx.md`）
2. `~/.aharou/prompts/<同名文件>` — 本地默认副本
3. App 内置的版本 — 兜底

所以你只需要放想改或想加的那几个片段，其余会自动用默认版本。

## 怎么改

1. 在 `~/.aharou/prompts.custom/` 下（没有就手动创建）放入片段：覆盖内置片段用相同数字命名，新增片段用尚未占用的数字命名，子目录片段（如 `agent/plan-mode.md`）对应放到 `prompts.custom/agent/` 下。
2. 编辑内容。
3. **重启 App 后生效**。

::: warning 必须重启 App，新开会话不算
提示词在 App 进程启动时加载并缓存，新建会话不会重新读文件。改完必须完全退出 App 再打开。
:::

编辑方式有三种：在终端里用 `vi` / `nano` 直接改；让 AI 帮你写（它的文件工具能直接读写这个目录）；或者[用文件管理器访问 App 私有目录](/guide/files#工作区文件在手机上的位置)。

## 完全禁用内置提示词

在 `prompts.custom/` 下新建一个名为 `.no-builtin` 的文件（内容随意，存在即生效），即可让**主代理**的系统提示词只由你在 `prompts.custom/` 顶层写好的数字片段组成：

- 不再注入任何内置片段，也不再自动附加技能清单、子代理清单、记忆、项目规则、工作区与当前时间；
- 需要哪些动态内容，就在你自己的片段里用下面的变量按需取回；
- 只作用于主代理，子代理仍按各自定义注入内置片段；
- `prompts.custom/` 里没有可识别的数字片段时系统提示词会为空，请确保至少有 `NN-xxx.md`。

::: danger 这是"完全接管"
启用后 AI 的行事规则完全由你写的片段决定，务必自备身份、工具说明与安全边界等内容，否则 AI 会缺少必要上下文。
:::

## 可用变量

在片段正文里写这些占位符，运行时会替换成真实内容；清单为空时替换为空字符串：

::: v-pre
| 变量 | 替换为 |
| --- | --- |
| `{{AICODE_SKILLS}}` | 可用技能清单（名称 + 描述） |
| `{{AICODE_MEMORY}}` | 记忆摘要清单（全局 + 项目） |
| `{{AICODE_SUBAGENTS}}` | 可用子代理清单 |
| `{{AICODE_PROJECT_RULES}}` | 项目规则（`AGENTS.md` / `CLAUDE.md`） |
| `{{AICODE_WORKSPACE}}` | 工作区上下文（当前项目根目录） |
| `{{AICODE_DATE}}` | 当前日期（`yyyy-MM-dd`） |
:::

::: tip 写了变量就不会再自动追加
平时系统会把技能/记忆等自动附在提示词末尾；只要你的片段里写了对应变量，该内容就**只在变量处插入一次**，不会再自动追加，避免重复。未写的仍照常自动追加。
:::

其它占位符（如压缩提示词用的 <span v-pre>`{{INSTRUCTION}}`</span>）不受影响。

## 升级时的行为

- `prompts/` 目录每次启动都会被内置版本全量覆盖，所以默认提示词会随 App 升级自动更新。
- `prompts.custom/` 目录 App 永远不会自动写入或删除，你的自定义在升级后完整保留。

::: danger 请勿直接修改 prompts/ 目录
`prompts/` 目录中的内容在每次启动时都会被全量重置，手动修改的内容在 App 升级或重启后会被覆盖。如需持久自定义，请务必保存在 `prompts.custom/` 目录中。
:::

## 片段说明

| 文件名 | 内容 |
| --- | --- |
| `00-identity.md` | AI 身份与角色定义 |
| `10-communication.md` | 沟通与回复风格 |
| `15-project-rules.md` | 项目规则加载约定（AGENTS.md / CLAUDE.md），详见 [记忆与项目规则](/guide/memory) |
| `20-coding-discipline.md` | 编码纪律 |
| `30-comments.md` | 代码注释规范 |
| `40-approach.md` | 工作方式与流程 |
| `50-safety.md` | 安全与可信边界 |
| `60-tools-and-paths.md` | 工具说明与路径约定 |
| `70-skills-and-mcp.md` | 技能与 MCP 说明 |
| `agent/plan-mode.md` | PLAN 模式提醒（按需注入） |
| `agent/auto-mode.md` | AUTO 模式提醒（按需注入） |
| `agent/subagent-base.md` | 子代理基础运行规范（按需注入） |
| `agent/compact-summary.md` | 长对话上下文压缩的提示词 |
| `agent/title-generator.md` | 会话标题生成的提示词 |
| `agent/commit-generator.md` | Git 页「AI 生成」提交信息用的提示词 |
| `agent/init.md` | `/init` 命令的指令正文（分析代码库并生成/改进 `AGENTS.md`），可在对话输入框用 `/init` 触发 |

## 恢复默认

删掉 `~/.aharou/prompts.custom/` 下对应的文件，那个片段就恢复默认版本；删掉整个 `prompts.custom/` 目录则全部恢复（`.no-builtin` 一并删除后内置提示词重新启用）。

## 注意

- 旧版把 `80-plan-mode.md`、`81-auto-mode.md`、`90-subagent-base.md` 放在 `prompts/` 顶层，**v1.12.0 起**已移到 `prompts/agent/` 且去掉编号；对应的自定义覆盖请改用 `prompts.custom/agent/plan-mode.md` 等路径。
- `60-tools-and-paths.md` 这类片段会随工具变更而更新。如果你覆盖了它，App 升级后不会自动获得新版工具说明，AI 看到的工具定义可能和实际不一致。需要更新时手动同步一下，或者删掉你的自定义版本让默认版本重新生效。