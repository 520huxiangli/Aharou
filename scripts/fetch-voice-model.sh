#!/usr/bin/env bash
# 下载离线语音模型并解压进 assets/voice_models/。
#
# 模型不进 git：单个 onnx 最大 154MB，超过 GitHub 单文件 100MB 上限。本地与 CI 都按需拉取。
# 只取运行时真正需要的四个文件（见 VoiceModels.ASR_ZH.requiredFiles），
# 压缩包里的示例音频与文档不进 APK。
set -euo pipefail

MODEL="sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"
DEST="app/src/main/assets/voice_models/$MODEL"
FILES=(encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt)
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL.tar.bz2"

ready=1
for f in "${FILES[@]}"; do
    [ -f "$DEST/$f" ] || ready=0
done
if [ "$ready" -eq 1 ]; then
    echo "语音模型已就位，跳过：$DEST"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "下载语音模型（约 127MB）：$URL"
curl -fL --retry 3 --retry-delay 2 -o "$work/model.tar.bz2" "$URL"

echo "解压并挑选运行所需文件"
mkdir -p "$DEST"
# 用 python 解 bz2：容器精灵里没有 bzip2 命令，python3 两边都有；只抽所需文件，不整包展开
python3 - "$work/model.tar.bz2" "$DEST" "${FILES[@]}" <<'PY'
import os, sys, tarfile

archive, dest, want = sys.argv[1], sys.argv[2], set(sys.argv[3:])
found = set()
with tarfile.open(archive, "r:bz2") as tf:
    for member in tf:
        name = os.path.basename(member.name)
        if not member.isfile() or name not in want or name in found:
            continue
        with tf.extractfile(member) as src, open(os.path.join(dest, name), "wb") as out:
            while chunk := src.read(1 << 20):
                out.write(chunk)
        found.add(name)
missing = want - found
if missing:
    sys.exit("压缩包里找不到：" + "、".join(sorted(missing)))
PY

echo "已写入 $DEST："
ls -l "$DEST"
