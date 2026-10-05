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
  ASSET_FILTER    Python 正则，仅同步匹配文件名的资产（如 `universal|mapping`）；
                  不设则同步该 Release 的全部资产
  KEEP_STABLE     保留的正式版数量，默认 3

与 Gitee 版的关键差异（GitCode API v5 实测确认）：
  - 认证：Authorization: Bearer <token>，不支持 access_token query 之外的方式也行（见代码）
  - 创建 Release：tag 不存在时自动在 target_commitish(master) 上创建，无需等待镜像同步；
    同 tag 已存在返回 409（幂等复用）
  - 上传附件：先 GET /releases/{tag}/upload_url?file_name= 取预签名地址，
    再 PUT 二进制（响应 headers 必须全部带上，参与 OBS 签名校验）
  - prerelease 标记：API 创建/更新均不生效（网页端才有），GitHub 的 prerelease 状态无法同步
  - 删除 Release：无独立 API；DELETE /tags/{tag} 会连带删除 release（副作用大），
    且 GitCode 未公布附件配额限制，故本脚本不做自动清理
"""

import gzip
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "aharou-gitcode-sync"

GH_REPO = os.environ.get("GH_REPO", "520huxiangli/Aharou")
# 可选：GitHub 文件镜像前缀（如 https://gh-proxy.com/），国内机器同步 GitHub 资产时
# 直连仅 ~0.03MB/s、走镜像 4~5MB/s，差两个数量级；镜像失败会自动回退原链。
GH_MIRROR = os.environ.get("GH_MIRROR", "")
GITCODE_OWNER = os.environ.get("GITCODE_OWNER") or GH_REPO.split("/")[0]
GITCODE_REPO = os.environ.get("GITCODE_REPO") or GH_REPO.split("/")[1]
GITCODE_TOKEN = os.environ.get("GITCODE_TOKEN", "")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
RELEASE_FILTER = os.environ.get("RELEASE_FILTER", "")
ASSET_FILTER = os.environ.get("ASSET_FILTER", "")
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
              headers: dict | None = None, timeout: int = 120, retries: int = 4):
    """发一次 JSON 请求；网络类异常（响应被截断、连接重置）按退避重试。

    国内直连 api.github.com 实测会被中途掐断（IncompleteRead），一次失败不代表真失败；
    4xx/5xx 这类 HTTP 状态错误不重试，直接抛出去交给调用方判断（如 Release 的 409）。
    """
    hdrs = {"User-Agent": UA}
    # 国内直连 api.github.com 时大响应会被中途截断（实测 ~600KB 就断），压缩后字节数少一个量级，明显稳。
    hdrs.setdefault("Accept-Encoding", "gzip")
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
    last_error = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                if resp.headers.get("Content-Encoding", "").lower() == "gzip":
                    raw = gzip.decompress(raw)
            body = raw.decode()
            if not body:  # 部分接口（如 DELETE）成功时返回空响应体
                return None
            return json.loads(body)
        except urllib.error.HTTPError:
            raise
        except Exception as e:  # noqa: BLE001 — 网络层各种断法都要重试
            last_error = e
            if attempt + 1 < retries:
                print(f"    请求失败（第 {attempt + 1} 次）：{e}，重试中...")
                time.sleep(2 * (attempt + 1))
    raise last_error


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


def put_stream(url: str, path: str, headers: dict, timeout: int = 3600):
    """流式 PUT：按 1MB 分块发送，不把整包读进内存（安装包动辄 500MB，容器内存吃不住）。

    用 http.client 而不是 urllib：urllib 只接受 bytes 形式的 body，等价于全量读进内存。
    Content-Length 显式给出——预签名 URL 的 OBS 签名按声明长度校验，不能改成 chunked。
    """
    parts = urllib.parse.urlsplit(url)
    conn = http.client.HTTPSConnection(parts.netloc, timeout=timeout)
    try:
        target = parts.path + (("?" + parts.query) if parts.query else "")
        conn.putrequest("PUT", target, skip_host=False, skip_accept_encoding=True)
        for k, v in headers.items():
            conn.putheader(k, str(v))
        conn.putheader("Content-Length", str(os.path.getsize(path)))
        conn.endheaders()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                conn.send(chunk)
        resp = conn.getresponse()
        body = resp.read()
        if resp.status not in (200, 201, 204):
            raise RuntimeError(f"HTTP {resp.status}: {body[:200]!r}")
    finally:
        conn.close()


def upload(release_tag: str, path: str) -> bool:
    """GitCode 上传附件：GET upload_url 取预签名地址 → 流式 PUT（headers 全带）。

    跨境链路上 200MB+ 的单次 PUT 很容易半途断（实测 EOF/timeout），所以重试次数
    给到 5 次、退避到 30s。"""
    fname = os.path.basename(path)
    for attempt in range(5):
        try:
            up = gc_get(
                f"/releases/{urllib.parse.quote(release_tag)}/upload_url",
                {"file_name": fname},
            )
            if not isinstance(up, dict) or "url" not in up:
                print(f"    获取上传地址失败：{up}")
                time.sleep(min(30, 3 * (attempt + 1)))
                continue
            headers = {k: str(v) for k, v in (up.get("headers") or {}).items()}
            put_stream(up["url"], path, headers)
            return True
        except Exception as e:
            print(f"    上传失败（第 {attempt + 1} 次）：{e}")
            time.sleep(min(30, 3 * (attempt + 1)))
    return False


def get_gitcode_release(tag: str):
    """按 tag 查单个 Release；不存在返回 None。

    不用「列出全部再查表」：实测列表接口会漏条目（同一时刻返回 37 条、实际 52 条），
    漏掉的条目会被当成「不存在」→ 重复创建 → 409 Conflict。逐个 tag 查最可靠。
    """
    try:
        r = gc_get(f"/releases/tags/{urllib.parse.quote(tag)}")
    except Exception:
        return None
    return r if isinstance(r, dict) and r.get("tag_name") else None


def non_source_asset_names(release: dict) -> set:
    """Release 里的非源码附件名（GitCode 会自带 .zip/.tar.gz 源码包，它们不算已同步的资产）。"""
    return {
        a.get("name") for a in (release.get("assets") or [])
        if a.get("name") and a.get("type") != "source"
    }


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
        batch = gh_get(f"/releases?per_page=30&page={page}")
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

    # 存在性逐个 tag 查（列表接口会漏条目，不可依赖）
    ok = skipped = failed = 0
    tmpdir = tempfile.mkdtemp(prefix="gitcode-sync-")
    try:
        # 按创建时间升序处理，先旧后新
        for rel in sorted(gh_releases, key=lambda r: r["created_at"]):
            tag = rel["tag_name"]
            existing = get_gitcode_release(tag)
            attach_names = set()
            if existing is not None:
                attach_names = non_source_asset_names(existing)
                print(f"[跳过创建] {tag}（已存在，非源码附件 {len(attach_names)} 个）")
            else:
                print(f"[创建] {tag} ...")
                created = None
                try:
                    created = gc_post("/releases", {
                        "tag_name": tag,
                        "name": rel.get("name") or f"Release {tag}",
                        "body": rel.get("body") or "",
                        "target_commitish": "master",
                    })
                except Exception as e:
                    if "409" in str(e):
                        # 列表/查询有延迟时会走到这：其实已存在，按已存在继续传资产，别整个跳过
                        print("  创建返回 409（实际已存在），按已存在继续")
                        created = get_gitcode_release(tag)
                    else:
                        print(f"  错误：创建 Release 失败：{e}")
                        failed += 1
                        continue
                if isinstance(created, dict) and created.get("tag_name"):
                    attach_names = non_source_asset_names(created)
                else:
                    print(f"  错误：创建后仍查不到 Release（{created}）")
                    failed += 1
                    continue

            for asset in rel.get("assets", []):
                name = asset["name"]
                if ASSET_FILTER and not re.search(ASSET_FILTER, name):
                    continue
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
                url = asset["browser_download_url"]
                print(f"  下载 {name}（{size / 1048576:.1f}MB）...")
                downloaded = bool(GH_MIRROR) and download(GH_MIRROR + url, dest)
                if not downloaded:
                    downloaded = download(url, dest)
                if not downloaded:
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
