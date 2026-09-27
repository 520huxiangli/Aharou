# 用 adb 无线调试其他设备

在 Aharou 的 Linux 容器里装好 adb，就能通过无线调试（Android 11 及以上）连接另一台 Android 手机，或者连接 Aharou 所在的这台手机本身——全程不需要电脑。

## 适用场景

- **调试另一台手机**：安装应用、抓日志、截屏、模拟输入、上传下载文件。
- **调试本机**：在 Aharou 里对正在使用的这台手机执行同样的操作。
- 需要 root 权限的操作不适用：adb 以 `shell`（uid 2000）身份运行。

## 环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Debian GNU/Linux 12 (bookworm) aarch64（PRoot 容器） |
| adb | 34.0.5（Debian backports；仓库默认版本过旧，缺少 `pair` 命令） |
| 目标设备 | Android 11 及以上（本文实测 Android 15 与 Android 16） |
| 网络 | 调试另一台手机时两台设备需在同一局域网；调试本机走 `127.0.0.1`，不依赖局域网 |
| 权限 | shell（uid 2000），非 root |

## 1. 安装 adb

容器仓库自带的 adb 版本过旧，缺少无线调试所需的 `pair` 命令，需要从 backports 安装：

```bash
apt update
apt install -y -t bookworm-backports adb
adb version
# 应输出 Version 34.x
```

**注意**：adb 安装在容器系统目录，重装容器后需要重新安装，配对记录也会一并丢失。

## 2. 配对（只需一次）

先在目标手机上操作：

1. 设置 → 关于手机 → 连点「版本号」7 次，开启开发者选项；
2. 开发者选项 → 打开「无线调试」；
3. 点「使用配对码配对设备」，屏幕会显示 **IP 地址和端口** 与 **6 位配对码**。

建议把「无线调试」设置页与 Aharou 分屏显示，边看边操作——配对对话框一旦被切走或关闭，端口和配对码就立即失效了。配对端口也可以用文末的脚本自动发现，只有 6 位配对码必须读屏幕。

回到 Aharou 终端，把端口和配对码换成屏幕上的值：

```bash
adb pair 192.168.1.23:37123 123456
# 成功会输出 Successfully paired to ...
```

**注意**：配对端口和配对码存活时间很短，对话框一关闭就立即失效，需要重新打开。配对成功后设备会记住这台主机，之后不必再配对。

## 3. 连接

```bash
adb connect 192.168.1.23:40001
adb devices -l
```

`192.168.1.23:40001` 是无线调试主页显示的「IP 地址和端口」，注意它与配对用的端口不是同一个。

连接端口每次重新打开无线调试都会变化，可以重新查看手机屏幕，或用文末脚本自动发现。

## 4. 调试本机

调试 Aharou 所在的这台手机时，地址用 `127.0.0.1`（容器与手机共享网络，不依赖局域网）：

```bash
adb pair 127.0.0.1:37123 123456
adb connect 127.0.0.1:40001
adb devices
```

**注意**：用手机的局域网 IP 也能连，但同一台设备会同时出现两条记录（一条 `127.0.0.1`、一条局域网 IP），用 `adb -s <序列号>` 指定设备，或 `adb disconnect` 去掉多余的那条。

调试本机的价值在于：Android 限制普通应用直接调用系统命令（`screencap`、`pm`、`dumpsys` 等会被拒绝），而以 `shell` 身份通过 adb 执行则不受此限制。

## 常用操作

```bash
adb shell getprop ro.product.model      # 设备型号
adb shell pm list packages              # 已安装应用
adb shell logcat -d                     # 导出当前日志
adb exec-out screencap -p > screen.png  # 截屏
adb push local.txt /sdcard/             # 上传文件
adb pull /sdcard/photo.jpg ./           # 下载文件
adb shell input keyevent KEYCODE_HOME   # 模拟按键
```

安装应用用 `adb install app.apk`，部分设备还需要在开发者选项里允许「通过 USB 安装应用」。

这些命令都以 `shell` 用户身份运行，可以读写 `/sdcard`，但无法访问 `/data/data` 等受限目录。

## 常见问题

**报错 `protocol fault (couldn't read status message)`**

最常见的原因是配对端口已失效（对话框关闭或重新打开过），重新打开对话框、用新的端口和配对码再配一次即可。如果端口确认是最新的仍报此错，直接重试同一条命令——部分 adb 版本存在该已知问题，第二次往往就能成功。

**报错 `failed to connect to ...: Connection refused`**

连接端口过期，或设备尚未配对。重新查看无线调试主页的端口，必要时重新配对。

**每个新终端都要重新连接**

adb 服务随终端会话结束而退出，新会话里需要重新执行 `adb connect`。把 `adb start-server`、`adb connect` 和后续命令放在同一个终端里执行即可。

**`adb mdns services` 列表为空**

容器里 adb 自带的 mDNS 发现无法工作——它需要的 5353 端口已被手机系统的 mDNS 服务占用。这不影响手动连接，自动发现请用下面的脚本。

## 附：自动发现端口

无线调试开启后，设备会在局域网广播 `_adb-tls-pairing._tcp`（配对服务，仅在配对对话框打开时）和 `_adb-tls-connect._tcp`（连接服务）。把下面的脚本保存为 `discover_adb.py`，运行后会分别列出两者的「IP:端口」：

```python
#!/usr/bin/env python3
"""扫描局域网中的 adb 无线调试端点"""
import socket
import struct
import time

SERVICES = {
    '配对端口': '_adb-tls-pairing._tcp.local',
    '连接端口': '_adb-tls-connect._tcp.local',
}


def encode(name):
    out = b''
    for part in name.split('.'):
        out += bytes([len(part)]) + part.encode()
    return out + b'\x00'


def read_name(data, off):
    labels, jumped, start = [], False, off
    while True:
        length = data[off]
        if length == 0:
            off += 1
            break
        if length & 0xC0 == 0xC0:
            ptr = struct.unpack('!H', data[off:off + 2])[0] & 0x3FFF
            if not jumped:
                start = off + 2
            off, jumped = ptr, True
            continue
        labels.append(data[off + 1:off + 1 + length].decode())
        off += 1 + length
    return '.'.join(labels), (start if jumped else off)


def scan(service, seconds=6):
    # class 高位置 1 表示请求单播响应：配对服务对普通查询只回组播，
    # 不绑定 5353 就收不到；连接服务则回单播
    query = (struct.pack('!HHHHHH', 0, 0, 1, 0, 0, 0)
             + encode(service)
             + struct.pack('!HH', 12, 0x8001))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    struct.pack('4s4s', socket.inet_aton('224.0.0.251'),
                                socket.inet_aton('0.0.0.0')))
    sock.bind(('', 0))
    sock.settimeout(0.3)
    endpoints = set()
    deadline = time.time() + seconds
    while time.time() < deadline:
        sock.sendto(query, ('224.0.0.251', 5353))
        try:
            data, addr = sock.recvfrom(9000)
        except socket.timeout:
            continue
        qd, an, ns, ar = struct.unpack('!HHHH', data[4:12])
        off = 12
        for _ in range(qd):
            _, off = read_name(data, off)
            off += 4
        for _ in range(an + ns + ar):
            name, off = read_name(data, off)
            rtype, _, _, rdlen = struct.unpack('!HHIH', data[off:off + 10])
            off += 10
            rdata = data[off:off + rdlen]
            off += rdlen
            if rtype == 33 and service.split('.')[0] in name:
                port = struct.unpack('!HHH', rdata[:6])[2]
                endpoints.add(f'{addr[0]}:{port}')
    sock.close()
    return endpoints


for label, service in SERVICES.items():
    for endpoint in sorted(scan(service)):
        print(f'{label}  {endpoint}')
```

用法：

```bash
python3 discover_adb.py
# 配对端口  192.168.1.23:37123
# 连接端口  192.168.1.23:40001
adb pair 192.168.1.23:37123 123456
adb connect 192.168.1.23:40001
```

**注意**：配对服务只在「使用配对码配对设备」对话框打开时广播，所以要在对话框开着的时候运行脚本；6 位配对码始终需要从手机屏幕读取。列表里可能出现多个结果（含设备重启前的过期端口），逐个 `adb connect` 试即可。
