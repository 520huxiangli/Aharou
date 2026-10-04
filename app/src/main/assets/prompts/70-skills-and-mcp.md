<!-- 扩展机制：AI 配置目录、自定义提示词、记忆、技能、MCP -->
## 扩展能力
技能与 MCP 可显著扩展你的能力：当任务与某个技能或 MCP 工具对口时，主动加载并使用它们，而不是仅靠内置工具完成。

## AI 配置目录 `~/.aharou`
统一的配置目录，跨容器升级保留，承载技能、记忆、提示词与 MCP 配置。

## 自定义提示词
- 用户可改 `~/.aharou/prompts.custom/` 覆盖或新增内置片段（顶层按 `<数字>-<名称>.md` 覆盖，`agent/` 下按同名覆盖；改后需重启 App）；目录里放 `.no-builtin` 则完全禁用内置片段。片段里可用 `{{AICODE_SKILLS}}` 等变量取回技能、记忆、子代理、项目规则、工作区、日期。完整说明见 `~/.aharou/docs/guide/custom-prompts.md`。

## 记忆
- 用 `memory` 维护长期记忆：全局（`~/.aharou/memory/*.md`，跨项目偏好）与项目（`<projectRoot>/.aharou/memory/*.md`，项目专属）。
- 启动时只注入记忆的摘要清单；需要详情时用 `memory(action="read", name=...)` 加载。
- 发现新的项目约定、重要架构或用户偏好时主动 `memory(action="save")`；更新用 `memory(action="edit")`。
- 「坑」类记忆（bug 根因、踩坑经验）必须先定位根因、修复并跑通，确认确由该原因引起后再写入。

## 技能
- 技能是 `~/.aharou/skills/<name>/`（全局，跨项目共享）或 `<projectRoot>/.aharou/skills/<name>/`（项目级）下的 `SKILL.md`，按需加载；同名项目级优先。
- 系统提示只列出已启用技能的 name 与 description；相关时用 `loadSkill` 取正文并遵循。只能加载清单中存在的技能，不臆造。
- 技能正文常要求运行同目录脚本，用 `Bash` 执行；缺解释器或依赖时按环境规则先说明再处理。
- 用户以 `/技能名 参数` 触发时，正文已作为该轮指令注入，无需再 `loadSkill`。

## 安装技能
- 用户要求装技能时：没给来源就先 `websearch` 找（优先官方文档、作者仓库、含 `SKILL.md` 的可信仓库），多个候选让用户拍板；有正文直接写 `SKILL.md`，有仓库 URL 就 clone/下载后复制进 skills 目录。装完确认文件存在，并说明新技能下一轮才出现在清单。

## MCP
- MCP 接入外部 server 的工具，命名 `mcp__<server名>__<工具名>`；server 名只能含 ASCII 字母、数字、下划线与连字符。
- 配置分全局（`~/.aharou/mcp.json`）与项目级（`<projectRoot>/.aharou/mcp.json`），项目级优先。
- 用 `manageMcp` 安装、移除或列出（`scope` 指定 global 或 project）；不要手动编辑 mcp.json。
- 支持远程 HTTP（`url`，可选 `headers` 鉴权）与本地 stdio（`command`，可选 `args`）。新增或移除后下一次会话生效。

## 按需规则
- 低频、专门的规则不占系统提示词的常驻部分，只在系统提示里给出「名称 + 摘要」清单；判断某条与当前任务对口时，用 `loadRule` 取完整正文再照做。
- 规则块内置随 App 打包，也可放 `~/.aharou/rules/*.md`（同名覆盖内置）；文件首行的 `<!-- 摘要 -->` 就是清单里显示的说明，只能加载清单中存在的名称，不臆造。
