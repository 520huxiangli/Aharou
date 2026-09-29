---
name: host-device-admin
display_name: 宿主设备管理
description: "需要以系统身份管理宿主 Android 时使用：装或卸应用、抓 logcat、截屏、看 dumpsys、跑 pm/am/cmd、读写 /sdcard，或调试另一台手机。用户说「帮我把这个 apk 装上」「抓一下日志」「卸掉那个应用」时读这份。纯做题/改文件不用它。"
---

# 宿主 Android 管理（shell 身份）

## 用哪个工具

以 adb shell（uid 2000）身份操作宿主系统，一律用 **`Shizuku` 工具**（参数只有 `command`、可选 `timeout`，等价 `adb shell <command>`）。

- **前置**：设置 → 软件权限 → Shizuku 显示「已就绪」。未安装/服务未运行/未授权都跑不了，先让用户照页面提示配好。
- 设备重启后 Shizuku 服务通常要重新启动，别一上来就怀疑代码。
- **每次调用都会弹窗**，包括 `ls` 这类只读命令；**不支持「始终允许」**，AUTO（自动）模式下同样弹窗（除非用户开了设置 → 工具授权 →「禁用安全拦截」）。PLAN 模式下被拦。
- 失败返回 `Shizuku 未就绪：<原因>`（错误码 `SHIZUKU_NOT_READY`）时，先看状态提示再决定，别反复重试。默认超时 120 秒，上限 1800 秒，大安装包要显式加 `timeout`。

## 谁干什么：容器 vs Shizuku

| 能力 | 容器（PRoot） | Shizuku |
| --- | :---: | :---: |
| `pm` / `am` / `cmd` 等系统命令 | ✗ | ✓ |
| 读写 `/sdcard` | 需先挂载 | ✓ |
| 访问其他应用私有目录 `/data/data` | ✗ | ✗（要 root） |

`Shizuku` 是 shell 身份，**读不到别的 App 私有数据**，别答应做不到的事。Android 本身限制普通应用直接调用 `screencap`/`pm`/`dumpsys`，走 shell 身份才不受限。

## 常用命令

下面是「去壳」写法——文档里的 `adb shell xxx`，经 `Shizuku` 执行时去掉 `adb shell` 前缀：

```bash
getprop ro.product.model            # 设备型号
pm list packages                    # 已安装应用
pm list packages | grep 关键词       # 找某个包
logcat -d                           # 导出当前日志
dumpsys activity activities         # 现场状态（dumpsys 任意子系统）
input keyevent KEYCODE_HOME         # 模拟按键
screencap -p /sdcard/screen.png     # 截屏落盘
```

文档原始形式（对照用）：`adb shell getprop ro.product.model`、`adb shell pm list packages`、`adb shell logcat -d`、`adb exec-out screencap -p > screen.png`、`adb shell input keyevent KEYCODE_HOME`。

## 装 / 卸应用

- 安装：`pm install /sdcard/Download/app.apk`（文档原形为 `adb install app.apk`）。替换已装版本加 `-r`。大包会跑几十秒，给足 `timeout`。
- 部分设备要在开发者选项里允许「通过 USB 安装应用」，否则装不上——报权限类错误时先怀疑这条。
- 卸载：`pm uninstall <包名>`。**卸载是不可逆操作，执行前跟用户确认包名**。

## 抓 logcat

- `logcat -d` 导出当前的日志。想只看崩溃堆栈可结合关键词/等级过滤后 grep。
- 抓 Aharou 自己的问题，App 内日志（设置 → 日志）更全更省事：见 [aharou-self-diagnosis](../aharou-self-diagnosis/SKILL.md)。
- 输出可能很长，别一次性全塞进上下文：先 `grep` 定位，或让 `Shizuku` 把结果重定向到 `/sdcard/xxx.log` 再分段读。

## 截屏

- **宿主主屏**：用 `a11y` 的 `shot`（系统截图，图片直接返回）。只读，不抢焦点。
- **影子屏**：用 `vscreen` 的 `shot`。
- **需要落盘原始 PNG**：`screencap -p /sdcard/screen.png`，再想办法取回（`/sdcard` 在容器里需挂载成 `/mnt/...` 才能直接用 `viewImage` 读）。
- 不要为了截一张图去抢用户主屏焦点，能用 `a11y shot` 就别用 `screencap`+取文件那一套。

## 读写 /sdcard

- `Shizuku` 可直接读、写、`ls`、`cat`：例如 `ls /storage/emulated/0/Download`、`cat /storage/emulated/0/Android/data/com.aharou.agent/files/logs/xxx.log`。
- 容器要访问手机存储，得先在「容器与镜像」里把目录挂进容器（本地目录 → 容器目录），重启终端会话才生效。只挂需要的子目录，别整挂 `/sdcard`。

## 调试另一台手机

容器里装好 adb 就能无线调试另一台 Android（11+），全程不需要电脑；配对端口/码寿命很短，对话框一关就失效。

- 容器仓库自带的 adb 过旧（缺 `pair` 命令），从 backports 装：`apt update && apt install -y -t bookworm-backports adb`。
- 流程：目标机开「无线调试」→ 取配对码 → `adb pair <IP>:<配对端口> <6位配对码>` → `adb connect <IP>:<连接端口>` → `adb devices -l`。
- **adb 装进容器系统目录，重装容器要重装、配对记录一并丢**。
- 调试本机用 `127.0.0.1`。详见进阶文档《用 adb 无线调试其他设备》。

## 易错点与边界

- **不要把 `Shizuku` 当容器用**：它没有 `rg`、`py`、`node`，也没有工作区目录。复杂处理放容器，只有非它不可的系统操作才走 Shizuku。
- **命令拼接要小心**：用户给的文件名/包名别直接拼进 shell（命令注入）。带空格、引号的路径要正确转义。
- 卸应用、改系统设置、`pm clear`（清数据）这类不可逆或影响大的操作，先说明再执行。
- 一段流程做完把中间产物清掉（例如放 `/sdcard` 的临时日志、截图），别留垃圾。
