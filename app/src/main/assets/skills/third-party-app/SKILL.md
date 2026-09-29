---
name: third-party-app
description: "需要操作宿主手机上 Aharou 以外的 App 时使用：打开某个应用、点里面的按钮、填表单、走一遍流程、看某个应用界面上是什么。凡是要动别人的 App，先读这份。"
---

# 操作第三方 App：一律用影子屏

## 硬规则

**要操作 Aharou 以外的 App，优先 `vscreen start` 建影子屏，再把 App 开进影子屏里操作。**

不要在用户的主屏上用 `a11y` 点按、滑动、输入、按系统键 —— 那会抢走用户的焦点、动他正在看的东西。用户把手机交给你是让你办事，不是让屏幕自己动起来。

**前提是 Shizuku 可用。** 影子屏强依赖 Shizuku，起不来就用不了 —— 此时退回 `a11y` 操作主屏是正确的选择，别为了“必须用影子屏”的教条卡死任务（见下「Shizuku 不可用时」）。

`a11y` 的写动作（`tap` / `swipe` / `key` / `setText`）在前台不是 Aharou 且 **Shizuku 可用** 时会被直接拒绝并返回 `USE_VSCREEN`，这时改用 vscreen。Shizuku 不可用时不会拦，正常用 a11y。

**例外（可以读主屏）**：只想「看」不想「点」时 —— 用户问「我屏幕上这个是啥」「这个弹窗写了什么」—— 用 `a11y` 的 `dump` / `find` / `shot` / `foreground` 是允许的：这些不抢焦点、不改任何状态。

## 标准流程

```
vscreen action=start                       # 建屏，可带 width/height/dpi
vscreen action=launch component=包名        # 把目标 App 开进影子屏
vscreen action=shot                        # 截图看当前画面
vscreen action=tap x=.. y=..               # 或 swipe / key
vscreen action=shot                        # 再截一次确认真点中了
...
vscreen action=stop                        # 用完收掉，别一直占着
```

- `launch` 的 `component` 可以只给包名（`com.example.app`），也可以给 `包名/Activity`。
- **`shot` 是每次操作后的必要动作**：看不见画面就没法确认点对了地方，盲点等于瞎猜。
- 用完整条流程后 **`stop`** 收掉影子屏，不要留着。

## 坐标是影子屏的，不是主屏的

`tap` / `swipe` 的 `x` / `y` 是**影子屏的像素坐标**，由 `start` 时的 `width` / `height` 决定（默认 1080×1920）。

拿坐标的正确方式：`shot` 截图 → 按图上的位置估 → 点了再 `shot` 确认。**不要**拿主屏上看到的坐标去点影子屏，两者不是一个坐标系。

## 实测踩过的坑

- **`tap` 对部分 App 无效**：有些应用只认真实触摸事件流，虚拟注入的点按会被忽略。遇到点了没反应，先多 `shot` 几次确认是不是真没动，再考虑换用 `key` 或直接告知用户这一步走不通。
- **别用 `am start -d` 传坏参数**：启动带 deeplink 的页面时参数拼错会让整个启动失败，`launch` 失败先退回只给包名试。容器里 `am` 的报错要看 stderr，光看退出码判断不出来。
- **`input text` 会丢字符**：容器内往第三方 App 输入长文本时，中文和特殊字符容易丢。短文本优先，或者分批输入后 `shot` 核对。
- **必须先有 Shizuku**：影子屏依赖 Shizuku 就绪。调用失败先看 `status`，提示未就绪就走下面的退路，不要反复重试。

## Shizuku 不可用时

Shizuku 没开、授权掉了、或 `vscreen start` 报未就绪 —— **这时不要卡住，退回 `a11y` 直接操作主屏**：

1. 先跟用户说清：「Shizuku 没就绪，影子屏起不来，我要在你的屏幕上直接操作了」——让他有个准备，别让他看到屏幕自己动。
2. 用 `a11y` 的 `dump` / `find` 定位，`tap` / `swipe` / `setText` / `key` 操作（此时不会被拦）。
3. 事后可以提一句「开一下 Shizuku 以后就不动你屏幕了」，但不要反复念叨。

**不要为了守着「必须用影子屏」这条规则而拒绝完成任务** —— 规则是为了不打扰用户，不是因为别的路走不通还硬拦。

## 什么时候该用 a11y

- 目标是 **Aharou 自己**（进它自己的设置页、点它自己的按钮）→ 用 `a11y` 直接操作，它在处理自己的界面。
- 只读查看用户主屏 → `a11y` 的 `dump` / `find` / `shot`。
- **Shizuku 不可用** → 影子屏用不了，退回 `a11y` 操作（先跟用户说明）。
- 其余一切「帮我在某个 App 里做件事」→ **vscreen**。
