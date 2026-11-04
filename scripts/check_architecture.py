#!/usr/bin/env python3
"""架构门禁：禁用组件 import、单文件行数棘轮、双语 strings 一致性。

规则的事实源是根目录 `architecture-policy.json`；存量违规登记在 `.architecture-baseline.json`，
基线只许缩减——修好一处就删掉对应条目，脚本会在 [WARN] 里提示可清理项，新增违规一律拦截。

退出码 0 通过 / 1 有违规，供 CI 与 Gradle preBuild 复用。

模式：默认跑三项检查；`--check-ratchet` 额外与 origin/master 上的基线比对（CI 用，防止一条
`--sync-baseline` 把棘轮抬高）；`--sync-baseline` 显式写回当前行数（本地手动操作）。
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POLICY_REL = "architecture-policy.json"
BASELINE_REL = ".architecture-baseline.json"


def load_json(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return json.load(f)


def _require(policy, key, errors, what):
    """取必需配置项；缺键直接记 FAIL。

    早期写法是 `if not X: return`，于是把 `maxFileLines` 写成 `max_file_lines` 这种拼写错误
    会让整项检查静默跳过，脚本还照样打印「通过」——门禁变成摆设。
    """
    if key not in policy:
        errors.append("architecture-policy.json 缺少 %s 配置（%s 无法执行）" % (key, what))
        return None
    return policy[key]


def _import_hits(text, imp):
    r"""找出文件里对 imp 的引用行号（1 起）。

    三种写法都要覆盖，否则换个写法就能绕过去：
      - `import X` / `import X as Y` / `import X.*`（尾部别名与星号导入）
      - 正文里的全限定调用 `X(...)`：不经 import 语句，只匹配 import 行完全看不见。
    `(?<![\w.])` 防止把 `foo.X(` 这种同后缀名字误判成 imp。
    """
    import_line = re.compile(
        r"^[ \t]*import\s+" + re.escape(imp) + r"(\.\*)?(\s+as\s+\w+)?[ \t]*$", re.M
    )
    hits = [text.count("\n", 0, m.start()) + 1 for m in import_line.finditer(text)]
    call = re.search(r"(?<![\w.])" + re.escape(imp) + r"\s*\(", text)
    if call:
        hits.append(text.count("\n", 0, call.start()) + 1)
    return sorted(hits)


def iter_sources(roots):
    """按源码根目录遍历 .kt，产出相对仓库根的 POSIX 路径。"""
    for root in roots:
        for dirpath, _, filenames in os.walk(os.path.join(ROOT, root)):
            for name in sorted(filenames):
                if name.endswith(".kt"):
                    abs_path = os.path.join(dirpath, name)
                    yield os.path.relpath(abs_path, ROOT).replace(os.sep, "/")


def check_banned_imports(policy, baseline, errors, notes):
    rules = _require(policy, "bannedImports", errors, "禁用 import 检查")
    if rules is None:
        return
    baseline_map = baseline.get("bannedImports", {})
    seen = {rule["import"]: set() for rule in rules}
    for rel in iter_sources(policy.get("sourceRoots", [])):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            text = f.read()
        for rule in rules:
            imp = rule["import"]
            hits = _import_hits(text, imp)
            if not hits:
                continue
            if rel in rule.get("allow", []):
                continue
            seen[imp].add(rel)
            if rel in baseline_map.get(imp, []):
                continue
            errors.append("%s:%d：%s（%s）" % (rel, hits[0], rule["message"], imp))
    for imp, files in baseline_map.items():
        for rel in files:
            if rel not in seen.get(imp, set()):
                notes.append("基线可清理：%s 已不再引用 %s" % (rel, imp))


def check_file_lines(policy, baseline, errors, notes):
    limit = _require(policy, "maxFileLines", errors, "文件行数检查")
    if limit is None:
        return
    if not isinstance(limit, int) or limit <= 0:
        errors.append("architecture-policy.json 的 maxFileLines 必须是正整数（当前 %r）" % (limit,))
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
    cfg = _require(policy, "stringsParity", errors, "双语 strings 一致性检查")
    if cfg is None:
        return
    if not cfg.get("zh") or not cfg.get("en"):
        errors.append("architecture-policy.json 的 stringsParity 缺少 zh / en 路径")
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
    if not isinstance(limit, int) or limit <= 0:
        print("[FAIL] architecture-policy.json 缺少有效的 maxFileLines，无法同步基线")
        return 1
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
    return 0


def check_ratchet(errors, ref="origin/master"):
    """基线只许缩：与 ref 上的基线逐项比对。

    `--sync-baseline` 一条命令就能把棘轮抬到当前行数，本地保留它作为显式操作没问题；
    但 CI 不比对就拦不住——抬完之后门禁照样通过，棘轮也就形同虚设。
    只对「数值上涨」与「新增豁免登记」报错，收紧与删除一律放行。
    """
    proc = subprocess.run(
        ["git", "-C", ROOT, "show", "%s:%s" % (ref, BASELINE_REL)],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        # ref 上还没有基线文件（首次引入）当空基线；其余失败（载不全、无该 ref）必须报错，
        # 不能静默放行——那样 CI 会在「读不到基准」时变成空跑。
        if "does not exist" in proc.stderr or "exists on disk, but not in" in proc.stderr:
            return
        errors.append("无法从 %s 读取 %s：%s" % (ref, BASELINE_REL, proc.stderr.strip()))
        return
    old = json.loads(proc.stdout)
    new = load_json(BASELINE_REL)
    for rel, count in new.get("fileLines", {}).items():
        prev = old.get("fileLines", {}).get(rel)
        if prev is not None and count > prev:
            errors.append("%s：基线行数上涨 %d -> %d（棘轮只许降）" % (rel, prev, count))
    old_banned = old.get("bannedImports", {})
    for imp, files in new.get("bannedImports", {}).items():
        for rel in sorted(set(files) - set(old_banned.get(imp, []))):
            errors.append("%s：新增禁用 import 豁免登记（%s）" % (rel, imp))


def main():
    policy = load_json(POLICY_REL)
    baseline = load_json(BASELINE_REL)
    if "--sync-baseline" in sys.argv:
        return sync_baseline(policy, baseline)
    ratchet_only = "--check-ratchet" in sys.argv
    errors, notes = [], []
    if ratchet_only:
        check_ratchet(errors)
    else:
        # 没源码目录可扫时三项检查全部空跑，和缺键一样得 FAIL。
        if not policy.get("sourceRoots"):
            errors.append("architecture-policy.json 缺少 sourceRoots（三项检查都会空跑）")
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
    if ratchet_only:
        print("[OK] 架构基线未上涨")
    else:
        print("[OK] 架构门禁通过：无新增禁用组件、文件行数未越基线、双语 strings 一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
