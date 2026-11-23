#!/usr/bin/env python3
"""对账插件宿主 API：方法表 ↔ 三处桥实现 ↔ 页面 SDK ↔ 插件包里的调用点。

事实源分工：
  - `feature/plugin/domain/PluginHostApiCatalog.kt`  方法表（方法名 + 可用来源 + 废弃信息）
  - `feature/plugin/runtime/PluginHostApiDispatcher.kt`  页面与进程共用的实现分支
  - `feature/plugin/presentation/PluginWebBridge.kt`  页面专属分支（上下文、文件订阅等）
  - `feature/plugin/overlay/PluginOverlayBridge.kt`  浮层专属分支
  - `app/src/main/assets/plugin-ui/aicode.js`  页面 SDK 的真实转发点 `call('方法名')`
  - `plugins-src/*`（以及 `plugins/*` 下同形态的插件目录）  插件包自己的页面与运行时调用

同时用 `plugins/schema/plugin.schema.json` 校验清单键名：schema 的顶层属性必须与
`PluginManifestParser` 的 TOP_LEVEL_KEYS 完全一致，仓库内插件清单的顶层键不得越界。

只读，无第三方依赖。退出码 0 一致；1 有不一致或解析失败。
用法：python3 scripts/check_plugin_host_api.py
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CATALOG_KT = "app/src/main/java/com/aharou/feature/plugin/domain/PluginHostApiCatalog.kt"
DISPATCHER_KT = "app/src/main/java/com/aharou/feature/plugin/runtime/PluginHostApiDispatcher.kt"
PAGE_BRIDGE_KT = "app/src/main/java/com/aharou/feature/plugin/presentation/PluginWebBridge.kt"
OVERLAY_BRIDGE_KT = "app/src/main/java/com/aharou/feature/plugin/overlay/PluginOverlayBridge.kt"
PAGE_SDK_JS = "app/src/main/assets/plugin-ui/aicode.js"
MANIFEST_PARSER_KT = "app/src/main/java/com/aharou/feature/plugin/domain/PluginManifestParser.kt"
SCHEMA_JSON = "plugins/schema/plugin.schema.json"

PLUGIN_ROOTS = ["plugins-src", "plugins"]

PAGE = "PAGE"
PROCESS = "PROCESS"
OVERLAY = "OVERLAY"


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def mask_strings(src):
    """把字符串字面量替换成等长空白，便于按花括号配平且不改变字符下标。"""
    return re.sub(r'"(?:\\.|[^"\\])*"', lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', src)


def function_body(src, signature):
    """从 `signature` 出现处按花括号配平截出函数体。"""
    start = src.find(signature)
    if start < 0:
        return None
    brace = src.find("{", start + len(signature))
    if brace < 0:
        return None
    depth = 0
    masked = mask_strings(src)
    for i in range(brace, len(masked)):
        ch = masked[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return src[brace:i + 1]
    return None


BRANCH = re.compile(
    r'^\s*((?:"[A-Za-z][A-Za-z0-9_.]*"\s*,\s*)*"[A-Za-z][A-Za-z0-9_.]*")\s*->', re.M
)
QUOTED = re.compile(r'"([A-Za-z][A-Za-z0-9_.]*)"')
CONST = re.compile(r"private val ([A-Z_]+) = setOf\(([^)]*)\)", re.S)
ORIGIN_TOKEN = re.compile(r"PluginHostApiOrigin\.([A-Z]+)")
METHOD_M = re.compile(r'^\s*m\("([^"]+)",\s*([A-Z_]+)\),?\s*$', re.M)
METHOD_D = re.compile(
    r'^\s*d\("([^"]+)",\s*"([^"]*)",\s*"([^"]*)",\s*([A-Z_]+)\),?\s*$', re.M
)


def branch_names(body):
    names = set()
    for match in BRANCH.finditer(body or ""):
        names.update(QUOTED.findall(match.group(1)))
    return names


def parse_catalog(problems):
    src = read(CATALOG_KT)
    constants = {}
    for name, body in CONST.findall(src):
        constants[name] = set(ORIGIN_TOKEN.findall(body))
    if not constants:
        problems.append("未能从方法表解析出来源常量，正则可能已失效：%s" % CATALOG_KT)

    entries = {}
    for name, const in METHOD_M.findall(src):
        if const not in constants:
            problems.append("方法表用了未知来源常量 %s：%s" % (const, name))
            continue
        entries[name] = {"origins": constants[const], "deprecated": None, "line": name}
    for name, replaced, removed, const in METHOD_D.findall(src):
        if const not in constants:
            problems.append("废弃方法用了未知来源常量 %s：%s" % (const, name))
            continue
        entries[name] = {
            "origins": constants[const],
            "deprecated": {"replacedBy": replaced, "removedIn": removed},
            "line": name,
        }
    if len(entries) < 40:
        problems.append(
            "方法表只解析到 %d 个方法，明显偏少，正则可能已失效：%s" % (len(entries), CATALOG_KT)
        )
    return entries


def parse_manifest_keys(problems):
    src = read(MANIFEST_PARSER_KT)
    match = re.search(r"private val TOP_LEVEL_KEYS = setOf\(([^)]*)\)", src, re.S)
    if not match:
        problems.append("未能从清单解析器解析出 TOP_LEVEL_KEYS：%s" % MANIFEST_PARSER_KT)
        return set()
    keys = {key.replace("\\$", "$") for key in re.findall(r'"([^"]+)"', match.group(1))}
    contributes = re.search(r'it != "skills".*?it != "configPage"', src, re.S)
    contributes_keys = set()
    if contributes:
        contributes_keys = set(re.findall(r'it != "([^"]+)"', contributes.group(0)))
    return keys, contributes_keys


def resolve_ref(schema, ref):
    if not ref.startswith("#/"):
        return False
    node = schema
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return False
        node = node[part]
    return True


def deref(schema, node):
    """跟随本地 $ref，用于读取定义体里的 properties。"""
    seen = set()
    while isinstance(node, dict) and isinstance(node.get("$ref"), str):
        ref = node["$ref"]
        if ref in seen or not ref.startswith("#/"):
            return node
        seen.add(ref)
        target = schema
        for part in ref[2:].split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            target = target.get(part, {}) if isinstance(target, dict) else {}
        node = target
    return node


def walk_refs(node, out):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                out.append(value)
            else:
                walk_refs(value, out)
    elif isinstance(node, list):
        for item in node:
            walk_refs(item, out)


def check_schema(problems, notes):
    path = os.path.join(ROOT, SCHEMA_JSON)
    if not os.path.isfile(path):
        problems.append("缺少清单 Schema：%s" % SCHEMA_JSON)
        return
    try:
        with open(path, encoding="utf-8") as f:
            schema = json.load(f)
    except (OSError, ValueError) as exc:
        problems.append("清单 Schema 不是合法 JSON：%s（%s）" % (SCHEMA_JSON, exc))
        return

    refs = []
    walk_refs(schema, refs)
    broken = sorted({ref for ref in refs if not resolve_ref(schema, ref)})
    for ref in broken:
        problems.append("清单 Schema 的 $ref 断链：%s" % ref)

    top_keys, contributes_keys = parse_manifest_keys(problems)
    properties = set((schema.get("properties") or {}).keys())
    if top_keys and properties != top_keys:
        problems.append(
            "清单 Schema 顶层属性与解析器 TOP_LEVEL_KEYS 不一致：\n"
            "  Schema 多出：%s\n  解析器多出：%s"
            % (sorted(properties - top_keys) or "无", sorted(top_keys - properties) or "无")
        )
    contributes = (deref(schema, (schema.get("properties") or {}).get("contributes")) or {}).get(
        "properties"
    ) or {}
    if contributes_keys and set(contributes.keys()) != contributes_keys:
        problems.append(
            "清单 Schema 的 contributes 属性与解析器不一致：\n"
            "  Schema 多出：%s\n  解析器多出：%s"
            % (sorted(set(contributes.keys()) - contributes_keys) or "无",
               sorted(contributes_keys - set(contributes.keys())) or "无")
        )
    notes.append("清单 Schema：$ref 全通，顶层与 contributes 键名与解析器一致")


def load_manifests(notes):
    manifests = []
    for root in PLUGIN_ROOTS:
        base = os.path.join(ROOT, root)
        if not os.path.isdir(base):
            continue
        for entry in sorted(os.listdir(base)):
            manifest = os.path.join(base, entry, "plugin.json")
            if not os.path.isfile(manifest):
                continue
            try:
                with open(manifest, encoding="utf-8") as f:
                    data = json.load(f)
            except (OSError, ValueError) as exc:
                notes.append("[WARN] 清单读取失败，跳过：%s（%s）" % (manifest, exc))
                continue
            manifests.append((os.path.relpath(manifest, ROOT), os.path.dirname(manifest), data))
    return manifests


def manifest_pages(manifest):
    """返回 (包内相对路径, 页面来源)：浮层页面只开放浮层能力，其余页面用页面能力。"""
    pages = []
    if isinstance(manifest.get("configPage"), str):
        pages.append((manifest["configPage"], PAGE))
    contributes = manifest.get("contributes")
    if not isinstance(contributes, dict):
        return pages
    if isinstance(contributes.get("configPage"), str):
        pages.append((contributes["configPage"], PAGE))
    for item in contributes.get("uiEntries") or []:
        if isinstance(item, dict) and isinstance(item.get("page"), str):
            pages.append((item["page"], PAGE))
    for item in contributes.get("workspaceTools") or []:
        if isinstance(item, dict) and isinstance(item.get("pageFile"), str):
            pages.append((item["pageFile"], PAGE))
    overlay = contributes.get("overlay")
    if isinstance(overlay, dict):
        for surface in overlay.get("surfaces") or []:
            if isinstance(surface, dict) and isinstance(surface.get("page"), str):
                pages.append((surface["page"], OVERLAY))
    return pages


CALL_SITE = re.compile(r"""call\(\s*['"]([A-Za-z][A-Za-z0-9_.]*)['"]""")
HOST_CALL = re.compile(r"\bhost\.([a-z][a-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)")
HOST_NAMESPACES = {
    "fs", "app", "chat", "model", "tools", "secrets", "terminal", "overlay",
    "ports", "ui", "mcp", "http", "shizuku", "workspaces", "sessions",
}


def scan_file(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except (OSError, UnicodeDecodeError):
        return ""


def main():
    problems = []
    notes = []

    catalog = parse_catalog(problems)
    dispatcher = branch_names(function_body(read(DISPATCHER_KT), "fun route("))
    page_local = branch_names(function_body(read(PAGE_BRIDGE_KT), "fun dispatch("))
    overlay_local = branch_names(function_body(read(OVERLAY_BRIDGE_KT), "fun dispatch("))

    if not dispatcher:
        problems.append("未能从分发器解析到任何分支：%s" % DISPATCHER_KT)
    if not page_local:
        problems.append("未能从页面桥解析到任何分支：%s" % PAGE_BRIDGE_KT)
    if not overlay_local:
        problems.append("未能从浮层桥解析到任何分支：%s" % OVERLAY_BRIDGE_KT)

    # 1) 方法表 ↔ 实现
    for name, entry in sorted(catalog.items()):
        origins = entry["origins"]
        if PAGE in origins and name not in page_local and name not in dispatcher:
            problems.append("方法表声明页面可用但没有实现：%s" % name)
        if PROCESS in origins and name not in dispatcher:
            problems.append("方法表声明进程可用但分发器没有分支：%s" % name)
        if OVERLAY in origins and name not in overlay_local:
            problems.append("方法表声明浮层可用但浮层桥没有分支：%s" % name)
        deprecated = entry["deprecated"]
        if deprecated:
            if not deprecated["replacedBy"] or not deprecated["removedIn"]:
                problems.append("废弃方法缺少 replacedBy / removedIn：%s" % name)
            elif deprecated["replacedBy"] not in catalog:
                problems.append(
                    "废弃方法 %s 的替代方法未在方法表里：%s" % (name, deprecated["replacedBy"])
                )
    for name in sorted(dispatcher):
        if name not in catalog:
            problems.append("分发器实现了方法表里没有的方法：%s" % name)
        elif not ({PAGE, PROCESS} & catalog[name]["origins"]):
            problems.append("分发器实现了只属于浮层的受控方法：%s" % name)
    for name in sorted(page_local):
        if name not in catalog:
            problems.append("页面桥实现了方法表里没有的方法：%s" % name)
        elif PAGE not in catalog[name]["origins"]:
            problems.append("页面桥实现了方法表未开放给页面的方法：%s" % name)
    for name in sorted(overlay_local):
        if name not in catalog:
            problems.append("浮层桥实现了方法表里没有的方法：%s" % name)
        elif OVERLAY not in catalog[name]["origins"]:
            problems.append("浮层桥实现了方法表未开放给浮层的方法：%s" % name)

    def check_calls(label, rel, names, origins):
        for name in sorted(names):
            entry = catalog.get(name)
            if entry is None or not (origins & entry["origins"]):
                problems.append("%s 调用了宿主未提供的方法：%s（%s）" % (label, name, rel))
            elif entry["deprecated"]:
                notes.append(
                    "[WARN] %s 仍在用已废弃方法 %s，改用 %s（计划 %s 移除）"
                    % (label, name, entry["deprecated"]["replacedBy"], entry["deprecated"]["removedIn"])
                )

    # 2) 页面 SDK
    sdk_calls = set(CALL_SITE.findall(read(PAGE_SDK_JS)))
    if not sdk_calls:
        problems.append("未能从页面 SDK 解析到任何 call(...)：%s" % PAGE_SDK_JS)
    check_calls("页面 SDK", PAGE_SDK_JS, sdk_calls, {PAGE, OVERLAY})
    sdk_only_host = sorted(set(catalog) - sdk_calls)
    notes.append(
        "页面 SDK 覆盖 %d 个方法；宿主另有 %d 个方法未暴露给页面 SDK（可能有意只给进程用）"
        % (len(sdk_calls), len(sdk_only_host))
    )

    # 3) 插件包
    plugin_count = 0
    for rel, directory, manifest in load_manifests(notes):
        plugin_count += 1
        for page, origin in manifest_pages(manifest):
            page_path = os.path.join(directory, page)
            if not os.path.isfile(page_path):
                continue
            found = set(CALL_SITE.findall(scan_file(page_path)))
            check_calls("插件 %s 的页面" % rel, os.path.relpath(page_path, ROOT), found, {origin})
        contributes = manifest.get("contributes")
        runtime = contributes.get("runtime") if isinstance(contributes, dict) else None
        entry = runtime.get("entry") if isinstance(runtime, dict) else None
        if isinstance(entry, str):
            runtime_path = os.path.join(directory, entry)
            if os.path.isfile(runtime_path):
                names = {
                    m for m in HOST_CALL.findall(scan_file(runtime_path))
                    if m.split(".", 1)[0] in HOST_NAMESPACES
                }
                check_calls(
                    "插件 %s 的运行时" % rel,
                    os.path.relpath(runtime_path, ROOT),
                    names,
                    {PROCESS},
                )

    check_schema(problems, notes)

    deprecated = sorted(
        (name, entry["deprecated"]) for name, entry in catalog.items() if entry["deprecated"]
    )

    print("插件宿主 API 一致性检查")
    print("  方法表 %d 个；分发器分支 %d 个；页面桥专属 %d 个；浮层桥专属 %d 个"
          % (len(catalog), len(dispatcher), len(page_local), len(overlay_local)))
    print("  页面 SDK 调用点 %d 个；仓库内插件 %d 个" % (len(sdk_calls), plugin_count))
    print("  已废弃方法 %d 个" % len(deprecated))
    for name, info in deprecated:
        print("    - %s → %s（计划 %s 移除）" % (name, info["replacedBy"], info["removedIn"]))
    for note in notes:
        print("  " + note)

    if problems:
        print("\n不一致：")
        for problem in problems:
            print("  - " + problem)
        print("\n共 %d 处不一致。" % len(problems))
        return 1

    print("\n一致：方法表、三处桥、页面 SDK 与插件包调用点全部对得上。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
