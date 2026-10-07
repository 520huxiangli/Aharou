#!/usr/bin/env python3
"""校验 proot 容器运行时资产与 app/src/main/assets/proot-runtime.json 是否一致。

换了 .so 却没更新描述文件（或反之）时非零退出——避免 APK 里跑着一个"来历不明"的 proot。
用法：python3 scripts/check_proot_assets.py
"""

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/main/assets/proot-runtime.json"
REQUIRED_ROLES = {"proot-executable", "loader-64", "loader-32", "talloc", "shmem"}


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if not MANIFEST.exists():
        print(f"[FAIL] 找不到描述文件：{MANIFEST.relative_to(ROOT)}")
        return 1

    try:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"[FAIL] 描述文件不是合法 JSON：{e}")
        return 1

    files = manifest.get("files") or []
    if not files:
        print("[FAIL] 描述文件里没有任何 files 条目")
        return 1

    problems: list[str] = []
    seen: dict[str, set[str]] = {}

    for entry in files:
        rel = entry.get("repoPath", "")
        abi = entry.get("abi", "")
        role = entry.get("role", "")
        path = ROOT / rel

        if not path.exists():
            problems.append(f"文件不存在：{rel}")
            continue

        actual_size = path.stat().st_size
        if actual_size != entry.get("sizeBytes"):
            problems.append(f"{rel} 大小不符：描述 {entry.get('sizeBytes')} != 实际 {actual_size}")

        actual_sha = sha256_of(path)
        if actual_sha != entry.get("sha256"):
            problems.append(f"{rel} 哈希不符：描述 {entry.get('sha256')} != 实际 {actual_sha}")

        seen.setdefault(abi, set()).add(role)

    for abi, roles in sorted(seen.items()):
        missing = REQUIRED_ROLES - roles
        if missing:
            problems.append(f"{abi} 缺少角色：{', '.join(sorted(missing))}")

    expected_abis = {"arm64-v8a", "x86_64"}
    for abi in sorted(expected_abis - set(seen)):
        problems.append(f"缺少 ABI：{abi}")

    if problems:
        print("[FAIL] proot 资产与描述文件不一致：")
        for p in problems:
            print(f"  - {p}")
        print("\n改了 .so 就同步更新 app/src/main/assets/proot-runtime.json（大小与哈希）。")
        return 1

    print(f"[OK] proot 资产校验通过：{len(files)} 个文件，ABI {'/'.join(sorted(seen))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
