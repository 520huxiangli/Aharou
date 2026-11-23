'use strict';

/**
 * Aharou · Operit Compose DSL 的 JS 运行时（CommonJS，零 npm 依赖）。
 *
 * 来源：AAswordman/Operit（LGPL-3.0，与 Aharou 的 GPL-3.0 兼容），移植自
 *   app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsComposeDslBridge.kt
 *   app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsComposeDslRuntimeScript.kt
 *
 * 改动：原实现跑在 QuickJS 里，靠 NativeInterface 做同步桥（createWebViewController /
 * measureText / openFilePicker / importPackage）并把渲染中间态回推宿主。这里换成
 * node + JSON-RPC：能异步的一律走宿主调用，异步不了的明确报 NOT_SUPPORTED；
 * 中间态回推整段删除，改成「宿主 dispatch 之后一次性拿走最新 {tree, state, memo}」。
 * 节点形状、action id 规则、state / memo 语义保持与 Operit 一致，让按 Operit 写的 ui.js 少改。
 */

const path = require('path');

const NODE_FLAG = '__composeNode';
const UNIT_TYPES = ['px', 'dp', 'fraction'];
let unitsInstalled = false;

function hasOwn(target, key) {
  return Object.prototype.hasOwnProperty.call(target, key);
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
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

function composeError(code, message) {
  const error = new Error(message);
  error.name = 'AharouComposeError';
  error.code = code;
  return error;
}

function notSupported(feature, alternative) {
  return composeError(
    'NOT_SUPPORTED',
    'Aharou 的 Compose DSL 运行时暂不支持 ' + feature + (alternative ? '；请改用 ' + alternative : '')
  );
}

/** Operit 的 cloneObject 语义：只摊平一层普通对象，数组与基础值原样返回。 */
function cloneObject(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return {};
  const out = {};
  Object.keys(input).forEach((key) => {
    out[key] = input[key];
  });
  return out;
}

function isComposeNodeLike(value) {
  return !!(value && typeof value === 'object' && value[NODE_FLAG] === true && typeof value.type === 'string');
}

function flattenSlotValue(value, out) {
  if (value === null || value === undefined) return;
  if (Array.isArray(value)) {
    value.forEach((item) => flattenSlotValue(item, out));
    return;
  }
  if (isComposeNodeLike(value)) out.push(value);
}

function normalizeSlotChildren(value) {
  const out = [];
  flattenSlotValue(value, out);
  return out;
}

/**
 * children 允许单节点、数组或嵌套数组；false / null 这类条件渲染残留直接丢掉，
 * 否则宿主会拿到无意义的占位条目。
 */
function normalizeChildren(children) {
  const out = [];
  const push = (value) => {
    if (value === null || value === undefined || value === false || value === '') return;
    if (Array.isArray(value)) {
      value.forEach(push);
      return;
    }
    if (isComposeNodeLike(value)) out.push(value);
  };
  push(children);
  return out;
}

/**
 * Operit 往 JS 全局注入了 `Icons`：`Icons.Add` 直接返回字符串 "Add"，由宿主映射成图标。
 * 节点里需要的只是一个名字，这里同源补上，否则真实 ui.js 一引用就 ReferenceError。
 */
function installGlobalIcons() {
  if (globalThis.Icons) return;
  if (typeof Proxy !== 'function') {
    globalThis.Icons = {};
    return;
  }
  globalThis.Icons = new Proxy(
    {},
    {
      get(_target, key) {
        if (typeof key !== 'string') return '';
        if (key === 'then') return undefined;
        return key;
      }
    }
  );
}

/**
 * `(4).dp` / `[1, 2].dp` 这类单位后缀是 Operit DSL 的语法糖，只在本进程的原型上加一次。
 * 注意 `4.dp` 在 JS 语法层就解析不过（小数字面量后面跟属性），写的时候要加括号。
 */
function installUnitGetters() {
  if (unitsInstalled) return;
  unitsInstalled = true;
  UNIT_TYPES.forEach((unit) => {
    try {
      if (!hasOwn(Number.prototype, unit)) {
        Object.defineProperty(Number.prototype, unit, {
          configurable: true,
          enumerable: false,
          get() {
            return { __unit: unit, value: this.valueOf() };
          }
        });
      }
      if (!hasOwn(Array.prototype, unit)) {
        Object.defineProperty(Array.prototype, unit, {
          configurable: true,
          enumerable: false,
          get() {
            return Array.isArray(this) ? this.map((item) => ({ __unit: unit, value: item })) : [];
          }
        });
      }
    } catch (error) {
      // 原型被冻结时静默跳过：单位语法糖不可用不影响渲染树
    }
  });
}

function formatTemplate(template, values) {
  let result = text(template);
  const source = isPlainObject(values) ? values : {};
  Object.keys(source).forEach((key) => {
    const value = source[key];
    result = result.split('{' + key + '}').join(value === null || value === undefined ? '' : text(value));
  });
  return result;
}

function unwrapTextFieldPayload(payload) {
  if (
    payload &&
    typeof payload === 'object' &&
    payload.__composeTextFieldPayload === true &&
    hasOwn(payload, 'value')
  ) {
    return payload.value;
  }
  return payload;
}

/**
 * 建一个路线实例的上下文。每个 route 一个实例：state / memo / action 表都挂在这里，
 * 宿主一次 ui.dispatch 之后重渲染读到的就是同一份 state。
 */
/** 插件进程内的 WebView 控制器表：key 是 controller id，值是插件自己创建的对象。 */
const webControllers = new Map();
let webControllerSeq = 0;

/**
 * 宿主回填网页里的 Promise 时调用。
 *
 * 参数是 JSON 字符串：跨进程只能传文本，解析失败就当成字符串原样返回，
 * 免得宿主一次性传了非 JSON 内容把接口调用整个拒掉。
 */
function callWebController(controllerId, ifaceName, method, argsJson) {
  const controller = webControllers.get(text(controllerId).trim());
  if (!controller) {
    return Promise.reject(composeError('WEB_CONTROLLER_MISSING', '没有这个 WebView 控制器: ' + controllerId));
  }
  const interfaces = controller.__aharouWebController.interfaces;
  const target = interfaces[text(ifaceName).trim()];
  if (!target) {
    return Promise.reject(composeError('WEB_INTERFACE_MISSING', '没有注册这个 JS 接口: ' + ifaceName));
  }
  const fn = target[text(method).trim()];
  if (typeof fn !== 'function') {
    return Promise.reject(composeError('WEB_METHOD_MISSING', '接口没有这个方法: ' + ifaceName + '.' + method));
  }
  let args = [];
  if (text(argsJson)) {
    try {
      const parsed = JSON.parse(argsJson);
      if (Array.isArray(parsed)) args = parsed;
    } catch (error) {
      args = [argsJson];
    }
  }
  return Promise.resolve(fn.apply(target, args));
}

function createContext(options) {
  const opts = isPlainObject(options) ? options : {};
  installUnitGetters();
  installGlobalIcons();

  const runtime = opts.runtime && typeof opts.runtime.toolCall === 'function' ? opts.runtime : null;
  const stateStore = cloneObject(opts.state);
  const memoStore = cloneObject(opts.memo);
  const envRef = isPlainObject(opts.env) ? opts.env : {};
  const moduleSpec = isPlainObject(opts.moduleSpec) ? opts.moduleSpec : {};
  const routeId = text(opts.routeId);
  const pluginId = text(opts.pluginId);

  const actionStore = Object.create(null);
  const listeners = [];
  let actionCounter = 0;
  let renderRound = 0;
  let stateDirty = false;

  function callHost(method, params) {
    if (!runtime) {
      return Promise.reject(notSupported('宿主调用（' + method + '）', '用 createRuntime 建出来的 runtime'));
    }
    return Promise.resolve(runtime.toolCall(method, params || {}));
  }

  function registerAction(handler) {
    actionCounter += 1;
    const actionId = 'action_' + actionCounter;
    actionStore[actionId] = handler;
    return actionId;
  }

  /**
   * 每轮渲染先清空 action 表：id 按渲染顺序重新生成，宿主手里那份树的 id 才和本次
   * 生成的 handler 一一对应；不清就会无限增长，旧 id 还会指向过期 handler。
   */
  function beginRender() {
    renderRound += 1;
    actionCounter = 0;
    Object.keys(actionStore).forEach((key) => {
      delete actionStore[key];
    });
    stateDirty = false;
    return renderRound;
  }

  function notifyStateChanged() {
    stateDirty = true;
    listeners.slice().forEach((listener) => {
      try {
        listener();
      } catch (error) {
        // 监听方自己的异常不能打断 setState
      }
    });
  }

  function subscribeStateChange(listener) {
    if (typeof listener !== 'function') return function noop() {};
    listeners.push(listener);
    let active = true;
    return function unsubscribe() {
      if (!active) return;
      active = false;
      const index = listeners.indexOf(listener);
      if (index >= 0) listeners.splice(index, 1);
    };
  }

  function useState(key, initialValue) {
    const stateKey = text(key).trim();
    if (!stateKey) throw composeError('PARAM_INVALID', 'useState 需要 key');
    if (!hasOwn(stateStore, stateKey)) stateStore[stateKey] = initialValue;
    return [
      stateStore[stateKey],
      function setState(nextValue) {
        // 与 Operit 一致：直接赋值，不把函数当 updater 解析（state 里存函数是合法值）
        stateStore[stateKey] = nextValue;
        notifyStateChanged();
      }
    ];
  }

  function useMutable(key, initialValue) {
    const memoKey = text(key).trim();
    if (!memoKey) throw composeError('PARAM_INVALID', 'useMutable 需要 key');
    if (!hasOwn(memoStore, memoKey)) memoStore[memoKey] = initialValue;
    return [
      memoStore[memoKey],
      function setMutable(nextValue) {
        // memo 不触发重渲染：它的定位就是跨渲染保留的可变引用
        memoStore[memoKey] = nextValue;
      }
    ];
  }

  function useRef(key, initialValue) {
    const memoKey = text(key).trim();
    if (!memoKey) throw composeError('PARAM_INVALID', 'useRef 需要 key');
    if (!hasOwn(memoStore, memoKey) || !isPlainObject(memoStore[memoKey])) {
      memoStore[memoKey] = { current: initialValue };
    }
    return memoStore[memoKey];
  }

  /**
   * React 语义的 useEffect：依赖没变就跳过，变了先跑上一个清理函数。
   *
   * 插件拿它做订阅和数据加载；回调抛错只记一条日志，不能让整棵面板渲染失败。
   */
  function useEffect(key, factory, deps) {
    const memoKey = text(key).trim();
    if (!memoKey) throw composeError('PARAM_INVALID', 'useEffect 需要 key');
    if (typeof factory !== 'function') return;
    const nextDeps = Array.isArray(deps) ? deps : null;
    const storeKey = '__effect__' + memoKey;
    const record = memoStore[storeKey];
    if (record && sameDeps(record.deps, nextDeps)) return;
    if (record && typeof record.cleanup === 'function') {
      try { record.cleanup(); } catch (error) {
        logEffectFailure('useEffect 清理失败 ' + memoKey + ': ' + text(error && error.message));
      }
    }
    let cleanup = null;
    try {
      cleanup = factory();
    } catch (error) {
      logEffectFailure('useEffect 执行失败 ' + memoKey + ': ' + text(error && error.message));
    }
    memoStore[storeKey] = { deps: nextDeps, cleanup: typeof cleanup === 'function' ? cleanup : null };
  }

  function logEffectFailure(message) {
    try {
      if (runtime && typeof runtime.hostLog === 'function') runtime.hostLog('warn', message, 'compose-dsl');
    } catch (error) {
      // 日志失败不影响渲染
    }
  }

  function sameDeps(a, b) {
    if (!a || !b) return false;
    if (a.length !== b.length) return false;
    for (let index = 0; index < a.length; index += 1) {
      if (!Object.is(a[index], b[index])) return false;
    }
    return true;
  }

  function useMemo(key, factory, deps) {
    const memoKey = text(key).trim();
    if (!memoKey) throw composeError('PARAM_INVALID', 'useMemo 需要 key');
    // deps 与 Operit 一样忽略：命中 key 就复用，插件要重算就换 key
    if (!hasOwn(memoStore, memoKey)) {
      memoStore[memoKey] = typeof factory === 'function' ? factory() : factory;
    }
    return memoStore[memoKey];
  }

  function normalizePropValue(value) {
    if (typeof value === 'function') return { __actionId: registerAction(value) };
    // WebView 控制器里全是函数，按普通对象递归会被逐个注册成 action，这里整体转成描述
    if (value && typeof value === 'object' && value.__aharouWebController) {
      const source = value.__aharouWebController;
      const interfaces = {};
      Object.keys(source.interfaces || {}).forEach((ifaceName) => {
        const target = source.interfaces[ifaceName] || {};
        interfaces[ifaceName] = Object.keys(target).filter((key) => typeof target[key] === 'function');
      });
      return { __aharouWebController: { id: source.id, interfaces } };
    }
    if (Array.isArray(value)) return value.map(normalizePropValue);
    if (value && typeof value === 'object') {
      if (isComposeNodeLike(value)) return value;
      // 颜色 token 自带的 copy() 是给插件链式用的，不能当动作注册，否则每轮渲染
      // 都会多占一个 action id，宿主手里的 id 也就对不上了
      if (typeof value.__colorToken === 'string') {
        return { __colorToken: value.__colorToken, alpha: value.alpha };
      }
      if (Array.isArray(value.__modifierOps)) return { __modifierOps: value.__modifierOps };
      const normalized = {};
      Object.keys(value).forEach((key) => {
        normalized[key] = normalizePropValue(value[key]);
      });
      return normalized;
    }
    return value;
  }

  /**
   * 节点的 props 里如果塞的是节点（AlertDialog 的 text、DropdownMenu 的 content），
   * 就挪进 slots，宿主按具名插槽渲染，不要当普通属性序列化。
   */
  function createNode(type, props, children) {
    const rawProps = isPlainObject(props) ? props : {};
    const normalizedProps = {};
    const normalizedSlots = {};
    Object.keys(rawProps).forEach((key) => {
      const rawValue = rawProps[key];
      const slotChildren = normalizeSlotChildren(rawValue);
      if (slotChildren.length > 0) {
        normalizedSlots[key] = slotChildren;
        return;
      }
      normalizedProps[key] = normalizePropValue(rawValue);
    });
    return {
      [NODE_FLAG]: true,
      type: text(type),
      props: normalizedProps,
      children: normalizeChildren(children),
      slots: normalizedSlots
    };
  }

  function createNodeFactory(type) {
    return function nodeFactory(props, children) {
      return createNode(type, props, children);
    };
  }

  function createModifierProxy(ops) {
    const chain = Array.isArray(ops) ? ops.slice() : [];
    const target = { __modifierOps: chain };
    return new Proxy(target, {
      get(_target, key) {
        if (key === '__modifierOps') return chain;
        if (key === 'toJSON') {
          return function toJSON() {
            return { __modifierOps: chain.slice() };
          };
        }
        // 有 then 就会被 await 当成 thenable，链式调用会被卡住
        if (key === 'then') return undefined;
        if (typeof key !== 'string') return undefined;
        return function modifierStep() {
          const args = [];
          for (let index = 0; index < arguments.length; index += 1) {
            args.push(normalizePropValue(arguments[index]));
          }
          const next = chain.slice();
          next.push({ name: key, args });
          return createModifierProxy(next);
        };
      }
    });
  }

  function createColorToken(name, alpha) {
    return {
      __colorToken: text(name),
      alpha: typeof alpha === 'number' ? alpha : undefined,
      copy(options) {
        const next = isPlainObject(options) ? options : {};
        const nextAlpha = typeof next.alpha === 'number' ? next.alpha : alpha;
        return createColorToken(name, nextAlpha);
      }
    };
  }

  const colorScheme = new Proxy(
    {},
    {
      get(_target, key) {
        if (typeof key !== 'string') return undefined;
        if (key === 'then' || key === 'toJSON') return undefined;
        return createColorToken(key);
      }
    }
  );

  const uiProxy = new Proxy(
    {},
    {
      get(_target, key) {
        // 类型不做白名单：渲染层决定怎么降级，未知类型也要能产出节点
        if (typeof key !== 'string') return undefined;
        if (key === 'then' || key === 'toJSON') return undefined;
        return createNodeFactory(key);
      }
    }
  );

  /**
   * 插件读主题的入口。
   *
   * 宿主把明暗写进 env；取不到就按浅色算，订阅只登记回调，
   * 宿主主题变了会通过 render 带新值进来，这里不额外造推送通道。
   */
  const themeState = {
    brightness: text(envRef.brightness) || (envRef.dark === true || envRef.dark === 'true' ? 'dark' : 'light')
  };
  const themeListeners = new Set();
  const themeApi = {
    getCurrent: function getCurrent() {
      return { brightness: themeState.brightness, dark: themeState.brightness === 'dark' };
    },
    subscribe: function subscribe(listener) {
      if (typeof listener !== 'function') return function noop() {};
      themeListeners.add(listener);
      return function unsubscribe() { themeListeners.delete(listener); };
    }
  };

  const ctx = {
    UI: uiProxy,
    Theme: themeApi,
    MaterialTheme: { colorScheme },
    Modifier: createModifierProxy([]),
    h: function h(type, props, children) {
      return createNode(type, props, children);
    },
    useState,
    useMutable,
    useRef,
    useMemo,
    useEffect,

    toolCall: function toolCallCompat() {
      // Operit 侧有 (name, params) 与 ('default', name, params) 两种写法
      const args = Array.prototype.slice.call(arguments);
      let name = args[0];
      let params = args[1];
      if (name === 'default') {
        name = args[1];
        params = args[2];
      }
      if (!name) return Promise.reject(composeError('PARAM_INVALID', 'toolCall 需要工具名'));
      return callHost('tools.execute', {
        name: text(name),
        arguments: isPlainObject(params) ? params : {}
      });
    },
    callTool: function callTool(name, params) {
      return ctx.toolCall(name, params);
    },

    /**
     * Operit 的 getEnv 是同步的（读宿主内存里的 env 表）。这里读的是创建上下文时的
     * env 快照；宿主后续变更要用 refreshEnv() 拉一次，别指望它在 render 中途自动更新。
     */
    getEnv: function getEnv(key) {
      const name = text(key).trim();
      if (!name) return undefined;
      if (hasOwn(envRef, name)) {
        const value = envRef[name];
        return value === undefined || value === null || value === '' ? undefined : text(value);
      }
      return undefined;
    },
    refreshEnv: async function refreshEnv() {
      if (runtime && typeof runtime.refreshEnv === 'function') await runtime.refreshEnv();
      return Object.assign({}, envRef);
    },
    setEnv: function setEnv(key, value) {
      const name = text(key).trim();
      if (!name) return Promise.reject(composeError('PARAM_INVALID', 'setEnv 需要 key'));
      const next = value === undefined || value === null ? '' : text(value);
      return callHost('env.set', { key: name, value: next }).then((result) => {
        envRef[name] = next;
        return result;
      });
    },
    setEnvs: function setEnvs(values) {
      const entries = isPlainObject(values) ? Object.keys(values) : [];
      // 宿主是单键写入：顺序发，避免并发写同一份 env 文件
      return entries.reduce(
        (chain, key) => chain.then(() => ctx.setEnv(key, values[key])),
        Promise.resolve()
      );
    },

    showToast: function showToast(message) {
      const body = text(message);
      const tools = runtime && runtime.Tools;
      if (tools && tools.System && typeof tools.System.toast === 'function') {
        return tools.System.toast(body).then(() => ({ ok: true, message: body }));
      }
      // 进程来源只能用 host.log（log.write 是页面侧方法），tag 让日志能按来源过滤
      return callHost('log', { level: 'info', message: body, tag: 'compose-dsl' }).then(() => ({
        ok: true,
        message: body,
        degraded: 'log'
      }));
    },
    reportError: function reportError(error) {
      const message = error && error.message !== undefined ? text(error.message) : text(error);
      return callHost('log', {
        level: 'error',
        message: '[compose_dsl] ' + message,
        tag: 'compose-dsl'
      }).catch(() => undefined);
    },

    /** 路由跳转需要宿主渲染层配合；现在没有这层能力，直接报错，不假装跳成功。 */
    navigate: function navigate(route) {
      return Promise.reject(
        notSupported('ctx.navigate(' + text(route) + ')', '让宿主切换路线后重新 ui.render')
      );
    },
    listRoutes: function listRoutes() {
      const table = opts.routeTable;
      return table && typeof table.list === 'function' ? table.list() : [];
    },
    getHostRoutes: function getHostRoutes() {
      // 宿主自己的路线与 Compose DSL 无关，这里没有清单可给
      return [];
    },

    getModuleSpec: function getModuleSpec() {
      return moduleSpec;
    },
    formatTemplate,
    getCurrentPackageName: function getCurrentPackageName() {
      return pluginId || undefined;
    },
    getCurrentToolPkgId: function getCurrentToolPkgId() {
      return pluginId || undefined;
    },
    getCurrentUiModuleId: function getCurrentUiModuleId() {
      return routeId || undefined;
    },

    /**
     * Operit 的 WebView 控制器：插件先把 JS 接口挂上去，再交给 UI.WebView 渲染。
     *
     * 接口函数不能序列化给宿主，所以这里只登记到进程内的表里，
     * 节点上留下 controller id 与方法名清单；真正的调用由宿主经 ui.jsbridge 回来执行。
     */
    createWebViewController: function createWebViewController(id) {
      const name = text(id).trim() || ('webview_' + (webControllerSeq += 1));
      const controller = {
        __aharouWebController: { id: name, interfaces: {} },
        addJavascriptInterface: function addJavascriptInterface(ifaceName, target) {
          const key = text(ifaceName).trim();
          if (!key || !target || typeof target !== 'object') return false;
          controller.__aharouWebController.interfaces[key] = target;
          webControllers.set(name, controller);
          return true;
        },
        removeJavascriptInterface: function removeJavascriptInterface(ifaceName) {
          const key = text(ifaceName).trim();
          if (!key) return false;
          delete controller.__aharouWebController.interfaces[key];
          return true;
        },
        // 控制器自带的这几个方法在 Operit 里是给 JS 直接操纵页面的；
        // 宿主页面由 Compose DSL 渲染，没有命令通道，保留空实现以免插件调用即崩。
        loadUrl: function loadUrl() { return false; },
        loadData: function loadData() { return false; },
        evaluateJavascript: function evaluateJavascript() { return false; },
        reload: function reload() { return false; }
      };
      webControllers.set(name, controller);
      return controller;
    },
    /**
     * 长列表的窗口化取数：只物化前 initialItemCount 项，其余由渲染层按总量补齐。
     *
     * 插件把它展开进 UI.LazyColumn 的 props（`{...lazy}`），所以这里返回的键名
     * 必须与 LazyColumn 的 props 对齐：content + viewport。
     */
    lazyItems: function lazyItems(options, itemBuilder) {
      const opts = isPlainObject(options) ? options : {};
      if (typeof itemBuilder !== 'function') {
        throw composeError('PARAM_INVALID', 'lazyItems 需要 itemBuilder');
      }
      const total = Math.max(0, Number(opts.itemCount) || 0);
      const wanted = Number(opts.initialItemCount);
      const limit = Number.isFinite(wanted) && wanted > 0 ? Math.min(total, wanted) : total;
      const content = [];
      for (let index = 0; index < limit; index += 1) content.push(itemBuilder(index));
      return {
        content,
        viewport: {
          itemCount: total,
          itemExtent: opts.itemExtent,
          variableExtent: opts.variableExtent === true
        }
      };
    },
    measureText: function measureText() {
      throw notSupported('ctx.measureText（宿主文本测量）');
    },
    openFilePicker: function openFilePicker() {
      return Promise.reject(notSupported('ctx.openFilePicker（宿主文件选择器）'));
    },

    isPackageImported: function isPackageImported() {
      return Promise.reject(notSupported('ctx.isPackageImported（Operit 的工具包机制）'));
    },
    importPackage: function importPackage() {
      return Promise.reject(notSupported('ctx.importPackage（Operit 的工具包机制）'));
    },
    removePackage: function removePackage() {
      return Promise.reject(notSupported('ctx.removePackage（Operit 的工具包机制）'));
    },
    usePackage: function usePackage() {
      return Promise.reject(notSupported('ctx.usePackage（Operit 的工具包机制）'));
    },
    listImportedPackages: function listImportedPackages() {
      return Promise.reject(notSupported('ctx.listImportedPackages（Operit 的工具包机制）'));
    },
    resolveToolName: function resolveToolName(request) {
      // 没有工具包命名空间，工具名就是宿主注册的名字
      const req = isPlainObject(request) ? request : {};
      return Promise.resolve(text(req.toolName));
    }
  };

  const bundle = {
    ctx,
    state: stateStore,
    memo: memoStore,
    routeId,
    beginRender,
    isDirty: () => stateDirty,
    invokeAction(actionId, payload) {
      const id = text(actionId).trim();
      if (!id) throw composeError('PARAM_INVALID', 'action id 不能为空');
      const handler = actionStore[id];
      if (typeof handler !== 'function') {
        throw composeError('ACTION_NOT_FOUND', 'compose action 不存在或已过期: ' + id);
      }
      return handler(unwrapTextFieldPayload(payload));
    },
    subscribeStateChange,
    flushStateChanges() {
      stateDirty = false;
      return Promise.resolve();
    },
    /** 宿主给回 state 时用：整份替换，或补缺键（不盖掉 JS 侧刚改的值）。 */
    applyHostState(nextState, replace, fillMissingOnly) {
      if (!isPlainObject(nextState)) return;
      if (replace) {
        Object.keys(stateStore).forEach((key) => {
          delete stateStore[key];
        });
      }
      Object.keys(nextState).forEach((key) => {
        // 热实例上只用宿主 state 补新键：宿主手里的那份可能是几轮之前的快照，
        // 直接盖会把 handler 刚写进 state 的值回退掉
        if (fillMissingOnly && hasOwn(stateStore, key)) return;
        stateStore[key] = nextState[key];
      });
    },
    applyHostMemo(nextMemo, replace, fillMissingOnly) {
      if (!isPlainObject(nextMemo)) return;
      if (replace) {
        Object.keys(memoStore).forEach((key) => {
          delete memoStore[key];
        });
      }
      Object.keys(nextMemo).forEach((key) => {
        if (fillMissingOnly && hasOwn(memoStore, key)) return;
        memoStore[key] = nextMemo[key];
      });
    }
  };

  return bundle;
}

/** screen 可以是函数、包内相对路径，或 import 出来的模块对象（Operit 三种写法都出现过）。 */
function pickEntry(mod) {
  if (typeof mod === 'function') return mod;
  if (mod && typeof mod.default === 'function') return mod.default;
  if (mod && typeof mod.Screen === 'function') return mod.Screen;
  return null;
}

function resolveScreenEntry(screen, baseDir) {
  if (typeof screen === 'function') return screen;
  if (typeof screen === 'string') {
    const target = path.isAbsolute(screen) ? screen : path.resolve(baseDir, screen);
    return pickEntry(require(target));
  }
  return pickEntry(screen);
}

/**
 * 路线表：注册（registerUiRoute）、渲染（ui.render）、派发动作（ui.dispatch）都在这里。
 * 每条路线一个上下文实例，state 随实例常驻进程内存。
 */
function createRouteTable(options) {
  const opts = isPlainObject(options) ? options : {};
  const baseDir = opts.baseDir ? path.resolve(opts.baseDir) : process.cwd();
  const runtime = opts.runtime || null;
  const env = isPlainObject(opts.env) ? opts.env : {};
  const pluginId = text(opts.pluginId);
  const routes = new Map();
  const instances = new Map();

  function register(definition) {
    const def = isPlainObject(definition) ? definition : {};
    const id = text(def.id).trim();
    const route = text(def.route).trim();
    const runtimeId = text(def.runtime).trim() || 'compose_dsl';
    if (!id) {
      return { ok: false, reason: 'registerUiRoute 需要非空的 id 或 route' };
    }
    if (runtimeId !== 'compose_dsl') {
      return { ok: false, reason: 'Aharou 只支持 compose_dsl 运行时（收到 ' + runtimeId + '）' };
    }
    const entry = {
      id,
      route,
      runtime: runtimeId,
      screen: def.screen,
      title: def.title,
      params: def.params,
      // 注册时不 require 页面模块：插件可能在注册阶段还没准备好文件，渲染时再解析
      screenKind: typeof def.screen === 'string' ? 'path' : typeof def.screen === 'function' ? 'function' : 'module'
    };
    routes.set(id, entry);
    if (route) routes.set(route, entry);
    return { ok: true, route: publicRoute(entry) };
  }

  function publicRoute(entry) {
    return {
      id: entry.id,
      route: entry.route,
      runtime: entry.runtime,
      title: entry.title,
      screen: entry.screenKind === 'path' ? text(entry.screen) : entry.screenKind
    };
  }

  function lookup(routeId) {
    const key = text(routeId).trim();
    if (!key) return null;
    return routes.get(key) || null;
  }

  function list() {
    const seen = new Set();
    const out = [];
    routes.forEach((entry) => {
      if (seen.has(entry.id)) return;
      seen.add(entry.id);
      out.push(publicRoute(entry));
    });
    return out;
  }

  function ensureInstance(entry, hostState) {
    let instance = instances.get(entry.id);
    const replace = !!(hostState && hostState.replaceState === true);
    if (!instance) {
      instance = {
        entry,
        rendered: false,
        bundle: createContext({
          state: hostState && hostState.state,
          memo: hostState && hostState.memo,
          env,
          runtime,
          pluginId,
          routeId: entry.id,
          moduleSpec: entry.params && isPlainObject(entry.params.spec) ? entry.params.spec : {},
          routeTable: { list }
        }),
        screen: null
      };
      instances.set(entry.id, instance);
      return instance;
    }
    const fillMissingOnly = instance.rendered && !replace;
    if (hostState && hostState.state) instance.bundle.applyHostState(hostState.state, replace, fillMissingOnly);
    if (hostState && hostState.memo) instance.bundle.applyHostMemo(hostState.memo, replace, fillMissingOnly);
    return instance;
  }

  function resolveEntryFor(instance) {
    if (typeof instance.screen === 'function') return instance.screen;
    const resolved = resolveScreenEntry(instance.entry.screen, baseDir);
    if (typeof resolved !== 'function') {
      throw composeError(
        'SCREEN_NOT_FOUND',
        '路线 ' + instance.entry.id + ' 的 screen 解析不出函数（期望 default export 或 Screen）'
      );
    }
    instance.screen = resolved;
    return resolved;
  }

  async function render(routeId, renderOptions) {
    const optsForRender = isPlainObject(renderOptions) ? renderOptions : {};
    let entry = typeof routeId === 'object' && routeId ? routeId : lookup(routeId);
    if (!entry && typeof routeId === 'object' && routeId) {
      // 允许直接把注册定义传进来
      const registered = register(routeId);
      if (!registered.ok) throw composeError('ROUTE_INVALID', registered.reason);
      entry = lookup(routeId.id || routeId.route);
    }
    if (!entry) throw composeError('ROUTE_NOT_FOUND', '没有注册过这条路线: ' + text(routeId));
    const instance = ensureInstance(entry, optsForRender);
    const screen = resolveEntryFor(instance);
    instance.bundle.beginRender();
    const tree = await screen(instance.bundle.ctx);
    if (tree === undefined || tree === null) {
      throw composeError('RENDER_EMPTY', 'Screen(ctx) 没有返回节点: ' + entry.id);
    }
    instance.rendered = true;
    return {
      tree,
      state: instance.bundle.state,
      memo: instance.bundle.memo,
      route: publicRoute(entry)
    };
  }

  async function dispatch(request) {
    const req = isPlainObject(request) ? request : {};
    const requestedRoute = text(req.routeId || req.route || req.id).trim();
    const actionId = text(req.actionId || req.__actionId || req.action).trim();
    if (!actionId) throw composeError('PARAM_INVALID', 'ui.dispatch 需要 actionId');
    if (!requestedRoute) throw composeError('PARAM_INVALID', 'ui.dispatch 需要 routeId');
    // 宿主可能传 route 全名：实例表按 id 存，先归一化到注册条目
    const entry = lookup(requestedRoute);
    if (!entry) throw composeError('ROUTE_NOT_FOUND', '没有注册过这条路线: ' + requestedRoute);

    let instance = instances.get(entry.id);
    if (!instance) {
      instance = ensureInstance(entry, req);
      // 没渲染过就没有 action 表：先渲染一次再把 handler 建出来
      await render(entry.id);
      instance = instances.get(entry.id);
    } else if (req.state || req.memo) {
      ensureInstance(entry, req);
    }

    const payload = hasOwn(req, 'payload') ? req.payload : hasOwn(req, 'args') ? req.args : undefined;
    const actionResult = await instance.bundle.invokeAction(actionId, payload);
    const rendered = await render(entry.id);
    return Object.assign({}, rendered, { actionResult });
  }

  function reset(routeId) {
    const key = text(routeId).trim();
    if (key) {
      const entry = lookup(key);
      if (entry) instances.delete(entry.id);
      return;
    }
    instances.clear();
  }

  return {
    register,
    list,
    render,
    dispatch,
    reset,
    has: (routeId) => !!lookup(routeId)
  };
}

module.exports = {
  callWebController,
  createContext,
  createRouteTable,
  resolveScreenEntry,
  installGlobalIcons,
  isComposeNode: isComposeNodeLike,
  nodeFlag: NODE_FLAG
};
