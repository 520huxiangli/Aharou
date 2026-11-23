'use strict';

/**
 * operit-shim 的最小自测：用假宿主桩替代 stdio 通道，验证兼容层与宿主之间的契约。
 *
 * 跑法：node plugins/sdk/operit-selftest.cjs
 * 只覆盖不需要真机与真宿主的路径；涉及 device.*、chat.*、env.* 的实际宿主实现不在本脚本范围。
 */

const path = require('path');
const assert = require('assert');

const shim = require(path.resolve(__dirname, '../../app/src/main/assets/plugin-ui/operit-shim.js'));

function createFakeHost(handlers) {
  const calls = [];
  const results = [];
  const waiters = new Map();
  let onRequest = null;
  let seq = 0;

  function settle(id) {
    const waiter = waiters.get(String(id));
    if (waiter) {
      waiters.delete(String(id));
      waiter();
    }
  }

  const transport = {
    async request(method, params) {
      calls.push({ method, params });
      const handler = handlers[method];
      if (!handler) {
        const error = new Error('unknown host method: ' + method);
        error.code = 'METHOD_UNKNOWN';
        throw error;
      }
      return handler(params || {});
    },
    onRequest(fn) {
      onRequest = fn;
    },
    sendResult(id, result) {
      results.push({ id, result });
      settle(id);
    },
    sendError(id, error) {
      results.push({ id, error });
      settle(id);
    },
    notify(method, params) {
      results.push({ notify: method, params });
    },
    onClose() {},
    close() {}
  };

  return {
    transport,
    calls,
    results,
    deliver(method, params) {
      const id = ++seq;
      return new Promise((resolve) => {
        waiters.set(String(id), resolve);
        onRequest(method, params || {}, id);
      });
    },
    lastResult() {
      return results[results.length - 1];
    }
  };
}

function createHarness(handlers) {
  const host = createFakeHost(handlers);
  const runtime = shim.createRuntime({
    transport: host.transport,
    env: { DEMO_KEY: 'demo-value', EMPTY_KEY: '' },
    pluginId: 'com.example.operit',
    configDir: '/root/.aharou/plugins/data/com.example.operit'
  });
  runtime.serve();
  return { host, runtime };
}

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

test('complete 把 success:true 原样作为 result', async () => {
  const { host, runtime } = createHarness({});
  runtime.registerHandler('tools.ok', () => ({ success: true, data: 42 }));
  await host.deliver('tool.invoke', { handler: 'tools.ok', args: {} });
  assert.deepStrictEqual(host.lastResult().result, { success: true, data: 42 });
  assert.strictEqual(host.lastResult().error, undefined);
});

test('complete 把 {success:false,message} 转成 JSON-RPC error', async () => {
  const { host, runtime } = createHarness({});
  runtime.registerHandler('tools.fail', () => runtime.complete({ success: false, message: '文件不存在' }));
  assert.strictEqual(runtime.complete({ success: false, message: '没有活动调用' }), false);
  await host.deliver('tool.invoke', { handler: 'tools.fail', args: {} });
  assert.strictEqual(host.lastResult().error.code, 'TOOL_FAILED');
  assert.strictEqual(host.lastResult().error.message, '文件不存在');
  assert.strictEqual(host.lastResult().result, undefined);
});

test('普通对象与 undefined 按原样/ null 返回', async () => {
  const { host, runtime } = createHarness({});
  runtime.registerHandler('tools.plain', () => ({ text: 'hello' }));
  runtime.registerHandler('tools.empty', () => undefined);
  await host.deliver('tool.invoke', { handler: 'tools.plain', args: {} });
  assert.deepStrictEqual(host.lastResult().result, { text: 'hello' });
  await host.deliver('tool.invoke', { handler: 'tools.empty', args: {} });
  assert.strictEqual(host.lastResult().result, null);
});

test('handler 抛错转成 PLUGIN_ERROR，缺 handler 报 HANDLER_NOT_FOUND', async () => {
  const { host, runtime } = createHarness({});
  runtime.registerHandler('tools.boom', () => {
    throw new Error('炸了');
  });
  await host.deliver('tool.invoke', { handler: 'tools.boom', args: {} });
  assert.strictEqual(host.lastResult().error.code, 'PLUGIN_ERROR');
  assert.strictEqual(host.lastResult().error.message, '炸了');
  await host.deliver('tool.invoke', { handler: 'tools.missing', args: {} });
  assert.strictEqual(host.lastResult().error.code, 'HANDLER_NOT_FOUND');
});

test('getEnv 读注入的 env，空值与未声明返回 undefined', async () => {
  const { runtime } = createHarness({});
  assert.strictEqual(runtime.getEnv('DEMO_KEY'), 'demo-value');
  assert.strictEqual(runtime.getEnv('EMPTY_KEY'), undefined);
  assert.strictEqual(runtime.getEnv('NOT_DECLARED_ANYWHERE'), undefined);
  assert.strictEqual(runtime.getPluginConfigDir(), '/root/.aharou/plugins/data/com.example.operit');
  assert.strictEqual(runtime.getLang(), 'en');
  assert.strictEqual(runtime.getChatId(), null);
});

test('Tools.Files.read 形变成 {content,size} 并走 host.fs.read', async () => {
  const { host, runtime } = createHarness({
    'host.fs.read': ({ path: filePath }) => ({ content: 'hello\nworld', size: 11, path: filePath })
  });
  const result = await runtime.Tools.Files.read({ path: 'data://note.md' });
  assert.deepStrictEqual(result, { path: 'data://note.md', content: 'hello\nworld', size: 11 });
  assert.strictEqual(host.calls[0].method, 'host.fs.read');
  assert.strictEqual(host.calls[0].params.path, 'data://note.md');
});

test('Tools.Files.list 补 isDirectory/path 别名', async () => {
  const { runtime } = createHarness({
    'host.fs.list': () => ({ entries: [{ name: 'a.txt', isDir: false, size: 3 }] })
  });
  const result = await runtime.Tools.Files.list('/root/workspace');
  assert.deepStrictEqual(result.entries[0], {
    name: 'a.txt',
    isDir: false,
    isDirectory: false,
    fileType: 'file',
    size: 3,
    path: '/root/workspace/a.txt'
  });
});

test('Tools.Files.readPart 在宿主缺 fs.readRange 时本地切行', async () => {
  const { runtime } = createHarness({
    'host.fs.read': () => ({ content: 'l1\nl2\nl3\nl4', size: 11 })
  });
  const result = await runtime.Tools.Files.readPart('a.txt', 2, 3);
  assert.strictEqual(result.content, 'l2\nl3');
  assert.strictEqual(result.totalLines, 4);
  assert.strictEqual(result.degraded, 'local-line-slice');
});

test('Tools.Files.edit 在宿主缺 fs.edit 时读改写并校验老内容', async () => {
  let stored = 'alpha beta';
  const { runtime } = createHarness({
    'host.fs.read': () => ({ content: stored, size: stored.length }),
    'host.fs.write': ({ content }) => {
      stored = content;
      return { ok: true };
    }
  });
  await runtime.Tools.Files.edit('a.txt', 'beta', 'gamma');
  assert.strictEqual(stored, 'alpha gamma');
  await assert.rejects(() => runtime.Tools.Files.edit('a.txt', 'not-there', 'x'), (error) => {
    assert.strictEqual(error.code, 'EDIT_NOT_FOUND');
    return true;
  });
});

test('Tools.Files.write 按 append 选择 fs.append', async () => {
  const { host, runtime } = createHarness({
    'host.fs.append': () => ({ ok: true }),
    'host.fs.write': () => ({ ok: true })
  });
  await runtime.Tools.Files.write('a.txt', 'tail', true);
  assert.strictEqual(host.calls[0].method, 'host.fs.append');
  await runtime.Tools.Files.write('a.txt', 'whole', false);
  assert.strictEqual(host.calls[1].method, 'host.fs.write');
});

test('Tools.Net.http 走 host.http.request 并整理响应', async () => {
  const { host, runtime } = createHarness({
    'host.http.request': ({ method, url }) => ({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ok: true, method, url }),
      truncated: false
    })
  });
  const result = await runtime.Tools.Net.http({ url: 'https://example.com/api', method: 'post', data: { a: 1 } });
  assert.strictEqual(result.status, 200);
  assert.deepStrictEqual(JSON.parse(result.data), { ok: true, method: 'POST', url: 'https://example.com/api' });
  assert.strictEqual(host.calls[0].method, 'host.http.request');
  assert.strictEqual(host.calls[0].params.body, '{"a":1}');
});

test('toolCall 支持三参数写法 toolCall("default", name, params)', async () => {
  const { host, runtime } = createHarness({
    'host.fs.stat': () => ({ exists: true, isDir: false, size: 7 })
  });
  const result = await runtime.toolCall('default', 'fs.stat', { path: 'a.txt' });
  assert.deepStrictEqual(result, { exists: true, isDir: false, size: 7 });
  assert.strictEqual(host.calls[0].method, 'host.fs.stat');
});

test('Tools.Net.http 把 ignore_ssl/follow_redirects 转成宿主写法', async () => {
  const { host, runtime } = createHarness({
    'host.http.request': () => ({ status: 204, headers: {}, body: '' })
  });
  await runtime.Tools.Net.http({ url: 'https://example.com', ignore_ssl: true, follow_redirects: true, maxBytes: 1024 });
  assert.strictEqual(host.calls[0].params.ignoreSsl, true);
  assert.strictEqual(host.calls[0].params.followRedirects, true);
  assert.strictEqual(host.calls[0].params.maxBytes, 1024);
  const get = await runtime.Tools.Net.httpGet('https://example.com', true);
  assert.strictEqual(get.status, 204);
  assert.strictEqual(host.calls[1].params.ignoreSsl, true);
});

test('Tools.Net.uploadFile 读文件后拼 multipart', async () => {
  const { host, runtime } = createHarness({
    'host.fs.readBinary': () => ({ base64: 'QUJD', size: 3 }),
    'host.http.request': ({ multipart }) => ({ status: 200, headers: {}, body: JSON.stringify(multipart) })
  });
  await runtime.Tools.Net.uploadFile({
    url: 'https://example.com/upload',
    files: [{ name: 'file', path: '/root/workspace/a.txt' }],
    form_data: { tag: 'demo' }
  });
  const multipart = host.calls[1].params.multipart;
  assert.deepStrictEqual(multipart.fields, { tag: 'demo' });
  assert.strictEqual(multipart.files[0].name, 'file');
  assert.strictEqual(multipart.files[0].fileName, 'a.txt');
  assert.strictEqual(multipart.files[0].base64, 'QUJD');
});

test('Tools.Files.download 用 bodyBase64 写二进制', async () => {
  const { host, runtime } = createHarness({
    'host.http.request': () => ({ status: 200, headers: {}, body: '', bodyBase64: 'AAEC', truncated: false }),
    'host.fs.writeBinary': () => ({ ok: true }),
    'host.fs.stat': () => ({ exists: true, isDir: false, size: 3 })
  });
  const result = await runtime.Tools.Files.download('https://example.com/f.bin', '/root/workspace/f.bin');
  assert.strictEqual(host.calls[0].params.responseBodyBase64, true);
  assert.strictEqual(host.calls[1].method, 'host.fs.writeBinary');
  assert.strictEqual(host.calls[1].params.base64, 'AAEC');
  assert.strictEqual(result.size, 3);
});

test('宿主不支持的能力明确报错，不静默返回空', async () => {
  const { runtime } = createHarness({});
  // 浏览器主路径已经接上宿主（browser.navigate），不再抛 NOT_SUPPORTED；
  // 仍然不支持的浏览器原语要明确报错，而不是静默返回空。
  await assert.rejects(() => runtime.Tools.Net.browserDrag({}), (error) => {
    assert.strictEqual(error.code, 'NOT_SUPPORTED');
    return true;
  });
  await assert.rejects(() => runtime.Tools.Files.zip('a', 'b'), (error) => {
    assert.strictEqual(error.code, 'NOT_SUPPORTED');
    return true;
  });
  // listApps 已经接了宿主（app.list），换成仍然不支持的浏览器原语：
  // 用插件自己的脚本去操作页面需要一套沙箱，Aharou 不做，必须明确拒绝而不是假装成功。
  await assert.rejects(() => runtime.Tools.Net.browserRunCode({}), (error) => {
    assert.strictEqual(error.code, 'NOT_SUPPORTED');
    return true;
  });
});

test('environment=secondary 明确拒绝，primary 放行', async () => {
  const { runtime } = createHarness({
    'host.fs.list': () => ({ entries: [] })
  });
  await runtime.Tools.Files.list('.', 'primary');
  await assert.rejects(() => runtime.Tools.Files.list('.', 'secondary'), (error) => {
    assert.strictEqual(error.code, 'ENVIRONMENT_UNSUPPORTED');
    return true;
  });
});

test('ToolPkg.register* 记录声明并返回 true，可被宿主取走', async () => {
  const { runtime } = createHarness({});
  assert.strictEqual(runtime.ToolPkg.registerXmlRenderPlugin({ id: 'xml', tag: 'my-tag' }), true);
  assert.strictEqual(runtime.ToolPkg.registerAiProvider({ id: 'p' }), true);
  const taken = runtime.takeUnsupportedRegistrations();
  assert.strictEqual(taken.length, 2);
  assert.strictEqual(taken[0].api, 'registerXmlRenderPlugin');
  assert.strictEqual(taken[0].ok, false);
  assert.ok(/XML 渲染/.test(taken[0].reason), taken[0].reason);
  assert.deepStrictEqual(runtime.takeUnsupportedRegistrations(), []);
});

test('Tools.System.sleep 走本地定时器，不占用宿主', async () => {
  const { host, runtime } = createHarness({});
  const started = Date.now();
  const result = await runtime.Tools.System.sleep(30);
  assert.strictEqual(result.ok, true);
  assert.ok(Date.now() - started >= 25);
  assert.strictEqual(host.calls.length, 0);
});

test('Tools.calc 本地算且拒绝非算术输入', async () => {
  const { runtime } = createHarness({});
  assert.strictEqual((await runtime.Tools.calc('2+3*4^2-(1+1)')).result, 48);
  assert.strictEqual((await runtime.Tools.calc('sqrt(16)+max(1,2)')).result, 6);
  await assert.rejects(() => runtime.Tools.calc('process.exit(1)'), (error) => {
    assert.strictEqual(error.code, 'PARAM_INVALID');
    return true;
  });
});

test('axios 兜底走 host.http.request 并解析 JSON', async () => {
  const axios = shim.createAxiosShim({
    toolCall: async (method, params) => {
      assert.strictEqual(method, 'http.request');
      assert.strictEqual(params.url, 'https://example.com/api');
      return { status: 200, headers: { 'content-type': 'application/json' }, body: '{"ok":true}' };
    }
  });
  const response = await axios.get('https://example.com/api');
  assert.strictEqual(response.status, 200);
  assert.deepStrictEqual(response.data, { ok: true });
});

test('lodash 兜底可用，缺的方法报明确错误', () => {
  const lodash = shim.createLodashShim();
  assert.deepStrictEqual(lodash.map([1, 2], (item) => item * 2), [2, 4]);
  assert.strictEqual(lodash.get({ a: { b: 3 } }, 'a.b'), 3);
  assert.throws(() => lodash.chunk([1, 2], 1), (error) => {
    assert.strictEqual(error.code, 'NOT_SUPPORTED');
    assert.ok(/chunk/.test(error.message));
    return true;
  });
});

test('uuid 兜底产出合法 v4', () => {
  const uuid = shim.createUuidShim();
  const value = uuid.v4();
  assert.ok(uuid.validate(value), value);
  assert.strictEqual(uuid.version(value), 4);
  const bare = uuid();
  assert.strictEqual(typeof bare, 'string');
  assert.ok(uuid.validate(bare), bare);
});

/**
 * 读一个真实 v2 工具包（manifest.json + main 入口），供 OPERIT_V2_PKG 用例使用。
 */
function loadV2Package(packageDir) {
  const fs = require('fs');
  const manifestPath = path.join(packageDir, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const entry = path.join(packageDir, manifest.main || 'dist/main.js');
  delete require.cache[require.resolve(entry)];
  return { manifest, module: require(entry) };
}

test('registerApi 落进 serve 的同一张表，tool.invoke 直接命中', async () => {
  const { host, runtime } = createHarness({});
  runtime.ToolPkg.registerApi({ name: 'getAll', function: () => ({ success: true, data: [1, 2] }) });
  runtime.serve();
  await host.deliver('tool.invoke', { handler: 'getAll', args: {} });
  assert.deepStrictEqual(host.lastResult().result, { success: true, data: [1, 2] });
});

test('registerApi 同名覆盖并记一条 warn，缺 name/function 只 warn 不抛错', async () => {
  const { host, runtime } = createHarness({});
  runtime.ToolPkg.registerApi({ name: 'dup', function: () => 'first' });
  runtime.ToolPkg.registerApi({ name: 'dup', function: () => 'second' });
  assert.strictEqual(await runtime.getHandler('dup')(), 'second');
  assert.strictEqual(runtime.ToolPkg.registerApi(), false);
  assert.strictEqual(runtime.ToolPkg.registerApi({ name: 'no-fn' }), false);
  const warns = host.calls.filter((call) => call.method === 'host.log' && call.params.level === 'warn');
  assert.ok(warns.some((call) => /registerApi 覆盖/.test(call.params.message)), JSON.stringify(warns));
});

test('v2 注册函数对 undefined / 非对象一律不抛错', async () => {
  const { runtime } = createHarness({});
  const names = [
    'registerApi', 'registerUiRoute', 'registerNavigationEntry', 'registerToolboxUiModule',
    'registerCoreCommand', 'registerHostEventHook', 'registerManifestExtension', 'registerAppLifecycleHook',
    'registerChatLifecycleHook', 'registerChatResponseHook', 'registerChatComposerSlot',
    'registerToolPermissionHook', 'registerChatViewHook', 'registerToolLifecycleHook',
    'registerPromptInputHook', 'registerToolPromptComposeHook'
  ];
  names.forEach((name) => {
    assert.strictEqual(typeof runtime.ToolPkg[name], 'function', '缺 ToolPkg.' + name);
    [undefined, null, 'text', 42, []].forEach((value) => {
      assert.doesNotThrow(() => runtime.ToolPkg[name](value));
    });
  });
});

test('ipc.on 与 ipc.call 同进程配对，未注册话题明确报错', async () => {
  const { runtime } = createHarness({});
  assert.strictEqual(runtime.ToolPkg.ipc.on('demo.service', (payload) => ({ echo: payload })), true);
  assert.strictEqual(runtime.ToolPkg.ipc.on('', () => undefined), false);
  assert.deepStrictEqual(await runtime.ToolPkg.ipc.call('demo.service', { a: 1 }), { echo: { a: 1 } });
  await assert.rejects(() => runtime.ToolPkg.ipc.call('demo.missing'), (error) => {
    assert.strictEqual(error.code, 'IPC_CHANNEL_MISSING');
    return true;
  });
});

test('ToolPkg.route 是恒等装饰器工厂（v2 类定义阶段就会调用）', () => {
  const { runtime } = createHarness({});
  const decorator = runtime.ToolPkg.route({ binding: 0 });
  assert.strictEqual(typeof decorator, 'function');
  const method = function method() {};
  assert.strictEqual(decorator(method), method);
  assert.strictEqual(decorator('value'), 'value');
});

test('PluginConfig.use/flush 按名字读写插件配置目录的 JSON', async () => {
  const fs = require('fs');
  const os = require('os');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'aharou-plugin-config-'));
  const host = createFakeHost({});
  const runtime = shim.createRuntime({ transport: host.transport, pluginId: 'com.example.cfg', configDir: dir });
  const config = runtime.globals.PluginConfig;
  const settings = await config.use('settings', { enabled: false, limit: 3 });
  assert.deepStrictEqual({ ...settings }, { enabled: false, limit: 3 });
  settings.enabled = true;
  settings.limit = 5;
  await config.flush(settings);
  assert.deepStrictEqual(JSON.parse(fs.readFileSync(path.join(dir, 'settings.json'), 'utf8')), {
    enabled: true,
    limit: 5
  });
  const reloaded = await config.use('settings', { enabled: false, limit: 3 });
  assert.strictEqual(reloaded.enabled, true);
  assert.strictEqual(reloaded.limit, 5);
});

if (process.env.OPERIT_V2_PKG) {
  test('真实 v2 包：require 后 registerToolPkg 注册出 registerApi 声明的 api', async () => {
    const packageDir = path.resolve(process.env.OPERIT_V2_PKG);
    const { host, runtime } = createHarness({});
    // installGlobals 会把 globalThis.console 接到宿主日志，整个用例结束前先存一份回来
    const realConsole = globalThis.console;
    try {
      // 与适配层同序：先装全局（ToolPkg / Tools），再 require 插件入口
      shim.installGlobals(runtime);
      runtime.serve();
      const { module: entry, manifest } = loadV2Package(packageDir);
      assert.strictEqual(typeof entry.registerToolPkg, 'function', manifest.toolpkg_id + ' 没有 registerToolPkg');
      assert.strictEqual(entry.registerToolPkg(), true);
      const expected = (process.env.OPERIT_V2_EXPECT || '').split(',').filter(Boolean);
      const missing = expected.filter((name) => typeof runtime.getHandler(name) !== 'function');
      assert.deepStrictEqual(missing, [], manifest.toolpkg_id + ' 未注册: ' + missing.join(','));
      assert.ok(runtime.listUiRoutes().length > 0, manifest.toolpkg_id + ' 没有登记任何 UI 路由');

      // 模拟适配层：boot 后拍 registerApi 快照，再 serve 适配层自己的包装；
      // 宿主按工具 id 调用时能走到 registerApi 注册的函数，而不是 HANDLER_NOT_FOUND
      const snapshot = new Map(runtime.handlerNames().map((name) => [name, runtime.getHandler(name)]));
      runtime.serve({ echoApi: (args) => snapshot.get('getAll')(args) });
      await host.deliver('tool.invoke', { handler: 'echoApi', args: {} });
      assert.notStrictEqual(host.lastResult().error && host.lastResult().error.code, 'HANDLER_NOT_FOUND');
    } finally {
      globalThis.console = realConsole;
    }
  });
}

(async () => {
  let failed = 0;
  for (const item of tests) {
    try {
      await item.fn();
      console.log('PASS  ' + item.name);
    } catch (error) {
      failed += 1;
      console.log('FAIL  ' + item.name);
      console.log('      ' + (error && error.stack ? error.stack.split('\n').slice(0, 3).join('\n      ') : error));
    }
  }
  console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed' + (failed ? '，' + failed + ' failed' : ''));
  process.exit(failed ? 1 : 0);
})();
