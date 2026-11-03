#!/usr/bin/env python3
"""经 GitHub API 建或移动 lightweight tag（refs/tags/<tag>）。

默认指向远端分支（--branch，默认 master）的最新 sha，不用本地 HEAD：
gh_api_push.py 推出去的 commit 与本地 sha 不同，本地 sha 在远端不存在。
tag 已存在则 PATCH 强制移动；推 v* tag 会触发发版 CI。
"""

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gh  # noqa: E402

_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def parse_args():
    p = argparse.ArgumentParser(
        description="经 GitHub API 建/移 lightweight tag（git push origin <tag> 不通时用）。",
        epilog="示例：python3 scripts/push/push_tag.py v2.45.0\n"
               "      python3 scripts/push/push_tag.py v2.45.0 <40 位 commit sha> --dry-run\n"
               "推完对齐本地：git tag -d <tag>; git fetch origin --tags",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("tag", help="tag 名，如 v2.45.0")
    p.add_argument("commit", nargs="?", help="要指向的完整 40 位 commit sha（默认远端 --branch 最新提交）")
    p.add_argument("--branch", default="master", help="未给 commit 时取哪个远端分支的最新 sha（默认 master）")
    p.add_argument("--repo-dir", default=".", help="本地仓库目录，用于解析 origin 与取 token（默认当前目录）")
    p.add_argument("--repo", help="GitHub 仓库 owner/name（默认从 origin 解析）")
    p.add_argument("--dry-run", action="store_true", help="只发 GET，打印将要发的写请求")
    return p.parse_args()


def main():
    args = parse_args()
    if args.tag.startswith("refs/") or not args.tag.strip():
        _gh.die("tag 名只写短名，如 v2.45.0")
    repo_dir = _gh.repo_root(os.path.expanduser(args.repo_dir))
    owner, name = _gh.resolve_repo(repo_dir, args.repo)
    token, source = _gh.get_token(repo_dir, required=not args.dry_run)
    _gh.log("仓库 %s/%s，token 来源：%s%s" % (owner, name, source, "（dry-run）" if args.dry_run else ""))
    gh = _gh.GitHub(owner, name, token)

    if args.commit:
        target = args.commit.lower()
        if not _SHA_RE.match(target):
            _gh.die("commit 需给完整 40 位 sha")
        try:
            gh.get_commit(target)
        except _gh.GhError as e:
            if e.status == 404:
                _gh.die("远端不存在提交 %s（本地 sha 若经 gh_api_push.py 推过，远端 sha 已不同）" % target[:8])
            raise
    else:
        target = gh.get_ref("heads/" + args.branch)
        if not target:
            _gh.die("远端没有分支 %s" % args.branch)

    current = gh.get_ref("tags/" + args.tag)
    if current == target:
        print("tag %s 已指向 %s，无需改动" % (args.tag, target[:8]))
        return
    if current:
        _gh.log("tag %s 已存在（%s），强制移动" % (args.tag, current[:8]))
        _gh.write(gh, "PATCH", gh.repo_path("/git/refs/tags/" + _gh.quote_ref(args.tag)),
                  {"sha": target, "force": True}, args.dry_run)
    else:
        _gh.write(gh, "POST", gh.repo_path("/git/refs"),
                  {"ref": "refs/tags/" + args.tag, "sha": target}, args.dry_run)

    if args.dry_run:
        print("[dry-run] 未发写请求；实际执行将使 %s 指向 %s" % (args.tag, target[:8]))
        return
    print("%s → %s（远端 tag %s）" % (current[:8] if current else "（新建）", target[:8], args.tag))
    print("对齐本地：git tag -d %s; git fetch origin --tags" % args.tag)


if __name__ == "__main__":
    try:
        main()
    except _gh.GhError as e:
        _gh.die(str(e))
    except KeyboardInterrupt:
        _gh.die("已中断", 130)
