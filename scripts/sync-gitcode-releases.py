#!/usr/bin/env python3
"""把 GitHub 上已有的 Release APK 历史资产同步到 GitCode（幂等可重跑）。

用法：
  GITCODE_TOKEN=<token> python3 scripts/sync-gitcode-releases.py            # 同步保留集合
  GITCODE_TOKEN=<token> python3 scripts/sync-gitcode-releases.py --all      # 同步 GitHub 全部 Release

保留集合（未设 RELEASE_FILTER 时自动推导）：
  仅最新 KEEP_STABLE 个正式版（默认 3），不包含 RC（GitCode 上 RC 无法标记预发布，
  且轻量同步即可满足国内下载需求）；--all 可全量同步 GitHub 全部 Release。

可选环境变量：
  GH_REPO         GitHub 仓库，默认 520huxiangli/Aharou
  GITCODE_OWNER   GitCode 用户名，默认取 GH_REPO 同名
  GITCODE_REPO    GitCode 仓库名，默认取 GH_REPO 同名
  GITHUB_TOKEN    GitHub 令牌（可选，提高 API 限额）
  RELEASE_FILTER  Python 正则，覆盖自动推导，仅同步匹配 tag_name 的 Release
  KEEP_STABLE     保留的正式版数量，默认 3

与 Gitee 版的关键差异（GitCode API v5 实测确认）：
  - 认证：Authorization: Bearer <token>，不支持 access_token query 之外的方式也行（见代码）
  - 创建 Release：tag 不存在时自动在 target_commitish(main) 上创建，无需等待镜像同步；
    同 tag 已存在返回 409（幂等复用）
  - 上传附件：先 GET /releases/{tag}/upload_url?file_name= 取预签名地址，
    再 PUT 二进制（响应 headers 必须全部带上，参与 OBS 签名校验）
  - prerelease 标记：API 创建/更新均不生效（网页端才有），GitHub 的 prerelease 状态无法同步
  - 删除 Release：无独立 API；DELETE /tags/{tag} 会连带删除 release（副作用大），
    且 GitCode 未公布附件配额限制，故本脚本不做自动清理
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

UA = "aharou-gitcode-sync"

GH_REPO = os.environ.get("GH_REPO", "520huxiangli/Aharou")
GITCODE_OWNER = os.environ.get("GITCODE_OWNER") or GH_REPO.split("/")[0]
GITCODE_REPO = os.environ.get("GITCODE_REPO") or GH_REPO.split("/")[1]
GITCODE_TOKEN = os.environ.get("GITCODE_TOKEN", "")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
RELEASE_FILTER = os.environ.get("RELEASE_FILTER", "")
KEEP_STABLE = int(os.environ.get("KEEP_STABLE", "3"))
MAX_ATTACH = 2 * 1024 * 1024 * 1024  # GitCode 未公布上限，2GB 兜底（GitHub 单附件上限）

GITCODE_API = f"https://api.gitcode.com/api/v5/repos/{GITCODE_OWNER}/{GITCODE_REPO}"
GH_API = f"https://api.github.com/repos/{GH_REPO}"

TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:-rc(\d+))?$")


def parse_tag(tag: str) -> tuple | None:
    m = TAG_RE.match(tag)
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), bool(m.group(4)))


def http_json(url: str, payload: dict | None = None, method: str | None = None,
              headers: dict | None = None, timeout: int = 120):
    hdrs = {"User-Agent": UA}
    if GITHUB_TOKEN and url.startswith(GH_API):
        hdrs["Authorization"] = f"Bearer {GITHUB_TOKEN}"
    if headers:
        hdrs.update(headers)
    if payload is None:
        req = urllib.request.Request(url, headers=hdrs, method=method)
    else:
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode(),
            headers={**hdrs, "Content-Type": "application/json"},
            method=method or "POST",
        )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode()
        if not body:  # 部分接口（如 DELETE）成功时返回空响应体
            return None
        return json.loads(body)


def gc_get(path: str, params: dict | None = None):
    url = f"{GITCODE_API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return http_json(url, headers={"Authorization": f"Bearer {GITCODE_TOKEN}"})


def gc_post(path: str, payload: dict):
    return http_json(
        f"{GITCODE_API}{path}",
        payload,
        headers={"Authorization": f"Bearer {GITCODE_TOKEN}"},
    )


def gh_get(path: str):
    return http_json(f"{GH_API}{path}")


def download(url: str, dest: str) -> bool:
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as f:
                shutil.copyfileobj(resp, f)
            return True
        except Exception as e:
            print(f"    下载失败（第 {attempt + 1} 次）：{e}")
            time.sleep(2 * (attempt + 1))
    return False


def upload(release_tag: str, path: str) -> bool:
    """GitCode 上传附件：GET upload_url 取预签名地址 → PUT 二进制（headers 全带）。"""
    fname = os.path.basename(path)
    for attempt in range(3):
        try:
            up = gc_get(
                f"/releases/{urllib.parse.quote(release_tag)}/upload_url",
                {"file_name": fname},
            )
            if not isinstance(up, dict) or "url" not in up:
                print(f"    获取上传地址失败：{up}")
                time.sleep(2 * (attempt + 1))
                continue
            url = up["url"]
            headers = {k: str(v) for k, v in (up.get("headers") or {}).items()}
            with open(path, "rb") as f:
                data = f.read()
            req = urllib.request.Request(url, data=data, method="PUT", headers=headers)
            with urllib.request.urlopen(req, timeout=1800) as resp:
                resp.read()
            return True
        except Exception as e:
            print(f"    上传失败（第 {attempt + 1} 次）：{e}")
            time.sleep(2 * (attempt + 1))
    return False


def list_gitcode_releases() -> list:
    """GitCode 列表接口；分页参数若被忽略则返回全量，按条数判断退出。"""
    out = []
    page = 1
    while True:
        batch = gc_get("/releases", {"per_page": 100, "page": page})
        if not isinstance(batch, list):
            raise RuntimeError(f"GitCode releases 列表返回异常: {batch}")
        out.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return out


def derive_keep_set(releases: list) -> set:
    """自动推导保留集合：仅最新 KEEP_STABLE 个正式版（不含 RC）。"""
    parsed = [(parse_tag(r["tag_name"]), r["tag_name"]) for r in releases]
    stable = sorted((p for p, _ in parsed if p and not p[3]), reverse=True)
    keep_main = {s[:3] for s in stable[:KEEP_STABLE]}
    return {t for p, t in parsed if p and not p[3] and p[:3] in keep_main}


def main() -> int:
    if not GITCODE_TOKEN:
        print("错误：请通过环境变量提供 GITCODE_TOKEN（GitCode 个人访问令牌）")
        return 1
    print(f"源: GitHub {GH_REPO} -> 目标: GitCode {GITCODE_OWNER}/{GITCODE_REPO}")

    # 验证 GitCode token 与仓库可达，避免把鉴权错误误判成其它问题
    try:
        gc_get("")
    except Exception as e:
        print(f"错误：无法访问 GitCode 仓库 {GITCODE_OWNER}/{GITCODE_REPO}：{e}")
        return 1

    # 拉取 GitHub 全部 releases（分页）
    gh_releases = []
    page = 1
    while True:
        batch = gh_get(f"/releases?per_page=100&page={page}")
        if not isinstance(batch, list):
            raise RuntimeError(f"GitHub releases 列表返回异常: {batch}")
        gh_releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1

    # 保留集合：RELEASE_FILTER 覆盖时用正则，否则自动推导；--all 全量
    if "--all" in sys.argv:
        keep = {r["tag_name"] for r in gh_releases}
        print("同步模式: 全部 Release")
    elif RELEASE_FILTER:
        pattern = re.compile(RELEASE_FILTER)
        keep = {r["tag_name"] for r in gh_releases if pattern.match(r["tag_name"])}
        print(f"版本过滤（正则）: {RELEASE_FILTER}")
    else:
        keep = derive_keep_set(gh_releases)
        print(f"保留策略: 仅最新 {KEEP_STABLE} 个正式版（不含 RC）")
    gh_releases = [r for r in gh_releases if r["tag_name"] in keep]
    print(f"GitHub 上 {len(gh_releases)} 个 Release 需保留")

    # 拉取 GitCode 已有 releases（幂等对比）
    gc_releases = {r["tag_name"]: r for r in list_gitcode_releases()}
    print(f"GitCode 已有 {len(gc_releases)} 个 Release")

    ok = skipped = failed = 0
    tmpdir = tempfile.mkdtemp(prefix="gitcode-sync-")
    try:
        # 按创建时间升序处理，先旧后新
        for rel in sorted(gh_releases, key=lambda r: r["created_at"]):
            tag = rel["tag_name"]
            existing = gc_releases.get(tag)
            attach_names = set()
            if existing is not None:
                # GitCode 列表接口直接带 assets（含平台自动生成的源码包，忽略）
                attach_names = {
                    a.get("name") for a in existing.get("assets", [])
                    if a.get("name", "").endswith(".apk")
                }
                print(f"[跳过创建] {tag}（已存在，APK 附件 {len(attach_names)} 个）")
            else:
                print(f"[创建] {tag} ...")
                try:
                    created = gc_post("/releases", {
                        "tag_name": tag,
                        "name": rel.get("name") or f"Release {tag}",
                        "body": rel.get("body") or "",
                        "target_commitish": "main",
                    })
                    if isinstance(created, dict) and created.get("tag_name"):
                        attach_names = set()
                    else:
                        raise RuntimeError(f"创建返回异常: {created}")
                except Exception as e:
                    print(f"  错误：创建 Release 失败：{e}")
                    failed += 1
                    continue

            for asset in rel.get("assets", []):
                name = asset["name"]
                size = asset["size"]
                if size > MAX_ATTACH:
                    print(f"  [跳过] {name}（{size / 1048576:.1f}MB 超过 {MAX_ATTACH // 1048576}MB 上限）")
                    skipped += 1
                    continue
                if name in attach_names:
                    print(f"  [已存在] {name}")
                    skipped += 1
                    continue
                dest = os.path.join(tmpdir, name)
                print(f"  下载 {name}（{size / 1048576:.1f}MB）...")
                if not download(asset["browser_download_url"], dest):
                    print(f"  [失败] {name} 下载失败")
                    failed += 1
                    continue
                if upload(tag, dest):
                    print(f"  [成功] {name} -> GitCode Release {tag}")
                    ok += 1
                else:
                    print(f"  [失败] {name} 上传失败")
                    failed += 1
                os.remove(dest)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"\n完成：成功 {ok}，跳过 {skipped}，失败 {failed}")
    if failed:
        print("有失败项，重跑本脚本即可续传（已成功的会跳过）。")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
