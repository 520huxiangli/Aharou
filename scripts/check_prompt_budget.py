#!/usr/bin/env python3
"""提示词常驻预算门禁：顶层 `NN-*.md` 每轮都会进系统提示，涨了就白烧 token。

检查两项：
- 单个常驻片段不得超过 PER_FILE_LIMIT 字符；
- 常驻片段合计不得超过 TOTAL_LIMIT 字符。

`agent/` 下的片段按场景注入，不计入常驻预算，只打印规模供参考。

用法：python3 scripts/check_prompt_budget.py
退出码：0 通过 / 1 超限（超限项逐条打印，便于直接定位要拆哪个文件）。
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROMPTS = ROOT / "app/src/main/assets/prompts"

# 上限按当前规模留出余量后固定：涨到这些数说明该拆片段或删冗余，
# 而不是每次发版顺手加几句进去。
PER_FILE_LIMIT = 14000
TOTAL_LIMIT = 24000


def char_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8"))


def main() -> int:
    if not PROMPTS.is_dir():
        print(f"[FAIL] 找不到提示词目录：{PROMPTS}")
        return 1

    always = sorted(PROMPTS.glob("*.md"))
    if not always:
        print("[FAIL] 常驻提示词一个都没有，检查路径是否写错")
        return 1

    problems: list[str] = []
    total = 0
    print("常驻片段（每轮注入）：")
    for path in always:
        size = char_count(path)
        total += size
        flag = ""
        if size > PER_FILE_LIMIT:
            flag = f"  [FAIL] 超过单文件上限 {PER_FILE_LIMIT}"
            problems.append(f"{path.name} 有 {size} 字符，超过单文件上限 {PER_FILE_LIMIT}")
        print(f"  {size:7d}  {path.name}{flag}")
    print(f"  {total:7d}  合计")

    if total > TOTAL_LIMIT:
        problems.append(f"常驻片段合计 {total} 字符，超过总量上限 {TOTAL_LIMIT}")

    on_demand = sorted((PROMPTS / "agent").glob("*.md"))
    if on_demand:
        on_demand_total = sum(char_count(p) for p in on_demand)
        print(f"\n按需片段：{len(on_demand)} 个，合计 {on_demand_total} 字符（不计入常驻预算）")

    if problems:
        print("\n[FAIL] 提示词预算超限：")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(f"\n[OK] 常驻 {total} / {TOTAL_LIMIT} 字符，单文件均未超过 {PER_FILE_LIMIT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
