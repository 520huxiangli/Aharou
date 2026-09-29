#!/usr/bin/env python3
"""官方技能自检：frontmatter、命名、验收用例、市场配置与文档是否同步。

与 check_migrations.py 同样定位：推送前 / CI 跑一遍。改技能、改验收用例、
改市场配置或改文档时漏了同步，在这里拦下来，而不是等用户装到坏技能。

只校验 `skills/` 下我们自己写的技能（市场里第三方仓库不在这里）。
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKILLS_DIR = os.path.join(ROOT, "skills")
EVALS_DIR = os.path.join(ROOT, "evals", "skills")
DOCS_SKILLS = os.path.join(ROOT, "docs-site", "docs", "guide", "skills.md")
MARKET_FILES = (
    os.path.join(ROOT, "data", "skills.json"),
    os.path.join(ROOT, "app", "src", "main", "assets", "skills-market.json"),
)

NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
FRONTMATTER_RE = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n", re.S)

# description 是 AI 判断「要不要加载这个技能」的唯一依据，太短等于没写
MIN_DESCRIPTION_CHARS = 30
MIN_BODY_LINES = 20
EVAL_SECTIONS = ("## 应该触发", "## 不该触发", "## 验收点")


def parse_skill(path: str):
    """返回 (frontmatter 字典, 正文)；没有 frontmatter 时返回 (None, 全文)。"""
    text = open(path, encoding="utf-8").read()
    m = FRONTMATTER_RE.match(text)
    if not m:
        return None, text
    fm_raw = m.group(1)
    fm = {}
    for key in ("name", "display_name", "description"):
        hit = re.search(rf"^{key}:\s*(.+?)(?=\r?\n[a-zA-Z_]+:|\Z)", fm_raw, re.S | re.M)
        if hit:
            fm[key] = " ".join(hit.group(1).split()).strip().strip('"')
    return fm, text[m.end():]


def check_market(errors: list[str]) -> None:
    entries = []
    for path in MARKET_FILES:
        rel = os.path.relpath(path, ROOT)
        if not os.path.exists(path):
            errors.append(f"找不到市场配置 {rel}")
            continue
        try:
            blob = json.load(open(path, encoding="utf-8"))
        except json.JSONDecodeError as exc:
            errors.append(f"{rel} 不是合法 JSON：{exc}")
            continue
        sources = blob.get("sources", blob)
        aharou = sources.get("aharou")
        if not aharou:
            errors.append(f"{rel} 里没有 aharou 官方源")
            continue
        if aharou.get("kind") != "directory" or aharou.get("path") != "skills":
            errors.append(f"{rel} 的 aharou 源应指向 kind=directory / path=skills")
        entries.append(aharou)
    if len(entries) == 2 and entries[0] != entries[1]:
        errors.append("data/skills.json 与 app/src/main/assets/skills-market.json 的 aharou 源不一致")


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []

    if not os.path.isdir(SKILLS_DIR):
        print("[FAIL] 找不到 skills/ 目录")
        return 1
    dirs = sorted(d for d in os.listdir(SKILLS_DIR) if os.path.isdir(os.path.join(SKILLS_DIR, d)))
    if not dirs:
        print("[FAIL] skills/ 下没有任何技能")
        return 1

    docs = open(DOCS_SKILLS, encoding="utf-8").read() if os.path.exists(DOCS_SKILLS) else ""
    if not docs:
        warnings.append(f"读不到 {os.path.relpath(DOCS_SKILLS, ROOT)}，跳过展示名同步检查")

    names: list[str] = []
    for d in dirs:
        skill_md = os.path.join(SKILLS_DIR, d, "SKILL.md")
        if not os.path.isfile(skill_md):
            errors.append(f"skills/{d}/ 里没有 SKILL.md")
            continue

        fm, body = parse_skill(skill_md)
        if fm is None:
            errors.append(f"skills/{d}/SKILL.md 没有 frontmatter")
            continue

        name = fm.get("name", "")
        if not name:
            errors.append(f"skills/{d}/SKILL.md 缺 name")
        elif name != d:
            errors.append(f"skills/{d}/SKILL.md 的 name「{name}」与目录名不一致")
        elif not NAME_RE.match(name):
            errors.append(f"技能名「{name}」不合规（要英文小写加连字符）")
        names.append(name or d)

        display = fm.get("display_name", "")
        if not display:
            errors.append(f"{d}：缺 display_name（技能市场里显示的中文名）")
        elif docs and display not in docs:
            errors.append(f"{d}：display_name「{display}」没出现在 docs-site 的技能文档里，文档漏同步")

        desc = fm.get("description", "")
        if len(desc) < MIN_DESCRIPTION_CHARS:
            errors.append(f"{d}：description 只有 {len(desc)} 字，太短（AI 靠它判断要不要加载）")

        filled = [line for line in body.splitlines() if line.strip()]
        if len(filled) < MIN_BODY_LINES:
            errors.append(f"{d}：正文只有 {len(filled)} 行非空内容，太薄")
        if not re.search(r"^## ", body, re.M):
            errors.append(f"{d}：正文没有二级小节")

        eval_path = os.path.join(EVALS_DIR, f"{d}.md")
        if not os.path.isfile(eval_path):
            errors.append(f"{d}：缺验收用例 evals/skills/{d}.md")
        else:
            text = open(eval_path, encoding="utf-8").read()
            for section in EVAL_SECTIONS:
                if section not in text:
                    errors.append(f"evals/skills/{d}.md 缺「{section}」小节")

        # 验收用例放技能目录里会被一起打包发给用户，只能放 evals/
        for stray in ("evals.md", "EVALS.md", "test.md"):
            if os.path.exists(os.path.join(SKILLS_DIR, d, stray)):
                warnings.append(f"{d}：技能目录里有 {stray}，会随技能打包，建议挪到 evals/skills/")

    check_market(errors)

    for w in warnings:
        print(f"[WARN] {w}")
    if errors:
        print(f"[FAIL] 官方技能自检未通过（{len(errors)} 项）")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"[OK] 官方技能自检通过：{len(names)} 个技能，验收用例齐全")
    print(f"  - {', '.join(names)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
