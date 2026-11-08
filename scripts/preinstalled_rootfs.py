#!/usr/bin/env python3
"""将调用方已校验来源与摘要的 rootfs 在 Docker 中预装，不在宿主解包。"""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid

RECIPE_REVISION = "1"
PLATFORMS = {"arm64": "linux/arm64", "x86_64": "linux/amd64"}
MARKER = "etc/aharou/preinstalled.json"
HOST_MARKERS = {".installed", ".installed_custom", ".provisioned"}
PREINSTALL_SCRIPT = Path(__file__).resolve().parent / "rootfs" / "preinstall.sh"


def _path(name: str) -> str:
    if not name or name.startswith("/") or "\\" in name or "\x00" in name:
        raise ValueError(f"归档路径不安全：{name!r}")
    parts = PurePosixPath(name).parts
    if ".." in parts:
        raise ValueError(f"归档路径越界：{name!r}")
    return "/".join(parts)


def _link_target(name: str, target: str, hard: bool) -> str:
    if not target or "\\" in target or "\x00" in target:
        raise ValueError(f"归档链接不安全：{name!r}")
    parts = [] if hard or target.startswith("/") else name.split("/")[:-1]
    for part in target.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                raise ValueError(f"归档链接越界：{name!r} -> {target!r}")
            parts.pop()
        else:
            parts.append(part)
    return "/".join(parts)


def _members(archive: tarfile.TarFile, allow_wrapper: bool) -> list:
    entries = []
    for item in archive.getmembers():
        name = _path(item.name)
        if not name:
            if not item.isdir():
                raise ValueError("归档根节点不是目录")
            continue
        entries.append((item, name))
    if not entries:
        raise ValueError("rootfs 归档为空")
    roots = {name.split("/")[0] for _, name in entries}
    wrapper = ""
    if allow_wrapper and len(roots) == 1:
        candidate = next(iter(roots))
        if re.fullmatch(r"debian-trixie-(aarch64|x86_64)", candidate):
            wrapper = candidate + "/"
    result = []
    by_name = {}
    for original, name in entries:
        if wrapper:
            if name == wrapper[:-1]:
                if not original.isdir():
                    raise ValueError("外包根节点不是目录")
                continue
            name = name[len(wrapper):]
        if not (original.isfile() or original.isdir() or original.issym() or original.islnk()):
            # /dev 由 Docker 与客户端运行时挂载；归档内设备节点不应落到宿主或分发包。
            if name.startswith("dev/") and (original.ischr() or original.isblk() or original.isfifo()):
                continue
            raise ValueError(f"归档含不支持的特殊文件：{name}")
        item = copy.copy(original)
        item.name = name
        item.pax_headers = {key: value for key, value in original.pax_headers.items()
                            if key not in ("path", "linkpath")}
        if item.islnk():
            target = _path(item.linkname)
            if wrapper:
                if not target.startswith(wrapper):
                    raise ValueError(f"硬链接指向外包目录之外：{name}")
                target = target[len(wrapper):]
            item.linkname = target
        if name in by_name:
            raise ValueError(f"归档路径重复：{name}")
        by_name[name] = item
        result.append((original, item))
    for _, item in result:
        parents = PurePosixPath(item.name).parents
        for parent in parents:
            ancestor = by_name.get(str(parent))
            if ancestor is not None and not ancestor.isdir():
                raise ValueError(f"归档成员穿过非目录节点：{item.name}")
        if item.issym() or item.islnk():
            target = _link_target(item.name, item.linkname, item.islnk())
            if item.islnk() and (target not in by_name or not by_name[target].isfile()):
                raise ValueError(f"硬链接目标不是普通文件：{item.name}")
            if item.issym():
                pending = item.linkname.split("/")
                resolved = [] if item.linkname.startswith("/") else item.name.split("/")[:-1]
                followed = 0
                while pending:
                    part = pending.pop(0)
                    if part in ("", "."):
                        continue
                    if part == "..":
                        if not resolved:
                            raise ValueError(f"归档链接链越界：{item.name}")
                        resolved.pop()
                        continue
                    resolved.append(part)
                    linked = by_name.get("/".join(resolved))
                    if linked is not None and linked.issym():
                        followed += 1
                        if followed > 40:
                            raise ValueError(f"归档链接循环或过深：{item.name}")
                        resolved.pop()
                        if linked.linkname.startswith("/"):
                            resolved = []
                        pending = linked.linkname.split("/") + pending
    if "etc" not in by_name or not by_name["etc"].isdir():
        raise ValueError("归档缺少 rootfs 根目录 etc")
    return result


def _rewrite(source: Path, destination: Path, mode: str, allow_wrapper: bool) -> None:
    with tarfile.open(source, "r:*") as archive:
        entries = _members(archive, allow_wrapper)
        with tarfile.open(destination, mode, format=tarfile.PAX_FORMAT) as output:
            for original, item in entries:
                if item.name.split("/")[0] in HOST_MARKERS or item.name in (".dockerenv", ".dockerinit"):
                    continue
                if original.isfile():
                    with archive.extractfile(original) as content:
                        output.addfile(item, content)
                else:
                    output.addfile(item)


def _verify_marker(archive_path: Path, expected: dict) -> None:
    with tarfile.open(archive_path, "r:*") as archive:
        entries = _members(archive, False)
        marker = next((original for original, item in entries if item.name == MARKER), None)
        if marker is None or not marker.isfile() or marker.size > 4096:
            raise ValueError("预装结果缺少有效标记")
        with archive.extractfile(marker) as content:
            if json.load(content) != expected:
                raise ValueError("预装标记与本次构建参数不一致")
        if any(item.name.split("/")[0] in HOST_MARKERS for _, item in entries):
            raise ValueError("预装结果包含宿主初始化标记")


def build_rootfs(source: Path, destination: Path, distro: str, abi: str, profile: str) -> dict:
    if distro not in ("alpine", "ubuntu", "debian") or abi not in PLATFORMS:
        raise ValueError("仅支持 alpine/ubuntu/debian 与 arm64/x86_64")
    if profile not in ("general", "napcat") or (profile == "napcat" and distro != "debian"):
        raise ValueError("general 支持三个发行版；napcat 仅支持 Debian")
    source, destination = Path(source), Path(destination)
    if not source.is_file():
        raise FileNotFoundError(f"rootfs 源文件不存在：{source}")
    if source.resolve() == destination.resolve() or destination.exists():
        raise ValueError("目标文件已存在或与源文件相同，拒绝覆盖")
    if destination.name.endswith(".tar.gz"):
        mode = "w:gz"
    elif destination.name.endswith(".tar.xz"):
        mode = "w:xz"
    else:
        raise ValueError("产物文件名必须以 .tar.gz 或 .tar.xz 结尾")
    docker = shutil.which("docker")
    if not docker:
        raise RuntimeError("缺少 Docker CLI，请先在构建环境配置 Docker；不会自动安装")
    if not PREINSTALL_SCRIPT.is_file():
        raise FileNotFoundError(f"预装脚本不存在：{PREINSTALL_SCRIPT}")
    script = PREINSTALL_SCRIPT.read_bytes()
    expected = {"schemaVersion": 1, "profile": profile, "distro": distro,
                "abi": abi, "recipeRevision": RECIPE_REVISION}
    unique = uuid.uuid4().hex
    image = f"aharou-rootfs-{unique}:build"
    container = f"aharou-rootfs-{unique}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".aharou-rootfs-", dir=destination.parent) as temporary:
        work = Path(temporary)
        config = work / "docker-config"
        config.mkdir()
        # Docker 客户端与 guest 都使用显式环境，不传入发布令牌、代理密码或宿主配置。
        environment = {"PATH": os.environ.get("PATH", os.defpath), "HOME": str(work),
                       "DOCKER_CONFIG": str(config), "LANG": "C"}

        def run(*arguments, **kwargs):
            return subprocess.run([docker, *arguments], env=environment, check=True, **kwargs)

        normalized = work / "source.tar"
        exported = work / "export.tar"
        artifact = work / destination.name
        _rewrite(source, normalized, "w", allow_wrapper=True)
        run("info", "--format", "{{.ServerVersion}}", stdout=subprocess.PIPE)
        cleanup_errors = []
        failure = None
        try:
            run("import", "--platform", PLATFORMS[abi], str(normalized), image,
                stdout=subprocess.PIPE)
            run("create", "--platform", PLATFORMS[abi], "--name", container,
                "--hostname", "aharou-rootfs", "--interactive", image,
                "/usr/bin/env", "-i", "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "HOME=/root",
                "LANG=C", "/bin/sh", "-s", "--", distro, abi, profile, RECIPE_REVISION,
                stdout=subprocess.PIPE)
            run("start", "--attach", "--interactive", container, input=script)
            state = run("inspect", "--format", "{{.State.ExitCode}}", container,
                        stdout=subprocess.PIPE).stdout.decode().strip()
            if state != "0":
                raise RuntimeError(f"预装容器退出码为 {state}，不发布产物")
            run("export", "--output", str(exported), container)
            _verify_marker(exported, expected)
            _rewrite(exported, artifact, mode, allow_wrapper=False)
            _verify_marker(artifact, expected)
        except BaseException as error:
            failure = error
            raise
        finally:
            for arguments in (("rm", "--force", container), ("image", "rm", "--force", image)):
                try:
                    result = subprocess.run([docker, *arguments], env=environment, check=False,
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    if result.returncode and b"No such" not in result.stderr:
                        cleanup_errors.append(" ".join(arguments))
                except OSError:
                    cleanup_errors.append(" ".join(arguments))
            if cleanup_errors:
                message = "本次 Docker 资源清理失败：" + "; ".join(cleanup_errors)
                if failure is None:
                    raise RuntimeError(message)
                print(message, file=sys.stderr)
        digest = hashlib.sha256()
        with artifact.open("rb") as content:
            for chunk in iter(lambda: content.read(1024 * 1024), b""):
                digest.update(chunk)
        size = artifact.stat().st_size
        # 排他创建，避免长时间构建期间覆盖另一任务刚交付的文件。
        target = destination.open("xb")
        try:
            with target, artifact.open("rb") as content:
                shutil.copyfileobj(content, target)
        except BaseException:
            destination.unlink(missing_ok=True)
            raise
    return {"profile": profile, "recipeRevision": RECIPE_REVISION,
            "sha256": digest.hexdigest(), "sizeBytes": size}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--distro", required=True, choices=("alpine", "ubuntu", "debian"))
    parser.add_argument("--abi", required=True, choices=tuple(PLATFORMS))
    parser.add_argument("--profile", required=True, choices=("general", "napcat"))
    parser.add_argument("--build", action="store_true", help="明确执行 Docker 构建及容器内依赖下载")
    args = parser.parse_args(argv)
    if args.profile == "napcat" and args.distro != "debian":
        parser.error("napcat 仅支持 Debian")
    if not args.build:
        print("未指定 --build：仅展示计划，不执行 Docker 或下载。")
        print(json.dumps({"source": str(args.source), "destination": str(args.destination),
                          "distro": args.distro, "abi": args.abi, "profile": args.profile,
                          "recipeRevision": RECIPE_REVISION}, ensure_ascii=False))
        return 0
    try:
        print(json.dumps(build_rootfs(args.source, args.destination, args.distro, args.abi, args.profile)))
        return 0
    except (OSError, ValueError, RuntimeError, tarfile.TarError, subprocess.SubprocessError) as error:
        print(f"预装构建失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
