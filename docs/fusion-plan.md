# Aharou 开发方案（OpenMinis × AiCode 融合 · v1.1）

> 软件名：**Aharou**（已定 ✅）｜ 内部代号 fusion ｜ 目标产物：一个「日常助手 + 编程副驾」二合一的安卓智能体
> 草拟：小染 · 2026-09-26 ｜ 状态：**开发中（P0 基线已完成）**

---

## 0 · 一句话方案

**AiCode 当底盘**（编码 Agent 的骨架已成型），把 **OpenMinis 的陪伴层**（设备集成 / 浏览器 / 语音 / 定时 / 分享 / 技能记忆）移植进来。

理由三句话：
1. 编码重资产（编辑器/Git/工作区/子代理/检查点）在 AiCode 已长成，推翻重来不值；
2. 助手层（设备能力等）是**相对自包含的增量模块**，搬进 AiCode 是"加工具"，动静小；反方向把 IDE 塞进聊天 App 会大动 UI；
3. AiCode **免 NDK 就能编译**（proot 是预编译的），本地沙箱就能验证构建；OpenMinis 需要 NDK r28+ 从源码编 proot。

---

## 1 · 两个上游盘点

| | OpenMinis | AiCode |
|---|---|---|
| 定位 | Minis 的开源版（全平台 Agent App） | 手机上的 AI Coding Agent |
| 许可证 | GPLv3 | GPLv3 |
| 安卓规模 | ~20.8 万行 Kotlin | ~9.3 万行 Kotlin（主模块） |
| 架构 | 手工 DI（无 Hilt）、Room、Compose | Hilt/KSP、Room、Compose、feature 分层 |
| 构建 | AGP 8.7.3 / Kotlin 2.1.0；**需 NDK r28+** | AGP 8.9.3 / Kotlin 2.2.21；**免 NDK** ✅ |
| SDK | minSdk 26 / targetSdk 35 | minSdk 26 / targetSdk 28（锁定） |
| 拿什么 | 设备集成（闹钟/日历/联系人/剪贴板/定位/通知/媒体/TTS）、浏览器自动化、语音、定时任务、分享入口、技能/记忆、17 语种 | 容器（PRoot 多发行版 + 远程 SSH）、终端、编辑器、Git 面板、文件树、工作区、子代理、检查点、MCP、权限三模式、多 Key |

---

## 2 · 能力矩阵与融合取值

| 能力 | OpenMinis | AiCode | 融合取值 |
|---|---|---|---|
| 沙箱/容器 | 自研 PRoot fork + 持久 shell | 预编译 proot、多发行版、SSH 远程后端 | **取 A 框架** + 吸收 O 的持久 shell/防呆细节 |
| 终端 UI | 有 | 有（Termux 组件） | A |
| 编辑器/Git/文件树 | 无 | 全有 | A |
| 子代理/检查点/权限模式 | 无 | 全有 | A |
| MCP | 有（含 OAuth） | 有（stdio+http） | A 框架，O 的 OAuth 按需合并 |
| 技能/记忆 | SKILL.md + 每日日志 + GLOBAL.md | 雏形（global/project） | 向 O 的约定统一（小染生态兼容） |
| 浏览器自动化 | 全套（多标签/DOM/截图/cookie/js） | 简化版 | **移植 O** |
| 设备集成 | 全套（10+ 项） | 无 | **移植 O** |
| 语音（输入/朗读） | 有（含纠错） | 无 | **移植 O** |
| 定时任务 | 有 | 无 | **移植 O** |
| 分享入口 | 有（7 文件） | 无 | **移植 O** |
| Provider | 多协议 + thinking/vision 细节 | 多协议 + 自定义 + 多 Key | A 为主，补 O 细节 |
| 构建可行性 | NDK 依赖 ❌ | 免 NDK ✅ | A |

---

## 2.5 · 横向需求：自动化（免确认执行）✅ 第一步已落地

**目标**（主人点名）：用 Aharou 干活，不要"每步点确认"。

- ✅ **新会话默认 AUTO**：`SessionUseCase.newSessionEntity` 默认 `AgentMode.AUTO` —— 全放行、不弹窗；
  - 灾难性删除防护保留（可在「工具授权」里关）；Shizuku 例外仍确认（同理可关）。
- ⏳ 后续：设置项「新会话默认模式」（BUILD/AUTO 可选）；定时任务 / 后台长任务自动跑（OpenMinis scheduled 模块）；设备权限一次授权、长期记住。

---

## 3 · 移植清单（分批，含参考文件定位）

参考文件均在 `openminis/src/android/app/src/main/java/com/openminis/app/` 下。

### P1 · 设备与系统能力（助手骨架）— 目标：手机里的活能干
| 功能 | 参考实现 | 落地方式 |
|---|---|---|
| 闹钟/计时 | `offload/AlarmOffloadManager.kt`、`AlarmReceiver.kt` | 写成 AiCode 工具（ToolRegistry 注册） |
| 日历读写 | `offload/CalendarManager.kt` | 同上 |
| 联系人 | `sandbox/offload/ContactsOffloadHandler.kt` | 同上 |
| 剪贴板 | `sandbox/offload/ClipboardOffloadHandler.kt` | 同上 |
| 定位+反地理编码 | `sandbox/offload/LocationOffloadHandler.kt` | 同上 |
| 通知（发/读） | `offload/MinisNotificationListenerService.kt`、`sandbox/offload/NotificationOffloadHandler.kt` | 同上 |
| 媒体播放 | `offload/MediaPlayerManager.kt` | 同上 |
| 天气 | `offload/WeatherManager.kt` | 同上 |
| 设备信息/电池/存储 | `sandbox/offload/DeviceOffloadHandler.kt` | 同上 |
| 权限引导流 | `offload/OffloadPermissionManager.kt`、`sandbox/offload/OffloadGate.kt` | 改为设置页引导 |
| 分享入口 | `share/`（7 文件）+ manifest intent-filter | 直接移植 |
| 相册查询 | DeviceOffload / MediaStore 相关 | 写成工具 |

**验收**：装机后说"明早 8 点叫我"→ 闹钟建成；"看下剪贴板/最近照片"→ 有回；权限缺了有引导。

### P2 · 浏览器 + 语音 + 定时（体验补齐）
| 功能 | 参考实现 | 说明 |
|---|---|---|
| browser_use 全套 | `browser/`（21 文件）+ `sandbox/offload/BrowserUseOffloadHandler.kt` | 多标签/截图/cookie/execute_js/fetch，替换 AiCode 简化版 |
| 语音输入 | `speech/`（13 文件）+ `speech/correction/`（14 文件） | 移植 + 纠错层 |
| TTS 朗读 | 同上语音模块 | 移植 |
| 定时任务 | `scheduled/`（12 文件） | WorkManager/AlarmManager 调度 agent 任务 |
| 技能/记忆统一 | `data/repository/SkillRepository.kt`、`MemoryRepository.kt` | 收口到 SKILL.md + 日志式记忆 |
| minis:// 兼容 | `docs/specs/minis-url-scheme.md` | 可选，评估后定 |

### P2.5 · 可视化专项（沙箱 + 浏览器）— 自包含度高，可插队
**A. 浏览器：能围观、能接管**（≈6.3k 行）
| 层 | 文件 | 行数 |
|---|---|---|
| UI | `ui/browser/`：BrowserSheet / 历史 / 下载 / 设置 / 外链处理 / WebView 封装 | ~2.1k |
| 引擎 | `browser/`：BrowserTabPool（多标签池）/ BrowserUseManager（动作集）/ BrowserUseJS（注入）/ BrowserAction / BrowserHistory / GoogleAuthRouter 等 | ~4.2k |
| 随行 | `ui/chat/StandardChatSheet.kt`(151) + 少量小组件 | 小 |

- 联动方式：工具跑 `browser_use` 时弹出面板（`ChatScreen` 里 `toggleBrowserSheet()`），用户实时观看 + 可接管。
- 适配点：`BrowserTabPool` 几乎零外部依赖（WebView + 协程）；`MinisTextButton` 等换 AiCode 组件或随带；`AppLogger`→AiCode `FileLogger`；接 Hilt。
- 现状对比：AiCode 自带浏览器 ≈2.1k 行（无头工具版、无界面）→ 移植后升维为可视化浏览器。

**B. 沙箱可视化：文件浏览器 + 文件预览**（≈2.4k 行）
| 层 | 文件 | 说明 |
|---|---|---|
| 文件浏览器 | `ui/sandbox/FileBrowserScreen.kt`(558) + ViewModel(521) | `java.io.File` 直读；浏览根改指 AiCode 容器根；含排序/搜索/操作 |
| 文件预览 | `ui/sandbox/FilePreviewScreen.kt`(1368) | 图片 / 文本 / Markdown / PDF(PdfRenderer) / 音视频 + 「存到…」 |
| 合并项 | `RootfsManagementScreen`(378+197)、`MirrorSettingsScreen`(824) | AiCode 已有「容器与镜像」管理 → 合并，不整搬 |
| 保留项 | 终端 UI | 保留 AiCode 的 Termux 终端（更老练），Minis 画布终端不搬 |

### P3 · 沙箱与运行时细节合并
| 项目 | 参考实现 | 说明 |
|---|---|---|
| 持久 shell | `sandbox/PersistentShell.kt` | 减少每次命令启动开销 |
| ash 防呆 | `agent/shell/BashismDetector.kt`、`BashismReminder.kt` | bash 语法在 ash 里乱跑时提醒 |
| 超时/清洗 | `sandbox/ShellTimeoutPolicy.kt`、`TerminalSanitizer.kt` | 合并进 AiCode 容器层 |
| 执行协调 | `sandbox/ExecutionCoordinator.kt` | 多任务/锁屏下的调度 |
| 工具 JSON 修复 | `provider/ToolJsonRepair.kt` | 模型吐坏 JSON 时兜底 |
| 循环/断尾检测 | `agent/ToolLoopDetector.kt`、`InterruptedTailDetector.kt` | 防死循环/半截输出 |
| 上下文折叠 | （O 的上下文守卫） | 与 AiCode 的"长对话压缩"对比取长 |
| 多模态路由 | `provider/VisionGroupResolver.kt`、`ImageBudget.kt` | vision 模型组/图预算 |

### P4 · 品牌与打包
- 名称 **Aharou** ✅ 已定；图标素材 ✅ 已收（`branding/aharou-icon.jpg`）；启动页、中文校对、预置 Provider 模板、签名与版本策略待做。

**工作量粗估**：P1 中 · P2 中大 · P3 中 · P4 小。整体以"周"为单位，每个阶段都产出**能装的 APK**。

---

## 4 · 构建与验证（本地沙箱）

| 组件 | 方案 | 状态 |
|---|---|---|
| JDK | Alpine openjdk17（aarch64 原生） | ✅ 已装 |
| Android SDK | 手拼：build-tools 36 + platform 36 + licenses（腾讯镜像） | 进行中 |
| aapt2/aidl/zipalign | **qemu-x86_64** + Ubuntu glibc rootfs（Google 不提供 aarch64 版） | 验证中 |
| Gradle | 8.14（工程自带 wrapper，走腾讯镜像） | 待跑 |
| Maven 源 | google()→阿里镜像；central/jitpack 直连；**dl.google.com/maven.google.com 在沙箱不可达** | 待适配 |

**循环**：改码 → `assembleUniversalDebug` → APK → 装机实测。

---

## 5 · 路线图

- **P0 基线** ✅：融合仓库建好 + 本地编出 AiCode 原版 APK（构建链验证通过 · 2026-09-26）
- **P1 设备层**：10+ 工具 + 分享 + 权限引导
- **P2 浏览器/语音/定时**：体验补齐
- **P3 沙箱与运行时**：两边最好的部分合体
- **P4 品牌打包**：小染化

---

## 6 · 许可证与合规

- 两个上游都是 **GPLv3** → 融合作品必须以 GPLv3 分发：保留版权声明、注明修改、发布时提供源码。
- 合并 `THIRD_PARTY_LICENSES`；新增文件头部注明来源（如 "adapted from OpenMinis"）。
- 自用无约束；对外分发不得闭源。

---

## 7 · 待拍板（3 件事）

1. **底盘**：默认按"方案甲"（AiCode 底）开工；若你更想"先长得像 Minis"可选乙（周期更长）。
2. **名字/包名**：名字 **Aharou** ✅ 已定（2026-09-26）；包名等品牌阶段统一换。
3. **定位**：自用为主 OK？（决定 targetSdk 策略：现锁 28，自用无碍）

---

## 8 · 工程纪律

- 小步提交、每步可编译、出错可回滚；不搞半截状态。
- 保留两个上游 remote，定期 diff 跟进。
- 每阶段产出可装 APK + 实机验收清单。

---

## 9 · 源码仓库与上传工作流

- 仓库：`https://github.com/520huxiangli/Aharou`（私有）
- 纪律：**每次完工 → 提交 → push**；一键脚本 `scripts/publish_aharou.sh`
- 图标素材：`branding/aharou-icon.jpg`（红发狐耳小染）

---

## 附 · 本地工程布局

```
/var/minis/shared/android-agent/
├── openminis/   # 上游克隆（对照参考）
├── aicode/      # 上游克隆（对照参考）
├── fusion/      # 融合工程（AiCode 完整历史 + 我们的提交）
└── toolchain/   # 本地构建链（SDK / qemu / rootfs）
```
