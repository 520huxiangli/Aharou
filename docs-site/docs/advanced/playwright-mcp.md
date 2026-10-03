# 安装 Playwright 浏览器自动化

在容器内安装 Chromium 并注册 Playwright MCP 服务器，AI 即可打开网页、点击元素、填写表单、抓取页面内容与截图。

::: tip 需要 Debian / Ubuntu 容器
Chrome for Testing 的二进制依赖 glibc，内置 Alpine 镜像使用 musl libc，无法运行。请先参照 [容器与镜像](/guide/container) 导入自定义 Debian 或 Ubuntu 镜像。
:::

## 环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Debian / Ubuntu，容器内默认 root |
| 架构 | aarch64（arm64）或 x86_64 |
| 浏览器 | Chrome for Testing 153.0.8010.12，安装至 `/opt/chrome` |
| MCP 服务 | `@playwright/mcp`，由 `npx` 按需拉取 |

## 1. 安装依赖

```bash
apt-get update -qq && apt-get install -y -qq unzip fonts-noto-cjk
```

缺少中文字体时，截图中的中文会显示为方块。

## 2. 下载并解压 Chromium

以 arm64 为例；x86_64 需将 URL 中的 `linux-arm64` 替换为 `linux64`，解压目录名替换为 `chrome-linux64`。

```bash
mkdir -p /opt/chrome
curl -L -o /tmp/chrome.zip \
  https://cdn.npmmirror.com/binaries/chrome-for-testing/153.0.8010.12/linux-arm64/chrome-linux-arm64.zip
unzip -q /tmp/chrome.zip -d /opt/chrome
rm /tmp/chrome.zip

/opt/chrome/chrome-linux-arm64/chrome --version
# 输出 Google Chrome for Testing 153.0.8010.12
```

上面用的是 npmmirror 镜像，也可改用官方源；该版本下架时替换 URL 中的版本号即可：

```
https://storage.googleapis.com/chrome-for-testing-public/153.0.8010.12/linux-arm64/chrome-linux-arm64.zip
```

## 3. 注册 MCP 服务器

进入「设置 → AI 配置 → MCP → +」，传输类型选择本地 stdio，按下表填写后保存，App 会自动连接。

| 字段 | 值 |
| --- | --- |
| 名称 | `playwright` |
| 启动命令 | `npx` |
| 命令参数 | `-y`、`@playwright/mcp@latest`、`--headless`、`--no-sandbox`、`--executable-path`、`/opt/chrome/chrome-linux-arm64/chrome`（逐项添加） |
| 作用域 | 全局（所有项目共用）或项目（仅当前工作区） |

也可直接编辑配置文件，全局为 `~/.aharou/mcp.json`，项目级为 `<工作区>/.aharou/mcp.json`，保存后数秒内自动生效：

```json
{
    "mcpServers": {
        "playwright": {
            "command": "npx",
            "args": [
                "-y",
                "@playwright/mcp@latest",
                "--headless",
                "--no-sandbox",
                "--executable-path",
                "/opt/chrome/chrome-linux-arm64/chrome"
            ],
            "enabled": true
        }
    }
}
```

其中 `--no-sandbox` 在容器内必须指定，否则浏览器启动即崩溃；`--executable-path` 指向已安装的 Chromium，避免 Playwright 重复下载。

默认 headless 的 User-Agent 带有 `HeadlessChrome` 标识，部分站点会据此拦截，可追加 `--user-agent` 覆盖（界面中作为两个参数项依次添加）：

```
--user-agent
Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36
```

截图等输出文件默认写入运行目录下的 `.playwright-mcp/`，也就是当前工作区根目录，会在仓库里留下未跟踪文件。追加 `--output-dir` 可移出工作区：

```
--output-dir
/root/playwright-output
```

截图时指定了文件名则例外：相对路径按工作区解析，会绕过 `--output-dir`，需写绝对路径。

## 4. 验证

进入「设置 → AI 配置 → MCP」，`playwright` 一行应显示为已连接并标注工具数量；连接失败时点击右上角的日志按钮排查。

随后新建会话（工具列表在会话开始时确定），令 AI 打开网页并截图，返回图片即表示链路正常。首次连接需等待 `npx` 下载 `@playwright/mcp`，实测 3 至 4 分钟。

## 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 启动即 `Target closed` 或崩溃退出 | 缺少 `--no-sandbox` | 补充该参数后重新连接 |
| 终端直接运行 chrome 报 `/dev/shm` FATAL | 容器无 `/dev/shm`，且 `/dev` 不可写 | 附加 `--disable-dev-shm-usage`；由 Playwright 启动的不受影响 |
| `chrome: not found` 或二进制无法执行 | 容器为 Alpine（musl），或包与架构不符 | 更换 Debian / Ubuntu 镜像，核对 arm64 与 x64 包 |
| 截图中文显示为方块 | 缺少中文字体 | 执行 `apt-get install -y fonts-noto-cjk` |
| 截图为空白页或整张透明 | 无 GPU 环境下渲染偶发失败 | 重试一次，仍为空白时改用 JPEG 格式 |
| 首次调用需等待数分钟 | `npx` 正在下载 `@playwright/mcp` | 等待完成，此后使用本地缓存；也可将 npm registry 换为 `https://registry.npmmirror.com` |
| MCP 列表中没有 `playwright` | 配置未生效或 JSON 存在语法错误 | 确认 `mcp.json` 可正常解析，再点击右上角「重新连接」 |

## 附：一键安装脚本

以下脚本完成第 1 至 3 步，另含下载源自动切换、压缩包校验、已安装时跳过下载、写入前备份 `mcp.json`。保存为 `/tmp/install-playwright.sh` 后执行 `bash /tmp/install-playwright.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${CHROME_VERSION:-153.0.8010.12}"
DIR=/opt/chrome
ZIP=/tmp/chrome-$VERSION.zip

case "$(uname -m)" in
  aarch64|arm64) SUB=linux-arm64; NAME=chrome-linux-arm64 ;;
  x86_64)        SUB=linux64;     NAME=chrome-linux64 ;;
  *) echo "不支持的架构: $(uname -m)"; exit 1 ;;
esac
BIN="$DIR/$NAME/chrome"

[ "$(id -u)" = 0 ] || { echo "请以 root 运行"; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3"; exit 1; }
command -v npx >/dev/null || echo "[警告] 未找到 npx，MCP 服务无法启动"

need=()
command -v unzip >/dev/null || need+=(unzip)
fc-list :lang=zh 2>/dev/null | grep -q . || need+=(fonts-noto-cjk)
if [ ${#need[@]} -gt 0 ]; then
  echo "安装依赖：${need[*]}"
  apt-get update -qq && apt-get install -y -qq "${need[@]}"
fi

if [ -x "$BIN" ]; then
  echo "已安装，跳过下载：$BIN"
else
  for url in \
    "https://cdn.npmmirror.com/binaries/chrome-for-testing/$VERSION/$SUB/$NAME.zip" \
    "https://storage.googleapis.com/chrome-for-testing-public/$VERSION/$SUB/$NAME.zip"
  do
    echo "下载：$url"
    curl -fL --retry 3 --retry-delay 2 -o "$ZIP" "$url" && break
    echo "[警告] 该源不可用，切换下一个"
  done
  [ -s "$ZIP" ] || { echo "全部下载源均失败"; exit 1; }

  mkdir -p "$DIR"
  unzip -tq "$ZIP" && unzip -q "$ZIP" -d "$DIR"
  rm -f "$ZIP"
fi

"$BIN" --version

BIN="$BIN" python3 - <<'PY'
import json, os, shutil
p = os.path.expanduser("~/.aharou/mcp.json")
os.makedirs(os.path.dirname(p), exist_ok=True)
data = {}
if os.path.exists(p):
    shutil.copy(p, p + ".bak")
    with open(p, encoding="utf-8") as f:
        data = json.load(f)   # 存在语法错误时报错退出，不覆盖
data.setdefault("mcpServers", {})["playwright"] = {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest", "--headless", "--no-sandbox",
             "--executable-path", os.environ["BIN"]],
    "enabled": True,
}
with open(p, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=4, ensure_ascii=False)
print("已写入 playwright 配置")
PY

echo "安装完成：$BIN"
```

`CHROME_VERSION` 可指定其它版本。安装完成后返回第 4 步验证。
