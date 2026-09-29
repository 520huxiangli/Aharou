---
name: dashboard-panel-script
display_name: 面板脚本编写
description: "要写或改「自定义面板（DIY Dashboard）」脚本时使用：余额/配额/Token/费用看板、收起态单行条目、展开态卡片、面板脚本报错或显示不出来。用户说「做个面板看余额」「这个卡片怎么不刷新」时读这份。"
---

# 自适应卡片面板脚本

面板脚本往 **stdout 吐一份 AdaptiveCard JSON**，App 用原生 Compose 渲染。脚本只描述数据，不碰绘制。

## 放哪、怎么被执行

- 统一目录 `~/.aharou/scripts/`（容器绝对路径 `/root/.aharou/scripts/`）。该目录映射到 App 数据目录，**容器升级重装时保留**。
- 供应商设置里的「面板脚本」路径支持四种写法：纯文件名（推荐，如 `demo_balance.py`）、`scripts/x.py`、`~/.aharou/scripts/x.py`、容器绝对路径 `/root/...`。
- 按扩展名/权限决定怎么跑：`.py` → `python3 <path>`；`.js` → `node <path>`；`.sh`/`.bash` → `bash <path>`；其它有可执行权限就直接跑，否则用 bash 跑。
- **脚本非零退出＝面板报错**，错误文本是「脚本退出码: N」+ 输出。写完先在容器里手动跑一遍：`python3 ~/.aharou/scripts/x.py`，确认 stdout 是合法 JSON 再上屏。

## 注入的环境变量

前缀同时注入两套：新脚本用 `AHAROU_*`，旧脚本的 `AICODE_*` 照常可用，二选一即可。常用项：

| 变量 | 含义 |
| --- | --- |
| `AICODE_MODEL` | 当前实际生效的模型 |
| `AICODE_PROVIDER_ID` / `_NAME` / `_TYPE` | 供应商 id / 名称 / 上游格式 |
| `AICODE_PROVIDER_API_KEY` / `_BASE_URL` | 当前生效的 Key（多 Key 时是活动 Key）/ Base URL |
| `AICODE_PROVIDER_DEFAULT_MODEL` / `_SELECTED_MODEL` | 默认模型 / 选中模型 |
| `AICODE_SESSION_ID` | 当前会话 id |
| `AICODE_LAST_INPUT_TOKENS` / `_OUTPUT_TOKENS` | 最近一次请求的输入 / 输出 Token |
| `AICODE_LAST_CACHED_TOKENS` | 其中命中缓存的部分（是输入 Token 的子集） |
| `AICODE_TOTAL_INPUT_TOKENS` / `_OUTPUT_TOKENS` | 本会话累计输入 / 输出 Token |
| `AICODE_MODEL_CONTEXT_TOKENS` / `_MAX_INPUT_TOKENS` / `_MAX_OUTPUT_TOKENS` | 模型窗口与上限，取不到时为 `0` |
| `AICODE_MODEL_INPUT_COST_USD_PER_M` / `_OUTPUT_COST_USD_PER_M` / `_CACHE_READ_COST_USD_PER_M` | 单价（美元/百万 Token），取不到时为 `0` |
| `AICODE_MODEL_SUPPORTS_TOOLS` / `_VISION` / `_REASONING` | 能力开关，`true` / `false` |
| `AICODE_MESSAGE_COUNT` | 当前会话消息总数 |
| `AICODE_AGENT_STATE` | `idle` / `loading` / `streaming` / `result` / `error` |
| `AICODE_SESSION_MODE` | `build` / `plan` / `auto` |
| `AICODE_REASONING_EFFORT` | `none` / `low` / `medium` / `high` / `xhigh` / `max`，未指定时为空 |
| `AICODE_REFRESH_REASON` | 本次刷新原因：`session` / `llm` / `done` / `manual` / `button` |
| `AICODE_WORKSPACE` / `_NAME` | 当前工作区容器路径（恒为 `/root/workspace`）/ 目录名 |

自定义脚本参数（供应商编辑 → 面板 DIY 里配 Key-Value）会注入为 `AICODE_KEY_<KEY>`，KEY 会规整成大写、只留字母数字下划线。参数值支持占位符 `{{PROVIDER_API_KEY}}`、`{{PROVIDER_ID}}`、`{{PROVIDER_NAME}}`、`{{PROVIDER_TYPE}}`、`{{BASE_URL}}`、`{{DEFAULT_MODEL}}`、`{{SELECTED_MODEL}}`、`{{MODEL}}`。

**不要在脚本里硬编码 API Key**——用 `AICODE_PROVIDER_API_KEY`，或让用户在参数里填 `{{PROVIDER_API_KEY}}`。

## 根结构

```json
{ "type": "AdaptiveCard", "version": "1.5", "compact": { "type": "Row", "items": [] }, "body": [] }
```

- `type` 固定 `"AdaptiveCard"`，`version` 推荐 `"1.5"`。
- `compact`＝输入框上方的收起态单行（约 28~34dp），缺省时系统自动从 `body` 抽前 2~3 个指标生成。
- `body`＝展开态主体（数组，必填）。按钮等交互元素也放 `body` 里。
- 所有组件都支持 `"visible": false` 动态隐藏。

## 组件速查

- 容器/排版：`ColumnSet` + `Column`（多列，`width` 用 `"auto"`/`"stretch"`/`"1"`/`"50dp"`）、`Container`（`style`: Default/Subtle/Emphasis/Good/Warning/Attention/Accent）、`Row`（子元素可带 `weight`）、`Spacer`、`FlowRow`（自动换行标签流）、`ScrollRow`（横向滚动）、`TabSet`（多页签；收起态只渲染第一个 Tab）。
- 展示：`TextBlock`（`size` 语义档 Micro/Small/Default/Medium/Large/ExtraLarge，`weight` Lighter/Default/Bolder，`isSubtle`，支持 `**加粗**` 与行内代码）、`ProgressBar`（`value` 0~100，`showPercent`，收起态建议 `height` 3~4）、`Metric`（label + 大字 value + unit + subText + `percent` + `trend`）、`Badge`（style: Good/Warning/Attention/Accent/Subtle）、`StatusDot`、`FactSet`（`facts` 为 `{title,value}` 列表）、`Divider`。
- 交互：`ActionButton`（`title` 必填；`action` 为 `openUrl` 时给 `url`，为 `copy` 时给 `value`，为 `refresh` 刷新面板）。

语义色只用 `Good` / `Warning` / `Attention` / `Accent` / `Subtle` / `Default`，它们会跟随明暗主题自动变色；确需固定色才写 Hex（如 `#8B5CF6`）。

## 刷新时机

- 每次 LLM 请求返回后自动重跑脚本刷新。
- 进入/切换会话、改脚本、点刷新按钮、`ActionButton action=refresh` 也会刷新。
- 脚本里想区分来源就读 `AICODE_REFRESH_REASON`。

## 写法建议

1. `compact` 保持单行精炼：`[状态点] 关键指标`，别塞长文本。
2. `body` 用 `ColumnSet` 铺多周期/多指标，细则用 `FactSet`。
3. **兜底**：模型元数据、Token 数据可能为 `0` 或空（取不到），算比率/费用前先判零，别除零崩掉。
4. **异常态**：拿不到数据就输出一张带 `StatusDot(color="Attention")` 的卡片说明情况，而不是让脚本报错、面板挂掉。
5. 输出前 `json.dumps(..., ensure_ascii=False)`，stdout **只放 JSON**，调试信息走 stderr。
