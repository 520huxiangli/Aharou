#!/usr/bin/env python3
"""NapCat 固定版本资产分发；默认离线 dry-run，只有 --apply 才下载和发布。

CNB_TOKEN 只从环境读取。发布前须自行核实 NapCat、QQ、Debian/proot-distro
及归档内各组件的许可与再分发条件；本脚本不代表已获得再分发许可。
"""

import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys
from types import SimpleNamespace
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
TAG = "napcat-runtime-v4.18.42-qq-53644-pd-4.29.0"
MANIFEST_NAME = "napcat-downloads.json"
CHUNK = 1024 * 1024
UA = "aharou-napcat-assets-sync"
ROOTFS_SHA = {
    "aarch64": "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
    "x86_64": "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
}


class SyncError(Exception):
    pass


def assets():
    result = []
    for arch, digest in ROOTFS_SHA.items():
        name = f"debian-trixie-{arch}-pd-v4.29.0.tar.xz"
        result.append({"name": name, "source":
                       f"https://github.com/termux/proot-distro/releases/download/v4.29.0/{name}",
                       "expected": digest})
    result.append({"name": "NapCat-v4.18.42.Shell.zip", "source":
                   "https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.42/NapCat.Shell.zip"})
    for arch in ("arm64", "amd64"):
        name = f"linuxqq_3.2.34-53644_{arch}.deb"
        result.append({"name": name, "source":
                       f"https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/{name}"})
    return result


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".new")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def request(url, method="GET", headers=None):
    return urllib.request.urlopen(urllib.request.Request(
        url, method=method, headers={"User-Agent": UA, "Accept-Encoding": "identity", **(headers or {})}),
        timeout=120)


def probe(url):
    # Range 验证实际附件，而不是把返回 HTML 的下载页误当成成功。
    with request(url, headers={"Range": "bytes=0-0"}) as response:
        content_range = response.headers.get("Content-Range", "")
        match = re.fullmatch(r"bytes 0-0/(\d+)", content_range)
        if response.status != 206 or not match or len(response.read(2)) != 1:
            raise SyncError("RangeVerificationFailed")
        size = int(match[1])
        if size <= 0:
            raise SyncError("EmptyAsset")
        validator = response.headers.get("ETag") or response.headers.get("Last-Modified")
        return size, validator


def download(item, cache):
    destination = cache / item["name"]
    metadata = destination.with_name(destination.name + ".json")
    source = item["source"]
    mirrors = []
    mirror = os.environ.get("GH_MIRROR", "").strip()
    if mirror and source.startswith("https://github.com/"):
        if not mirror.startswith("https://") or urllib.parse.urlsplit(mirror).query:
            raise SyncError("InvalidMirrorPrefix")
        mirrors.append(mirror.rstrip("/") + "/" + source)
    mirrors.append(source)
    last_error = None
    for transport in mirrors:
        transport_key = hashlib.sha256(transport.encode()).hexdigest()[:16]
        partial = destination.with_name(destination.name + f".{transport_key}.part")
        part_meta = partial.with_name(partial.name + ".json")
        for attempt in range(3):
            try:
                size, validator = probe(transport)
                saved = load_json(metadata)
                if destination.exists():
                    digest = sha256(destination)
                    if (not isinstance(saved, dict) or saved.get("source") != source
                            or saved.get("sizeBytes") != size or destination.stat().st_size != size
                            or saved.get("sha256") != digest
                            or (item.get("expected") and item["expected"] != digest)):
                        raise SyncError("CacheValidationFailed")
                    return destination, digest, size
                state = load_json(part_meta)
                if partial.exists():
                    if (not isinstance(state, dict) or state.get("source") != source
                            or state.get("transport") != transport or state.get("sizeBytes") != size
                            or not validator or state.get("validator") != validator
                            or state.get("partialSize") != partial.stat().st_size
                            or state.get("partialSha256") != sha256(partial)):
                        # 无法证明同源的断点不拼接；保留旧内容供排查。
                        raise SyncError("PartialValidationFailed")
                offset = partial.stat().st_size if partial.exists() else 0
                if offset > size:
                    raise SyncError("PartialSizeInvalid")
                state = {"source": source, "transport": transport, "sizeBytes": size,
                         "validator": validator}
                try:
                    if offset < size:
                        headers = {"Range": f"bytes={offset}-"}
                        if validator:
                            headers["If-Range"] = validator
                        with request(transport, headers=headers) as response:
                            expected = f"bytes {offset}-{size - 1}/{size}"
                            if response.status != 206 or response.headers.get("Content-Range") != expected:
                                raise SyncError("ResumeResponseInvalid")
                            with partial.open("ab") as output:
                                while chunk := response.read(CHUNK):
                                    if output.tell() + len(chunk) > size:
                                        raise SyncError("DownloadSizeExceeded")
                                    output.write(chunk)
                finally:
                    if partial.exists():
                        state.update(partialSize=partial.stat().st_size, partialSha256=sha256(partial))
                        write_json(part_meta, state)
                if partial.stat().st_size != size:
                    raise SyncError("IncompleteDownload")
                digest = sha256(partial)
                if item.get("expected") and digest != item["expected"]:
                    raise SyncError("OfficialChecksumMismatch")
                partial.replace(destination)
                write_json(metadata, {"source": source, "sizeBytes": size, "sha256": digest})
                return destination, digest, size
            except SyncError as error:
                if str(error) in {"CacheValidationFailed", "PartialValidationFailed",
                                  "PartialSizeInvalid", "OfficialChecksumMismatch"}:
                    raise
                last_error = error
                print(f"  下载尝试 {attempt + 1}/3 失败：{safe_error(error)}")
            except Exception as error:
                last_error = error
                print(f"  下载尝试 {attempt + 1}/3 失败：{safe_error(error)}")
        # 镜像和原链各留自己的断点，回退时不混合来源，也不丢掉失败现场。
    raise last_error or SyncError("DownloadFailed")


def safe_error(error):
    if isinstance(error, urllib.error.HTTPError):
        return f"HTTP {error.code}"
    if isinstance(error, SyncError):
        return str(error)
    return type(error).__name__


def load_sync():
    spec = importlib.util.spec_from_file_location("napcat_cnb_sync", ROOT / "scripts/sync-cnb-releases.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    # 复用上传三步协议，不沿用原脚本可能打印预签名 URL 的异常输出和长退避。
    def http_json(url, payload=None, method=None, headers=None, **kwargs):
        data = None if payload is None else json.dumps(payload).encode()
        hdrs = {"User-Agent": UA, "Accept": "application/json", **(headers or {})}
        if data is not None:
            hdrs["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024:
                raise SyncError("ApiResponseTooLarge")
            return json.loads(raw) if raw else None

    module.http_json = http_json
    module.time = SimpleNamespace(sleep=lambda _: None)
    original_post = module.cnb_post

    def safe_post(path, payload):
        if path.endswith("/asset-upload-url"):
            # 检查后仍可能有并发上传；服务端也禁止覆盖，避免竞态破坏同名资产。
            payload = {**payload, "overwrite": False}
        return original_post(path, payload)

    module.cnb_post = safe_post

    def get_release(tag):
        try:
            value = module.cnb_get(f"/-/releases/tags/{urllib.parse.quote(tag, safe='')}")
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise
        if (not isinstance(value, dict) or value.get("tag_name") != tag
                or not value.get("id") or not isinstance(value.get("assets"), list)):
            raise SyncError("ReleaseShapeInvalid")
        return value

    module.get_cnb_release = get_release
    return module


def release_assets(sync, release):
    current = sync.get_cnb_release(TAG)
    if current is None:
        raise SyncError("ReleaseNotFound")
    if str(current["id"]) != str(release["id"]):
        raise SyncError("ReleaseIdentityChanged")
    found = {}
    for asset in current["assets"]:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str) or not asset["name"]:
            raise SyncError("AssetShapeInvalid")
        if asset["name"] in found:
            raise SyncError("DuplicateAssetName")
        found[asset["name"]] = asset
    return found


def public_url(sync, name):
    return (f"https://cnb.cool/{sync.CNB_OWNER}/{sync.CNB_REPO}/-/releases/download/"
            f"{TAG}/{urllib.parse.quote(name, safe='')}")


def remote_digest(url, expected_size):
    digest = hashlib.sha256()
    count = 0
    with request(url) as response:
        if response.status != 200:
            raise SyncError("RemoteDownloadInvalid")
        while chunk := response.read(CHUNK):
            count += len(chunk)
            if count > expected_size:
                raise SyncError("RemoteSizeMismatch")
            digest.update(chunk)
    if count != expected_size:
        raise SyncError("RemoteSizeMismatch")
    return digest.hexdigest()


def publish(sync, release, path, digest, size):
    name = path.name
    url = public_url(sync, name)
    existing = release_assets(sync, release)
    if name in existing:
        if probe(url)[0] != size or remote_digest(url, size) != digest:
            raise SyncError("ExistingAssetContentMismatch")
        print(f"  已存在且摘要一致：{name}")
    else:
        # 原函数会输出网络异常中的 URL；静默复用，失败仅返回固定错误类型。
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            success = sync.upload(str(release["id"]), str(path))
        if not success:
            raise SyncError("UploadFailed")
        if name not in release_assets(sync, release):
            raise SyncError("UploadedAssetNotListed")
        if probe(url)[0] != size or remote_digest(url, size) != digest:
            raise SyncError("UploadedAssetVerificationFailed")
    return url


def publish_napcat_license(sync, release, cache):
    source = "https://raw.githubusercontent.com/NapNeko/NapCatQQ/v4.18.42/LICENSE"
    mirror = os.environ.get("GH_MIRROR", "").strip().rstrip("/")
    transport = f"{mirror}/{source}" if mirror else source
    with request(transport) as response:
        content = response.read(32769)
    if len(content) > 32768 or not content.startswith(b"Limited Redistribution License for NapCat") or b"Mlikiowa" not in content:
        raise SyncError("NapCatLicenseVerificationFailed")
    path = cache / "NapCat-v4.18.42-LICENSE.txt"
    path.write_bytes(content)
    return publish(sync, release, path, sha256(path), path.stat().st_size)


def check_rootfs_constants():
    code = (ROOT / "app/src/main/java/com/aharou/feature/im/data/napcat/NapCatRootfs.kt").read_text()
    match = re.search(r'architecture == "aarch64"\)\s*"([0-9a-f]{64})"\s*else "([0-9a-f]{64})"', code)
    if not match or match.groups() != tuple(ROOTFS_SHA.values()):
        raise SyncError("RootfsSourceChecksumChanged")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="下载、校验并发布；默认只展示计划")
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".aharou/napcat-assets-cache")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/napcat-downloads.json")
    args = parser.parse_args()
    check_rootfs_constants()
    plan = assets()
    print(f"固定发布 tag：{TAG}")
    print("许可提醒：执行发布前须核实各来源及归档内组件的再分发条款。")
    for item in plan:
        print(f"{item['name']}\n  {item['source']}")
    if not args.apply:
        print("dry-run：未联网，未创建缓存、清单或发布资源。使用 --apply 执行。")
        return 0
    if not os.environ.get("CNB_TOKEN"):
        raise SyncError("MissingCnbTokenEnvironment")
    sync = load_sync()
    # 先核实认证，避免既有 helper 将查询失败误判成 Release 不存在。
    sync.cnb_get("/-/releases", {"page": 1, "page_size": 1})
    args.cache_dir = args.cache_dir.expanduser().resolve()
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    downloaded = [(item, *download(item, args.cache_dir)) for item in plan]
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        release = sync.get_cnb_release(TAG) or sync.ensure_release(
            TAG, "NapCat runtime v4.18.42 / QQ 53644 / proot-distro v4.29.0",
            "固定版本运行时附件；来源 URL 与实算摘要见 napcat-downloads.json。\n"
            "NapCatQQ 原作者 Mlikiowa，项目：NapNeko/NapCatQQ，https://github.com/NapNeko/NapCatQQ 。\n"
            "NapCat.Shell.zip 按上游原始字节分发，不移除其中 LICENSE 或版权声明。\n"
            "Debian rootfs 来自 termux/proot-distro：https://github.com/termux/proot-distro 。\n"
            "QQ 为腾讯软件，来源：https://qqdl.gtimg.cn/ 。\n"
            "本发布说明不代表获得腾讯或其他上游的镜像/再分发授权；须核实适用许可与条款。")
    if not release or not release.get("id"):
        raise SyncError("ReleaseUnavailable")
    publish_napcat_license(sync, release, args.cache_dir)
    records = []
    for item, path, digest, size in downloaded:
        print(f"核对并发布：{item['name']}")
        url = publish(sync, release, path, digest, size)
        records.append({"name": item["name"], "source": item["source"], "url": url,
                        "sha256": digest, "sizeBytes": size})
    manifest = {"schemaVersion": 1, "napcatVersion": "v4.18.42", "qqVersion": "3.2.34-53644",
                "rootfsVersion": "v4.29.0", "assets": records}
    # 五个资源全部可匿名读取后才生成清单；先验证发布副本再更新项目文件。
    staged = args.cache_dir / MANIFEST_NAME
    write_json(staged, manifest)
    publish(sync, release, staged, sha256(staged), staged.stat().st_size)
    write_json(args.output.expanduser(), manifest)
    print(f"完成：5 个资产及下载清单已核对；本地清单：{args.output}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"失败：{safe_error(error)}；缓存保留，不删除远端资产。", file=sys.stderr)
        sys.exit(1)
