#!/usr/bin/env python3
"""对账插件页面 SDK（`aicode.js`）的方法名与宿主方法表。

两个方向分开处理：
  - 页面在用、宿主没有（或宿主有但没开放给页面/浮层）：报错，退出码 1；
  - 宿主有、页面 SDK 没暴露：只打印（可能是只给插件进程用的方法），不影响退出码。

顺带核对 `plugins/sdk/types/host.d.ts` 的 `HostMethodMap` 与三个来源联合类型：
声明文件存在时必须与方法表一一对应（方法缺失/多出/分组错都算不一致）；
声明文件不存在时只提示一句，不影响结果。

方法名统一取页面 SDK 里 `call('方法名')` 的真实转发点，宿主侧取
`feature/plugin/domain/PluginHostApiCatalog.kt` 的方法表与来源声明。
两者是不同层的事实源，类型与参数不在这里比对（类型见 `plugins/sdk/types/host.d.ts`）。

零依赖、只读。退出码：0 一致；1 有方法名对不上或解析失败；2 用法错误。
用法：
  python3 scripts/check-page-bridge.py
  python3 scripts/check-page-bridge.py --root <仓库根>   # 换根目录（脚本自测用）
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CATALOG_KT = "app/src/main/java/com/aharou/feature/plugin/domain/PluginHostApiCatalog.kt"
PAGE_SDK_JS = "app/src/main/assets/plugin-ui/aicode.js"
SDK_TYPES_DTS = "plugins/sdk/types/host.d.ts"

USAGE = "用法：python3 scripts/check-page-bridge.py [--root DIR]"

PAGE = "PAGE"
PROCESS = "PROCESS"
OVERLAY = "OVERLAY"

# 与 check_plugin_host_api.py 保持同一套解析口径：方法表用 m(...)/d(...) 声明。
CONST = re.compile(r"private val ([A-Z_]+) = setOf\(([^)]*)\)", re.S)
ORIGIN = re.compile(r"PluginHostApiOrigin\.([A-Z]+)")
METHOD = re.compile(r'^\s*m\("([^"]+)",\s*([A-Z_]+)\)', re.M)
DEPRECATED = re.compile(
    r'^\s*d\("([^"]+)",\s*"([^"]*)",\s*"([^"]*)",\s*([A-Z_]+)\)', re.M
)
CALL_SITE = re.compile(r"""call\(\s*['"]([A-Za-z][A-Za-z0-9_.]*)['"]""")

# 方法表少于这个数说明正则失效或文件被整体改写，不能当作「一致」放过去。
MIN_METHODS = 40


def read(root: str, rel: str) -> str:
    with open(os.path.join(root, rel), encoding="utf-8") as f:
        return f.read()


def parse_catalog(root: str, problems: list[str]) -> dict[str, dict]:
    src = read(root, CATALOG_KT)
    constants = {
        name: set(ORIGIN.findall(body)) for name, body in CONST.findall(src)
    }
    if not constants:
        problems.append("未能从方法表解析出来源常量，正则可能已失效：%s" % CATALOG_KT)
        return {}

    entries: dict[str, dict] = {}

    def add(name: str, const: str, deprecated: dict | None = None) -> None:
        if const not in constants:
            problems.append("方法表用了未知来源常量 %s：%s" % (const, name))
            return
        if name in entries:
            problems.append("方法表里有重复方法名：%s" % name)
            return
        entries[name] = {"origins": constants[const], "deprecated": deprecated}

    for name, const in METHOD.findall(src):
        add(name, const)
    for name, replaced, removed, const in DEPRECATED.findall(src):
        add(name, const, {"replacedBy": replaced, "removedIn": removed})

    if len(entries) < MIN_METHODS:
        problems.append(
            "方法表只解析到 %d 个方法，明显偏少，正则可能已失效：%s"
            % (len(entries), CATALOG_KT)
        )
    return entries


def origin_label(origins: set[str]) -> str:
    names = {PAGE: "页面", PROCESS: "进程", OVERLAY: "浮层"}
    return "、".join(names[o] for o in (PAGE, PROCESS, OVERLAY) if o in origins) or "无"


TYPE_UNION = r"export type %s =([^;]+);"
TYPE_KEYS = re.compile(r'^  (?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*)):\s*\{', re.M)


def check_types(root: str, catalog: dict[str, dict], problems: list[str], notes: list[str]) -> None:
    """核对类型声明的 HostMethodMap 与三个来源联合类型。声明文件缺失时只提示。"""
    path = os.path.join(root, SDK_TYPES_DTS)
    if not os.path.isfile(path):
        notes.append("未找到类型声明 %s，跳过覆盖核对" % SDK_TYPES_DTS)
        return
    try:
        with open(path, encoding="utf-8") as f:
            src = f.read()
    except OSError as exc:
        notes.append("类型声明读取失败，跳过覆盖核对：%s" % exc)
        return

    match = re.search(r"export interface HostMethodMap \{(.*?)\n\}", src, re.S)
    if not match:
        problems.append("未能从类型声明解析出 HostMethodMap：%s" % SDK_TYPES_DTS)
        return
    mapped = [quoted or bare for quoted, bare in TYPE_KEYS.findall(match.group(1))]
    duplicates = sorted({name for name in mapped if mapped.count(name) > 1})
    if duplicates:
        problems.append("类型声明里有重复方法：%s" % "、".join(duplicates))
    missing = sorted(set(catalog) - set(mapped))
    extra = sorted(set(mapped) - set(catalog))
    for name in missing:
        problems.append("类型声明缺少宿主方法：%s（补 %s）" % (name, SDK_TYPES_DTS))
    for name in extra:
        problems.append("类型声明多了宿主没有的方法：%s" % name)

    def union(name: str) -> set[str] | None:
        found = re.search(TYPE_UNION % name, src, re.S)
        if not found:
            problems.append("类型声明缺少来源联合类型 %s：%s" % (name, SDK_TYPES_DTS))
            return None
        return set(re.findall(r'"([^"]+)"', found.group(1)))

    page_only = union("PageOnlyMethodName")
    process_only = union("ProcessOnlyMethodName")
    overlay_only = union("OverlayOnlyMethodName")
    exact_overlay = union("OverlayMethodName")
    if None in (page_only, process_only, overlay_only, exact_overlay):
        return

    expected = {
        PAGE: {n for n, e in catalog.items() if PAGE in e["origins"]},
        PROCESS: {n for n, e in catalog.items() if PROCESS in e["origins"]},
        OVERLAY: {n for n, e in catalog.items() if OVERLAY in e["origins"]},
    }
    declared = {
        PAGE: set(catalog) - process_only - overlay_only,
        PROCESS: set(catalog) - page_only - overlay_only - {"context"},
        OVERLAY: exact_overlay | overlay_only,
    }
    labels = {PAGE: "页面", PROCESS: "进程", OVERLAY: "浮层"}
    for origin in (PAGE, PROCESS, OVERLAY):
        lacking = sorted(expected[origin] - declared[origin])
        surplus = sorted(declared[origin] - expected[origin])
        if lacking:
            problems.append(
                "类型声明少了%s可用方法：%s" % (labels[origin], "、".join(lacking))
            )
        if surplus:
            problems.append(
                "类型声明把%s不可用的方法算进来了：%s" % (labels[origin], "、".join(surplus))
            )
    notes.append(
        "类型声明：HostMethodMap %d 个方法（页面 %d / 进程 %d / 浮层 %d）"
        % (len(mapped), len(expected[PAGE]), len(expected[PROCESS]), len(expected[OVERLAY]))
    )


def usage_error(message: str) -> int:
    print("[FAIL] %s" % message, file=sys.stderr)
    print(USAGE, file=sys.stderr)
    return 2


def parse_root(argv: list[str]) -> tuple[str | None, int | None]:
    root = ROOT
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in ("-h", "--help"):
            print(USAGE)
            return None, 0
        if arg == "--root":
            index += 1
            if index >= len(argv):
                return None, usage_error("--root 缺少目录")
            root = argv[index]
        elif arg.startswith("--root="):
            root = arg.split("=", 1)[1]
        else:
            return None, usage_error("未知参数：%s" % arg)
        index += 1
    if not os.path.isdir(root):
        return None, usage_error("目录不存在：%s" % root)
    return root, None


def main(argv: list[str]) -> int:
    root, early = parse_root(argv)
    if early is not None:
        return early

    problems: list[str] = []
    notes: list[str] = []

    try:
        catalog = parse_catalog(root, problems)
        sdk_calls = set(CALL_SITE.findall(read(root, PAGE_SDK_JS)))
    except OSError as exc:
        print("[FAIL] 读取失败：%s" % exc)
        return 1

    if not sdk_calls:
        problems.append("未能从页面 SDK 解析到任何 call(...)：%s" % PAGE_SDK_JS)

    check_types(root, catalog, problems, notes)

    unknown = sorted(sdk_calls - set(catalog))
    not_page = sorted(
        name for name in sdk_calls & set(catalog)
        if not ({PAGE, OVERLAY} & catalog[name]["origins"])
    )
    for name in unknown:
        problems.append("页面 SDK 调用了宿主没有的方法：%s" % name)
    for name in not_page:
        problems.append(
            "页面 SDK 调用了未开放给页面/浮层的方法：%s（宿主来源：%s）"
            % (name, origin_label(catalog[name]["origins"]))
        )

    unexposed = sorted(set(catalog) - sdk_calls)

    print("插件页面 SDK 与宿主方法表对账")
    print("  宿主方法表 %d 个；页面 SDK 调用 %d 个" % (len(catalog), len(sdk_calls)))
    if unexposed:
        print("  页面 SDK 未暴露 %d 个（只打印，页面可能本来就不该调）：" % len(unexposed))
        for name in unexposed:
            print("    - %s（来源：%s）" % (name, origin_label(catalog[name]["origins"])))

    for name in sorted(sdk_calls & set(catalog)):
        deprecated = catalog[name]["deprecated"]
        if deprecated:
            notes.append(
                "[WARN] 页面 SDK 仍在用已废弃方法 %s，改用 %s（计划 %s 移除）"
                % (name, deprecated["replacedBy"], deprecated["removedIn"])
            )
    for note in notes:
        print("  " + note)

    if problems:
        print("\n不一致：")
        for problem in problems:
            print("  - " + problem)
        print("\n共 %d 处不一致。" % len(problems))
        return 1

    print(
        "\n[OK] 一致：页面 SDK 的 %d 个方法都在宿主方法表里，且都开放给页面或浮层。"
        % len(sdk_calls)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
