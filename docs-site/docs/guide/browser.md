# 内置浏览器

App 内置了一个 WebView 浏览器，支持 AI 自动化操作网页。AI 可在后台操作浏览器，截图也能在后台完成（不需要先打开面板）。

## 打开浏览器

在侧边栏底部的卡片中点击「浏览器」：

- **大屏（平板）**：浏览器在右栏与聊天并排打开，可拖动分割条调整宽度。
- **窄屏（手机）**：浏览器全屏打开，按返回键回到聊天。

## 手动浏览

在地址栏输入网址按回车即可导航，加载时地址栏右侧显示停止按钮。顶栏标签行两端分别是「新建标签页」与「历史记录」，左上角图标随当前浏览器标识（UA）档位变化，点击进入浏览器设置。底部工具栏包含后退、前进、下载（有下载记录时出现）、刷新/停止、页内查找，以及「更多」菜单。页面加载时地址栏下方显示线性进度条。

## 本地文件预览

地址栏支持本地文件地址，可直接打开设备上的 HTML 文件：

- 设备真实路径：`file:///storage/emulated/0/Download/index.html`
- 容器路径（AI 在容器里看到的路径，自动映射为真实文件）：`~/workspace/index.html`、`/etc/xxx.html`

AI 也可通过 `browser` 工具的 `navigate` 打开本地页面并截图分析。

**注意**：远程工作区模式下工作区文件在远端，只能加载页面本身，HTML 引用的相对资源（CSS/JS/图片）会失效；本地工作区不受影响。

## 浏览器标识（User-Agent）

顶栏左上角图标随当前 UA 档位变化，点击进入浏览器设置；底部工具栏的「更多」菜单也聚合了同一组 UA 切换入口。可选档位：

| 档位 | 说明 |
| --- | --- |
| 系统默认 | 沿用设备原生 WebView 标识（可随时切回） |
| 安卓手机 | Chrome Mobile (Android) |
| 安卓平板 | Chrome Tablet (Android) |
| 桌面电脑 | Chrome Desktop (Windows PC) |
| 苹果 iPhone | Mobile Safari (iOS) |
| 苹果 iPad | Tablet Safari (iPadOS) |
| 自定义 | 手动填写完整 User-Agent 字符串 |

切换档位后页面会重新加载，并按该档位的默认视口尺寸渲染（可在浏览器设置里用 Web Viewport 覆盖）。

浏览器设置里还可调整空闲标签页回收时长，以及查看/清除 Cookie 与网站数据。

> **说明**：内置浏览器目前不提供夜间模式。

## 页内查找

底部工具栏的放大镜按钮打开页内查找条，输入关键词即可在当前页面高亮匹配，用上/下箭头在结果间跳转，右侧显示「当前/总数」，点关闭结束查找。

## 开发者工具（Eruda）

「更多」菜单里可开关「开发者工具」。开启后会在页面内注入 Eruda（控制台、元素检查、网络面板），并持久保存该开关；之后每个新加载的页面都会自动带上它，关闭即从当前页面移除。适合排查网页报错与查看页面结构。

## AI 自动化操作

AI 可通过 `browser` 工具控制浏览器执行以下操作：

动作分两类：读页面（`get_text`、`get_readable`、`get_page_info`、`get_backbone`、`find_elements`、`get_cookies`、`execute_js`、`screenshot`）与动页面（`click`、`type`、`scroll`、`hover`、`navigate`、标签页与 Cookie 写操作）。

| 操作 | 说明 |
| --- | --- |
| `navigate` | 导航到指定 URL（支持 `http(s)`，本地文件支持 `file://` 或容器路径），等待页面加载完成 |
| `screenshot` | 截取当前页面，返回图片供视觉模型分析（支持后台离屏截图，`full_page` 可整页截图） |
| `click` | 点击元素（完整事件链，兼容 React/Vue）；不写选择器时可用 `coordinate_x` / `coordinate_y` 按坐标点击 |
| `type` | 给输入框写入文本（先选中元素再输入） |
| `get_text` | 提取页面文本（可指定选择器），已过滤 script/style |
| `scroll` | 滚动页面（可指定元素，或用 `direction` / `amount` 滚固定像素，默认 500） |
| `get_page_info` | 取当前页面的基本信息（标题、URL 等） |
| `execute_js` | 执行任意 JavaScript（支持 await / 顶层 return） |
| `find_elements` | 按选择器批量查找元素，返回位置与可见性 |
| `hover` | 悬停元素（派发 mouseenter/over，可展开下拉菜单） |
| `get_readable` | 提取页面的可读正文（去掉导航、广告与脚本） |
| `set_user_agent` | 切换 UA 档位（`default` / `android_phone` / `android_tablet` / `desktop_pc` / `iphone` / `ipad` / `custom`；另兼容旧的 `mobile_chrome` / `desktop_chrome`） |
| `set_viewport` | 设置视口尺寸（`viewport_width` / `viewport_height`，`reset` 清除会话级覆盖） |
| `get_backbone` | 提取页面几何树（字段与截断规则见下） |
| `fetch` | 直接发起 HTTP 请求取回内容，不经过页面渲染 |
| `new_tab` / `close_tab` / `list_tabs` | 标签页管理（最多 3 个标签页） |
| `get_cookies` | 读取 Cookie（可用 `keywords` 过滤、`fuzzy` 模糊匹配） |
| `set_cookies` | 写入 Cookie |
| `scroll_and_collect` | 滚动若干次并收集条目（`item_selector`、`scroll_count`、`keywords`） |
| `wait_for_dom_stable` | 等待 DOM 稳定（`timeout` 毫秒） |
| `wait` | 等待条件成立（`condition=selector=元素` / `text=` / `text*=` / `domStable`，也可直接用 `selector` / `text` 参数；`timeout` 毫秒） |
| `press` | 向当前焦点元素派发按键（`key`：Enter / Escape / Tab / ArrowUp / ArrowDown / ArrowLeft / ArrowRight / Backspace / Delete / 空格） |
| `select` | 选中 `<select>` 的 option（`selector` 定位下拉，`text` 给 option 的 value 或文本），并派发 change |
| `console` | 读取页面控制台日志（`level` 过滤 error/warning/debug/log，`clear` 读取后清空缓冲） |

**交互与导航类动作会自动附截图**：`navigate`、`click`、`scroll`、`hover`、`type` 成功后，结果里会带上当时的页面截图（走图像通道返回），所以不用每次都额外调一次。其余动作用完想确认视觉状态时，再显式调 `screenshot`。

## 选择器格式

`click`、`type`、`hover`、`find_elements`、`get_text`、`scroll`、`select`、`wait` 的选择器支持以下形式：

| 形式 | 含义 |
| --- | --- |
| CSS | `#id`、`.class`、`a[href=...]`、`div > span` 等，按 `document.querySelector` 语义匹配 |
| `text=` | 精确文本匹配（元素直接文本等于给定值） |
| `text*=` | 包含文本匹配（取命中且文本最短的元素，避免命中整页） |
| `role=` | ARIA role 匹配，如 `role=button`；可加名称，如 `role=button[name="提交"]` |
| `ref=` | 引用 `get_backbone` 节点返回的 `ref`（形如 `e12`） |
| `xpath=` | XPath 表达式 |

`find_elements` 对 CSS 返回全部匹配；对扩展形式（`text=` / `role=` / `ref=` 等）返回解析到的单个元素。

`get_backbone` 返回的是**几何树**：在可见元素上按页面结构建树，每个节点形如 `{tag,id,cls,sel,ref,role?,text,href,img,input,rect,pageXY,children}`——`tag` 是标签名，`sel` 是可直接回填到选择器的 CSS 选择器，`ref` 是可直接用 `ref=` 选择器引用的引用 id，`role`/`text`/`href` 按元素实际情况出现，`img` 是图片尺寸与地址，`input` 是输入框类型（含已有值与占位符），`rect`（视口坐标）与 `pageXY`（文档坐标）是位置尺寸。树里**没有** `name` / `url` / `value` 字段，需要交互时用 `sel` 或 `ref` 回填。已过滤 `script`/`style` 与不可见元素，超出 `max_depth`（默认 5）的层级会被裁掉，只留到该深度为止。

## 后台运行

AI 可在后台操作浏览器，无需先打开面板。WebView 由单一的浏览器标签池（BrowserTabPool）管理，独立于 UI 生命周期；截图同样可在后台完成。

## 接管

当 AI 卡在登录、验证码、支付这类必须由你本人完成的网页时，它会把浏览器交回给你：聊天里出现一张**接管卡片**（标题「AI 正在等你操作」），你操作完点卡片上的「**我已完成**」，AI 会重新观察当前页面接着往下做。没有待接管时，卡片位置只留一个「接管」入口，你也可以主动把浏览器拿过来自己操作。详见[影子屏](/guide/shadow-screen)的「接管」。

## 与 websearch / webfetch 的区别

- **websearch**：搜索引擎查询，获取搜索结果摘要。
- **webfetch**：抓取网页 HTTP 内容（纯文本或 HTML），不支持 JS 渲染。
- **browser**：完整 WebView 渲染，支持 JS 动态页面、交互操作、截图分析。

当网页内容依赖 JavaScript 渲染、需要登录后才能访问、或需要点击/填表等交互操作时，使用 `browser` 工具。
