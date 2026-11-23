# 上游插件类型声明快照（Operit2）

本目录收录 **Operit2 的插件 SDK 类型声明**，只作接口参考：对照方法名、参数与返回结构，方便移植与排查兼容问题。它**不是** Aharou 的对外接口。

- 写新插件以 `plugins/sdk/types/host.d.ts` 为准（配合 [宿主方法总览](/advanced/plugin/api-reference)）。
- 本目录的文件不参与 Gradle 构建，不打进 APK，不要 `require` / `import` 它们当运行时依赖。
- 只收 `.d.ts`（接口事实），**不含任何 `.ts` / `.js` 实现代码**。上游实现是 AGPL-3.0，Aharou 是 GPL-3.0，两码事不能混。

## 许可与来源

上游仓库 `AAswordman/Operit2`（AGPL-3.0）。每个文件顶部都有来源注释：

```ts
// 来源：AAswordman/Operit2（AGPL-3.0）plugins/types/v2/<文件名>，仅作接口参考，不含实现。
```

新增或替换文件时请保留这一行，并把路径写对。

## 两套契约：v1 与 v2

上游用 `manifest.hjson` 的 `api_version` 声明插件按哪套契约写：

| 取值 | 契约 | 声明目录 |
| --- | --- | --- |
| 缺省或 `1.0.0` | v1：全局函数 + `Tools.*` 的旧返回形状 | `plugins/types-v1/` |
| `2.0.0` | v2：结构化返回、`ToolPkg` 命名空间、`registerUiRoute` 等 | `plugins/types/v2/` |

Aharou 导入 `.toolpkg` 时会读这个字段并记进转换后的清单；运行期只跑一套兼容层（`operit-shim.js`），不按版本切换宿主行为。差别体现在「哪个方法存在、返回什么形状」，所以对照时别把两个目录混着看。兼容分级与迁移写法见[兼容 Operit 插件与 v1 / v2 契约](/advanced/plugin/compat-v1)。

## 当前快照收录情况

`v2/`：32 个 `.d.ts`，全部带来源头。`v1/`：25 个 `.d.ts` 加一份 `source.json`（记录上游提交与文件清单）。

两套都是**只读参考**：不参与 Gradle 构建、不打进 APK、不当运行时依赖。

## 怎么用这份快照

1. **移植旧插件**：先在 v1 里看它调了哪些 `Tools.*` / 全局函数，再到[兼容文档](/advanced/plugin/compat-v1)的映射表里找 Aharou 的对应写法。
2. **对参数**：找到目标方法名，看上游的参数与返回，再和 `plugins/sdk/types/host.d.ts` 里同名或近似的宿主方法比对——名字一样不代表参数一样。
3. **别照抄错误处理**：上游大量返回 `{success: false}`，Aharou 一律 reject 带 `{code, message}`，照抄会让失败被当成成功。

## 与 Aharou 声明的关系

`plugins/sdk/types/host.d.ts` 是**我们自己的**方法表类型，由 `scripts/check-page-bridge.py` 对账；本目录与它没有同步关系，也不该为了「看着一致」去改我们自己的声明。发现上游某个方法在 Aharou 有更好的对应实现时，改的是文档与 `host.d.ts`，不是这里。

> 说明：本目录为接口参考，按 AGPL-3.0 上游声明的收录约定保留来源注释；Aharou 文档中的对应说明按本项目术语重新表述。
