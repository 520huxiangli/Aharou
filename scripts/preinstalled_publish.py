"""预装镜像发布；构建与网络动作只从显式 apply 入口执行。"""

import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import urllib.parse
import urllib.request

ABIS = ("arm64", "x86_64")
SUPPORTED_DISTROS = ("alpine", "ubuntu", "debian")


class PublishError(RuntimeError):
    pass


def recipe_revision():
    from preinstalled_rootfs import RECIPE_REVISION
    return str(RECIPE_REVISION)


def file_digest(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_build(source, destination, distro, abi, profile):
    from preinstalled_rootfs import build_rootfs
    result = build_rootfs(source, destination, distro, abi, profile)
    if (result.get("profile") != profile or result.get("recipeRevision") != recipe_revision()
            or result.get("sha256") != file_digest(destination)
            or result.get("sizeBytes") != destination.stat().st_size
            or result["sizeBytes"] <= 0):
        raise PublishError("PreinstalledBuildMetadataMismatch")
    return result


def content_key(records):
    raw = json.dumps(records, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def safe_name(value):
    return re.sub(r"[^A-Za-z0-9._-]+", "-", str(value)).strip("-")


def clear_preinstalled_source(entry, source):
    for key in ("preinstalledPaths", "sourceSha256ByAbi", "sourceSizeBytesByAbi", "preinstalledSources"):
        values = entry.get(key)
        if isinstance(values, dict):
            values.pop(source, None)
            if not values:
                entry.pop(key, None)


def current_preinstalled(entry, info, revision):
    metadata = (entry.get("preinstalledSources") or {}).get("aharou")
    return (entry["version"] == info["version"]
            and metadata == {"profile": "general", "recipeRevision": revision}
            and bool((entry.get("preinstalledPaths") or {}).get("aharou"))
            and all((entry.get("sourceSha256ByAbi") or {}).get("aharou", {}).get(abi)
                    and (entry.get("sourceSizeBytesByAbi") or {}).get("aharou", {}).get(abi)
                    for abi in ABIS))


def verify_remote(sync, url, digest, size):
    if sync.http_size(url) != size:
        raise PublishError("RemoteSizeMismatch")
    request = urllib.request.Request(url, headers={"User-Agent": sync.UA, "Accept-Encoding": "identity"})
    actual = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(request, timeout=600) as response:
        if response.status != 200:
            raise PublishError("RemoteDownloadInvalid")
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            count += len(chunk)
            if count > size:
                raise PublishError("RemoteSizeMismatch")
            actual.update(chunk)
    if count != size or actual.hexdigest() != digest:
        raise PublishError("RemoteContentMismatch")


def publish_general(sync, release, path, metadata):
    name = path.name
    url = (f"https://cnb.cool/{sync.CNB_OWNER}/{sync.CNB_REPO}/-/releases/download/"
           f"{urllib.parse.quote(sync.CNB_TAG, safe='')}/{urllib.parse.quote(name, safe='')}")
    existing = {asset.get("name") for asset in release.get("assets", [])}
    if name not in existing:
        # 旧上传器可能把预签名地址带进异常文本，发布侧只输出固定失败原因。
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            success = sync.upload(str(release["id"]), str(path), overwrite=False)
        if not success:
            raise PublishError("PreinstalledUploadFailed")
    verify_remote(sync, url, metadata["sha256"], metadata["sizeBytes"])


def publish_general_entry(sync, entry, info, hashes, release, work):
    revision = recipe_revision()
    built = {}
    for abi in ABIS:
        source = work / f"upstream-{abi}"
        if not sync.download(info["urls"][abi], str(source)):
            raise PublishError("UpstreamDownloadFailed")
        if source.stat().st_size != info["sizes"][abi] or file_digest(source) != hashes[abi]:
            raise PublishError("UpstreamChecksumMismatch")
        destination = work / f"preinstalled-{abi}.tar.gz"
        built[abi] = checked_build(source, destination, entry["distro"], abi, "general")
    identity = content_key({"version": info["version"], "recipeRevision": revision, "artifacts": built})
    template = (f"{safe_name(entry['id'])}-{safe_name(info['version'])}-general-r{safe_name(revision)}-"
                f"{identity}-{{abi}}.tar.gz")
    for abi in ABIS:
        destination = work / template.replace("{abi}", entry["abiNames"][abi])
        (work / f"preinstalled-{abi}.tar.gz").rename(destination)
        publish_general(sync, release, destination, built[abi])
    # 双架构远端字节全部验证后才替换条目，失败不会留下半套元数据。
    updated = copy.deepcopy(entry)
    updated.setdefault("preinstalledPaths", {})["aharou"] = template
    updated.setdefault("sourceSha256ByAbi", {})["aharou"] = {abi: built[abi]["sha256"] for abi in ABIS}
    updated.setdefault("sourceSizeBytesByAbi", {})["aharou"] = {abi: built[abi]["sizeBytes"] for abi in ABIS}
    updated.setdefault("preinstalledSources", {})["aharou"] = {"profile": "general", "recipeRevision": revision}
    entry.clear()
    entry.update(updated)


def published_base(sync, data, entry):
    hashes = entry.get("sha256ByAbi") or {}
    if any(not hashes.get(abi) for abi in ABIS):
        # Debian 旧清单未固定摘要，只在官方仍是同版本且原包字节匹配时接受。
        official, error = sync.probe_versions(entry)
        if error or not official or official["version"] != entry["version"]:
            raise PublishError("PublishedBaseChecksumUnavailable")
        hashes = sync.fetch_checksums(entry, official)
    if any(not re.fullmatch(r"[0-9a-f]{64}", hashes.get(abi) or "") for abi in ABIS):
        raise PublishError("OfficialChecksumMissing")
    base = data["sources"]["aharou"]["distros"][entry["distro"]].rstrip("/")
    template = entry["paths"]["aharou"]
    urls = {abi: f"{base}/{template.replace('{abi}', entry['abiNames'][abi])}" for abi in ABIS}
    sizes = {abi: sync.http_size(urls[abi]) for abi in ABIS}
    return {"version": entry["version"], "urls": urls, "sizes": sizes}, hashes


def run_general(sync, args, data, targets):
    targets = [entry for entry in targets if entry.get("distro") in SUPPORTED_DISTROS]
    if not targets:
        print("没有支持预装的镜像条目；仅支持 alpine、ubuntu、debian。")
        return 1
    if not args.apply:
        for entry in targets:
            print(f"[{entry['id']}] general 双架构预装计划")
        print("dry-run：未联网、未构建、未上传、未改清单；使用 --preinstall --apply 执行。")
        return 0
    if not sync.CNB_TOKEN:
        print("错误：--preinstall --apply 需要 CNB_TOKEN")
        return 1
    revision = recipe_revision()
    cache = Path.home() / ".aharou/container-images-build"
    cache.mkdir(parents=True, exist_ok=True)
    changed, failed = [], []
    release = None
    for entry in targets:
        try:
            info, hashes = published_base(sync, data, entry)
            if current_preinstalled(entry, info, revision):
                print(f"[{entry['id']}] 已发布基线与预装配方均未变")
                continue
            release = release or sync.ensure_release()
            if not release or not release.get("id"):
                raise PublishError("ReleaseUnavailable")
            with tempfile.TemporaryDirectory(prefix=f"{safe_name(entry['id'])}-", dir=cache) as temporary:
                publish_general_entry(sync, entry, info, hashes, release, Path(temporary))
            changed.append(entry["id"])
        except Exception as error:
            failed.append(entry["id"])
            reason = str(error) if isinstance(error, PublishError) else type(error).__name__
            print(f"[{entry['id']}] 预装发布失败：{reason}；该条目未更新")
    if changed and not sync.save_manifests(data):
        return 1
    print(f"预装发布完成：更新 {len(changed)} 条，失败 {len(failed)} 条")
    return 1 if failed else 0


def slim_rootfs(src_tar, dest_tar, distro="", mirrorlist_text=""):
    work = tempfile.mkdtemp(prefix="slim-rootfs-")
    try:
        with tarfile.open(src_tar, "r:gz") as archive:
            archive.extractall(work)
        for relative in ("boot", "usr/lib/firmware", "usr/lib/modules", "usr/share/locale",
                         "usr/share/man", "usr/share/doc", "usr/share/info", "usr/share/i18n"):
            shutil.rmtree(os.path.join(work, relative), ignore_errors=True)
        if distro == "manjaro":
            mirrorlist = Path(work) / "etc/pacman.d/mirrorlist"
            mirrorlist.parent.mkdir(parents=True, exist_ok=True)
            mirrorlist.write_text(mirrorlist_text, encoding="utf-8")
        # Android 的 link2symlink 可能产生循环，不能把这些链接再放进归档。
        for root, dirs, files in os.walk(work):
            for name in files:
                if ".l2s." in name:
                    os.unlink(os.path.join(root, name))
        with tarfile.open(dest_tar, "w:gz") as archive:
            for name in sorted(os.listdir(work)):
                archive.add(os.path.join(work, name), arcname=name, recursive=True)
    finally:
        shutil.rmtree(work, ignore_errors=True)
