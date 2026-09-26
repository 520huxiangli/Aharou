#!/bin/sh
# 场景：Flutter 开发（Android SDK + Flutter SDK 3.27.4）
# 依赖 lib/android-sdk.sh 提供的共享安装逻辑。
FLUTTER_VERSION="3.27.4"
FLUTTER_DIR="$HOME/.local/flutter-$FLUTTER_VERSION"
FLUTTER_STORAGE="https://storage.flutter-io.cn"

scenario_name_flutter() { echo "Flutter 开发（Flutter + Android SDK）"; }

install_scenario_flutter() {
    if [ "$PMGR" != "apt" ]; then
        echo "${C_YELLOW}注意${C_RESET}：编译 Flutter 应用建议使用 Debian / Ubuntu 镜像，内置 Alpine（musl）需额外处理。"
        printf "当前包管理器为 %s，仍要继续吗？[y/N]: " "${PMGR:-未知}"
        read a
        case "$a" in y|Y) ;; *) return 2 ;; esac
    fi
    echo ""
    echo "安装 JDK、git 与基础工具..."
    pkg_add "$(apt_jdk_pkg)" git curl wget unzip zip || return 1
    install_android_sdk "35" "33.0.1 35.0.0" || return 1
    install_android_tools_arm64 || true
    write_android_profile
    if [ ! -x "$FLUTTER_DIR/bin/flutter" ]; then
        echo "克隆 Flutter SDK（$FLUTTER_VERSION）..."
        rm -rf "$FLUTTER_DIR"
        git clone --depth 1 --branch "$FLUTTER_VERSION" https://github.com/flutter/flutter.git "$FLUTTER_DIR" || {
            echo "${C_RED}Flutter 克隆失败，可挂代理或重试。${C_RESET}"
            return 1
        }
    fi
    export PATH="$PATH:$FLUTTER_DIR/bin"
    export FLUTTER_STORAGE_BASE_URL="$FLUTTER_STORAGE"
    echo "初始化 Flutter（首次会下载 dart-sdk，可能较慢，请耐心等待）..."
    "$FLUTTER_DIR/bin/flutter" --version || echo "${C_YELLOW}Flutter 初始化未完成，可在新终端重试 flutter --version。${C_RESET}"
    mkdir -p /etc/profile.d
    {
        echo "# AiCode: Flutter 开发环境（由环境安装工具写入）"
        echo "export FLUTTER_STORAGE_BASE_URL=\"$FLUTTER_STORAGE\""
        echo "export PATH=\"$FLUTTER_DIR/bin:\$PATH\""
    } > /etc/profile.d/aicode-flutter.sh
    echo ""
    echo "${C_GREEN}Flutter 环境就绪：$FLUTTER_DIR${C_RESET}"
    echo "  注意：ARM64 容器只能构建 debug APK；新开终端会自动带上 Flutter / Android 环境变量。"
    return 0
}