# 工具授权与代审核

插件自带的 AI 工具、以及插件代执行的宿主工具，都要经过同一套授权链：宿主判定 →（必要时）使用者确认或插件代审核 → 执行。本文说明插件能在哪一环做什么，以及被卡在「等待授权」时怎么排查。

## 授权链

1. **清单声明**：`contributes.tools` 声明工具，宿主生成对 AI 暴露的名字 `plugin__<插件ID>__<工具ID>`。
2. **权限策略**：宿主按工具与参数判定 ALLOW / ASK / DENY。插件工具的默认策略是 ASK，首次调用会显示授权提示。
3. **开发者 hook**：`agent.tool.before`（transform）可以在权限判定**之前**拒绝或改写参数。
4. **代审核**：判定为 ASK 后、弹出使用者确认前，`agent.permission.review`（review 模式）可以先给出 `allow` / `deny` / `pass`。
5. **执行**：真正执行时，工具实现自己再查一遍路径、归属、参数——粗筛通过不代表细查通过。

改写参数后，宿主会按新参数重新走完整判定，之前的批准不会沿用。PLAN 模式下的写操作与外部工具按计划模式规则拦截，任何 hook 都不能把它改成放行。

## 插件工具的授权

- 启用插件等同于信任其全部声明，但**工具调用不被启用动作预先批准**，第一次调用仍会询问。
- 每次调用重新判定；「始终允许」规则只在该工具的 `rememberablePatterns` 非空时才出现。
- 工具结果以 JSON-RPC `result` / `error` 返回；不要在成功结果里塞 `ok: false`，AI 会当成成功。

## 代执行宿主工具：`tools.execute`

| 项 | 规则 |
| --- | --- |
| 可用工具 | 内置工具与 MCP 工具；`plugin__` 前缀的插件工具返回 `TOOL_NOT_ALLOWED` |
| 会话型工具 | `askUserQuestion`、`planMode`、`goalStatus`、`todo`、`task`、`messageParent` 依赖聊天会话，同样 `TOOL_NOT_ALLOWED` |
| `sessionId` | 缺省为 `plugin:<插件ID>`；只能使用该会话或其 `:` 子会话，否则 `SESSION_FORBIDDEN` |
| `timeoutMs` | 默认 120000，范围 1000–600000，同时约束「等授权」与「执行」两段 |
| 审批等待 | 策略为 ASK 时 `execute` 会挂起，**且不会弹出聊天页的审批框** |

页面是全屏 WebView 时，聊天页的审批框本来就不可见。因此插件必须自己把审批摆出来，两条路：

### 路线 A：轮询 `tools.pending` + `tools.resolve`

```js
const ctx = await aharou.context();
const sessionId = `plugin:${ctx.pluginId}:my-page`;

const timer = setInterval(async () => {
  const pending = await aharou.tools.pending(sessionId).catch(() => null);
  renderApproval(pending ? pending.approval : null); // 按钮调用 aharou.tools.resolve(id, 'once')
}, 800);

try {
  const result = await aharou.tools.execute('writeFile', {path, content}, {sessionId});
  appendToolResult(result);
} finally {
  clearInterval(timer);
  renderApproval(null);
}
```

`approval` 含 `id`、`toolName`、`title`、`summary`、`details`、`argsPreview`、`rememberablePatterns`、`rememberDisabledReason?`。`rememberablePatterns` 为空时不要渲染「始终允许」按钮，宿主会拒。结果对象为 `{status, data?, message?, code?, images?, imagesTruncated?, dataTruncated?}`，`status` 为 `success` / `error` / `partial`。

### 路线 B：`agent.permission.review` 代审核

```json
{"hooks": [{"id": "review", "event": "agent.permission.review", "handler": "hooks.review", "mode": "review"}]}
```

```js
const handlers = {
  'hooks.review': (payload) => {
    if (payload.toolName === 'bash' && payload.arguments.command === 'ls') {
      return {decision: 'allow', reason: '只读列目录'};
    }
    return {decision: 'pass'};
  }
};
```

| 返回值 | 效果 |
| --- | --- |
| `allow` | 只批准当前这一次，不写入「始终允许」，不影响后续调用 |
| `deny` | 只拒绝当前调用，返回 `PLUGIN_REVIEW_DENIED`，其他工具照常 |
| `pass` | 交给下一个代审核插件；全都不处理才进入使用者确认界面 |

代审核的边界：不得修改参数，不得覆盖宿主已给出的 DENY、自动放行或 PLAN 限制，不得代审本插件自己提供的工具。`planMode` 的模式切换始终由使用者本人处理。需要「人在后台」时，可用 `visibilityPolicy: "background"` 的浮层轮询 `app.isForeground`，App 回到前台后返回 `pass` 把请求交回应用内确认框。

## 需要单独授权的四类能力

启用插件**不会**自动获得下面这些，使用者要到「设置 → 插件 → 该插件详情 → 权限」逐项开启：

| 能力 | 清单声明 | 拿不到时的表现 |
| --- | --- | --- |
| Shizuku 命令 | `contributes.shizuku` | `shizuku.exec` 与 `system.settingsGet/Set` 报权限错误 |
| 系统浮层 | `contributes.overlay` | `overlay.create` 报错，且 App 需有「显示在其他应用上层」 |
| 备份与还原事件 | `contributes.backup` | 不派发 `backup.export.before` / `backup.import.finished` |
| 调用已配置模型 | `contributes.modelAccess`（声明即可，不再单独授权） | `model.*` 返回 `MODEL_ACCESS_NOT_DECLARED` |

Shizuku 还要求系统里 Shizuku 服务正在运行；无障碍类设备方法（`device.*`）要求打开 Aharou 无障碍服务，与插件授权无关。

## 权限相关 hook

| 事件 | 模式 | 能做什么 |
| --- | --- | --- |
| `agent.tool.before` | `transform` | `decision: "deny"` 阻止执行；返回 `arguments` 改写参数 |
| `agent.tool.after` | `transform` | 只改成功结果的 `data`，不能把错误改成成功 |
| `agent.permission.review` | `review` | 代审核，见上 |

`transform` 与 `review` 必须在清单里显式写 `mode`，写错直接拒绝安装。多个插件按插件 ID 升序执行，后一个读前一个改写后的值。单次 `agent.tool.*` 上限 10 秒、一次拦截合计 20 秒，超时或返回格式不合法时**保持原值**继续（不会因此放行或拒绝）；同一 hook 连续 3 次失败熔断 60 秒。详见[生命周期 Hooks](/advanced/plugin/hooks)。

## 卡在「等待授权」怎么办

| 现象 | 原因与处理 |
| --- | --- |
| 轮次一直停在 running | 页面没有展示审批：改用 `tools.pending` 轮询，或声明 `agent.permission.review` |
| 点了允许但没继续 | 默认会话 `plugin:<id>` 与调用时传的 `sessionId` 不一致，导致审批结果对不上 |
| 没有「始终允许」按钮 | 该工具不可记忆（`rememberablePatterns` 为空），只能一次次批准 |
| 代审核返回 allow 仍被拒 | 宿主已判定 DENY，或处于 PLAN 模式，或该工具由本插件自己提供 |
| `TOOL_NOT_ALLOWED` | 想代执行插件工具或会话型工具，宿主不允许；改成直接调用自己的 handler |
| `PLUGIN_REVIEW_DENIED` | 某个代审核插件返回了 `deny`，查它的日志 |

::: tip 授权分层
插件的清单声明与工具名过滤只解决「这一类调用能不能进来」；真正的边界由每个工具执行时自己复查。插件不要指望在 hook 里做细粒度校验来替代工具自身的检查。
:::

> 部分内容参照 Operit2 文档改写（AGPL-3.0）；授权链、错误码与限额取自 Aharou 的插件权限实现。
