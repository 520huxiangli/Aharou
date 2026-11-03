#!/usr/bin/env python3
"""经 GitHub API 只推指定文件：以远端分支 tree 为 base，替换给定路径后建 commit 推进分支。

不需要本地拥有远端对象，也不碰任何 tag（远端有正在跑的 release tag 时不会把它挪走）。
文件内容取工作区当前内容；工作区里已不存在的路径视为删除。
推完同样需要对齐本地：git fetch origin && git reset --hard origin/<分支>（会丢弃本地未推的改动，先确认）。
"""

import argparse
import os
import stat
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gh  # noqa: E402


def parse_args():
    p = argparse.ArgumentParser(
        description="经 GitHub API 只把指定文件推到远端分支，不碰 tag。",
        epilog="示例：python3 scripts/push/push_paths.py rules.d/40-git-release.md docs/agent-user-parity.md\n"
               "      python3 scripts/push/push_paths.py app/x.kt --branch master -m 'fix(core): ...' --dry-run",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("paths", nargs="+", help="要推的文件（相对仓库根或当前目录；工作区里已删除的视为删除）")
    p.add_argument("--branch", default="master", help="远端分支（默认 master）")
    p.add_argument("-m", "--message", help="commit message（默认取本地 HEAD 的 message）")
    p.add_argument("--repo-dir", default=".", help="本地仓库目录（默认当前目录）")
    p.add_argument("--repo", help="GitHub 仓库 owner/name（默认从 origin 解析）")
    p.add_argument("--dry-run", action="store_true", help="只发 GET，打印将上传的文件与写请求")
    return p.parse_args()


def to_repo_path(root, path):
    abs_path = os.path.abspath(os.path.expanduser(path))
    rel = os.path.relpath(abs_path, root)
    if rel.startswith("..") or os.path.isabs(rel):
        _gh.die("%s 不在仓库 %s 内" % (path, root))
    return rel.replace(os.sep, "/"), abs_path


def read_local(abs_path):
    st = os.lstat(abs_path)
    if stat.S_ISLNK(st.st_mode):
        return "120000", os.readlink(abs_path).encode("utf-8", "surrogateescape")
    if stat.S_ISDIR(st.st_mode):
        _gh.die("%s 是目录；只接受文件路径" % abs_path)
    mode = "100755" if st.st_mode & stat.S_IXUSR else "100644"
    with open(abs_path, "rb") as f:
        return mode, f.read()


def main():
    args = parse_args()
    root = _gh.repo_root(os.path.expanduser(args.repo_dir))
    owner, name = _gh.resolve_repo(root, args.repo)
    token, source = _gh.get_token(root, required=not args.dry_run)
    _gh.log("仓库 %s/%s，分支 %s，token 来源：%s%s" % (owner, name, args.branch, source,
                                                 "（dry-run）" if args.dry_run else ""))
    gh = _gh.GitHub(owner, name, token)

    uploads, deletions, seen = [], [], set()
    for p in args.paths:
        rel, abs_path = to_repo_path(root, p)
        if rel in seen:
            continue
        seen.add(rel)
        if os.path.lexists(abs_path):
            mode, content = read_local(abs_path)
            uploads.append((rel, mode, content))
            print("  上传 %s（mode %s，%d 字节）" % (rel, mode, len(content)))
        else:
            deletions.append(rel)
            print("  删除 %s" % rel)

    remote_sha = gh.get_ref("heads/" + args.branch)
    if not remote_sha:
        _gh.die("远端没有分支 %s" % args.branch)
    base_tree = gh.get_commit(remote_sha)["tree"]["sha"]
    _gh.log("远端 %s = %s" % (args.branch, remote_sha[:8]))

    message = args.message or _gh.git(root, "log", "-1", "--pretty=%B")
    entries = _gh.upload_blobs(gh, uploads, args.dry_run)
    # 删除要求远端确有该路径，否则 GitHub 建 tree 会报 422；事先查一遍给出明确报错。
    for rel in deletions:
        try:
            gh.get(gh.repo_path("/contents/%s?ref=%s" % (_gh.quote_ref(rel), remote_sha)))
        except _gh.GhError as e:
            if e.status == 404:
                _gh.die("%s 本地与远端都不存在，无法删除" % rel)
            raise
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": None})

    new_sha = _gh.create_commit(gh, remote_sha, base_tree, entries, message, None, args.dry_run)
    _gh.update_branch(gh, args.branch, new_sha, args.dry_run)

    if args.dry_run:
        print("[dry-run] 未发任何写请求；实际执行将把远端 %s 从 %s 推进到新 commit" % (args.branch, remote_sha[:8]))
        return
    print("%s → %s（远端 %s）" % (remote_sha[:8], new_sha[:8], args.branch))
    print("对齐本地（会丢弃未推改动，先确认）：git fetch origin && git reset --hard origin/%s" % args.branch)


if __name__ == "__main__":
    try:
        main()
    except _gh.GhError as e:
        _gh.die(str(e))
    except KeyboardInterrupt:
        _gh.die("已中断", 130)
