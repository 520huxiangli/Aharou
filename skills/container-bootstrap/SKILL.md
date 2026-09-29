---
name: container-bootstrap
display_name: 容器环境初始化
description: "要在容器里初始化环境或装依赖时使用：首次进容器没装好、换软件源、装 Node/Python/JDK/Android SDK/Flutter/Go/Rust、装包报错、缺基础工具、要判断该在哪个镜像里装。用户说「容器里没有 xx」「装个 python」「换下源」时读这份。"
---

# 容器初始化与依赖安装

容器＝AI 执行命令的地方（PRoot + Alpine，aarch64）。装依赖优先走 App 自带流程，不要上来就手搓 `apk add`。

## 首次初始化

本地容器解压 rootfs 与基础工具安装，**在首次进入终端页面时自动触发**。第一次进终端会弹菜单（四选一）：

1. **自动安装依赖（推荐）**：基础工具（bash、curl、ripgrep、git）+ Node.js + Python 3，按 `y` 确认后先选软件源再装。
2. **环境安装**：进「环境安装」菜单，按场景选（见下）。
3. **手动安装（不再提示）**：跳过，之后不再弹；想让菜单回来就重置容器。
4. **退出**：这次不装，下次还问。

装包失败不影响进 shell，重进终端可以再试。

## 之后随时装

菜单只在首次弹一次。后来想装别的运行时（比如才需要 Java/Go），两个入口打开同一个菜单：

- 终端里执行 `aicode`；
- 终端右上角「工具」图标 →「重新运行环境安装」（在新标签打开）。

场景与内容：

| 场景 | 安装内容 |
| --- | --- |
| 通用开发 | 基础工具 + Node.js + Python 3 |
| Python 开发 | 基础工具 + Python 3 + pip |
| Node.js / 前端开发 | 基础工具 + Node.js + npm |
| Kotlin / Android 开发 | 基础工具 + JDK 17 + Android SDK |
| Flutter 开发 | 基础工具 + JDK + Android SDK + Flutter SDK |
| Java 开发 | 基础工具 + JDK |
| Go 开发 | 基础工具 + Go |
| Rust 开发 | 基础工具 + rustc + cargo |
| PHP 开发 | 基础工具 + PHP + Composer |
| 仅基础工具 | bash、curl、ripgrep、git |

基础工具（bash、curl、ripgrep、git）任何场景都会一起装。默认装仓库最新主版本，要指定版本用菜单里的「自定义安装」逐项勾选。菜单里也能单独换源、看已装了哪些运行时。

## 换软件源

换源菜单选项：`1) 自动探测（推荐）`（实测各镜像源可达性、自动跳过失效/被墙的）、`2~6)` 华为云 / 清华 tuna / 中科大 ustc / 腾讯云 / 阿里云、`0) 不换源`。换源前会备份原配置，失败自动恢复。内置 Alpine 的 rootfs 保持官方源，要国内源就在菜单里换。

## 环境体检与补装

设置 → 容器与镜像 → 环境体检，逐项探：共享存储访问权限、沙箱基础工具（`sh`/`git`/`curl`/`tar`/`xz`）、容器 DNS 解析、容器出网。缺基础工具时下方会出「安装」按钮，一键补装并自动重体检。

- 包管理器自动识别（`apk` 还是 `apt-get`），包名自动对应（Debian/Ubuntu 的 `xz` → `xz-utils`）。
- 装在容器里、**不进镜像**，重建镜像后要再装一次。
- 黄＝需处理、红＝出错、灰＝探测不到（不是故障，别当问题报）。

## 已知坑

- **装软件报错**：容器内硬链接不可用导致。编辑容器 → proot 参数 → 加 `--link2symlink` → 保存 → 重进终端。用内置下载功能导入的镜像默认已带这个参数。
- **方向键显示成 `^[[A`**：当前 shell 是 `sh`，没有行编辑能力。把 Shell 改成 `/bin/bash`（自带方向键、历史、补全）。
- **重场景（Android / Flutter）**：会从国内镜像下 JDK、Android SDK（build-tools、platform-tools）、Flutter SDK，耗时长、占存储多。**建议在 Debian / Ubuntu 镜像里装**——内置 Alpine 用 musl libc，需额外处理。ARM64 会自动替换可执行的 `aapt2` 等原生二进制；**ARM64 容器只能构建 Flutter debug APK，release 包得走远程构建**。
- **aapt2 覆盖配置别写进项目**：写到全局 `~/.gradle/gradle.properties`（`android.aapt2FromMavenOverride=...`），不要写进项目的 `gradle.properties`。
- **adb 要装 backports 版**：仓库默认版本过旧、缺 `pair` 命令；`apt install -y -t bookworm-backports adb`。装在容器系统目录，**重装容器后要重装，配对记录也丢**。
- 装好后新开终端会自动带上 `JAVA_HOME`、`ANDROID_HOME` 等环境变量；老终端里没有，别在旧会话里找不到就以为装失败。

## 目录与持久化

| 容器内路径 | 内容 |
| --- | --- |
| `~/workspace` | 当前工作区（切工作区就是换这里的内容） |
| `~/shared` | 跨工作区共享区（所有工作区同一份，切了不变） |
| `~/.aharou` | AI 配置：`skills/`、`docs/`、`mcp.json`、`scripts/` 等 |
| `~/.aharou/memory` | 记忆：SOUL.md、核心档案、日志 |

（`~` 展开即 `/root`；`~/.aicode` 是 `~/.aharou` 的旧路径别名。）这些都在 App 私有存储、与镜像分开存放：**换镜像、重置容器都不动它们**；反之删容器也不影响。

## 破坏性与耗时操作

- **重置容器**会删掉容器里已装的工具和配置（二次确认）——装了很多东西的大容器重置要几十秒，期间显示「正在清理容器数据」，别以为卡死。
- **切换容器**会关闭正在运行的 AI 会话和所有终端标签，动手前提醒用户。
- 装系统包/全局工具属于「影响容器本身」的改动：**先说明缺什么、装什么、装在哪，得到确认再动手**；别自行降级、换源、删锁文件强装。
- 重场景安装动辄几分钟到十几分钟：用后台方式跑（`Bash(background=true)` 或 `terminal(notify=true)`），先告知预计耗时，别空等。
- 只属于某个项目的文件放 `~/workspace`，跨项目复用的脚本/素材放 `~/shared`——别往 `~/.aharou` 里堆杂物。
