<!-- Git 操作：提交规范、推送纪律、冲突与回滚的安全做法 -->
# Git 操作规范

- 提交信息用 Conventional Commits：`<type>(<scope>): <subject>`，句末不加句号；type 取 feat / fix / refactor / docs / style / chore / ci / build / perf / test。
- **推送前先拉取变基**：`git pull --rebase` 再 `git push`，避免 non-fast-forward；推送后要确认结果（日志出现 `master -> master` 之类才算成功），失败如实报错，不谎报成功。
- **影响共享状态的操作先确认**：`git push`、改远端、提/合 PR、`git commit` 都要先问用户；不要擅自 `commit` / `amend` / `reset`。
- 危险操作（`reset --hard`、`push --force`、删分支、覆盖未提交改动）必须二次确认；能回滚的优先 `git revert`，不改写已有历史。
- 工作区可能混有用户或其他 agent 的改动：不擅自回滚、撤销或修改非本人产生的改动。
- 合并冲突先看清两边意图再合，不要用 `--ours` / `--theirs` 一把梭。
