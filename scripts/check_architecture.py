#!/usr/bin/env python3
"""架构门禁：禁用组件 import、单文件行数棘轮、双语 strings 一致性。

规则的事实源是根目录 `architecture-policy.json`；存量违规登记在 `.architecture-baseline.json`，
基线只许缩减——修好一处就删掉对应条目，脚本会在 [WARN] 里提示可清理项，新增违规一律拦截。

退出码 0 通过 / 1 有违规，供 CI 与 Gradle preBuild 复用。
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POLICY_REL = "architecture-policy.json"
BASELINE_REL = ".architecture-baseline.json"


def load_json(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return json.load(f)


def iter_sources(roots):
    """按源码根目录遍历 .kt，产出相对仓库根的 POSIX 路径。"""
    for root in roots:
        for dirpath, _, filenames in os.walk(os.path.join(ROOT, root)):
            for name in sorted(filenames):
                if name.endswith(".kt"):
                    abs_path = os.path.join(dirpath, name)
                    yield os.path.relpath(abs_path, ROOT).replace(os.sep, "/")


def check_banned_imports(policy, baseline, errors, notes):
    rules = policy.get("bannedImports", [])
    if not rules:
        return
    baseline_map = baseline.get("bannedImports", {})
    seen = {rule["import"]: set() for rule in rules}
    for rel in iter_sources(policy.get("sourceRoots", [])):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            text = f.read()
        for rule in rules:
            imp = rule["import"]
            # 行尾锚定，避免 TextField 误伤 TextFieldColors 之类
            if not re.search(r"^\s*import\s+" + re.escape(imp) + r"\s*$", text, re.M):
                continue
            if rel in rule.get("allow", []):
                continue
            seen[imp].add(rel)
            if rel in baseline_map.get(imp, []):
                continue
            errors.append("%s：%s（import %s）" % (rel, rule["message"], imp))
    for imp, files in baseline_map.items():
        for rel in files:
            if rel not in seen.get(imp, set()):
                notes.append("基线可清理：%s 已不再引用 %s" % (rel, imp))


def check_file_lines(policy, baseline, errors, notes):
    limit = policy.get("maxFileLines")
    if not limit:
        return
    baseline_map = baseline.get("fileLines", {})
    for rel in iter_sources(policy.get("sourceRoots", [])):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            count = len(f.readlines())
        recorded = baseline_map.get(rel)
        if count <= limit:
            if recorded is not None:
                notes.append("基线可清理：%s 已降到 %d 行（≤ %d）" % (rel, count, limit))
            continue
        if recorded is not None and count <= recorded:
            continue
        if recorded is not None:
            errors.append("%s：%d 行已超出基线 %d 行（棘轮只许降）" % (rel, count, recorded))
        else:
            errors.append("%s：%d 行超出上限 %d 行" % (rel, count, limit))


def check_strings_parity(policy, errors):
    cfg = policy.get("stringsParity")
    if not cfg:
        return

    def names(rel):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            return set(re.findall(r'<string name="([^"]+)"', f.read()))

    zh, en = names(cfg["zh"]), names(cfg["en"])
    for missing in sorted(en - zh):
        errors.append("%s：英文有而中文缺 %s" % (cfg["zh"], missing))
    for missing in sorted(zh - en):
        errors.append("%s：中文有而英文缺 %s" % (cfg["en"], missing))


def sync_baseline(policy, baseline):
    """把当前超限文件的行数写回基线。

    正常情况基线只许缩减；确因新增功能导致既有文件变长时，手动跑一次同步把基线抬到当前值，
    避免门禁对「已声明的增长」反复报警（新增长的文件仍需当次同步才放过）。
    """
    limit = policy.get("maxFileLines")
    if not limit:
        return
    file_lines = baseline.setdefault("fileLines", {})
    for rel in iter_sources(policy.get("sourceRoots", [])):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            count = len(f.readlines())
        if count > limit:
            file_lines[rel] = count
        elif rel in file_lines:
            del file_lines[rel]
    with open(os.path.join(ROOT, BASELINE_REL), "w", encoding="utf-8") as f:
        json.dump(baseline, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("[OK] 已同步文件行数基线（%d 个文件超限）" % len(file_lines))


def main():
    policy = load_json(POLICY_REL)
    baseline = load_json(BASELINE_REL)
    if "--sync-baseline" in sys.argv:
        sync_baseline(policy, baseline)
        return 0
    errors, notes = [], []
    check_banned_imports(policy, baseline, errors, notes)
    check_file_lines(policy, baseline, errors, notes)
    check_strings_parity(policy, errors)
    for note in notes:
        print("[WARN] %s" % note)
    if errors:
        print("[FAIL] 架构门禁未通过（%d 项）：" % len(errors))
        for err in errors:
            print("  - %s" % err)
        return 1
    print("[OK] 架构门禁通过：无新增禁用组件、文件行数未越基线、双语 strings 一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
