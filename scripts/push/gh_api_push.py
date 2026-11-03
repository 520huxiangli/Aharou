#!/usr/bin/env python3
"""git push 不通时，经 GitHub REST API 把本地 HEAD 推到远端分支。

流程：读远端分支 sha → 校验本地 HEAD 是它的后代 → 按两者 diff 逐文件建 blob
（含删除、改名、可执行位与符号链接）→ 以远端 commit 的 tree 为 base_tree 建 tree
→ 建 commit（message 与作者取本地 HEAD）→ PATCH refs/heads/<分支>（不 force）。

注意：API 建出的 commit sha 与本地不同；本地多笔提交会被压成一条，message 只取 HEAD。
推完必须执行 `git fetch origin && git reset --hard origin/<分支>` 对齐，脚本自己不做 reset。
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gh  # noqa: E402


def parse_args():
    p = argparse.ArgumentParser(
        description="经 GitHub API 把本地 HEAD 推到远端分支（git push 不通时用）。",
        epilog="示例：python3 scripts/push/gh_api_push.py master\n"
               "      python3 scripts/push/gh_api_push.py master ~/workspace --dry-run\n"
               "token 依次取：GITHUB_TOKEN → git credential fill（host=github.com）→ "
               "~/.aharou/memory/SECRETS.md；任何输出都不打印 token。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("branch", nargs="?", default="master", help="远端分支名（默认 master）")
    p.add_argument("repo_dir", nargs="?", default=".", help="本地仓库目录（默认当前目录）")
    p.add_argument("--repo", help="GitHub 仓库 owner/name（默认从 origin 解析）")
    p.add_argument("--dry-run", action="store_true",
                   help="只发 GET 读远端，打印将上传的文件与写请求，不发任何写请求")
    return p.parse_args()


def collect_changes(repo_dir, base, head):
    """返回 (上传列表 [(path, mode, bytes)], 树条目补丁列表, 删除路径列表)。

    用 --no-renames 把改名拆成「删旧 + 加新」，这样 tree 补丁只需两类条目。
    """
    raw = _gh.git(repo_dir, "diff-tree", "-r", "-z", "--no-renames", "--no-commit-id", base, head, binary=True)
    parts = raw.split(b"\0")
    uploads, extra_entries, deletions = [], [], []
    i = 0
    while i < len(parts) and parts[i]:
        meta = parts[i].decode()
        path = parts[i + 1].decode("utf-8", "surrogateescape")
        i += 2
        old_mode, new_mode, _, new_sha, status = meta.lstrip(":").split(" ")
        if status == "D":
            deletions.append(path)
            kind = "commit" if old_mode == "160000" else "blob"
            extra_entries.append({"path": path, "mode": old_mode, "type": kind, "sha": None})
        elif new_mode == "160000":
            extra_entries.append({"path": path, "mode": new_mode, "type": "commit", "sha": new_sha})
        else:
            content = _gh.git(repo_dir, "cat-file", "blob", new_sha, binary=True)
            uploads.append((path, new_mode, content, status))
    return uploads, extra_entries, deletions


def main():
    args = parse_args()
    repo_dir = _gh.repo_root(os.path.expanduser(args.repo_dir))
    owner, name = _gh.resolve_repo(repo_dir, args.repo)
    token, source = _gh.get_token(repo_dir, required=not args.dry_run)
    _gh.log("仓库 %s/%s，分支 %s，token 来源：%s%s" % (owner, name, args.branch, source,
                                                 "（dry-run）" if args.dry_run else ""))
    gh = _gh.GitHub(owner, name, token)

    remote_sha = gh.get_ref("heads/" + args.branch)
    if not remote_sha:
        _gh.die("远端没有分支 %s（本脚本只推进已有分支，不新建）" % args.branch)
    head_sha = _gh.git(repo_dir, "rev-parse", "HEAD")
    _gh.log("远端 %s = %s，本地 HEAD = %s" % (args.branch, remote_sha[:8], head_sha[:8]))

    if remote_sha == head_sha:
        print("已是最新：远端 %s 与本地 HEAD 同为 %s，无需推送" % (args.branch, head_sha[:8]))
        return
    if not _gh.git_ok(repo_dir, "cat-file", "-e", remote_sha + "^{commit}"):
        _gh.die("本地没有远端提交 %s：远端有本地没拉到的提交，本地不是它的后代。"
                "先 git fetch origin 并把本地变基到 origin/%s 上再推。" % (remote_sha[:8], args.branch))
    if not _gh.git_ok(repo_dir, "merge-base", "--is-ancestor", remote_sha, head_sha):
        _gh.die("远端 %s（%s）领先或与本地分叉，本地 HEAD 不是它的后代；拒绝覆盖。"
                "先合入/变基远端提交再推。" % (args.branch, remote_sha[:8]))

    ahead = _gh.git(repo_dir, "rev-list", "--count", "%s..%s" % (remote_sha, head_sha))
    if ahead != "1":
        _gh.log("提示：本地领先 %s 个提交，经 API 会压成一条，message 只取 HEAD" % ahead)

    uploads, extra_entries, deletions = collect_changes(repo_dir, remote_sha, head_sha)
    status_label = {"A": "新增", "M": "修改", "T": "类型变更"}
    for path, mode, content, status in uploads:
        print("  %s %s（mode %s，%d 字节）" % (status_label.get(status, status), path, mode, len(content)))
    for path in deletions:
        print("  删除 %s" % path)
    for e in extra_entries:
        if e["type"] == "commit":
            print("  子模块 %s -> %s" % (e["path"], e["sha"][:8]))
    print("共 %d 个文件上传、%d 个删除" % (len(uploads), len(deletions)))

    remote_commit = gh.get_commit(remote_sha)
    base_tree = remote_commit["tree"]["sha"]
    message = _gh.git(repo_dir, "log", "-1", "--pretty=%B", head_sha)
    author = dict(zip(("name", "email", "date"),
                      _gh.git(repo_dir, "log", "-1", "--pretty=%an%x00%ae%x00%aI", head_sha).split("\0")))

    entries = _gh.upload_blobs(gh, [(p, m, c) for p, m, c, _ in uploads], args.dry_run) + extra_entries
    if entries:
        new_sha = _gh.create_commit(gh, remote_sha, base_tree, entries, message, author, args.dry_run)
    else:
        # 没有文件变化（如空提交）时直接复用远端 tree，GitHub 不接受空的 tree 补丁。
        body = {"message": message, "tree": base_tree, "parents": [remote_sha], "author": author}
        res = _gh.write(gh, "POST", gh.repo_path("/git/commits"), body, args.dry_run)
        new_sha = res["sha"] if res else _gh.DRY_SHA
    _gh.update_branch(gh, args.branch, new_sha, args.dry_run)

    if args.dry_run:
        print("[dry-run] 未发任何写请求；实际执行将把远端 %s 从 %s 推进到新 commit" % (args.branch, remote_sha[:8]))
        return
    print("%s → %s（远端 %s）" % (remote_sha[:8], new_sha[:8], args.branch))
    print("必须执行以下命令对齐本地（API 建的 commit sha 与本地不同，不对齐后续提交会分叉）：")
    print("  git fetch origin && git reset --hard origin/%s" % args.branch)


if __name__ == "__main__":
    try:
        main()
    except _gh.GhError as e:
        _gh.die(str(e))
    except KeyboardInterrupt:
        _gh.die("已中断", 130)
