#!/bin/sh
# aharou-vd 构建脚本（在容器内运行）：VdMain.java -> aharou-vd.jar（app_process 可直跑），
# 并同步到 App 资产 app/src/main/assets/vd/aharou-vd.jar（App 启动影子屏时从资产部署）。
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
SDK=${ANDROID_HOME:-/root/android/sdk}
AJAR=$SDK/platforms/android-36/android.jar
# 取已装的最新 build-tools，不写死版本号（容器里装的版本会随镜像变）。
D8=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)d8
OUT=$DIR/build
ASSET=$DIR/../../app/src/main/assets/vd/aharou-vd.jar

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

javac --release 11 -cp "$AJAR" -d "$OUT/classes" "$DIR/VdMain.java"
"$D8" --release --min-api 26 --lib "$AJAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
jar --create --file "$DIR/aharou-vd.jar" -C "$OUT/dex" classes.dex
cp "$DIR/aharou-vd.jar" "$ASSET"
rm -rf "$OUT"

echo "OK: $DIR/aharou-vd.jar -> $ASSET ($(wc -c < "$ASSET") bytes)"
