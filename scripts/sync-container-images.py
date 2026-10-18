#!/usr/bin/env python3
"""容器 rootfs 自动同步：上游发现新版本 → 官方校验和 → 上传 cnb → 改写两份清单。

用法：
  python3 scripts/sync-container-images.py                  # dry-run：只打印差异（默认）
  python3 scripts/sync-container-images.py --distro alpine   # 只检查某一类（alpine|ubuntu|debian）
  CNB_TOKEN=<token> python3 scripts/sync-container-images.py --apply   # 真下载 + 校验 + 上传 + 改清单

环境变量：
  CNB_TOKEN   cnb 访问令牌；--apply 必填。dry-run 可省，省略时跳过「cnb 是否已有同名资产」查重
  CNB_OWNER   cnb 组织，默认 huxiangli
  CNB_REPO    cnb 发布仓，默认 aharou-releases
  CNB_TAG     资产所在 Release 的 tag，默认 container-images

上游发现路径（2026-10-18 逐个实测可用）：
  alpine  {base}/alpine/v<series>/releases/<abi>/        取最大 alpine-minirootfs-<ver>-<abi>.tar.gz（排除 _rcN）
  ubuntu  {base}/ubuntu-base/releases/<series>/release/  取最大 ubuntu-base-<ver>-base-<abi>.tar.gz
  debian  {base}/job/tianon/job/debuerreotype/job/<abi>/lastSuccessfulBuild/
          artifact/<suite>/rootfs.debian_version 给出版本号，rootfs.tar.xz 是产物
  base 官方在前、国内镜像兜底；三类发行版在官方与镜像上的相对路径一致（实测过）。

清单约定（动手改之前先读）：
  - `sizeBytes` 取 **arm64** 那份产物的字节数（现有 12 条的实测值全部等于 arm64 产物），不是两份之和
  - `path` 与 `paths.*` 里的 {abi} 由 abiNames 替换（alpine aarch64 / ubuntu arm64+amd64 /
    debian arm64v8+amd64）；cnb 上的资产名就是 `paths.aharou` 替换后的字符串
  - 两份清单（assets 内置 + data 远端）必须逐字节一致，本脚本改完会自行比对
"""

import argparse
import hashlib
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
from concurrent.futures import ThreadPoolExecutor

UA = "aharou-container-sync"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFESTS = [
    os.path.join(ROOT, "app/src/main/assets/container-images.json"),
    os.path.join(ROOT, "data/container-images.json"),
]
ABIS = ("arm64", "x86_64")
DISTROS = ("alpine", "ubuntu", "debian")
CNB_OWNER = os.environ.get("CNB_OWNER") or "huxiangli"
CNB_REPO = os.environ.get("CNB_REPO") or "aharou-releases"
CNB_TAG = os.environ.get("CNB_TAG") or "container-images"
CNB_TOKEN = os.environ.get("CNB_TOKEN", "")
CNB_API = f"https://api.cnb.cool/{CNB_OWNER}/{CNB_REPO}"

# 官方在前：镜像的更新时间晚于官方，把镜像放前面会在发版当天「看不到刚出的版本」
DISCOVERY_BASES = {
    "alpine": [
        "https://dl-cdn.alpinelinux.org",
        "https://mirrors.aliyun.com",
        "https://repo.huaweicloud.com",
        "https://mirrors.cloud.tencent.com",
    ],
    "ubuntu": [
        "https://cdimage.ubuntu.com",
        "https://mirrors.aliyun.com/ubuntu-cdimage",
        "https://mirrors.huaweicloud.com/ubuntu-cdimage",
    ],
    "debian": ["https://doi-janky.infosiftr.net"],
}

HREF_RE = re.compile(r'href="([^"]+)"')


# ---------------------------------------------------------------- 网络基础

def http_text(url: str, timeout: int = 30, retries: int = 3) -> str:
    """取文本（目录列表 / 校验文件）。跨境链路偶发超时或半截响应，退避重试。"""
    hdrs = {"User-Agent": UA, "Accept": "*/*", "Accept-Encoding": "identity"}
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=hdrs)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001 — 网络层各种断法都要重试（实测有过整片 DNS 解析失败的时段）
            last = e
            if attempt + 1 < retries:
                time.sleep(1.0 * (attempt + 1))
    raise RuntimeError(f"请求失败：{last}")


def http_size(url: str, timeout: int = 30) -> int:
    """只取远端字节数（HEAD）。个别镜像不支持 HEAD，退化成 Range GET 读 Content-Range。"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA}, method="HEAD")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            n = resp.headers.get("Content-Length")
            if n:
                return int(n)
    except Exception:  # noqa: BLE001 — 退到 Range 路线，见下
        pass
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        m = re.search(r"/(\d+)\s*$", resp.headers.get("Content-Range") or "")
        if m:
            return int(m.group(1))
    raise RuntimeError("服务器既不给 Content-Length 也不给 Content-Range")


def dir_entries(url: str) -> set:
    """目录列表里的文件名集合。只保留文件名，过滤掉子目录与上级链接。"""
    out = set()
    for h in HREF_RE.findall(http_text(url)):
        h = urllib.parse.unquote(h.split("?")[0].split("#")[0])
        if not h or "/" in h:
            continue
        out.add(h)
    return out


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: str) -> bool:
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=600) as resp, open(dest, "wb") as f:
                shutil.copyfileobj(resp, f)
            return True
        except Exception as e:  # noqa: BLE001
            print(f"    下载失败（第 {attempt + 1} 次）：{e}")
            time.sleep(2 * (attempt + 1))
    return False


# ---------------------------------------------------------------- 版本工具

def version_tuple(v: str) -> tuple:
    m = re.match(r"^\s*(\d+(?:\.\d+)*)", v)
    if not m:
        raise ValueError(f"无法解析版本号：{v!r}")
    return tuple(int(x) for x in m.group(1).split("."))


def is_newer(a: str, b: str) -> bool:
    """a 是否比 b 新。点分段数不等时缺位补 0（26.10 vs 26.10.1 才可比）。"""
    ta, tb = version_tuple(a), version_tuple(b)
    n = max(len(ta), len(tb))
    return ta + (0,) * (n - len(ta)) > tb + (0,) * (n - len(tb))


def series_of(entry: dict) -> str:
    """条目 id 里的分支号：alpine-3.24 → 3.24；ubuntu-24.04 → 24.04。"""
    return entry["id"].split("-", 1)[1]


def suite_of(entry: dict) -> str:
    """debian 版本串带套件名：`12.15 (bookworm)` → bookworm。"""
    m = re.search(r"\(([^)]+)\)", entry.get("version", ""))
    if not m:
        raise RuntimeError("版本字段缺少 (套件名) 后缀，无法定位 debian 套件目录")
    return m.group(1)


def debian_version_str(debian_version: str, suite: str) -> str:
    return f"{debian_version} ({suite})"


def path_for(entry: dict, version: str) -> str:
    """按清单现有约定生成上游相对路径；{abi} 留给替换方处理。"""
    d = entry["distro"]
    if d == "alpine":
        return f"alpine/v{series_of(entry)}/releases/{{abi}}/alpine-minirootfs-{version}-{{abi}}.tar.gz"
    if d == "ubuntu":
        return f"ubuntu-base/releases/{series_of(entry)}/release/ubuntu-base-{version}-base-{{abi}}.tar.gz"
    if d == "debian":
        return ("job/tianon/job/debuerreotype/job/{abi}/lastSuccessfulBuild/artifact/"
                f"{suite_of(entry)}/rootfs.tar.xz")
    raise RuntimeError(f"未知发行版：{d}")


def asset_name(entry: dict, abi: str) -> str:
    """cnb 上的资产名（= paths.aharou 替换 {abi}）。"""
    return entry["paths"]["aharou"].replace("{abi}", entry["abiNames"][abi])


# ---------------------------------------------------------------- 上游探测

def discover(entry: dict, base: str) -> dict:
    """在指定 base 上探测该条目当前上游版本，返回 {version, urls, extra}；探测不通抛异常。"""
    d = entry["distro"]
    arm_name = entry["abiNames"]["arm64"]
    if d == "alpine":
        series = series_of(entry)
        pat = re.compile(rf"^alpine-minirootfs-(\d+\.\d+\.\d+)-{re.escape(arm_name)}\.tar\.gz$")
        vers = sorted({m.group(1) for f in dir_entries(f"{base}/alpine/v{series}/releases/{arm_name}/")
                       if (m := pat.match(f))}, key=version_tuple)
        if not vers:
            raise RuntimeError(f"目录里没有 alpine-minirootfs-*-{arm_name}.tar.gz")
        ver = vers[-1]
        urls = {abi: f"{base}/alpine/v{series}/releases/{entry['abiNames'][abi]}/"
                     f"alpine-minirootfs-{ver}-{entry['abiNames'][abi]}.tar.gz" for abi in ABIS}
        return {"version": ver, "urls": urls, "extra": f"候选 {len(vers)} 个"}

    if d == "ubuntu":
        series = series_of(entry)
        pat = re.compile(rf"^ubuntu-base-({re.escape(series)}(?:\.\d+)?)-base-{arm_name}\.tar\.gz$")
        vers = sorted({m.group(1) for f in dir_entries(f"{base}/ubuntu-base/releases/{series}/release/")
                       if (m := pat.match(f))}, key=version_tuple)
        if not vers:
            raise RuntimeError(f"目录里没有 ubuntu-base-{series}*-base-{arm_name}.tar.gz")
        ver = vers[-1]
        urls = {abi: f"{base}/ubuntu-base/releases/{series}/release/"
                     f"ubuntu-base-{ver}-base-{entry['abiNames'][abi]}.tar.gz" for abi in ABIS}
        return {"version": ver, "urls": urls, "extra": f"候选 {len(vers)} 个"}

    if d == "debian":
        suite = suite_of(entry)
        job = f"{base}/job/tianon/job/debuerreotype/job/{arm_name}/lastSuccessfulBuild"
        meta = json.loads(http_text(f"{job}/api/json?tree=number,timestamp,artifacts%5BrelativePath%5D"))
        rels = {a.get("relativePath") for a in (meta.get("artifacts") or [])}
        if f"{suite}/rootfs.tar.xz" not in rels:
            raise RuntimeError(f"本次构建里没有 {suite}/rootfs.tar.xz（套件名写错或该套件已停更）")
        # 版本号只在 arm 那份构建里取一次：同一轮的 amd64/arm64v8 构建共享同一个 debian_version
        ver = http_text(f"{job}/artifact/{suite}/rootfs.debian_version").strip()
        if not re.match(r"^\d+(\.\d+)*$", ver):
            raise RuntimeError(f"debian_version 内容异常：{ver!r}")
        urls = {abi: f"{base}/job/tianon/job/debuerreotype/job/{entry['abiNames'][abi]}"
                     f"/lastSuccessfulBuild/artifact/{suite}/rootfs.tar.xz" for abi in ABIS}
        return {"version": debian_version_str(ver, suite), "urls": urls,
                "extra": f"build #{meta.get('number')}"}

    raise RuntimeError(f"未知发行版：{d}")


def report_unlisted_series(data: dict) -> None:
    """提醒「上游有、清单里没有」的发行版分支。

    新分支要不要收录（版本号怎么起、保留几代）是产品决策，脚本不擅自加，
    只打一行让人定。探测失败静默跳过——网络抖动不该把整趟跑挂。
    """
    known = {(e.get("distro"), series_of(e)) for e in (data.get("images") or [])}
    hints: list = []
    try:
        for f in dir_entries("https://dl-cdn.alpinelinux.org/alpine/"):
            m = re.match(r"^v(\d+\.\d+)/$", f)
            if m and ("alpine", m.group(1)) not in known:
                hints.append(f"Alpine {m.group(1)}")
    except Exception:  # noqa: BLE001
        pass
    try:
        for f in dir_entries("https://cdimage.ubuntu.com/ubuntu-base/releases/"):
            m = re.match(r"^(\d+\.\d+)/$", f)
            if m and ("ubuntu", m.group(1)) not in known:
                hints.append(f"Ubuntu {m.group(1)}")
    except Exception:  # noqa: BLE001
        pass
    if hints:
        print("\n上游有、清单里没有的分支（是否收录你定）：" + "、".join(sorted(set(hints))))


def probe_versions(entry: dict) -> tuple:
    """只探版本（1~2 个请求），返回 (info, error)。「无更新」这条便宜路径上不碰产物体积。"""
    errs = []
    for base in DISCOVERY_BASES[entry["distro"]]:
        try:
            info = discover(entry, base)
        except Exception as e:  # noqa: BLE001 — 换个 base 继续试
            errs.append(f"{urllib.parse.urlsplit(base).netloc} → {e}")
            continue
        info["base"] = base
        return info, None
    return None, "；".join(errs)


def probe_artifacts(entry: dict) -> tuple:
    """确认真有新版本后才跑：HEAD 出两个 ABI 产物的字节数（不下载产物本体）。"""
    errs = []
    for base in DISCOVERY_BASES[entry["distro"]]:
        try:
            info = discover(entry, base)
            sizes = {abi: http_size(info["urls"][abi]) for abi in ABIS}
        except Exception as e:  # noqa: BLE001
            errs.append(f"{urllib.parse.urlsplit(base).netloc} → {e}")
            continue
        info["base"] = base
        info["sizes"] = sizes
        return info, None
    return None, "；".join(errs)


def fetch_checksums(entry: dict, info: dict) -> dict:
    """取各 ABI 的官方 SHA256，缺哪份返回 None（调用方在报告里注明「未校验」）。

    三类发行版给校验的形式不同：alpine 是每个产物一个 .sha256，ubuntu 是目录里的 SHA256SUMS，
    debian 是产物旁的 .sha256（内容只有一串裸哈希）。
    """
    d = entry["distro"]
    base, ver = info["base"], info["version"]
    out = {abi: None for abi in ABIS}
    if d == "alpine":
        series = series_of(entry)
        for abi in ABIS:
            n = entry["abiNames"][abi]
            txt = http_text(f"{base}/alpine/v{series}/releases/{n}/"
                            f"alpine-minirootfs-{ver}-{n}.tar.gz.sha256")
            out[abi] = txt.split()[0].lower()
        return out
    if d == "ubuntu":
        series = series_of(entry)
        table = {}
        for line in http_text(f"{base}/ubuntu-base/releases/{series}/release/SHA256SUMS").splitlines():
            parts = line.split()
            if len(parts) >= 2:
                table[parts[-1].lstrip("*")] = parts[0].lower()
        for abi in ABIS:
            out[abi] = table.get(f"ubuntu-base-{ver}-base-{entry['abiNames'][abi]}")
        return out
    if d == "debian":
        suite = suite_of(entry)
        for abi in ABIS:
            txt = http_text(f"{base}/job/tianon/job/debuerreotype/job/{entry['abiNames'][abi]}"
                            f"/lastSuccessfulBuild/artifact/{suite}/rootfs.tar.xz.sha256")
            out[abi] = txt.split()[0].lower()
        return out
    raise RuntimeError(f"未知发行版：{d}")


# ---------------------------------------------------------------- cnb 侧

def http_json(url: str, payload: dict = None, method: str = None,
              headers: dict = None, timeout: int = 300, retries: int = 4):
    hdrs = {"User-Agent": UA, "Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if payload is None:
        req = urllib.request.Request(url, headers=hdrs, method=method)
    else:
        req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                     headers={**hdrs, "Content-Type": "application/json"},
                                     method=method or "POST")
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
            if not raw:
                return None
            return json.loads(raw.decode())
        except urllib.error.HTTPError:
            raise  # 4xx/5xx 交给调用方判（如 409 已存在），重试没意义
        except Exception as e:  # noqa: BLE001
            last = e
            if attempt + 1 < retries:
                print(f"    请求失败（第 {attempt + 1} 次）：{e}，重试中...")
                time.sleep(2 * (attempt + 1))
    raise last


def cnb_get(path: str):
    return http_json(f"{CNB_API}{path}", headers={"Authorization": f"Bearer {CNB_TOKEN}"})


def cnb_post(path: str, payload: dict):
    return http_json(f"{CNB_API}{path}", payload or {},
                     headers={"Authorization": f"Bearer {CNB_TOKEN}"})


def put_stream(url: str, path: str, timeout: int = 3600):
    """流式 PUT：1MB 分块发，不把整包读进内存。

    用 http.client 而不是 urllib：urllib 的 body 只能是 bytes，等价于全量读进内存。
    Content-Length 必须显式给——预签名地址按声明长度校验，不接受 chunked。
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
    """取预签名地址 → 流式 PUT → 回调确认。三步缺一不可，少确认那步附件不进列表。"""
    name = os.path.basename(path)
    size = os.path.getsize(path)
    for attempt in range(5):
        try:
            slot = cnb_post(f"/-/releases/{release_id}/asset-upload-url",
                            {"asset_name": name, "size": size, "ttl": 0, "overwrite": True})
            if not isinstance(slot, dict) or not slot.get("upload_url"):
                print(f"    获取上传地址失败：{slot}")
                time.sleep(min(30, 3 * (attempt + 1)))
                continue
            put_stream(slot["upload_url"], path)
            http_json(slot["verify_url"], payload={},
                      headers={"Authorization": f"Bearer {CNB_TOKEN}"})
            return True
        except Exception as e:  # noqa: BLE001 — 跨境链路上单次 PUT 容易半途断
            print(f"    上传失败（第 {attempt + 1} 次）：{e}")
            time.sleep(min(30, 3 * (attempt + 1)))
    return False


def get_release():
    """取 tag 对应的 Release（含附件列表），不存在返回 None。"""
    try:
        r = cnb_get(f"/-/releases/tags/{urllib.parse.quote(CNB_TAG)}")
    except Exception:  # noqa: BLE001 — 404 与网络错误都按「拿不到」处理，由调用方决定
        return None
    return r if isinstance(r, dict) and r.get("tag_name") else None


def ensure_release():
    """没有 container-images 这个 tag 就建一个空 Release（资产靠后续上传填）。"""
    existing = get_release()
    if existing is not None:
        return existing
    try:
        created = cnb_post("/-/releases", {
            "tag_name": CNB_TAG,
            "name": "容器镜像 rootfs",
            "body": "App「Aharou 自建源」使用的容器 rootfs 包（由 scripts/sync-container-images.py 维护）。",
            "prerelease": False,
            "target_commitish": "main",
        })
        if isinstance(created, dict) and created.get("tag_name"):
            return created
    except Exception as e:  # noqa: BLE001
        print(f"  错误：创建 Release 失败：{e}")
    return get_release()


# ---------------------------------------------------------------- 清单读写

def load_manifests():
    """读两份清单；内容不一致直接拒跑——蔓延不一致状态比不同步更糟。"""
    raws = []
    for p in MANIFESTS:
        if not os.path.exists(p):
            print(f"错误：清单不存在：{p}")
            return None
        with open(p, encoding="utf-8") as f:
            raws.append(f.read())
    if raws[0] != raws[1]:
        print("错误：两份清单内容不一致（" + " / ".join(os.path.relpath(p, ROOT) for p in MANIFESTS) + "）")
        print("      先人工对齐再跑本脚本，避免把差异继续扩散。")
        return None
    try:
        return json.loads(raws[0])
    except Exception as e:  # noqa: BLE001
        print(f"错误：清单 JSON 解析失败：{e}")
        return None


def save_manifests(data: dict) -> bool:
    """两份一起写（先临时文件再 rename），写完逐字节比对。"""
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    for p in MANIFESTS:
        tmp = p + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, p)
    after = []
    for p in MANIFESTS:
        with open(p, encoding="utf-8") as f:
            after.append(f.read())
    if after[0] != after[1]:
        print("错误：写完后两份清单仍不一致，请人工检查")
        return False
    return True


# ---------------------------------------------------------------- 主流程

def parse_args():
    p = argparse.ArgumentParser(
        description="容器 rootfs 同步：探测上游新版本 → 官方 SHA256 校验 → 上传 cnb → 更新两份清单。",
        epilog="缺省是 dry-run，只打印差异；加 --apply 才会真下载（每份 4~40MB）、上传并改写清单。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--apply", action="store_true",
                   help="真下载 + 校验 + 上传 cnb + 改写清单（需要 CNB_TOKEN）")
    p.add_argument("--distro", choices=DISTROS, help="只处理某一类发行版；缺省三类都查")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    data = load_manifests()
    if data is None:
        return 1
    images = data.get("images") or []
    targets = [e for e in images if not args.distro or e.get("distro") == args.distro]
    mode = "apply" if args.apply else "dry-run"
    print(f"模式：{mode}｜清单 {len(images)} 条，本次检查 {len(targets)} 条"
          + (f"（仅 {args.distro}）" if args.distro else ""))

    changes, failures = [], []
    todo = []
    for entry in targets:  # 先做纯本地的模板自检，坏条目不必浪费请求
        eid = entry.get("id", "?")
        try:
            expected = path_for(entry, entry["version"])
        except Exception as e:  # noqa: BLE001
            print(f"[{eid}] 清单字段不可用：{e}")
            failures.append((eid, str(e)))
            continue
        # 模板自检：脚本生成的路径必须与清单现值完全一致，否则说明目录约定被手改过
        if expected != entry["path"]:
            msg = f"清单 path 与脚本模板不符（现 {entry['path']} / 期望 {expected}）"
            print(f"[{eid}] {msg}")
            failures.append((eid, msg))
            continue
        todo.append(entry)

    # 各条镜像的探测互不依赖，而 urllib 单请求在此环境要 1~2s，串行 12 条要 40s+；
    # 并行后「全部已最新」这条常用路径能压到几秒。
    probed = []
    if todo:
        with ThreadPoolExecutor(max_workers=min(8, len(todo))) as ex:
            probed = list(ex.map(probe_versions, todo))

    for entry, (info, err) in zip(todo, probed):
        eid = entry["id"]
        if err:
            print(f"[{eid}] 探测失败：{err}")
            failures.append((eid, f"探测失败：{err}"))
            continue
        if not is_newer(info["version"], entry["version"]):
            print(f"[{eid}] 已最新 {entry['version']}（上游 {info['version']}）")
            continue

        # 有新版本才去 HEAD 产物体积（这一步只为报体积与后续幂等判断，不下载）
        info, err = probe_artifacts(entry)
        if err:
            print(f"[{eid}] 取产物体积失败：{err}")
            failures.append((eid, f"取产物体积失败：{err}"))
            continue
        print(f"[{eid}] {entry['version']} → {info['version']}"
              f"（arm64 {info['sizes']['arm64'] / 1048576:.1f}MB / "
              f"x86_64 {info['sizes']['x86_64'] / 1048576:.1f}MB，"
              f"{urllib.parse.urlsplit(info['base']).netloc}，{info['extra']}）")
        changes.append({"entry": entry, "info": info})

    # 无论有没有新版都提一句：上游新分支不该被静默漏掉
    report_unlisted_series(data)

    if not changes:
        print("\n全部已最新，无需同步。")
        if failures:
            print(f"另有 {len(failures)} 条探测/自检失败，见上。")
            return 1
        return 0

    print(f"\n发现 {len(changes)} 条待更新：")
    for c in changes:
        entry, info = c["entry"], c["info"]
        print(f"  {entry['id']}  {entry['version']} → {info['version']}")
        for abi in ABIS:
            print(f"    [{entry['abiNames'][abi]}] {info['urls'][abi]}")
            print(f"          → cnb 资产 {asset_name(entry, abi)}（{info['sizes'][abi]} 字节）")

    if not args.apply:
        print(f"\ndry-run 结束：{len(changes)} 条待更新，未下载未上传。"
              f"（加 --apply 执行；清单 sizeBytes 取 arm64 产物字节数）")
        if failures:
            print(f"另有 {len(failures)} 条探测/自检失败，见上——失败项不会被同步。")
            return 1
        return 0

    if not CNB_TOKEN:
        print("\n错误：--apply 需要环境变量 CNB_TOKEN")
        return 1

    applied, failed = apply_changes(changes)
    if applied and not save_manifests(data):
        return 1
    if applied:
        print(f"\n清单已更新（两份一致）：{', '.join(c['entry']['id'] for c in applied)}")
        print("清单： " + " / ".join(os.path.relpath(p, ROOT) for p in MANIFESTS))
    if failed:
        print(f"\n完成但有失败：成功 {len(applied)} 条，失败 {len(failed)} 条 → {'；'.join(failed)}")
        return 1
    print(f"\n完成：成功 {len(applied)} 条，失败 0 条")
    return 0


def apply_changes(changes: list) -> tuple:
    """下载 + 校验 + 上传。返回 (成功列表, 失败描述列表)。"""
    rel = ensure_release()
    if rel is None:
        print(f"错误：无法获取 cnb Release（tag={CNB_TAG}），检查 CNB_TOKEN 与 {CNB_OWNER}/{CNB_REPO}")
        return [], ["cnb Release 不可用"]
    release_id = str(rel["id"])
    # 同名资产策略：名与体积都一致 → 跳过（省掉一次几百 MB 上传，重跑本就该是幂等的）；
    # 同名但体积不同 → 覆盖上传（清单里 paths.aharou 是固定名，改名会让 App 端 404）。
    assets = {a.get("name"): a.get("size") for a in (rel.get("assets") or []) if a.get("name")}

    applied, failed = [], []
    for c in changes:
        entry, info = c["entry"], c["info"]
        eid = entry["id"]
        print(f"\n[{eid}] {entry['version']} → {info['version']}")
        names = {abi: asset_name(entry, abi) for abi in ABIS}
        if all(assets.get(names[abi]) == info["sizes"][abi] for abi in ABIS):
            print("  cnb 上两个 ABI 的同名资产都在且体积一致，跳过下载与上传")
            applied.append(c)
            continue

        hashes = {}
        try:
            hashes = fetch_checksums(entry, info)
        except Exception as e:  # noqa: BLE001 — 官方没给校验文件不该阻塞上传，但要打出来
            print(f"  [警告] 取官方 SHA256 失败：{e}（本次不做校验）")

        tmpdir = tempfile.mkdtemp(prefix=f"cimg-{eid}-")
        ok = True
        try:
            for abi in ABIS:
                name = names[abi]
                dest = os.path.join(tmpdir, name)
                if assets.get(name) == info["sizes"][abi]:
                    print(f"  [已存在] {name}（体积一致，跳过）")
                    continue
                print(f"  下载 {name}（{info['sizes'][abi] / 1048576:.1f}MB）...")
                started = time.time()
                if not download(info["urls"][abi], dest):
                    print(f"  [失败] {name} 下载失败：{info['urls'][abi]}")
                    ok = False
                    break
                got_size = os.path.getsize(dest)
                if got_size != info["sizes"][abi]:
                    print(f"  [失败] {name} 下载不完整：{got_size} != {info['sizes'][abi]}")
                    ok = False
                    break
                if hashes.get(abi):
                    got = sha256_file(dest)
                    if got != hashes[abi]:
                        print(f"  [失败] {name} SHA256 不符：官方 {hashes[abi]} / 实际 {got}")
                        ok = False
                        break
                    print(f"  校验通过（SHA256 {got[:16]}…，{time.time() - started:.0f}s）")
                else:
                    print("  [警告] 上游未提供该产物的 SHA256，跳过校验")
                if assets.get(name) is not None:
                    print(f"  覆盖上传 {name}（cnb 现有 {assets[name]} 字节）")
                else:
                    print(f"  上传 {name} ...")
                if not upload(release_id, dest):
                    print(f"  [失败] {name} 上传失败")
                    ok = False
                    break
                print(f"  [成功] {name} → cnb {CNB_TAG}")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

        if ok:
            applied.append(c)
        else:
            failed.append(f"{eid}（{entry['version']} → {info['version']}）")

    for c in applied:  # 清单只对「两个 ABI 都到位」的条目改动
        e, info = c["entry"], c["info"]
        e["version"] = info["version"]
        e["path"] = path_for(e, info["version"])
        e["sizeBytes"] = info["sizes"]["arm64"]  # 与现有 12 条一致：取 arm64 产物
    return applied, failed


if __name__ == "__main__":
    sys.exit(main())