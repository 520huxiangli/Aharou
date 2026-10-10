# 依赖版本检查

Aharou 可以让 AI 帮你检查一个 Gradle 工程里的依赖有没有新版本，并把「改哪一行、从什么改成什么」列出来。

## 怎么用

在对话里直接说需求即可，例如：

> 帮我看看这个项目哪些依赖可以升级

AI 会调用 `check_dependencies` 工具，扫描：

- `gradle/libs.versions.toml`（Gradle 版本目录）
- `settings.gradle(.kts)`、根 `build.gradle(.kts)`，以及子模块的 `build.gradle(.kts)`

然后对照 Maven 仓库（Google、Maven Central、Gradle 插件门户、JitPack，以及工程自己声明的镜像仓库）查最新版本。

## 结果怎么看

聊天里会出现一张「依赖版本检查」卡片，逐项列出：

- `group:artifact`
- 当前版本 → 可升级到的版本

点某一项可复制建议的改动。卡片下方的「N 条声明未能解析」表示有少数写法工具没认出来（例如跨多行的特殊写法），可以把这几行回给 AI 手动处理。

## 让它改

卡片和工具返回里，每一项可升级依赖都带着「改哪一行、从什么改成什么」（`old_string` / `new_string`）。确认无误后让 AI 用 `editFile` 写回即可——`check_dependencies` 本身只读，不会替你改文件。

::: tip 关于缓存
仓库版本信息会缓存在本机 12 小时。想要立刻拿最新数据，在对话里说「强制刷新」即可（工具会带 `refresh`）。
:::

::: warning 网络
项目需要能访问 Maven 仓库。若卡片提示无法访问仓库，说明当前网络到仓库不通，此时显示的版本可能不是最新。
:::
