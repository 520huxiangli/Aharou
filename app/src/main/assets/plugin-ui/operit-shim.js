'use strict';

/**
 * Aharou · Operit 插件兼容层（CommonJS，供插件进程内的 node 直接 require）。
 *
 * 来源：AAswordman/Operit（LGPL-3.0，与 Aharou 的 GPL-3.0 兼容），取自
 *   app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsTools.kt
 *   app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsExecutionScriptBuilder.kt
 *   app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsToolPkgRegistration.kt
 *
 * 改动：只移植 JS 侧「拼参数 → 调宿主 → 整理返回」的包装层与注册声明语义，
 * QuickJS 宿主、NativeInterface、Java/Android 桥未移植；
 * Compose DSL 的 JS 运行时单独放在同目录的 `compose-dsl-runtime.js`（同源移植），
 * 本文件只负责把 `registerUiRoute` 的路由、`ui.render` / `ui.dispatch` 的调用接到它上面。
 * 其余缺失能力统一转成明确报错，不伪造成功。返回形状尽量按 Operit 的形状对齐，
 * 让按 Operit 写的插件代码少改。
 */

const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const Module = require('module');
const { AsyncLocalStorage } = require('async_hooks');

const JSONRPC_VERSION = '2.0';
const HOST_PREFIX = 'host.';
const INVOKE_METHODS = new Set(['tool.invoke', 'hook.invoke', 'ui.invoke']);
/** shim 内置处理的 `ui.invoke` handler 名：插件没注册同名 handler 时由路线表接手。 */
const COMPOSE_UI_HANDLERS = new Set(['ui.render', 'ui.dispatch', 'ui.routes', 'ui.jsbridge']);
const DEFAULT_LANG = 'en';
const MAX_LOG_CHARS = 8000;
const EMIT_LOG_INTERVAL_MS = 200;

/** 预加载模式下创建的默认 runtime；插件再调 createRuntime 时复用同一条 stdio 通道。 */
let autoloadRuntime = null;

/** 语义对不上的能力统一走这句，保证插件作者看到的报错能指路。 */
const UNSUPPORTED_BROWSER =
  'Aharou 的内置浏览器没有这个原语（Operit 的 Tools.Net.browser*）：' +
  '抓包请用 Tools.Net.http，拖拽/文件上传/Playwright 代码请用 Tools.Net.browserEvaluate 自行实现。';
const UNSUPPORTED_XML_RENDER = 'Aharou 不支持聊天内 XML 渲染';

class AharouPluginError extends Error {
  constructor(code, message, extra) {
    super(message);
    this.name = 'AharouPluginError';
    this.code = code;
    if (extra && typeof extra === 'object') Object.assign(this, extra);
  }
}

function pluginError(code, message, extra) {
  return new AharouPluginError(code, message, extra);
}

/**
 * 延迟加载 Compose DSL 运行时：宿主可能只同步 shim 一个文件，缺文件时只有 UI 路线不可用，
 * 其余兼容能力要照常工作。
 */
function loadComposeRuntime() {
  const candidates = [
    process.env.AHAROU_COMPOSE_DSL_RUNTIME,
    path.join(__dirname, 'compose-dsl-runtime.js')
  ].filter((item) => !!item);
  let lastError = null;
  for (const candidate of candidates) {
    try {
      if (!fs.existsSync(candidate)) continue;
      return require(candidate);
    } catch (error) {
      lastError = error;
    }
  }
  throw pluginError(
    'NOT_SUPPORTED',
    '缺少 compose-dsl-runtime.js（应与 operit-shim.js 同目录）：' +
      (lastError && lastError.message ? lastError.message : '文件不存在')
  );
}

function notSupported(feature, alternative) {
  const tail = alternative ? '；请改用 ' + alternative : '';
  return pluginError('NOT_SUPPORTED', 'Aharou 不支持 ' + feature + tail);
}

function text(value) {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  try {
    return String(value);
  } catch (error) {
    return '';
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** 序列化给日志/中间结果用：循环引用与不可序列化对象都不能把调用打断。 */
function stringify(value) {
  if (typeof value === 'string') return value.length > MAX_LOG_CHARS ? value.slice(0, MAX_LOG_CHARS) + '…' : value;
  if (value === undefined) return 'undefined';
  try {
    const encoded = JSON.stringify(value);
    if (encoded === undefined) return text(value);
    return encoded.length > MAX_LOG_CHARS ? encoded.slice(0, MAX_LOG_CHARS) + '…' : encoded;
  } catch (error) {
    return text(value);
  }
}

function byteLength(value) {
  return Buffer.byteLength(text(value), 'utf8');
}

function envValue(key, fallback) {
  const value = process.env[key];
  return value === undefined || value === '' ? fallback : value;
}

function envFlag(key) {
  return envValue(key, '') === '1';
}

/** 宿主可以直接用 AHAROU_PLUGIN_ENV 传一整份 JSON，不必为 getEnv 新增宿主方法。 */
function parseEnvJson(raw) {
  const source = text(raw).trim();
  if (!source) return {};
  try {
    const parsed = JSON.parse(source);
    return isPlainObject(parsed) ? parsed : {};
  } catch (error) {
    return {};
  }
}

/** 逐字段剔除 undefined，避免把 undefined 塞进 JSON-RPC 参数。 */
function compact(object) {
  const output = {};
  if (!isPlainObject(object)) return output;
  Object.keys(object).forEach((key) => {
    if (object[key] !== undefined) output[key] = object[key];
  });
  return output;
}

function toErrorPayload(error) {
  // 插件常抛空 message 的 Error 或普通对象；只回一句「插件返回错误」会丢掉全部线索，
  // 这里退到 name / JSON 片段 / String() 逐级兜底，至少能看到是哪一类错。
  function describe(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'string') return value;
    try {
      const json = JSON.stringify(value);
      if (json && json !== '{}') return json.slice(0, 300);
    } catch (ignored) {
      // 循环引用等，继续往下退
    }
    try {
      return String(value);
    } catch (ignored) {
      return '';
    }
  }
  if (error && typeof error === 'object') {
    const code = typeof error.code === 'string' && error.code ? error.code : 'PLUGIN_ERROR';
    let message = text(error.message);
    if (!message) message = describe(error);
    if (!message && typeof error.name === 'string') message = error.name;
    if (!message && error.cause) message = 'cause: ' + describe(error.cause);
    return { code, message: message || '插件返回错误' };
  }
  return { code: 'PLUGIN_ERROR', message: describerOrText(error) };
}

function describerOrText(value) {
  const direct = text(value);
  if (direct) return direct;
  try {
    const json = JSON.stringify(value);
    if (json && json !== '{}') return json.slice(0, 300);
  } catch (ignored) {
    // 忽略
  }
  return '插件返回错误';
}

/** 宿主错误对象带 code，插件侧按 code 判断降级，不靠文案。 */
function isUnknownMethod(error) {
  if (!error) return false;
  if (error.code === 'METHOD_UNKNOWN') return true;
  return /unknown host method|METHOD_UNKNOWN/i.test(text(error.message));
}

// ---------------------------------------------------------------------------
// 传输层：按行 NDJSON。宿主只把 `result` 当成功、`error` 当失败。
// ---------------------------------------------------------------------------

function createStdioTransport(options) {
  const settings = options || {};
  const pending = new Map();
  const stdoutWrite = process.stdout.write.bind(process.stdout);
  let seq = 0;
  let buffer = '';
  let requestHandler = null;
  let closeHandler = null;
  let closed = false;

  function writeLine(message) {
    // 一行一条消息：宿主按行切分，跨行会把一条请求拆坏。
    stdoutWrite(JSON.stringify(message) + '\n');
  }

  function failAll(reason) {
    const code = 'TRANSPORT_CLOSED';
    pending.forEach((entry) => {
      try {
        entry.reject(pluginError(code, reason));
      } catch (error) {
        // 忽略：对手方已经走了
      }
    });
    pending.clear();
  }

  function handleResponse(message) {
    const key = String(message.id);
    const entry = pending.get(key);
    if (!entry) return;
    pending.delete(key);
    if (message.error) {
      const error = pluginError(
        text(message.error.code) || 'HOST_ERROR',
        text(message.error.message) || '宿主调用失败'
      );
      entry.reject(error);
      return;
    }
    entry.resolve(message.result === undefined ? null : message.result);
  }

  function handleMessage(message) {
    if (!isPlainObject(message)) return;
    const hasMethod = typeof message.method === 'string';
    const hasId = message.id !== undefined && message.id !== null;
    if (hasMethod && hasId) {
      if (typeof requestHandler !== 'function') {
        // 没有 serve() 时说明插件自己实现了消息循环（预加载模式），这里不能抢答
        return;
      }
      const params = isPlainObject(message.params) ? message.params : {};
      Promise.resolve()
        .then(() => requestHandler(message.method, params, message.id))
        .catch((error) => {
          writeLine({ jsonrpc: JSONRPC_VERSION, id: message.id, error: toErrorPayload(error) });
        });
      return;
    }
    if (hasMethod) return; // 宿主通知：当前没有对应通道，忽略
    if (hasId) handleResponse(message);
  }

  function feed(chunk) {
    buffer += chunk;
    let index = buffer.indexOf('\n');
    while (index >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      const trimmed = line.trim();
      if (trimmed) {
        try {
          handleMessage(JSON.parse(trimmed));
        } catch (error) {
          // 非 JSON 行走宿主日志，不打断通道
          if (typeof settings.onBadLine === 'function') settings.onBadLine(trimmed, error);
        }
      }
      index = buffer.indexOf('\n');
    }
  }

  process.stdin.setEncoding('utf8');
  process.stdin.on('data', feed);
  process.stdin.on('end', () => {
    if (closed) return;
    closed = true;
    failAll('插件进程 stdin 已结束');
    if (typeof closeHandler === 'function') {
      try {
        closeHandler();
      } catch (error) {
        // 忽略
      }
    }
  });
  process.stdin.on('error', () => failAll('插件进程 stdin 出错'));

  return {
    request(method, params) {
      if (closed) return Promise.reject(pluginError('TRANSPORT_CLOSED', '插件进程通道已关闭'));
      const id = ++seq;
      return new Promise((resolve, reject) => {
        pending.set(String(id), { resolve, reject });
        try {
          writeLine({ jsonrpc: JSONRPC_VERSION, id, method, params: params || {} });
        } catch (error) {
          pending.delete(String(id));
          reject(pluginError('TRANSPORT_WRITE_FAILED', '写入宿主通道失败: ' + text(error && error.message)));
        }
      });
    },
    onRequest(handler) {
      requestHandler = handler;
    },
    onClose(handler) {
      closeHandler = handler;
    },
    sendResult(id, result) {
      writeLine({ jsonrpc: JSONRPC_VERSION, id, result: result === undefined ? null : result });
    },
    sendError(id, error) {
      writeLine({ jsonrpc: JSONRPC_VERSION, id, error: toErrorPayload(error) });
    },
    notify(method, params) {
      writeLine({ jsonrpc: JSONRPC_VERSION, method, params: params || {} });
    },
    close() {
      closed = true;
      failAll('插件进程通道已关闭');
    }
  };
}

// ---------------------------------------------------------------------------
// 运行时
// ---------------------------------------------------------------------------

function createRuntime(options) {
  const settings = options || {};
  const envObject = isPlainObject(settings.env)
    ? Object.assign({}, settings.env)
    : parseEnvJson(process.env.AHAROU_PLUGIN_ENV);
  const als = new AsyncLocalStorage();
  // 预加载模式下插件可能再调 createRuntime：复用同一条通道，避免两个读数循环抢行
  const transport =
    settings.transport ||
    (autoloadRuntime ? autoloadRuntime.transport : createStdioTransport({ onBadLine: settings.onBadLine }));
  const pluginsRegistrations = [];
  const handlers = new Map();
  // v2 的 `ToolPkg.ipc` 通道表。Aharou 的插件只有一个进程（没有 Operit 的 UI / main 双运行时），
  // 因此 ipc.on 与 ipc.call 在同一进程内配对：谁注册的话题谁接，派发走本地调用。
  const ipcChannels = new Map();
  const tools = Object.create(null);
  let composeRouteTable = null;

  const runtime = {
    pluginId: settings.pluginId || envValue('AICODE_PLUGIN_ID', ''),
    dataDir: settings.dataDir || envValue('AICODE_DATA_DIR', ''),
    workspace: settings.workspace || envValue('AICODE_WORKSPACE', ''),
    transport,
    Tools: tools
  };

  function hostLog(level, message, tag) {
    const payload = {
      level: String(level || 'info'),
      message: text(message).slice(0, MAX_LOG_CHARS),
      tag: tag || 'plugin'
    };
    // 日志不该拖住业务：失败静默，避免插件因为日志端点缺失直接崩。
    Promise.resolve()
      .then(() => transport.request('host.log', payload))
      .catch(() => undefined);
  }

  async function rawCall(method, params) {
    const timeoutMs = Number(settings.hostCallTimeoutMs || 0);
    if (!(timeoutMs > 0)) return transport.request(method, params || {});
    let timer = null;
    try {
      return await Promise.race([
        transport.request(method, params || {}),
        new Promise((resolve, reject) => {
          timer = setTimeout(
            () => reject(pluginError('HOST_TIMEOUT', '宿主调用超时（' + timeoutMs + 'ms）: ' + method)),
            timeoutMs
          );
        })
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /**
   * 调宿主方法。Operit 里存在 `toolCall('default', name, params)` 的三参数写法，
   * 这里一并兼容；不带 `host.` 前缀时自动补上（进程侧方法名带前缀）。
   */
  async function toolCall(name, params, toolOptions) {
    let methodName = name;
    let methodParams = params;
    let extra = toolOptions;
    if (name === 'default' && typeof params === 'string') {
      methodName = params;
      methodParams = toolOptions;
      extra = arguments.length > 3 ? arguments[3] : undefined;
    }
    if (typeof methodName !== 'string' || !methodName.trim()) {
      throw pluginError('PARAM_INVALID', 'toolCall 需要一个方法名');
    }
    const method = methodName.startsWith(HOST_PREFIX) ? methodName.trim() : HOST_PREFIX + methodName.trim();
    if (isPlainObject(extra) && typeof extra.onIntermediateResult === 'function') {
      // Operit 的第三个参数只用来接流式中间结果，宿主侧没有对应通道，保留引用供降级路径使用
      runtime.__lastToolOptions = extra;
    }
    return rawCall(method, isPlainObject(methodParams) ? methodParams : {});
  }

  function currentCall() {
    return als.getStore() || null;
  }

  // ---- Compose DSL 路线：注册、渲染、派发 ----

  function getComposeRoutes() {
    if (composeRouteTable) return composeRouteTable;
    const moduleRef = loadComposeRuntime();
    composeRouteTable = moduleRef.createRouteTable({
      runtime,
      // 插件包目录就是进程 cwd；宿主若另传就优先用它的
      baseDir: settings.pluginDir || envValue('AICODE_PLUGIN_DIR', '') || process.cwd(),
      env: envObject,
      pluginId: runtime.pluginId
    });
    return composeRouteTable;
  }

  function listUiRoutes() {
    return composeRouteTable ? composeRouteTable.list() : [];
  }

  function publishUiRoutes() {
    // 宿主可能想展时就知道有哪些路由；通知失败不能影响注册结果
    try {
      transport.notify('plugin.event', { event: 'ui.routes', routes: listUiRoutes() });
    } catch (error) {
      // 忽略
    }
  }

  function routeIdOf(source) {
    const args = isPlainObject(source) ? source : {};
    return text(args.routeId || args.route || args.id || args.__route_id);
  }

  /** `ui.dispatch` 的入参归一化：兼容宿主把 payload 再套一层 args 的写法。 */
  function normalizeDispatchRequest(source) {
    const args = isPlainObject(source) ? source : {};
    const nested = isPlainObject(args.args) ? args.args : null;
    const request = {
      routeId: routeIdOf(args),
      actionId: text(args.actionId || args.__actionId || args.action),
      replaceState: args.replaceState === true || args.reset === true
    };
    if (nested) request.payload = nested;
    else if (Object.prototype.hasOwnProperty.call(args, 'payload')) request.payload = args.payload;
    if (isPlainObject(args.state)) request.state = args.state;
    if (isPlainObject(args.memo)) request.memo = args.memo;
    return request;
  }

  /**
   * 内置的 ui.render / ui.dispatch / ui.routes：插件自己注册了同名 handler 时以插件为准，
   * 这里只在 handlers 表里没有同名项时接。
   */
  function serveComposeUi(method, handlerName, params, id) {
    if (method !== 'ui.invoke' || !COMPOSE_UI_HANDLERS.has(handlerName)) return false;
    const args = isPlainObject(params.args) ? params.args : {};
    const meta = isPlainObject(params.meta) ? params.meta : {};
    const call = { id, method, handler: handlerName, args, meta, settled: false };
    als.run(call, () =>
      Promise.resolve()
        .then(() => {
          const table = getComposeRoutes();
          if (handlerName === 'ui.routes') return { routes: table.list() };
          if (handlerName === 'ui.jsbridge') {
            // 宿主页面里的 JS 接口调用转到这里：找插件创建的控制器，调它的方法
            const compose = loadComposeRuntime();
            return compose.callWebController(
              text(args.controllerId),
              text(args.interfaceName || args.iface),
              text(args.method),
              text(args.argsJson)
            );
          }
          if (handlerName === 'ui.render') {
            return table.render(routeIdOf(args), {
              state: isPlainObject(args.state) ? args.state : undefined,
              memo: isPlainObject(args.memo) ? args.memo : undefined,
              replaceState: args.replaceState === true || args.reset === true
            });
          }
          return table.dispatch(normalizeDispatchRequest(args));
        })
        .then((value) => {
          if (!call.settled) settle(call, classifyResult(value));
        })
        .catch((error) => {
          if (!call.settled) settle(call, { error: toErrorPayload(error) });
        })
    );
    return true;
  }

  /** Operit 约定 {success:false, message} 表示失败，必须转成 JSON-RPC error。 */
  function classifyResult(value) {
    if (value === undefined) return { result: null };
    if (value === null) return { result: null };
    if (isPlainObject(value)) {
      const flag = value.success;
      if (flag === false || flag === 'false' || flag === 0 || flag === '0') {
        const message =
          text(value.message) ||
          text(value.error) ||
          text(value.detail) ||
          text(value.msg) ||
          '插件返回 success:false 但没有给出 message';
        return { error: { code: 'TOOL_FAILED', message } };
      }
    }
    return { result: value };
  }

  function settle(call, outcome) {
    if (!call || call.settled) return false;
    call.settled = true;
    if (outcome.error) transport.sendError(call.id, outcome.error);
    else transport.sendResult(call.id, outcome.result);
    return true;
  }

  function complete(value) {
    const call = currentCall();
    if (!call) {
      hostLog('warn', 'complete() 在没有活动调用时被调用，已忽略：' + stringify(value), 'operit-shim');
      return false;
    }
    return settle(call, classifyResult(value));
  }

  function emitIntermediate(kind) {
    // 流式 delta 可能每帧一条：按时间窗限流，否则每条中间结果都是一次 host.log 往返
    let lastLogAt = 0;
    let suppressed = 0;
    return function emitValue(value) {
      const call = currentCall();
      const payload = value === undefined ? '' : stringify(value);
      if (settings.notifyEvents && call) {
        // plugin.event 是宿主已有的通知方法，能被插件订阅者看到
        try {
          transport.notify('plugin.event', {
            event: 'intermediate',
            kind,
            handler: call.handler,
            text: payload
          });
        } catch (error) {
          // 忽略
        }
      }
      if (typeof settings.onIntermediate === 'function') {
        try {
          settings.onIntermediate(value, kind);
        } catch (error) {
          // 回调不该影响主流程
        }
      }
      const now = Date.now();
      if (lastLogAt !== 0 && now - lastLogAt < EMIT_LOG_INTERVAL_MS) {
        suppressed += 1;
      } else {
        const note = suppressed > 0 ? '（省略 ' + suppressed + ' 条）' : '';
        lastLogAt = now;
        suppressed = 0;
        hostLog('info', '[' + kind + '] ' + payload + note, 'operit-shim');
      }
      return undefined;
    };
  }
  const callMeta = () => {
    const call = currentCall();
    return (call && call.meta) || {};
  };

  /** 注入的 env 优先；没有时回落到进程环境（宿主 extraEnv 传的 AICODE_* 与用户透传变量）。 */
  function getEnv(key) {
    const name = text(key).trim();
    if (!name) return undefined;
    if (Object.prototype.hasOwnProperty.call(envObject, name)) {
      const value = envObject[name];
      if (value === undefined || value === null || value === '') return undefined;
      return text(value);
    }
    const fallback = process.env[name];
    if (fallback === undefined || fallback === '') return undefined;
    return text(fallback);
  }

  /** 宿主若有 env.getAll（新方法），把它并进注入表；没有就静默跳过。 */
  async function refreshEnv() {
    try {
      const result = await rawCall(HOST_PREFIX + 'env.getAll', {});
      const values = isPlainObject(result) && isPlainObject(result.values) ? result.values : result;
      if (isPlainObject(values)) {
        Object.keys(values).forEach((key) => {
          if (envObject[key] === undefined) envObject[key] = values[key];
        });
      }
      return envObject;
    } catch (error) {
      if (isUnknownMethod(error)) return envObject;
      throw error;
    }
  }

  function getPluginConfigDir(id) {
    const explicit = text(id).trim();
    if (explicit && explicit !== runtime.pluginId) {
      // 宿主只授权本插件自己的目录；别的插件 ID 一律拒绝，不猜路径
      hostLog('warn', 'getPluginConfigDir 只支持本插件，已忽略: ' + explicit, 'operit-shim');
      return '';
    }
    return settings.configDir || envObject.AICODE_DATA_DIR || runtime.dataDir || '';
  }

  /**
   * v2 的 `PluginConfig`：插件配置目录下的具名 JSON。
   *
   * `use(name, defaults)` 读出包内已有值并与默认值合并，返回值可直接读写属性；
   * 赋值只安排一次写盘，`flush(config)` 等它落地。目录不可写时只记 warn，
   * 不把插件调用拖成异常。
   */
  function createPluginConfigApi() {
    const entries = new Map();
    const proxies = new WeakMap();

    function fileOf(name) {
      const raw = text(name).trim().replace(/\.json$/i, '') || 'config';
      return path.join(getPluginConfigDir() || process.cwd(), raw + '.json');
    }

    function writeNow(entry) {
      entry.queued = false;
      try {
        fs.mkdirSync(path.dirname(entry.path), { recursive: true });
        fs.writeFileSync(entry.path, JSON.stringify(entry.data));
      } catch (error) {
        hostLog('warn', 'PluginConfig 写盘失败 ' + entry.path + ': ' + text(error && error.message), 'operit-shim');
      }
    }

    function schedule(entry) {
      if (entry.queued) return;
      entry.queued = true;
      entry.pending = Promise.resolve().then(() => writeNow(entry));
    }

    function open(name, defaults) {
      const file = fileOf(name);
      const cached = entries.get(file);
      if (cached) return cached;
      let stored = {};
      try {
        if (fs.existsSync(file)) stored = JSON.parse(fs.readFileSync(file, 'utf8'));
      } catch (error) {
        hostLog('warn', 'PluginConfig 读取失败 ' + file + ': ' + text(error && error.message), 'operit-shim');
      }
      const data = Object.assign(
        {},
        isPlainObject(defaults) ? defaults : {},
        isPlainObject(stored) ? stored : {}
      );
      const entry = { path: file, data, pending: Promise.resolve(), queued: false };
      entries.set(file, entry);
      return entry;
    }

    function configFor(name, defaults) {
      const entry = open(name, defaults);
      const existing = proxies.get(entry);
      if (existing) return existing;
      const proxy = new Proxy(entry.data, {
        set(target, prop, value) {
          target[prop] = value;
          schedule(entry);
          return true;
        },
        deleteProperty(target, prop) {
          delete target[prop];
          schedule(entry);
          return true;
        }
      });
      proxies.set(entry, proxy);
      proxies.set(proxy, entry);
      return proxy;
    }

    return {
      // use(defaults) 写 config.json，use(name, defaults) 写 <name>.json
      use(nameOrDefaults, maybeDefaults) {
        const singleArg = arguments.length < 2;
        return Promise.resolve().then(() =>
          configFor(singleArg ? 'config' : nameOrDefaults, singleArg ? nameOrDefaults : maybeDefaults)
        );
      },
      flush(config) {
        const entry = config && typeof config === 'object' ? proxies.get(config) : null;
        if (!entry) return Promise.resolve();
        if (!entry.queued) schedule(entry);
        return entry.pending;
      }
    };
  }

  function getLang() {
    return text(callMeta().lang) || text(settings.lang) || envValue('AHAROU_LANG', DEFAULT_LANG);
  }

  function getState() {
    const meta = callMeta();
    return meta.state === undefined ? undefined : meta.state;
  }

  function getCallerName() {
    const value = callMeta().callerName;
    return value === undefined ? null : value;
  }

  function getChatId() {
    const value = callMeta().chatId;
    return value === undefined ? null : value;
  }

  function getCallerCardId() {
    const value = callMeta().callerCardId;
    return value === undefined ? null : value;
  }

  function registerHandler(name, fn) {
    if (typeof name !== 'string' || !name) throw pluginError('PARAM_INVALID', 'handler 名称不能为空');
    if (typeof fn !== 'function') throw pluginError('PARAM_INVALID', 'handler 必须是函数: ' + name);
    handlers.set(name, fn);
    return true;
  }

  function registerHandlers(table) {
    if (!isPlainObject(table)) throw pluginError('PARAM_INVALID', 'registerHandlers 需要一个对象');
    const groups = ['tools', 'hooks', 'ui'];
    let count = 0;
    groups.forEach((group) => {
      const bucket = table[group];
      if (isPlainObject(bucket)) {
        Object.keys(bucket).forEach((key) => {
          registerHandler(key, bucket[key]);
          count += 1;
        });
      }
    });
    Object.keys(table).forEach((key) => {
      if (groups.indexOf(key) >= 0) return;
      if (typeof table[key] === 'function') {
        registerHandler(key, table[key]);
        count += 1;
      }
    });
    return count;
  }

  /**
   * v2 的 `ToolPkg.registerApi({ name, function })`。
   *
   * 直接写进 `handlers` —— 与 `serve(handlers)` 登记到的是同一张表，所以宿主
   * `tool.invoke { handler: name }` 能命中；同名时后注册的覆盖并留一条 warn，
   * 与 `serve` 的覆盖语义保持一致。
   */
  function registerApiFunction(definition) {
    const def = isPlainObject(definition) ? definition : {};
    const name = text(def.name).trim();
    const fn = typeof def.function === 'function' ? def.function : null;
    if (!name || !fn) {
      hostLog('warn', 'registerApi 缺少 name 或 function，已忽略', 'operit-shim');
      return false;
    }
    if (handlers.has(name)) {
      hostLog('warn', 'registerApi 覆盖了同名 handler: ' + name, 'operit-shim');
    }
    handlers.set(name, fn);
    return true;
  }

  function handleInvoke(method, params, id) {
    if (!INVOKE_METHODS.has(method)) {
      transport.sendError(id, { code: 'METHOD_UNKNOWN', message: '插件不支持的调用方法: ' + method });
      return Promise.resolve();
    }
    const handlerName = text(params.handler);
    const fn = handlers.get(handlerName);
    if (typeof fn !== 'function') {
      // 插件没注册同名 handler 时，内置的 Compose DSL 入口接手
      if (serveComposeUi(method, handlerName, params, id)) return Promise.resolve();
      transport.sendError(id, { code: 'HANDLER_NOT_FOUND', message: handlerName || '（缺少 handler）' });
      return Promise.resolve();
    }
    const args = isPlainObject(params.args) ? params.args : {};
    const meta = isPlainObject(params.meta) ? params.meta : {};
    const call = { id, method, handler: handlerName, args, meta, settled: false };
    return als.run(call, () =>
      Promise.resolve()
        .then(() => fn(args, { method, handler: handlerName, id }))
        .then((value) => {
          if (!call.settled) settle(call, classifyResult(value));
        })
        .catch((error) => {
          if (!call.settled) settle(call, { error: toErrorPayload(error) });
        })
    );
  }

  function serve(table) {
    if (table) registerHandlers(table);
    transport.onRequest((method, params, id) => handleInvoke(method, params, id));
    return runtime;
  }

  /**
   * v2 的 `ToolPkg.ipc`。
   *
   * Operit 里它是「UI 运行时 ↔ main 运行时」的通道；Aharou 只有一个插件进程，
   * 所以 on / call 就在同一进程内配对。话题名可以带点（`workflow.service`），不拆分段。
   */
  let cachedManifest = null;
  /**
   * 原始包内容的根目录。
   *
   * 安装后的插件把 Operit 原包整个放在 `operit/pkg/` 下（清单也在那里），
   * 只有直接跑未导入的源码包时清单才在 cwd。两种形态都要能读。
   */
  function pluginPackageRoot() {
    const imported = path.resolve(process.cwd(), 'operit/pkg');
    if (fs.existsSync(path.resolve(imported, 'manifest.json'))) return imported;
    return process.cwd();
  }

  /** 插件清单只在首次读一次：注册阶段与渲染阶段看到的是同一份。 */
  function readPluginManifest() {
    if (cachedManifest) return cachedManifest;
    try {
      cachedManifest = JSON.parse(fs.readFileSync(path.resolve(pluginPackageRoot(), 'manifest.json'), 'utf8'));
    } catch (error) {
      cachedManifest = {};
    }
    return cachedManifest;
  }

  const ipc = {
    on(topic, handler) {
      const name = text(topic).trim();
      if (!name || typeof handler !== 'function') {
        hostLog('warn', 'ipc.on 缺少 topic 或 handler，已忽略', 'operit-shim');
        return false;
      }
      if (ipcChannels.has(name)) {
        hostLog('warn', 'ipc.on 覆盖了同名话题: ' + name, 'operit-shim');
      }
      ipcChannels.set(name, handler);
      return true;
    },
    call(topic, payload, options) {
      const name = text(topic).trim();
      const handler = ipcChannels.get(name);
      if (typeof handler !== 'function') {
        // 不静默成 null：插件靠返回值判断成败，吞掉会变成难查的空数据
        return Promise.reject(
          pluginError('IPC_CHANNEL_MISSING', '插件进程内没有注册这个 ipc 话题: ' + name)
        );
      }
      const meta = Object.assign({ topic: name }, isPlainObject(options) ? options : {});
      return Promise.resolve().then(() => handler(payload, meta));
    }
  };

  // ---- ToolPkg 注册：全部记录后返回 true，绝不抛错 ----
  // `registerUiRoute` 已经落地（路由声明会交给 compose-dsl-runtime.js 执行），不再列在这里；
  // 其余注册仍需宿主侧对应的 UI / 消息链入口，暂不支持。

  const REGISTRATION_REASONS = {
    registerToolboxUiModule: 'Aharou 不支持在工具箱里挂插件 Compose DSL 页面',
    registerNavigationEntry: 'Aharou 不支持往侧栏/工具箱加插件导航项',
    registerDesktopWidget: 'Aharou 不支持桌面小组件',
    registerChatMessageMenuItem: 'Aharou 不支持往聊天消息长按菜单加插件动作',
    registerChatRuntimeHook: 'Aharou 不支持插件订阅聊天运行态变化',
    registerChatLifecycleHook: 'Aharou 只在 plugin.json 的 contributes.hooks 里派发 hook，运行期注册的聊天生命周期 hook 不生效',
    registerChatResponseHook: 'Aharou 只在 plugin.json 的 contributes.hooks 里派发 hook，运行期注册的回复改写 hook 不生效',
    registerChatComposerSlot: 'Aharou 不支持往输入区插插件槽位',
    registerCoreCommand: 'Aharou 不支持插件注册内置斜杠命令（请在 plugin.json 里声明工具代替）',
    registerHostEventHook: 'Aharou 不支持插件订阅宿主事件（定时 / 广播）',
    registerManifestExtension: 'Aharou 不支持插件清单扩展点',
    registerToolPermissionHook: 'Aharou 不支持插件订阅工具授权变更',
    'RuntimeContext.register': 'Aharou 没有插件运行时服务容器，注册的后台服务函数不会被调起',
    registerAppLifecycleHook: 'Aharou 不支持插件订阅应用生命周期事件',
    registerMessageProcessingPlugin: 'Aharou 不支持插件接管消息处理链',
    registerXmlRenderPlugin: UNSUPPORTED_XML_RENDER,
    registerInputMenuTogglePlugin: 'Aharou 不支持输入菜单开关插件',
    registerChatInputHook: 'Aharou 不支持插件拦截聊天输入提交',
    registerChatViewHook: 'Aharou 不支持插件观察聊天视图生命周期',
    registerChatMessageHook: 'Aharou 不支持插件接收消息持久化通知',
    registerToolLifecycleHook: 'Aharou 不支持插件观察工具调用生命周期',
    registerPromptInputHook: 'Aharou 不支持插件改写 Prompt 输入分段',
    registerPromptHistoryHook: 'Aharou 不支持插件改写 Prompt 历史',
    registerPromptEstimateHistoryHook: 'Aharou 不支持插件改写 token 估算用的历史',
    registerSystemPromptComposeHook: 'Aharou 不支持插件拼装系统提示词',
    registerToolPromptComposeHook: 'Aharou 不支持插件拼装工具提示词',
    registerPromptFinalizeHook: 'Aharou 不支持插件改写最终 Prompt',
    registerPromptEstimateFinalizeHook: 'Aharou 不支持插件改写最终估算 Prompt',
    registerSummaryGenerateHook: 'Aharou 不支持插件接管上下文摘要生成',
    registerAiProvider: 'Aharou 不支持插件注册 AI Provider（模型供应商请在设置里配置）'
  };

  function recordRegistration(api, definition, supported, reason) {
    const def = isPlainObject(definition) ? definition : {};
    const ok = supported === true;
    const entry = {
      api,
      ok,
      reason: ok ? '' : text(reason) || REGISTRATION_REASONS[api] || 'Aharou 不支持该注册能力',
      id: text(def.id),
      route: text(def.route),
      runtime: text(def.runtime)
    };
    pluginsRegistrations.push(entry);
    if (ok) {
      hostLog('info', '插件注册了 Compose DSL 路由: ' + (entry.id || entry.route), 'operit-shim');
    } else {
      hostLog('warn', '插件声明了 Aharou 不支持的注册: ' + api + '（' + entry.reason + '）', 'operit-shim');
    }
    return true;
  }

  function takeUnsupportedRegistrations() {
    const copied = pluginsRegistrations.filter((entry) => entry.ok !== true);
    pluginsRegistrations.length = 0;
    return copied;
  }

  /** 已经落地的路由声明：不清理，渲染时要靠它取 Screen。 */
  function registerComposeUiRoute(definition) {
    const def = isPlainObject(definition) ? definition : {};
    const runtimeId = text(def.runtime).trim() || 'compose_dsl';
    if (runtimeId !== 'compose_dsl') {
      return recordRegistration(
        'registerUiRoute',
        def,
        false,
        'Aharou 只支持 compose_dsl 运行时的插件页面（收到 ' + runtimeId + '）'
      );
    }
    try {
      const outcome = getComposeRoutes().register(def);
      const recorded = recordRegistration('registerUiRoute', def, outcome.ok, outcome.reason);
      if (outcome.ok) publishUiRoutes();
      return recorded;
    } catch (error) {
      return recordRegistration(
        'registerUiRoute',
        def,
        false,
        text(error.message) || 'Compose DSL 运行时不可用'
      );
    }
  }

  const ToolPkg = {
    // 插件在 main.js 里无条件调用这些注册函数，抛错会直接让插件起不来
    /**
     * Operit 的内部签名/校验入口，插件在模块求值阶段就会调用。
     *
     * 我们不复刻它校验什么，但缺了这个方法会让 require 直接抛 TypeError，
     * 整个插件连 registerToolPkg 都跑不到，表现成「装好了但没有任何路由」。
     */
    _m: () => true,
    registerToolboxUiModule: (definition) => recordRegistration('registerToolboxUiModule', definition),
    registerApi: (definition) => registerApiFunction(definition),
    ipc,
    // v2 的 static 方法装饰器工厂：只标记「这个方法可被宿主路由」，Aharou 里不额外做事，
    // 必须返回恒等装饰器，否则模块求值阶段（类定义时）就会报 not a function
    route: () => (value) => value,
    registerUiRoute: (definition) => registerComposeUiRoute(definition),
    registerNavigationEntry: (definition) => recordRegistration('registerNavigationEntry', definition),
    registerDesktopWidget: (definition) => recordRegistration('registerDesktopWidget', definition),
    registerChatMessageMenuItem: (definition) => recordRegistration('registerChatMessageMenuItem', definition),
    registerChatRuntimeHook: (definition) => recordRegistration('registerChatRuntimeHook', definition),
    registerChatLifecycleHook: (definition) => recordRegistration('registerChatLifecycleHook', definition),
    registerChatResponseHook: (definition) => recordRegistration('registerChatResponseHook', definition),
    registerChatComposerSlot: (definition) => recordRegistration('registerChatComposerSlot', definition),
    registerCoreCommand: (definition) => recordRegistration('registerCoreCommand', definition),
    registerHostEventHook: (definition) => recordRegistration('registerHostEventHook', definition),
    registerManifestExtension: (definition) => recordRegistration('registerManifestExtension', definition),
    registerToolPermissionHook: (definition) => recordRegistration('registerToolPermissionHook', definition),
    registerAppLifecycleHook: (definition) => recordRegistration('registerAppLifecycleHook', definition),
    registerMessageProcessingPlugin: (definition) => recordRegistration('registerMessageProcessingPlugin', definition),
    registerXmlRenderPlugin: (definition) => recordRegistration('registerXmlRenderPlugin', definition),
    registerInputMenuTogglePlugin: (definition) => recordRegistration('registerInputMenuTogglePlugin', definition),
    registerChatInputHook: (definition) => recordRegistration('registerChatInputHook', definition),
    registerChatViewHook: (definition) => recordRegistration('registerChatViewHook', definition),
    registerChatMessageHook: (definition) => recordRegistration('registerChatMessageHook', definition),
    registerToolLifecycleHook: (definition) => recordRegistration('registerToolLifecycleHook', definition),
    registerPromptInputHook: (definition) => recordRegistration('registerPromptInputHook', definition),
    registerPromptHistoryHook: (definition) => recordRegistration('registerPromptHistoryHook', definition),
    registerPromptEstimateHistoryHook: (definition) =>
      recordRegistration('registerPromptEstimateHistoryHook', definition),
    registerSystemPromptComposeHook: (definition) => recordRegistration('registerSystemPromptComposeHook', definition),
    registerToolPromptComposeHook: (definition) => recordRegistration('registerToolPromptComposeHook', definition),
    registerPromptFinalizeHook: (definition) => recordRegistration('registerPromptFinalizeHook', definition),
    registerPromptEstimateFinalizeHook: (definition) =>
      recordRegistration('registerPromptEstimateFinalizeHook', definition),
    registerSummaryGenerateHook: (definition) => recordRegistration('registerSummaryGenerateHook', definition),
    registerAiProvider: (definition) => recordRegistration('registerAiProvider', definition),
    getConfigDir: () => getPluginConfigDir(),
    // Operit 区分「本地数据目录」与「空间数据目录」；Aharou 只有一个空间，两者都指向插件自己的数据目录
    getLocalDataDir: () => getPluginConfigDir(),
    getSpaceDataDir: () => getPluginConfigDir(),
    /** 跨包调用：Aharou 没有插件间调用总线，明确报错而不是静默返回空值。 */
    /**
     * 调另一个包（或本包）的公开 API。
     *
     * 目标和本包相同的时候就是「调自己的 registerApi 方法」，直接在本进程派发；
     * 真正跨包的情况需要宿主在插件进程之间中转，Aharou 没有这条总线，明确报错。
     */
    callDependency: (packageId, operation, payload) => {
      const targetPackage = text(packageId).trim();
      const key = text(operation).trim();
      if (!targetPackage || !key) {
        return Promise.reject(pluginError('PARAM_INVALID', 'callDependency 需要包名与操作名'));
      }
      const selfId = text(runtime.pluginId).trim();
      if (selfId && targetPackage === selfId) {
        const local = handlers.get(key);
        if (typeof local === 'function') {
          return Promise.resolve().then(() => local(payload || {}, { handler: key, method: 'ipc.invoke' }));
        }
        return Promise.reject(
          pluginError('HANDLER_NOT_FOUND', '本包没有注册这个 API: ' + key)
        );
      }
      return Promise.reject(
        notSupported(
          '跨包调用 ' + targetPackage + '/' + key,
          'Aharou 没有插件间调用总线；把被调逻辑并进本包，或用 Tools.Files 共享数据'
        )
      );
    },
    /**
     * 读另一个插件声明的资源。
     *
     * 宿主只允许读对方清单里声明过的路径，且限制在其 operit/pkg 目录内；
     * 返回值同样是导出后的可读路径（与 readResource 口径一致）。
     */
    readResourceFromPackage: async (packageId, resourceKey, outputFileName) => {
      const target = text(packageId).trim();
      const key = text(resourceKey).trim();
      if (!target || !key) {
        throw pluginError('PARAM_INVALID', 'readResourceFromPackage 需要包名与资源 key');
      }
      const result = await call('package.readResource', { package: target, key });
      const base64 = text(result && result.base64);
      if (!base64) throw pluginError('RESOURCE_MISSING', '目标插件没有可读的资源: ' + key);
      const dir = runtime.dataDir || process.cwd();
      const name = text(outputFileName).trim() || (text(result && result.path).split('/').pop() || key);
      const target_path = path.resolve(dir, name);
      fs.mkdirSync(path.dirname(target_path), { recursive: true });
      fs.writeFileSync(target_path, Buffer.from(base64, 'base64'));
      return target_path;
    },
    /** 工具授权由宿主自己管，插件既不能列也不能代为批准。 */
    toolPermissions: {
      list: () => Promise.reject(notSupported('插件读取工具授权请求列表')),
      respond: () => Promise.reject(notSupported('插件代为批准工具授权'))
    },
    wasm: {
      open: (moduleId) => Promise.reject(notSupported('插件加载 WASM 模块 ' + text(moduleId)))
    },
    /**
     * v2：readResource(key, outputFileName?, internal?) → 返回**可读的文件路径**（不是内容）。
     *
     * 插件拿这个路径交给 WebView 的 baseUrl 或 File 读取；早先按「路径 → 内容」实现，
     * 调用方把返回值当路径用就会去 open 一个目录，报 `not a file`。
     */
    readResource: async (key, outputFileName, internal) => {
      const name = text(key).trim();
      if (!name) throw pluginError('PARAM_INVALID', 'readResource 需要资源 key');
      const manifest = readPluginManifest();
      const resources = Array.isArray(manifest.resources) ? manifest.resources : [];
      const entry = resources.find((item) => text(item && item.key) === name);
      if (!entry) {
        throw pluginError('RESOURCE_MISSING', '清单里没有这个资源: ' + name);
      }
      const source = path.resolve(pluginPackageRoot(), text(entry.path));
      if (!fs.existsSync(source)) {
        throw pluginError('RESOURCE_MISSING', '资源文件不存在: ' + text(entry.path));
      }
      const targetDir = runtime.dataDir || process.cwd();
      // outputFileName 给的是导出后的名字；不给就沿用原文件名
      const outName = text(outputFileName).trim() || path.basename(source);
      const target = path.resolve(targetDir, outName);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      if (fs.statSync(source).isDirectory()) {
        fs.cpSync(source, target, { recursive: true });
      } else {
        fs.copyFileSync(source, target);
      }
      return target;
    },
    ui: {
      /**
       * v2 的浮动窗口：运行时按 DSL 路由开窗，不要求清单预先声明浮层。
       *
       * 窗口由宿主用 Compose 渲染同一套 DSL，因此内容与面板完全一致。
       */
      floatingWindows: {
        open: async (options) => {
          const opts = isPlainObject(options) ? options : {};
          const id = text(opts.id).trim();
          const route = text(opts.route).trim();
          if (!id || !route) throw pluginError('PARAM_INVALID', 'floatingWindows.open 需要 id 与 route');
          const sizing = isPlainObject(opts.options) ? opts.options : {};
          const result = await call('overlay.createDsl', {
            overlayId: id,
            route,
            width: Number(sizing.width) || undefined,
            height: Number(sizing.height) || undefined,
            x: Number(sizing.x),
            y: Number(sizing.y),
            draggable: sizing.draggable !== false
          });
          return { id: text(result && result.overlayId) || id, kind: 'compose_dsl' };
        },
        create: async (options) => globalThis.ToolPkg.ui.floatingWindows.open(options),
        update: async (id, payload) => {
          const changes = isPlainObject(payload) ? payload : {};
          const result = await call('overlay.update', {
            overlayId: text(id),
            dx: Number(changes.dx) || Number(changes.x),
            dy: Number(changes.dy) || Number(changes.y)
          });
          return { id: text(result && result.overlayId) || text(id) };
        },
        close: async (id) => {
          await call('overlay.close', { overlayId: text(id) });
          return true;
        },
        show: async (id) => {
          await call('overlay.update', { overlayId: text(id) });
          return true;
        },
        hide: async (id) => {
          await call('overlay.close', { overlayId: text(id) });
          return true;
        },
        // 宿主不单独建权限入口：授权与否由 overlays 的权限开关决定
        capabilities: async () => ({ supported: true, compose: true }),
        requestPermission: async () => ({ granted: false })
      }
    }
  };

  /**
   * v2 的 `RuntimeContext.register({...})`：把后台服务函数交给宿主的运行时容器。
   * Aharou 没有这套容器，只记录声明，不伪造调起。
   */
  const RuntimeContext = {
    register: (definition) => recordRegistration('RuntimeContext.register', definition)
  };

  const globals = {
    pluginId: runtime.pluginId,
    ToolPkg,
    RuntimeContext,
    PluginConfig: createPluginConfigApi(),
    Tools: tools,
    toolCall,
    complete,
    done: complete,
    emit: emitIntermediate('emit'),
    delta: emitIntermediate('delta'),
    log: emitIntermediate('log'),
    update: emitIntermediate('update'),
    sendIntermediateResult: emitIntermediate('sendIntermediateResult'),
    getEnv,
    getPluginConfigDir,
    getState,
    getLang,
    getCallerName,
    getChatId,
    getCallerCardId,
    __aharouTakeUnsupportedRegistrations: takeUnsupportedRegistrations,
    __aharouListUiRoutes: listUiRoutes
  };

  runtime.hostLog = hostLog;
  runtime.toolCall = toolCall;
  runtime.rawCall = rawCall;
  runtime.complete = complete;
  runtime.emit = globals.emit;
  runtime.getEnv = getEnv;
  runtime.refreshEnv = refreshEnv;
  runtime.getPluginConfigDir = getPluginConfigDir;
  runtime.getLang = getLang;
  runtime.getState = getState;
  runtime.getCallerName = getCallerName;
  runtime.getChatId = getChatId;
  runtime.getCallerCardId = getCallerCardId;
  runtime.serve = serve;
  runtime.registerHandler = registerHandler;
  /** 让适配层能按名字取回 `registerApi` / `serve` 登记的函数（同一张表）。 */
  runtime.getHandler = (name) => handlers.get(text(name)) || null;
  runtime.hasHandler = (name) => handlers.has(text(name));
  /** 适配层要在 serve 覆盖之前拍快照，按名字列出当前表里的项。 */
  runtime.handlerNames = () => Array.from(handlers.keys());
  runtime.ipc = ipc;
  runtime.registerHandlers = registerHandlers;
  runtime.takeUnsupportedRegistrations = takeUnsupportedRegistrations;
  runtime.listUiRoutes = listUiRoutes;
  runtime.renderUiRoute = (routeId, renderOptions) => getComposeRoutes().render(routeId, renderOptions);
  runtime.dispatchUiAction = (request) => getComposeRoutes().dispatch(request);
  runtime.composeRoutes = () => getComposeRoutes();
  runtime.ToolPkg = ToolPkg;
  runtime.globals = globals;
  runtime.currentCall = currentCall;
  runtime.classifyResult = classifyResult;
  /** 插件自己实现消息循环时（预加载模式），用它按同一套 success 语义把结果回给宿主。 */
  runtime.reply = (id, value) => settle({ id, settled: false }, classifyResult(value));

  installTools(runtime, tools);

  runtime.installGlobals = () => installGlobals(runtime, globals);
  return runtime;
}

// ---------------------------------------------------------------------------
// Tools.*
// ---------------------------------------------------------------------------

/** 宿主没有 environment 概念；只有 primary/空 能映射到当前工作区。 */
function stripEnvironment(options) {
  const payload = compact(options);
  const environment = text(payload.environment).trim();
  delete payload.environment;
  if (environment && environment.toLowerCase() !== 'primary') {
    throw pluginError(
      'ENVIRONMENT_UNSUPPORTED',
      'Aharou 插件只有一个执行环境（工作区 + data://），不支持 environment=' + environment
    );
  }
  return payload;
}

/**
 * v2 的每个 *ResultData 都有格式化 toString()，插件常写 `String(await Tools.Files.info(p))`，
 * 纯对象只会得到 [object Object]。这里补一个不可枚举的 toString：不可枚举是为了让结果
 * 回传给宿主时 JSON.stringify 不会把它带上。
 */
function withToString(value, formatter) {
  if (value === null || typeof value !== 'object') return value;
  // v2 契约里文件类结果用 `successful` 表示成败（FileOperationData），
  // 早先的实现写的是 `ok`。字段名对不上时插件会判成失败并把 details 当异常抛出。
  if (value.successful === undefined && value.ok !== undefined) {
    value.successful = Boolean(value.ok);
  }
  Object.defineProperty(value, 'toString', {
    value: function describe() {
      try {
        return formatter(this);
      } catch (error) {
        return '[object Object]';
      }
    },
    enumerable: false,
    configurable: true,
    writable: true
  });
  return value;
}

/** v2 的 FileInfoData.fileType；宿主 stat 只给 isDir，其余类型推不出来就不猜。 */
function fileTypeOf(isDir) {
  return isDir ? 'directory' : 'file';
}

/** 摘要里的长文本统一截断，避免 toString 把整份输出塞进日志。 */
function clip(value, maxChars) {
  const raw = text(value);
  const limit = maxChars || 200;
  return raw.length > limit ? raw.slice(0, limit) + '...' : raw;
}

function formatFileList(result) {
  const lines = ['[workspace] Directory listing for ' + text(result.path) + ':'];
  (Array.isArray(result.entries) ? result.entries : []).forEach((entry) => {
    const marker = entry.isDir ? 'd' : '-';
    lines.push(marker + String(Number(entry.size) || 0).padStart(8) + ' ' + text(entry.name));
  });
  return lines.join('\n');
}

function formatFileContent(result) {
  return '[workspace] Content of ' + text(result.path) + ':\n' + text(result.content);
}

function formatBinaryContent(result) {
  return (
    '[workspace] Binary content of ' +
    text(result.path) +
    ' (' +
    (Number(result.size) || 0) +
    ' bytes, base64 length=' +
    text(result.contentBase64).length +
    ')'
  );
}

/** v2 FileOperationData.toString 就是 `[env] details`。 */
function formatFileOperation(result) {
  return '[workspace] ' + text(result.details || result.operation || result.path);
}

function formatFileExists(result) {
  if (!result.exists) return '[workspace] No file or directory exists at path: ' + text(result.path);
  const kind = result.isDir ? 'Directory' : 'File';
  return (
    '[workspace] ' +
    kind +
    ' exists at path: ' +
    text(result.path) +
    ' (size: ' +
    (Number(result.size) || 0) +
    ' bytes)'
  );
}

function formatFileInfo(result) {
  if (!result.exists) {
    return '[workspace] File or directory does not exist at path: ' + text(result.path);
  }
  const lines = [
    '[workspace] File information for ' + text(result.path) + ':',
    'Type: ' + text(result.fileType),
    'Size: ' + (Number(result.size) || 0) + ' bytes'
  ];
  // 宿主 stat 不回权限与修改时间，缺字段就不进摘要，不填假值
  if (result.permissions) lines.push('Permissions: ' + text(result.permissions));
  if (result.lastModified) lines.push('Last modified: ' + text(result.lastModified));
  return lines.join('\n');
}

function formatFilePart(result) {
  const range = text(result.startLine) + '-' + text(result.endLine);
  return '[workspace] Lines ' + range + ' of ' + text(result.path) + ':\n' + text(result.content);
}

function formatFindResult(result) {
  const entries = Array.isArray(result.entries)
    ? result.entries
    : Array.isArray(result.matches)
      ? result.matches
      : [];
  const scope = text(result.path || result.searchPath || result.pattern);
  const lines = ['[workspace] Found ' + entries.length + ' entries' + (scope ? ' under ' + scope : '') + ':'];
  entries.slice(0, 200).forEach((entry) => lines.push('- ' + text(entry.path || entry.name)));
  return lines.join('\n');
}

function formatGrepResult(result) {
  const matches = Array.isArray(result.matches) ? result.matches : [];
  return (
    '[workspace] grep ' +
    text(result.pattern) +
    ' in ' +
    text(result.searchPath || result.path) +
    ': ' +
    matches.length +
    ' files, ' +
    (Number(result.totalMatches) || 0) +
    ' matches'
  );
}

function formatFileTransfer(result) {
  return (
    '[workspace] ' +
    (result.url ? 'Downloaded ' + text(result.url) + ' to ' : 'Shared ') +
    text(result.path) +
    (result.size === undefined ? '' : ' (' + (Number(result.size) || 0) + ' bytes)')
  );
}

function formatHttpResponse(result) {
  const lines = [
    'HTTP Response:',
    'URL: ' + text(result.url),
    'Status: ' + (Number(result.statusCode) || 0),
    'Content-Type: ' + text(result.contentType),
    'Size: ' + (Number(result.size) || 0) + ' bytes'
  ];
  if (result.truncated) lines.push('Truncated: true');
  lines.push('', 'Content Summary:', clip(result.content, 2000));
  return lines.join('\n');
}

function formatSystemSetting(result) {
  return (
    'Current value of ' + text(result.namespace) + '.' + text(result.setting) + ': ' + text(result.value)
  );
}

function formatAppList(result) {
  const kind = result.includesSystemApps ? 'All Apps' : 'Third-Party Apps';
  const packages = Array.isArray(result.packages) ? result.packages : [];
  return 'Installed ' + kind + ' List:\n' + packages.join('\n');
}

function formatAppOperation(result) {
  if (result.details) return text(result.details);
  return text(result.operationType) + ': ' + text(result.package);
}

function formatNotifications(result) {
  const list = Array.isArray(result.notifications) ? result.notifications : [];
  const lines = ['Device Notifications (' + list.length + ' total):'];
  list.forEach((item, index) => {
    lines.push(index + 1 + '. Package: ' + text(item.packageName));
    lines.push('   Content: ' + text(item.text));
    lines.push('');
  });
  if (!list.length) lines.push('No notifications');
  return lines.join('\n');
}

function formatTerminalCommand(result) {
  const lines = ['Terminal Command Execution Result:'];
  if (result.command) lines.push('Command: ' + text(result.command));
  lines.push('Session: ' + text(result.sessionId || result.session_id));
  lines.push(
    'Exit Code: ' +
      (result.exitCode === undefined || result.exitCode === null ? 'unknown' : text(result.exitCode))
  );
  lines.push('', 'Output:');
  lines.push(text(result.output));
  return lines.join('\n');
}

function formatTerminalSession(result) {
  return (
    'Terminal session ' +
    text(result.sessionId || result.session_id) +
    (result.title ? " ('" + text(result.title) + "')" : '') +
    ' ready'
  );
}

function formatChatList(result) {
  const chats = Array.isArray(result.chats)
    ? result.chats
    : Array.isArray(result.sessions)
      ? result.sessions
      : [];
  const lines = ['Chats (' + chats.length + '):'];
  chats.forEach((item) => {
    lines.push('- ' + text(item.id) + '  ' + text(item.title));
  });
  return lines.join('\n');
}

function formatAgentStatus(result) {
  const state = text(result.state || (result.running ? 'running' : 'idle'));
  return 'Agent status of ' + text(result.sessionId || result.chatId) + ': ' + state;
}

function formatCharacterCards(result) {
  const cards = Array.isArray(result.cards) ? result.cards : [];
  const lines = ['Character Cards (' + (Number(result.totalCount) || cards.length) + ' total):'];
  cards.forEach((card) => lines.push('- ' + text(card.id) + '  ' + text(card.name)));
  if (!cards.length) lines.push('No cards');
  return lines.join('\n');
}

function formatChatMessages(result) {
  const messages = Array.isArray(result.messages) ? result.messages : [];
  const range =
    result.start !== undefined && result.end !== undefined ? ', range=' + result.start + '-' + result.end : '';
  return (
    'Chat messages: ' +
    text(result.chatId || result.sessionId) +
    ' (limit=' +
    (Number(result.limit) || messages.length) +
    range +
    ')\nTotal: ' +
    messages.length
  );
}

function formatMessageSent(result) {
  const lines = [
    'Message sent to chat: ' + text(result.chatId),
    'Message content: ' + clip(result.message, 50)
  ];
  if (result.aiResponse) lines.push('AI Reply: ' + clip(result.aiResponse, 200));
  return lines.join('\n');
}

function formatSendAndWait(result) {
  const lines = ['Message sent to chat: ' + text(result.sessionId)];
  if (result.timedOut) lines.push('Timed out waiting for the reply');
  lines.push('AI Reply: ' + (result.message ? clip(result.message, 500) : '(none)'));
  return lines.join('\n');
}

function normalizeEntry(entry, basePath) {
  const isDir = entry.isDir === true || entry.isDirectory === true;
  const name = text(entry.name);
  return {
    name,
    isDir,
    isDirectory: isDir,
    fileType: fileTypeOf(isDir),
    size: Number(entry.size || 0),
    path: text(entry.path) || (basePath ? (basePath.replace(/\/+$/, '') + '/' + name) : name)
  };
}

function normalizeFileInfo(result, filePath) {
  const isDir = result && (result.isDir === true || result.isDirectory === true);
  const info = {
    exists: Boolean(result && result.exists),
    isDir,
    isDirectory: isDir,
    size: Number((result && result.size) || 0),
    path: filePath
  };
  if (info.exists) info.fileType = fileTypeOf(isDir);
  return info;
}

const CALC_FUNCTIONS = {
  sqrt: Math.sqrt,
  abs: Math.abs,
  sin: Math.sin,
  cos: Math.cos,
  tan: Math.tan,
  log: Math.log10,
  ln: Math.log,
  exp: Math.exp,
  round: Math.round,
  floor: Math.floor,
  ceil: Math.ceil,
  min: Math.min,
  max: Math.max,
  pow: Math.pow
};
const CALC_CONSTANTS = { pi: Math.PI, e: Math.E };

function evaluateArithmetic(expression) {
  const source = text(expression);
  if (!source.trim()) throw pluginError('PARAM_INVALID', 'calc 需要非空表达式');
  if (source.length > 1000) throw pluginError('PARAM_INVALID', 'calc 表达式过长');
  let pos = 0;
  const fail = () => {
    throw pluginError('PARAM_INVALID', 'calc 无法解析表达式: ' + source);
  };
  const skip = () => {
    while (pos < source.length && /\s/.test(source[pos])) pos += 1;
  };
  function parseExpression() {
    let value = parseTerm();
    for (;;) {
      skip();
      const op = source[pos];
      if (op !== '+' && op !== '-') return value;
      pos += 1;
      const rhs = parseTerm();
      value = op === '+' ? value + rhs : value - rhs;
    }
  }
  function parseTerm() {
    let value = parsePower();
    for (;;) {
      skip();
      const op = source[pos];
      if (op !== '*' && op !== '/' && op !== '%') return value;
      pos += 1;
      const rhs = parsePower();
      if ((op === '/' || op === '%') && rhs === 0) throw pluginError('CALC_DIVIDE_BY_ZERO', 'calc 除以 0');
      value = op === '*' ? value * rhs : op === '/' ? value / rhs : value % rhs;
    }
  }
  function parsePower() {
    const base = parseUnary();
    skip();
    if (source[pos] === '^') {
      pos += 1;
      return Math.pow(base, parsePower());
    }
    return base;
  }
  function parseUnary() {
    skip();
    if (source[pos] === '+') {
      pos += 1;
      return parseUnary();
    }
    if (source[pos] === '-') {
      pos += 1;
      return -parseUnary();
    }
    return parsePrimary();
  }
  function parsePrimary() {
    skip();
    if (source[pos] === '(') {
      pos += 1;
      const value = parseExpression();
      skip();
      if (source[pos] !== ')') fail();
      pos += 1;
      return value;
    }
    const number = /^(?:\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\.\d+)/.exec(source.slice(pos));
    if (number) {
      pos += number[0].length;
      return Number(number[0]);
    }
    const identifier = /^[A-Za-z_][A-Za-z0-9_]*/.exec(source.slice(pos));
    if (identifier) {
      const name = identifier[0].toLowerCase();
      pos += identifier[0].length;
      if (Object.prototype.hasOwnProperty.call(CALC_CONSTANTS, name)) return CALC_CONSTANTS[name];
      if (Object.prototype.hasOwnProperty.call(CALC_FUNCTIONS, name)) {
        skip();
        if (source[pos] !== '(') fail();
        pos += 1;
        const args = [];
        skip();
        if (source[pos] !== ')') {
          args.push(parseExpression());
          for (;;) {
            skip();
            if (source[pos] !== ',') break;
            pos += 1;
            args.push(parseExpression());
          }
        }
        skip();
        if (source[pos] !== ')') fail();
        pos += 1;
        return CALC_FUNCTIONS[name].apply(null, args);
      }
    }
    fail();
  }
  const result = parseExpression();
  skip();
  if (pos !== source.length) fail();
  return { expression: source, result, finite: Number.isFinite(result) };
}

function installTools(runtime, tools) {
  const call = (method, params) => runtime.toolCall(method, params);

  // ---------------- Files ----------------
  const files = {
    list: async (targetPath, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      if (!params.path) throw pluginError('PARAM_INVALID', 'Tools.Files.list 需要 path');
      const result = await call('fs.list', params);
      const entries = Array.isArray(result && result.entries) ? result.entries : [];
      // 宿主 fs.list 只回 name/isDir/size，permissions/lastModified 无源可拿，省字段不填假值
      return withToString(
        { path: params.path, entries: entries.map((entry) => normalizeEntry(entry, params.path)) },
        formatFileList
      );
    },

    read: async (pathOrOptions) => {
      let options;
      if (typeof pathOrOptions === 'string') options = { path: pathOrOptions };
      else if (isPlainObject(pathOrOptions)) options = pathOrOptions;
      else throw pluginError('PARAM_INVALID', 'Tools.Files.read 需要路径或 { path } 选项');
      const params = stripEnvironment(options);
      if (!params.path) throw pluginError('PARAM_INVALID', 'Tools.Files.read 需要 path');
      // intent 是 Operit 的语义提示（原样读取 / 摘要），宿主不做区分，丢弃即可
      delete params.intent;
      const result = await call('fs.read', { path: params.path });
      return withToString(
        {
          path: params.path,
          content: text(result && result.content),
          size: Number((result && result.size) || byteLength(result && result.content))
        },
        formatFileContent
      );
    },

    readBinary: async (targetPath, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const result = await call('fs.readBinary', params);
      const base64 = text(result && result.base64);
      // contentBase64 是 v2 的字段名，按 v2 写的插件直接读它
      return withToString(
        {
          path: params.path,
          contentBase64: base64,
          base64,
          base64Content: base64,
          content: base64,
          size: Number((result && result.size) || 0)
        },
        formatBinaryContent
      );
    },

    /** 1 基行号、闭区间；宿主没有 fs.readRange 时退化为整读后本地切行。 */
    readPart: async (targetPath, startLine, endLine, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const start = Math.max(1, Number(startLine) || 1);
      const end = endLine === undefined || endLine === null ? 0 : Math.max(start, Number(endLine) || start);
      try {
        const result = await call('fs.readRange', { path: params.path, startLine: start, endLine: end });
        return withToString(
          {
            path: params.path,
            content: text(result && result.content),
            size: Number((result && result.size) || 0),
            startLine: Number((result && result.startLine) || start),
            endLine: Number((result && result.endLine) || end),
            totalLines: result && result.totalLines !== undefined ? result.totalLines : null,
            truncated: result ? Boolean(result.truncated) : false
          },
          formatFilePart
        );
      } catch (error) {
        if (!isUnknownMethod(error)) throw error;
        const whole = await call('fs.read', { path: params.path });
        const content = text(whole && whole.content);
        const lines = content.split('\n');
        const from = start - 1;
        const to = end > 0 ? end : lines.length;
        const slice = lines.slice(from, to).join('\n');
        return withToString(
          {
            path: params.path,
            content: slice,
            size: byteLength(slice),
            startLine: start,
            endLine: Math.min(to, lines.length),
            totalLines: lines.length,
            truncated: to < lines.length,
            degraded: 'local-line-slice'
          },
          formatFilePart
        );
      }
    },

    write: async (targetPath, content, append, environment) => {
      const params = stripEnvironment({
        path: text(targetPath),
        content: content === undefined || content === null ? '' : text(content),
        environment
      });
      if (append === true || append === 'true') {
        const result = await call('fs.append', { path: params.path, content: params.content });
        const size = byteLength(params.content);
        return withToString(
          {
            ok: true,
            operation: 'append',
            path: params.path,
            appended: true,
            size,
            details: 'Appended ' + size + ' bytes to ' + params.path,
            result
          },
          formatFileOperation
        );
      }
      const result = await call('fs.write', { path: params.path, content: params.content });
      const written = byteLength(params.content);
      return withToString(
        {
          ok: true,
          operation: 'write',
          path: params.path,
          appended: false,
          size: written,
          details: 'Wrote ' + written + ' bytes to ' + params.path,
          result
        },
        formatFileOperation
      );
    },

    writeBinary: async (targetPath, base64Content, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      await call('fs.writeBinary', { path: params.path, base64: text(base64Content) });
      return withToString(
        {
          ok: true,
          operation: 'write_binary',
          path: params.path,
          details: 'Wrote binary content to ' + params.path
        },
        formatFileOperation
      );
    },

    deleteFile: async (targetPath, recursive, environment) => {
      const params = stripEnvironment({ path: text(targetPath), recursive, environment });
      // 宿主 fs.delete 恒为递归删除，recursive=false 只能尽力提示
      await call('fs.delete', { path: params.path });
      return withToString(
        {
          ok: true,
          operation: 'delete',
          path: params.path,
          recursive: true,
          details: 'Deleted ' + params.path
        },
        formatFileOperation
      );
    },

    exists: async (targetPath, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const stat = await call('fs.stat', params);
      const info = normalizeFileInfo(stat, params.path);
      return withToString(info, formatFileExists);
    },

    move: async (source, destination, environment) => {
      const sourcePath = text(source);
      const destinationPath = text(destination);
      const environmentName = text(environment);
      stripEnvironment({ environment: environmentName });
      await call('fs.rename', { from: sourcePath, to: destinationPath });
      return withToString(
        {
          ok: true,
          operation: 'move',
          path: destinationPath,
          source: sourcePath,
          destination: destinationPath,
          details: 'Moved ' + sourcePath + ' to ' + destinationPath
        },
        formatFileOperation
      );
    },

    copy: async (source, destination, recursive, sourceEnvironment, destinationEnvironment) => {
      const sourcePath = text(source);
      const destinationPath = text(destination);
      stripEnvironment({ environment: sourceEnvironment });
      stripEnvironment({ environment: destinationEnvironment });
      // 宿主 copy 支持递归；overwrite 默认 false，目标已存在时回 FILE_EXISTS
      await call('fs.copy', { from: sourcePath, to: destinationPath, overwrite: false });
      return withToString(
        {
          ok: true,
          operation: 'copy',
          path: destinationPath,
          source: sourcePath,
          destination: destinationPath,
          recursive: recursive !== false,
          details: 'Copied ' + sourcePath + ' to ' + destinationPath
        },
        formatFileOperation
      );
    },

    mkdir: async (targetPath, createParents, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      await call('fs.mkdir', { path: params.path });
      return withToString(
        {
          ok: true,
          operation: 'mkdir',
          path: params.path,
          createParents: createParents !== false,
          details: 'Created directory ' + params.path
        },
        formatFileOperation
      );
    },

    find: async (targetPath, pattern, options, environment) => {
      const params = stripEnvironment(Object.assign({ path: text(targetPath), pattern: text(pattern) }, options || {}));
      const result = await call('fs.find', params);
      return withToString(
        result === null || result === undefined ? { path: params.path, pattern: params.pattern, entries: [] } : result,
        formatFindResult
      );
    },

    grep: async (targetPath, pattern, options) => {
      const params = stripEnvironment(Object.assign({ path: text(targetPath), pattern: text(pattern) }, options || {}));
      return withToString(await call('fs.grep', params), formatGrepResult);
    },

    /** Operit 的 grep_context 是「按意图找上下文」，宿主没有等价实现，不做假装。 */
    grepContext: async () => {
      throw notSupported(
        '按意图检索代码上下文（Tools.Files.grepContext）',
        'Tools.Files.grep({ path, pattern })（正则检索）或 Tools.Files.read 读完自己筛'
      );
    },

    info: async (targetPath, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const stat = await call('fs.stat', params);
      return withToString(normalizeFileInfo(stat, params.path), formatFileInfo);
    },

    /** 宿主有 fs.edit 就用它；没有时本地读-改-写，语义保持「必须命中老内容」。 */
    edit: async (targetPath, oldContent, newContent, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const oldText = oldContent === undefined || oldContent === null ? '' : text(oldContent);
      const newText = newContent === undefined || newContent === null ? '' : text(newContent);
      if (!oldText) throw pluginError('PARAM_INVALID', 'Tools.Files.edit 需要 oldContent');
      try {
        const result = await call('fs.edit', { path: params.path, old: oldText, new: newText });
        return { ok: true, path: params.path, replaced: result && result.replaced !== undefined ? result.replaced : 1 };
      } catch (error) {
        if (!isUnknownMethod(error)) throw error;
        const whole = await call('fs.read', { path: params.path });
        const content = text(whole && whole.content);
        const index = content.indexOf(oldText);
        if (index < 0) {
          throw pluginError('EDIT_NOT_FOUND', '文件里没有找到要替换的内容: ' + params.path);
        }
        const updated = content.slice(0, index) + newText + content.slice(index + oldText.length);
        await call('fs.write', { path: params.path, content: updated });
        return { ok: true, path: params.path, replaced: 1, degraded: 'local-read-modify-write' };
      }
    },

    apply: async (targetPath, type, oldContent, newContent, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const kind = text(type) || 'replace';
      const oldText = oldContent === undefined || oldContent === null ? '' : text(oldContent);
      const newText = newContent === undefined || newContent === null ? '' : text(newContent);
      try {
        const result = await call('fs.edit', { path: params.path, old: oldText, new: newText, type: kind });
        return { ok: true, path: params.path, type: kind, result };
      } catch (error) {
        if (!isUnknownMethod(error)) throw error;
        return files.edit(params.path, oldText, newText);
      }
    },

    create: async (targetPath, newContent, environment) => {
      const params = stripEnvironment({ path: text(targetPath), environment });
      const content = newContent === undefined || newContent === null ? '' : text(newContent);
      await call('fs.write', { path: params.path, content });
      const written = byteLength(content);
      return withToString(
        {
          ok: true,
          operation: 'create',
          path: params.path,
          size: written,
          details: 'Created ' + params.path + ' (' + written + ' bytes)'
        },
        formatFileOperation
      );
    },

    zip: async (source, destination, environment) => {
      const params = stripEnvironment({ path: text(source), target: text(destination), environment });
      const result = await call('fs.zip', { path: params.path, target: params.target });
      return withToString(
        {
          ok: true,
          operation: 'zip',
          path: text(destination),
          source: text(source),
          entries: (result && result.entries) || 0,
          bytes: (result && result.bytes) || 0,
          details: 'Created archive ' + text(destination)
        },
        formatFileOperation
      );
    },

    /** 宿主 fs.unzip 会逐条目校验路径、拒绝 `..` 逃逸，并自动创建目标目录。 */
    unzip: async (source, destination, environment) => {
      const params = stripEnvironment({
        path: text(source),
        target: text(destination),
        environment
      });
      if (!params.path) throw pluginError('PARAM_INVALID', 'Tools.Files.unzip 需要 source');
      if (!params.target) throw pluginError('PARAM_INVALID', 'Tools.Files.unzip 需要 destination');
      const result = await call('fs.unzip', { path: params.path, target: params.target });
      const entries = Number((result && result.entries) || 0);
      const bytes = Number((result && result.bytes) || 0);
      return withToString(
        {
          ok: true,
          operation: 'unzip',
          path: params.target,
          source: params.path,
          entries,
          bytes,
          extracted: Array.isArray(result && result.paths) ? result.paths : [],
          details: 'Extracted ' + entries + ' entries (' + bytes + ' bytes) from ' + params.path + ' to ' + params.target
        },
        formatFileOperation
      );
    },

    /** 宿主经 FileProvider 交给系统应用打开；容器路径由宿主换算成宿主路径。 */
    open: async (targetPath) => {
      const params = stripEnvironment({ path: text(targetPath) });
      const result = await call('fs.open', { path: params.path });
      return withToString(
        {
          ok: true,
          operation: 'open',
          path: params.path,
          mimeType: text(result && result.mimeType),
          details: 'Opened ' + params.path
        },
        formatFileOperation
      );
    },

    /** 宿主只能分享文本：能当文本读就分享内容，读不出来就明确失败。 */
    share: async (targetPath, title) => {
      const filePath = text(targetPath);
      const read = await call('fs.read', { path: filePath });
      const content = text(read && read.content);
      if (!content) throw pluginError('EMPTY_FILE', '文件为空，没有可分享的内容: ' + filePath);
      await call('share.text', { text: content, title: text(title) || undefined });
      return withToString(
        { ok: true, path: filePath, details: 'Shared text content of ' + filePath, degraded: 'share-text-content' },
        formatFileTransfer
      );
    },

    /** 宿主 http 只回文本体：按文本落盘，二进制下载会失真（见 README）。 */
    download: async (urlOrOptions, destination, environment, headers) => {
      let options;
      if (typeof urlOrOptions === 'string') options = { url: urlOrOptions };
      else if (isPlainObject(urlOrOptions)) options = Object.assign({}, urlOrOptions);
      else throw pluginError('PARAM_INVALID', 'Tools.Files.download 需要 url 或 { url } 选项');
      const url = text(options.url);
      if (!url) throw pluginError('PARAM_INVALID', 'Tools.Files.download 需要 url');
      const target = text(destination || options.destination);
      if (!target) throw pluginError('PARAM_INVALID', 'Tools.Files.download 需要 destination');
      const headerObject = isPlainObject(headers) ? headers : isPlainObject(options.headers) ? options.headers : null;
      // responseBodyBase64 是宿主原生能力：要它才能把二进制下载得一字不差
      const response = await call(
        'http.request',
        compact({ url, method: 'GET', headers: headerObject, responseBodyBase64: true })
      );
      const status = Number((response && response.status) || 0);
      if (status >= 400) {
        throw pluginError('HTTP_FAILED', '下载失败，HTTP ' + status + ': ' + url);
      }
      const base64 = text(response && response.bodyBase64);
      if (base64) {
        await call('fs.writeBinary', { path: target, base64 });
        const stat = await call('fs.stat', { path: target });
        return withToString(
          {
            ok: true,
            url,
            path: target,
            status,
            size: Number((stat && stat.size) || 0),
            truncated: Boolean(response && response.truncated)
          },
          formatFileTransfer
        );
      }
      // 宿主没有回 bodyBase64（旧版或降级路径）时只能按文本落盘
      const body = text(response && response.body);
      await call('fs.write', { path: target, content: body });
      return withToString(
        {
          ok: true,
          url,
          path: target,
          status,
          size: byteLength(body),
          truncated: Boolean(response && response.truncated),
          degraded: 'text-body-only'
        },
        formatFileTransfer
      );
    },

    // ---- 文件名别名：社区插件按 Node 风格写 delete/unlink/remove、createDirectory ----
    // 转发全部参数，保持与 deleteFile(path, recursive, environment) 一致
    delete: (...args) => files.deleteFile(...args),
    unlink: (...args) => files.deleteFile(...args),
    remove: (...args) => files.deleteFile(...args),
    createDirectory: (...args) => files.mkdir(...args),
    makeDirectory: (...args) => files.mkdir(...args)
  };

  // ---------------- Net ----------------
  /** Operit 用 snake_case，宿主用 camelCase：两边都收，统一转成宿主写法。 */
  function pickOption(source, camel, snake) {
    if (source[camel] !== undefined) return source[camel];
    if (source[snake] !== undefined) return source[snake];
    return undefined;
  }

  function normalizeHeaders(raw) {
    if (!isPlainObject(raw)) return undefined;
    const headers = {};
    Object.keys(raw).forEach((key) => {
      const value = raw[key];
      if (value !== undefined && value !== null) headers[key] = text(value);
    });
    return headers;
  }

  function httpRequest(options) {
    const source = typeof options === 'string' ? { url: options } : Object.assign({}, options || {});
    const bodyValue = source.body === undefined ? source.data : source.body;
    const bodyBase64 = text(pickOption(source, 'bodyBase64', 'body_base64')).trim();
    const payload = compact({
      url: text(source.url),
      method: text(source.method || source.type || 'GET').toUpperCase(),
      headers: normalizeHeaders(source.headers),
      body:
        bodyValue === undefined || bodyValue === null
          ? undefined
          : typeof bodyValue === 'string'
            ? bodyValue
            : JSON.stringify(bodyValue),
      bodyBase64: bodyBase64 || undefined,
      multipart: isPlainObject(source.multipart) ? source.multipart : undefined,
      timeout: source.timeout === undefined || source.timeout === null ? undefined : Math.max(1, Number(source.timeout)),
      followRedirects: pickOption(source, 'followRedirects', 'follow_redirects'),
      ignoreSsl: pickOption(source, 'ignoreSsl', 'ignore_ssl'),
      maxBytes: source.maxBytes === undefined ? undefined : Number(source.maxBytes),
      responseBodyBase64: pickOption(source, 'responseBodyBase64', 'response_body_base64')
    });
    if (!payload.url) return Promise.reject(pluginError('PARAM_INVALID', 'Tools.Net 需要 url'));
    return call('http.request', payload).then((result) => {
      const body = text(result && result.body);
      const encoded = text(result && result.bodyBase64);
      // content / contentBase64 / size 是 v2 HttpResponseData 的字段名，一并给出
      return withToString(
        {
          url: payload.url,
          status: Number((result && result.status) || 0),
          statusCode: Number((result && result.status) || 0),
          headers: (result && result.headers) || {},
          contentType: text((result && result.headers && (result.headers['content-type'] || result.headers['Content-Type']))),
          body: encoded && !body ? encoded : body,
          content: encoded && !body ? encoded : body,
          data: encoded && !body ? encoded : body,
          bodyBase64: encoded || undefined,
          contentBase64: encoded || undefined,
          size: encoded ? Math.floor((encoded.replace(/=+$/, '').length * 3) / 4) : byteLength(body),
          truncated: Boolean(result && result.truncated)
        },
        formatHttpResponse
      );
    });
  }

  /** v2 的 browserClick/browserType 等只认 options 对象；这里统一取 ref / selector。 */
  function browserSelectorParams(options) {
    const opts = isPlainObject(options) ? options : {};
    return compact({
      ref: text(opts.ref).trim() || undefined,
      selector: text(opts.selector).trim() || undefined
    });
  }

  const browserTools = {
    /** startBrowser 不是 v2 facade 属性，但声明的参数与 browser.start 一一对应。 */
    startBrowser: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      return call(
        'browser.start',
        compact({
          url: text(opts.url).trim() || undefined,
          user_agent: text(pickOption(opts, 'userAgent', 'user_agent')).trim() || undefined,
          headers: opts.headers,
          session_name: text(pickOption(opts, 'sessionName', 'session_name')).trim() || undefined
        })
      );
    },
    stopBrowser: async (sessionIdOrOptions) => {
      const opts =
        typeof sessionIdOrOptions === 'string'
          ? { session_id: sessionIdOrOptions }
          : isPlainObject(sessionIdOrOptions)
            ? sessionIdOrOptions
            : {};
      const closeAll = pickOption(opts, 'closeAll', 'close_all');
      return call(
        'browser.stop',
        compact({
          session_id: text(pickOption(opts, 'sessionId', 'session_id')).trim() || undefined,
          close_all: closeAll === undefined ? undefined : Boolean(closeAll)
        })
      );
    },
    browserNavigate: async (urlOrOptions) => {
      const options = typeof urlOrOptions === 'string' ? { url: urlOrOptions } : Object.assign({}, urlOrOptions || {});
      const url = text(options.url).trim();
      if (!url) throw pluginError('PARAM_INVALID', 'browserNavigate 需要 url');
      return call('browser.navigate', compact({ url, headers: options.headers }));
    },
    browserNavigateBack: async () => call('browser.navigateBack', {}),
    browserClick: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const params = browserSelectorParams(opts);
      if (!params.ref && !params.selector) {
        throw pluginError('PARAM_INVALID', 'browserClick 需要 ref 或 selector');
      }
      const button = text(opts.button).trim().toLowerCase();
      if (button && button !== 'left') params.button = button;
      if (Array.isArray(opts.modifiers) && opts.modifiers.length) {
        params.modifiers = opts.modifiers.map((item) => text(item));
      }
      if (opts.doubleClick !== undefined) params.doubleClick = Boolean(opts.doubleClick);
      return call('browser.click', params);
    },
    browserType: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const params = browserSelectorParams(opts);
      if (!params.ref && !params.selector) throw pluginError('PARAM_INVALID', 'browserType 需要 ref 或 selector');
      params.text = text(opts.text);
      if (!params.text) throw pluginError('PARAM_INVALID', 'browserType 需要 text');
      if (opts.submit !== undefined) params.submit = Boolean(opts.submit);
      if (opts.slowly !== undefined) params.slowly = Boolean(opts.slowly);
      return call('browser.type', params);
    },
    browserPressKey: async (keyOrOptions) => {
      const key =
        typeof keyOrOptions === 'string'
          ? keyOrOptions
          : text(isPlainObject(keyOrOptions) ? keyOrOptions.key : '');
      if (!text(key).trim()) throw pluginError('PARAM_INVALID', 'browserPressKey 需要 key');
      return call('browser.press', { key: text(key).trim() });
    },
    browserSelectOption: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const params = browserSelectorParams(opts);
      if (!params.ref && !params.selector) {
        throw pluginError('PARAM_INVALID', 'browserSelectOption 需要 ref 或 selector');
      }
      const values = Array.isArray(opts.values) ? opts.values.map((item) => text(item)) : [];
      if (!values.length) throw pluginError('PARAM_INVALID', 'browserSelectOption 需要非空 values 数组');
      params.values = values;
      return call('browser.select', params);
    },
    browserHover: async (options) => {
      const params = browserSelectorParams(options);
      if (!params.ref && !params.selector) throw pluginError('PARAM_INVALID', 'browserHover 需要 ref 或 selector');
      return call('browser.hover', params);
    },
    browserResize: async (options, maybeHeight) => {
      const opts = isPlainObject(options) ? options : {};
      const width = Number(opts.width === undefined && typeof options === 'number' ? options : opts.width);
      const height = Number(opts.height === undefined && maybeHeight !== undefined ? maybeHeight : opts.height);
      if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0) {
        throw pluginError('PARAM_INVALID', 'browserResize 需要正整数 width/height');
      }
      return call('browser.resize', { width, height });
    },
    browserEvaluate: async (options) => {
      const opts = typeof options === 'string' ? { function: options } : isPlainObject(options) ? options : {};
      const script = text(opts.function !== undefined ? opts.function : opts.code);
      if (!script) throw pluginError('PARAM_INVALID', 'browserEvaluate 需要 function');
      return call(
        'browser.evaluate',
        compact({ function: script, ref: text(opts.ref).trim() || undefined })
      );
    },
    browserWaitFor: async (options) => {
      const opts = typeof options === 'number' ? { time: options } : isPlainObject(options) ? options : {};
      const params = compact({
        time: opts.time === undefined ? undefined : Number(opts.time),
        text: text(opts.text).trim() || undefined,
        textGone: text(pickOption(opts, 'textGone', 'text_gone')).trim() || undefined
      });
      if (params.time === undefined && !params.text && !params.textGone) {
        throw pluginError('PARAM_INVALID', 'browserWaitFor 需要 time / text / textGone 之一');
      }
      return call('browser.waitFor', params);
    },
    browserSnapshot: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      return call(
        'browser.snapshot',
        compact({
          filename: text(opts.filename).trim() || undefined,
          selector: text(opts.selector).trim() || undefined,
          depth: opts.depth === undefined ? undefined : Number(opts.depth)
        })
      );
    },
    browserTakeScreenshot: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      return call(
        'browser.screenshot',
        compact({
          type: text(opts.type).trim() || undefined,
          element: text(opts.element).trim() || undefined,
          ref: text(opts.ref).trim() || undefined,
          fullPage: opts.fullPage === undefined ? undefined : Boolean(opts.fullPage),
          filename: text(opts.filename).trim() || undefined
        })
      );
    },
    browserConsoleMessages: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      return call(
        'browser.console',
        compact({
          level: text(opts.level).trim() || undefined,
          filename: text(opts.filename).trim() || undefined
        })
      );
    },
    browserFillForm: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const fields = Array.isArray(opts.fields) ? opts.fields : [];
      if (!fields.length) throw pluginError('PARAM_INVALID', 'browserFillForm 需要非空 fields 数组');
      const mapped = fields.map((field, index) => {
        if (!isPlainObject(field)) throw pluginError('PARAM_INVALID', 'browserFillForm fields[' + index + '] 必须是对象');
        return compact({
          name: text(field.name) || 'field#' + index,
          type: text(field.type).toLowerCase() || undefined,
          value: field.value,
          ref: text(field.ref).trim() || undefined,
          selector: text(field.selector).trim() || undefined
        });
      });
      return call('browser.fillForm', { fields: mapped });
    },
    browserHandleDialog: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      if (typeof opts.accept !== 'boolean') {
        throw pluginError('PARAM_INVALID', 'browserHandleDialog 需要布尔 accept');
      }
      return call(
        'browser.handleDialog',
        compact({ accept: opts.accept, promptText: text(opts.promptText).trim() || undefined })
      );
    },
    browserTabs: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      let action = text(opts.action).trim().toLowerCase() || 'list';
      // v2 用 create，宿主叫 new
      if (action === 'create') action = 'new';
      return call(
        'browser.tabs',
        compact({ action, index: opts.index === undefined ? undefined : Number(opts.index) })
      );
    },
    browserClose: async () => call('browser.close', {}),
    browserCloseAll: async () => call('browser.closeAll', {})
  };

  const unsupportedBrowser = {};
  // 宿主内置浏览器没有对应原语：抓包、拖拽、文件选择器、Playwright 代码与会话用户脚本
  [
    'browserNetworkRequests',
    'browserDrag',
    'browserFileUpload',
    'browserRunCode',
    'browserUserscriptInstall',
    'browserUserscriptList',
    'browserUserscriptStart',
    'browserUserscriptStop',
    'browserUserscriptUninstall'
  ].forEach((name) => {
    unsupportedBrowser[name] = () => Promise.reject(pluginError('NOT_SUPPORTED', UNSUPPORTED_BROWSER));
  });

  const net = {
    http: httpRequest,
    httpGet: (url, ignoreSsl) => httpRequest({ url, method: 'GET', ignoreSsl }),
    httpPost: (url, body, ignoreSsl) => httpRequest({ url, method: 'POST', body, ignoreSsl }),
    /** visit_web 的降级：只取 HTML 原文，不渲染 JS、不转 markdown。 */
    visit: async (paramsOrUrl) => {
      const options = typeof paramsOrUrl === 'string' ? { url: paramsOrUrl } : Object.assign({}, paramsOrUrl || {});
      const response = await httpRequest({ url: options.url, method: 'GET', headers: options.headers });
      return withToString(
        {
          url: text(options.url),
          status: response.status,
          contentType: text((response.headers || {})['content-type'] || (response.headers || {})['Content-Type']),
          content: response.body,
          body: response.body,
          truncated: response.truncated,
          degraded: 'raw-html'
        },
        (result) =>
          'Visited ' + text(result.url) + ' (HTTP ' + (Number(result.status) || 0) + ', ' + byteLength(result.content) + ' bytes)'
      );
    },
    /** 宿主 http.request 原生支持 multipart：files 读成 base64 再拼。 */
    uploadFile: async (options) => {
      const source = Object.assign({}, options || {});
      const url = text(source.url);
      if (!url) throw pluginError('PARAM_INVALID', 'uploadFile 需要 url');
      const files = Array.isArray(source.files) ? source.files : [];
      const fields = isPlainObject(source.form_data) ? source.form_data : isPlainObject(source.fields) ? source.fields : {};
      if (!files.length && !Object.keys(fields).length) {
        throw pluginError('PARAM_INVALID', 'uploadFile 需要 files 或 form_data');
      }
      const mappedFiles = [];
      for (const item of files) {
        if (!isPlainObject(item)) throw pluginError('PARAM_INVALID', 'uploadFile 的 files 项必须是对象');
        const filePath = text(item.path || item.filePath);
        if (!filePath) throw pluginError('PARAM_INVALID', 'uploadFile 的文件项缺少 path/filePath');
        const binary = await call('fs.readBinary', { path: filePath });
        mappedFiles.push(
          compact({
            name: text(item.name || item.fieldName || 'file'),
            fileName: text(item.fileName || item.filename) || path.basename(filePath),
            mimeType: text(item.mimeType || item.mime),
            base64: text(binary && binary.base64)
          })
        );
      }
      const lockedFields = {};
      Object.keys(fields).forEach((key) => {
        lockedFields[key] = text(fields[key]);
      });
      return httpRequest({
        url,
        method: text(source.method || 'POST').toUpperCase(),
        headers: source.headers,
        timeout: source.timeout,
        ignoreSsl: pickOption(source, 'ignoreSsl', 'ignore_ssl'),
        multipart: { fields: lockedFields, files: mappedFiles }
      });
    },
    cookies: {
      get: async (url) => {
        const target = text(url).trim();
        if (!target) throw pluginError('PARAM_INVALID', 'Tools.Net.cookies.get 需要 url');
        const result = await call('net.cookies', { url: target, action: 'get' });
        return text(result && result.cookie);
      },
      set: async (url, cookie) => {
        const target = text(url).trim();
        if (!target) throw pluginError('PARAM_INVALID', 'Tools.Net.cookies.set 需要 url');
        const result = await call('net.cookies', { url: target, cookie: text(cookie), action: 'set' });
        return result && result.ok === true;
      }
    }
  };
  // v2 的浏览器方法返回字符串：给结果对象补 toString 指向宿主的 result 文本，
  // 既保留 pageURL/tabId/imagePath 等结构化字段，String(result) 又能拿到与 Operit 一致的文本。
  Object.keys(browserTools).forEach((name) => {
    const method = browserTools[name];
    browserTools[name] = async (...args) =>
      withToString(await method(...args), (payload) => text(payload && payload.result));
  });

  Object.assign(net, browserTools, unsupportedBrowser);
  /** 宿主用 OkHttp 管连接：open 拿 handle，read 取积压事件，close 释放。 */
  net.webSocket = async (request) => {
    const req = isPlainObject(request) ? request : {};
    const op = text(req.op).trim();
    if (!op) throw pluginError('PARAM_INVALID', 'Tools.Net.webSocket 需要 op');
    const payload = { op };
    if (op === 'open' || op === 'open_binary') {
      payload.url = text(req.url).trim();
      if (!payload.url) throw pluginError('PARAM_INVALID', 'webSocket.open 需要 url');
      payload.connect_timeout_seconds = Number(req.connect_timeout_seconds) || 15;
    } else {
      payload.handle = text(req.handle).trim();
      if (!payload.handle) throw pluginError('PARAM_INVALID', 'webSocket 需要 handle');
      if (op === 'send') payload.text = text(req.text);
      if (op === 'send_binary') payload.base64 = text(req.base64);
    }
    return call('net.websocket', payload);
  };

  // ---------------- System ----------------
  function shellQuote(value) {
    const raw = text(value);
    if (!raw) return "''";
    return "'" + raw.replace(/'/g, "'\\''") + "'";
  }

  /** Operit 的 activity 参数既可能是全名也可能是简名，都归一成宿主认的 pkg/Class。 */
  function componentOf(packageName, activity) {
    const raw = text(activity).trim();
    if (!raw) return undefined;
    if (raw.indexOf('/') >= 0) return raw;
    return packageName + '/' + (raw.indexOf('.') >= 0 ? raw : packageName + '.' + raw);
  }

  async function runShell(command, timeoutSeconds) {
    const payload = compact({ command: text(command), timeout: timeoutSeconds === undefined ? undefined : Number(timeoutSeconds) });
    if (!payload.command) throw pluginError('PARAM_INVALID', '命令不能为空');
    const result = await call('bash', payload);
    return withToString(
      {
        command: payload.command,
        output: text(result && result.output),
        exitCode: Number((result && result.exitCode) || 0),
        truncated: Boolean(result && result.truncated)
      },
      formatTerminalCommand
    );
  }

  const terminalState = new Map();

  async function pollOnce(tabId, offset) {
    return call('terminal.poll', { tabId, offset: offset || 0 });
  }

  /** 轮询到「输出不再变化且状态已结束」，或到达超时。 */
  async function drainTerminal(tabId, offset, timeoutMs, onChunk) {
    const startedAt = Date.now();
    const deadline = startedAt + Math.max(1000, Number(timeoutMs) || 30000);
    let cursor = offset || 0;
    let collected = '';
    let lastState = 'running';
    let exitCode = null;
    let idle = 0;
    let sawOutput = false;
    while (Date.now() < deadline) {
      const poll = await pollOnce(tabId, cursor);
      cursor = Number((poll && poll.offset) || cursor);
      const chunk = text(poll && poll.output);
      if (chunk) {
        collected += chunk;
        sawOutput = true;
        if (typeof onChunk === 'function') onChunk(chunk);
        idle = 0;
      } else {
        idle += 1;
      }
      lastState = text(poll && poll.state) || lastState;
      if (poll && poll.exitCode !== undefined && poll.exitCode !== null) exitCode = Number(poll.exitCode);
      if (lastState !== 'running') break;
      // 交互式 shell 的命令没有退出码：出过输出后静默 3 轮（约 600ms）视为完成
      if (sawOutput && idle >= 3) break;
      // 一直没输出时不能立刻判完，给命令一个起步窗口
      if (!sawOutput && Date.now() - startedAt > 3000) break;
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
    return { output: collected, offset: cursor, state: lastState, exitCode };
  }

  /**
   * Tools.System.music（宿主 music.*）：应用内 ExoPlayer 真播放；宿主侧没音源时遥控系统媒体会话。
   * 返回宿主数据对象（{state, title, positionMs, ...}），失败由 transport 抛 AharouPluginError，
   * 与 Operit 的 toolCall 一致（成功给 result.data，失败 throw）。
   */
  function musicNumber(value) {
    if (value === undefined || value === null || value === '') return null;
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric : null;
  }

  const music = {
    play: async (options) => {
      if (!isPlainObject(options)) {
        throw pluginError('PARAM_INVALID', 'music.play 需要 { source, sourceType, title?, artist?, loop?, volume?, startPositionMs? }');
      }
      const source = text(options.source).trim();
      if (!source) throw pluginError('PARAM_INVALID', 'music.play 需要 source');
      const sourceType = text(pickOption(options, 'sourceType', 'source_type')).trim();
      if (!sourceType) throw pluginError('PARAM_INVALID', 'music.play 需要 sourceType：path / url / uri');
      const params = { source, source_type: sourceType };
      const title = text(options.title).trim();
      if (title) params.title = title;
      const artist = text(options.artist).trim();
      if (artist) params.artist = artist;
      if (options.loop !== undefined) params.loop = Boolean(options.loop);
      const volume = musicNumber(options.volume);
      if (volume !== null) params.volume = volume;
      const start = musicNumber(pickOption(options, 'startPositionMs', 'start_position_ms'));
      if (start !== null) params.start_position_ms = start;
      return call('music.play', params);
    },
    playQueue: async (options) => {
      if (!isPlainObject(options)) throw pluginError('PARAM_INVALID', 'music.playQueue 需要 { items: [...] }');
      if (!Array.isArray(options.items) || !options.items.length) {
        throw pluginError('PARAM_INVALID', 'music.playQueue 需要非空 items 数组');
      }
      const items = options.items.map((item, index) => {
        if (!isPlainObject(item)) throw pluginError('PARAM_INVALID', 'music.playQueue items[' + index + '] 必须是对象');
        const source = text(item.source).trim();
        if (!source) throw pluginError('PARAM_INVALID', 'music.playQueue items[' + index + '] 缺少 source');
        const sourceType = text(pickOption(item, 'sourceType', 'source_type')).trim();
        if (!sourceType) throw pluginError('PARAM_INVALID', 'music.playQueue items[' + index + '] 缺少 sourceType');
        return compact({
          source,
          source_type: sourceType,
          title: text(item.title).trim() || undefined,
          artist: text(item.artist).trim() || undefined
        });
      });
      const params = { items };
      if (options.loop !== undefined) params.loop = Boolean(options.loop);
      const volume = musicNumber(options.volume);
      if (volume !== null) params.volume = volume;
      const startIndex = musicNumber(pickOption(options, 'startIndex', 'start_index'));
      if (startIndex !== null) params.start_index = startIndex;
      const start = musicNumber(pickOption(options, 'startPositionMs', 'start_position_ms'));
      if (start !== null) params.start_position_ms = start;
      return call('music.playQueue', params);
    },
    pause: () => call('music.pause', {}),
    resume: () => call('music.resume', {}),
    stop: () => call('music.stop', {}),
    seek: (positionMs) => {
      const position = musicNumber(positionMs);
      if (position === null || position < 0) {
        throw pluginError('PARAM_INVALID', 'music.seek 需要非负的 positionMs');
      }
      return call('music.seek', { position_ms: position });
    },
    setVolume: (volume) => {
      const value = musicNumber(volume);
      if (value === null || value < 0 || value > 1) {
        throw pluginError('PARAM_INVALID', 'music.setVolume 的 volume 必须在 0~1 之间');
      }
      return call('music.setVolume', { volume: value });
    },
    status: () => call('music.status', {})
  };

  // ---------------- Bluetooth（Operit 的 Tools.System.bluetooth / .ble） ----------------
  /**
   * 真接能力层：每条都落到宿主 `bluetooth.*` / `ble.*`，参数按 Operit 的 snake_case 传，
   * 失败码由宿主给出（DISABLED / PERMISSION_DENIED / SESSION_NOT_FOUND 等 15 个），
   * 插件按 code 分支即可，不靠解析文案。
   */
  function bluetoothNumber(value) {
    if (value === undefined || value === null || value === '') return undefined;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  /** Operit 的 send / writeCharacteristic 都收 { text, dataBase64 }，dataBase64 优先由宿主决定。 */
  function bluetoothPayload(options) {
    const opts = isPlainObject(options) ? options : {};
    return compact({
      text: opts.text === undefined || opts.text === null ? undefined : text(opts.text),
      data_base64: text(pickOption(opts, 'dataBase64', 'data_base64')) || undefined
    });
  }

  function bluetoothReadOptions(options) {
    const opts = isPlainObject(options) ? options : {};
    return compact({
      max_bytes: bluetoothNumber(pickOption(opts, 'maxBytes', 'max_bytes')),
      timeout_ms: bluetoothNumber(pickOption(opts, 'timeoutMs', 'timeout_ms'))
    });
  }

  function requireSessionArg(sessionId, method) {
    const id = text(sessionId).trim();
    if (!id) throw pluginError('PARAM_INVALID', method + ' 需要 sessionId');
    return id;
  }

  const bluetooth = {
    requestPermission: () => call('bluetooth.permission', {}),
    getState: () => call('bluetooth.state', {}),
    requestEnable: () => call('bluetooth.requestEnable', {}),
    listBondedDevices: () => call('bluetooth.bondedDevices', {}),
    scan: (options) => {
      const opts = isPlainObject(options) ? options : {};
      const includeBle = pickOption(opts, 'includeBle', 'include_ble');
      return call('bluetooth.scan', compact({
        duration_ms: bluetoothNumber(pickOption(opts, 'durationMs', 'duration_ms')),
        include_ble: includeBle === undefined ? undefined : Boolean(includeBle)
      }));
    },
    connect: (options) => {
      const opts = isPlainObject(options) ? options : {};
      const address = text(opts.address).trim();
      if (!address) throw pluginError('PARAM_INVALID', 'bluetooth.connect 需要 { address }');
      return call('bluetooth.connect', compact({
        address,
        uuid: text(opts.uuid).trim() || undefined,
        timeout_ms: bluetoothNumber(pickOption(opts, 'timeoutMs', 'timeout_ms'))
      }));
    },
    listen: (options) => {
      const opts = isPlainObject(options) ? options : {};
      return call('bluetooth.listen', compact({
        name: text(opts.name).trim() || undefined,
        uuid: text(opts.uuid).trim() || undefined
      }));
    },
    accept: (listenerSessionId, timeoutMs) => {
      const id = requireSessionArg(listenerSessionId, 'bluetooth.accept');
      return call('bluetooth.accept', compact({
        listener_session_id: id,
        timeout_ms: bluetoothNumber(timeoutMs)
      }));
    },
    send: (sessionId, options) => {
      const id = requireSessionArg(sessionId, 'bluetooth.send');
      return call('bluetooth.send', Object.assign({ session_id: id }, bluetoothPayload(options)));
    },
    read: (sessionId, options) => {
      const id = requireSessionArg(sessionId, 'bluetooth.read');
      return call('bluetooth.read', Object.assign({ session_id: id }, bluetoothReadOptions(options)));
    },
    sendAndRead: (sessionId, options) => {
      const id = requireSessionArg(sessionId, 'bluetooth.sendAndRead');
      return call('bluetooth.sendAndRead', Object.assign(
        { session_id: id },
        bluetoothPayload(options),
        bluetoothReadOptions(options)
      ));
    },
    close: (sessionId) =>
      call('bluetooth.close', { session_id: requireSessionArg(sessionId, 'bluetooth.close') })
  };

  /** Operit 的 BLE 入口嵌在 `Tools.System.bluetooth.ble.*` 下；宿主侧是独立的 `ble.*` 命名空间。 */
  bluetooth.ble = {
    connect: (options) => {
      const opts = isPlainObject(options) ? options : {};
      const address = text(opts.address).trim();
      if (!address) throw pluginError('PARAM_INVALID', 'bluetooth.ble.connect 需要 { address }');
      const autoConnect = pickOption(opts, 'autoConnect', 'auto_connect');
      return call('ble.connect', compact({
        address,
        auto_connect: autoConnect === undefined ? undefined : Boolean(autoConnect),
        timeout_ms: bluetoothNumber(pickOption(opts, 'timeoutMs', 'timeout_ms'))
      }));
    },
    discoverServices: (sessionId, timeoutMs) =>
      call('ble.discoverServices', compact({
        session_id: requireSessionArg(sessionId, 'bluetooth.ble.discoverServices'),
        timeout_ms: bluetoothNumber(timeoutMs)
      })),
    readCharacteristic: (sessionId, options) => {
      const opts = isPlainObject(options) ? options : {};
      return call('ble.readCharacteristic', compact({
        session_id: requireSessionArg(sessionId, 'bluetooth.ble.readCharacteristic'),
        service_uuid: text(pickOption(opts, 'serviceUuid', 'service_uuid')).trim() || undefined,
        characteristic_uuid:
          text(pickOption(opts, 'characteristicUuid', 'characteristic_uuid')).trim() || undefined,
        timeout_ms: bluetoothNumber(pickOption(opts, 'timeoutMs', 'timeout_ms'))
      }));
    },
    writeCharacteristic: (sessionId, options) => {
      const opts = isPlainObject(options) ? options : {};
      return call('ble.writeCharacteristic', Object.assign(
        {
          session_id: requireSessionArg(sessionId, 'bluetooth.ble.writeCharacteristic'),
          service_uuid: text(pickOption(opts, 'serviceUuid', 'service_uuid')).trim() || undefined,
          characteristic_uuid:
            text(pickOption(opts, 'characteristicUuid', 'characteristic_uuid')).trim() || undefined
        },
        bluetoothPayload(opts)
      ));
    },
    writeAndReadCharacteristic: (sessionId, options) => {
      const opts = isPlainObject(options) ? options : {};
      return call('ble.writeAndReadCharacteristic', Object.assign(
        {
          session_id: requireSessionArg(sessionId, 'bluetooth.ble.writeAndReadCharacteristic'),
          write_service_uuid: text(pickOption(opts, 'writeServiceUuid', 'write_service_uuid')).trim() || undefined,
          write_characteristic_uuid:
            text(pickOption(opts, 'writeCharacteristicUuid', 'write_characteristic_uuid')).trim() || undefined,
          read_service_uuid: text(pickOption(opts, 'readServiceUuid', 'read_service_uuid')).trim() || undefined,
          read_characteristic_uuid:
            text(pickOption(opts, 'readCharacteristicUuid', 'read_characteristic_uuid')).trim() || undefined,
          timeout_ms: bluetoothNumber(pickOption(opts, 'timeoutMs', 'timeout_ms'))
        },
        bluetoothPayload(opts)
      ));
    },
    subscribe: (sessionId, options) => {
      const opts = isPlainObject(options) ? options : {};
      const enable = opts.enable;
      return call('ble.subscribe', compact({
        session_id: requireSessionArg(sessionId, 'bluetooth.ble.subscribe'),
        service_uuid: text(pickOption(opts, 'serviceUuid', 'service_uuid')).trim() || undefined,
        characteristic_uuid:
          text(pickOption(opts, 'characteristicUuid', 'characteristic_uuid')).trim() || undefined,
        enable: enable === undefined ? undefined : Boolean(enable),
        timeout_ms: bluetoothNumber(pickOption(opts, 'timeoutMs', 'timeout_ms'))
      }));
    },
    readNotifications: (sessionId, limit) =>
      call('ble.readNotifications', compact({
        session_id: requireSessionArg(sessionId, 'bluetooth.ble.readNotifications'),
        limit: bluetoothNumber(limit)
      }))
  };

  const system = {
    sleep: (milliseconds) => {
      const ms = Math.max(0, Number(milliseconds) || 0);
      // 本地定时器：不需要宿主参与，也不会占用宿主线程
      return new Promise((resolve) =>
        setTimeout(
          () => resolve(withToString({ ok: true, requestedMs: ms, sleptMs: ms }, (result) => 'Slept for ' + result.sleptMs + 'ms')),
          ms
        )
      );
    },

    /** 走宿主 system.settingsGet/set：需要 Shizuku，没授权时由宿主返回 SETTINGS_GET_FAILED 类错误。 */
    getSetting: async (setting, namespace) => {
      const key = text(setting).trim();
      if (!key) throw pluginError('PARAM_INVALID', 'getSetting 需要 setting');
      const ns = text(namespace).trim().toLowerCase() || 'system';
      const result = await call('system.settingsGet', { namespace: ns, key });
      return withToString(
        {
          namespace: text(result && result.namespace) || ns,
          setting: text(result && result.key) || key,
          value: text(result && result.value),
          unset: Boolean(result && result.unset)
        },
        formatSystemSetting
      );
    },
    setSetting: async (setting, value, namespace) => {
      const key = text(setting).trim();
      if (!key) throw pluginError('PARAM_INVALID', 'setSetting 需要 setting');
      const ns = text(namespace).trim().toLowerCase() || 'system';
      const result = await call('system.settingsSet', { namespace: ns, key, value: text(value) });
      return withToString(
        {
          ok: true,
          namespace: text(result && result.namespace) || ns,
          setting: key,
          value: text(value)
        },
        formatSystemSetting
      );
    },

    getDeviceInfo: async () => {
      const info = await call('app.info', {});
      return withToString(
        Object.assign({}, info, {
          sdk: info && info.sdkInt,
          sdkVersion: info && info.sdkInt,
          language: info && info.languageTag
        }),
        (result) =>
          'Host App: ' +
          text(result.packageName) +
          ' ' +
          text(result.versionName) +
          ' (SDK ' +
          text(result.sdkInt) +
          ', locale ' +
          text(result.languageTag) +
          ')'
      );
    },

    /** 宿主 toast 只对页面来源开放；进程侧拿不到就退化成通知。 */
    toast: async (message) => {
      const body = text(message);
      try {
        const result = await call('toast', { message: body });
        return withToString({ ok: true, message: body, result }, (sent) => 'Toast: ' + clip(sent.message, 100));
      } catch (error) {
        if (!isUnknownMethod(error)) throw error;
        await call('notify', { title: 'Aharou', body: body || '（空消息）' });
        return withToString(
          { ok: true, message: body, degraded: 'notification' },
          (sent) => 'Notification: ' + clip(sent.message, 100)
        );
      }
    },

    sendNotification: async (message, title) => {
      const body = text(message);
      const heading = text(title).trim() || 'Aharou';
      await call('notify', { title: heading, body: body || '（空消息）' });
      return withToString(
        { ok: true, title: heading, message: body },
        (sent) => 'Notification [' + text(sent.title) + ']: ' + clip(sent.message, 100)
      );
    },

    usePackage: async () => {
      throw notSupported('插件切换工具包（Tools.System.usePackage）');
    },
    /** 宿主走 `pm install -r`，只接受能映射到宿主文件系统的 APK 路径。 */
    installApp: async (apkPath) => {
      const path = text(apkPath).trim();
      if (!path) throw pluginError('PARAM_INVALID', 'Tools.System.installApp 需要 APK 路径');
      const result = await call('system.installApp', { path });
      return { ok: true, output: text(result && result.output) };
    },
    /** 宿主走 `pm uninstall`，系统会弹确认框，插件无法静默卸载。 */
    uninstallApp: async (packageName) => {
      const pkg = text(packageName).trim();
      if (!pkg) throw pluginError('PARAM_INVALID', 'Tools.System.uninstallApp 需要 packageName');
      const result = await call('system.uninstallApp', { package: pkg });
      return { ok: true, output: text(result && result.output) };
    },
    /** 宿主 app.start：不给 activity 时用 monkey 解析 LAUNCHER。 */
    startApp: async (packageName, activity) => {
      const pkg = text(packageName).trim();
      if (!pkg) throw pluginError('PARAM_INVALID', 'Tools.System.startApp 需要 packageName');
      const component = componentOf(pkg, activity);
      const result = await call('app.start', compact({ package: pkg, component }));
      return withToString(
        {
          ok: true,
          operationType: 'start',
          package: pkg,
          details: 'Successfully started app: ' + pkg + (component ? ' (' + component + ')' : ''),
          output: text(result && result.output)
        },
        formatAppOperation
      );
    },
    /** 宿主 app.stop（am force-stop）；停宿主自己会被宿主直接拒绝。 */
    stopApp: async (packageName) => {
      const pkg = text(packageName).trim();
      if (!pkg) throw pluginError('PARAM_INVALID', 'Tools.System.stopApp 需要 packageName');
      await call('app.stop', { package: pkg });
      return withToString(
        { ok: true, operationType: 'stop', package: pkg, details: 'Successfully stopped app: ' + pkg },
        formatAppOperation
      );
    },
    /** 宿主 app.list：默认只列第三方应用，filter 按包名子串筛选。 */
    listApps: async (options) => {
      let includeSystem = false;
      let filter;
      if (typeof options === 'boolean') {
        includeSystem = options;
      } else if (isPlainObject(options)) {
        const flag = pickOption(
          pickOption(options, 'includeSystem', 'include_system'),
          'includeSystemApps',
          'include_system_apps'
        );
        includeSystem = Boolean(flag);
        filter = text(options.filter).trim() || undefined;
      }
      const result = await call('app.list', compact({ includeSystem, filter }));
      const packages = Array.isArray(result && result.packages) ? result.packages : [];
      return withToString(
        {
          includesSystemApps: includeSystem,
          packages,
          truncated: Boolean(result && result.truncated)
        },
        formatAppList
      );
    },
    /** 宿主 notification.list；未开通知使用权时宿主会报 NOTIFICATION_ACCESS_DENIED。 */
    getNotifications: async (limitOrOptions, includeOngoing) => {
      let limit;
      let packageFilter;
      // v2 默认不返回 ongoing 通知；宿主快照层没有这个参数，在本地按 ongoing 字段过滤
      let ongoing = includeOngoing === true;
      if (isPlainObject(limitOrOptions)) {
        limit = limitOrOptions.limit;
        packageFilter = text(limitOrOptions.package).trim() || undefined;
        const flag = pickOption(limitOrOptions, 'includeOngoing', 'include_ongoing');
        if (flag !== undefined) ongoing = flag === true || flag === 'true';
      } else if (limitOrOptions !== undefined && limitOrOptions !== null) {
        limit = limitOrOptions;
      }
      const result = await call(
        'notification.list',
        compact({
          limit: limit === undefined ? undefined : Math.max(1, Number(limit) || 1),
          package: packageFilter
        })
      );
      const entries = Array.isArray(result && result.notifications) ? result.notifications : [];
      const notifications = entries
        .filter((item) => ongoing || !item.ongoing)
        .map((item) => {
          const postedAt = Number(item.postedAt || 0);
          return {
            key: text(item.key),
            package: text(item.package),
            packageName: text(item.package),
            title: text(item.title),
            text: text(item.text),
            timestamp: postedAt,
            postedAt,
            ongoing: Boolean(item.ongoing),
            canReply: Boolean(item.canReply)
          };
        });
      return withToString({ notifications, timestamp: Date.now() }, formatNotifications);
    },
    /** 宿主 clipboard.read/write 读写的是系统真剪贴板，不是插件进程内的替身。 */
    getClipboardText: async () => {
      const result = await call('clipboard.read', {});
      return withToString(
        { text: text(result && result.text) },
        (read) => 'Clipboard (' + byteLength(read.text) + ' bytes): ' + clip(read.text, 100)
      );
    },
    setClipboardText: async (value) => {
      const content = value === undefined || value === null ? '' : text(value);
      await call('clipboard.write', { text: content });
      return withToString(
        { ok: true, text: content, length: content.length },
        (written) => 'Copied ' + text(written.length) + ' chars to clipboard'
      );
    },
    // 社区插件的几种写法（setClipboard / clipboard.write）都归到同一个宿主方法
    getClipboard: (...args) => system.getClipboardText(...args),
    setClipboard: (...args) => system.setClipboardText(...args),
    clipboard: {
      read: (...args) => system.getClipboardText(...args),
      get: (...args) => system.getClipboardText(...args),
      write: (...args) => system.setClipboardText(...args),
      set: (...args) => system.setClipboardText(...args)
    },
    /** 宿主返回按包名聚合的前台时长；插件侧按 Operit 的字段名重新拼一遍。 */
    getAppUsageTime: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const result = await call('system.usage', { days: Number(opts.days) || 1 });
      const apps = Array.isArray(result && result.apps) ? result.apps : [];
      return apps.map((item) => ({
        packageName: text(item && item.package),
        foregroundTime: Number(item && item.foregroundMs) || 0
      }));
    },
    getLocation: async () => {
      const result = await call('system.location', {});
      return {
        latitude: Number(result && result.latitude) || 0,
        longitude: Number(result && result.longitude) || 0,
        accuracy: Number(result && result.accuracy) || 0,
        provider: text(result && result.provider),
        time: Number(result && result.time) || 0
      };
    },

    shell: (command) => runShell(command),

    terminal: {
      /** 宿主 terminal.run 起的是 PTY 里的命令，`sh -i` 就是可持续喂命令的会话。 */
      create: async (sessionName) => {
        const title = text(sessionName).trim() || 'plugin-shell';
        const command = envValue('AHAROU_TERMINAL_SHELL', 'sh -i');
        const result = await call('terminal.run', { command, title });
        const tabId = text(result && result.tabId);
        if (!tabId) throw pluginError('TERMINAL_FAILED', '宿主没有返回 terminal tabId');
        terminalState.set(tabId, { title, cursor: 0 });
        return withToString(
          {
            session_id: tabId,
            sessionId: tabId,
            sessionName: title,
            tabId,
            title,
            isNewSession: true,
            state: 'running'
          },
          formatTerminalSession
        );
      },
      exec: async (sessionId, command, timeoutMs) => {
        const tabId = text(sessionId);
        if (!tabId) throw pluginError('PARAM_INVALID', 'terminal.exec 需要 sessionId');
        const state = terminalState.get(tabId) || { cursor: 0 };
        const payload = text(command);
        await call('terminal.write', { tabId, text: payload, appendNewline: true });
        const drained = await drainTerminal(tabId, state.cursor, timeoutMs || 30000);
        terminalState.set(tabId, { cursor: drained.offset });
        return withToString(
          {
            command: payload,
            session_id: tabId,
            sessionId: tabId,
            output: drained.output,
            state: drained.state,
            exit_code: drained.exitCode,
            exitCode: drained.exitCode
          },
          formatTerminalCommand
        );
      },
      /** 宿主没有流式通道：用轮询把增量逐段交给回调。 */
      execStreaming: async (sessionId, command, options) => {
        const opts = isPlainObject(options) ? options : {};
        const onChunk = typeof opts.onIntermediateResult === 'function' ? opts.onIntermediateResult : null;
        const tabId = text(sessionId);
        if (!tabId) throw pluginError('PARAM_INVALID', 'terminal.execStreaming 需要 sessionId');
        const state = terminalState.get(tabId) || { cursor: 0 };
        const payload = text(command);
        await call('terminal.write', { tabId, text: payload, appendNewline: true });
        const drained = await drainTerminal(tabId, state.cursor, opts.timeoutMs || 30000, (chunk) => {
          if (onChunk) {
            try {
              onChunk(chunk);
            } catch (error) {
              // 回调异常不影响命令执行
            }
          }
          if (runtime.emit) runtime.emit(chunk);
        });
        terminalState.set(tabId, { cursor: drained.offset });
        return withToString(
          {
            command: payload,
            session_id: tabId,
            sessionId: tabId,
            output: drained.output,
            state: drained.state,
            degraded: 'polling-stream'
          },
          formatTerminalCommand
        );
      },
      hiddenExec: async (command, options) => {
        const opts = isPlainObject(options) ? options : {};
        const timeoutSeconds = opts.timeoutMs ? Math.max(1, Math.ceil(Number(opts.timeoutMs) / 1000)) : 30;
        return runShell(command, timeoutSeconds);
      },
      screen: async (sessionId) => {
        const tabId = text(sessionId);
        const poll = await pollOnce(tabId, 0);
        return withToString(
          { session_id: tabId, sessionId: tabId, content: text(poll && poll.output), output: text(poll && poll.output), state: text(poll && poll.state) },
          (snapshot) =>
            'Terminal Session Screen Snapshot:\nSession: ' +
            text(snapshot.sessionId) +
            '\n\n' +
            text(snapshot.content)
        );
      },
      close: async (sessionId) => {
        const tabId = text(sessionId);
        await call('terminal.close', { tabId });
        terminalState.delete(tabId);
        return withToString(
          { ok: true, session_id: tabId, sessionId: tabId },
          (closed) => 'Terminal session closed: ' + text(closed.sessionId)
        );
      },
      input: async (sessionId, options) => {
        const tabId = text(sessionId);
        const opts = isPlainObject(options) ? options : {};
        const control = text(opts.control);
        const sequence = control ? controlSequence(control) : null;
        const payload = sequence === null ? text(opts.input) : sequence;
        await call('terminal.write', { tabId, text: payload, appendNewline: false });
        return withToString(
          { ok: true, session_id: tabId, sessionId: tabId, sent: payload },
          (result) => 'Sent ' + text(result.sent).length + ' chars to terminal ' + text(result.sessionId)
        );
      }
    },

    music,
    bluetooth,
    // Operit 的 BLE 嵌在 bluetooth.ble 下；顶层再挂一份别名，兼容按 Tools.System.ble 写的调用
    ble: bluetooth.ble,

    /** 宿主 intent.start 走 `am start`，参数形状与插件侧 action/data/package/component 一致。 */
    intent: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const payload = {};
      ['action', 'data', 'package', 'component', 'extras'].forEach((key) => {
        if (opts[key] !== undefined && opts[key] !== null) payload[key] = opts[key];
      });
      if (!Object.keys(payload).length) {
        throw pluginError('PARAM_INVALID', 'Tools.System.intent 需要 action / data / package / component 之一');
      }
      const result = await call('intent.start', payload);
      return { ok: true, output: text(result && result.output) };
    },
    sendBroadcast: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      if (!text(opts.action).trim()) {
        throw pluginError('PARAM_INVALID', 'Tools.System.sendBroadcast 需要 action');
      }
      const result = await call('system.broadcast', {
        action: text(opts.action),
        extras: isPlainObject(opts.extras) ? opts.extras : undefined,
        component: text(opts.component).trim() || undefined
      });
      return { ok: true, output: text(result && result.output) };
    }
  };

  // ---------------- SoftwareSettings ----------------

  /**
   * Operit 的 JS 层把数组/对象序列化成 JSON 串再传（attached_tag_ids、custom_headers…），
   * Aharou 宿主直接收数组/对象。这里把「看着像 JSON 的字符串」解回来，普通字符串原样返回。
   */
  function jsonish(value) {
    if (typeof value !== 'string') return value;
    const trimmed = value.trim();
    if (!trimmed) return undefined;
    if (trimmed[0] !== '[' && trimmed[0] !== '{') return value;
    try {
      return JSON.parse(trimmed);
    } catch (error) {
      return value;
    }
  }

  /** 角色卡入参归一：camelCase / snake_case 都收，统一转成宿主认的 snake_case 键。 */
  function characterCardParams(options) {
    const source = isPlainObject(options) ? options : {};
    const params = {};
    const fields = [
      ['name', 'name'],
      ['description', 'description'],
      ['avatar', 'avatar'],
      ['marks', 'marks'],
      ['characterSetting', 'character_setting'],
      ['openingStatement', 'opening_statement'],
      ['otherContentChat', 'other_content_chat'],
      ['otherContentVoice', 'other_content_voice'],
      ['advancedCustomPrompt', 'advanced_custom_prompt'],
      ['chatModelBindingMode', 'chat_model_binding_mode'],
      ['chatModelConfigId', 'chat_model_config_id'],
      ['memoryProfileBindingMode', 'memory_profile_binding_mode'],
      ['memoryProfileId', 'memory_profile_id']
    ];
    fields.forEach((pair) => {
      const value = pickOption(source, pair[0], pair[1]);
      if (value !== undefined && value !== null) params[pair[1]] = text(value);
    });
    ['tags', 'attachedTagIds', 'attached_tag_ids', 'attached_tags', 'tool_access_config'].forEach((name) => {
      const value = source[name];
      if (value === undefined) return;
      const key = name === 'attachedTagIds' ? 'attached_tag_ids' : name;
      params[key] = jsonish(value);
    });
    const modelIndex = pickOption(source, 'chatModelIndex', 'chat_model_index');
    if (bluetoothNumber(modelIndex) !== undefined) params.chat_model_index = bluetoothNumber(modelIndex);
    return params;
  }

  const softwareSettings = {
    readEnvironmentVariable: async (key) => {
      const name = text(key).trim();
      if (!name) throw pluginError('PARAM_INVALID', 'readEnvironmentVariable 需要 key');
      try {
        const result = await call('env.get', { key: name });
        if (result === null || result === undefined) return { key: name, value: '' };
        if (typeof result === 'object') return { key: name, value: text(result.value) };
        return { key: name, value: text(result) };
      } catch (error) {
        if (!isUnknownMethod(error)) throw error;
        // 宿主没有 env.get 时回落到启动时注入的 env / 进程环境
        return { key: name, value: text(runtime.getEnv(name)), degraded: 'injected-env' };
      }
    },
    writeEnvironmentVariable: async (key, value) => {
      const name = text(key).trim();
      if (!name) throw pluginError('PARAM_INVALID', 'writeEnvironmentVariable 需要 key');
      try {
        await call('env.set', { key: name, value: text(value) });
        return { ok: true, key: name, value: text(value) };
      } catch (error) {
        if (isUnknownMethod(error)) {
          throw notSupported(
            '插件写环境变量（宿主缺 env.set）',
            '让用户在 Aharou 设置的「环境变量」里添加 ' + name
          );
        }
        throw error;
      }
    },

    // ---- 沙盒包：Aharou 没有这套体系，宿主返回 UNSUPPORTED + 原因 ----
    listSandboxPackages: () => call('softwareSettings.listSandboxPackages', {}),
    setSandboxPackageEnabled: (packageName, enabled) =>
      call('softwareSettings.setSandboxPackageEnabled', {
        package_name: text(packageName),
        enabled: Boolean(enabled)
      }),
    executeSandboxScriptDirect: (options) =>
      call('softwareSettings.executeSandboxScriptDirect', isPlainObject(options) ? options : {}),
    restartMcpWithLogs: (timeoutMs) =>
      call('softwareSettings.restartMcpWithLogs', compact({ timeout_ms: bluetoothNumber(timeoutMs) })),

    // ---- 语音配置 ----
    getSpeechServicesConfig: () => call('softwareSettings.getSpeechServicesConfig', {}),
    setSpeechServicesConfig: (updates) => {
      const params = isPlainObject(updates) ? Object.assign({}, updates) : {};
      // 宿主只认自己支持的键；不认识的会进返回里的 ignored 列表，不静默丢掉
      ['tts_headers', 'tts_response_pipeline', 'tts_cleaner_regexs', 'tts_http_config', 'stt_http_config'].forEach(
        (name) => {
          if (isPlainObject(params[name]) || Array.isArray(params[name])) {
            params[name] = JSON.stringify(params[name]);
          }
        }
      );
      return call('softwareSettings.setSpeechServicesConfig', params);
    },
    testTtsPlayback: (message, options) => {
      const body = text(message);
      if (!body) throw pluginError('PARAM_INVALID', 'testTtsPlayback 需要要朗读的文本');
      const opts = isPlainObject(options) ? options : {};
      const interrupt = opts.interrupt;
      return call('softwareSettings.testTtsPlayback', compact({
        text: body,
        interrupt: interrupt === undefined ? undefined : Boolean(interrupt)
      }));
    },

    // ---- 模型配置（映射到真实供应商列表） ----
    listModelConfigs: () => call('softwareSettings.listModelConfigs', {}),
    createModelConfig: (options) => {
      const params = Object.assign({}, isPlainObject(options) ? options : {});
      ['custom_parameters', 'custom_headers'].forEach((name) => {
        if (isPlainObject(params[name])) params[name] = JSON.stringify(params[name]);
      });
      return call('softwareSettings.createModelConfig', params);
    },
    updateModelConfig: (configId, updates) => {
      const id = text(configId).trim();
      if (!id) throw pluginError('PARAM_INVALID', 'updateModelConfig 需要 configId');
      const params = Object.assign({}, isPlainObject(updates) ? updates : {}, { config_id: id });
      ['custom_parameters', 'custom_headers'].forEach((name) => {
        if (isPlainObject(params[name])) params[name] = JSON.stringify(params[name]);
      });
      return call('softwareSettings.updateModelConfig', params);
    },
    deleteModelConfig: (configId) => {
      const id = text(configId).trim();
      if (!id) throw pluginError('PARAM_INVALID', 'deleteModelConfig 需要 configId');
      return call('softwareSettings.deleteModelConfig', { config_id: id });
    },
    listFunctionModelConfigs: () => call('softwareSettings.listFunctionModelConfigs', {}),
    getFunctionModelConfig: (functionType) =>
      call('softwareSettings.getFunctionModelConfig', { function_type: text(functionType) }),
    setFunctionModelConfig: (functionType, configId, modelIndex) =>
      call('softwareSettings.setFunctionModelConfig', compact({
        function_type: text(functionType),
        config_id: text(configId),
        model_index: bluetoothNumber(modelIndex)
      })),
    testModelConfigConnection: (configId, modelIndex) =>
      call('softwareSettings.testModelConfigConnection', compact({
        config_id: text(configId),
        model_index: bluetoothNumber(modelIndex)
      })),

    // ---- 角色卡（Operit 也在这组里放了一份） ----
    listCharacterCards: () => call('characterCard.list', {}),
    getCharacterCard: (characterCardId) =>
      call('characterCard.get', { character_card_id: text(characterCardId) }),
    createCharacterCard: (options) => call('characterCard.create', characterCardParams(options)),
    updateCharacterCard: (characterCardId, updates) =>
      call('characterCard.update', Object.assign(characterCardParams(updates), {
        character_card_id: text(characterCardId)
      })),
    deleteCharacterCard: (characterCardId) =>
      call('characterCard.delete', { character_card_id: text(characterCardId) }),
    setActiveCharacterCard: (characterCardId) =>
      call('characterCard.setActive', { character_card_id: text(characterCardId) }),
    clearActiveCharacterCard: () => call('characterCard.clearActive', {}),
    importCharacterCardFromTavernJson: (tavernJson) =>
      call('characterCard.importTavern', {
        tavern_json: typeof tavernJson === 'string' ? tavernJson : JSON.stringify(tavernJson || {})
      }),
    exportCharacterCardToTavernJson: (characterCardId) =>
      call('characterCard.exportTavern', { character_card_id: text(characterCardId) })
  };

  // ---------------- UI ----------------
  function boundsOf(node) {
    if (!node || typeof node !== 'object') return null;
    const bounds = node.bounds;
    if (isPlainObject(bounds)) {
      const left = Number(bounds.left !== undefined ? bounds.left : bounds.x);
      const top = Number(bounds.top !== undefined ? bounds.top : bounds.y);
      const right = Number(bounds.right !== undefined ? bounds.right : left + Number(bounds.width || 0));
      const bottom = Number(bounds.bottom !== undefined ? bounds.bottom : top + Number(bounds.height || 0));
      if ([left, top, right, bottom].every((value) => Number.isFinite(value))) return { left, top, right, bottom };
    }
    if (typeof bounds === 'string') {
      // Android uiautomator 的 "[l,t][r,b]" 写法
      const match = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(bounds);
      if (match) {
        return { left: Number(match[1]), top: Number(match[2]), right: Number(match[3]), bottom: Number(match[4]) };
      }
    }
    const centerX = Number(node.centerX !== undefined ? node.centerX : node.x);
    const centerY = Number(node.centerY !== undefined ? node.centerY : node.y);
    if (Number.isFinite(centerX) && Number.isFinite(centerY)) {
      return { left: centerX, top: centerY, right: centerX, bottom: centerY };
    }
    return null;
  }

  async function pageNodes() {
    const info = await call('device.pageInfo', {});
    if (Array.isArray(info)) return info;
    if (info && Array.isArray(info.nodes)) return info.nodes;
    if (info && Array.isArray(info.elements)) return info.elements;
    return [];
  }

  function matchNode(node, criteria) {
    if (criteria.resourceId && text(node.resourceId || node.viewIdResourceName) !== criteria.resourceId) return false;
    if (criteria.className && text(node.className || node.type) !== criteria.className) return false;
    if (criteria.text && text(node.text) !== criteria.text) return false;
    if (criteria.bounds) {
      const bounds = boundsOf(node);
      if (!bounds) return false;
      const wanted = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(text(criteria.bounds));
      if (wanted) {
        if (
          bounds.left !== Number(wanted[1]) ||
          bounds.top !== Number(wanted[2]) ||
          bounds.right !== Number(wanted[3]) ||
          bounds.bottom !== Number(wanted[4])
        ) {
          return false;
        }
        return true;
      }
      return text(node.bounds) === text(criteria.bounds);
    }
    return true;
  }

  async function tapElement(criteria) {
    const index = criteria.index === undefined || criteria.index === null ? 0 : Math.max(0, Number(criteria.index) || 0);
    const nodes = await pageNodes();
    const matched = nodes.filter((node) => matchNode(node, criteria));
    const target = matched[index];
    if (!target) {
      throw pluginError(
        'ELEMENT_NOT_FOUND',
        '在 device.pageInfo 里找不到目标元素: ' + stringify(compact(criteria)) + '（共匹配 ' + matched.length + ' 个）'
      );
    }
    const bounds = boundsOf(target);
    if (!bounds) throw pluginError('ELEMENT_INVALID', '目标元素没有可用的 bounds/坐标: ' + stringify(target).slice(0, 300));
    const x = Math.round((bounds.left + bounds.right) / 2);
    const y = Math.round((bounds.top + bounds.bottom) / 2);
    await call('device.tap', { x, y });
    return { ok: true, x, y, element: { text: text(target.text), className: text(target.className || target.type) } };
  }

  /** Operit 的 clickElement 支持 (obj) / (resourceId) / (type,value) / (type,value,index) 四种写法。 */
  function clickElement(param1, param2, param3) {
    if (isPlainObject(param1)) return tapElement(param1);
    if (arguments.length === 1) {
      const single = text(param1);
      if (/^\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]$/.test(single)) return tapElement({ bounds: single });
      return tapElement({ resourceId: single });
    }
    const first = text(param1);
    if (first === 'bounds') return tapElement({ bounds: text(param2), index: param3 });
    if (arguments.length === 2) {
      if (first === 'resourceId') return tapElement({ resourceId: text(param2) });
      if (first === 'className') return tapElement({ className: text(param2) });
      return tapElement({ resourceId: first, index: param2 });
    }
    if (first === 'resourceId') return tapElement({ resourceId: text(param2), index: param3 });
    if (first === 'className') return tapElement({ className: text(param2), index: param3 });
    return tapElement({ resourceId: first, className: text(param2), index: param3 });
  }

  const ui = {
    getPageInfo: () => call('device.pageInfo', {}),
    captureScreenshot: () => call('device.screenshot', { format: 'png' }),
    tap: (x, y) => call('device.tap', { x: Number(x), y: Number(y) }),
    longPress: async (x, y) => {
      try {
        return await call('device.longPress', { x: Number(x), y: Number(y), duration: 800 });
      } catch (error) {
        if (!isUnknownMethod(error)) throw error;
        // 按住一点 800ms 是长按的标准等价写法
        return call('device.swipe', { x: Number(x), y: Number(y), x2: Number(x), y2: Number(y), duration: 800 });
      }
    },
    clickElement,
    setText: async (value, resourceId) => {
      const textValue = text(value);
      if (resourceId) {
        await tapElement({ resourceId: text(resourceId) });
      }
      return call('device.setText', compact({ text: textValue, resourceId: resourceId ? text(resourceId) : undefined }));
    },
    swipe: (startX, startY, endX, endY, duration) =>
      call('device.swipe', compact({
        x: Number(startX),
        y: Number(startY),
        x2: Number(endX),
        y2: Number(endY),
        duration: duration === undefined ? undefined : Number(duration)
      })),
    pressKey: (keyCode) => call('device.key', { keyCode }),
    runSubAgent: async (intent, maxSteps, agentId, targetApp) => {
      const params = compact({
        goal: text(intent),
        maxSteps: maxSteps === undefined ? undefined : Number(maxSteps),
        agentId: agentId ? text(agentId) : undefined,
        targetApp: targetApp ? text(targetApp) : undefined
      });
      if (!params.goal) throw pluginError('PARAM_INVALID', 'runSubAgent 需要 intent');
      try {
        return await call('agent.runSubAgent', params);
      } catch (error) {
        if (isUnknownMethod(error)) {
          throw notSupported(
            '在插件进程里跑界面子代理（宿主缺 agent.runSubAgent）',
            'Tools.UI 的 tap/setText/getPageInfo 自己写操作步骤'
          );
        }
        throw error;
      }
    }
  };

  // ---------------- Chat ----------------
  async function resolveSessionId(chatId) {
    const explicit = text(chatId).trim();
    if (explicit) return explicit;
    const listed = await call('chat.listSessions', { limit: 1, includeSubagents: false });
    const sessions = listed && Array.isArray(listed.sessions) ? listed.sessions : [];
    const first = sessions[0];
    if (!first || !first.id) {
      throw pluginError('NO_SESSION', 'Aharou 当前工作区还没有会话，请先新建会话或显式传入 chatId');
    }
    return text(first.id);
  }

  async function sendAndWait(message, chatId, options) {
    const opts = isPlainObject(options) ? options : {};
    const sessionId = await resolveSessionId(chatId);
    await call('chat.sendMessage', { sessionId, text: text(message) });
    const timeoutMs = opts.timeout_ms ? Number(opts.timeout_ms) : opts.timeoutMs ? Number(opts.timeoutMs) : 120000;
    const waited = await call('chat.waitForRun', { sessionId, timeoutMs });
    const listed = await call('chat.listMessages', { sessionId, limit: 5 });
    const messages = listed && Array.isArray(listed.messages) ? listed.messages : [];
    let reply = null;
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      if (text(messages[index].role) === 'assistant') {
        reply = messages[index];
        break;
      }
    }
    return withToString(
      {
        session_id: sessionId,
        sessionId,
        timedOut: Boolean(waited && waited.timedOut),
        run: (waited && waited.run) || null,
        message: reply ? text(reply.content) : '',
        reply: reply ? { id: reply.id, role: reply.role, content: text(reply.content) } : null
      },
      formatSendAndWait
    );
  }

  /**
   * v1/v2 两种 sendMessage 形态都要认：
   *   位置参数 sendMessage(text, chatId, roleCardId, senderName, options)
   *   对象参数 sendMessage({ chatId, text, kind, runtime, input })
   * 宿主 chat.sendMessage 只收 sessionId + text；角色卡、发送者名、persist_turn/hide_user_message
   * 都是 Operit 聊天层自己的字段，这里记一条 debug 日志后忽略，不抛错。
   */
  function normalizeSendMessageArgs(args) {
    const first = args[0];
    const request = { text: '', chatId: '', options: {}, ignored: [] };
    if (isPlainObject(first)) {
      request.options = first;
      request.chatId = text(pickOption(first, 'chatId', 'chat_id')).trim();
      const input = first.input;
      const fromInput = isPlainObject(input)
        ? pickOption(input, 'text', 'content')
        : typeof input === 'string'
          ? input
          : undefined;
      request.text = text(
        first.text !== undefined ? first.text : first.message !== undefined ? first.message : fromInput
      );
      ['kind', 'runtime', 'input'].forEach((key) => {
        if (first[key] !== undefined) request.ignored.push(key);
      });
      return request;
    }
    request.text = text(first);
    request.chatId = text(args[1]).trim();
    // 第 3 参角色卡、第 4 参发送者名：宿主会话实体没有这两个字段
    if (text(args[2]).trim()) request.ignored.push('roleCardId');
    if (text(args[3]).trim()) request.ignored.push('senderName');
    request.options = isPlainObject(args[4]) ? args[4] : {};
    return request;
  }

  /** 这些选项属于 Operit 自己的聊天层；宿主 chat.sendMessage 只收 sessionId + text，未映射的汇总成一条 debug 日志。 */
  function logIgnoredSendOptions(options, ignored) {
    const keys = [
      'persist_turn',
      'persistTurn',
      'hide_user_message',
      'hideUserMessage',
      'notify_reply',
      'notifyReply',
      'disable_warning',
      'disableWarning',
      'timeout_ms',
      'timeoutMs'
    ];
    keys.forEach((key) => {
      if (options[key] !== undefined && ignored.indexOf(key) < 0) ignored.push(key);
    });
    if (!ignored.length || typeof runtime.hostLog !== 'function') return;
    runtime.hostLog(
      'debug',
      'Tools.Chat.sendMessage 有参数未映射到宿主 chat.sendMessage: ' + ignored.join(', '),
      'operit-shim'
    );
  }

  const MESSAGE_PAGE_SIZE = 100;
  const MAX_MESSAGE_PAGES = 20;

  /**
   * 从新到旧按 before 时间戳锚点翻页，攒到历史头部为止，返回按时间正序的全部消息。
   * 只靠时间戳锚点翻页：同一毫秒的多条消息可能被锚点跳过，这是宿主 chat.listMessages 的边界。
   */
  async function collectAllMessages(sessionId) {
    const pages = [];
    let cursor;
    for (let page = 0; page < MAX_MESSAGE_PAGES; page += 1) {
      const listed = await call(
        'chat.listMessages',
        compact({ sessionId, limit: MESSAGE_PAGE_SIZE, before: cursor })
      );
      const messages = Array.isArray(listed && listed.messages) ? listed.messages : [];
      if (!messages.length) return { messages: [].concat(...pages), complete: true };
      pages.unshift(messages);
      if (!(listed && listed.hasMore)) return { messages: [].concat(...pages), complete: true };
      const anchor = Number(messages[0] && messages[0].timestamp);
      if (!Number.isFinite(anchor) || anchor <= 0) return { messages: [].concat(...pages), complete: true };
      cursor = anchor;
    }
    return { messages: [].concat(...pages), complete: false };
  }

  const chat = {
    /**
     * 宿主没有「聊天浮窗服务」：这里按“确保会话就绪”退化成 chat.createSession，
     * isConnected 表示宿主会话可用。initial_mode 等浮窗参数只对 Operit 的 UI 有意义。
     */
    startService: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const ignored = Object.keys(opts).filter((key) => opts[key] !== undefined);
      if (ignored.length && typeof runtime.hostLog === 'function') {
        runtime.hostLog(
          'debug',
          'Tools.Chat.startService 忽略浮窗参数（宿主无浮窗服务）: ' + ignored.join(', '),
          'operit-shim'
        );
      }
      const created = await call('chat.createSession', {});
      const sessionId = text(created && created.session && created.session.id);
      if (!sessionId) throw pluginError('CHAT_FAILED', '宿主没有返回新建会话 id');
      return withToString(
        { isConnected: true, sessionId, chatId: sessionId, connectionTime: Date.now() },
        (result) =>
          'Chat service started and connected successfully (session ' + text(result.sessionId) + ')'
      );
    },
    /** v2 createNew(group?, setAsCurrentChat?, characterCardId?)：宿主会话没有分组与角色卡字段。 */
    createNew: async (group, setAsCurrentChat, characterCardId) => {
      const ignored = [];
      if (text(group).trim()) ignored.push('group');
      if (text(characterCardId).trim()) ignored.push('characterCardId');
      if (ignored.length && typeof runtime.hostLog === 'function') {
        runtime.hostLog(
          'debug',
          'Tools.Chat.createNew 忽略宿主会话没有的字段: ' + ignored.join(', '),
          'operit-shim'
        );
      }
      const created = await call('chat.createSession', {});
      const session = (created && created.session) || {};
      const chatId = text(session.id);
      if (!chatId) throw pluginError('CHAT_FAILED', '宿主没有返回新建会话 id');
      if (setAsCurrentChat !== false) await call('chat.openSession', { sessionId: chatId });
      return withToString(
        {
          chatId,
          sessionId: chatId,
          title: text(session.title),
          createdAt: Number(session.createdAt || Date.now())
        },
        (result) => 'Created new chat\nChat ID: ' + text(result.chatId)
      );
    },
    listAll: async () =>
      withToString(await call('chat.listSessions', { limit: 100 }), formatChatList),
    listChats: async (params) => {
      const opts = isPlainObject(params) ? params : {};
      const listed = await call(
        'chat.listSessions',
        compact({
          limit: opts.limit === undefined || opts.limit === null ? undefined : Math.max(1, Number(opts.limit)),
          includeSubagents: opts.includeSubagents === undefined ? undefined : Boolean(opts.includeSubagents)
        })
      );
      return withToString(listed, formatChatList);
    },
    /**
     * v2 findChat({ query, match?, index? })：先按会话 id 精确命中，再按标题
     * contains/exact/regex 匹配（非法正则按零匹配处理，不抛）。
     * 找不到时返回 null（v2 会 rejected，这里按插件侧约定给 null）。
     */
    findChat: async (params) => {
      const opts = isPlainObject(params) ? params : { query: params };
      const query = text(opts.query).trim();
      if (!query) throw pluginError('PARAM_INVALID', 'Tools.Chat.findChat 需要 query');
      const match = text(opts.match).trim().toLowerCase() || 'contains';
      const index = opts.index === undefined || opts.index === null ? 0 : Number(opts.index);
      if (!Number.isInteger(index) || index < 0) {
        throw pluginError('PARAM_INVALID', 'Tools.Chat.findChat 的 index 必须是非负整数');
      }
      const listed = await call('chat.listSessions', { limit: 200, includeSubagents: false });
      const sessions = Array.isArray(listed && listed.sessions) ? listed.sessions : [];
      const idHit = sessions.find((item) => text(item.id) === query);
      let matches;
      if (idHit) {
        matches = [idHit];
      } else if (match === 'exact') {
        matches = sessions.filter((item) => text(item.title) === query);
      } else if (match === 'regex') {
        let pattern = null;
        try {
          pattern = new RegExp(query);
        } catch (error) {
          pattern = null;
        }
        matches = pattern ? sessions.filter((item) => pattern.test(text(item.title))) : [];
      } else {
        matches = sessions.filter((item) => text(item.title).indexOf(query) >= 0);
      }
      const picked = matches[index];
      if (!picked) return null;
      return withToString(
        {
          matchedCount: matches.length,
          chat: {
            id: text(picked.id),
            title: text(picked.title),
            createdAt: Number(picked.createdAt || 0),
            updatedAt: Number(picked.updatedAt || 0),
            isPinned: Boolean(picked.isPinned),
            mode: text(picked.mode)
          }
        },
        (result) =>
          'Found chat (' +
          text(result.chat && result.chat.id) +
          ') (matched=' +
          text(result.matchedCount) +
          ')'
      );
    },
    getMessages: async (chatId, options) => {
      const opts = isPlainObject(options) ? options : {};
      const sessionId = await resolveSessionId(chatId);
      const listed = await call(
        'chat.listMessages',
        compact({
          sessionId,
          limit: opts.limit === undefined || opts.limit === null ? undefined : Math.max(1, Number(opts.limit)),
          before: opts.before === undefined ? undefined : Number(opts.before),
          after: opts.after === undefined ? undefined : Number(opts.after)
        })
      );
      return withToString(
        Object.assign({}, listed, { chatId: sessionId, order: 'asc' }),
        formatChatMessages
      );
    },
    /**
     * v2 getMessagesRange(chatId, { order?, start, end }) 是按序号取区间（从 0 起、含两端）；
     * 宿主 chat.listMessages 只支持 limit + before/after 时间戳锚点，所以先用 before 翻页到历史头部，
     * 再按绝对序号切出 start..end。历史超过翻页上限时明确报错，不返回错位的消息。
     */
    getMessagesRange: async (chatId, options) => {
      const opts = isPlainObject(options) ? options : {};
      const start = Number(opts.start);
      const end = Number(opts.end);
      if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start) {
        throw pluginError(
          'PARAM_INVALID',
          'Tools.Chat.getMessagesRange 需要整数 start/end，且 0 <= start <= end'
        );
      }
      const order = text(opts.order).trim().toLowerCase() === 'desc' ? 'desc' : 'asc';
      const sessionId = await resolveSessionId(chatId);
      const before = opts.before === undefined || opts.before === null ? undefined : Number(opts.before);
      const after = opts.after === undefined || opts.after === null ? undefined : Number(opts.after);
      // 给了时间戳锚点时直接交给宿主的 before/after，只按区间长度取条数（序号意义不再适用）
      if (before !== undefined || after !== undefined) {
        const listed = await call(
          'chat.listMessages',
          compact({ sessionId, limit: end - start + 1, before, after })
        );
        return withToString(
          {
            chatId: sessionId,
            sessionId,
            order,
            limit: end - start + 1,
            start,
            end,
            messages: Array.isArray(listed && listed.messages) ? listed.messages : [],
            hasMore: Boolean(listed && listed.hasMore),
            degraded: 'timestamp-anchor'
          },
          formatChatMessages
        );
      }
      const history = await collectAllMessages(sessionId);
      if (!history.complete) {
        throw pluginError(
          'RANGE_TOO_DEEP',
          '会话历史超过 ' +
            MESSAGE_PAGE_SIZE * MAX_MESSAGE_PAGES +
            ' 条，无法按绝对序号取区间；请改用 Tools.Chat.getMessages({ before/after, limit })'
        );
      }
      const slice = history.messages.slice(start, end + 1);
      if (!slice.length && start >= history.messages.length) {
        throw pluginError(
          'RANGE_OUT_OF_BOUNDS',
          'start=' + start + ' 超出会话消息数（共 ' + history.messages.length + ' 条）'
        );
      }
      return withToString(
        {
          chatId: sessionId,
          sessionId,
          order,
          limit: end - start + 1,
          start,
          end,
          messages: order === 'desc' ? slice.slice().reverse() : slice,
          hasMore: end + 1 < history.messages.length
        },
        formatChatMessages
      );
    },
    switchTo: async (chatId) =>
      withToString(
        await call('chat.openSession', { sessionId: await resolveSessionId(chatId) }),
        (result) => 'Switched to chat ' + text(result.sessionId)
      ),
    updateTitle: async (chatId, title) =>
      withToString(
        await call('chat.renameSession', { sessionId: await resolveSessionId(chatId), title: text(title) }),
        (result) => 'Renamed chat ' + text(result.session && result.session.id) + ' to: ' + text(title)
      ),
    deleteChat: async (chatId) =>
      withToString(
        await call('chat.deleteSession', { sessionId: await resolveSessionId(chatId) }),
        (result) => 'Deleted chat ' + text(result.sessionId)
      ),
    agentStatus: async (chatId) => {
      const sessionId = await resolveSessionId(chatId);
      return withToString(await call('chat.getRunState', { sessionId }), formatAgentStatus);
    },
    /** 中断当前会话正在跑的一轮（宿主 chat.stop）。 */
    cancel: async (chatId) => {
      const sessionId = await resolveSessionId(chatId);
      await call('chat.stop', { sessionId });
      return withToString(
        { ok: true, stopped: true, session_id: sessionId, sessionId, chatId: sessionId },
        (result) => 'Stopped chat run: ' + text(result.sessionId)
      );
    },
    /** v2 的 updatePinned(chatId, pinned)；宿主 chat.pinSession 要求显式布尔值。 */
    updatePinned: async (chatId, pinned) => {
      if (pinned === undefined || pinned === null) {
        throw pluginError('PARAM_INVALID', 'Tools.Chat.updatePinned 需要 pinned 布尔值');
      }
      const value = pinned === true || pinned === 1 || pinned === 'true';
      const sessionId = await resolveSessionId(chatId);
      const result = await call('chat.pinSession', { sessionId, pinned: value });
      return withToString(
        { ok: true, session_id: sessionId, sessionId, chatId: sessionId, pinned: value, session: (result && result.session) || null },
        (pinnedResult) =>
          'Chat ' + text(pinnedResult.chatId) + (pinnedResult.pinned ? ' pinned' : ' unpinned')
      );
    },
    /**
     * Operit 的 sendMessage 在宿主侧是「发出去就不等」；两种参数写法都认。
     * 返回同时带 v2 MessageSendResultData 的 chatId/message/sentAt；aiResponse 为 null 表示
     * 本次没有等回复（要等回复用 sendMessageAndWait）。
     */
    sendMessage: async function () {
      const request = normalizeSendMessageArgs(arguments);
      logIgnoredSendOptions(request.options, request.ignored);
      if (!request.text.trim()) throw pluginError('PARAM_INVALID', 'Tools.Chat.sendMessage 需要 message');
      const sessionId = await resolveSessionId(request.chatId);
      await call('chat.sendMessage', { sessionId, text: request.text });
      return withToString(
        {
          ok: true,
          accepted: true,
          session_id: sessionId,
          sessionId,
          chatId: sessionId,
          message: request.text,
          aiResponse: null,
          receivedAt: null,
          sentAt: Date.now(),
          degraded: 'no-wait'
        },
        formatMessageSent
      );
    },
    /** 宿主没有 sendMessageAndWait：发完等 run 结束再取最后一条 assistant 消息。 */
    sendMessageAndWait: sendAndWait,
    /** 宿主没有流式回复通道：退化成「发完再取回复」，最后把整段回复交给回调一次。 */
    sendMessageStreaming: async (message, chatId, roleCardId, senderName, options) => {
      const opts = isPlainObject(options) ? options : isPlainObject(senderName) ? senderName : {};
      const result = await sendAndWait(message, chatId, opts);
      if (typeof opts.onIntermediateResult === 'function' && result.message) {
        try {
          opts.onIntermediateResult(result.message);
        } catch (error) {
          // 回调异常不影响结果
        }
      }
      return withToString(Object.assign({ degraded: 'no-streaming' }, result), formatSendAndWait);
    },
    /**
     * 用为 functionType 配好的模型跑一次对话。
     *
     * 每次调用都会消耗真实额度，插件自己决定何时调；这里不做缓存也不重试。
     */
    call: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const functionType = text(opts.functionType).trim();
      if (!functionType) throw pluginError('PARAM_INVALID', 'Tools.Chat.call 需要 functionType');
      const turns = Array.isArray(opts.turns) ? opts.turns : [];
      if (!turns.length) throw pluginError('PARAM_INVALID', 'Tools.Chat.call 需要 turns');
      const payload = turns.map((turn) => {
        const one = isPlainObject(turn) ? turn : {};
        return { kind: text(one.kind) || 'USER', content: text(one.content) };
      });
      const result = await call('chat.callModel', { functionType, turns: payload });
      return withToString(
        { text: text(result && result.text), functionType },
        (value) => text(value && value.text)
      );
    },
    listCharacterCards: async () =>
      withToString(await call('characterCard.list', {}), formatCharacterCards),
    importCharacterCardFromTavernJson: async (tavernJson) =>
      withToString(
        await call('characterCard.importTavern', {
          tavern_json: typeof tavernJson === 'string' ? tavernJson : JSON.stringify(tavernJson || {})
        }),
        (result) => 'Imported character card: ' + text(result.id || result.cardId || result.name)
      ),
    exportCharacterCardToTavernJson: async (characterCardId) =>
      withToString(
        await call('characterCard.exportTavern', { character_card_id: text(characterCardId) }),
        (result) => 'Exported character card ' + text(characterCardId) + ' (' + byteLength(result.tavern_json || result.tavernJson) + ' bytes)'
      )
  };

  // ---------------- Memory / Workflow / Tasker ----------------
  /**
   * Tools.Memory（宿主 memory.*）：插件自己的记忆图谱，独立于对话记忆存储。
   * 形状对齐 Operit：query/getByTitle 返回 { memories, totalCount }，链接返回 { links, totalCount }。
   */
  function memoryParams(source) {
    const options = isPlainObject(source) ? source : {};
    return {
      folder_path: text(pickOption(options, 'folderPath', 'folder_path')).trim() || undefined,
      start_time:
        options.startTime === undefined || options.startTime === null ? undefined : String(options.startTime),
      end_time:
        options.endTime === undefined || options.endTime === null ? undefined : String(options.endTime),
      limit: typeof options.limit === 'number' ? options.limit : undefined,
      threshold: typeof options.threshold === 'number' ? options.threshold : undefined
    };
  }

  const memory = {
    query: (query, folderPath, limit, startTime, endTime, snapshotId, threshold, callerCardId) => {
      const options = isPlainObject(query)
        ? query
        : { query, folderPath, limit, startTime, endTime, snapshotId, threshold, callerCardId };
      const keyword = text(options.query).trim();
      if (!keyword) {
        throw pluginError('PARAM_INVALID', 'Tools.Memory.query 需要 query（要全量就用 "*"）');
      }
      const params = memoryParams(options);
      params.query = keyword;
      const snapshot = text(pickOption(options, 'snapshotId', 'snapshot_id')).trim();
      if (snapshot) params.snapshot_id = snapshot;
      return call('memory.query', compact(params));
    },
    getByTitle: (title, chunkIndex, chunkRange, query, limit, callerCardId) => {
      const options = isPlainObject(title)
        ? title
        : { title, chunkIndex, chunkRange, query, limit, callerCardId };
      const name = text(options.title).trim();
      if (!name) throw pluginError('PARAM_INVALID', 'Tools.Memory.getByTitle 需要 title');
      return call('memory.getByTitle', compact({
        title: name,
        chunk_index: pickOption(options, 'chunkIndex', 'chunk_index'),
        chunk_range: pickOption(options, 'chunkRange', 'chunk_range'),
        query: text(options.query).trim() || undefined
      }));
    },
    create: (title, content, contentType, source, folderPath, tags, callerCardId) => {
      const options = isPlainObject(title)
        ? title
        : { title, content, contentType, source, folderPath, tags, callerCardId };
      const name = text(options.title).trim();
      const body = text(options.content);
      if (!name) throw pluginError('PARAM_INVALID', 'Tools.Memory.create 需要 title');
      if (!body) throw pluginError('PARAM_INVALID', 'Tools.Memory.create 需要 content');
      return call('memory.create', compact({
        title: name,
        content: body,
        content_type: text(pickOption(options, 'contentType', 'content_type')).trim() || undefined,
        source: text(options.source).trim() || undefined,
        folder_path: text(pickOption(options, 'folderPath', 'folder_path')).trim() || undefined,
        tags: options.tags === undefined || options.tags === null ? undefined : options.tags
      }));
    },
    update: (oldTitle, updates, callerCardId) => {
      const options = isPlainObject(oldTitle)
        ? oldTitle
        : Object.assign({ oldTitle }, isPlainObject(updates) ? updates : {}, { callerCardId });
      const name = text(options.oldTitle).trim();
      if (!name) throw pluginError('PARAM_INVALID', 'Tools.Memory.update 需要 oldTitle');
      return call('memory.update', compact({
        old_title: name,
        new_title: text(pickOption(options, 'newTitle', 'new_title')).trim() || undefined,
        content: options.content === undefined ? undefined : text(options.content),
        content_type: text(pickOption(options, 'contentType', 'content_type')).trim() || undefined,
        source: text(options.source).trim() || undefined,
        credibility: typeof options.credibility === 'number' ? options.credibility : undefined,
        importance: typeof options.importance === 'number' ? options.importance : undefined,
        folder_path: pickOption(options, 'folderPath', 'folder_path'),
        tags: options.tags === undefined || options.tags === null ? undefined : options.tags
      }));
    },
    deleteMemory: (title, callerCardId) => {
      const options = isPlainObject(title) ? title : { title, callerCardId };
      const name = text(options.title).trim();
      if (!name) throw pluginError('PARAM_INVALID', 'Tools.Memory.deleteMemory 需要 title');
      return call('memory.deleteMemory', { title: name });
    },
    move: (targetFolderPath, titles, sourceFolderPath, callerCardId) => {
      const options = isPlainObject(targetFolderPath)
        ? targetFolderPath
        : { targetFolderPath, titles, sourceFolderPath, callerCardId };
      const target = pickOption(options, 'targetFolderPath', 'target_folder_path');
      if (target === undefined || target === null) {
        throw pluginError('PARAM_INVALID', 'Tools.Memory.move 需要 targetFolderPath（移到未分类就传空串）');
      }
      const source = pickOption(options, 'sourceFolderPath', 'source_folder_path');
      return call('memory.move', compact({
        target_folder_path: text(target),
        titles: Array.isArray(options.titles) ? options.titles : options.titles ? [options.titles] : undefined,
        source_folder_path: source === undefined || source === null ? undefined : text(source)
      }));
    },
    link: (sourceTitle, targetTitle, linkType, weight, description, callerCardId) => {
      const options = isPlainObject(sourceTitle)
        ? sourceTitle
        : { sourceTitle, targetTitle, linkType, weight, description, callerCardId };
      const from = text(pickOption(options, 'sourceTitle', 'source_title')).trim();
      const to = text(pickOption(options, 'targetTitle', 'target_title')).trim();
      if (!from || !to) throw pluginError('PARAM_INVALID', 'Tools.Memory.link 需要 sourceTitle 与 targetTitle');
      return call('memory.link', compact({
        source_title: from,
        target_title: to,
        link_type: text(pickOption(options, 'linkType', 'link_type')).trim() || undefined,
        weight: typeof options.weight === 'number' ? options.weight : undefined,
        description: text(options.description)
      }));
    },
    queryLinks: (linkId, sourceTitle, targetTitle, linkType, limit, callerCardId) => {
      const options = isPlainObject(linkId)
        ? linkId
        : { linkId, sourceTitle, targetTitle, linkType, limit, callerCardId };
      const id = pickOption(options, 'linkId', 'link_id');
      return call('memory.queryLinks', compact({
        link_id: id === undefined || id === null ? undefined : String(id),
        source_title: text(pickOption(options, 'sourceTitle', 'source_title')).trim() || undefined,
        target_title: text(pickOption(options, 'targetTitle', 'target_title')).trim() || undefined,
        link_type: text(pickOption(options, 'linkType', 'link_type')).trim() || undefined,
        limit: typeof options.limit === 'number' ? options.limit : undefined
      }));
    },
    updateLink: (linkId, sourceTitle, targetTitle, linkType, newLinkType, weight, description, callerCardId) => {
      const options = isPlainObject(linkId)
        ? linkId
        : { linkId, sourceTitle, targetTitle, linkType, newLinkType, weight, description, callerCardId };
      const id = pickOption(options, 'linkId', 'link_id');
      return call('memory.updateLink', compact({
        link_id: id === undefined || id === null ? undefined : String(id),
        source_title: text(pickOption(options, 'sourceTitle', 'source_title')).trim() || undefined,
        target_title: text(pickOption(options, 'targetTitle', 'target_title')).trim() || undefined,
        link_type: text(pickOption(options, 'linkType', 'link_type')).trim() || undefined,
        new_link_type: text(pickOption(options, 'newLinkType', 'new_link_type')).trim() || undefined,
        weight: typeof options.weight === 'number' ? options.weight : undefined,
        description: text(options.description)
      }));
    },
    deleteLink: (linkId, sourceTitle, targetTitle, linkType, callerCardId) => {
      const options = isPlainObject(linkId)
        ? linkId
        : { linkId, sourceTitle, targetTitle, linkType, callerCardId };
      const id = pickOption(options, 'linkId', 'link_id');
      return call('memory.deleteLink', compact({
        link_id: id === undefined || id === null ? undefined : String(id),
        source_title: text(pickOption(options, 'sourceTitle', 'source_title')).trim() || undefined,
        target_title: text(pickOption(options, 'targetTitle', 'target_title')).trim() || undefined,
        link_type: text(pickOption(options, 'linkType', 'link_type')).trim() || undefined
      }));
    }
  };
  // 旧版本 shim 用 delete / moveBatch 这两个名字声明过（Operit 原生是 deleteMemory / move），留着当别名
  memory.delete = memory.deleteMemory;
  memory.moveBatch = memory.move;

  /**
   * Operit 的 Tools.Workflow.*：宿主侧对应 host.workflow.*，具体解析与执行在 Aharou 工作流引擎里。
   * 模板导入后的工作流默认禁用，启用后会按触发节点的 interval / cron 排调度。
   */
  const workflow = {
    getAll: () => call('workflow.getAll', {}),
    create: (name, description, nodes, connections, enabled) =>
      call('workflow.create', compact({
        name: text(name),
        description: description === undefined || description === null ? undefined : text(description),
        nodes: nodes === undefined || nodes === null ? undefined : nodes,
        connections: connections === undefined || connections === null ? undefined : connections,
        enabled: enabled === undefined ? undefined : Boolean(enabled)
      })),
    get: (workflowId) => call('workflow.get', { workflow_id: text(workflowId) }),
    update: (workflowId, updates) => {
      const patch = isPlainObject(updates) ? updates : {};
      return call('workflow.update', compact({
        workflow_id: text(workflowId),
        name: patch.name === undefined ? undefined : text(patch.name),
        description: patch.description === undefined ? undefined : text(patch.description),
        nodes: patch.nodes === undefined ? undefined : patch.nodes,
        connections: patch.connections === undefined ? undefined : patch.connections,
        enabled: patch.enabled === undefined ? undefined : Boolean(patch.enabled)
      }));
    },
    patch: () =>
      Promise.reject(
        notSupported(
          '增量更新工作流（Tools.Workflow.patch）',
          'Tools.Workflow.get 取回节点与连接，改完再调 Tools.Workflow.update'
        )
      ),
    setEnabled: (workflowId, enabled) =>
      call('workflow.setEnabled', { workflow_id: text(workflowId), enabled: Boolean(enabled) }),
    enable: (workflowId) => call('workflow.setEnabled', { workflow_id: text(workflowId), enabled: true }),
    disable: (workflowId) => call('workflow.setEnabled', { workflow_id: text(workflowId), enabled: false }),
    delete: (workflowId) => call('workflow.delete', { workflow_id: text(workflowId) }),
    trigger: (workflowId) => call('workflow.trigger', { workflow_id: text(workflowId) })
  };

  const tasker = {
    /** 宿主会返回 UNSUPPORTED 并把可用替代方案写进 message；不在这里伪造成功。 */
    triggerEvent: (params) => call('tasker.triggerEvent', isPlainObject(params) ? params : {})
  };

  // ---------------- FFmpeg ----------------
  function ffmpegArgs(options) {
    if (options === undefined || options === null) return [];
    if (typeof options === 'string') return options.trim() ? [options.trim()] : [];
    if (Array.isArray(options)) return options.map((item) => shellQuote(item));
    if (isPlainObject(options) && Array.isArray(options.args)) return options.args.map((item) => shellQuote(item));
    if (isPlainObject(options) && typeof options.args === 'string') return [options.args];
    throw pluginError('PARAM_INVALID', 'FFmpeg.convert 的 options 只接受字符串、数组或 { args }');
  }

  const ffmpeg = {
    execute: (command) => runShell(command, 600),
    info: () => runShell('ffmpeg -version', 30),
    convert: (inputPath, outputPath, options) => {
      const input = shellQuote(inputPath);
      const output = shellQuote(outputPath);
      const extra = ffmpegArgs(options);
      const command = ['ffmpeg', '-y', '-i', input].concat(extra).concat([output]).join(' ');
      return runShell(command, 1800);
    }
  };

  // ---------------- Storage ----------------
  /**
   * Operit 的 Tools.Storage 是 SQLite 对象库 + 偏好文件两套东西。
   *
   * 插件进程里没有 SQLite 引擎，这里用 JSON 文件承载同样的 API 形状：
   * collection 的增删查改、dataStore 的快照与编辑都能用，
   * 真正需要 SQL 的 query 明确报不支持，不返回空结果骗调用方。
   */
  function storageFilePath(options) {
    const opts = isPlainObject(options) ? options : {};
    const raw = text(opts.path).trim();
    if (!raw) throw pluginError('PARAM_INVALID', 'Storage 需要 path');
    return path.isAbsolute(raw) ? raw : path.resolve(runtime.dataDir || process.cwd(), raw);
  }

  function readJsonStore(file) {
    try {
      if (!fs.existsSync(file)) return {};
      const raw = fs.readFileSync(file, 'utf8');
      if (!raw.trim()) return {};
      const parsed = JSON.parse(raw);
      return isPlainObject(parsed) ? parsed : {};
    } catch (error) {
      // 文件坏了不能连累整个插件：当成空库，写入时覆盖
      hostLog('warn', 'Storage 读取失败，按空库处理: ' + file, 'operit-shim');
      return {};
    }
  }

  function writeJsonStore(file, data) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(data));
  }

  function createCollection(store, save, name) {
    const collectionName = text(name).trim();
    function bucket() {
      if (!isPlainObject(store.collections)) store.collections = {};
      if (!isPlainObject(store.collections[collectionName])) store.collections[collectionName] = {};
      return store.collections[collectionName];
    }
    return {
      get: async (key) => {
        const k = text(key);
        const bucketData = bucket();
        return Object.prototype.hasOwnProperty.call(bucketData, k) ? { key: k, value: bucketData[k] } : null;
      },
      getMany: async (keys) => {
        const bucketData = bucket();
        return (Array.isArray(keys) ? keys : []).map((key) => {
          const k = text(key);
          return Object.prototype.hasOwnProperty.call(bucketData, k) ? { key: k, value: bucketData[k] } : null;
        });
      },
      list: async (after, limit) => {
        const bucketData = bucket();
        const keys = Object.keys(bucketData).sort();
        const start = after ? keys.indexOf(text(after)) + 1 : 0;
        const size = Number.isFinite(Number(limit)) && Number(limit) > 0 ? Number(limit) : keys.length;
        return keys.slice(start, start + size).map((k) => ({ key: k, value: bucketData[k] }));
      },
      put: async (key, value) => {
        bucket()[text(key)] = value;
        save();
        return { key: text(key) };
      },
      remove: async (key) => {
        delete bucket()[text(key)];
        save();
        return { key: text(key) };
      },
      // watch 需要宿主推送变更；插件进程内没有订阅总线，给出一个立刻结束的迭代器
      watch: async function* (key) {
        const hit = await this.get(key);
        yield hit;
      }
    };
  }

  /** node:sqlite 存在就用真库；老 node 上退回 JSON，功能会退化但不会崩。 */
  function loadSqlite() {
    try {
      return require('node:sqlite');
    } catch (error) {
      return null;
    }
  }

  /**
   * Operit 的对象库最终都落在一张 (collection, key, value, version) 表上，
   * 插件查询走的是宿主建的 `plugin_objects` 视图。这里照同样形状建表并暴露视图，
   * 插件写的 SQL（含 json_extract / json_each）才能直接跑。
   */
  function ensureObjectStore(db) {
    db.exec('CREATE TABLE IF NOT EXISTS plugin_objects_data (collection TEXT NOT NULL, key TEXT NOT NULL, value TEXT, version INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (collection, key))');
    db.exec('CREATE VIEW IF NOT EXISTS plugin_objects AS SELECT collection, key, value, version FROM plugin_objects_data');
  }

  function createSqliteCollection(db, name) {
    const collectionName = text(name).trim();
    const read = (key) => {
      ensureObjectStore(db);
      const row = db.prepare('SELECT value, version FROM plugin_objects_data WHERE collection = ? AND key = ?')
        .get(collectionName, text(key));
      if (!row) return null;
      return { key: text(key), value: JSON.parse(row.value), version: row.version };
    };
    return {
      get: async (key) => read(key),
      getMany: async (keys) => (Array.isArray(keys) ? keys : []).map((key) => read(key)),
      list: async (after, limit) => {
        ensureObjectStore(db);
        const size = Number.isFinite(Number(limit)) && Number(limit) > 0 ? Number(limit) : 1000;
        const rows = after
          ? db.prepare('SELECT key, value, version FROM plugin_objects_data WHERE collection = ? AND key > ? ORDER BY key LIMIT ?').all(collectionName, text(after), size)
          : db.prepare('SELECT key, value, version FROM plugin_objects_data WHERE collection = ? ORDER BY key LIMIT ?').all(collectionName, size);
        return rows.map((row) => ({ key: row.key, value: JSON.parse(row.value), version: row.version }));
      },
      put: async (key, value) => {
        ensureObjectStore(db);
        db.prepare('INSERT INTO plugin_objects_data (collection, key, value, version) VALUES (?, ?, ?, 1) ' +
          'ON CONFLICT(collection, key) DO UPDATE SET value = excluded.value, version = plugin_objects_data.version + 1')
          .run(collectionName, text(key), JSON.stringify(value === undefined ? null : value));
        return { key: text(key) };
      },
      remove: async (key) => {
        ensureObjectStore(db);
        db.prepare('DELETE FROM plugin_objects_data WHERE collection = ? AND key = ?').run(collectionName, text(key));
        return { key: text(key) };
      },
      watch: async function* (key) { yield read(key); }
    };
  }

  function openObjectDatabase(options) {
    const file = storageFilePath(options);
    const sqlite = loadSqlite();
    if (sqlite) {
      const db = new sqlite.DatabaseSync(file);
      ensureObjectStore(db);
      return {
        path: file,
        close: async () => { try { db.close(); } catch (error) { /* 已关闭 */ } },
        changes: async () => ({ items: [], next: null }),
        query: async (sql, params) => {
          const statement = db.prepare(text(sql));
          const args = Array.isArray(params) ? params : [];
          return statement.columns().length > 0 ? statement.all(...args) : (statement.run(...args), []);
        },
        collection: (name) => createSqliteCollection(db, name),
        commit: async (mutations) => {
          const list = Array.isArray(mutations) ? mutations : [];
          list.forEach((item) => {
            const one = isPlainObject(item) ? item : {};
            const target = createSqliteCollection(db, one.collection);
            if (one.type === 'put' || one.op === 'put') target.put(one.key, one.value);
            else if (one.type === 'delete' || one.op === 'remove') target.remove(one.key);
          });
          return { count: list.length };
        }
      };
    }
    const store = readJsonStore(file);
    const save = () => writeJsonStore(file, store);
    return {
      path: file,
      close: async () => {},
      changes: async () => ({ items: [], next: null }),
      query: async () => {
        throw notSupported('Tools.Storage.objects.query（当前运行时没有 SQLite）', '升级容器内 Node 后可用');
      },
      collection: (name) => createCollection(store, save, name),
      commit: async (mutations) => {
        const list = Array.isArray(mutations) ? mutations : [];
        list.forEach((item) => {
          const one = isPlainObject(item) ? item : {};
          const target = createCollection(store, () => {}, one.collection);
          if (one.type === 'put' || one.op === 'put') target.put(one.key, one.value);
          else if (one.type === 'delete' || one.op === 'remove') target.remove(one.key);
        });
        save();
        return { count: list.length };
      }
    };
  }

  function openDataStore(options) {
    const file = storageFilePath(options);
    let store = readJsonStore(file);
    const save = () => writeJsonStore(file, store);
    const hasKey = (key) => Object.prototype.hasOwnProperty.call(store, text(key));
    const snapshot = () => ({
      get: (key) => store[text(key)],
      contains: hasKey,
      // 旧写法里有 has 的用法，保留别名
      has: hasKey,
      entries: () => Object.entries(store),
      toObject: () => ({ ...store }),
      toJSON: () => ({ ...store })
    });
    const api = {
      path: file,
      close: async () => {},
      data: async () => snapshot(),
      edit: async (transform) => {
        if (typeof transform !== 'function') throw pluginError('PARAM_INVALID', 'DataStore.edit 需要回调');
        const draft = { ...store };
        const result = transform({
          get: (key) => draft[text(key)],
          contains: (key) => Object.prototype.hasOwnProperty.call(draft, text(key)),
          entries: () => Object.entries(draft),
          set: (key, value) => { draft[text(key)] = value; },
          remove: (key) => { delete draft[text(key)]; },
          incrementInteger: (key, delta) => {
            const current = Number(draft[text(key)]) || 0;
            draft[text(key)] = current + (Number(delta) || 0);
          },
          setObjectField: (key, field, value) => {
            const target = isPlainObject(draft[text(key)]) ? { ...draft[text(key)] } : {};
            target[text(field)] = value;
            draft[text(key)] = target;
          },
          toObject: () => ({ ...draft })
        });
        const next = result && typeof result === 'object' && !Array.isArray(result) ? result : draft;
        store = { ...next };
        save();
        return snapshot();
      },
      withSchema: () => api,
      schemaVersion: async () => Number(store.__schemaVersion) || 0,
      dataFlow: async function* () { yield snapshot(); }
    };
    return api;
  }

  const storage = {
    objects: { open: async (options) => openObjectDatabase(options) },
    dataStore: { open: async (options) => openDataStore(options) },
    sqlite: {
      open: async () => {
        throw notSupported('Tools.Storage.sqlite（SQLite 引擎）', '改用 collection 的 get/list/put/remove');
      }
    },
    request: async () => {
      throw notSupported('Tools.Storage.request（SQLite 线协议）');
    }
  };


  // ---------------- 契约里存在但宿主没有的能力 ----------------
  /**
   * 这些方法在 v2 契约里是标准成员，插件会直接调用。
   * 缺了会让插件在 `xxx.yyy is not a function` 处整段崩掉；
   * 补成「明确报不支持」，插件自己能 catch 并降级，界面其余部分照常渲染。
   */
  const unsupportedChat = {};
  [
    'cancelScheduledMessage', 'listScheduledMessages', 'scheduleMessage',
    'promoteToRoot', 'readExtension', 'reorder', 'updateGroup', 'updateLocked'
  ].forEach((name) => {
    unsupportedChat[name] = () => Promise.reject(
      notSupported('Tools.Chat.' + name + '（会话调度/分组/排序）', 'Aharou 只支持会话本身的增删改查与发消息')
    );
  });

  /** 窗口枚举/聚焦接到无障碍服务；未开启时宿主返回 PERMISSION_REQUIRED，插件自己降级。 */
  const unsupportedUi = {
    listWindows: async () => {
      const result = await call('ui.windows', {});
      const windows = Array.isArray(result && result.windows) ? result.windows : [];
      return {
        windows: windows.map((item) => ({
          windowId: Number(item && item.windowId) || 0,
          displayId: Number(item && item.displayId) || 0,
          type: text(item && item.type),
          title: text(item && item.title),
          focused: item && item.focused === true,
          active: item && item.active === true,
          packageName: text(item && item.rootPackage)
        }))
      };
    },
    focusWindow: async (windowId, ensureVisible) => {
      const id = Number(windowId);
      const result = await call('ui.focusWindow', Number.isFinite(id) ? { windowId: id } : {});
      return { ok: result && result.ok === true, packageName: text(result && result.package) };
    }
  };

  /** 宿主用内置系统 TTS 念；没有任何外部转发依赖。 */
  const ttsTools = {
    speak: async (textOrOptions) => {
      const text = typeof textOrOptions === 'string'
        ? textOrOptions
        : text((textOrOptions && textOrOptions.text) || '');
      if (!text.trim()) throw pluginError('PARAM_INVALID', 'Tools.Tts.speak 需要文本');
      const result = await call('tts.speak', { text });
      return { ok: result && result.ok === true };
    },
    cancel: async () => {
      // 宿主没有单独的取消通道：speak 是等待式的，新的一段会顶掉旧的
      return true;
    },
    listConfigs: async () => ({ configs: [] })
  };

  /** 只支持 WAV(PCM 16-bit)；识别走用户自己配的云端模型。 */
  const sttTools = {
    transcribe: async (request) => {
      const req = isPlainObject(request) ? request : {};
      const audio = Array.isArray(req.audio) ? req.audio : null;
      if (!audio || !audio.length) throw pluginError('PARAM_INVALID', 'Tools.Stt.transcribe 需要 audio 字节数组');
      const result = await call('stt.transcribe', { audio });
      return withToString({ text: text(result && result.text) }, (v) => text(v && v.text));
    }
  };

  /** 登记进系统媒体库：宿主用 MediaScanner，异步完成但会等一小会儿拿 URI。 */
  const mediaTools = {
    registerFromPath: async (options) => {
      const opts = isPlainObject(options) ? options : {};
      const targetPath = text(opts.path).trim();
      if (!targetPath) throw pluginError('PARAM_INVALID', 'Tools.Media.registerFromPath 需要 path');
      const result = await call('media.register', { path: targetPath, type: text(opts.type) || undefined });
      return { id: text(result && result.id), path: targetPath };
    }
  };

  const modelsTools = {
    /** 宿主有模型列表：转成插件认识的摘要形状。 */
    listSummaries: async () => {
      const payload = await call('model.listModels', {});
      const list = Array.isArray(payload) ? payload : (payload && Array.isArray(payload.models) ? payload.models : []);
      return list.map((item) => {
        const one = isPlainObject(item) ? item : {};
        return {
          id: text(one.id || one.name),
          name: text(one.name || one.id),
          provider: text(one.provider || one.providerId),
          description: text(one.description)
        };
      });
    }
  };

  /** 宿主如实报四类真实来源；插件拿去展示或去重。 */
  const catalogTools = {
    readToolSources: async () => {
      const result = await call('catalog.toolSources', {});
      const pick = (key) => (Array.isArray(result && result[key]) ? result[key] : []).map((item) => ({
        name: text(item && item.name),
        displayName: text(item && item.displayName),
        description: text(item && item.description)
      }));
      return {
        builtinTools: pick('builtinTools'),
        packages: pick('packages'),
        skills: pick('skills'),
        mcpServers: pick('mcpServers')
      };
    }
  };

  // ---------------- 组装 ----------------
  tools.Files = files;
  tools.Net = net;
  tools.System = system;
  // Operit 只有 Tools.System.terminal；同时认顶层 Tools.Terminal.* 与 Tools.System.exec
  tools.Terminal = system.terminal;
  system.exec = system.terminal.exec;
  // Operit 把音乐挂在 Tools.System.music 下；顶层再挂一份别名，兼容按 Tools.music 写的调用
  tools.music = music;
  tools.SoftwareSettings = softwareSettings;
  // 插件用 exec 跑沙箱脚本；宿主对应入口是 executeSandboxScriptDirect
  if (typeof softwareSettings.exec !== 'function') {
    softwareSettings.exec = (script, options) => call('softwareSettings.executeSandboxScriptDirect', compact({
      script: text(script),
      ...(isPlainObject(options) ? options : {})
    }));
  }
  tools.UI = ui;
  tools.Chat = chat;
  tools.Memory = memory;
  tools.Workflow = workflow;
  tools.Tasker = tasker;
  tools.calc = async (expression) => evaluateArithmetic(expression);
  tools.FFmpeg = ffmpeg;
  tools.Storage = storage;
  Object.assign(chat, unsupportedChat);
  Object.assign(ui, unsupportedUi);
  tools.Tts = ttsTools;
  tools.Stt = sttTools;
  tools.Media = mediaTools;
  tools.Models = modelsTools;
  tools.Catalog = catalogTools;
}

function controlSequence(control) {
  const name = text(control).trim().toUpperCase();
  const table = {
    'C-C': '\u0003',
    CTRL_C: '\u0003',
    'C-D': '\u0004',
    CTRL_D: '\u0004',
    'C-Z': '\u001a',
    CTRL_Z: '\u001a',
    'C-L': '\u000c',
    CTRL_L: '\u000c',
    ENTER: '\r',
    RETURN: '\r',
    TAB: '\t',
    ESC: '\u001b',
    ESCAPE: '\u001b',
    UP: '\u001b[A',
    DOWN: '\u001b[B',
    RIGHT: '\u001b[C',
    LEFT: '\u001b[D',
    BACKSPACE: '\u007f'
  };
  if (!Object.prototype.hasOwnProperty.call(table, name)) {
    throw pluginError('PARAM_INVALID', 'terminal.input 不认识的控制符: ' + control);
  }
  return table[name];
}

// ---------------------------------------------------------------------------
// 内置库兜底：lodash / uuid / axios
// ---------------------------------------------------------------------------

function createLodashShim() {
  const lodash = {
    isArray: Array.isArray,
    isObject: (value) => value !== null && typeof value === 'object',
    isPlainObject,
    isString: (value) => typeof value === 'string',
    isNumber: (value) => typeof value === 'number' && !Number.isNaN(value),
    isBoolean: (value) => typeof value === 'boolean',
    isFunction: (value) => typeof value === 'function',
    isNil: (value) => value === null || value === undefined,
    isEmpty: (value) => {
      if (value === null || value === undefined) return true;
      if (typeof value === 'string' || Array.isArray(value)) return value.length === 0;
      if (typeof value === 'object') return Object.keys(value).length === 0;
      return true;
    },
    get: (object, keyPath, defaultValue) => {
      if (object === null || object === undefined) return defaultValue;
      const parts = Array.isArray(keyPath) ? keyPath : text(keyPath).split('.').filter((part) => part !== '');
      let current = object;
      for (let index = 0; index < parts.length; index += 1) {
        if (current === null || current === undefined) return defaultValue;
        current = current[parts[index]];
      }
      return current === undefined ? defaultValue : current;
    },
    keys: (value) => (value === null || value === undefined ? [] : Object.keys(value)),
    values: (value) => (value === null || value === undefined ? [] : Object.values(value)),
    forEach: (collection, iteratee) => {
      if (typeof iteratee !== 'function' || collection === null || collection === undefined) return collection;
      if (Array.isArray(collection)) {
        collection.forEach((item, index) => iteratee(item, index, collection));
        return collection;
      }
      Object.keys(collection).forEach((key) => iteratee(collection[key], key, collection));
      return collection;
    },
    map: (collection, iteratee) => {
      if (collection === null || collection === undefined) return [];
      const output = [];
      if (Array.isArray(collection)) {
        collection.forEach((item, index) => output.push(iteratee(item, index, collection)));
        return output;
      }
      Object.keys(collection).forEach((key) => output.push(iteratee(collection[key], key, collection)));
      return output;
    },
    filter: (collection, predicate) => {
      if (collection === null || collection === undefined) return [];
      const test = typeof predicate === 'function' ? predicate : (item) => Boolean(item);
      return (Array.isArray(collection) ? collection : Object.values(collection)).filter((item, index) =>
        test(item, index)
      );
    },
    reduce: (collection, iteratee, accumulator) => {
      if (collection === null || collection === undefined) return accumulator;
      const items = Array.isArray(collection) ? collection : Object.values(collection);
      if (accumulator === undefined) return items.reduce(iteratee);
      return items.reduce(iteratee, accumulator);
    },
    find: (collection, predicate) => {
      if (collection === null || collection === undefined) return undefined;
      const test = typeof predicate === 'function' ? predicate : (item) => Boolean(item);
      if (Array.isArray(collection)) return collection.find(test);
      return Object.keys(collection).map((key) => collection[key]).find(test);
    },
    includes: (collection, value) => {
      if (collection === null || collection === undefined) return false;
      if (typeof collection === 'string') return collection.indexOf(value) >= 0;
      if (Array.isArray(collection)) return collection.indexOf(value) >= 0;
      return Object.keys(collection).some((key) => collection[key] === value);
    },
    uniq: (collection) => Array.from(new Set(Array.isArray(collection) ? collection : [])),
    flatten: (collection) => (Array.isArray(collection) ? collection.flat(1) : []),
    flattenDeep: (collection) => (Array.isArray(collection) ? collection.flat(Infinity) : []),
    assign: (target, ...sources) => Object.assign(target || {}, ...sources),
    merge: (target, ...sources) => {
      const deepMerge = (base, source) => {
        Object.keys(source || {}).forEach((key) => {
          const value = source[key];
          if (isPlainObject(value)) base[key] = deepMerge(isPlainObject(base[key]) ? base[key] : {}, value);
          else if (value !== undefined) base[key] = value;
        });
        return base;
      };
      return sources.reduce((accumulator, source) => deepMerge(accumulator, source), target || {});
    },
    cloneDeep: (value) => {
      if (value === null || typeof value !== 'object') return value;
      if (value instanceof Date) return new Date(value.getTime());
      return JSON.parse(JSON.stringify(value));
    },
    pick: (object, keys) => {
      const output = {};
      (Array.isArray(keys) ? keys : [keys]).forEach((key) => {
        if (object && Object.prototype.hasOwnProperty.call(object, key)) output[key] = object[key];
      });
      return output;
    },
    omit: (object, keys) => {
      const skip = new Set(Array.isArray(keys) ? keys : [keys]);
      const output = {};
      Object.keys(object || {}).forEach((key) => {
        if (!skip.has(key)) output[key] = object[key];
      });
      return output;
    },
    groupBy: (collection, iteratee) => {
      const output = {};
      const items = Array.isArray(collection) ? collection : Object.values(collection || {});
      items.forEach((item, index) => {
        const key = String(typeof iteratee === 'function' ? iteratee(item, index) : item[iteratee]);
        if (!output[key]) output[key] = [];
        output[key].push(item);
      });
      return output;
    },
    clamp: (value, lower, upper) => Math.min(Math.max(value, lower), upper === undefined ? lower : upper)
  };
  // 缺的方法被调用时抛明确错误；用 Proxy 才能覆盖任意名字
  return new Proxy(lodash, {
    get(target, key) {
      if (typeof key !== 'string') return target[key];
      // thenable 探测不能返回可调用的桩，否则 await 这个对象会直接抛
      if (key === 'then' || key === 'toJSON' || key === 'inspect') return undefined;
      if (Object.prototype.hasOwnProperty.call(target, key)) return target[key];
      return () => {
        throw pluginError('NOT_SUPPORTED', 'Aharou 的 lodash 兼容层未实现 _.' + key + '()');
      };
    },
    has: (target, key) => typeof key === 'string' && Object.prototype.hasOwnProperty.call(target, key)
  });
}

function createUuidShim() {
  const uuidValidate = (value) =>
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(text(value));
  const uuidVersion = (value) => (uuidValidate(value) ? Number(text(value)[14]) : null);
  const v4 = () => crypto.randomUUID();
  const hashed = (version, namespace, name) => {
    const algorithm = version === 3 ? 'md5' : 'sha1';
    const bytes = crypto.createHash(algorithm).update(Buffer.concat([Buffer.from(uuidParse(namespace)), Buffer.from(text(name))])).digest();
    bytes[6] = (bytes[6] & 0x0f) | (version === 3 ? 0x30 : 0x50);
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = bytes.slice(0, 16).toString('hex');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20, 32);
  };
  const uuidParse = (value) => Buffer.from(text(value).replace(/-/g, ''), 'hex');
  const api = {
    v4,
    v1: () => v4(), // 没有时钟序列实现，退化成随机 UUID，避免伪造时间语义
    v3: (namespace, name) => hashed(3, namespace, name),
    v5: (namespace, name) => hashed(5, namespace, name),
    validate: uuidValidate,
    version: uuidVersion,
    parse: uuidParse,
    stringify: (bytes) => {
      const hex = Buffer.from(bytes).toString('hex');
      return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20, 32);
    },
    NIL: '00000000-0000-0000-0000-000000000000',
    MAX: 'ffffffff-ffff-ffff-ffff-ffffffffffff'
  };
  // 老写法 `const uuid = require('uuid')` 直接把模块当函数用
  const callable = () => v4();
  Object.keys(api).forEach((key) => {
    callable[key] = api[key];
  });
  callable.default = callable;
  return callable;
}

function createAxiosShim(runtime) {
  const buildConfig = (config) => {
    const headers = Object.assign({}, config.headers || {});
    let body = config.data;
    if (body !== undefined && body !== null && typeof body !== 'string') {
      body = JSON.stringify(body);
      if (!headers['Content-Type'] && !headers['content-type']) headers['Content-Type'] = 'application/json';
    }
    return {
      url: text(config.url || config.uri),
      method: text(config.method || 'GET').toUpperCase(),
      headers,
      body,
      timeout: config.timeout ? Math.max(1, Math.ceil(Number(config.timeout) / 1000)) : undefined
    };
  };

  const parseBody = (body, headers) => {
    const contentType = text(headers && (headers['content-type'] || headers['Content-Type'])).toLowerCase();
    if (contentType.indexOf('json') >= 0 || /^\s*[[{]/.test(body)) {
      try {
        return JSON.parse(body);
      } catch (error) {
        return body;
      }
    }
    return body;
  };

  const createInstance = (defaults) => {
    const base = Object.assign({}, defaults || {});
    const requestInterceptors = [];
    const responseInterceptors = [];

    const send = async (config) => {
      const merged = Object.assign({}, base, config || {});
      if (base.baseURL && merged.url && !/^https?:\/\//i.test(merged.url)) {
        merged.url = text(base.baseURL).replace(/\/+$/, '') + '/' + text(merged.url).replace(/^\/+/, '');
      }
      merged.headers = Object.assign({}, base.headers || {}, merged.headers || {});
      let finalConfig = merged;
      requestInterceptors.forEach((interceptor) => {
        if (interceptor && typeof interceptor.fulfilled === 'function') finalConfig = interceptor.fulfilled(finalConfig) || finalConfig;
      });
      const payload = buildConfig(finalConfig);
      const response = await runtime.toolCall('http.request', compact(payload));
      const status = Number((response && response.status) || 0);
      const rawBody = text(response && response.body);
      const data = parseBody(rawBody, response && response.headers);
      const axiosResponse = {
        data,
        status,
        statusText: '',
        headers: (response && response.headers) || {},
        config: finalConfig
      };
      const validate = finalConfig.validateStatus || ((code) => code >= 200 && code < 300);
      if (!validate(status)) {
        const error = pluginError('HTTP_STATUS', '请求失败，HTTP ' + status + ': ' + text(finalConfig.url), {
          isAxiosError: true,
          response: axiosResponse,
          config: finalConfig
        });
        const rejected = responseInterceptors.reduce((current, interceptor) => {
          if (current) return current;
          if (interceptor && typeof interceptor.rejected === 'function') return interceptor.rejected(error) || null;
          return null;
        }, null);
        throw rejected || error;
      }
      let result = axiosResponse;
      for (let index = 0; index < responseInterceptors.length; index += 1) {
        const interceptor = responseInterceptors[index];
        if (interceptor && typeof interceptor.fulfilled === 'function') {
          result = (await interceptor.fulfilled(result)) || result;
        }
      }
      return result;
    };

    const instance = (config) => send(config);
    instance.request = send;
    instance.get = (url, config) => send(Object.assign({}, config || {}, { url, method: 'GET' }));
    instance.delete = (url, config) => send(Object.assign({}, config || {}, { url, method: 'DELETE' }));
    instance.head = (url, config) => send(Object.assign({}, config || {}, { url, method: 'HEAD' }));
    instance.options = (url, config) => send(Object.assign({}, config || {}, { url, method: 'OPTIONS' }));
    instance.post = (url, data, config) => send(Object.assign({}, config || {}, { url, data, method: 'POST' }));
    instance.put = (url, data, config) => send(Object.assign({}, config || {}, { url, data, method: 'PUT' }));
    instance.patch = (url, data, config) => send(Object.assign({}, config || {}, { url, data, method: 'PATCH' }));
    instance.create = (extra) => createInstance(Object.assign({}, base, extra || {}));
    instance.interceptors = {
      request: {
        use: (fulfilled, rejected) => {
          requestInterceptors.push({ fulfilled, rejected });
          return requestInterceptors.length - 1;
        },
        eject: (index) => {
          requestInterceptors[index] = null;
        }
      },
      response: {
        use: (fulfilled, rejected) => {
          responseInterceptors.push({ fulfilled, rejected });
          return responseInterceptors.length - 1;
        },
        eject: (index) => {
          responseInterceptors[index] = null;
        }
      }
    };
    instance.defaults = base;
    instance.isAxiosError = (value) => Boolean(value && value.isAxiosError);
    return instance;
  };

  return createInstance({});
}

const BUILTIN_SHIMS = {
  lodash: () => createLodashShim()
};

let requireShimInstalled = false;

function installRequireShims(runtime) {
  if (requireShimInstalled) return;
  requireShimInstalled = true;
  const target = runtime || null;
  const originalLoad = Module._load;
  Module._load = function aharouLoad(request, parent, isMain) {
    if (Object.prototype.hasOwnProperty.call(BUILTIN_SHIMS, request)) {
      // 装了真包就让位给真包，只有缺包时才用兜底实现
      try {
        return originalLoad.call(this, request, parent, isMain);
      } catch (error) {
        if (error && error.code === 'MODULE_NOT_FOUND') return BUILTIN_SHIMS[request]();
        throw error;
      }
    }
    if (request === 'uuid') {
      try {
        return originalLoad.call(this, request, parent, isMain);
      } catch (error) {
        if (error && error.code === 'MODULE_NOT_FOUND') return createUuidShim();
        throw error;
      }
    }
    if (request === 'axios') {
      try {
        return originalLoad.call(this, request, parent, isMain);
      } catch (error) {
        if (error && error.code === 'MODULE_NOT_FOUND') {
          if (!target) {
            throw pluginError('NOT_SUPPORTED', 'axios 兜底实现需要先创建 runtime（createRuntime）');
          }
          return createAxiosShim(target);
        }
        throw error;
      }
    }
    return originalLoad.call(this, request, parent, isMain);
  };
}

// ---------------------------------------------------------------------------
// 全局注入
// ---------------------------------------------------------------------------

function installGlobals(runtime, globals) {
  const scope = globals || (runtime && runtime.globals);
  if (!scope) throw pluginError('PARAM_INVALID', 'installGlobals 需要 runtime');
  Object.keys(scope).forEach((key) => {
    globalThis[key] = scope[key];
  });
  globalThis._ = createLodashShim();
  installRequireShims(runtime);
  // compose DSL 屏幕模块在加载阶段就引用 `Icons.X`，若等到首次 ui.render 才装，
  // require 插件入口时就是 ReferenceError，整个包起不来。缺 compose-dsl-runtime.js 时
  // 只影响 UI 路线，其余兼容能力照常，所以吞掉异常。
  try {
    const compose = loadComposeRuntime();
    if (compose && typeof compose.installGlobalIcons === 'function') compose.installGlobalIcons();
  } catch (error) {
    // 忽略
  }

  const log = (level) => (...args) => {
    const message = args
      .map((item) => (typeof item === 'string' ? item : stringify(item)))
      .join(' ');
    if (runtime && typeof runtime.hostLog === 'function') runtime.hostLog(level, message, 'plugin-console');
  };
  // 插件打日志不能写 stdout：宿主按行读 JSON-RPC，纯文本行会被丢弃并刷日志
  // 插件会把 console 当成完整实现来用（verbose、table、group 都有实例），
  // 少一个方法就是在回调里抛 TypeError；没实际意义的那些统一转发到对应级别。
  globalThis.console = {
    log: log('info'),
    info: log('info'),
    warn: log('warn'),
    error: log('error'),
    debug: log('debug'),
    verbose: log('debug'),
    trace: log('debug'),
    dir: log('debug'),
    table: log('debug'),
    assert: function assert(condition) {
      if (!condition) log('error')('Assertion failed');
    },
    group: log('info'),
    groupCollapsed: log('info'),
    groupEnd: function groupEnd() {},
    count: function count() {},
    time: function time() {},
    timeEnd: function timeEnd() {},
    clear: function clear() {}
  };
  return globalThis;
}

module.exports = {
  createRuntime,
  loadComposeRuntime,
  createStdioTransport,
  installGlobals,
  installRequireShims,
  createLodashShim,
  createUuidShim,
  createAxiosShim,
  evaluateArithmetic,
  AharouPluginError,
  pluginError
};

// 预加载模式：宿主用 `node -r <本文件> main.js` 并设 AHAROU_OPERIT_AUTOLOAD=1 时，
// 模块求值阶段就把 Tools/toolCall/console 补到全局；消息循环仍由插件自己写，
// 插件收到调用后可用 runtime.reply(id, value) 按同一套 success 语义回执。
if (envFlag('AHAROU_OPERIT_AUTOLOAD')) {
  autoloadRuntime = createRuntime({});
  autoloadRuntime.installGlobals();
  module.exports.defaultRuntime = autoloadRuntime;
  module.exports.reply = autoloadRuntime.reply;
}
