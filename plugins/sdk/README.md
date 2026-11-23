# 插件宿主 API 类型参考

`types/host.d.ts` 是宿主插件 API 的 TypeScript 声明，**不是 npm 包**：没有 `package.json`、没有构建流程、不会发布。
它只给插件作者与编辑器补全用，运行期不生效，方法集合随宿主实现变化（以 `PluginHostApiCatalog.kt` 与 `PluginHostApiDispatcher.kt` 为准）。

## 怎么在本地引用

插件仓库里不需要任何依赖，用 JSDoc 注释引入即可（VS Code 会按路径找类型）：

```js
/** @type {import('../../Aharou/plugins/sdk/types/host').ProcessCall} */
const hostCall = (method, params) => {
  // 按 docs-site「插件开发 / 快速开始」里的 JSON-RPC 写法实现
};

const result = await hostCall('host.fs.read', { path: 'data://note.md' });
// result 的类型是 { content: string; size: number }
```

TypeScript 工程用 `import type`，并在协议实现后标注一次：

```ts
import type { ProcessCall, PageCall, HostMethodMap } from '../../Aharou/plugins/sdk/types/host';

const hostCall: ProcessCall = (method, params) => sendRequest(method, params);
```

页面脚本平时直接用宿主注入的 `window.aharou.*`（实现在 `app/src/main/assets/plugin-ui/aicode.js`），不需要这份声明；
只有要自己拼一条桥调用时才用 `PageCall`：

```js
/** @type {import('../../Aharou/plugins/sdk/types/host').PageCall} */
const call = (method, params) => new Promise(/* ... */);
```

相对路径按插件仓库与 Aharou 仓库的实际位置调整；把声明文件复制进自己的工程也可以，只要别当成本地的运行时依赖。

## 约定

- 页面侧方法名不带前缀（`fs.read`），插件进程侧带 `host.` 前缀（`host.fs.read`）。
- 每个方法的名字、可用来源（页面 / 进程 / 浮层）见 `HostMethodMap` 与 `PageMethodName` / `ProcessMethodName` / `OverlayMethodName`。
- 参数里的必填键与返回结构来自分发器实现；`NoParams` 表示宿主不读任何参数。
- 失败一律 reject，带 `{ code, message }`，`code` 由宿主给出（如 `NOT_FOUND`、`PARAM_INVALID`）。
- 方法名对账脚本：`python3 scripts/check-page-bridge.py`（只校验方法名，不校验类型）。

## 尚未覆盖的域

`host.d.ts` 目前没有蓝牙 / BLE、音乐、角色卡、软件设置、记忆、工作流与 `tasker` 事件的声明，但这些方法已在宿主方法表（`PluginHostApiCatalog.kt`）与分发器里可用。补齐前以 `docs-site/docs/advanced/plugin/api-reference.md` 为准。
