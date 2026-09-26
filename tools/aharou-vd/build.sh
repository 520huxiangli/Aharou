#!/bin/sh
# aharou-vd 构建脚本（在沙箱内运行）：VdMain.java -> aharou-vd.jar（app_process 可直跑）
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
SDK=/var/minis/shared/android-agent/toolchain/sdk
AJAR=$SDK/platforms/android-36/android.jar
D8=$SDK/build-tools/36.0.0/d8
OUT=$DIR/build

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

javac -cp "$AJAR" -d "$OUT/classes" "$DIR/VdMain.java"
"$D8" --release --min-api 26 --lib "$AJAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
jar --create --file "$DIR/aharou-vd.jar" -C "$OUT/dex" classes.dex

echo "OK: $DIR/aharou-vd.jar ($(wc -c < "$DIR/aharou-vd.jar") bytes)"
