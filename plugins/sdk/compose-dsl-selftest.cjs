'use strict';

/**
 * Compose DSL 运行时的自测：直接对 compose-dsl-runtime.js 与 operit-shim.js 的
 * `registerUiRoute` / `ui.render` / `ui.dispatch` 链路跑一遍，用假宿主桩代替 stdio 通道。
 *
 * 跑法：node plugins/sdk/compose-dsl-selftest.cjs
 * 只覆盖进程内的树生成与动作回放；真实宿主的 Compose 渲染不在本脚本范围。
 */

const path = require('path');
const assert = require('assert');

const PLUGIN_UI_DIR = path.resolve(__dirname, '../../app/src/main/assets/plugin-ui');
const runtimeModule = require(path.join(PLUGIN_UI_DIR, 'compose-dsl-runtime.js'));
const shim = require(path.join(PLUGIN_UI_DIR, 'operit-shim.js'));

function createFakeRuntime(extra) {
  const calls = [];
  return {
    calls,
    toolCall: async (method, params) => {
      calls.push({ method, params });
      if (method === 'tools.execute') return { ok: true, tool: params.name };
      if (method === 'log.write') return { ok: true };
      return {};
    },
    refreshEnv: async () => ({}),
    Tools: {},
    ...(extra || {})
  };
}

function createRouteTable(options) {
  const fake = createFakeRuntime();
  const table = runtimeModule.createRouteTable({
    runtime: fake,
    env: { DEMO: 'demo' },
    pluginId: 'demo.plugin',
    ...(options || {})
  });
  return { table, fake };
}

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
    },
    lastOf(method) {
      for (let index = results.length - 1; index >= 0; index -= 1) {
        const entry = results[index];
        if (entry.notify === method) return entry;
      }
      return null;
    }
  };
}

function createShimHarness() {
  const host = createFakeHost({
    'host.log': () => null
  });
  const runtime = shim.createRuntime({
    transport: host.transport,
    env: { DEMO: 'demo' },
    pluginId: 'com.example.compose',
    configDir: '/root/.aharou/plugins/data/com.example.compose'
  });
  runtime.serve();
  return { host, runtime };
}

/** 一个最小的真实 Screen：Column / Text / Button，按钮改 state。 */
function demoScreen(ctx) {
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [count, setCount] = ctx.useState('count', 0);
  return UI.Column({ spacing: 8, modifier: ctx.Modifier.padding(4) }, [
    UI.Text({ text: '你好 ' + count, color: colors.primary }),
    UI.Button({ onClick: () => setCount(count + 1) }, UI.Text({ text: '加一' }))
  ]);
}

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

test('Column/Text/Button 渲染出正确的节点树', async () => {
  const { table } = createRouteTable();
  table.register({ id: 'demo', runtime: 'compose_dsl', screen: demoScreen });
  const out = await table.render('demo');

  assert.strictEqual(out.tree.__composeNode, true);
  assert.strictEqual(out.tree.type, 'Column');
  assert.strictEqual(out.tree.props.spacing, 8);
  assert.deepStrictEqual(out.tree.props.modifier, {
    __modifierOps: [{ name: 'padding', args: [4] }]
  });
  assert.strictEqual(out.tree.children.length, 2);

  const label = out.tree.children[0];
  assert.strictEqual(label.type, 'Text');
  assert.strictEqual(label.props.text, '你好 0');
  assert.deepStrictEqual(label.props.color, { __colorToken: 'primary', alpha: undefined });

  const button = out.tree.children[1];
  assert.strictEqual(button.type, 'Button');
  assert.deepStrictEqual(button.props.onClick, { __actionId: 'action_1' });
  assert.strictEqual(button.children[0].type, 'Text');
  assert.strictEqual(button.children[0].props.text, '加一');

  assert.deepStrictEqual(out.state, { count: 0 });
  assert.strictEqual(out.route.id, 'demo');
});

test('点击按钮 → dispatch → state 变化 → 重渲染后文本变了', async () => {
  const { table } = createRouteTable();
  table.register({ id: 'demo', runtime: 'compose_dsl', screen: demoScreen });
  const first = await table.render('demo');
  const actionId = first.tree.children[1].props.onClick.__actionId;

  const second = await table.dispatch({ routeId: 'demo', actionId });
  assert.strictEqual(second.state.count, 1);
  assert.strictEqual(second.tree.children[0].props.text, '你好 1');
  // action id 按渲染顺序重建，宿主手里的新旧树 id 对得上
  assert.strictEqual(second.tree.children[1].props.onClick.__actionId, 'action_1');

  const third = await table.dispatch({ routeId: 'demo', actionId: 'action_1' });
  assert.strictEqual(third.tree.children[0].props.text, '你好 2');
});

test('dispatch 支持用 route 全名定位', async () => {
  const { table } = createRouteTable();
  table.register({
    id: 'named',
    route: 'toolpkg:com.example.compose:ui:named',
    runtime: 'compose_dsl',
    screen: demoScreen
  });
  const first = await table.render('toolpkg:com.example.compose:ui:named');
  const actionId = first.tree.children[1].props.onClick.__actionId;
  const second = await table.dispatch({
    route: 'toolpkg:com.example.compose:ui:named',
    actionId
  });
  assert.deepStrictEqual(second.state, { count: 1 });
  assert.strictEqual(second.route.id, 'named');
});

test('未知节点类型也能产出节点，不抛错', async () => {
  const { table } = createRouteTable();
  table.register({
    id: 'unknown',
    runtime: 'compose_dsl',
    screen: (ctx) => ctx.UI.NotARealThing({ a: 1 }, ctx.UI.Text({ text: 'inside' }))
  });
  const out = await table.render('unknown');
  assert.strictEqual(out.tree.type, 'NotARealThing');
  assert.strictEqual(out.tree.props.a, 1);
  assert.strictEqual(out.tree.children[0].type, 'Text');
});

test('props 里的节点挪进 slots（AlertDialog 的 text）', async () => {
  const { table } = createRouteTable();
  table.register({
    id: 'dialog',
    runtime: 'compose_dsl',
    screen: (ctx) =>
      ctx.UI.AlertDialog({
        title: '标题',
        text: ctx.UI.LazyColumn({ spacing: 4 }, [ctx.UI.Text({ text: '正文' })]),
        onConfirm: () => undefined
      })
  });
  const out = await table.render('dialog');
  assert.strictEqual(out.tree.props.title, '标题');
  assert.strictEqual(out.tree.props.text, undefined);
  assert.strictEqual(out.tree.slots.text[0].type, 'LazyColumn');
  assert.deepStrictEqual(out.tree.props.onConfirm, { __actionId: 'action_1' });
});

test('Modifier 链、dp 单位与颜色 token', async () => {
  const { table } = createRouteTable();
  table.register({
    id: 'modifier',
    runtime: 'compose_dsl',
    screen: (ctx) => {
      const onClick = () => undefined;
      return ctx.UI.Box({
        modifier: ctx.Modifier.fillMaxWidth().clickable(onClick).toJSON(),
        width: 200,
        padding: (4).dp,
        color: ctx.MaterialTheme.colorScheme.surface.copy({ alpha: 0.2 })
      });
    }
  });
  const out = await table.render('modifier');
  assert.deepStrictEqual(out.tree.props.modifier.__modifierOps[0], { name: 'fillMaxWidth', args: [] });
  assert.deepStrictEqual(out.tree.props.modifier.__modifierOps[1], {
    name: 'clickable',
    args: [{ __actionId: 'action_1' }]
  });
  assert.deepStrictEqual(out.tree.props.padding, { __unit: 'dp', value: 4 });
  assert.deepStrictEqual(out.tree.props.color, { __colorToken: 'surface', alpha: 0.2 });
});

test('宿主带回 state 时按合并语义恢复', async () => {
  const { table } = createRouteTable();
  table.register({ id: 'demo', runtime: 'compose_dsl', screen: demoScreen });
  const out = await table.render('demo', { state: { count: 5 } });
  assert.strictEqual(out.tree.children[0].props.text, '你好 5');
  assert.deepStrictEqual(out.state, { count: 5 });
});

test('热实例上宿主回传的旧 state 不覆盖 JS 刚改的值；replaceState 才整份替换', async () => {
  const { table } = createRouteTable();
  table.register({ id: 'demo', runtime: 'compose_dsl', screen: demoScreen });
  await table.render('demo');
  await table.dispatch({ routeId: 'demo', actionId: 'action_1' });
  const stale = await table.dispatch({ routeId: 'demo', actionId: 'action_1', state: { count: 0 } });
  assert.deepStrictEqual(stale.state, { count: 2 });

  const restored = await table.render('demo', { state: { count: 9 }, replaceState: true });
  assert.deepStrictEqual(restored.state, { count: 9 });
});

test('async Screen 与 async 动作都能跑完', async () => {
  const { table } = createRouteTable();
  table.register({
    id: 'async',
    runtime: 'compose_dsl',
    screen: async (ctx) => {
      await Promise.resolve();
      const [saved, setSaved] = ctx.useState('saved', false);
      return ctx.UI.Button(
        {
          onClick: async () => {
            await Promise.resolve();
            setSaved(true);
          }
        },
        ctx.UI.Text({ text: saved ? '已保存' : '未保存' })
      );
    }
  });
  const first = await table.render('async');
  assert.strictEqual(first.tree.children[0].props.text, '未保存');
  const second = await table.dispatch({ routeId: 'async', actionId: 'action_1' });
  assert.strictEqual(second.tree.children[0].props.text, '已保存');
});

test('未知 actionId 报 ACTION_NOT_FOUND', async () => {
  const { table } = createRouteTable();
  table.register({ id: 'demo', runtime: 'compose_dsl', screen: demoScreen });
  await table.render('demo');
  await assert.rejects(() => table.dispatch({ routeId: 'demo', actionId: 'action_99' }), (error) => {
    assert.strictEqual(error.code, 'ACTION_NOT_FOUND');
    return true;
  });
});

test('未支持的 ctx 能力明确报 NOT_SUPPORTED，不伪造成功', async () => {
  const { table } = createRouteTable();
  table.register({
    id: 'gaps',
    runtime: 'compose_dsl',
    screen: (ctx) => {
      const failures = [];
      try {
        ctx.measureText({ text: 'x' });
      } catch (error) {
        failures.push(error.code);
      }
      try {
        ctx.createWebViewController('wv');
      } catch (error) {
        failures.push(error.code);
      }
      return ctx.UI.Column({}, [ctx.UI.Text({ text: failures.join(',') })]);
    }
  });
  const out = await table.render('gaps');
  assert.strictEqual(out.tree.children[0].props.text, 'NOT_SUPPORTED,NOT_SUPPORTED');

  table.register({ id: 'picker', runtime: 'compose_dsl', screen: () => null });
  await assert.rejects(() => table.render('picker'), (error) => error.code === 'RENDER_EMPTY');
});

test('shim: registerUiRoute 记为已支持，且不再进 unsupported 清单', async () => {
  const { runtime, host } = createShimHarness();
  runtime.ToolPkg.registerUiRoute({
    id: 'demo2',
    route: 'toolpkg:com.example.compose:ui:demo2',
    runtime: 'compose_dsl',
    screen: demoScreen,
    title: { zh: '演示' }
  });
  runtime.ToolPkg.registerUiRoute({ id: 'vue', runtime: 'vue', screen: 'ui/vue.js' });
  runtime.ToolPkg.registerNavigationEntry({ id: 'nav' });

  const routes = runtime.listUiRoutes();
  assert.strictEqual(routes.length, 1);
  assert.strictEqual(routes[0].id, 'demo2');
  assert.strictEqual(routes[0].screen, 'function');

  const unsupported = runtime.takeUnsupportedRegistrations();
  assert.deepStrictEqual(
    unsupported.map((entry) => entry.api).sort(),
    ['registerNavigationEntry', 'registerUiRoute']
  );
  const vueEntry = unsupported.find((entry) => entry.id === 'vue');
  assert.strictEqual(vueEntry.ok, false);
  assert.match(vueEntry.reason, /compose_dsl/);

  const notice = host.lastOf('plugin.event');
  assert.strictEqual(notice.params.event, 'ui.routes');
  assert.strictEqual(notice.params.routes.length, 1);
});

test('shim: ui.routes / ui.render / ui.dispatch 走 ui.invoke', async () => {
  const { runtime, host } = createShimHarness();
  runtime.ToolPkg.registerUiRoute({
    id: 'demo3',
    route: 'toolpkg:com.example.compose:ui:demo3',
    runtime: 'compose_dsl',
    screen: demoScreen
  });

  await host.deliver('ui.invoke', { handler: 'ui.routes', args: {} });
  const listed = host.lastResult().result;
  assert.strictEqual(listed.routes.length, 1);
  assert.strictEqual(listed.routes[0].route, 'toolpkg:com.example.compose:ui:demo3');

  await host.deliver('ui.invoke', { handler: 'ui.render', args: { routeId: 'demo3' } });
  const rendered = host.lastResult().result;
  assert.strictEqual(rendered.tree.type, 'Column');
  assert.strictEqual(rendered.tree.children[0].props.text, '你好 0');
  assert.deepStrictEqual(rendered.state, { count: 0 });
  assert.deepStrictEqual(rendered.memo, {});

  const actionId = rendered.tree.children[1].props.onClick.__actionId;
  await host.deliver('ui.invoke', { handler: 'ui.dispatch', args: { routeId: 'demo3', actionId } });
  const dispatched = host.lastResult().result;
  assert.strictEqual(dispatched.tree.children[0].props.text, '你好 1');
  assert.deepStrictEqual(dispatched.state, { count: 1 });

  await host.deliver('ui.invoke', { handler: 'ui.dispatch', args: { routeId: 'demo3', actionId: 'action_9' } });
  assert.strictEqual(host.lastResult().error.code, 'ACTION_NOT_FOUND');
});

test('shim: 插件自己注册同名 handler 时以插件为准', async () => {
  const { runtime, host } = createShimHarness();
  runtime.registerHandler('ui.render', () => ({ tree: { type: 'Custom' } }));
  await host.deliver('ui.invoke', { handler: 'ui.render', args: {} });
  assert.deepStrictEqual(host.lastResult().result, { tree: { type: 'Custom' } });
});

(async () => {
  let failed = 0;
  for (const item of tests) {
    try {
      await item.fn();
      console.log('PASS ' + item.name);
    } catch (error) {
      failed += 1;
      console.log('FAIL ' + item.name);
      console.log('     ' + (error && error.stack ? error.stack : String(error)));
    }
  }
  console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
  if (failed > 0) process.exitCode = 1;
})();
