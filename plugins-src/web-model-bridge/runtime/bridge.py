#!/usr/bin/env python3
"""网页模型接入：托管 deeperseeker。

宿主通过 NDJSON（每行一个 JSON）与插件进程通信：带 id 与 method 的是请求，
必须回 {"id": ..., "result": ...}。本脚本主线程只做应答，真正的安装在后台线程做，
所以「安装耗时」不会让宿主的握手超时。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("AICODE_DATA_DIR") or os.path.join(BASE_DIR, "data")
SOURCE_DIR = os.path.join(DATA_DIR, "deeperseeker")
VENV_DIR = os.path.join(DATA_DIR, "venv")
PY = os.path.join(VENV_DIR, "bin", "python")
PORT = int(os.environ.get("DEEPERSEEKER_PORT", "4000"))

MIRRORS = [
    "https://gh-proxy.com/https://github.com/AmanCode22/deeperseeker/archive/refs/heads/main.zip",
    "https://ghproxy.net/https://github.com/AmanCode22/deeperseeker/archive/refs/heads/main.zip",
]

PIP_INDEXES = [
    "https://pypi.tuna.tsinghua.edu.cn/simple",
    "https://mirrors.aliyun.com/pypi/simple/",
    "https://pypi.org/simple",
]
DEPS_MARK = ".deps-ok"
# 固定本地 API Key：App 里填这个值就能连上，不用让用户去扒它生成的随机 key
LOCAL_API_KEY = "aharou-local"
ADMIN_PASSWORD = "aharou-admin"

_lock = threading.Lock()
_started = False


def log(msg: str) -> None:
    print(f"[web-model-bridge] {msg}", file=sys.stderr, flush=True)


def read_os_release() -> str:
    try:
        with open("/etc/os-release", encoding="utf-8", errors="ignore") as f:
            return f.read().lower()
    except Exception:
        return ""


def ensure_source() -> bool:
    if os.path.isfile(os.path.join(SOURCE_DIR, "app.py")):
        return True
    os.makedirs(DATA_DIR, exist_ok=True)
    archive = os.path.join(DATA_DIR, "deeperseeker.zip")
    for url in MIRRORS:
        try:
            log(f"下载源码 {url}")
            urllib.request.urlretrieve(url, archive)
            if os.path.getsize(archive) > 1024:
                break
        except Exception as exc:
            log(f"下载失败：{exc}")
    if not os.path.isfile(archive) or os.path.getsize(archive) <= 1024:
        return False
    try:
        with zipfile.ZipFile(archive) as z:
            z.extractall(DATA_DIR)
    except Exception as exc:
        log(f"解压失败：{exc}")
        return False
    for name in os.listdir(DATA_DIR):
        if name.startswith("deeperseeker-") and os.path.isdir(os.path.join(DATA_DIR, name)):
            os.rename(os.path.join(DATA_DIR, name), SOURCE_DIR)
            break
    os.remove(archive)
    ok = os.path.isfile(os.path.join(SOURCE_DIR, "app.py"))
    log(f"源码就绪={ok}")
    return ok


def ensure_venv() -> bool:
    mark = os.path.join(VENV_DIR, DEPS_MARK)
    # 只有依赖装完（标记存在）才算就绪：上次只建了 venv 就失败的情况要能重试
    if os.path.isfile(PY) and os.path.isfile(mark):
        return True
    # patchright 只发 glibc 的 wheel，musl（Alpine）上根本拉不到，提前说清楚
    if "alpine" in read_os_release():
        log("当前容器是 Alpine（musl），patchright 只提供 glibc 版，无法安装；"
            "请在设置 → 容器里换成 Ubuntu 或 Debian 镜像后再试")
        return False
    if not os.path.isfile(PY):
        log("创建虚拟环境")
        subprocess.run([sys.executable, "-m", "venv", VENV_DIR], check=False)
        if not os.path.isfile(PY):
            return False
    req = os.path.join(SOURCE_DIR, "requirements.txt")
    if not os.path.isfile(req):
        open(mark, "w").write("1")
        return True
    subprocess.run([PY, "-m", "pip", "install", "-q", "--upgrade", "pip"], check=False)
    for index in PIP_INDEXES:
        log(f"诊断：探测源 {index}")
        probe = subprocess.run(
            [PY, "-m", "pip", "download", "--no-deps", "-d", os.path.join(DATA_DIR, "probe"),
             "-i", index, "--timeout", "20", "patchright==1.64.4"],
            cwd=SOURCE_DIR, capture_output=True, text=True,
        )
        if probe.returncode != 0:
            tail = (probe.stderr or probe.stdout or "").strip().splitlines()[-2:]
            log(f"源不可用 {index}：{' | '.join(tail)}")
            continue
        log(f"源可用 {index}，开始装依赖")
        done = subprocess.run(
            [PY, "-m", "pip", "install", "-i", index, "--timeout", "60", "--retries", "3", "-r", req],
            cwd=SOURCE_DIR, capture_output=True, text=True,
        )
        if done.returncode == 0:
            open(mark, "w").write("1")
            return True
        tail = (done.stderr or done.stdout or "").strip().splitlines()[-3:]
        log(f"装依赖失败 {index}：{' | '.join(tail)}")
    return False


def write_env() -> None:
    """固定本地密钥与管理密码：换机器/重装都不用重新找 key。"""
    env_file = os.path.join(SOURCE_DIR, ".env")
    if os.path.isfile(env_file):
        return
    with open(env_file, "w", encoding="utf-8") as f:
        f.write(
            f"DEEPSEEKER_API_KEY={LOCAL_API_KEY}\n"
            f"DEEPSEEKER_ADMIN_USER=admin\n"
            f"DEEPSEEKER_ADMIN_PASSWORD={ADMIN_PASSWORD}\n"
            f"HOST=127.0.0.1\n"
            f"PORT={PORT}\n"
        )


def healthy() -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False


def boot() -> None:
    global _started
    with _lock:
        if _started:
            return
        _started = True
    if healthy():
        log("服务已在运行")
        return
    if not ensure_source() or not ensure_venv():
        log("安装失败，服务无法启动")
        return
    write_env()
    log(f"启动 deeperseeker，端口 {PORT}")
    subprocess.run([PY, "app.py"], cwd=SOURCE_DIR, env={**os.environ, "PORT": str(PORT)})
    log("deeperseeker 已退出")


def reply(mid, result=None, error=None) -> None:
    payload = {"id": mid}
    if error is not None:
        payload["error"] = error
    else:
        payload["result"] = result if result is not None else {}
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def rpc_loop() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        mid = msg.get("id")
        method = msg.get("method")
        if mid is None:
            continue  # 通知，无需应答
        if method in ("plugin.disabled", "plugin.uninstalled"):
            reply(mid, {"ok": True})
            continue
        # 任何一次握手都顺手拉起后台服务，保证只装一次
        threading.Thread(target=boot, daemon=True).start()
        reply(mid, {"ok": True, "service": "deeperseeker", "port": PORT})


def main() -> None:
    threading.Thread(target=boot, daemon=True).start()
    rpc_loop()


if __name__ == "__main__":
    main()
