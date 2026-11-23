# 设备能力与系统工具

除文件与命令之外，插件还能直接操作手机：点屏幕、按返回键、截图、读通知、启停应用、读写系统设置、连接蓝牙设备。这些能力**都要求用户额外开权限**，本文说明每组能力的前置条件、调用方式与失败表现。

## 前置条件

| 能力 | 需要什么 | 没开时的报错 |
| --- | --- | --- |
| 屏幕操作（`device.*`） | Aharou 无障碍服务（设置 → 权限与后台 → 无障碍） | `A11Y_NOT_ENABLED` |
| 截图（`device.screenshot`） | 同上；系统要求 Android 11 及以上 | `SHOT_FAILED` / `DEVICE_FAILED` |
| 读通知（`notification.list`） | 系统「通知使用权」 | `NOTIFICATION_ACCESS_DENIED` |
| 读写系统设置（`system.settingsGet/Set`） | 插件声明 `contributes.shizuku` 且用户授权，Shizuku 服务在运行 | 权限相关错误 |
| 蓝牙（`bluetooth.*` / `ble.*`） | 运行时授予附近设备/蓝牙权限；部分操作还要求蓝牙开关已开 | `PERMISSION_DENIED` 类错误 |

`device.*` 全部只对**插件进程**开放，页面侧拿不到——这是有意的：点按与按键属于「改外部世界」的动作用，应写在工具或后台逻辑里，而不是页面按钮背后。

## 屏幕操作

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `device.tap` | `{id?}` 或 `{x, y}`，可选 `label` | 点按；`id` 来自元素 dump/find |
| `device.longPress` | `{id?}` 或 `{x, y}`，`duration` 默认 600，范围 300–5000 | 给 `id` 时按住元素中心 |
| `device.swipe` | `{x, y, x2, y2, duration?}` | 滑动 |
| `device.key` | `{key}`，取 `back` / `home` / `recents` / `notifications` | 系统按键 |
| `device.setText` | `{id, text}` | 向指定输入框写文字 |
| `device.pageInfo` | 无 | 前台应用与 Activity、屏幕像素、窗口数、键盘是否展开 |
| `device.screenshot` | 无 | 系统截图，Base64 回传 |

```js
const info = await hostCall('host.device.pageInfo', {});
if (info.package !== 'com.example.app') {
  await hostCall('host.device.key', {key: 'home'});
}
const shot = await hostCall('host.device.screenshot', {});
saveToFile(Buffer.from(shot.base64, 'base64'));
```

`pageInfo` 返回 `package`、`activity`、`width`、`height`、`windowCount`、`keyboardShown`、`appName`；截图为 `{ok, mimeType, base64, caption?}`，宿主没有多模态通道，图片只能以字符串带出——别把整张图塞进模型上下文。

### 行为细节

- **长按不是独立手势**：实现用「起终点相同、按住 600ms」的滑动表达，避免两套判据。
- **节点会过期**：`id` 来自上一次 dump/find，界面变了就失效，报 `STALE_NODE`，重新取页面信息后再操作。
- **写文字受前台限制**：前台是别的应用时，无障碍层会拒绝写入并提示改用影子屏，插件会收到相应错误，不要吞掉改写成成功。
- **结果形状**：成功为 `{ok: true, ...}`，部分成功为 `{ok: false, message, ...}`，失败直接 reject。`tap_delivered`、`screen_changed` 用来判断动作是否真的生效。

这一组和 AI 用的 `a11y` 工具是同一套执行逻辑，插件看到的行为与 AI 直接操作一致；区别在于插件走宿主方法，不经过工具审批队列（否则同步调用会被挂住）。

## 应用、通知与系统设置

| 方法 | 参数要点 | 说明 |
| --- | --- | --- |
| `app.info` | `{package?}` | 不给包名时返回 Aharou 自己的信息 |
| `app.start` | `{package, component?}` | 启动应用 |
| `app.stop` | `{package}` | 停止应用；停宿主自己被拒（`PARAM_DENIED`） |
| `app.list` | `{includeSystem?, filter?}` | 已安装应用 |
| `notification.list` | `{limit?, package?}` | 最近通知 |
| `system.settingsGet` | `{namespace, key}` | 读设置，需 Shizuku |
| `system.settingsSet` | `{namespace, key, value}` | 写设置，需 Shizuku |
| `intent.start` / `intent.broadcast` | `{action?, data?, package?, component?, extras?}` | 至少给 action / data / package / component 之一；extras 只支持字符串 |

::: warning 不要用插件停自己
`app.stop` 对 `com.aharou.agent` 会被拒绝。需要重启宿主时请让用户手动操作，插件强制杀进程会连自己的会话一起杀掉。
:::

## 蓝牙与 BLE

经典蓝牙（`bluetooth.*`）覆盖授权查询、开关、配对列表、扫描、连接、监听与接受、按行读写：

| 阶段 | 方法 |
| --- | --- |
| 准备 | `bluetooth.permission`、`bluetooth.state`、`bluetooth.requestEnable`、`bluetooth.bondedDevices`、`bluetooth.scan` |
| 连接 | `bluetooth.connect`（配对好的设备）、`bluetooth.listen`、`bluetooth.accept`（让手机当服务端） |
| 通信 | `bluetooth.send`、`bluetooth.read`、`bluetooth.sendAndRead` |
| 收尾 | `bluetooth.close` |

BLE（`ble.*`）按连接 → 发现服务 → 读写特征 → 订阅通知的顺序：

| 阶段 | 方法 |
| --- | --- |
| 连接 | `ble.connect`、`ble.discoverServices` |
| 读写 | `ble.readCharacteristic`、`ble.writeCharacteristic`、`ble.writeAndReadCharacteristic` |
| 订阅 | `ble.subscribe`、`ble.readNotifications` |

BLE 的通知不是推送回调，而是「订阅后按需读」：先 `subscribe`，再周期性 `readNotifications` 取增量。连接句柄由宿主保管，插件停用时一并释放。

## 媒体与端口

| 方法 | 说明 |
| --- | --- |
| `music.play` / `music.playQueue` | 播放单曲或队列 |
| `music.pause` / `music.resume` / `music.stop` | 播放控制 |
| `music.seek` / `music.setVolume` / `music.status` | 定位、音量、当前状态 |
| `ports.forward` / `ports.close` / `ports.list` | 把服务器端口映射到手机 loopback |

`ports.*` 用于「容器里跑了个服务，想用手机浏览器打开」：映射之后 `http://127.0.0.1:<端口>` 直达。只对进程开放，映射随插件停用而关闭。

## 排查清单

| 现象 | 处理 |
| --- | --- |
| 所有 `device.*` 都失败 | 无障碍服务没开，或系统把这台设备的无障碍关闭了（部分 ROM 会在重启后清掉） |
| 点按没反应但返回成功 | 看 `tap_delivered` / `screen_changed`；界面有动画时可能落在过场帧上，稍等再点 |
| `STALE_NODE` | 重新取 `pageInfo`（或重新 dump）拿新 id |
| 截图报 `SHOT_FAILED` | Android 11 以下没有系统截图通道；或无障碍已断连 |
| 读通知为空且报权限错 | 到系统设置里给 Aharou 开「通知使用权」 |
| 系统设置读写报错 | 插件没声明 `contributes.shizuku`，或用户没在插件详情里开 Shizuku 授权 |
| 蓝牙扫描不到设备 | 附近设备权限未授予，或扫描期间被系统省电策略打断 |

> 部分内容参照 Operit2 文档改写（AGPL-3.0）；参数、返回结构与错误码取自 Aharou 的 `device.*` / 蓝牙 / 应用类宿主方法实现。
