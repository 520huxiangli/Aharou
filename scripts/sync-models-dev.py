#!/usr/bin/env python3
"""CI 定时执行：抓取 models.dev 的完整模型元数据并保存到 data/models.json。

仅抓取与保存全量元数据，不触碰 app 内置的 api.official.json。
网络或解析失败以非零退出，不覆盖旧文件。
"""

import json
import os
import sys
import urllib.request

MODELS_DEV_URL = "https://models.dev/api.json"
TARGET_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "data", "models.json")
)
UA = "aharou-models-sync"


def fetch_remote() -> dict:
    print(f"拉取 {MODELS_DEV_URL} ...")
    req = urllib.request.Request(MODELS_DEV_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    try:
        remote = fetch_remote()
    except Exception as e:
        print(f"拉取或解析 models.dev 失败: {e}")
        return 1

    if not isinstance(remote, dict) or not remote:
        print("远端返回非字典或空数据，放弃写入")
        return 1

    os.makedirs(os.path.dirname(TARGET_PATH), exist_ok=True)
    with open(TARGET_PATH, "w", encoding="utf-8") as f:
        json.dump(remote, f, ensure_ascii=False, separators=(",", ":"))

    total_models = sum(
        len(v.get("models", {}))
        for v in remote.values()
        if isinstance(v, dict)
    )
    print(f"已成功同步完整元数据至 {TARGET_PATH}")
    print(f"包含 {len(remote)} 个供应商，共计 {total_models} 个模型")
    return 0


if __name__ == "__main__":
    sys.exit(main())
