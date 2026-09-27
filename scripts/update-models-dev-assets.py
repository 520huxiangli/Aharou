#!/usr/bin/env python3
"""发布前手动执行：更新内置 models.dev 模型元数据快照（app/src/main/assets/api.official.json）。

api.official.json 的结构：
- 仅保留内置快照已有的 provider，不引入新 provider；这些 provider 下的新模型可直接加入。
- 每个 provider 节点除 models 外，还写入 name / type / baseUrl 字段，供「从预设库添加」面板展示与预填。
- 网络拉取或解析失败时打印错误并以非零退出，绝不改动现有文件。
用法：python3 scripts/update-models-dev-assets.py
"""

import json
import os
import sys
import urllib.request

MODELS_DEV_URL = "https://models.dev/api.json"
ASSET_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "api.official.json")
)
UA = "aharou-assets-updater"

# 官方 provider 的默认 Base URL（models.dev 的 api 字段缺失时回退）。
DEFAULT_BASE_URL = {
    "openai": "https://api.openai.com/",
    "anthropic": "https://api.anthropic.com/",
    "google": "https://generativelanguage.googleapis.com/",
}

# 每 provider 覆盖项：models.dev 的 api/npm 字段有时与 App 期望的协议/端点不匹配，
# 这里在生成预设时强制覆盖（baseUrl 直接采用，不经过 normalize_base_url 的 /v1 裁剪）。
#   - xai：models.dev api 缺省落成 openai 官方地址，实际应为 xAI 官方端点。
#   - minimax/minimax-cn 系列：npm 是 anthropic 只是 MiniMax 提供的兼容变体，
#     默认仍按 OpenAI 兼容端点接入（App 会拼 /v1/chat/completions）。
PROVIDER_OVERRIDES = {
    "xai": {"baseUrl": "https://api.x.ai/"},
    "minimax": {"type": "OPENAI", "baseUrl": "https://api.minimax.io/"},
    "minimax-cn": {"type": "OPENAI", "baseUrl": "https://api.minimaxi.com/"},
    "minimax-coding-plan": {"type": "OPENAI", "baseUrl": "https://api.minimax.io/"},
    "minimax-cn-coding-plan": {"type": "OPENAI", "baseUrl": "https://api.minimaxi.com/"},
}


def trim_model(mv: dict) -> dict:
    out = {}
    for key in ("id", "name", "tool_call", "temperature", "reasoning", "reasoning_options", "limit"):
        if key in mv:
            out[key] = mv[key]
    modalities = mv.get("modalities", {})
    if isinstance(modalities, dict) and "input" in modalities:
        out["modalities"] = {"input": modalities["input"]}
    if "cost" in mv:
        out["cost"] = mv["cost"]
    return out


def map_type(npm: str) -> str:
    """由 models.dev 的 npm 字段映射协议类型（OPENAI / ANTHROPIC / GEMINI）。"""
    if not npm:
        return "OPENAI"
    n = npm.lower()
    if "anthropic" in n:
        return "ANTHROPIC"
    if "google" in n:
        return "GEMINI"
    return "OPENAI"


def normalize_base_url(raw: str, pid: str) -> str:
    """规整 Base URL：去末尾斜杠；OPENAI 兼容路径若带 /v1 则去掉，
    因为 App 会自行拼接 v1/chat/completions，避免出现 /v1/v1/...。"""
    if not raw:
        return DEFAULT_BASE_URL.get(pid, "https://api.openai.com/")
    url = raw.strip().rstrip("/")
    if url.endswith("/v1"):
        url = url[: -len("/v1")].rstrip("/")
    return url


def build_provider_meta(pid: str, pv: dict) -> dict:
    """计算单个 provider 的展示元数据（name / type / baseUrl），写入 api.official.json 的 provider 节点。"""
    npm = pv.get("npm")
    override = PROVIDER_OVERRIDES.get(pid, {})
    base_url = override.get("baseUrl") if "baseUrl" in override else normalize_base_url(pv.get("api"), pid)
    return {
        "name": pv.get("name") or pid,
        "type": override.get("type", map_type(npm)),
        "baseUrl": base_url,
    }


def fetch_remote() -> dict:
    print(f"拉取 {MODELS_DEV_URL} ...")
    req = urllib.request.Request(MODELS_DEV_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    if not os.path.isfile(ASSET_PATH):
        print(f"错误：未找到内置快照 {ASSET_PATH}")
        return 1

    with open(ASSET_PATH, encoding="utf-8") as f:
        current = json.load(f)
    providers = list(current.keys())

    try:
        remote = fetch_remote()
    except Exception as e:
        print(f"拉取/解析失败（{e}），跳过更新，保持现有文件")
        return 1

    if not isinstance(remote, dict):
        print("远端数据格式异常，跳过更新，保持现有文件")
        return 1

    # 1) 更新 api.official.json（仅保留已有 provider，合并新模型，并写入 provider 展示元数据）
    merged = {}
    for pid in providers:
        net_models = remote.get(pid, {}).get("models", {})
        models = {
            mid: trim_model(mv)
            for mid, mv in net_models.items()
            if isinstance(mv, dict)
        }
        for mid, mv in current.get(pid, {}).get("models", {}).items():
            if mid not in models:
                models[mid] = mv
        remote_pv = remote.get(pid, {})
        meta = build_provider_meta(pid, remote_pv) if isinstance(remote_pv, dict) else {}
        merged[pid] = {**meta, "models": models}

    old_total = sum(len(p.get("models", {})) for p in current.values())
    new_total = sum(len(p.get("models", {})) for p in merged.values())
    if new_total == 0:
        print("合并结果为空，放弃更新")
        return 1

    with open(ASSET_PATH, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, separators=(",", ":"))
    print(f"已更新 {ASSET_PATH}")
    for pid in providers:
        old = len(current.get(pid, {}).get("models", {}))
        new = len(merged.get(pid, {}).get("models", {}))
        flag = "（新增模型）" if new > old else ""
        print(f"  {pid}: {old} -> {new} {flag}")
    print(f"合计：{old_total} -> {new_total} 个模型")
    return 0


if __name__ == "__main__":
    sys.exit(main())
