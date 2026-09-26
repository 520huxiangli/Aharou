#!/bin/sh
# Android SDK 安装共享逻辑：供 scenarios/android.sh 与 scenarios/flutter.sh 共用。
# 参考「进阶教程 · 在容器中编译 Android 应用」。

ANDROID_CLT_URL="https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip"
ANDROID_MIRROR="https://mirrors.cloud.tencent.com/AndroidSDK/"
ARM_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
ARM_TOOLS_PROXY_URL="https://gh-proxy.com/$ARM_TOOLS_URL"

# ── 探测 JDK 的 JAVA_HOME：优先 17（AGP / 教程基线），其次 21；发行版路径各异，逐个候选 + 从 java 反推 ──
java_home_dir() {
    for v in 17 21; do
        for d in /usr/lib/jvm/java-$v-openjdk-* /usr/lib/jvm/java-$v-* /usr/lib/jvm/openjdk-$v* /usr/lib/jvm/java-1.$v.0-*; do
            [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
        done
    done
    if command -v java >/dev/null 2>&1; then
        jp=$(readlink -f "$(command -v java)" 2>/dev/null)
        case "$jp" in */bin/java) echo "${jp%/bin/java}"; return 0 ;; esac
    fi
    return 1
}

# ── apt 下要安装的 JDK 包名：优先 17，源里没有则回退 21（Debian 13 / Ubuntu 26.04 只有 21）──
apt_jdk_pkg() {
    for p in openjdk-17-jdk-headless openjdk-21-jdk-headless; do
        if apt-cache policy "$p" 2>/dev/null | grep -q '^  Candidate: [0-9]'; then
            echo "$p"; return 0
        fi
    done
    echo "openjdk-17-jdk-headless"
}

# ── 安装 Android SDK：cmdline-tools + sdkmanager 组件。
#    $1=平台版本（空格分隔，如 "36"）、$2=build-tools 版本（空格分隔，如 "33.0.1 35.0.0"）。
#    组件走腾讯云镜像（SDK_TEST_BASE_URL 覆盖 sdkmanager 仓库根）。幂等：已装则跳过。──
install_android_sdk() {
    platforms="$1"
    buildtools="$2"
    sdk="$HOME/android/sdk"
    mkdir -p "$sdk"
    if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
        echo "下载 Android commandline-tools..."
        (
            cd "$sdk" || exit 1
            rm -rf cmdline-tools-tmp
            curl -fL -C - -o clt.zip "$ANDROID_CLT_URL" || exit 1
            unzip -q -o clt.zip || exit 1
            mv cmdline-tools cmdline-tools-tmp || exit 1
            mkdir -p cmdline-tools/latest || exit 1
            mv cmdline-tools-tmp/* cmdline-tools/latest/ || exit 1
            rmdir cmdline-tools-tmp
            rm -f clt.zip
        ) || { echo "${C_RED}commandline-tools 下载/解压失败。${C_RESET}"; return 1; }
    fi
    jh=$(java_home_dir) || { echo "${C_RED}未找到 JDK 17，无法继续。${C_RESET}"; return 1; }
    export JAVA_HOME="$jh"
    export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
    export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$PATH"
    export SDK_TEST_BASE_URL="$ANDROID_MIRROR"
    args=""
    for p in $platforms; do args="$args \"platforms;android-$p\""; done
    for b in $buildtools; do args="$args \"build-tools;$b\""; done
    echo "通过 sdkmanager 安装：$args \"platform-tools\""
    eval "yes | sdkmanager --install $args \"platform-tools\"" || {
        unset SDK_TEST_BASE_URL
        echo "${C_RED}sdkmanager 安装失败，可检查网络后重试。${C_RESET}"
        return 1
    }
    unset SDK_TEST_BASE_URL
    return 0
}

# ── aarch64 替换 Android SDK 里的原生二进制（Google 的 aapt2/adb 等是 x86_64，ARM64 上跑不了）。
#    x86_64 容器跳过（官方二进制可直用）。下载失败仅告警，不阻塞后续。──
install_android_tools_arm64() {
    [ "$(uname -m)" = "aarch64" ] || return 0
    sdk="$HOME/android/sdk"
    [ -d "$sdk/build-tools" ] || return 0
    zip="$HOME/.aicode-cache/android-sdk-tools-static-aarch64.zip"
    mkdir -p "$HOME/.aicode-cache"
    if [ ! -f "$zip" ]; then
        echo "下载 ARM64 原生二进制（aapt2 / adb 等）..."
        curl -fL -o "$zip" "$ARM_TOOLS_PROXY_URL" || curl -fL -o "$zip" "$ARM_TOOLS_URL" || {
            echo "${C_YELLOW}ARM64 二进制下载失败，可在新终端重试（见进阶教程）。${C_RESET}"
            return 1
        }
    fi
    tmp="$HOME/.aicode-cache/armtools35"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    unzip -q -o "$zip" -d "$tmp" || return 1
    for d in "$sdk"/build-tools/*/; do
        [ -d "$d" ] || continue
        cp -pf "$tmp"/build-tools/* "$d" 2>/dev/null
    done
    [ -d "$sdk/platform-tools" ] && cp -pf "$tmp"/platform-tools/* "$sdk/platform-tools/" 2>/dev/null
    chmod +x "$sdk"/build-tools/*/* "$sdk"/platform-tools/* 2>/dev/null
    echo "已替换 ARM64 原生二进制。"
    return 0
}

# ── 写 Android 环境变量到容器登录 shell，并配置 Gradle 的 aapt2 覆盖（全局，不污染项目）──
write_android_profile() {
    sdk="$HOME/android/sdk"
    jh=$(java_home_dir)
    mkdir -p /etc/profile.d
    {
        echo "# AiCode: Android / Kotlin 开发环境（由环境安装工具写入）"
        [ -n "$jh" ] && echo "export JAVA_HOME=\"$jh\""
        echo "export ANDROID_HOME=\"$sdk\""
        echo "export ANDROID_SDK_ROOT=\"$sdk\""
        echo "export PATH=\"$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:\$PATH\""
    } > /etc/profile.d/aicode-android.sh
    if [ -x "$sdk/build-tools/35.0.0/aapt2" ]; then
        mkdir -p "$HOME/.gradle"
        gp="$HOME/.gradle/gradle.properties"
        if [ -f "$gp" ]; then
            grep -v "aapt2FromMavenOverride" "$gp" > "$gp.tmp" 2>/dev/null || true
        else
            : > "$gp.tmp"
        fi
        echo "android.aapt2FromMavenOverride=$sdk/build-tools/35.0.0/aapt2" >> "$gp.tmp"
        mv "$gp.tmp" "$gp"
    fi
    # 当前会话立即可用
    [ -n "$jh" ] && export JAVA_HOME="$jh"
    export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
    export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$PATH"
}