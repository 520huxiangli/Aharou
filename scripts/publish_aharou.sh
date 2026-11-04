#!/bin/sh
# 已停用（2026-11-04）：这个脚本原本一条命令 `git add -A` 直推 master。
# 为什么改成直接拒绝而不是删掉：它绕过的正是分支工作流与提交规范（见 rules.d/40-git-release.md），
# 顺手 `git add -A` 还会把别人未完成的改动、临时文件一并提交；先改成会说话的拒绝，删不删由主人决定。
cat >&2 <<'MSG'
[拒绝] scripts/publish_aharou.sh 已停用：它会 `git add -A` 一条命令直推 master，
       绕过分支工作流、提交规范（Conventional Commits）与推送前的门禁。

请改走正常流程：
  git status                       # 先看工作区里都有什么，别把别人的改动带上
  git add <具体文件>                # 逐项 add，不要 -A
  git commit -m "type(scope): 说明"
  python3 scripts/check_migrations.py && python3 scripts/check_skills.py && python3 scripts/check_architecture.py
  git push origin master
MSG
exit 1
