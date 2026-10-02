# aharou-vd — 无头虚拟屏小工具（不占宿主屏幕跑 App）

来源：机制参考并改编自 **Genymobile/scrcpy** 的 `--new-display`（Apache-2.0）。
本目录：`VdMain.java`（源码）+ `build.sh`（沙箱内编译）+ `aharou-vd.jar`（产物，约 3KB）。

## 已验证的完整链路（2026-09-26 晚，真机）

```sh
# 1) 推送（沙箱侧）：jar 放 /var/minis/mounts/Aharou/ → 手机 /sdcard/Aharou/
# 2) 启动（手机 shell / Shizuku）：
cp /sdcard/Aharou/aharou-vd.jar /data/local/tmp/
cd /data/local/tmp
CLASSPATH=/data/local/tmp/aharou-vd.jar setsid app_process / com.aharou.vd.VdMain 1080 1920 440 > vd.log 2>&1 < /dev/null &
cat vd.log        # => VD_READY id=N
# 3) 查 SF 长 id（screencap 用）：
dumpsys SurfaceFlinger --display-id | grep aharou-vd
# 4) 开 App（短 id）：
am start --display N -n pkg/act
# 5) 触控（短 id）：
input -d N tap X Y
# 6) 截图：Android 14+ 可 `screencap -d <SF长id>`；Android 13 得用 runner 自带出图（见下）
# 7) 停止：
touch /data/local/tmp/aharou-vd.stop
```

## 自带截图（Android 13 起必需）

`screencap -d <SF长id>` 只在 Android 14+ 对虚拟屏有效；**Android 13 上它只认物理屏 token**，会把目标文件写成 0 字节且不报错。
因此 runner 支持自己出图：启动时多传「截图输出路径 截图请求文件」两个参数，App 侧 touch 请求文件即触发把最近一帧编成 PNG。

```sh
CLASSPATH=/data/local/tmp/aharou-vd.jar setsid app_process / com.aharou.vd.VdMain 1080 1920 440 \
  /sdcard/out.png /data/local/tmp/aharou-vd.shot > vd.log 2>&1 < /dev/null &
touch /data/local/tmp/aharou-vd.shot   # 触发一次出图 → /sdcard/out.png，日志打 VD_SHOT
```

- 输出路径要落在 **App 可读**的位置（二进制不能经 Shizuku 的文本通道回来）；写文件先落 `.tmp` 再改名，避免读到半截。
- 屏上还没有内容时打印 `VD_SHOT_FAILED no-frame`，不写文件。
- 帧拷贝节流 100ms（画面在动时不必每帧都拷）。

## 已实测内容

- 虚拟屏 id=5（1080x1920@440），不在设备屏幕上显示（"影子屏"）。
- `am start --display 5` 把 Aharou (Debug) 开上影子屏；聊天界面完整渲染。
- `input -d 5 tap` 导航全通：抽屉 → 设置 →「Aharou 助手」分组 → 人格 (Soul) 页 → 配置审计页。
- 主屏焦点全程未变（零打扰）；截图证据存 `mounts/Aharou/vd_*.png`。

## 注

- 进程死 = 虚拟屏消失；重启执行第 2 步。
- flags 与 scrcpy dev 分支一致（PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|
  ROTATES_WITH_CONTENT + 13+: TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED|TOUCH_FEEDBACK_DISABLED
  + 14+: OWN_FOCUS|DEVICE_DISPLAY_GROUP）。
- am / input 用短 id（如 5）；`screencap -d` 用 SF 长 id，且只在 Android 14+ 可用。
