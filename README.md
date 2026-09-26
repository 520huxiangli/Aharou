<p align="center">
  <h1 align="center">Aharou</h1>
  <p align="center">
    手机上的 AI 助手 · Coding Agent · 内置 Linux 环境
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/MinSDK-26-orange.svg" alt="Min SDK 26 (Android 8.0)" />
  <a href="https://github.com/520huxiangli/Aharou/releases"><img src="https://img.shields.io/github/v/release/520huxiangli/Aharou?display_name=tag&include_prereleases" alt="Latest Release" /></a>
  <a href="https://github.com/520huxiangli/Aharou/releases"><img src="https://img.shields.io/github/downloads/520huxiangli/Aharou/total" alt="Downloads" /></a>
</p>

---

## 下载安装

- **最新安装包**：[Releases](https://github.com/520huxiangli/Aharou/releases/latest) 页面下载 `Aharou-*.apk`，允许「安装未知应用」后安装即可。
- 支持 Android 8.0+，无需 root；首次启动自动初始化内置 Linux 环境。
- 模型、密钥、端点全部由你自行配置——不内置模型，不绑定供应商。

## 简介

Aharou 把一套完整的 AI 助手与 Linux 环境装进手机：内置 Alpine 容器与多标签终端，Agent 能读写文件、执行命令、跑构建；也可以把执行后端切到远程 SSH。所有能力在本机完成，不用电脑，也不用自己搭环境。

## 功能

- 🗨️ **AI 对话** — 多供应商 / 多模型，Markdown、图片、思考过程展示
- 🐧 **内置 Linux 环境** — Alpine（PRoot）+ 多标签终端，支持导入自定义镜像与挂载宿主目录
- 🌐 **浏览器面板** — 内置浏览器，Agent 可以驱动它打开与操作页面
- 📁 **沙箱文件** — 文件浏览器与预览（图片 / 文本 / Markdown / 音频 / 视频 / PDF / APK / 压缩包）
- 🖥️ **小屏幕** — 工具执行实时可视化：终端逐行输出、网页快照、影子屏直播
- 🪟 **影子屏** — 无头虚拟显示屏：Agent 在「看不见的屏幕」里运行、截图、操作 App，完全不打扰你的手机
- ⚙️ **配置通道** — Agent 可以修改自身设置；每次写入都要你确认，全程审计、一键回滚
- 🦊 **人格（SOUL.md）** — 名字 / 图标 / 语言 / 人格正文，随手改，聊天里即时生效
- 🔑 **环境变量** — 加密存储的自定义变量，自动注入容器内所有命令与终端
- 🗄️ **存储管理** — 按类别查看占用，一键清理
- 🧩 **更多** — MCP、技能、子代理、主题与背景……

## 截图

| 聊天 | 设置 | 运行环境 |
| :---: | :---: | :---: |
| ![聊天](docs/screenshots/aharou-chat.png) | ![设置](docs/screenshots/aharou-settings.png) | ![运行环境](docs/screenshots/aharou-tools.png) |

## 开源说明

- 本项目以 [GPL-3.0](LICENSE) 协议开源。
- 项目开发中使用了若干开源组件；其版权与许可声明见 [LICENSE](LICENSE) 与相关 NOTICE 文件。

## 反馈与贡献

- 🐧 **交流群**：QQ 群 **141882781**
- **Bug 反馈**：到 [Issues](https://github.com/520huxiangli/Aharou/issues) 提交，附上复现步骤、设备型号与系统版本
- **功能建议**：欢迎在 [Issues](https://github.com/520huxiangli/Aharou/issues) 讨论
- **贡献代码**：欢迎提交 [Pull Request](https://github.com/520huxiangli/Aharou/pulls)
