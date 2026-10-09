#!/usr/bin/env python3
"""把 GitHub Release 的 APK 资产同步到 cnb 发布仓（幂等可重跑）。

用法：
  CNB_TOKEN=<token> python3 scripts/sync-cnb-releases.py            # 同步保留集合
  CNB_TOKEN=<token> python3 scripts/sync-cnb-releases.py --all      # 同步 GitHub 全部 Release
  CNB_TOKEN=<token> python3 scripts/sync-cnb-releases.py --voice-models   # 只同步离线语音模型包
  CNB_TOKEN=<token> RELEASE_FILTER='^container-images$' GH_MIRROR=https://gh-proxy.com/ \
      python3 scripts/sync-cnb-releases.py            # 只同步容器镜像 rootfs（App 的「Aharou 自建源」，tag 固定为 container-images）

保留集合（未设 RELEASE_FILTER 时自动推导）：
  仅最新 KEEP_STABLE 个正式版（默认 3），与 GitCode 那边保持一致。

可选环境变量：
  GH_REPO         GitHub 仓库，默认 520huxiangli/Aharou
  GITHUB_TOKEN    GitHub 令牌（可选，提高 API 限额）
  GH_MIRROR       GitHub 文件镜像前缀（如 https://gh-proxy.com/），国内机器拉资产时用；失败回退原链
  CNB_OWNER       cnb 组织，默认 huxiangli
  CNB_REPO        cnb 发布仓，默认 aharou-releases
  CNB_BRANCH      创建 release 时用来建 tag 的分支，默认 main（cnb 仓只需一个占位分支）
  RELEASE_FILTER  Python 正则，仅同步匹配 tag_name 的 Release
  ASSET_FILTER    Python 正则，仅同步匹配文件名的资产
  KEEP_STABLE     保留的正式版数量，默认 3

与 GitCode 版的差异（cnb OpenAPI 实测确认）：
  - 认证：Authorization: Bearer <token>；**所有 OpenAPI 都要令牌**（匿名一律 401），
    所以 App 侧只能用「release 附件下载直链」（那个匿名可下），不能拿 API 当检查更新源
  - 创建 Release：POST /{repo}/-/releases，tag 不存在时按 target_commitish 自动建 tag
  - 上传附件是两步：POST /releases/{id}/asset-upload-url 拿预签名地址 → PUT 上传 →
    POST verify_url 确认（少一步确认附件不会出现在列表里）
  - 附件下载直链形如 https://cnb.cool/{owner}/{repo}/-/releases/download/{tag}/{file}
"""

import gzip
import http.client
import json
import os
import re
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "aharou-cnb-sync"

# 环境变量一律用 `or` 兑默认值：CI 里 `${{ vars.X }}` 未定义时会传空串，
# `os.environ.get(k, default)` 会拿到 "" 而不是 default，拼出的 API 路径直接变空 owner。
GH_REPO = os.environ.get("GH_REPO") or "520huxiangli/Aharou"
GH_MIRROR = os.environ.get("GH_MIRROR", "")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
CNB_OWNER = os.environ.get("CNB_OWNER") or "huxiangli"
CNB_REPO = os.environ.get("CNB_REPO") or "aharou-releases"
CNB_BRANCH = os.environ.get("CNB_BRANCH") or "main"
CNB_TOKEN = os.environ.get("CNB_TOKEN", "")
RELEASE_FILTER = os.environ.get("RELEASE_FILTER", "")
ASSET_FILTER = os.environ.get("ASSET_FILTER", "")
KEEP_STABLE = int(os.environ.get("KEEP_STABLE", "3"))
MAX_ATTACH = 2 * 1024 * 1024 * 1024  # 2GB 兜底（GitHub 单附件上限）

GH_API = f"https://api.github.com/repos/{GH_REPO}"
CNB_API = f"https://api.cnb.cool/{CNB_OWNER}/{CNB_REPO}"

TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:-rc(\d+))?$")

# 离线语音模型包（在 k2-fsa/sherpa-onnx 的 Release 里，不随本项目发布）。
# 放进一个固定 tag 的 Release（tag 名不是版本号，与发版无关），App 端按固定 URL 取；
# 模型只在换版本时变，日常发版不会碰这个 tag，所以这个模式只在需要时手动跑。
VOICE_MODEL_TAG = "voice-models"
VOICE_MODEL_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
VOICE_MODELS = [
    (f"{VOICE_MODEL_BASE}/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2",
     "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2"),
    (f"{VOICE_MODEL_BASE}/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
     "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"),
    (f"{VOICE_MODEL_BASE}/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
     "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"),
]


def parse_tag(tag: str) -> tuple | None:
    m = TAG_RE.match(tag)
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), bool(m.group(4)))


def http_json(url: str, payload: dict | None = None, method: str | None = None,
              headers: dict | None = None, timeout: int = 300, retries: int = 4):
    """发一次 JSON 请求；网络类异常（响应被截断、连接重置）按退避重试。

    4xx/5xx 这类 HTTP 状态错误不重试，直接抛出去交给调用方判断（如 409 已存在）。
    """
    hdrs = {"User-Agent": UA, "Accept": "application/json"}
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
            if not body:  # 部分接口成功时返回空响应体
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


def cnb_get(path: str, params: dict | None = None):
    url = f"{CNB_API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return http_json(url, headers={"Authorization": f"Bearer {CNB_TOKEN}"})


def cnb_post(path: str, payload: dict):
    return http_json(f"{CNB_API}{path}", payload or {}, headers={"Authorization": f"Bearer {CNB_TOKEN}"})


def gh_get(path: str):
    return http_json(f"{GH_API}{path}")


def download(url: str, dest: str) -> bool:
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=600) as resp, open(dest, "wb") as f:
                shutil.copyfileobj(resp, f)
            return True
        except Exception as e:
            print(f"    下载失败（第 {attempt + 1} 次）：{e}")
            time.sleep(2 * (attempt + 1))
    return False


def put_stream(url: str, path: str, timeout: int = 3600):
    """流式 PUT：按 1MB 分块发送，不把整包读进内存（安装包动辄几百 MB，容器内存吃不住）。

    用 http.client 而不是 urllib：urllib 只接受 bytes 形式的 body，等价于全量读进内存。
    Content-Length 显式给出——预签名地址的签名按声明长度校验，不能改成 chunked。
    """
    parts = urllib.parse.urlsplit(url)
    conn = http.client.HTTPSConnection(parts.netloc, timeout=timeout)
    try:
        target = parts.path + (("?" + parts.query) if parts.query else "")
        conn.putrequest("PUT", target, skip_host=False, skip_accept_encoding=True)
        conn.putheader("Content-Type", "application/octet-stream")
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


def upload(release_id: str, path: str) -> bool:
    """上传附件：取预签名地址 → 流式 PUT → 回调确认。三步缺一不可，少确认那步附件不会进列表。

    跨境链路上几百 MB 的单次 PUT 容易半途断，重试给到 5 次、退避到 30s。
    """
    name = os.path.basename(path)
    size = os.path.getsize(path)
    for attempt in range(5):
        try:
            slot = cnb_post(
                f"/-/releases/{release_id}/asset-upload-url",
                {"asset_name": name, "size": size, "ttl": 0, "overwrite": True},
            )
            if not isinstance(slot, dict) or not slot.get("upload_url"):
                print(f"    获取上传地址失败：{slot}")
                time.sleep(min(30, 3 * (attempt + 1)))
                continue
            put_stream(slot["upload_url"], path)
            http_json(
                slot["verify_url"],
                payload={},
                headers={"Authorization": f"Bearer {CNB_TOKEN}"},
            )
            return True
        except Exception as e:
            print(f"    上传失败（第 {attempt + 1} 次）：{e}")
            time.sleep(min(30, 3 * (attempt + 1)))
    return False


def get_cnb_release(tag: str):
    """按 tag 查单个 Release；不存在返回 None。

    逐个 tag 查而不是「列全部再查表」：列表接口有分页与延迟，漏掉的条目会被当成不存在
    而导致重复创建。
    """
    try:
        r = cnb_get(f"/-/releases/tags/{urllib.parse.quote(tag)}")
    except Exception:
        return None
    return r if isinstance(r, dict) and r.get("tag_name") else None


def asset_names(release: dict) -> set:
    return {a.get("name") for a in (release.get("assets") or []) if a.get("name")}


def derive_keep_set(releases: list) -> set:
    """自动推导保留集合：仅最新 KEEP_STABLE 个正式版（不含 RC）。"""
    parsed = [(parse_tag(r["tag_name"]), r["tag_name"]) for r in releases]
    stable = sorted((p for p, _ in parsed if p and not p[3]), reverse=True)
    keep_main = {s[:3] for s in stable[:KEEP_STABLE]}
    return {t for p, t in parsed if p and not p[3] and p[:3] in keep_main}


def ensure_release(tag: str, name: str, body: str = "", prerelease: bool = False) -> dict | None:
    """取 tag 对应的 Release，不存在就建一个（cnb 会按 target_commitish 自动建 tag）。"""
    existing = get_cnb_release(tag)
    if existing is not None:
        return existing
    try:
        created = cnb_post("/-/releases", {
            "tag_name": tag,
            "name": name,
            "body": body,
            "prerelease": prerelease,
            "target_commitish": CNB_BRANCH,
        })
    except Exception as e:
        if "409" in str(e):
            # 查询有延迟时会走到这：其实已存在，按已存在继续
            return get_cnb_release(tag)
        print(f"  错误：创建 Release 失败：{e}")
        return None
    if isinstance(created, dict) and created.get("tag_name"):
        return created
    return get_cnb_release(tag)


def fetch_to(url: str, dest: str) -> bool:
    """先试镜像、再回退原链。国内直连 GitHub 资产实测几乎下不动（20s 拉不到 1MB），
    镜像能到 4.7MB/s，所以有 GH_MIRROR 就先走它。"""
    if GH_MIRROR and download(GH_MIRROR + url, dest):
        return True
    return download(url, dest)


def sync_voice_models() -> int:
    """把三份离线语音模型包传到固定 tag 的 Release（已存在同名附件则跳过）。"""
    print(f"语音模型同步：k2-fsa/sherpa-onnx -> cnb {CNB_OWNER}/{CNB_REPO}（tag={VOICE_MODEL_TAG}）")
    rel = ensure_release(
        VOICE_MODEL_TAG,
        "离线语音模型",
        "App 运行时下载的离线语音模型包（唤醒 / 流式识别 / 整段识别）。",
    )
    if not rel:
        print("错误：无法创建/获取 voice-models Release")
        return 1
    release_id = str(rel.get("id"))
    have = {a.get("name") for a in (rel.get("assets") or []) if a.get("name")}

    ok = skipped = failed = 0
    tmpdir = tempfile.mkdtemp(prefix="cnb-voice-")
    try:
        for url, name in VOICE_MODELS:
            if name in have:
                print(f"  [已存在] {name}")
                skipped += 1
                continue
            dest = os.path.join(tmpdir, name)
            print(f"  下载 {name} ...")
            if not fetch_to(url, dest):
                print(f"  [失败] {name} 下载失败")
                failed += 1
                continue
            size = os.path.getsize(dest)
            print(f"  上传 {name}（{size / 1048576:.1f}MB）...")
            if upload(release_id, dest):
                print(f"  [成功] {name} -> cnb Release {VOICE_MODEL_TAG}")
                ok += 1
            else:
                print(f"  [失败] {name} 上传失败")
                failed += 1
            os.remove(dest)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"\n完成：成功 {ok}，跳过 {skipped}，失败 {failed}")
    return 1 if failed else 0


def main() -> int:
    if not CNB_TOKEN:
        print("错误：请通过环境变量提供 CNB_TOKEN（cnb 访问令牌）")
        return 1
    if "--voice-models" in sys.argv:
        return sync_voice_models()
    print(f"源: GitHub {GH_REPO} -> 目标: cnb {CNB_OWNER}/{CNB_REPO}")

    # 先验证令牌与仓库可达，避免把鉴权错误误判成别的问题
    try:
        cnb_get("/-/releases", {"page": 1, "page_size": 1})
    except Exception as e:
        print(f"错误：无法访问 cnb 发布仓 {CNB_OWNER}/{CNB_REPO}：{e}")
        return 1

    gh_releases = []
    page = 1
    while True:
        batch = gh_get(f"/releases?per_page=30&page={page}")
        if not isinstance(batch, list):
            raise RuntimeError(f"GitHub releases 列表返回异常: {batch}")
        gh_releases.extend(batch)
        if len(batch) < 30:
            break
        page += 1

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

    ok = skipped = failed = 0
    tmpdir = tempfile.mkdtemp(prefix="cnb-sync-")
    try:
        for rel in sorted(gh_releases, key=lambda r: r["created_at"]):
            tag = rel["tag_name"]
            existing = get_cnb_release(tag)
            names = set()
            if existing is not None:
                names = asset_names(existing)
                print(f"[跳过创建] {tag}（已存在，附件 {len(names)} 个）")
                release_id = str(existing.get("id"))
            else:
                print(f"[创建] {tag} ...")
                try:
                    created = cnb_post("/-/releases", {
                        "tag_name": tag,
                        "name": rel.get("name") or f"Release {tag}",
                        "body": rel.get("body") or "",
                        "prerelease": bool(rel.get("prerelease")),
                        "target_commitish": CNB_BRANCH,
                    })
                except Exception as e:
                    if "409" in str(e):
                        # 查询有延迟时会走到这：其实已存在，按已存在继续传资产
                        print("  创建返回 409（实际已存在），按已存在继续")
                        created = get_cnb_release(tag)
                    else:
                        print(f"  错误：创建 Release 失败：{e}")
                        failed += 1
                        continue
                if isinstance(created, dict) and created.get("tag_name"):
                    names = asset_names(created)
                    release_id = str(created.get("id"))
                else:
                    print(f"  错误：创建后仍查不到 Release（{created}）")
                    failed += 1
                    continue

            for asset in rel.get("assets", []):
                name = asset["name"]
                if ASSET_FILTER and not re.search(ASSET_FILTER, name):
                    continue
                size = asset.get("size") or 0
                if size > MAX_ATTACH:
                    print(f"  [跳过] {name}（超过 {MAX_ATTACH // 1048576}MB 上限）")
                    skipped += 1
                    continue
                if name in names:
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
                if upload(release_id, dest):
                    print(f"  [成功] {name} -> cnb Release {tag}")
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
