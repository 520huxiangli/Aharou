#!/usr/bin/env python3
"""插件索引自检。

校验 plugins/index.json 的结构与条目合法性，并在本地存在插件包时核对版本与文件。
远端仓库里的包不在本地，只校验地址形态，不报缺失。

零依赖，CI 与本地都能直接跑；退出码 0 通过，1 有错误。
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = os.path.join(ROOT, "plugins", "index.json")
PACKAGES_DIR = os.path.join(ROOT, "plugins", "packages")

PLUGIN_ID = re.compile(r"^[a-z][a-z0-9]*(\.[a-z0-9][a-z0-9-]*)+$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$")

errors: list[str] = []
warnings: list[str] = []


def localized(value, field: str, plugin_id: str) -> str | None:
    if isinstance(value, str) and value.strip():
        return value
    if isinstance(value, dict) and value:
        for k, v in value.items():
            if not isinstance(v, str) or not v.strip():
                errors.append(f"{plugin_id}: {field} 的语言 {k} 不是非空字符串")
                return None
        return next(iter(value.values()))
    errors.append(f"{plugin_id}: {field} 必须是非空字符串或语言映射")
    return None


def check_package(plugin_id: str, version: str, url: str) -> None:
    if not url.startswith("https://"):
        errors.append(f"{plugin_id}: package 必须是 https 地址")
        return
    local = os.path.join(PACKAGES_DIR, plugin_id, f"{version}.zip")
    if os.path.isfile(local):
        if os.path.getsize(local) == 0:
            errors.append(f"{plugin_id}: 本地插件包为空 {local}")
    elif os.path.isdir(os.path.join(PACKAGES_DIR, plugin_id)):
        warnings.append(f"{plugin_id}: 本地有包目录但缺少 {version}.zip")


def main() -> int:
    if not os.path.isfile(INDEX):
        print(f"[FAIL] 找不到索引：{INDEX}")
        return 1
    try:
        with open(INDEX, encoding="utf-8") as f:
            doc = json.load(f)
    except Exception as exc:
        print(f"[FAIL] 索引不是合法 JSON：{exc}")
        return 1

    if doc.get("schema_version") != 1:
        errors.append(f"schema_version 必须是 1，当前为 {doc.get('schema_version')!r}")

    plugins = doc.get("plugins")
    if not isinstance(plugins, list):
        print("[FAIL] plugins 必须是数组")
        return 1
    if len(plugins) > 200:
        errors.append(f"插件条目过多（{len(plugins)}），上限 200")

    seen: set[str] = set()
    for item in plugins:
        if not isinstance(item, dict):
            errors.append("存在非对象的插件条目")
            continue
        pid = item.get("id")
        if not isinstance(pid, str) or not PLUGIN_ID.match(pid):
            errors.append(f"非法插件 ID：{pid!r}")
            continue
        if pid in seen:
            errors.append(f"插件 ID 重复：{pid}")
            continue
        seen.add(pid)

        localized(item.get("name"), "name", pid)
        localized(item.get("description"), "description", pid)

        author = item.get("author")
        if not isinstance(author, str) or not re.match(r"^[a-z][a-z0-9-]*$", author):
            errors.append(f"{pid}: author 非法（只允许小写字母、数字与连字符）")

        repo = item.get("repository")
        if not isinstance(repo, str) or not repo.startswith("https://"):
            errors.append(f"{pid}: repository 必须是 https 地址")

        version = item.get("version")
        if version is None:
            warnings.append(f"{pid}: 没有 version，客户端只能按 latest 解析")
        elif not isinstance(version, str) or not SEMVER.match(version):
            errors.append(f"{pid}: version 不是合法 SemVer：{version!r}")

        package = item.get("package")
        if package is not None:
            if not isinstance(package, str):
                errors.append(f"{pid}: package 必须是字符串")
            elif version is None:
                errors.append(f"{pid}: 配了 package 就必须写 version")
            else:
                check_package(pid, version, package)

        logo = item.get("logo")
        if logo is not None and (not isinstance(logo, str) or not logo.startswith("https://")):
            errors.append(f"{pid}: logo 必须是 https 地址")

    for warning in warnings:
        print(f"[WARN] {warning}")
    for error in errors:
        print(f"[FAIL] {error}")
    if errors:
        print(f"\n插件索引自检未通过：{len(errors)} 个错误")
        return 1
    print(f"[OK] 插件索引自检通过：{len(plugins)} 个插件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
