# Git 提交、分支、PR 与发版

## Git 提交规范

Conventional Commits，格式 `<type>(<scope>): <subject>`，正文空行隔开。校验脚本在 `.githooks/commit-msg`，但 **hook 需要先启用才生效**：`git config core.hooksPath .githooks`（新 clone 后不会自动启用，没配就相当于没有校验）。`.githooks/pre-commit` 会跑 `check_task_list.py`，`.aharou/task-list.md` 里还有 `- [ ]` 时提交会被拒。

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
   - `30-assets-sync.md` 各项；含数据库迁移的 PR 必须跑 `python3 scripts/check_migrations.py` 对账（编号连续、与已发布 tag 无冲突）
   - 超出 PR 标题范围的改动记下来交用户定夺
4. **预演冲突**：`git merge-tree --write-tree --name-only master pr-<N>`。只输出一个 tree hash = 可干净合并；列出文件名 = 有冲突。
5. **临时分支合并**：`git switch -c merge/pr-<N> master` → `git merge --no-ff pr-<N> -m "Merge pull request #<N> from <owner>/<head-branch>" -m "<PR 标题>"`。
6. **冒烟 + 真机验证**：动了 UI 或交互的 PR 必须发 APK 给用户真机验证，**通过才继续**。
7. **单测 + 推送**：`git switch master && git merge --ff-only merge/pr-<N> && git push origin master`。head commit 成为 `master` 祖先后 GitHub 自动标记 Merged，用 `gh pr view <N> --json state,mergeCommit` 核实。
8. **清理**：`git branch -d merge/pr-<N> pr-<N>`。不动 stash 与未跟踪文件。

**必须停下来问用户**：本地 `master` 有未推提交（推 `master` 会把在途提交一并带上去）／预演有冲突／PR 含数据库迁移或构建、CI、签名改动／审查发现超范围改动或质量问题／编译或单测失败。

## 发版

**版本号唯一事实源是 Git Tag / Commit，无需手写。** `versionName` 由 `gitVersionName()` 解析（tag `v1.7.0` → `1.7.0`，非 Tag 提交 → `1.7.0-dev.N+<hash>`）；`versionCode` 由 `gitCommitCount()` 递增。

### 发版节奏（本项目实际流程，以此为准）

**测试包（`.debug`）验过没问题 → 直接发正式版，不强制先发 RC。**（上游那套「含新功能或行为变化必须先发 RC」的分发纪律本项目不采用，不得据此拦发布；要发 `-rcN` 也可以、属可选，`v1.14.0-rc1` 有先例。）

### 步骤

0. **更新内置模型数据（发版前跑，但不单独成笔提交）**：`python3 scripts/update-models-dev-assets.py` 从 models.dev 刷新 `app/src/main/assets/api.official.json`（只保留内置 12 个官方 provider、不引入新 provider，现有 provider 下可扩充模型与单价）。**流程照旧每版跑**，但跑完要把快照改动 squash 进本版最近的实质提交（在功能提交上 `git commit --amend`，或 `fixup` + `rebase --autosquash`），**不要单独出一笔 `chore(build): 更新内置模型数据`，也不在发版记录 / 版本说明里列条目**——它在 Release changelog 里只会是噪音（2026-10-20 主人定）。**失败时脚本非零退出且不改快照——直接跳过发版此步，不要重试或手改文件**。
1. **在 `master` 最新提交上打 Tag 并推送**：`git tag v1.7.0-rc1 && git push origin v1.7.0-rc1`。**严禁在 `feat/*` / `refactor/*` 上打 Tag 发版**，必须先合入 `master`。
   - **推送失败不许停手（硬规则）**：分支或 Tag 的 `git push` 报 GnuTLS、连接超时、443 不通这类网络错误时，最多再重试 1 次；仍不通**立刻改用 API 推送**——分支 `python3 scripts/push/gh_api_push.py master`，Tag `python3 scripts/push/push_tag.py <tag>`，只改少量文件时 `python3 scripts/push/push_paths.py <文件...> --branch master`（都不碰 tag）。推完按脚本提示 `git fetch origin && git reset --hard origin/<分支>` 对齐本地（API 建的 commit sha 与本地不同）。**推上去之前不许结束本轮、不许以「等网络恢复」收尾**；脚本本身报错就照记忆 `gh_api_push_script` 的流程现写，不许撂手。
2. CI 捕获 `v*` Tag 后自动推导版本、构建 APK、发布 Release。
3. **真机装包**，至少跑通 AI 对话、终端、容器启动三条主线。
4. **有问题**：从该 RC Tag 拉 `hotfix/xxx` 修复（**勿从最新 `master` 或功能分支拉**，否则会把已合入的未发版功能带进修复包）→ 升 rc 序号打 Tag 重发 → 修复合回 `master` 并推送 → 删 hotfix 分支。这是允许在非 `master` 分支打 Tag 的唯一例外。**无问题**：直接打正式 Tag（`v1.7.0`）转正。
   - 若 hotfix 带数据库迁移：迁移号随 tag 发布即冻结（发布即冻结），合回 `master` 时 `master` 上编号更小的未发布迁移必须后移（见 `20-architecture.md` 的合流后移），push 前先跑 `check_migrations.py`，CI 兜底拦截。
