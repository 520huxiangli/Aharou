#!/usr/bin/env bash
# 下载离线语音模型并解压进 assets/voice_models/。
#
# 模型不进 git：单个 onnx 最大 239MB，超过 GitHub 单文件 100MB 上限。本地与 CI 都按需拉取。
# 每个模型只取运行时真正需要的文件（见 VoiceModels.kt 的 requiredFiles），
# 压缩包里的示例音频与文档不进 APK。
#
# 清单必须与 VoiceModels.kt 的 VoiceModels 三个 spec 一致：少一个模型，编出来的包在用户机上
# 就是「安装包里的语音模型不完整，请重新安装 App」（v2.6.0 正式包漏了唤醒与 SenseVoice 两个，
# 语音唤醒直接不可用，故这里列全）。
set -euo pipefail

ASSETS_ROOT="app/src/main/assets/voice_models"

# 每行「目录名|下载地址|所需文件（空格分隔）」。
# 唤醒模型必须用 -mobile 包：普通包里 encoder 是 fp32，只有 mobile 包带 int8。
MODELS=(
    "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30|https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2|encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt"
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17|https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2|model.int8.onnx tokens.txt"
    "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01|https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2|encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx decoder-epoch-12-avg-2-chunk-16-left-64.onnx joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx tokens.txt keywords.txt"
)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for entry in "${MODELS[@]}"; do
    IFS='|' read -r model url files <<<"$entry"
    dest="$ASSETS_ROOT/$model"
    read -r -a want <<<"$files"

    ready=1
    for f in "${want[@]}"; do
        [ -f "$dest/$f" ] || ready=0
    done
    if [ "$ready" -eq 1 ]; then
        echo "语音模型已就位，跳过：$dest"
        continue
    fi

    echo "下载语音模型：$url"
    curl -fL --retry 3 --retry-delay 2 -o "$work/model.tar.bz2" "$url"

    echo "解压并挑选运行所需文件 → $dest"
    mkdir -p "$dest"
    # 用 python 解 bz2：容器精灵里没有 bzip2 命令，python3 两边都有；只抽所需文件，不整包展开
    python3 - "$work/model.tar.bz2" "$dest" "${want[@]}" <<'PY'
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

    rm -f "$work/model.tar.bz2"
    echo "已写入 $dest："
    ls -l "$dest"
done
