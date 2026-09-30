# 内置浏览器

App 内置了一个 WebView 浏览器，支持 AI 自动化操作网页。AI 可在后台操作浏览器，截图也能在后台完成（不需要先打开面板）。

## 打开浏览器

在侧边栏底部的卡片中点击「浏览器」：

- **大屏（平板）**：浏览器在右栏与聊天并排打开，可拖动分割条调整宽度。
- **窄屏（手机）**：浏览器全屏打开，按返回键回到聊天。

## 手动浏览

在地址栏输入网址按回车即可导航。地址栏右侧提供开发者工具开关与刷新按钮，下方底栏包含后退、前进、新建标签页与多标签管理。页面加载时地址栏下方显示线性进度条。

## 本地文件预览

地址栏支持本地文件地址，可直接打开设备上的 HTML 文件：

- 设备真实路径：`file:///storage/emulated/0/Download/index.html`
- 容器路径（AI 在容器里看到的路径，自动映射为真实文件）：`~/workspace/index.html`、`/etc/xxx.html`

AI 也可通过 `browser` 工具的 `navigate` 打开本地页面并截图分析。

**注意**：远程工作区模式下工作区文件在远端，只能加载页面本身，HTML 引用的相对资源（CSS/JS/图片）会失效；本地工作区不受影响。

## 开发者工具

点击地址栏右侧的 `</>`（代码图标）可随时开启或收起移动端开发者工具（基于 Eruda）：
- **全功能控制台**：包含 Console（查看日志与执行 JavaScript 代码）、Elements（查看与实时编辑 DOM 树和 CSS 样式）、Network（抓包网络请求与响应）、Resources（查看 LocalStorage、Cookie 等数据）、Sources 等。
- **即点即用**：开启后页面右下角显示浮动齿轮图标，点击即可展开完整控制台面板；再次点击地址栏的开发工具按钮可彻底关闭并移除悬浮球。
- **电脑端调试联动**：开启时同步启用 Chromium 的 `WebContentsDebugging`，支持通过 USB 连接电脑并在 Chrome 浏览器访问 `chrome://inspect` 进行桌面级远程审查。

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
| `set_user_agent` | 切换 UA 档位（`desktop_chrome` / `mobile_chrome`） |
| `set_viewport` | 设置视口尺寸（`viewport_width` / `viewport_height`，`reset` 清除会话级覆盖） |
| `get_backbone` | 提取页面几何树（字段与截断规则见下） |
| `fetch` | 直接发起 HTTP 请求取回内容，不经过页面渲染 |
| `new_tab` / `close_tab` / `list_tabs` | 标签页管理（最多 3 个标签页） |
| `get_cookies` | 读取 Cookie（可用 `keywords` 过滤、`fuzzy` 模糊匹配） |
| `set_cookies` | 写入 Cookie |
| `scroll_and_collect` | 滚动若干次并收集条目（`item_selector`、`scroll_count`、`keywords`） |
| `wait_for_dom_stable` | 等待 DOM 稳定（`timeout` 毫秒） |

**交互与导航类动作会自动附截图**：`navigate`、`click`、`scroll`、`hover`、`type` 成功后，结果里会带上当时的页面截图（走图像通道返回），所以不用每次都额外调一次。其余动作用完想确认视觉状态时，再显式调 `screenshot`。

## 选择器格式

`click`、`type`、`hover`、`find_elements`、`get_text`、`scroll` 的选择器只支持 **CSS 选择器**（`#id`、`.class`、`a[href=...]`、`div > span` 等），按 `document.querySelector` / `querySelectorAll` 语义匹配。`ref=`、`text=`、`text*=`、`role=`、`xpath=` 这类写法不识别，写了会按 CSS 解析从而找不到元素。

`get_backbone` 返回的是**几何树**：在可见元素上按页面结构建树，每个节点形如 `{tag,id,cls,sel,role?,text,href,img,input,rect,pageXY,children}`——`tag` 是标签名，`sel` 是可直接回填到选择器的 CSS 选择器，`role`/`text`/`href` 按元素实际情况出现，`img` 是图片尺寸与地址，`input` 是输入框类型（含已有值与占位符），`rect`（视口坐标）与 `pageXY`（文档坐标）是位置尺寸。树里**没有** `name` / `ref` / `url` / `value` 字段，需要交互时用 `sel` 回填 CSS 选择器。已过滤 `script`/`style` 与不可见元素，超出 `max_depth`（默认 5）的层级会被裁掉，只留到该深度为止。

## 后台运行

AI 可在后台操作浏览器，无需先打开面板。WebView 由 BrowserManager 管理，独立于 UI 生命周期；截图同样可在后台完成。

## 与 websearch / webfetch 的区别

- **websearch**：搜索引擎查询，获取搜索结果摘要。
- **webfetch**：抓取网页 HTTP 内容（纯文本或 HTML），不支持 JS 渲染。
- **browser**：完整 WebView 渲染，支持 JS 动态页面、交互操作、截图分析。

当网页内容依赖 JavaScript 渲染、需要登录后才能访问、或需要点击/填表等交互操作时，使用 `browser` 工具。
