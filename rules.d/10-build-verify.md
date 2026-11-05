# 构建与验证

**先问再编**：每次构建前都要重新问（细则与命令约束见全局 `40-work-discipline`）。

**一次授权只换一次编译（硬规则，2026-11-04 主人重申）**：

- 上一轮说过的「编」不能拿来开这一轮的门；修完 bug 想重编也要重新问；同一个任务里连编两次也要问两次。
- 「接」「做吧」「继续」「好」这类话只是干活授权，**不是编译授权**。只有原话里明确出现「编 / 编译 / build / 打包 / APK / 出包」才算。
- 写 `.aharou/build-allowed` 之前先自问一句：主人本轮的这句话里有没有上面那几个词？没有就停下来问，不要替主人补全意思。
- 活没干完不编：先把手上的改动做完、自检通过，再问要不要编。

**编译与发版是两个口令，不能互相顶替（2026-11-04 主人定）**：

| 主人说 | 含义 | 能做 | 不能做 |
|---|---|---|---|
| 「编译 / 编 / 打包」 | 只要一个本地构建产物 | 写令牌跑 gradlew 出 debug 包，交共享区 | 跑不了测试（要另说「单测」）、打不了 tag、发不了 Release |
| 「单测 / 测试」 | 只跑单元测试 | 写令牌跑 `:app:test*` / `check*` | 出不了包 |
| 「发版」 | 走发版流程、出正式版 | 打 tag、推分支与 tag、盯 CI、发 Release | 不进 Gradle：既不能出包也不能跑单测 |

- 三个口令互不顶替，也不能拿上一轮的顶这一轮。发版流程里要跑定点单测，**主人得单独说一句「单测」**。

**`gradlew` 有硬闸门**（`gradlew` 开头的脚本段）：没有 `.aharou/build-allowed` 就拒绝执行任何 Gradle 任务，包括单测和 `checkMigrations`。主人明确同意后写入：

```
authorized_by: 主人
quote: "<主人原话>"
at: 2026-10-29T21:00
```

- `quote` 决定令牌权限（七个组合全部实测过）：
  - 含「编 / build / 打包 / APK / 出包」→ 放行 **出包类**（`:assemble*`、`:bundle*`、`:install*`、`:package*`、`:connectedCheck*`）；
  - 含「单测 / 测试 / test」→ 放行 **测试类**（`:test*`、`:check*`、`checkMigrations`、`checkArchitecture`、`checkSkills`、`checkProotAssets`）；
  - 含「发版 / release」→ **不进 Gradle**（发版走 git tag + 推 CI），既不放出包也不放测试；
  - 同一句里出现多个词就同时放行对应类别（例：「编，也跑单测」），否则拒绝。
- 两个类别**互不顶替**：「单测」「发版」都不能拿来出包，「编」「发版」都不能拿来跑测试。所以发版流程里的定点单测需要主人单独说一句「单测」。
- `at` 超过 30 分钟作废；可选 `commit: <HEAD>`，HEAD 变了就作废。审计日志记 `scope=build|test|release` 的组合。
- 令牌一次性：每调一次 `gradlew` 就被删掉并记进 `.aharou/build-audit.log`，所以一次授权只够一条 gradlew 命令，需要多个任务就写进同一条命令。
- CI 靠 job 级 `AHAROU_CI_BUILD=1` 放行，本地不许设这个变量绕过。

Aharou 特有的部分是：子代理交付后只做只读核实（读文件、抽查它自报的 file:line、跑 `python3 scripts/check_architecture.py`），不要顺手起构建；同一任务里的多处改动攒齐一次再编，不要做一个功能编一个功能。

**经用户同意后**，改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 提交前跑冒烟编译；并跑 `check_migrations.py` 迁移对账。 改了 `skills/` 下官方技能或 `evals/` 时，跑 `check_skills.py` 技能自检。只改文档 / 资源文案 / 纯 `.md` 时这些都跳过。

单元测试（`:app:testUniversalDebugUnitTest` / `:app:testArmsoloDebugUnitTest`——两个 flavor 各一个任务名，**没有** `testDebugUnitTest`）在容器里可能根本跑不动：项目用 Robolectric，首次要下对应 SDK 的 `android-all-instrumented`（约 190 MB，**不走 Gradle 镜像**，流量网络下会永久 hang：worker `wchan=futex_wait`、CPU 近 0、daemon 日志停写）。**跑不动就跳过交给 CI** —— `.github/workflows/android-release.yml` 在构建前会跑同一套单测，失败会拦住发版。

| 用途 | 命令 |
| --- | --- |
| 冒烟编译（日常默认） | `./gradlew :app:assembleUniversalDebug` |
| 推送前单测 | `./gradlew :app:testUniversalDebugUnitTest` |
| 推送前迁移对账 | `python3 scripts/check_migrations.py`（或 `./gradlew checkMigrations`） |
| 推送前技能自检 | `python3 scripts/check_skills.py`（frontmatter/命名、description、验收用例齐全、市场配置与文档同步） |
| 推送前架构门禁 | `python3 scripts/check_architecture.py`（或 `./gradlew checkArchitecture`；已挂 `preBuild`，禁用组件 import、单文件行数棘轮、双语 strings 不一致会直接编不过） |
| proot 资产校验 | `python3 scripts/check_proot_assets.py`（或 `./gradlew checkProotAssets`；已挂 `preBuild`，换了 .so 却没同步 `assets/proot-runtime.json` 时拦下） |
| 发版构建 APK / AAB | `./gradlew assembleRelease` / `./gradlew bundleRelease` |

- 只跑单个测试类：`./gradlew :app:testUniversalDebugUnitTest --tests "com.aharou.<包>.<类>Test"`；结果读 `app/build/test-results/testUniversalDebugUnitTest/TEST-*.xml`——**看用例数，别只看 BUILD SUCCESSFUL**（`--tests` 过滤成 0 个也是成功）。

- **别用聚合任务做日常验证**：`assembleDebug` / `assembleRelease` / `test` / `build` 都会跨三个 flavor 全跑，耗时极长。
- **别用 `--rerun-tasks` 强制全量重编**：它与 dex 增量缓存冲突，会在 `dexBuilderUniversalDebug` 上报 `NoSuchFileException` 并让后续编译停在 `UP-TO-DATE`（日志照样写 SUCCESSFUL，但 APK 不更新）。真要强制重编就删中间产物。
- 产物：冒烟编译出 `app/build/outputs/apk/universal/debug/app-universal-debug.apk`；发版出 `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`、`.../bundle/<flavor>/release/app-<flavor>-release.aab`。
- flavor 按 ABI 拆分：`universal`（arm64-v8a + x86_64）、`armsolo`（仅 arm64-v8a）、`x86solo`（仅 x86_64）。
- buildType 三个：`debug`（applicationId 后缀 `.debug`）、`beta`（后缀 `.beta`，与 release 同配置同签名，CI 用它出测试包）、`release`。三者可与正式版同机共存，日常验证用 `.debug` 即可。
- release 签名凭据读 `app/keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）；本地通常不存放签名文件，CI 从 GitHub secret 还原到 `app/aicode.jks`。
- **`targetSdk = 35`**（`minSdk = 26`）：早年锁 28 是为了绕开 PRoot 的 W^X / SELinux 限制（App 可写目录不许 execve），后来 proot 全套改由 jniLibs 装进 `nativeLibraryDir`（`apk_data_file`，允许 execve），这条理由已消失。34 起前台服务必须声明类型并申请对应权限，35 起 dataSync 前台服务有「24 小时内累计 6 小时」上限——故两个前台服务统一用 `specialUse`。共享存储直读依赖「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），代码改动前先看 `app/build.gradle.kts` 的 `defaultConfig` 注释。

## 产物交付（硬规则）

**编译产物交付按全局规则**（复制到跨工作区共享区 `~/shared`，汇报「文件名 + 字节数 + sha256」）。Aharou 特有的部分：

- 复制时文件名带上版本与短 hash，例如 `Aharou-2.14.0-dirty-1152-<sha8>.apk`；不要再问放哪，也不要自定输出路径。
- 装完核对：用 `Shizuku` 读设备上 `/data/app/**/base.apk` 的字节数与 sha256，与共享区那份对比。同 versionCode 的覆盖安装可能被 ROM 跳过，字节数和哈希是唯一可靠判据。
- 正式版发版包不用交付，主人自己在「软件更新下载」里取。

## CI 全景（`.github/workflows/`）

- `ci.yml`：push 与 PR 门禁，依次跑迁移对账、技能自检、架构门禁（含基线只许缩的比对）、`:app:assembleUniversalDebug`、`:app:testUniversalDebugUnitTest`。
- `android-release.yml`：由 push `v*` tag 触发，先校验 tag 位于 master 历史中，再跑迁移对账、技能自检与单测，然后构建 armsolo / x86solo 两个单架构 Release APK 并发布 GitHub Release（不发 AAB）；手动触发只允许 master，且强制标 pre-release。
- `beta.yml`：push `master` 时自动构建 `.beta` 测试包（universal、正式签名、与 release 同配置），产物既传 Actions Artifacts（保留 90 天），也发到固定 tag `beta-latest` 的 prerelease Release（每次覆盖，测试版通道靠它检测）；纯文档/资源改动按 `paths-ignore` 跳过，手动触发只允许 master。
- `sync-container-images.yml`：每周一 cron 从上游拉容器 rootfs（两个 ABI）→ 校验和 → 传 cnb → 改写两份镜像清单，代码里手改清单会被下次同步覆盖。
- `sync-maven-index.yml`：每周一 cron 把高频 Maven 坐标的版本元数据抓成离线索引发到 cnb，App 的依赖检查在网络仓库全部挂掉时用它兜底。
- `docs-deploy.yml`：**只在 push `v*` tag 时部署**文档站到 GitHub Pages（https://520huxiangli.github.io/Aharou/）——改完 `docs-site/` 推 `master` 不会上线，要等下一次发版才生效。
- `sync-models.yml`：每日 cron 同步 models.dev 模型数据到 data 分支（全量），不用手动跑；发版前另需手动跑 `scripts/update-models-dev-assets.py` 只保留内置 12 个官方 provider（见 `40-git-release.md` 的发版步骤 0）。
