#!/usr/bin/env python3
"""把高频 Maven 坐标的元数据抓成一份离线索引，传到 cnb 发布仓（幂等可重跑）。

用法：
  CNB_TOKEN=<token> python3 scripts/sync-maven-index.py
  CNB_TOKEN=<token> python3 scripts/sync-maven-index.py --coordinates scripts/maven-index-coordinates.txt
  python3 scripts/sync-maven-index.py --dry-run          # 只抓不发，打印统计

背景：App 的依赖检查要逐个坐标去仓库拉 maven-metadata.xml，而 maven.google.com 在国内不可达，
Google 系坐标（androidx.* / com.google.*）会整批查不到。这份索引把常用坐标的版本列表做成快照，
主源全挂时 App 用它兜底——代价是滞后，所以 App 侧只在网络仓库全部失败后才查它。

可选环境变量：
  CNB_OWNER   cnb 组织，默认 huxiangli
  CNB_REPO    cnb 发布仓，默认 aharou-releases
  CNB_BRANCH  创建 release 时用来建 tag 的分支，默认 main
  SYNC_WORKERS 抓取并发数，默认 6

产物：tag 固定为 maven-index 的 Release 里的 maven-index.json.gz
直链：https://cnb.cool/{owner}/{repo}/-/releases/download/maven-index/maven-index.json.gz （匿名可下）
"""

import concurrent.futures
import gzip
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

UA = "aharou-maven-index"
HERE = os.path.dirname(os.path.abspath(__file__))

CNB_OWNER = os.environ.get("CNB_OWNER") or "huxiangli"
CNB_REPO = os.environ.get("CNB_REPO") or "aharou-releases"
CNB_BRANCH = os.environ.get("CNB_BRANCH") or "main"
CNB_TOKEN = os.environ.get("CNB_TOKEN", "")
WORKERS = int(os.environ.get("SYNC_WORKERS") or "6")

TAG = "maven-index"
RELEASE_NAME = "Aharou 依赖索引"
RELEASE_BODY = (
    "依赖检查用的离线元数据快照（maven-index.json.gz）。"
    "由 scripts/sync-maven-index.py 生成，App 在实时仓库全部不可达时回退到它。"
)
ASSET_NAME = "maven-index.json.gz"
INDEX_VERSION = 1

CNB_API = f"https://api.cnb.cool/{CNB_OWNER}/{CNB_REPO}"

# 抓取候选链：国内可达的镜像优先，官方源排最后（google 的官方源在国内实测连不上）。
REPOSITORIES = [
    "https://maven.aliyun.com/repository/google",
    "https://maven.aliyun.com/repository/central",
    "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
    "https://maven.google.com",
    "https://repo1.maven.org/maven2",
]

HTTP_TIMEOUT = 20


def http_json(url, payload=None, method=None, headers=None, timeout=60, retries=4):
    hdrs = {"User-Agent": UA, "Accept": "application/json"}
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
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                if resp.headers.get("Content-Encoding", "").lower() == "gzip":
                    raw = gzip.decompress(raw)
            body = raw.decode()
            return json.loads(body) if body else None
        except urllib.error.HTTPError as e:
            # 4xx/5xx 是明确的业务响应，重试没意义，直接抛给调用方判断
            raise RuntimeError(f"HTTP {e.code}: {e.read()[:200]!r}") from e
        except Exception as e:
            last = e
            time.sleep(min(15, 2 * (attempt + 1)))
    raise RuntimeError(str(last))


def cnb_get(path, params=None):
    url = f"{CNB_API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return http_json(url, headers={"Authorization": f"Bearer {CNB_TOKEN}"})


def cnb_post(path, payload):
    return http_json(
        f"{CNB_API}{path}", payload or {}, headers={"Authorization": f"Bearer {CNB_TOKEN}"}
    )


def put_stream(url, path, timeout=3600):
    """流式 PUT 上传。Content-Length 显式给出——预签名地址按声明长度校验，不能改 chunked。"""
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


def get_cnb_release(tag):
    try:
        r = cnb_get(f"/-/releases/tags/{urllib.parse.quote(tag)}")
    except Exception:
        return None
    return r if isinstance(r, dict) and r.get("tag_name") else None


def ensure_release(tag):
    existing = get_cnb_release(tag)
    if existing is not None:
        return existing
    try:
        created = cnb_post(
            "/-/releases",
            {
                "tag_name": tag,
                "name": RELEASE_NAME,
                "body": RELEASE_BODY,
                "prerelease": False,
                "target_commitish": CNB_BRANCH,
            },
        )
    except Exception as e:
        if "409" in str(e):
            return get_cnb_release(tag)
        raise RuntimeError(f"创建 Release 失败：{e}") from e
    if isinstance(created, dict) and created.get("tag_name"):
        return created
    return get_cnb_release(tag)


def upload_asset(release_id, path):
    """取预签名地址 → 流式 PUT → 回调确认（少确认那步附件不会出现在列表里）。"""
    name = os.path.basename(path)
    size = os.path.getsize(path)
    for attempt in range(5):
        try:
            slot = cnb_post(
                f"/-/releases/{release_id}/asset-upload-url",
                {"asset_name": name, "size": size, "ttl": 0, "overwrite": True},
            )
            if not isinstance(slot, dict) or not slot.get("upload_url"):
                print(f"  获取上传地址失败：{slot}")
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
            print(f"  上传失败（第 {attempt + 1} 次）：{e}")
            time.sleep(min(30, 3 * (attempt + 1)))
    return False


def load_coordinates(path):
    coords = []
    seen = set()
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            if ":" not in line:
                print(f"  [跳过] 格式不是 group:artifact —— {line}")
                continue
            group, artifact = (p.strip() for p in line.split(":", 1))
            if not group or not artifact:
                print(f"  [跳过] 坐标不完整 —— {line}")
                continue
            key = f"{group}:{artifact}"
            if key in seen:
                continue
            seen.add(key)
            coords.append((group, artifact))
    return coords


def parse_metadata(xml_text):
    """只取 App 侧 MavenMetadataParser 认的三个字段；三个都空视为无效。"""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None
    versioning = root.find("versioning")
    if versioning is None:
        return None
    latest = (versioning.findtext("latest") or "").strip() or None
    release = (versioning.findtext("release") or "").strip() or None
    versions = [
        (v.text or "").strip()
        for v in versioning.findall("./versions/version")
        if (v.text or "").strip()
    ]
    if latest is None and release is None and not versions:
        return None
    entry = {"versions": versions}
    if latest:
        entry["latest"] = latest
    if release:
        entry["release"] = release
    return entry


def fetch_one(group, artifact):
    """逐个仓库试，命中即返回。返回 (group, artifact, entry|None, error|None)。"""
    rel = f"{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    last_error = None
    for repo in REPOSITORIES:
        url = f"{repo.rstrip('/')}/{rel}"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
                text = resp.read().decode("utf-8", errors="replace")
            entry = parse_metadata(text)
            if entry is not None:
                return group, artifact, entry, None
            last_error = f"元数据无有效字段：{repo}"
        except urllib.error.HTTPError as e:
            last_error = f"HTTP {e.code} @ {repo}"
        except Exception as e:
            last_error = f"{type(e).__name__} @ {repo}: {e}"
    return group, artifact, None, last_error


def main():
    coord_path = os.path.join(HERE, "maven-index-coordinates.txt")
    if "--coordinates" in sys.argv:
        coord_path = sys.argv[sys.argv.index("--coordinates") + 1]
    dry_run = "--dry-run" in sys.argv

    if not dry_run and not CNB_TOKEN:
        print("错误：请通过环境变量提供 CNB_TOKEN（cnb 访问令牌）")
        return 1

    coords = load_coordinates(coord_path)
    if not coords:
        print(f"错误：{coord_path} 里没有可用坐标")
        return 1
    print(f"坐标清单：{len(coords)} 条（{coord_path}）")

    entries = {}
    failures = []
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = [pool.submit(fetch_one, g, a) for g, a in coords]
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            group, artifact, entry, error = fut.result()
            if entry is not None:
                entries[f"{group}:{artifact}"] = entry
            else:
                failures.append((f"{group}:{artifact}", error))
            if i % 20 == 0 or i == len(coords):
                print(f"  进度 {i}/{len(coords)}  命中 {len(entries)}  失败 {len(failures)}")

    print(f"\n抓取完成：命中 {len(entries)}，失败 {len(failures)}，用时 {time.time() - started:.1f}s")
    if failures:
        print("失败明细（这些坐标在 App 端兜底不到，可按需从清单里删掉或稍后重跑）：")
        for name, error in failures[:30]:
            print(f"  - {name}: {error}")
        if len(failures) > 30:
            print(f"  ... 另有 {len(failures) - 30} 条")

    if not entries:
        print("错误：一条都没抓到，不覆盖线上索引")
        return 1

    index = {
        "version": INDEX_VERSION,
        "generatedAt": int(time.time() * 1000),
        "coordinates": entries,
    }
    payload = gzip.compress(json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode(), 9)
    tmp = os.path.join("/tmp", ASSET_NAME)
    with open(tmp, "wb") as f:
        f.write(payload)
    print(f"索引：{len(entries)} 条坐标，gzip 后 {len(payload) / 1024:.1f} KB -> {tmp}")

    if dry_run:
        print("[dry-run] 不执行上传")
        return 0

    rel = ensure_release(TAG)
    if not rel:
        print(f"错误：无法创建/获取 {TAG} Release")
        return 1
    print(f"上传到 Release {TAG}（id={rel.get('id')}）...")
    if not upload_asset(str(rel.get("id")), tmp):
        print("错误：上传失败")
        return 1
    print(
        "完成。直链：\n"
        f"  https://cnb.cool/{CNB_OWNER}/{CNB_REPO}/-/releases/download/{TAG}/{ASSET_NAME}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
