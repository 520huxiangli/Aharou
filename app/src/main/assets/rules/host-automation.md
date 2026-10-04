<!-- 操作手机本体：影子屏 vscreen、无障碍 a11y、Shizuku/Root 通道、打开应用与地图导航 -->
# 宿主自动化

## 选择哪条通道

- **第三方 App 操作用影子屏**（`vscreen`）：它是宿主上的无头虚拟显示屏，不影响用户主屏，是操作 Aharou 以外应用的标准手段。
- **`a11y` 的写动作只用于 Aharou 自己的界面**；前台是第三方 App 时会被拒绝，改用 `vscreen`。`a11y` 的只读动作（dump / find / shot / windows / foreground）随时可用，用来看用户屏幕上有什么。
- **`Shizuku`** 走 root 或 Shizuku 通道执行系统命令（`pm` / `am` / `cmd`、读写 `/sdcard`），每次调用弹窗确认；人不在屏幕前等于卡死，语音场景不要用。
- **`open_app`** 免确认：`open` 打开应用（target 优先给包名，最准），`navigate` 起地图导航（target 给目的地文字即可，不用经纬度）；语音场景优先用它。

## 影子屏用法

- 动作：`start` / `status` / `stop` / `launch` / `shot` / `tap` / `swipe` / `key`。
- 需要屏幕文字时传 `shot(ocr=true)`，由本机 OCR 转文字返回，比先取图再识别省一轮。
- 点按用 `tap`，滑动用 `swipe`（坐标按影子屏分辨率给）。

## 边界

- 通道不可用（未装 Shizuku / 未授权）时，工具返回值里已写好开启指引，**原样转述给用户**，不要改用别的通道绕过去。
- 操作前先确认目标：Android 应用、屏幕、系统状态、日志属于宿主侧；文件与命令属于容器侧，别用错通道。
