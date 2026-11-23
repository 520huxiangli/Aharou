#!/usr/bin/env python3
"""用 `plugins/schema/plugin.schema.json` 校验插件清单 plugin.json。

不引入 jsonschema 等第三方库，内置一个覆盖本仓库 Schema 实际用到的关键字子集的最小校验器：
$ref / type / required / properties / additionalProperties / propertyNames /
enum / const / pattern / minLength / maxLength / minItems / maxItems / items /
minimum / maximum / oneOf。Schema 里出现没实现的关键字会提示一次（跳过，不改判定）。

只报告不阻断：这是给插件作者本地自查与 CI 用的静态校验，安装流程不调用它，
校验失败也不会让插件装不上。字段事实源仍是 PluginManifestParser（解析器更严格，
两者不一致时以解析器为准，`check_plugin_host_api.py` 对账键名）。

退出码：0 全部通过；1 有清单不合格或 Schema 不可用；2 用法错误。
用法：
  python3 scripts/check-plugin-manifests.py                       # 扫 plugins-src/* 与 plugins/*
  python3 scripts/check-plugin-manifests.py path/to/plugin.json   # 指定清单或目录
  python3 scripts/check-plugin-manifests.py --root DIR            # 换仓库根（脚本自测用）
"""

from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCHEMA_JSON = "plugins/schema/plugin.schema.json"
PLUGIN_ROOTS = ("plugins-src", "plugins")

USAGE = (
    "用法：python3 scripts/check-plugin-manifests.py "
    "[--root DIR] [plugin.json 或插件目录 ...]"
)

# 校验器实现的关键字；其余（description / title 等）是注释性字段，跳过不算问题。
IMPLEMENTED = {
    "$ref", "type", "required", "properties", "additionalProperties", "propertyNames",
    "enum", "const", "pattern", "minLength", "maxLength", "minItems", "maxItems",
    "items", "minimum", "maximum", "oneOf",
}
IGNORED = {"description", "title", "$schema", "$id", "default", "definitions", "$defs"}

TYPE_LABEL = {
    "object": "对象",
    "array": "数组",
    "string": "字符串",
    "number": "数字",
    "integer": "整数",
    "boolean": "布尔值",
    "null": "null",
}

MAX_DEPTH = 64


def type_matches(value, name: str) -> bool:
    if name == "object":
        return isinstance(value, dict)
    if name == "array":
        return isinstance(value, list)
    if name == "string":
        return isinstance(value, str)
    if name == "boolean":
        return isinstance(value, bool)
    if name == "null":
        return value is None
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return True


def value_type(value) -> str:
    if isinstance(value, bool):
        return "布尔值"
    if isinstance(value, dict):
        return "对象"
    if isinstance(value, list):
        return "数组"
    if isinstance(value, str):
        return "字符串"
    if isinstance(value, int):
        return "整数"
    if isinstance(value, float):
        return "数字"
    if value is None:
        return "null"
    return type(value).__name__


def json_equal(left, right) -> bool:
    """按 JSON 语义比较：`true` 与 `1` 不相等。"""
    if isinstance(left, bool) != isinstance(right, bool):
        return False
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(
            json_equal(left[key], right[key]) for key in left
        )
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(
            json_equal(a, b) for a, b in zip(left, right)
        )
    return left == right


def child_path(path: str, key: str) -> str:
    return key if not path else "%s.%s" % (path, key)


def index_path(path: str, index: int) -> str:
    return "%s[%d]" % (path, index)


class Validator:
    def __init__(self, schema: dict):
        self.schema = schema
        self.unknown_keywords: set[str] = set()

    def resolve(self, ref: str):
        if not ref.startswith("#/"):
            return None
        node = self.schema
        for part in ref[2:].split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            if not isinstance(node, dict) or part not in node:
                return None
            node = node[part]
        return node

    def validate(self, value, schema, path: str, errors: list[str], depth: int = 0) -> None:
        if depth > MAX_DEPTH:
            errors.append("%s：Schema 嵌套过深或存在循环 $ref" % (path or "清单"))
            return
        if not isinstance(schema, dict):
            return

        for key in schema:
            if key not in IMPLEMENTED and key not in IGNORED:
                self.unknown_keywords.add(key)

        ref = schema.get("$ref")
        if isinstance(ref, str):
            target = self.resolve(ref)
            if target is None:
                errors.append("%s：Schema 的 $ref 断链 %s" % (path or "清单", ref))
                return
            # draft-07 里 $ref 的兄弟关键字被忽略，本仓库 Schema 也没有兄弟关键字。
            self.validate(value, target, path, errors, depth + 1)
            return

        label = path or "清单"

        if "const" in schema and not json_equal(value, schema["const"]):
            errors.append(
                "%s：只能是 %s，当前是 %s"
                % (label, json.dumps(schema["const"], ensure_ascii=False), json.dumps(value, ensure_ascii=False))
            )
            return

        if isinstance(schema.get("enum"), list):
            if not any(json_equal(value, item) for item in schema["enum"]):
                allowed = "、".join(json.dumps(item, ensure_ascii=False) for item in schema["enum"])
                errors.append(
                    "%s：取值不在允许范围（%s），当前是 %s"
                    % (label, allowed, json.dumps(value, ensure_ascii=False))
                )
                return

        declared = schema.get("type")
        if declared is not None:
            allowed_types = declared if isinstance(declared, list) else [declared]
            allowed_types = [name for name in allowed_types if isinstance(name, str)]
            if allowed_types and not any(type_matches(value, name) for name in allowed_types):
                wanted = " 或 ".join(TYPE_LABEL.get(name, name) for name in allowed_types)
                errors.append("%s：必须是%s，当前是%s" % (label, wanted, value_type(value)))
                return

        if isinstance(value, str):
            pattern = schema.get("pattern")
            if isinstance(pattern, str):
                try:
                    matched = re.search(pattern, value) is not None
                except re.error:
                    errors.append("%s：Schema 的 pattern 不是合法正则：%s" % (label, pattern))
                    matched = True
                if not matched:
                    errors.append(
                        "%s：不符合格式 %s，当前是 %s"
                        % (label, pattern, json.dumps(value, ensure_ascii=False))
                    )
            if isinstance(schema.get("minLength"), int) and len(value) < schema["minLength"]:
                errors.append("%s：长度不能少于 %d" % (label, schema["minLength"]))
            if isinstance(schema.get("maxLength"), int) and len(value) > schema["maxLength"]:
                errors.append("%s：长度不能超过 %d，当前 %d" % (label, schema["maxLength"], len(value)))

        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if isinstance(schema.get("minimum"), (int, float)) and value < schema["minimum"]:
                errors.append("%s：不能小于 %s" % (label, schema["minimum"]))
            if isinstance(schema.get("maximum"), (int, float)) and value > schema["maximum"]:
                errors.append("%s：不能大于 %s" % (label, schema["maximum"]))

        if isinstance(value, list):
            if isinstance(schema.get("minItems"), int) and len(value) < schema["minItems"]:
                errors.append("%s：至少要有 %d 项" % (label, schema["minItems"]))
            if isinstance(schema.get("maxItems"), int) and len(value) > schema["maxItems"]:
                errors.append("%s：最多 %d 项，当前 %d" % (label, schema["maxItems"], len(value)))
            items = schema.get("items")
            if isinstance(items, dict):
                for index, item in enumerate(value):
                    self.validate(item, items, index_path(path, index), errors, depth + 1)

        if isinstance(value, dict):
            required = schema.get("required")
            if isinstance(required, list):
                for key in required:
                    if isinstance(key, str) and key not in value:
                        errors.append("缺少必填字段 %s" % child_path(path, key))
            properties = schema.get("properties")
            properties = properties if isinstance(properties, dict) else {}
            for key, item in value.items():
                key_path = child_path(path, key)
                if key in properties:
                    self.validate(item, properties[key], key_path, errors, depth + 1)
                    continue
                extra = schema.get("additionalProperties")
                if extra is False:
                    errors.append("不支持的字段 %s" % key_path)
                elif isinstance(extra, dict):
                    self.validate(item, extra, key_path, errors, depth + 1)
            property_names = schema.get("propertyNames")
            if isinstance(property_names, dict):
                for key in value:
                    self.validate(key, property_names, "%s（键名）" % child_path(path, key), errors, depth + 1)

        one_of = schema.get("oneOf")
        if isinstance(one_of, list) and one_of:
            passing = 0
            branch_errors: list[str] = []
            for branch in one_of:
                branch_errors_list: list[str] = []
                self.validate(value, branch, path, branch_errors_list, depth + 1)
                if branch_errors_list:
                    branch_errors.append(branch_errors_list[0])
                else:
                    passing += 1
            if passing == 0:
                detail = "；".join(branch_errors[:3])
                errors.append("%s：不符合 oneOf 的任何一种形态（%s）" % (label, detail))
            elif passing > 1:
                errors.append("%s：同时满足 oneOf 的 %d 种形态，只能满足一种" % (label, passing))


def discover(paths: list[str], root: str) -> tuple[list[str], str | None]:
    """返回 (清单绝对路径列表, 用法错误信息)。"""
    if paths:
        found: list[str] = []
        for raw in paths:
            target = raw if os.path.isabs(raw) else os.path.join(os.getcwd(), raw)
            if os.path.isdir(target):
                target = os.path.join(target, "plugin.json")
            if not os.path.isfile(target):
                return [], "找不到清单文件：%s" % raw
            found.append(target)
        return found, None

    found = []
    for base_name in PLUGIN_ROOTS:
        base = os.path.join(root, base_name)
        if not os.path.isdir(base):
            continue
        for entry in sorted(os.listdir(base)):
            manifest = os.path.join(base, entry, "plugin.json")
            if os.path.isfile(manifest):
                found.append(manifest)
    return found, None


def display(path: str) -> str:
    try:
        rel = os.path.relpath(path, ROOT)
    except ValueError:
        return path
    return path if rel.startswith("..") else rel


def usage_error(message: str) -> int:
    print("[FAIL] %s" % message, file=sys.stderr)
    print(USAGE, file=sys.stderr)
    return 2


def main(argv: list[str]) -> int:
    root = ROOT
    paths: list[str] = []
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in ("-h", "--help"):
            print(USAGE)
            return 0
        if arg == "--root":
            index += 1
            if index >= len(argv):
                return usage_error("--root 缺少目录")
            root = argv[index]
        elif arg.startswith("--root="):
            root = arg.split("=", 1)[1]
        elif arg.startswith("-"):
            return usage_error("未知参数：%s" % arg)
        else:
            paths.append(arg)
        index += 1

    if not os.path.isdir(root):
        return usage_error("目录不存在：%s" % root)

    schema_path = os.path.join(root, SCHEMA_JSON)
    try:
        with open(schema_path, encoding="utf-8") as f:
            schema = json.load(f)
    except OSError as exc:
        print("[FAIL] 读不到清单 Schema：%s（%s）" % (SCHEMA_JSON, exc))
        return 1
    except ValueError as exc:
        print("[FAIL] 清单 Schema 不是合法 JSON：%s（%s）" % (SCHEMA_JSON, exc))
        return 1
    if not isinstance(schema, dict):
        print("[FAIL] 清单 Schema 顶层必须是对象：%s" % SCHEMA_JSON)
        return 1

    manifests, error = discover(paths, root)
    if error:
        return usage_error(error)

    print("插件清单校验")
    print("  Schema：%s（内置最小校验器，无第三方依赖）" % SCHEMA_JSON)
    print("  待校验清单 %d 个" % len(manifests))

    if not manifests:
        print("\n[OK] 没有找到任何 plugin.json，跳过校验。")
        return 0

    validator = Validator(schema)
    failed_files = 0
    total_errors = 0
    for path in manifests:
        name = display(path)
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except OSError as exc:
            failed_files += 1
            total_errors += 1
            print("  [FAIL] %s" % name)
            print("    - 读取失败：%s" % exc)
            continue
        except ValueError as exc:
            failed_files += 1
            total_errors += 1
            print("  [FAIL] %s" % name)
            print("    - 不是合法 JSON：%s" % exc)
            continue

        errors: list[str] = []
        validator.validate(data, schema, "", errors)
        if errors:
            failed_files += 1
            total_errors += len(errors)
            print("  [FAIL] %s" % name)
            for message in errors:
                print("    - %s" % message)
        else:
            print("  [OK] %s" % name)

    if validator.unknown_keywords:
        print(
            "  [WARN] Schema 里有校验器未实现的关键字（已跳过）：%s"
            % "、".join(sorted(validator.unknown_keywords))
        )

    if total_errors:
        print(
            "\n[FAIL] 清单校验未通过：%d 处问题，涉及 %d 个清单。"
            % (total_errors, failed_files)
        )
        return 1

    print("\n[OK] 清单校验通过：%d 个插件。" % len(manifests))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
