# CLAUDE.md

本仓库的 AI 协作规则（Claude Code、AiCode 等 AI 编程助手均读取本文件），优先级高于默认通用规则。

## 基本约定

- **永远使用中文回复。**
- 文件读写用已有的文件工具，不用 `cat` / `sed` / `echo >` 代替。
- Android 应用：Kotlin + Compose + Hilt + Coroutines/Flow，模块 `:app` / `:terminal-emulator` / `:terminal-view`。后两个是 Termux 终端组件（包名 `com.termux.terminal` / `com.termux.view`），改终端功能优先动 `feature/terminal/`。
- **优先使用项目自定义组件（硬规则）**：
  - **输入框**：优先使用 `core/ui/AppTextField.kt`（及其配套配色 `appTextFieldColors()`、弹窗专用 `dialogTextFieldColors()`），禁止在业务页面无故裸写带有长下划线的原生 `TextField`，禁止在各模块私自重复包装 OutlinedTextField。
  - **开关**：一律使用 `core/ui/AppSwitch.kt`，禁止使用原生 M3 `Switch`。
  - **模态底栏**：一律使用 `core/ui/AdaptiveModalBottomSheet.kt`（已集成平板自适应与 fling fix），禁止直接调用原生 `ModalBottomSheet`；长表单容器外层注意追加 `.imePadding()` 避让软键盘。
  - **分组卡片与列表行**：设置与表单页面优先复用 `feature/settings/presentation/component/SettingsGroupComponents.kt` 中的 `SettingsGroup`、`SettingsRow`、`SettingsDivider`、`SettingsGroupHeader`，保持统一卡片与层级质感。
  - **左滑删除**：一律使用 `core/ui/SwipeToDeleteRow.kt`。
  - **主题与设计规范**：严格遵循 `core/theme/AIEditorTheme.kt` 的 `Spacing`、`Radius`（含 `Radius.mdLarge = 12.dp`）与 `MaterialTheme.semanticColors`，禁止随意硬编码非标魔数与色彩。

## 构建与验证

**改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 提交前跑冒烟编译；并跑 `check_migrations.py` 迁移对账。** 改了 `skills/` 下官方技能或 `evals/` 时，跑 `check_skills.py` 技能自检。只改文档 / 资源文案 / 纯 `.md` 时这些都跳过。

单元测试（`:app:testUniversalDebugUnitTest` / `:app:testArmsoloDebugUnitTest`——两个 flavor 各一个任务名，**没有** `testDebugUnitTest`）在容器里可能根本跑不动：项目用 Robolectric，首次要下对应 SDK 的 `android-all-instrumented`（约 190 MB，**不走 Gradle 镜像**，流量网络下会永久 hang：worker `wchan=futex_wait`、CPU 近 0、daemon 日志停写）。**跑不动就跳过交给 CI** —— `.github/workflows/android-release.yml` 在构建前会跑同一套单测，失败会拦住发版。

| 用途 | 命令 |
| --- | --- |
| 冒烟编译（日常默认） | `./gradlew :app:assembleUniversalDebug` |
| 推送前单测 | `./gradlew :app:testUniversalDebugUnitTest` |
| 推送前迁移对账 | `python3 scripts/check_migrations.py`（或 `./gradlew checkMigrations`） |
| 推送前技能自检 | `python3 scripts/check_skills.py`（frontmatter/命名、description、验收用例齐全、市场配置与文档同步） |
| 推送前架构门禁 | `python3 scripts/check_architecture.py`（或 `./gradlew checkArchitecture`；已挂 `preBuild`，禁用组件 import、单文件行数棘轮、双语 strings 不一致会直接编不过） |
| 发版构建 APK / AAB | `./gradlew assembleRelease` / `./gradlew bundleRelease` |

- 只跑单个测试类：`./gradlew :app:testUniversalDebugUnitTest --tests "com.aharou.<包>.<类>Test"`；结果读 `app/build/test-results/testUniversalDebugUnitTest/TEST-*.xml`——**看用例数，别只看 BUILD SUCCESSFUL**（`--tests` 过滤成 0 个也是成功）。

- **别用聚合任务做日常验证**：`assembleDebug` / `assembleRelease` / `test` / `build` 都会跨三个 flavor 全跑，耗时极长。
- **别用 `--rerun-tasks` 强制全量重编**：它与 dex 增量缓存冲突，会在 `dexBuilderUniversalDebug` 上报 `NoSuchFileException` 并让后续编译停在 `UP-TO-DATE`（日志照样写 SUCCESSFUL，但 APK 不更新）。真要强制重编就删中间产物。
- 产物：`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`、`.../bundle/<flavor>/release/app-<flavor>-release.aab`。
- flavor 按 ABI 拆分：`universal`（arm64-v8a + x86_64）、`armsolo`（仅 arm64-v8a）、`x86solo`（仅 x86_64）。
- buildType 三个：`debug`（applicationId 后缀 `.debug`）、`beta`（后缀 `.beta`，与 release 同配置同签名，CI 用它出测试包）、`release`。三者可与正式版同机共存，日常验证用 `.debug` 即可。
- release 签名凭据读 `app/keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）；本地通常不存放签名文件，CI 从 GitHub secret 还原到 `app/aicode.jks`。
- **`targetSdk = 35`**（`minSdk = 26`）：早年锁 28 是为了绕开 PRoot 的 W^X / SELinux 限制（App 可写目录不许 execve），后来 proot 全套改由 jniLibs 装进 `nativeLibraryDir`（`apk_data_file`，允许 execve），这条理由已消失。34 起前台服务必须声明类型并申请对应权限，35 起 dataSync 前台服务有「24 小时内累计 6 小时」上限——故两个前台服务统一用 `specialUse`。共享存储直读依赖「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），代码改动前先看 `app/build.gradle.kts` 的 `defaultConfig` 注释。

### CI 全景（`.github/workflows/`）

- `ci.yml`：push 与 PR 门禁，构建前跑迁移对账、官方技能自检与同一套单测。
- `android-release.yml`：由 push `v*` tag 触发，构建 APK / AAB 并发布 GitHub Release；构建前同样跑迁移对账、技能自检与单测，失败会拦住发版。
- `beta.yml`：push `master` 时自动构建 `.beta` 测试包（universal、正式签名、与 release 同配置），**只传 Actions Artifacts（保留 90 天），不进 Release**；纯文档/资源改动按 `paths-ignore` 跳过。
- `docs-deploy.yml`：**只在 push `v*` tag 时部署**文档站到 GitHub Pages（https://520huxiangli.github.io/Aharou/）——改完 `docs-site/` 推 `master` 不会上线，要等下一次发版才生效。
- `sync-gitcode.yml` / `sync-models.yml`：每日 cron 定时同步 GitCode 镜像与 models.dev 模型数据，不用手动跑。

## 架构地图

feature-based 分层 + DDD。入口 `AIEditorApp` 初始化 `FileLogger`、`TerminalKeepaliveService`、`McpManager`。

- **`core/`**：跨 feature 基础设施 —— `db/`（含 `MigrationLoader.kt`）、`net/`、`theme/`、`ui/`、`util/`（含 `FileLogger`）。
- **`feature/`**（`app/src/main/java/com/aharou/feature/`）：
  - `agent`：AI agent 核心 —— 提示词、工具注册与权限、MCP、provider 适配（`data/remote/` 下 `anthropic` / `openai` / `gemini`）。
  - `terminal`：终端与会话。本地模式 Termux 组件 + PRoot（`LinuxContainerEngine`）；远程模式 sshj。
  - `workspace`：工作区与 DocumentsProvider，远程走 `RemoteSftpFileAccess`。
  - `editor`：sora-editor 编辑器。`git`：Git 操作。`settings`：provider、日志、保活等设置。
  - `browser`：内置 WebView 浏览器与网页自动化（`BrowserUseManager` / `BrowserTool`）；`sandbox`：容器文件浏览与预览（`FileBrowserScreen` / `FilePreviewScreen`）。
  - `voice`：语音识别、合成、通话、唤醒（`call/` / `assistant/`）；`pet`：桌面伙伴小染（`PetOverlayService`）；`onboarding`：首次启动引导。
  - `backup`：备份恢复与加密。`credentials`：凭据管理与注入容器。
- **远程 SSH 链路**：`RemoteSshConnection`（共享 sshj client）+ `RemoteSshEngine`（执行命令）+ `RemoteSftpFileAccess`（文件）+ `RemoteTerminalSessionManager`（终端）。
- **工具系统**：`feature/agent/domain/tool/` 下各工具经 `ToolRegistry` 注册，执行权限由 `ToolPermissionManager` 与 `ToolPermissionPolicyEngine` 管控。
- **MCP**：`feature/agent/domain/mcp/`，连接远端 server 并动态注册其工具。**DI**：Hilt，各 feature 自带 DI 模块。
- **延伸阅读**：主要运行时调用链见 `docs/EXECUTION_TRACES.md`，文件定位见 `docs/FILE_INDEX.md`。

### 数据库与迁移

Room（`feature/agent/data/local/database/AgentDatabase.kt` + 各 DAO），迁移分两套，**一个版本只能二选一**：

- **文件式（默认）**：`core/db/MigrationLoader.kt` 读 `app/src/main/assets/migrations/{VERSION}_description.sql`，由 `SqlScriptSplitter` 按语句切分（识别注释与字符串字面量，**字符串里可放心写 `;`**，不再需要 `char(59)` 绕行）；整段迁移包事务、任一条失败整体回滚，成功记入 `migration_history` 表。
- **AutoMigration**：纯 schema 变更（加列/建表/索引）可用 `@AutoMigration(from = N-1, to = N)` 编译期自动生成，改 entity 忘写迁移会直接编译失败；含数据清理/重命名/改约束的版本必须走文件式。

改 schema 三步：

1. 递增 `AgentDatabase.kt` 的 `SCHEMA_VERSION`（当前 65）。
2. 文件式：在 `app/src/main/assets/migrations/` 新建 `{VERSION}_description.sql`（如 `46_add_provider_multi_key.sql`），**编号必须连续**；AutoMigration：加注解，保证 `to == SCHEMA_VERSION` 且 `from` 衔接文件式最大版本。
3. 写入 DDL/SQL，启动时自动执行并记入 `migration_history` 表。

**跨分支冲突硬规则**（RC/hotfix 与 `master` 并行推进时）

- **发布即冻结**：已打 `v*` tag 的迁移文件内容与编号不可再改，补丁只能靠新增迁移修正。
- **合流后移**：RC/hotfix 若带数据库迁移，其迁移号一旦随 tag 发布即占用；合回 `master` 时，`master` 上所有编号 ≤ 已发布最大版本号的未发布迁移必须整体重编号到该上限之后（内容一字不改、编号连续），并同步递增 `SCHEMA_VERSION`。已发布号一律不可复用。
- **对账脚本**：`python3 scripts/check_migrations.py`（或 `./gradlew checkMigrations`）校验编号连续、SCHEMA_VERSION 一致、已发布迁移未被篡改/复用，**推送前必跑**；CI（ci.yml 与 android-release.yml）已在构建前挂载，合流后未后移会被直接拦下。

## 资产同步（硬规则）

`app/src/main/assets/prompts/`（AI 提示词）与 `docs-site/docs/`（用户文档）是 AI Agent 的知识来源，必须与代码同步：

- **AI 工作流改动 → 同步 `prompts/`**：工具增删改名、参数签名变化、agent 行为或提示词逻辑调整，都要更新 `prompts/` 下对应文件（自行查找，不存在则新建）。静态基线片段在 `prompts/` 顶层（`NN-名称.md`，常驻注入），按场景注入的叶子片段在 `prompts/agent/`；低频专门规则另放 `assets/rules/`（不进常驻提示词，由 `loadRule` 工具按需取正文，可被 `~/.aharou/rules/` 同名覆盖）。
- **功能或工具变化 → 检查 `docs-site/docs/`** 是否有使用文档要更新。
- **UI 变化（新增页面、改交互、调布局、改文案）→ 必须更新 `docs-site/docs/`**；新增文档页同步加进 `docs-site/.vitepress/config.ts` 侧栏与 `docs-site/docs/guide/overview.md` 索引。
- **用户可见中文文案 → 必须进双语 strings.xml**：写入 `values/strings.xml`（中文）与 `values-en/strings.xml`（英文），代码用 `stringResource(R.string.xxx)` 引用。**禁止在 `.kt` 中硬编码中文 UI 文案。** 命名用语义化英文小写下划线，跨页面复用的加 `common_` 前缀。

**文档目录约定**：`docs-site/docs/` 是文档唯一事实源，`guide/` 放功能说明、`advanced/` 放环境搭建与进阶教程。构建时由 `syncAiDocs` task（已挂 `preBuild`）生成进 APK 的 `assets/docs/`，AI 在容器内看到的是 `~/.aharou/docs/{guide,advanced}/*.md`（`~/.aicode/docs` 是同一份的兼容旧路径）。**面向用户书写**：讲清怎么做、会看到什么、出错怎么办；变量名、错误码、内部实现路径属于 `prompts/`，别写进用户文档。

## Git 提交规范

Conventional Commits，格式 `<type>(<scope>): <subject>`，正文空行隔开。校验脚本在 `.githooks/commit-msg`，但 **hook 需要先启用才生效**：`git config core.hooksPath .githooks`（新 clone 后不会自动启用，没配就相当于没有校验）。

- **type** ∈ `feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope** 可选，用功能模块：`agent | settings | terminal | workspace | git | ui | mcp | db | core | docs | build | deps`
- **subject** 一行简述，中英文均可，句末不加句号。例：`fix(settings): 修复 provider 保存时校验失败`
- 仅紧急情况可用 `git commit --no-verify` 跳过校验。

## 分支工作流

Tag 驱动发版，平时 `master` 上的提交不影响发布包。

- **新功能 / 复杂多文件改动 / 架构重构** → 开 `feat/xxx` 或 `refactor/xxx`，动手前先定好分支名，验证通过后合回 `master`。
- **日常 bug 修复 / 补单测 / CI 与构建配置 / 纯文档 / 资源文案** → 直接提交 `master`。
- **预览版热修复** → 从该 RC Tag 拉 `hotfix/xxx`，见〈发版〉。
- **合并后清理分支**：`git branch --merged master` 确认后 `git branch -d <branch>`；推送过的同步 `git push origin --delete <branch>`。

## PR 合并流程

**在本地验证完再推送，不用 `gh pr merge` 在远端直接合**（绕过本地编译验证，且与本地在途提交分叉）。

1. **摸状态**：`git fetch origin` → `gh pr view <N> --json state,mergeable,mergeStateStatus,headRefName,changedFiles` + `gh pr checks <N>` → `git rev-list --left-right --count origin/master...master`。fork 来的 PR 的 Vercel 必报 `Authorization required to deploy`（`mergeStateStatus` 随之 `UNSTABLE`），属正常，只看 `Build & Test` 是否 pass。
2. **拉到本地**：`git fetch origin pull/<N>/head:pr-<N>`。勿用 `gh pr checkout`，它会改当前分支的跟踪关系。
3. **审查**：`gh pr diff <N> > /tmp/pr<N>.diff` 通读。**PR 描述与 CodeRabbit 结论都不算证据**，逐项落地核实：
   - 新引入的库在 `app/build.gradle.kts` 里是否已有
   - 新用的 `R.string.xxx` 在中英两份 `strings.xml` 里是否都存在
   - 调用的既有组件签名是否匹配；被改了签名的组件用 `rg` 找出全部调用点确认都改到
   - 新交互是否复用既有实现（例：模型拖拽排序对照 `ProvidersAndLogSection.kt` 的手柄写法）
   - 安全面：可疑网络请求、凭据外发、命令拼接
   - 〈资产同步〉各项；含数据库迁移的 PR 必须跑 `python3 scripts/check_migrations.py` 对账（编号连续、与已发布 tag 无冲突）
   - 超出 PR 标题范围的改动记下来交用户定夺
4. **预演冲突**：`git merge-tree --write-tree --name-only master pr-<N>`。只输出一个 tree hash = 可干净合并；列出文件名 = 有冲突。
5. **临时分支合并**：`git switch -c merge/pr-<N> master` → `git merge --no-ff pr-<N> -m "Merge pull request #<N> from <owner>/<head-branch>" -m "<PR 标题>"`。
6. **冒烟 + 真机验证**：动了 UI 或交互的 PR 必须发 APK 给用户真机验证，**通过才继续**。
7. **单测 + 推送**：`git switch master && git merge --ff-only merge/pr-<N> && git push origin master`。head commit 成为 `master` 祖先后 GitHub 自动标记 Merged，用 `gh pr view <N> --json state,mergeCommit` 核实。
8. **清理**：`git branch -d merge/pr-<N> pr-<N>`。不动 stash 与未跟踪文件。

**必须停下来问用户**：本地 `master` 有未推提交（推 `master` 会把在途提交一并带上去）／预演有冲突／PR 含数据库迁移或构建、CI、签名改动／审查发现超范围改动或质量问题／编译或单测失败。

## Issue 审查

收到待评估的 issue（新功能 / bug 报告 / 改进建议）时，按以下顺序评估并输出审查报告：

1. **通读 issue**：提炼核心诉求、验收标准、所属模块（对照〈架构地图〉）与优先级；关联的讨论、PR、历史 commit 一并核实。**issue 里提出的实现方案只是作者的建议，不代表最优解，不得直接采纳**，优劣须在第 2~4 步结合代码与查证独立判断。
2. **阅读相关代码**：按 issue 提到的模块定位并阅读真实代码（`list` / `search` / `readFile`），确认描述是否属实、影响面多大。**陈述事实以核实过的代码为准，issue 作者的描述不算证据**。做法拿不准或需要业界对照时，用 `websearch` / `webfetch` 查官方文档、同类实现与社区实践。
3. **评估是否合适**：结合项目约束判断该改动该不该做——
   - 是否符合 feature-based 分层 + DDD 现有结构，是否重复造已有轮子；
   - 是否触碰硬约束（`targetSdk` 35、迁移编号连续、双语 strings.xml、`prompts/` / 文档同步等〈资产同步〉各项）；
   - 改动范围与风险：是否触及启动 / 容器 / 签名 / CI 等敏感面（此类改动发版需按〈发版〉先发 RC）。
4. **平替方案**：不受 issue 提案的局限，以核实过的代码与查证资料为依据，给出至少一个替代实现并对比（实现成本、风险、维护性、对既有用户的影响），给出推荐结论；需要用户取舍时用选择题征询，别替用户拍板。
5. **输出报告**：以对话回复形式输出报告，包含：结论（采纳 / 拒绝 / 待定）、理由、相关代码位置（带行号链接）、方案对比、建议的下一步（是否进入 PLAN、是否开分支按〈分支工作流〉）。报告不落盘，除非用户明确要求写文件。

## 发版

**版本号唯一事实源是 Git Tag / Commit，无需手写。** `versionName` 由 `gitVersionName()` 解析（tag `v1.7.0` → `1.7.0`，非 Tag 提交 → `1.7.0-dev.N+<hash>`）；`versionCode` 由 `gitCommitCount()` 递增。

### 发版节奏（本项目实际流程，以此为准）

**测试包（`.debug`）验过没问题 → 直接发正式版，不强制先发 RC。**（上游那套「含新功能或行为变化必须先发 RC」的分发纪律本项目不采用，不得据此拦发布；要发 `-rcN` 也可以、属可选，`v1.14.0-rc1` 有先例。）

### 步骤

0. **更新内置模型数据（仅打 Tag 前执行）**：`python3 scripts/update-models-dev-assets.py` 从 models.dev 刷新 `app/src/main/assets/api.official.json`（只保留内置 12 个官方 provider、不引入新 provider，现有 provider 下可扩充模型与单价）。**该脚本只在打 Tag 发版前手动执行；日常提交 / 推 master / 其它开发流程一律不跑**。**失败时脚本非零退出且不改快照——直接跳过此步发版，不要重试或手改文件**；成功则把快照改动一并提交到即将打 Tag 的分支。
1. **在 `master` 最新提交上打 Tag 并推送**：`git tag v1.7.0-rc1 && git push origin v1.7.0-rc1`。**严禁在 `feat/*` / `refactor/*` 上打 Tag 发版**，必须先合入 `master`。
2. CI 捕获 `v*` Tag 后自动推导版本、构建 APK、发布 Release。
3. **真机装 RC 包**，至少跑通 AI 对话 + 终端 + 容器启动三条主线。
4. **有问题**：从该 RC Tag 拉 `hotfix/xxx` 修复（**勿从最新 `master` 或功能分支拉**，否则会把已合入的未发版功能带进修复包）→ 升 rc 序号打 Tag 重发 → 修复合回 `master` 并推送 → 删 hotfix 分支。这是允许在非 `master` 分支打 Tag 的唯一例外。**无问题**：直接打正式 Tag（`v1.7.0`）转正。
   - 若 hotfix 带数据库迁移：迁移号随 tag 发布即冻结（发布即冻结），合回 `master` 时 `master` 上编号更小的未发布迁移必须后移（见〈数据库与迁移〉合流后移），push 前先跑 `check_migrations.py`，CI 兜底拦截。

## 记忆写作纪律

记忆系统的实测规范（来自服务器上 `memory-v2` / `memory-engineering` 技能），**写入前逐条自查**。

- **写入门槛只有一条判据**：下次新开对话，不知道这件事会不会让回答变差？会变差才写，不会就不写。

- **禁止写入**：本次对话的临时上下文；自己推断、用户未确认的结论；随口提到的话题；翻聊天记录或读代码就能查到的事实。

- **写法**：写成完整第三人称陈述句，条目脉离上下文也要读得懂；**禁用「刚才」「他说的」「上面」这类指回对话的词**。

- **裁决去重（Smart Add 三条）**：写入前与已有条目比相似度——**≥0.92 ⇒ 跳过**（已有同义条目，只刷新时间）；**0.62~0.92 ⇒ 冲突**（旧值进归档、写新值、演变史 +1，是改写不是覆盖）；其余 ⇒ 新增。

- **矛盾绝不自动合并（F4）**：两条记忆冲突时**两条都保留、各标日期、标注「待确认」，禁止自行裁定哪条对**。自行选一边会抹掉「曾经有过分歧」这个证据，而那条被自动裁定过的错记忆会进入之后每一个读到它的会话。

- **遗忘不是可选项（F1）**：任何记忆系统都必须写明「什么会离开」。核心档案（`CORE` / `GLOBAL` 类）永远保留；其余长期未被印证或已过期的条目**降级 / 归档，而不是无限积累**。**一条错记忆不会只错一次**。
