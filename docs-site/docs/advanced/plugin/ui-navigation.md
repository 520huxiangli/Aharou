# 插件入口与页面导航

插件界面从哪里被打开、打开后怎样返回、面板型入口怎么工作——本文把这三件事讲清楚。页面内部的桥方法见[插件页面与宿主接口](/advanced/plugin/ui-pages)。

## 三类入口

| 类型 | 清单位置 | 打开方式 | 适合 |
| --- | --- | --- | --- |
| 工作区工具 | `contributes.workspaceTools` | 右侧「项目工具」抽屉里添加后点开 | 构建、清理、部署、项目状态 |
| 槽位入口 | `contributes.uiEntries` | 设置页分组、侧栏底部、聊天顶栏、空态、会话菜单、文件树、编辑器选区、备份页 | 记忆管理、快捷动作、面板 |
| 插件配置页 | `configPage` | 「设置 → 插件」插件的详情页里，与启停同层分段切换 | 填 Key、开关、选项 |

安装后入口立即出现，副标题标明插件来源；停用或卸载后入口消失。

## 槽位对照

| `slot` | 出现在哪 | 接受哪种入口 |
| --- | --- | --- |
| `settings.general` | 设置页「通用」分组末尾 | `page` / `screen` |
| `settings.ai` | 设置页「AI 配置」分组末尾 | `page` / `screen` |
| `settings.environment` | 设置页「运行环境」分组末尾 | `page` / `screen` |
| `settings.permissions` | 设置页「权限与后台」分组末尾 | `page` / `screen` |
| `settings.data` | 设置页「数据与诊断」分组末尾 | `page` / `screen` |
| `settings.help` | 设置页「帮助与关于」分组末尾 | `page` / `screen` |
| `drawer.footer` | 侧栏底部，浏览器与设置之上 | `page` / `handler` / `screen` |
| `chat.header.menu` | 聊天顶栏「更多」菜单底部 | `page` / `handler` / `screen` |
| `chat.welcome` | 聊天空态建议区（最多 3 条） | `page` / `handler` / `screen` |
| `chat.session.menu` | 会话长按菜单，删除项之前 | `page` / `handler` / `screen` |
| `files.tree.menu` | 文件/目录长按菜单 | 仅 `handler` |
| `editor.selection.menu` | 编辑器选区浮动菜单 | 仅 `handler` |
| `backup.data` | 备份页「选择导出数据」 | 仅数据选项（宿主渲染开关） |

`page`、`handler`、`screen` 必须且只能出现一个。同一插件每个槽位最多 2 个入口（`backup.data` 最多 1 个），超了安装会被拒。入口顺序由宿主决定，插件不能指定排在第几位。

## 三种入口形态

| 形态 | 写法 | 行为 |
| --- | --- | --- |
| HTML 页面 | `page: "pages/memory.html"` | WebView 打开包内页面，可用 `window.aharou` 全套桥 |
| 进程动作 | `handler: "actions.sync"` | 宿主调插件进程的 `ui.invoke`，返回 `{message}` 时用 toast 显示；`confirm: true` 先弹确认 |
| Compose 面板 | `screen: "pages/dashboard.ui.js"` | 宿主用 Compose DSL 渲染器画原生界面，不走 WebView |

动作入口能拿到槽位上下文：`entryId`、`slot`，以及可用的 `sessionId`、`path`、`isDirectory`、`selection`、`language`、`lineStart`、`lineEnd`。动作里可以正常调 `host.*`（包括 `host.tip`、`host.notify`、`host.chat.*`）。

## Compose 面板（`screen`）

面板型入口的文件必须是 `.js` / `.mjs` / `.cjs`。运行时有两步：

1. 插件进程用 `ToolPkg.registerUiRoute`（兼容层）或用进程 handler 提供路线清单，路线里带 `runtime: 'compose_dsl'` 与 `screen`；
2. 宿主打开入口时，按清单里的 `screen` 路径去找**路径相同**的路线，找到后渲染第一棵树。

```json
{
  "uiEntries": [
    {
      "id": "dashboard",
      "title": "面板",
      "slot": "settings.ai",
      "icon": "monitor",
      "screen": "pages/dashboard.ui.js"
    }
  ]
}
```

| 与 HTML 页面的差别 | HTML 页面 | Compose 面板 |
| --- | --- | --- |
| 渲染 | WebView + DOM | 宿主原生控件，跟随 App 主题 |
| 样式 | CSS 变量与自有样式 | 用 `MaterialTheme`、`Modifier` 表达 |
| 资源 | 包内 HTML/CSS/JS | 只有脚本，不能嵌 iframe 或远程脚本 |
| 状态 | 脚本自行管理 | `useState` 管状态，动作后宿主取新树重绘 |
| 回调宿主 | `aharou.*` | 面板脚本里的调用同样落到 `host.*` |

路线找不到时会报「没有与入口匹配的 Compose 路线」，此时检查清单里的 `screen` 路径与进程注册的 `screen` 是否逐字一致。Compose DSL 的节点与 ctx 形状以宿主渲染器支持的为准，仓库内 `plugins/sdk/compose-dsl-selftest.cjs` 的用例列出了可用节点与调用方式。

## 打开后的导航

| 动作 | 写法 | 说明 |
| --- | --- | --- |
| 返回上一层 | `aharou.ui.onBack(handler)` | 同步返回 `true` 表示已处理；后注册的优先 |
| 退出插件页 | `aharou.ui.close()` | 回到打开前的位置（设置页、侧栏或聊天页） |
| 页面内全屏弹层 | `aharou.ui.setOverlay(visible, rect)` | 宿主按矩形在背景上挖孔 |
| 打开外部链接 | `aharou.ui.openUrl(url)` | 交给系统浏览器，仅 http/https |
| 跳到别的插件页 | 不支持 | 没有「由插件主动打开另一个插件页面」的接口 |

返回键的处理顺序是：WebView 自身历史 → `ui.onBack` 注册的处理器 → 退出插件页。顶栏自绘的返回按钮应在首页调用 `aharou.ui.close()`，在子页走自己的路由返回，不要两套逻辑混用。

```js
const off = aharou.ui.onBack(() => {
  if (drawerOpen) {
    drawerOpen = false;
    return true;
  }
  return false;
});
```

## 大屏与窄屏

从侧栏、顶栏、会话菜单或文件树打开的页面，在大屏下展示在右侧工作台；设置页入口沿用设置页自己的分栏布局；窄屏一律全屏页面；编辑器选区入口在编辑器浮动菜单里打开。插件不需要为两种屏宽各写一套页面，宿主负责容器。

## 常见问题

| 现象 | 原因 |
| --- | --- |
| 装了插件但槽位里没东西 | 清单声明被拒（槽位名拼错、`page`/`handler`/`screen` 出现两个、同槽位超过 2 个）——安装时就会失败，看导入报错 |
| 侧栏只看到部分入口 | 侧栏底部最多内联 3 条，其余收进「更多插件」 |
| 顶栏菜单里整段消失 | 没有任何插件声明 `chat.header.menu` 时宿主隐藏该段 |
| 点入口报「no compose route」 | `screen` 路径与进程注册路线不一致 |
| 页面打不开、白屏 | 页面不是包内文件、超过 512 KiB，或被 CSP 拦下（远程脚本、iframe） |
| 返回键直接退出插件 | `onBack` 没有注册，或处理器返回了 `false` |

> 部分内容参照 Operit2 文档改写（AGPL-3.0）；槽位、入口解析与面板路线匹配取自 Aharou 的插件宿主实现。
