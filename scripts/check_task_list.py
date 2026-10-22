#!/usr/bin/env python3
"""收尾前检查任务清单是否全部完成。

用途：AI 汇报「做完了」之前必须先跑这个脚本。清单存在 `.aharou/task-list.md`，
未勾选项（`- [ ]`）就是没做完的事——脚本非零退出，此时不许说完成。

清单格式（Markdown 复选框，一项一行）：

    - [x] 已完成的事
    - [ ] 还没做的事

清单文件不存在时视为通过（没有待办可查）。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TASK_FILE = ROOT / ".aharou" / "task-list.md"


def main() -> int:
    if not TASK_FILE.is_file():
        print("[OK] 没有任务清单文件（.aharou/task-list.md），视为无待办")
        return 0
    text = TASK_FILE.read_text(encoding="utf-8")
    pending = [
        line.strip()[5:].strip()
        for line in text.splitlines()
        if line.lstrip().startswith("- [ ]")
    ]
    if not pending:
        print("[OK] 任务清单已全部勾完")
        return 0
    print(f"[FAIL] 还有 {len(pending)} 项没做完：")
    for item in pending:
        print(f"  - {item}")
    print("\n未完成之前不许汇报「全部完成」。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
