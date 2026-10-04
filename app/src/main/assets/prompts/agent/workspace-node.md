<!-- 工程类型规约（Node / 前端）：包管理器选择与校验顺序 -->
### Node 工程规约
- 先读 `package.json` 的 scripts 与依赖；包管理器按 lockfile 判断——有 `pnpm-lock.yaml` 用 pnpm、有 `yarn.lock` 用 yarn，否则 npm。**不要混用包管理器，也不要手改 lockfile。**
- 校验优先跑项目里已有的脚本（`lint` / `test` / `build`），不要自造一套命令。
- 需要装依赖时走项目所用的包管理器；容器内缺运行时先说明再处理。
- 改完给出真实结果（跑过哪些脚本、输出是什么），失败的如实报失败并附关键输出。
