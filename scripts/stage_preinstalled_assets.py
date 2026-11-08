#!/usr/bin/env python3
"""从已发布清单暂存预装 Alpine。默认只读计划，--apply 才下载；不覆盖仓库原始镜像。"""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
ABIS = {"arm64": "arm", "x86_64": "x86"}
MARKER = "etc/aharou/preinstalled.json"
REQUIRED = {
    "sh", "bash", "curl", "wget", "jq", "rsync", "git", "rg", "file",
    "less", "netstat", "nc", "nslookup", "node", "npm", "python3",
}
TOOL_DIRS = ("bin", "sbin", "usr/bin", "usr/sbin")


def plan(manifest, image_id="alpine-3.24"):
    entries = [e for e in manifest["images"] if e["id"] == image_id]
    if len(entries) != 1:
        raise ValueError("Expected one built-in Alpine image entry")
    entry = entries[0]
    if entry["distro"] != "alpine" or not entry["version"].startswith("3.24."):
        raise ValueError("Built-in runtime must remain on Alpine 3.24")
    profile = entry.get("preinstalledSources", {}).get("aharou")
    if profile is None:
        return []
    if profile != {"profile": "general", "recipeRevision": "1"}:
        raise ValueError("Unsupported preinstalled image profile")
    base = manifest["sources"]["aharou"]["distros"]["alpine"].rstrip("/")
    template = entry["preinstalledPaths"]["aharou"]
    result = []
    for abi, directory in ABIS.items():
        digest = entry["sourceSha256ByAbi"]["aharou"][abi]
        size = entry["sourceSizeBytesByAbi"]["aharou"][abi]
        if not re.fullmatch(r"[a-fA-F0-9]{64}", digest) or type(size) is not int or size <= 0:
            raise ValueError("Missing derived asset checksum or size")
        relative = template.replace("{abi}", entry["abiNames"][abi])
        if "{" in relative or PurePosixPath(relative).is_absolute() or ".." in PurePosixPath(relative).parts:
            raise ValueError("Invalid derived asset path")
        url = base + "/" + relative
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Invalid derived asset URL")
        result.append({"abi": abi, "directory": directory, "url": url,
                       "sha256": digest.lower(), "sizeBytes": size, "version": entry["version"]})
    if result[0]["url"] == result[1]["url"]:
        raise ValueError("Architecture-specific assets must have distinct URLs")
    return result


def validate_archive(path, abi):
    names = set()
    marker = None
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            name = str(PurePosixPath(member.name))
            parts = PurePosixPath(name).parts
            if PurePosixPath(name).is_absolute() or ".." in parts:
                raise ValueError("Unsafe archive path")
            if member.isfile() or member.issym() or member.islnk():
                names.add(name)
            if name in {".installed", ".installed_custom", ".provisioned"}:
                raise ValueError("Archive contains application installation state")
            if name == MARKER:
                if marker is not None or not member.isfile() or member.size > 16384:
                    raise ValueError("Invalid preinstalled marker")
                with archive.extractfile(member) as stream:
                    marker = json.loads(stream.read())
    if not isinstance(marker, dict) or any(marker.get(key) != value for key, value in {
        "schemaVersion": 1, "profile": "general", "distro": "alpine", "abi": abi, "recipeRevision": "1"
    }.items()):
        raise ValueError("Archive preinstalled marker mismatch")
    missing = [tool for tool in REQUIRED if not any(f"{folder}/{tool}" in names for folder in TOOL_DIRS)]
    if missing:
        raise ValueError("Preinstalled archive is missing required tools: " + ", ".join(sorted(missing)))


def download(record, destination):
    request = urllib.request.Request(record["url"], headers={"Accept-Encoding": "identity"})
    digest = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("xb") as stream:
        if response.status != 200:
            raise ValueError("Unexpected image HTTP status")
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            count += len(chunk)
            if count > record["sizeBytes"]:
                raise ValueError("Image exceeds declared size")
            digest.update(chunk)
            stream.write(chunk)
    if count != record["sizeBytes"] or digest.hexdigest() != record["sha256"]:
        raise ValueError("Downloaded image checksum or size mismatch")
    validate_archive(destination, record["abi"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--manifest", type=Path, default=ROOT / "app/src/main/assets/container-images.json")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/preinstalled-assets")
    args = parser.parse_args(argv)
    records = plan(json.loads(args.manifest.read_text()))
    if not records:
        print("清单尚无已发布的预装 Alpine，保留仓库现有基础镜像；不声称已预装。")
        return 0
    for record in records:
        print(f"{record['abi']}: {record['url']} ({record['sizeBytes']} bytes)")
    if not args.apply:
        print("计划已核对，未下载或修改资产。")
        return 0
    output = args.output.resolve()
    if "\n" in str(output) or "\r" in str(output):
        raise ValueError("Invalid output path")
    if output.exists():
        raise ValueError("Output already exists; choose a fresh staging directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".preinstalled-", dir=output.parent))
    try:
        for record in records:
            target = work / record["directory"] / "container" / record["directory"] / "alpine-rootfs.bin"
            target.parent.mkdir(parents=True)
            download(record, target)
        (work / "verified.json").write_text(json.dumps(records, indent=2) + "\n")
        work.rename(output)
    finally:
        if work.exists():
            shutil.rmtree(work)
    github_env = os.environ.get("GITHUB_ENV")
    if github_env:
        with open(github_env, "a", encoding="utf-8") as stream:
            stream.write(f"AHAROU_PREINSTALLED_ASSETS={output}\n")
    print(f"双架构已校验：{output}；本地构建时设置 AHAROU_PREINSTALLED_ASSETS 为此目录。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"预装资产准备失败：{error}", file=sys.stderr)
        sys.exit(1)
