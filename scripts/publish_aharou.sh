#!/bin/sh
# Aharou 源码上传（每次完工跑一次）
# 仓库: https://github.com/520huxiangli/Aharou
cd "$(dirname "$0")/.." || exit 1
if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -m "chore: 工作快照 $(date '+%Y-%m-%d %H:%M')"
fi
git push origin main && echo "[publish] 已上传: $(git log --oneline -1)"
