#!/usr/bin/env bash
# 把 CI 编出的 libllama_jni.so 取回放进 jniLibs。
#
# 为什么不在本机编：容器是 aarch64，NDK 只带 linux-x86_64 的 LLVM 工具链，
# clang 根本跑不起来。产物由 .github/workflows/build-llama-android.yml 出。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${AHAROU_REPO:-520huxiangli/Aharou}"
OUT_ARM="$ROOT/app/src/_armJniLibs/arm64-v8a"
OUT_X86="$ROOT/app/src/_x86JniLibs/x86_64"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

TOKEN="$(python3 - "$ROOT" <<'PY'
import sys
sys.path.insert(0, sys.argv[1] + "/scripts/push")
from _gh import get_token
print(get_token()[0])
PY
)"

download() {
    local abi="$1" dest="$2" id
    id="$(curl -s -m 30 -H "Authorization: Bearer $TOKEN" \
        "https://api.github.com/repos/$REPO/actions/artifacts?name=llama-jni-$abi&per_page=20" \
        | python3 -c 'import sys,json; d=json.load(sys.stdin); a=[x for x in d.get("artifacts",[]) if not x.get("expired")]; print(a[0]["id"] if a else "")')"
    if [ -z "$id" ]; then
        echo "找不到 $abi 的构建产物：先跑一次 build-llama-android.yml" >&2
        exit 1
    fi
    curl -sL -m 300 -H "Authorization: Bearer $TOKEN" \
        "https://api.github.com/repos/$REPO/actions/artifacts/$id/zip" -o "$TMP/$abi.zip"
    rm -rf "$TMP/$abi"
    mkdir -p "$TMP/$abi"
    unzip -o -q "$TMP/$abi.zip" -d "$TMP/$abi"
    mkdir -p "$dest"
    cp "$TMP/$abi/libllama_jni.so" "$dest/libllama_jni.so"
    ls -l "$dest/libllama_jni.so"
}

download arm64-v8a "$OUT_ARM"
download x86_64 "$OUT_X86"
echo "已放好；重新构建 APK 才会打进包。"
