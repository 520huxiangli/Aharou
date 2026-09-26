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
# 5) 截图（SF 长 id）/ 触控（短 id）：
screencap -d <SF长id> -p /sdcard/Aharou/x.png
input -d N tap X Y
# 6) 停止：
touch /data/local/tmp/aharou-vd.stop
```

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
- screencap 用 SF 长 id；am / input 用短 id（如 5）。
