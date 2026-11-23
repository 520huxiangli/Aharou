// 宿主插件 API 的类型参考（仓库内文件，不是 npm 包，没有构建流程）。
//
// 事实源按优先级：
//   1. app/src/main/java/com/aharou/feature/plugin/domain/PluginHostApiCatalog.kt   —— 哪些方法、谁能调
//   2. app/src/main/java/com/aharou/feature/plugin/runtime/PluginHostApiDispatcher.kt —— 参数键与返回结构
//   3. presentation/PluginWebBridge.kt、overlay/PluginOverlayBridge.kt           —— 页面/浮层专属方法
//
// 方法名与可用来源由 scripts/check-page-bridge.py 对账（只对方法名，不校验类型）；
// 改宿主方法或参数时同步改这份声明，否则作者看到的类型会跟实现漂移。
//
// 约定：页面侧直接写方法名（`fs.read`），插件进程侧一律带 `host.` 前缀（`host.fs.read`）。
// 错误统一走 reject：Promise 拒绝时带上 { code, message }，code 见各方法说明。

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };

/** 无参数方法；调用时传也可以，多传的键会被宿主忽略。 */
export type NoParams = Record<string, never>;

export interface OkResult {
  ok: true;
}

export interface FileContent {
  content: string;
  size: number;
}

/** fs.readBinary / fs.writeBinary 的载荷。 */
export interface BinaryContent {
  base64: string;
  size: number;
}

export interface FileStat {
  exists: boolean;
  isDir: boolean;
  size: number;
}

export interface FileEntry {
  name: string;
  isDir: boolean;
  size: number;
}

export interface FileListResult {
  entries: FileEntry[];
}

/** bash / shizuku.exec 的返回；truncated 表示输出被截断。 */
export interface ExecResult {
  output: string;
  exitCode: number;
  truncated: boolean;
}

/** 页面 context()：页面专属字段随入口上下文变化，缺省时不出现该键。 */
export interface PageContextResult {
  pluginId: string;
  pageId: string;
  slot?: string;
  sessionId?: string;
  path?: string;
  isDirectory?: boolean;
  selection?: string;
  language?: string;
  lineStart?: number;
  lineEnd?: number;
  version: string;
  languageTag: string;
  theme: string;
  workspaceName: string;
  contextKey: string;
  visible: boolean;
  home: string;
  workspace: string;
  dataDir: string;
}

/** 浮层页面 context()：只有浮层自己的少量字段。 */
export interface OverlayContextResult {
  pluginId: string;
  overlayId: string;
  pageId: string;
  version: string;
  density: number;
}

export type OverlayVisibilityPolicy = "always" | "foreground" | "background";

/** overlay.create 的返回；x / y 是宿主窗口像素。 */
export interface OverlayDescription {
  overlayId: string;
  pluginId: string;
  width: number;
  height: number;
  x: number;
  y: number;
  draggable: boolean;
  snap: string;
  visibilityPolicy: OverlayVisibilityPolicy;
}

/** overlay.self 的返回；x / y 换算成了 dp（与 width / height 同单位）。 */
export interface OverlaySelfInfo extends OverlayDescription {
  density: number;
  foreground: boolean;
}

export interface TerminalRunResult {
  tabId: string;
  state: "running";
}

export interface TerminalTab {
  tabId: string;
  title: string;
  command: string;
  state: "running" | "finished" | "cancelled";
}

export interface TerminalPollResult {
  output: string;
  offset: number;
  state: "running" | "finished" | "cancelled";
  exitCode?: number;
  truncated: boolean;
}

export interface ChatSession {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  /** 会话模式，宿主写入时是小写；auto 模式只允许用户选，插件建不出来。 */
  mode: string;
  isPinned: boolean;
  parentId?: string;
  subagentType?: string;
  providerId?: string;
  model?: string;
  goal?: string;
  goalStatus?: string;
  reasoningEffort: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  lastInputTokens: number;
}

export interface ChatToolCall {
  id: string;
  name: string;
  /** 正常是对象；超过长度上限时退化成截断的 JSON 字符串并带 argumentsTruncated。 */
  arguments: JsonObject | string;
  argumentsTruncated?: boolean;
}

export interface ChatAttachment {
  fileName: string;
  containerPath: string;
  mimeType: string;
  sizeBytes: number;
  isImage: boolean;
}

export interface ChatMessage {
  id: string;
  role: string;
  content: string;
  timestamp: number;
  toolName?: string;
  isError: boolean;
  isCompacted: boolean;
  isContextSummary: boolean;
  isCompactionMarker: boolean;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  reasoning?: string;
  reasoningTruncated?: boolean;
  toolCalls?: ChatToolCall[];
  attachments?: ChatAttachment[];
}

export interface ChatRunState {
  running: boolean;
  /** 空闲时是 "idle"；其余状态由 agent 运行态给出。 */
  status: string;
  error?: string;
  startedAt?: number;
  finishedAt?: number;
}

export interface ChatToolApproval {
  id: string;
  toolName: string;
  title: string;
  summary: string;
  details: string;
  argsPreview: string;
  sessionId: string;
  rememberablePatterns: string[];
  rememberDisabledReason?: string;
}

export interface ChatQuestion {
  id: string;
  sessionId: string;
  questions: {
    question: string;
    header: string;
    multiSelect: boolean;
    options: { label: string; description: string }[];
  }[];
}

export interface ChatPlanApproval {
  id: string;
  sessionId?: string;
  reason: string;
}

export interface ModelProviderInfo {
  id: string;
  name: string;
  type: string;
  enabled: boolean;
  hasKey: boolean;
  isPlugin: boolean;
  isDefault: boolean;
  defaultModel: string;
  selectedModel: string;
  modelCount: number;
}

export interface ModelInfo {
  id: string;
  displayName: string;
  contextTokens: number;
  inputTokens: number;
  outputTokens: number;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsReasoning: boolean;
  supportsCustomTemperature: boolean;
  supportsImageOutput: boolean;
  modelType: string;
  reasoningLevels?: string[];
  inputCostUsdPerM?: number;
  outputCostUsdPerM?: number;
  cacheReadCostUsdPerM?: number;
  cacheWriteCostUsdPerM?: number;
  source: string;
}

export interface ModelParams {
  reasoningEffort?: string;
  maxOutputTokens?: number;
  temperature?: number;
}

export interface ModelHandle {
  id: string;
  providerId: string;
  model: string;
  name?: string;
  params: ModelParams;
  createdAt: number;
}

/** 图片输入：path 与 base64 二选一，mimeType 必填。 */
export interface ModelImageInput {
  mimeType: string;
  path?: string;
  base64?: string;
}

export type ModelInputMessage =
  | { role: "user"; content: string; images?: ModelImageInput[] }
  | {
      role: "assistant";
      content: string;
      toolCalls?: { id: string; name: string; arguments: JsonObject }[];
      reasoning?: string;
      providerState?: { signature?: string; thinkingBlocksJson?: string };
    }
  | { role: "tool"; content: string; toolCallId: string; name: string; images?: ModelImageInput[] };

export type ModelToolRef =
  | { type: "app"; name: string }
  | { type: "custom"; name: string; description?: string; inputSchema?: JsonObject };

export type ModelStreamEvent =
  | { type: "text"; text: string }
  | { type: "reasoning"; text: string }
  | { type: "toolCall"; name: string }
  | { type: "retry"; attempt: number; maxRetries: number; error: { code: string; statusCode?: number } }
  | { type: "keySwitch"; index: number; total: number };

export interface ModelError {
  code: string;
  message: string;
}

export interface ModelFinal {
  content: string;
  contentTruncated?: boolean;
  reasoning?: string;
  reasoningTruncated?: boolean;
  toolCalls?: ChatToolCall[];
  toolCallsTruncated?: boolean;
  stopReason?: string;
  stopDetail?: string;
  providerState?: { signature?: string; thinkingBlocksJson?: string };
  imageCount?: number;
  images?: { mimeType: string; base64: string }[];
  imagesTruncated?: boolean;
  usage: { input: number; output: number; cachedInput: number; cacheCreation: number };
  durationMs: number;
}

export interface ModelReadResult {
  streamId: string;
  events: ModelStreamEvent[];
  /** 下次读取用的游标。 */
  cursor: number;
  done: boolean;
  /** 只在 done=true 时出现。 */
  status?: "success" | "error" | "cancelled";
  final?: ModelFinal;
  error?: ModelError;
}

export interface ToolListItem {
  name: string;
  description: string;
  type: "builtin" | "mcp" | "plugin";
  permissionPolicy: string;
  capabilities: string[];
  inputSchema?: JsonObject;
}

export interface ToolImage {
  mimeType: string;
  base64: string;
  path?: string;
}

export type ToolExecuteResult =
  | {
      status: "success";
      data: JsonValue;
      dataTruncated?: boolean;
      images?: ToolImage[];
      imagesTruncated?: boolean;
    }
  | { status: "partial"; data: JsonValue; dataTruncated?: boolean; message: string }
  | { status: "error"; message: string; code: string };

export interface ToolPendingResult {
  approval: ChatToolApproval | null;
}

export interface McpServerStatus {
  name: string;
  state: string;
  toolCount: number;
  error: string | null;
}

export interface HttpRequestResult {
  status: number;
  headers: Record<string, string>;
  body: string;
  /** 传 responseBodyBase64=true 时额外出现：截断后字节的 Base64，二进制响应不必经 UTF-8 文本这一层。 */
  bodyBase64?: string;
  truncated: boolean;
}

/** http.request 的 multipart 文件项；base64 是文件原始字节。 */
export interface HttpMultipartFile {
  name: string;
  fileName?: string;
  /** fileName 的别名，两个键都接受。 */
  filename?: string;
  mimeType?: string;
  base64: string;
}

/** http.request 的 multipart 请求体：普通字段 + 文件，至少给一项。 */
export interface HttpMultipartBody {
  fields?: Record<string, string>;
  files?: HttpMultipartFile[];
}

/** env.get：found=false 表示该键没存过（此时 value 是传入的 default，没传就是空串）。 */
export interface EnvGetResult {
  key: string;
  value: string;
  found: boolean;
}

/** env.list：与 secrets.list 不同，这里连值一起返回。 */
export interface EnvListResult {
  values: Record<string, string>;
  keys: string[];
}

/** fs.edit：replacements 是实际替换处数。 */
export interface FsEditResult extends OkResult {
  replacements: number;
  size: number;
}

/** fs.readRange：start / end 是字节偏移（左闭右开），size 是文件总字节数。 */
export interface FsRangeResult {
  content: string;
  start: number;
  end: number;
  size: number;
  truncated: boolean;
}

export interface FsFindMatch {
  path: string;
  name: string;
  isDir: boolean;
  size: number;
}

export interface FsFindResult {
  matches: FsFindMatch[];
  /** 实际扫过的条目数（不是命中数）。 */
  scanned: number;
  truncated: boolean;
}

export interface FsGrepMatch {
  path: string;
  /** 1 起的行号。 */
  line: number;
  text: string;
}

export interface FsGrepResult {
  matches: FsGrepMatch[];
  scannedFiles: number;
  truncated: boolean;
}

/** fs.unzip：paths 是实际写出的文件（不含目录）。 */
export interface FsUnzipResult extends OkResult {
  entries: number;
  bytes: number;
  paths: string[];
}

/** 点按类动作的回执；screen_changed 为 "unknown" 表示没拿到前后画面，不代表没点中。 */
export interface DeviceActionResult {
  ok: boolean;
  message?: string;
  tap_delivered?: boolean;
  screen_changed?: boolean | "unknown";
  note?: string;
}

export interface DevicePageInfo {
  package: string;
  activity: string;
  width?: number;
  height?: number;
  windowCount: number;
  keyboardShown: boolean;
  appName: string;
}

/** device.screenshot：图片以 Base64 直接回传（宿主方法没有多模态通道）。 */
export interface DeviceScreenshotResult extends OkResult {
  mimeType: string;
  base64: string;
  caption?: string;
}

export interface AppLaunchResult extends OkResult {
  package: string;
  output: string;
}

export interface AppListResult {
  packages: string[];
  truncated: boolean;
}

/** app.info 传了 package 时的返回；查不到已装应用时只有 packageName 与 isInstalled=false。 */
export interface InstalledAppInfo {
  packageName: string;
  isInstalled: boolean;
  versionName?: string;
  versionCode?: number;
  label?: string;
  isSystemApp?: boolean;
}

export interface NotificationEntry {
  key: string;
  package: string;
  title: string;
  text: string;
  postedAt: number;
  ongoing: boolean;
  canReply: boolean;
}

export interface NotificationListResult {
  notifications: NotificationEntry[];
}

/** settings get 的原值；unset=true 表示这个键没有显式值（此时 value 为空串）。 */
export interface SettingsGetResult {
  namespace: string;
  key: string;
  value: string;
  unset: boolean;
}

export interface SettingsSetResult extends OkResult {
  namespace: string;
  key: string;
}

export interface IntentResult extends OkResult {
  output: string;
}

/** chat.sendMessageAndWait：timedOut=true 时 status 仍是 "running"，text 可能是空串。 */
export interface ChatSendAndWaitResult {
  sessionId: string;
  text: string;
  hasReply: boolean;
  messageId?: string;
  timestamp?: number;
  status: string;
  timedOut: boolean;
  run: ChatRunState;
}

/** agent.runSubAgent：id 是新建的子会话 id，state 通常是 "running"。 */
export interface SubAgentResult {
  ok: boolean;
  /** 父会话 id。 */
  sessionId: string;
  id?: string;
  state?: string;
  message?: string;
}

export interface PortForwardInfo {
  id: string;
  localPort: number;
  remoteHost: string;
  remotePort: number;
  url: string;
  direct: boolean;
}

/**
 * 方法表：方法名 → 参数与返回结构。
 *
 * 参数里没写 `?` 的键是宿主用 requireString 之类必读的，缺了会 reject；
 * 结果里的可选键是在特定条件下才出现的（截断标记、附加信息等）。
 */
export interface HostMethodMap {
  // ---- 文件：路径是当前执行环境的真实绝对路径，data:// 指向插件自己的数据目录 ----
  // contextKey 是页面侧的可选防跨上下文参数：宿主会把它与当前页面上下文比对，不一致时回 STALE_CONTEXT。
  "fs.read": {
    params: { path: string; contextKey?: string };
    result: FileContent;
  };
  "fs.write": {
    params: { path: string; content: string; contextKey?: string };
    result: OkResult;
  };
  "fs.list": {
    params: { path: string; contextKey?: string };
    result: FileListResult;
  };
  "fs.delete": {
    params: { path: string; contextKey?: string };
    result: OkResult;
  };
  "fs.stat": {
    params: { path: string; contextKey?: string };
    result: FileStat;
  };
  "fs.mkdir": {
    params: { path: string; contextKey?: string };
    result: OkResult;
  };
  "fs.rename": {
    params: { from: string; to: string; contextKey?: string };
    result: OkResult;
  };
  "fs.copy": {
    params: { from: string; to: string; overwrite?: boolean; contextKey?: string };
    result: OkResult;
  };
  "fs.append": {
    params: { path: string; content: string; contextKey?: string };
    result: OkResult;
  };
  "fs.readBinary": {
    params: { path: string; contextKey?: string };
    result: BinaryContent;
  };
  "fs.writeBinary": {
    params: { path: string; base64: string; contextKey?: string };
    result: OkResult;
  };
  /** 唯一替换：oldContent 找不到回 CONTENT_NOT_FOUND，多处命中且没传 replaceAll 回 CONTENT_NOT_UNIQUE。 */
  "fs.edit": {
    params: { path: string; oldContent: string; newContent: string; replaceAll?: boolean; contextKey?: string };
    result: FsEditResult;
  };
  /** 按字节区间读（左闭右开）；end 缺省读到文件尾，整文件仍受 readBinary 的大小上限约束。 */
  "fs.readRange": {
    params: { path: string; start?: number; end?: number; maxBytes?: number; contextKey?: string };
    result: FsRangeResult;
  };
  /** 按文件名找：带 * / ? 按通配符，否则按包含匹配（都不区分大小写）。 */
  "fs.find": {
    params: { path: string; pattern: string; limit?: number; contextKey?: string };
    result: FsFindResult;
  };
  /** 按内容找：合法正则按正则，不合法的 pattern 退化为字面匹配。 */
  "fs.grep": {
    params: { path: string; pattern: string; limit?: number; contextKey?: string };
    result: FsGrepResult;
  };
  /** 解压 zip 到 target 目录；绝对路径或含 .. 的条目直接回 PATH_DENIED，单条目上限 1 MiB。 */
  "fs.unzip": {
    params: { path: string; target: string; contextKey?: string };
    result: FsUnzipResult;
  };
  /** 仅页面：订阅文件变化，事件经 window.__aharouEvent 推回（name = "fs.changed"）。 */
  "fs.watch": {
    params: { subscriptionId: string; path: string; recursive?: boolean; contextKey?: string };
    result: { subscriptionId: string };
  };
  /** 仅页面：取消订阅。 */
  "fs.unwatch": {
    params: { subscriptionId: string };
    result: OkResult;
  };
  // ---- 工作流：脚本与面板都能调；模板注册进来默认禁用，需显式 setEnabled ----
  "workflow.getAll": {
    params: NoParams;
    result: JsonValue;
  };
  "workflow.create": {
    params: { name: string; description?: string; nodes?: JsonValue[]; connections?: JsonValue[] };
    result: JsonValue;
  };
  "workflow.get": {
    params: { id: string };
    result: JsonValue;
  };
  "workflow.update": {
    params: { id: string; name?: string; description?: string; nodes?: JsonValue[]; connections?: JsonValue[] };
    result: JsonValue;
  };
  "workflow.delete": {
    params: { id: string };
    result: JsonValue;
  };
  "workflow.setEnabled": {
    params: { id: string; enabled: boolean };
    result: JsonValue;
  };
  "workflow.trigger": {
    params: { id: string };
    result: JsonValue;
  };
  "workflow.registerTemplate": {
    params: { template: JsonValue; sourcePluginId?: string };
    result: JsonValue;
  };

  // ---- 宿主与进程 ----
  /** 宿主 App 是否在前台；三类来源都能调。 */
  "app.isForeground": {
    params: NoParams;
    result: boolean;
  };
  "app.info": {
    params: { package?: string };
    result:
      | {
          packageName: string;
          versionName: string;
          versionCode: number;
          sdkInt: number;
          languageTag: string;
          isForeground: boolean;
        }
      | InstalledAppInfo;
  };
  /** 启动应用：只给 package 时用 monkey 走 LAUNCHER，给 component 时改走 am start。 */
  "app.start": {
    params: { package: string; component?: string };
    result: AppLaunchResult;
  };
  /** am force-stop；停止宿主自己会被拒（PARAM_DENIED）。 */
  "app.stop": {
    params: { package: string };
    result: OkResult & { package: string };
  };
  "app.list": {
    params: { includeSystem?: boolean; filter?: string };
    result: AppListResult;
  };
  /** 读最近通知；需要用户在系统里开「通知使用权」，没开回 NOTIFICATION_ACCESS_DENIED。 */
  "notification.list": {
    params: { limit?: number; package?: string };
    result: NotificationListResult;
  };
  /** 读系统设置（需要插件声明 shizuku 且使用者已授权）。 */
  "system.settingsGet": {
    params: { namespace: "system" | "secure" | "global"; key: string };
    result: SettingsGetResult;
  };
  "system.settingsSet": {
    params: { namespace: "system" | "secure" | "global"; key: string; value: string };
    result: SettingsSetResult;
  };
  /** 发 Intent；action / data / package / component 至少给一项，extras 只支持字符串值。 */
  "intent.start": {
    params: {
      action?: string;
      data?: string;
      package?: string;
      component?: string;
      extras?: Record<string, string>;
    };
    result: IntentResult;
  };
  "intent.broadcast": {
    params: {
      action?: string;
      data?: string;
      package?: string;
      component?: string;
      extras?: Record<string, string>;
    };
    result: IntentResult;
  };
  /** 在当前工作区执行一次性命令（超时单位秒）。 */
  bash: {
    params: { command: string; timeout?: number };
    result: ExecResult;
  };
  "shizuku.exec": {
    params: { command: string; timeout?: number };
    result: ExecResult;
  };
  /** 仅进程：发系统通知；key 非空时可用来覆盖同一条通知。 */
  notify: {
    params: { title: string; body: string; key?: string };
    result: OkResult;
  };
  /** 在对话内显示提示条；带 action 时会回调插件进程的 handler。 */
  tip: {
    params: {
      text: string;
      title?: string;
      action?: { label: string; handler: string };
    };
    result: { tipId: string; queued: true };
  };

  // ---- 设备：仅进程（薄封无障碍工具执行，页面不开放） ----
  /** 点按：id 来自 dump/find，或直接给 x / y；label 是手指下/目标文字。 */
  "device.tap": {
    params: { id?: string; x?: number; y?: number; label?: string };
    result: DeviceActionResult;
  };
  /** 长按：给 id 时按住元素中心；duration 默认 600ms（范围 300~5000）。 */
  "device.longPress": {
    params: { id?: string; x?: number; y?: number; duration?: number; label?: string };
    result: DeviceActionResult;
  };
  "device.swipe": {
    params: { x: number; y: number; x2: number; y2: number; duration?: number; label?: string };
    result: DeviceActionResult;
  };
  "device.key": {
    params: { key: "back" | "home" | "recents" | "notifications" };
    result: DeviceActionResult;
  };
  "device.setText": {
    params: { id: string; text: string; label?: string };
    result: DeviceActionResult;
  };
  /** 前台应用与 Activity、屏幕像素、窗口数、键盘是否在；无障碍未开回 A11Y_NOT_ENABLED。 */
  "device.pageInfo": {
    params: NoParams;
    result: DevicePageInfo;
  };
  /** 系统截图，Android 11+；图片以 Base64 直接返回。 */
  "device.screenshot": {
    params: NoParams;
    result: DeviceScreenshotResult;
  };

  // ---- 浮层 ----
  /** 创建浮层；surface 缺省时用清单里声明的第一个 surface。返回的 x / y 是窗口像素。 */
  "overlay.create": {
    params: {
      surface?: string;
      width?: number;
      height?: number;
      visibilityPolicy?: OverlayVisibilityPolicy;
    };
    result: OverlayDescription;
  };
  "overlay.update": {
    params: {
      overlayId: string;
      dx?: number;
      dy?: number;
      width?: number;
      height?: number;
      visible?: boolean;
      visibilityPolicy?: OverlayVisibilityPolicy;
    };
    result: OkResult;
  };
  /**
   * 关闭浮层。页面/进程按 overlayId（进程也可改用 surface 定位）关闭，返回 { ok: true }；
   * 浮层页面自己调用时返回 null。
   */
  "overlay.close": {
    params: { overlayId?: string; surface?: string };
    result: OkResult | null;
  };
  /**
   * 给浮层页面推消息（浮层侧经 __aharouOverlayMessage 收到）。页面/进程返回 { ok: true }；
   * 浮层页面自己调用时转给插件进程，返回 null。
   */
  "overlay.postMessage": {
    params: { overlayId?: string; surface?: string; payload?: JsonValue };
    result: OkResult | null;
  };
  /** 仅浮层：自身窗口信息，x / y 是 dp。 */
  "overlay.self": {
    params: NoParams;
    result: OverlaySelfInfo;
  };
  /** 仅浮层：always / foreground / background。 */
  "overlay.setVisibilityPolicy": {
    params: { policy: OverlayVisibilityPolicy };
    result: null;
  };
  /** 仅浮层：拖拽热区（CSS 像素，宽高必须为正）。 */
  "overlay.setDragRegion": {
    params: { left: number; top: number; width: number; height: number };
    result: null;
  };
  /** 仅浮层：按 dp 位移。 */
  "overlay.moveBy": {
    params: { dx?: number; dy?: number };
    result: null;
  };
  /** 仅浮层：调整自身尺寸（dp），可选同时移动。 */
  "overlay.resize": {
    params: { width: number; height: number; animate?: boolean; x?: number; y?: number };
    result: { width: number; height: number };
  };

  // ---- 共享终端 ----
  "terminal.run": {
    params: { command: string; title?: string };
    result: TerminalRunResult;
  };
  "terminal.poll": {
    params: { tabId: string; offset?: number };
    result: TerminalPollResult;
  };
  "terminal.stop": {
    params: { tabId: string };
    result: OkResult;
  };
  "terminal.close": {
    params: { tabId: string };
    result: OkResult;
  };
  "terminal.write": {
    params: { tabId: string; text: string; appendNewline?: boolean };
    result: { ok: boolean };
  };
  /** 直接返回数组，不是 { tabs: [...] }。 */
  "terminal.list": {
    params: NoParams;
    result: TerminalTab[];
  };
  /** 仅进程：不建标签地跑一条命令并等结果（超时单位秒，默认 30、上限 300）。 */
  "terminal.hiddenExec": {
    params: { command: string; timeout?: number };
    result: ExecResult;
  };

  // ---- 会话与消息 ----
  "chat.listSessions": {
    params: { includeSubagents?: boolean; limit?: number };
    result: { sessions: ChatSession[] };
  };
  "chat.createSession": {
    params: { title?: string; mode?: "build" | "plan" };
    result: { session: ChatSession };
  };
  "chat.listMessages": {
    params: { sessionId: string; limit?: number; before?: number; after?: number };
    result: { sessionId: string; messages: ChatMessage[]; hasMore: boolean };
  };
  "chat.sendMessage": {
    params: { sessionId: string; text: string };
    result: { sessionId: string; accepted: true };
  };
  "chat.setDraft": {
    params: { sessionId?: string; text: string; append?: boolean };
    result: { sessionId?: string; accepted: true };
  };
  "chat.stop": {
    params: { sessionId: string };
    result: { sessionId: string; accepted: true };
  };
  "chat.openSession": {
    params: { sessionId: string };
    result: { sessionId: string; accepted: true };
  };
  "chat.getSession": {
    params: { sessionId: string };
    result: { session: ChatSession };
  };
  "chat.renameSession": {
    params: { sessionId: string; title: string };
    result: OkResult & { session: ChatSession };
  };
  "chat.pinSession": {
    params: { sessionId: string; pinned: boolean };
    result: OkResult & { session: ChatSession };
  };
  "chat.deleteSession": {
    params: { sessionId: string };
    result: { sessionId: string; accepted: true };
  };
  "chat.getRunState": {
    params: { sessionId: string };
    result: { sessionId: string; run: ChatRunState };
  };
  "chat.waitForRun": {
    params: { sessionId: string; timeoutMs?: number };
    result: { sessionId: string; timedOut: boolean; run: ChatRunState };
  };
  /**
   * 发一条消息并等到这一轮跑完，返回最后一条助手回复。
   * 会话本来在跑回 CHAT_BUSY；本轮到超时都没开始回 CHAT_RUN_NOT_STARTED；
   * 用户中途按停止不算失败（run 仍会是 completed），只是 text 可能为空或只有半截。
   */
  "chat.sendMessageAndWait": {
    params: { sessionId: string; text: string; timeoutMs?: number };
    result: ChatSendAndWaitResult;
  };
  "chat.listApprovals": {
    params: { sessionId: string };
    result: {
      sessionId: string;
      tool: ChatToolApproval | null;
      question: ChatQuestion | null;
      plan: ChatPlanApproval | null;
    };
  };
  "chat.resolveToolApproval": {
    params: {
      sessionId: string;
      requestId: string;
      choice: "once" | "allow" | "always" | "reject" | "deny";
    };
    result: OkResult;
  };
  "chat.resolveQuestion": {
    params: {
      sessionId: string;
      requestId: string;
      answers: { question: string; selected?: string[]; customText?: string }[];
    };
    result: OkResult;
  };
  "chat.resolvePlanApproval": {
    params: { sessionId: string; requestId: string; choice: "approve" | "refine" | "reject" };
    result: OkResult;
  };

  // ---- 模型句柄：直接打已配置的供应商，不经过聊天页 ----
  "model.listProviders": {
    params: NoParams;
    result: { providers: ModelProviderInfo[] };
  };
  "model.listModels": {
    params: { providerId: string };
    result: { providerId: string; models: ModelInfo[]; truncated?: boolean };
  };
  "model.create": {
    params: {
      providerId: string;
      model?: string;
      systemPrompt?: string;
      params?: ModelParams;
      name?: string;
    };
    result: { handle: ModelHandle };
  };
  "model.close": {
    params: { handleId: string };
    result: OkResult;
  };
  "model.start": {
    params: {
      handleId: string;
      messages: ModelInputMessage[];
      tools?: ModelToolRef[];
      systemPrompt?: string;
      params?: ModelParams;
    };
    result: { streamId: string };
  };
  /** 拉取一批流事件；waitMs 内没有新事件就返回空批次。cursor 用上次返回的值。 */
  "model.read": {
    params: { streamId: string; cursor?: number; maxEvents?: number; waitMs?: number };
    result: ModelReadResult;
  };
  "model.cancel": {
    params: { streamId: string };
    result: OkResult;
  };

  // ---- 系统能力 ----
  "clipboard.read": {
    params: NoParams;
    result: { text: string };
  };
  "clipboard.write": {
    params: { text: string };
    result: OkResult;
  };
  "share.text": {
    params: { text: string; title?: string };
    result: OkResult;
  };
  "ui.openUrl": {
    params: { url: string };
    result: OkResult;
  };
  /** 仅页面：按矩形在全局背景图上挖洞（页面内全屏弹层的背景）。 */
  "ui.overlay": {
    params: {
      visible: boolean;
      rect?: {
        left: number;
        top: number;
        width: number;
        height: number;
        topRadius?: number;
        bottomRadius?: number;
        coversBottom?: boolean;
      };
    };
    result: OkResult;
  };
  /** 仅页面：关闭当前插件页。 */
  "ui.close": {
    params: NoParams;
    result: OkResult;
  };
  /** 仅页面：系统 Toast。 */
  toast: {
    params: { message: string };
    result: OkResult;
  };
  /** 仅页面：读宿主 WebView 里某个站点的 cookie。 */
  "cookies.get": {
    params: { url: string };
    result: { cookie: string; empty: boolean };
  };

  // ---- 日志：进程记 log、页面记 log.write，两个名字历史上就各走一边 ----
  /** 仅进程。 */
  log: {
    params: { level: "debug" | "info" | "warn" | "error"; message: string; tag?: string };
    result: OkResult;
  };
  /** 仅页面。 */
  "log.write": {
    params: { level: "debug" | "info" | "warn" | "error"; message: string; tag?: string };
    result: OkResult;
  };

  // ---- 插件私有密钥（加密落盘，键名 1~64 位 [A-Za-z0-9._-]） ----
  "secrets.get": {
    params: { key: string };
    result: { value: string };
  };
  "secrets.set": {
    params: { key: string; value: string };
    result: OkResult;
  };
  "secrets.delete": {
    params: { key: string };
    result: OkResult;
  };
  "secrets.list": {
    params: NoParams;
    result: { keys: string[] };
  };

  // ---- 插件自己的配置（加密落盘，键名 1~128 位 [A-Za-z0-9._-]；与 secrets 的区别是 list 返回值） ----
  /** 读一个键；没存过时 value 是 default（没传就空串），found=false。 */
  "env.get": {
    params: { key: string; default?: string };
    result: EnvGetResult;
  };
  "env.set": {
    params: { key: string; value: string };
    result: OkResult;
  };
  /** prefix 非空时只返回以它开头的键。 */
  "env.list": {
    params: { prefix?: string };
    result: EnvListResult;
  };

  // ---- 工具与 MCP ----
  "tools.list": {
    params: { includeSchema?: boolean };
    result: { tools: ToolListItem[] };
  };
  /** 只允许内置与 MCP 工具；plugin__ 命名空间与需要聊天会话的工具会被拒绝。 */
  "tools.execute": {
    params: {
      name: string;
      arguments?: JsonObject;
      sessionId?: string;
      context?: { path?: string; selection?: string; language?: string };
      timeoutMs?: number;
    };
    result: ToolExecuteResult;
  };
  "tools.pending": {
    params: { sessionId?: string };
    result: ToolPendingResult;
  };
  "tools.resolve": {
    params: { requestId: string; choice: "once" | "always" | "reject" };
    result: OkResult;
  };
  /** 仅进程：派一个子代理（复用 task 工具的创建逻辑；不在这里等结果，用 chat.* 取进展）。 */
  "agent.runSubAgent": {
    params: {
      sessionId: string;
      prompt: string;
      description?: string;
      agent?: string;
      department?: string;
      agentQuery?: string;
      model?: string;
      provider?: string;
      writePaths?: string[];
    };
    result: SubAgentResult;
  };
  "mcp.status": {
    params: NoParams;
    result: { servers: McpServerStatus[] };
  };
  /** 不注入宿主凭据、默认不跟随重定向；timeout 单位秒。 */
  "http.request": {
    params: {
      url: string;
      method?: string;
      headers?: Record<string, string>;
      body?: string;
      timeout?: number;
      /** 默认 false（与扩展前一致）；true 时才跟随重定向。 */
      followRedirects?: boolean;
      /** 响应体上限（字节），默认 1048576，范围 1~33554432。 */
      maxBytes?: number;
      /** 二进制请求体；与 body / multipart 三者互斥。 */
      bodyBase64?: string;
      /** true 时结果额外带 bodyBase64。 */
      responseBodyBase64?: boolean;
      /** multipart 表单；与 body / bodyBase64 互斥。 */
      multipart?: HttpMultipartBody;
      /** 只在显式 true 时跳过证书校验（为自签证书的局域网服务存在）。 */
      ignoreSsl?: boolean;
    };
    result: HttpRequestResult;
  };

  // ---- 端口转发（仅进程） ----
  "ports.forward": {
    params: { port: number; localPort?: number; host?: string };
    result: PortForwardInfo;
  };
  "ports.close": {
    params: { id: string };
    result: { closed: boolean };
  };
  /** 直接返回数组，不是 { ports: [...] }。 */
  "ports.list": {
    params: NoParams;
    result: PortForwardInfo[];
  };

  /** 仅页面：调用同一插件的常驻进程 handler，返回 handler 的 result。 */
  "plugin.invoke": {
    params: { handler: string; args?: JsonObject };
    result: JsonValue;
  };

  /** 入口上下文；页面与浮层返回的字段不同，见 PageContextResult / OverlayContextResult。 */
  context: {
    params: NoParams;
    result: PageContextResult | OverlayContextResult;
  };

  // ---- 音乐播放（仅进程）：Aharou 自己的 ExoPlayer；本地没装过音源时转为遥控系统正在播的播放器 ----
  /**
   * 播一首。source 给了就播它（source_type 必填），没给则：已装过音源就继续播，
   * 否则遥控系统播放器（结果里 remote=true；拿不到会话回 NO_SESSION / NO_SESSION_PERMISSION）。
   * start_position_ms 只在新建播放时生效；volume 取值 0~1。
   */
  "music.play": {
    params: {
      source?: string;
      source_type?: MusicSourceType;
      sourceType?: MusicSourceType;
      title?: string;
      artist?: string;
      loop?: boolean;
      volume?: number;
      start_position_ms?: number;
      startPositionMs?: number;
    };
    result: MusicPlaybackResult;
  };
  /** items 不能为空；start_index 越界回 PARAM_INVALID。 */
  "music.playQueue": {
    params: {
      items: MusicTrackInput[];
      loop?: boolean;
      volume?: number;
      start_index?: number;
      startIndex?: number;
      start_position_ms?: number;
      startPositionMs?: number;
    };
    result: MusicPlaybackResult;
  };
  "music.pause": {
    params: NoParams;
    result: MusicPlaybackResult;
  };
  "music.resume": {
    params: NoParams;
    result: MusicPlaybackResult;
  };
  "music.stop": {
    params: NoParams;
    result: MusicPlaybackResult;
  };
  /** 毫秒；超过时长会被夹到时长，负数回 PARAM_INVALID。 */
  "music.seek": {
    params: { position_ms?: number; positionMs?: number };
    result: MusicPlaybackResult;
  };
  /** 本地播放时改播放器音量；遥控别人播放器时改的是系统媒体音量，结果 state=remote_volume。 */
  "music.setVolume": {
    params: { volume: number };
    result: MusicPlaybackResult | MusicRemoteVolumeResult;
  };
  "music.status": {
    params: NoParams;
    result: MusicPlaybackResult;
  };

  // ---- 经典蓝牙（仅进程）：插件进程弹不了系统权限框，permission 只报缺什么 ----
  "bluetooth.permission": {
    params: NoParams;
    result: BluetoothPermissionResult;
  };
  "bluetooth.state": {
    params: NoParams;
    result: BluetoothStateResult;
  };
  "bluetooth.requestEnable": {
    params: NoParams;
    result: BluetoothEnableResult;
  };
  "bluetooth.bondedDevices": {
    params: NoParams;
    result: BluetoothBondedDevicesResult;
  };
  /** duration_ms 缺省走宿主默认；include_ble 缺省 true。 */
  "bluetooth.scan": {
    params: {
      duration_ms?: number;
      durationMs?: number;
      include_ble?: boolean;
      includeBle?: boolean;
    };
    result: BluetoothScanResult;
  };
  /** uuid 缺省用串口服务号；timeout_ms 缺省走宿主默认。 */
  "bluetooth.connect": {
    params: {
      address: string;
      uuid?: string;
      timeout_ms?: number;
      timeoutMs?: number;
    };
    result: BluetoothSession;
  };
  "bluetooth.listen": {
    params: { name?: string; uuid?: string };
    result: BluetoothSession;
  };
  /** listener_session_id / listener_id 二选一必填。 */
  "bluetooth.accept": {
    params: {
      listener_session_id?: string;
      listener_id?: string;
      timeout_ms?: number;
      timeoutMs?: number;
    };
    result: BluetoothSession;
  };
  /** text 与 data_base64 二选一；session_id / sessionId 二选一必填。 */
  "bluetooth.send": {
    params: BluetoothWriteParams;
    result: BluetoothTransferResult;
  };
  "bluetooth.read": {
    params: BluetoothReadParams;
    result: BluetoothReadResult;
  };
  "bluetooth.sendAndRead": {
    params: BluetoothWriteParams & { max_bytes?: number; maxBytes?: number; timeout_ms?: number; timeoutMs?: number };
    result: BluetoothReadResult;
  };
  /** 经典会话、监听器、BLE 会话共用一个入口，按 id 关。 */
  "bluetooth.close": {
    params: { session_id?: string; sessionId?: string };
    result: OkResult;
  };

  // ---- BLE（仅进程）：同一个蓝牙服务，session_id 指向 ble.connect 或 discoverServices 拿到的会话 ----
  "ble.connect": {
    params: {
      address: string;
      auto_connect?: boolean;
      autoConnect?: boolean;
      timeout_ms?: number;
      timeoutMs?: number;
    };
    result: BluetoothSession;
  };
  "ble.discoverServices": {
    params: { session_id?: string; sessionId?: string; timeout_ms?: number; timeoutMs?: number };
    result: BleServicesResult;
  };
  "ble.readCharacteristic": {
    params: {
      session_id?: string;
      sessionId?: string;
      service_uuid: string;
      characteristic_uuid: string;
      timeout_ms?: number;
      timeoutMs?: number;
    };
    result: BluetoothReadResult;
  };
  "ble.writeCharacteristic": {
    params: {
      session_id?: string;
      sessionId?: string;
      service_uuid: string;
      characteristic_uuid: string;
      text?: string;
      data_base64?: string;
      dataBase64?: string;
    };
    result: BluetoothTransferResult;
  };
  "ble.writeAndReadCharacteristic": {
    params: {
      session_id?: string;
      sessionId?: string;
      write_service_uuid: string;
      write_characteristic_uuid: string;
      text?: string;
      data_base64?: string;
      dataBase64?: string;
      read_service_uuid: string;
      read_characteristic_uuid: string;
      timeout_ms?: number;
      timeoutMs?: number;
    };
    result: BluetoothReadResult;
  };
  /** enable 缺省 true；关订阅也走这个方法。 */
  "ble.subscribe": {
    params: {
      session_id?: string;
      sessionId?: string;
      service_uuid: string;
      characteristic_uuid: string;
      enable?: boolean;
      timeout_ms?: number;
      timeoutMs?: number;
    };
    result: BluetoothTransferResult;
  };
  /** 通知是「取走即清空」的语义。 */
  "ble.readNotifications": {
    params: { session_id?: string; sessionId?: string; limit?: number };
    result: BleNotificationsResult;
  };

  // ---- 记忆图谱（仅进程）：Aharou 没有文档分块，按标题或关键词拿整条节点 ----
  /** threshold 是相似度下限；query 为 "*" 且没给 limit 时返回全部，否则默认 20 条。 */
  "memory.query": {
    params: {
      query: string;
      folder_path?: string;
      start_time?: string;
      end_time?: string;
      limit?: number;
      threshold?: number;
      snapshot_id?: string;
    };
    result: MemoryQueryResult;
  };
  /** 不支持分块读取：带 chunk_index / chunk_range / query 直接回 CHUNK_UNSUPPORTED。 */
  "memory.getByTitle": {
    params: { title: string; chunk_index?: string; chunk_range?: string; query?: string };
    result: MemoryGetByTitleResult;
  };
  "memory.create": {
    params: {
      title: string;
      content: string;
      content_type?: string;
      source?: string;
      folder_path?: string;
      tags?: string[] | string;
    };
    result: { memory: MemoryEntry; message: string };
  };
  /** 按 old_title 定位，只改出现的字段；至少给一个要改的字段。 */
  "memory.update": {
    params: {
      old_title: string;
      new_title?: string;
      content?: string;
      content_type?: string;
      source?: string;
      credibility?: number;
      importance?: number;
      folder_path?: string;
      tags?: string[] | string;
    };
    result: { memory: MemoryEntry; previousTitle: string; message: string };
  };
  "memory.deleteMemory": {
    params: { title: string };
    result: { deletedTitle: string; removedLinks: number; message: string };
  };
  /** target_folder_path 必填（移到「未分类」传空串）；titles 与 source_folder_path 至少给一个。 */
  "memory.move": {
    params: {
      target_folder_path: string;
      titles?: string[];
      source_folder_path?: string;
    };
    result: MemoryMoveResult;
  };
  /** link_type 缺省 related，weight 缺省 0.7。 */
  "memory.link": {
    params: {
      source_title: string;
      target_title: string;
      link_type?: string;
      weight?: number;
      description?: string;
    };
    result: { link: MemoryLinkEntry; created: boolean; message: string };
  };
  "memory.queryLinks": {
    params: {
      link_id?: string;
      source_title?: string;
      target_title?: string;
      link_type?: string;
      limit?: number;
    };
    result: { totalCount: number; links: MemoryLinkEntry[] };
  };
  "memory.updateLink": {
    params: {
      link_id?: string;
      source_title?: string;
      target_title?: string;
      link_type?: string;
      new_link_type?: string;
      weight?: number;
      description?: string;
    };
    result: { link: MemoryLinkEntry; message: string };
  };
  "memory.deleteLink": {
    params: {
      link_id?: string;
      source_title?: string;
      target_title?: string;
      link_type?: string;
    };
    result: { deletedLinkId: string; sourceTitle: string; targetTitle: string; message: string };
  };

  // ---- 角色卡（仅进程）：id 可用 character_card_id / characterCardId / id 三种写法 ----
  "characterCard.list": {
    params: NoParams;
    result: CharacterCardListResult;
  };
  "characterCard.get": {
    params: CharacterCardIdParams;
    result: { card: CharacterCard; activeCharacterCardId: string | null };
  };
  /** 创建需要 name；其余字段只写出现的那些。 */
  "characterCard.create": {
    params: CharacterCardWriteParams;
    result: CharacterCardWriteResult;
  };
  /** 只改出现的字段，一个都没给回 PARAM_INVALID。 */
  "characterCard.update": {
    params: CharacterCardIdParams & CharacterCardWriteParams;
    result: CharacterCardWriteResult;
  };
  "characterCard.delete": {
    params: CharacterCardIdParams;
    result: { deleted: true; characterCardId: string; activeCharacterCardId: string | null };
  };
  "characterCard.setActive": {
    params: CharacterCardIdParams;
    result: { activeCharacterCardId: string | null };
  };
  "characterCard.clearActive": {
    params: NoParams;
    result: { activeCharacterCardId: null };
  };
  /** tavern_json 可以是 JSON 文本或已解析的对象；attachedTags 的标签对象只取 name 存进 tags。 */
  "characterCard.importTavern": {
    params: { tavern_json?: string | JsonObject; tavernJson?: string | JsonObject };
    result: { imported: true; card: CharacterCard; activeCharacterCardId: string | null };
  };
  "characterCard.exportTavern": {
    params: CharacterCardIdParams;
    result: { characterCardId: string; tavernJson: string };
  };

  // ---- 软件设置（仅进程）：Aharou 的供应商都是平级的，没有 Operit 的「默认配置」概念 ----
  "softwareSettings.getSpeechServicesConfig": {
    params: NoParams;
    result: SpeechServicesConfig;
  };
  /**
   * 改语音设置：只改出现的键；认不出的键（与 Operit 专属键）原样出现在结果的 ignored 里，不被静默吞掉。
   * tts_service_type 取 SYSTEM / AHAROU_CLOUD；切到云端时必须同时给 tts_provider。
   */
  "softwareSettings.setSpeechServicesConfig": {
    params: SpeechServicesConfigPatch;
    result: SpeechServicesConfig & { applied: string[]; ignored: string[] };
  };
  /** text 上限 2000 字符；interrupt 缺省 true。 */
  "softwareSettings.testTtsPlayback": {
    params: { text: string; interrupt?: boolean };
    result: TtsPlaybackTestResult;
  };
  "softwareSettings.listModelConfigs": {
    params: NoParams;
    result: ModelConfigListResult;
  };
  /** api_endpoint 必填；api_provider_type 缺省 OPENAI，只接受 OPENAI / ANTHROPIC / GEMINI。 */
  "softwareSettings.createModelConfig": {
    params: ModelConfigWriteParams;
    result: { created: true; config: ModelConfigItem };
  };
  /** 按 config_id 定位，只改出现的字段，一个都没给回 PARAM_INVALID。 */
  "softwareSettings.updateModelConfig": {
    params: ModelConfigIdParams & ModelConfigWriteParams;
    result: { updated: true; config: ModelConfigItem; changedFields: string[] };
  };
  "softwareSettings.deleteModelConfig": {
    params: ModelConfigIdParams;
    result: { deleted: true; configId: string };
  };
  "softwareSettings.listFunctionModelConfigs": {
    params: NoParams;
    result: { defaultConfigId: ""; mappings: FunctionModelMapping[] };
  };
  /** function_type 取值见 listFunctionModelConfigs 的 mappings（如 CHAT）；不支持的类型回 UNSUPPORTED。 */
  "softwareSettings.getFunctionModelConfig": {
    params: { function_type?: string; functionType?: string };
    result: FunctionModelBinding;
  };
  /** config_id 必须存在且该供应商已有模型；model_index 越界会被夹到有效范围。 */
  "softwareSettings.setFunctionModelConfig": {
    params: {
      function_type?: string;
      functionType?: string;
      config_id?: string;
      configId?: string;
      model_index?: number;
    };
    result: FunctionModelBindingResult;
  };
  /** 走真实网络请求拉模型列表；失败不 reject，而是 success=false、failedTests=1 并带 error。 */
  "softwareSettings.testModelConfigConnection": {
    params: ModelConfigIdParams & { model_index?: number };
    result: ModelConfigTestResult;
  };
  /** 暂不支持：Aharou 没有 Operit 的沙盒包（QuickJS 脚本包）体系，调用必 reject UNSUPPORTED。 */
  "softwareSettings.listSandboxPackages": {
    params: NoParams;
    result: never;
  };
  /** 暂不支持：没有沙盒包可启停，调用必 reject UNSUPPORTED（参数不被读取）。 */
  "softwareSettings.setSandboxPackageEnabled": {
    params: Record<string, JsonValue>;
    result: never;
  };
  /** 暂不支持：没有沙盒运行时，调用必 reject UNSUPPORTED（参数不被读取）；要跑脚本用 bash / terminal.run。 */
  "softwareSettings.executeSandboxScriptDirect": {
    params: Record<string, JsonValue>;
    result: never;
  };
  /** 暂不支持：Aharou 的 MCP 由会话层按需连接，没有常驻进程可重启，调用必 reject UNSUPPORTED（参数不被读取）。 */
  "softwareSettings.restartMcpWithLogs": {
    params: Record<string, JsonValue>;
    result: never;
  };

  /** 暂不支持：Aharou 的事件触发只认通知 / 开机 / 电量 / 网络 / 应用启动，调用必 reject UNSUPPORTED（参数不被读取）。 */
  "tasker.triggerEvent": {
    params: Record<string, JsonValue>;
    result: never;
  };

  // ---- 浏览器（仅进程）：驱动内置 WebView 标签页池（BrowserTabPool），失败走 reject、code 见 BrowserErrorCode ----
  // 宿主只有一个会话（session_id 恒为 "default"）；v2 里宿主没实现的那部分参数（headers / modifiers /
  // doubleClick / slowly / type=png …）不会报错，而是在返回的 result 末尾另起一行说明。
  /**
   * 启动会话。给 url 就直接导航，否则开一个空白标签页；user_agent 同时改池里后续标签页的 UA。
   * headers / session_name 不支持，会在 result 末尾追加一行说明，不失败。
   */
  "browser.start": {
    params: {
      url?: string;
      user_agent?: string;
      headers?: Record<string, string> | string;
      session_name?: string;
    };
    result: BrowserSessionResult;
  };
  /** 关闭全部标签页；session_id 只接受 "default"，其它值回 PARAM_INVALID。 */
  "browser.stop": {
    params: { session_id?: string; close_all?: boolean };
    result: BrowserStopResult;
  };
  /**
   * 导航。url 与 v2 的 urlOrOptions 二选一必填（urlOrOptions 可以是字符串或 { url, headers }）；
   * headers 不支持，会在 result 末尾说明。
   */
  "browser.navigate": {
    params: {
      url?: string;
      headers?: Record<string, string> | string;
      urlOrOptions?: string | { url: string; headers?: Record<string, string> | string };
    };
    result: BrowserResult;
  };
  /** 返回上一页；没有历史时页面不变，不报错。 */
  "browser.navigateBack": {
    params: NoParams;
    result: BrowserPageResult;
  };
  /**
   * 点击元素：selector 优先，只有 ref 时用 get_backbone 的引用（ref=…）。
   * button / modifiers / doubleClick 不派发，会在 result 末尾说明。
   */
  "browser.click": {
    params: {
      selector?: string;
      ref?: string;
      element?: string;
      button?: "left" | "right" | "middle";
      modifiers?: Array<"Alt" | "Control" | "ControlOrMeta" | "Meta" | "Shift">;
      doubleClick?: boolean;
    };
    result: BrowserResult;
  };
  /** 往输入框写文本。submit 实际是写完后按 Enter（宿主没有提交表单原语）；slowly 不支持。 */
  "browser.type": {
    params: {
      ref?: string;
      selector?: string;
      text: string;
      element?: string;
      submit?: boolean;
      slowly?: boolean;
    };
    result: BrowserResult;
  };
  /** 按页面快捷键（Enter / Escape / Tab / Arrow* / Backspace / Delete / 空格等）；key 与 keyOrOptions 二选一必填。 */
  "browser.press": {
    params: { key?: string; keyOrOptions?: string | { key: string } };
    result: BrowserResult;
  };
  /** <select> 选中 values；多个值时逐项选中，返回里带 selected。 */
  "browser.select": {
    params: { selector?: string; ref?: string; values: string[]; element?: string };
    result: BrowserResult | BrowserSelectResult;
  };
  "browser.hover": {
    params: { selector?: string; ref?: string; element?: string };
    result: BrowserResult;
  };
  /** 会话级视口覆盖（CSS px），同时作用于池里所有标签页。 */
  "browser.resize": {
    params: { width: number; height: number };
    result: BrowserResult;
  };
  /** 在整页作用域执行 JS（支持 await / 顶层 return），结果文本就是 JS 返回值；ref 不生效。 */
  "browser.evaluate": {
    params: { function: string; ref?: string; element?: string };
    result: BrowserResult;
  };
  /**
   * 等待：time 单位秒（上限 60s）；text 等出现；textGone / text_gone 等消失。
   * 三者至少给一个；文本类等待默认 10s，超时回 TIMEOUT。
   */
  "browser.waitFor": {
    params: { time?: number; text?: string; textGone?: string; text_gone?: string };
    result: BrowserResult;
  };
  /** 文本快照（get_backbone，节点带 ref）；depth 限层数，filename 落盘，selector 不生效。 */
  "browser.snapshot": {
    params: { filename?: string; selector?: string; depth?: number };
    result: BrowserResult;
  };
  /**
   * 截图（宿主固定 JPEG）。ref / element 不做元素裁剪，type=png 不生效；
   * 只有传 filename 才会把图写到插件可访问路径，并在结果里给 imagePath。
   */
  "browser.screenshot": {
    params: {
      type?: "png" | "jpeg";
      filename?: string;
      element?: string;
      ref?: string;
      fullPage?: boolean;
    };
    result: BrowserResult;
  };
  /** 读页面 console 缓冲；level 过滤（error / warning / debug / log），filename 落盘。 */
  "browser.console": {
    params: { level?: string; filename?: string };
    result: BrowserResult;
  };
  /** 批量填表：select* 走选中，checkbox / radio 为真才点击，其余按文本输入；返回逐字段结果。 */
  "browser.fillForm": {
    params: {
      fields: Array<{
        name: string;
        type: string;
        value: string | number | boolean | JsonValue;
        ref?: string;
        selector?: string;
      }>;
    };
    result: BrowserFillFormResult;
  };
  /** 应答挂起的 alert / confirm / prompt；需要池里有标签页，否则 NO_BROWSER_SESSION。 */
  "browser.handleDialog": {
    params: { accept: boolean; promptText?: string; prompt_text?: string };
    result: BrowserResult;
  };
  /**
   * 标签页管理：list 返回结构化 tabs；new 开新标签页；close 关第 index 个（缺省当前页）；
   * select 切到第 index 个。index 从 0 起，越界回 PARAM_INVALID。
   */
  "browser.tabs": {
    params: {
      action: "list" | "new" | "open" | "close" | "select" | "switch" | "activate";
      index?: number;
    };
    result: BrowserTabListResult | BrowserResult;
  };
  /** 关闭当前选中的标签页；池里没有标签页时回 BROWSER_ACTION_FAILED。 */
  "browser.close": {
    params: NoParams;
    result: BrowserResult;
  };
  /** 同 browser.stop：关闭全部标签页。 */
  "browser.closeAll": {
    params: { session_id?: string; close_all?: boolean };
    result: BrowserStopResult;
  };

  /**
   * 暂不支持：宿主浏览器不记录网络请求日志。方法名按 v2 保留，调用必 reject NOT_SUPPORTED；
   * 需要接口响应或精确报文请用 http.request。
   */
  "browser.networkRequests": {
    params: { includeStatic?: boolean; filename?: string };
    result: never;
  };
  /** 暂不支持：宿主浏览器没有拖拽原语。调用必 reject NOT_SUPPORTED。 */
  "browser.drag": {
    params: { startElement: string; startRef: string; endElement: string; endRef: string };
    result: never;
  };
  /** 暂不支持：宿主浏览器没有文件选择器通道。调用必 reject NOT_SUPPORTED。 */
  "browser.fileUpload": {
    params: { paths?: string[] };
    result: never;
  };
  /** 暂不支持：宿主没有 Playwright 执行环境。调用必 reject NOT_SUPPORTED。 */
  "browser.runCode": {
    params: { code: string };
    result: never;
  };
  /** 暂不支持用户脚本：宿主没有安装 / 启停 / 注入通道，调用必 reject NOT_SUPPORTED。 */
  "browser.userscriptInstall": {
    params: {
      url?: string;
      path?: string;
      source?: string;
      source_url?: string;
      source_display?: string;
    };
    result: never;
  };
  /** 暂不支持用户脚本，调用必 reject NOT_SUPPORTED。 */
  "browser.userscriptList": {
    params: { include_disabled?: boolean };
    result: never;
  };
  /** 暂不支持用户脚本，调用必 reject NOT_SUPPORTED。 */
  "browser.userscriptStart": {
    params: { script_id?: string | number; name?: string; namespace?: string; source_url?: string };
    result: never;
  };
  /** 暂不支持用户脚本，调用必 reject NOT_SUPPORTED。 */
  "browser.userscriptStop": {
    params: { script_id?: string | number; name?: string; namespace?: string; source_url?: string };
    result: never;
  };
  /** 暂不支持用户脚本，调用必 reject NOT_SUPPORTED。 */
  "browser.userscriptUninstall": {
    params: { script_id?: string | number; name?: string; namespace?: string; source_url?: string };
    result: never;
  };
}

/** browser.* 的统一返回；失败走 reject（code 见 BrowserErrorCode），不会返回 ok:false。 */
export interface BrowserResult {
  ok: true;
  /** 结果文本；宿主不支持的那部分参数会另起一行追加说明。 */
  result: string;
  /** 动作结束时的页面 URL；没有标签页的操作不出现。 */
  pageURL?: string;
  /** 实际执行动作的标签页 id。 */
  tabId?: number;
  /** 只有 browser.screenshot 传了 filename 时才有：写入后的插件内路径。 */
  imagePath?: string;
}

/** browser.start 的返回：session_id 恒为 "default"（宿主只有一个浏览器会话）。 */
export interface BrowserSessionResult extends BrowserResult {
  session_id: "default";
  tabs: number;
  selectedTabId: number;
}

/** browser.stop / browser.closeAll 的返回。 */
export interface BrowserStopResult extends BrowserResult {
  session_id: "default";
}

/** browser.navigateBack 的返回：一定带返回后的页面 URL。 */
export interface BrowserPageResult extends BrowserResult {
  pageURL: string;
}

/** browser.tabs action=list 的单条标签页。 */
export interface BrowserTabInfo {
  index: number;
  tabId: number;
  url: string;
  title: string;
  selected: boolean;
}

/** browser.tabs action=list 的返回。 */
export interface BrowserTabListResult {
  ok: true;
  result: string;
  tabs: BrowserTabInfo[];
}

/** browser.select 多值选中时的返回。 */
export interface BrowserSelectResult {
  ok: true;
  result: string;
  selected: number;
}

/** browser.fillForm 的返回：filled 是实际处理过的字段数。 */
export interface BrowserFillFormResult {
  ok: true;
  result: string;
  filled: number;
}

/** 音源类型：path/file/local 读本地文件，url/http/https/remote 走网络，uri/content 当 content:// 用。 */
export type MusicSourceType =
  | "path"
  | "file"
  | "local"
  | "url"
  | "http"
  | "https"
  | "remote"
  | "uri"
  | "content";

/** 单曲参数；source_type / sourceType 二选一必填。 */
export interface MusicTrackInput {
  source: string;
  source_type?: MusicSourceType;
  sourceType?: MusicSourceType;
  title?: string;
  artist?: string;
}

/** 本机 ExoPlayer 播放时的状态快照。 */
export interface MusicLocalState {
  state: string;
  source: string;
  sourceType: string;
  title: string;
  artist: string;
  durationMs: number;
  positionMs: number;
  volume: number;
  loop: boolean;
  /** off / one / all。 */
  repeatMode: string;
  queueIndex: number;
  queueSize: number;
  bufferedPositionMs: number;
  message: string;
  errorCode?: string;
  errorMessage?: string;
}

/** 遥控系统里别的播放器时的状态。平台不暴露 repeat mode，所以没有 loop / repeatMode。 */
export interface MusicRemoteState {
  state: string;
  remote: true;
  packageName: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  positionMs: number;
  volume: number;
  message: string;
}

/** 遥控模式下 setVolume 改的是系统媒体音量，不是对方应用的内部音量。 */
export interface MusicRemoteVolumeResult {
  state: "remote_volume";
  remote: true;
  volume: number;
  message: string;
}

export type MusicPlaybackResult = MusicLocalState | MusicRemoteState | MusicRemoteVolumeResult;

/** 蓝牙权限现状；插件进程弹不了权限框，requestable 恒为 false。 */
export interface BluetoothPermissionResult {
  granted: boolean;
  missing: string[];
  sdkInt: number;
  requiredForConnect: string[];
  requiredForScan: string[];
  requestable: false;
  hint: string;
}

export interface BluetoothStateResult {
  supported: boolean;
  enabled: boolean;
  state: string;
}

export interface BluetoothEnableResult {
  alreadyEnabled: boolean;
  requestLaunched: boolean;
  message: string;
}

export interface BluetoothDeviceEntry {
  name: string | null;
  address: string;
  type: string;
  bondState: string;
}

export interface BluetoothScannedDevice extends BluetoothDeviceEntry {
  source: string;
  rssi: number | null;
}

export interface BluetoothScanResult {
  durationMs: number;
  includesBle: boolean;
  totalCount: number;
  devices: BluetoothScannedDevice[];
}

/** 经典会话 / 监听器 / BLE 会话的公共描述；两种 id 写法都带上。 */
export interface BluetoothSession {
  sessionId: string;
  session_id: string;
  address: string;
  mode: string;
}

export interface BluetoothTransferResult {
  sessionId: string;
  bytesWritten: number;
}

export interface BluetoothReadResult {
  sessionId: string;
  bytesRead: number;
  text: string | null;
  dataBase64: string | null;
}

export interface BluetoothWriteParams {
  session_id?: string;
  sessionId?: string;
  text?: string;
  data_base64?: string;
  dataBase64?: string;
}

export interface BluetoothReadParams {
  session_id?: string;
  sessionId?: string;
  max_bytes?: number;
  maxBytes?: number;
  timeout_ms?: number;
  timeoutMs?: number;
}

export interface BleCharacteristicInfo {
  uuid: string;
  properties: string[];
}

export interface BleServiceInfo {
  uuid: string;
  characteristics: BleCharacteristicInfo[];
}

export interface BleServicesResult {
  sessionId: string;
  services: BleServiceInfo[];
}

export interface BleNotificationEntry {
  characteristicUuid: string;
  bytesRead: number;
  text: string | null;
  dataBase64: string | null;
  timestamp: number;
}

export interface BleNotificationsResult {
  sessionId: string;
  totalCount: number;
  notifications: BleNotificationEntry[];
}

/** 记忆节点。createdAt / updatedAt 是格式化文本，同时附毫秒值。 */
export interface MemoryEntry {
  id: string;
  title: string;
  content: string;
  contentType: string;
  source: string;
  folderPath: string;
  tags: string[];
  credibility: number;
  importance: number;
  createdAt: string;
  createdAtMs: number;
  updatedAt: string;
  updatedAtMs: number;
  createdBy: string;
  /** 只在 query 命中时出现：相关度。 */
  score?: number;
}

export interface MemoryQueryResult {
  query: string;
  totalCount: number;
  memories: MemoryEntry[];
  snapshotId: string;
  snapshotCreated: boolean;
  excludedBySnapshotCount: number;
}

export interface MemoryGetByTitleResult {
  memory: MemoryEntry;
  memories: MemoryEntry[];
  totalCount: 1;
}

export interface MemoryMoveResult {
  movedCount: number;
  titles: string[];
  targetFolderPath: string;
  message: string;
}

export interface MemoryLinkEntry {
  linkId: string;
  sourceTitle: string;
  targetTitle: string;
  linkType: string;
  weight: number;
  description: string;
  createdAt: string;
  createdAtMs: number;
  updatedAtMs: number;
  createdBy: string;
}

/** 角色卡工具白名单；在 Aharou 里只是随卡保存的元数据，不驱动执行。 */
export interface CharacterCardToolAccess {
  enabled: boolean;
  allowedBuiltinTools: string[];
  allowedPackages: string[];
  allowedSkills: string[];
  allowedMcpServers: string[];
}

/** 角色卡完整形状；id 可用 character_card_id / characterCardId / id 三种写法传入。 */
export interface CharacterCard {
  id: string;
  name: string;
  description: string;
  avatar: string;
  characterSetting: string;
  openingStatement: string;
  otherContentChat: string;
  otherContentVoice: string;
  tags: string[];
  attachedTagIds: string[];
  advancedCustomPrompt: string;
  marks: string;
  /** FOLLOW_GLOBAL / FIXED_CONFIG。 */
  chatModelBindingMode: string;
  chatModelConfigId: string | null;
  chatModelIndex: number;
  /** FOLLOW_GLOBAL / FIXED_PROFILE。 */
  memoryProfileBindingMode: string;
  memoryProfileId: string | null;
  toolAccessConfig: CharacterCardToolAccess;
  worldBookEntryCount: number;
  isDefault: false;
  createdAt: number;
  updatedAt: number;
}

export interface CharacterCardListResult {
  totalCount: number;
  activeCharacterCardId: string | null;
  cards: CharacterCard[];
}

export interface CharacterCardIdParams {
  character_card_id?: string;
  characterCardId?: string;
  id?: string;
}

/** 创建/更新时只写传了的字段；snake_case 与 camelCase 两种写法都收。 */
export interface CharacterCardWriteParams {
  name?: string;
  description?: string;
  avatar?: string;
  character_setting?: string;
  characterSetting?: string;
  opening_statement?: string;
  openingStatement?: string;
  other_content_chat?: string;
  otherContentChat?: string;
  other_content_voice?: string;
  otherContentVoice?: string;
  advanced_custom_prompt?: string;
  advancedCustomPrompt?: string;
  marks?: string;
  /** 数组或逗号串都收。 */
  tags?: string[] | string;
  attached_tag_ids?: string[] | string;
  /** Operit 的完整标签对象；Aharou 只取 name 存进 tags。 */
  attached_tags?: Array<string | { name?: string }>;
  chat_model_binding_mode?: string;
  chat_model_config_id?: string;
  chat_model_index?: number;
  memory_profile_binding_mode?: string;
  memory_profile_id?: string;
  tool_access_config?: Partial<CharacterCardToolAccess>;
}

/** create 回 created，update 回 updated，其余字段相同。 */
export type CharacterCardWriteResult =
  | {
      created: true;
      card: CharacterCard;
      activeCharacterCardId: string | null;
      changedFields: string[];
    }
  | {
      updated: true;
      card: CharacterCard;
      activeCharacterCardId: string | null;
      changedFields: string[];
    };

/** 一个供应商配置；apiKey 只给预览，不回明文。 */
export interface ModelConfigItem {
  id: string;
  name: string;
  apiProviderType: string;
  apiEndpoint: string;
  modelName: string;
  modelList: string[];
  apiKeySet: boolean;
  apiKeyPreview: string;
  enabled: boolean;
  customHeaders: Record<string, string>;
  useFullUrl: boolean;
  /** 插件清单里声明的供应商。 */
  pluginOwned: boolean;
}

export interface FunctionModelMapping {
  functionType: string;
  configId: string;
  configName: string;
  /** Aharou 的角色模型只存 (provider, model)，恒为 0。 */
  modelIndex: number;
  selectedModel: string;
  supported: boolean;
  /** 仅 supported=false 时出现，说明为什么这个功能类型没有对应能力。 */
  reason?: string;
}

export interface ModelConfigListResult {
  totalConfigCount: number;
  /** Aharou 的供应商都是平级的，没有默认配置，恒为空串。 */
  defaultConfigId: "";
  configs: ModelConfigItem[];
  functionMappings: FunctionModelMapping[];
}

export interface ModelConfigIdParams {
  config_id?: string;
  configId?: string;
  id?: string;
}

export interface ModelConfigWriteParams {
  name?: string;
  /** 供应商地址；create 时必填。 */
  api_endpoint?: string;
  apiEndpoint?: string;
  base_url?: string;
  baseUrl?: string;
  /** OPENAI / ANTHROPIC / GEMINI；create 时缺省 OPENAI。 */
  api_provider_type?: string;
  apiProviderType?: string;
  type?: string;
  api_key?: string;
  apiKey?: string;
  model_name?: string;
  modelName?: string;
  default_model?: string;
  /** 数组或逗号串都收。 */
  models?: string[] | string;
  model_list?: string[] | string;
  enabled?: boolean;
  /** JSON 对象或其文本形式。 */
  custom_headers?: Record<string, string> | string;
  customHeaders?: Record<string, string> | string;
}

export interface FunctionModelBinding {
  functionType: string;
  configId: string;
  configName: string;
  modelIndex: number;
  selectedModel: string;
  supported: boolean;
}

export interface FunctionModelBindingResult {
  functionType: string;
  configId: string;
  configName: string;
  requestedModelIndex: number;
  /** 越界后实际使用的下标。 */
  actualModelIndex: number;
  selectedModel: string;
}

/** 连接测试不会 reject：失败也回 200 形状的结果，只是 success=false。 */
export interface ModelConfigTestResult {
  configId: string;
  configName: string;
  providerType: string;
  requestedModelIndex: number;
  testedModelName: string;
  success: boolean;
  verified: true;
  totalTests: number;
  passedTests: number;
  failedTests: number;
  latencyMs: number;
  responseCode: number;
  /** 只在成功分支里出现。 */
  modelCount?: number;
  models?: string[];
  /** 只在失败分支里出现。 */
  error?: string;
  tests: Array<{ type: string; success: boolean; outcome: "passed" | "failed"; error?: string }>;
}

/** 语音配置快照；读不出的能力列在 unsupported 里，不编造值。 */
export interface SpeechServicesConfig {
  /** SYSTEM 或 AHAROU_CLOUD。 */
  ttsServiceType: string;
  ttsProviderId: string;
  ttsHttpConfig: {
    urlTemplate: string;
    apiKeySet: boolean;
    apiKeyPreview: string;
    headers: Record<string, string>;
    httpMethod: string;
    requestBody: string;
    contentType: string;
    localeTag: string;
    voiceId: string;
    modelName: string;
    responsePipeline: JsonValue[];
  };
  ttsVitsPackageConfig: {
    packagePath: string;
    speakerId: string;
    options: Record<string, JsonValue>;
  };
  ttsCleanerRegexs: JsonValue[];
  ttsSpeechRate: number;
  ttsPitch: number;
  ttsVoiceUser: string;
  autoRead: boolean;
  /** OFFLINE 或 AHAROU_CLOUD。 */
  sttServiceType: string;
  sttProviderId: string;
  sttHttpConfig: {
    endpointUrl: string;
    apiKeySet: boolean;
    apiKeyPreview: string;
    modelName: string;
  };
  /** Aharou 改不了 / 不提供的项（VITS 包、清洗正则、语速、音高）的理由。 */
  unsupported: string[];
}

/** 只应用出现的键；认不出的键（含 Operit 专属键）会进结果的 ignored。 */
export interface SpeechServicesConfigPatch {
  tts_service_type?: "SYSTEM" | "AHAROU_CLOUD" | string;
  tts_provider?: string;
  tts_provider_id?: string;
  tts_model?: string;
  tts_voice?: string;
  tts_voice_id?: string;
  tts_voice_user?: string;
  tts_user_voice?: string;
  stt_provider?: string;
  stt_provider_id?: string;
  stt_model?: string;
  auto_read?: boolean;
  chat_mode?: boolean;
}

export interface TtsPlaybackTestResult {
  initialized: true;
  playbackTriggered: boolean;
  interrupt: boolean;
  textLength: number;
  speechRate: 1.0;
  pitch: 1.0;
  engine: "system" | "cloud";
  /** 没能出声时的原因。 */
  message?: string;
}

export type BrowserErrorCode =
  | "BROWSER_ACTION_FAILED"
  | "PARAM_INVALID"
  | "NOT_SUPPORTED"
  | "NO_BROWSER_SESSION"
  | "TIMEOUT";

export type HostMethodName = keyof HostMethodMap;
export type HostParams<M extends HostMethodName> = HostMethodMap[M]["params"];
export type HostResult<M extends HostMethodName> = HostMethodMap[M]["result"];

/** 只给插件进程用（页面没有、浮层也没有）。 */
export type ProcessOnlyMethodName =
  | "notify"
  | "log"
  | "ports.forward"
  | "ports.close"
  | "ports.list"
  | "device.tap"
  | "device.longPress"
  | "device.swipe"
  | "device.key"
  | "device.setText"
  | "device.pageInfo"
  | "device.screenshot"
  | "terminal.hiddenExec"
  | "agent.runSubAgent"
  | "browser.start"
  | "browser.stop"
  | "browser.navigate"
  | "browser.navigateBack"
  | "browser.click"
  | "browser.type"
  | "browser.press"
  | "browser.select"
  | "browser.hover"
  | "browser.resize"
  | "browser.evaluate"
  | "browser.waitFor"
  | "browser.snapshot"
  | "browser.screenshot"
  | "browser.console"
  | "browser.fillForm"
  | "browser.handleDialog"
  | "browser.tabs"
  | "browser.close"
  | "browser.closeAll"
  | "browser.networkRequests"
  | "browser.drag"
  | "browser.fileUpload"
  | "browser.runCode"
  | "browser.userscriptInstall"
  | "browser.userscriptList"
  | "browser.userscriptStart"
  | "browser.userscriptStop"
  | "browser.userscriptUninstall";

/** 只给浮层页面用。 */
export type OverlayOnlyMethodName =
  | "overlay.self"
  | "overlay.setVisibilityPolicy"
  | "overlay.setDragRegion"
  | "overlay.moveBy"
  | "overlay.resize";

/** 只给普通页面用（浮层与进程都调不到）。 */
export type PageOnlyMethodName =
  | "fs.watch"
  | "fs.unwatch"
  | "ui.overlay"
  | "ui.close"
  | "toast"
  | "cookies.get"
  | "log.write"
  | "plugin.invoke";

/** 页面可用的方法名（含浮层也开放的那些）。 */
export type PageMethodName = Exclude<HostMethodName, ProcessOnlyMethodName | OverlayOnlyMethodName>;

/** 插件进程可用的方法名；调用时前面要加 host. 前缀。 */
export type ProcessMethodName = Exclude<
  HostMethodName,
  PageOnlyMethodName | OverlayOnlyMethodName | "context"
>;

/** 浮层页面可用的方法名。 */
export type OverlayMethodName =
  | "context"
  | "app.isForeground"
  | "overlay.close"
  | "overlay.postMessage"
  | OverlayOnlyMethodName;

/** 页面侧 `call('方法名', params)` 的类型；SDK 内部的转发函数就长这样。 */
export type PageCall = <M extends PageMethodName>(
  method: M,
  params?: HostParams<M>,
) => Promise<HostResult<M>>;

/** 浮层页面侧同样的转发函数。 */
export type OverlayCall = <M extends OverlayMethodName>(
  method: M,
  params?: HostParams<M>,
) => Promise<HostResult<M>>;

/**
 * 插件进程侧的 hostCall 类型（方法名前缀 host.）。
 *
 * 运行时里按文档自己实现 JSON-RPC 的 hostCall 之后这样标注：
 *   const hostCall: ProcessCall = (method, params) => { ... };
 */
export type ProcessCall = <M extends ProcessMethodName>(
  method: `host.${M}`,
  params?: HostParams<M>,
) => Promise<HostResult<M>>;

declare global {
  interface Window {
    /** 宿主注入的页面桥；页面 SDK 用它把请求发给宿主。 */
    __aharouBridge?: { postMessage(payload: string): void };
    __aharouResolve?: (id: string, payload: string) => void;
    __aharouEvent?: (payload: string) => void;
    __aharouOverlayMessage?: (payload: string) => void;
    __aharouBack?: () => boolean;
  }
}
