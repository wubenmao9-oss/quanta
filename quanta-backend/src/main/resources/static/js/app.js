/**
 * app.js —— 应用入口：hash 路由 + 侧栏 + 顶栏（全局轮询）+ Toast + 运行模式切换
 */
(function (global) {
  'use strict';

  var API = global.QuantaAPI;
  var State = global.QuantaState;
  var U = global.QuantaUtil;
  var CFG = global.QuantaConfig;

  var VIEW_META = [
    { key: 'positions', label: '持仓 · K线', title: '持仓与实时行情', sub: 'P0 交易主屏',
      icon: '<path d="M3 17l5-6 4 3 5-7 4 5"/>' },
    { key: 'chat', label: 'AI 对话', title: 'AI 决策会话', sub: '研判与动作轨迹',
      icon: '<path d="M21 12a8 8 0 0 1-8 8H7l-4 3v-7a8 8 0 0 1 8-8h2a8 8 0 0 1 8 4z"/>' },
    { key: 'history', label: '历史交易', title: '历史交易复盘', sub: '净值与盈亏统计',
      icon: '<path d="M3 3v18h18"/><path d="M7 15l4-5 3 3 5-7"/>' },
    { key: 'strategy', label: '策略 · 回测', title: '策略库与回测', sub: '门禁验收与点差扫描',
      icon: '<path d="M12 2l3 6 6 1-4.5 4 1 6-5.5-3-5.5 3 1-6L3 9l6-1z"/>' },
    { key: 'schedule', label: '定时任务', title: '调度与触发', sub: '盯盘状态与唤醒',
      icon: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>' },
    { key: 'config', label: '配置', title: '连接配置', sub: 'MT5 / LLM / MAIL',
      icon: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.14.36.5.62.9.65H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>' },
  ];

  function titleOf(route) {
    for (var i = 0; i < VIEW_META.length; i++) if (VIEW_META[i].key === route) return VIEW_META[i];
    return VIEW_META[0];
  }

  // ==================== 路由 ====================
  function parseHash() {
    var h = (location.hash || '').replace(/^#\/?/, '');
    var key = h.split('/')[0] || 'positions';
    var valid = false;
    for (var i = 0; i < VIEW_META.length; i++) if (VIEW_META[i].key === key) valid = true;
    return valid ? key : 'positions';
  }

  function goto(route) {
    if (location.hash === '#/' + route) { State.route = route; return; }
    location.hash = '#/' + route;
  }

  // ==================== 顶栏轮询 ====================
  var topbarTimer = null;
  function topbarTick() {
    Promise.all([
      API.opsState().catch(function () { return null; }),
      API.mt5Account().catch(function () { return null; }),
      API.mt5Health().catch(function () { return null; }),
    ]).then(function (res) {
      var ops = res[0], acct = res[1], health = res[2];
      State.connected = !!(ops || acct);
      if (ops) {
        State.runMode = ops.runMode || 'AUTO';
        State.degraded = !!ops.degraded;
        State.tokenUsedToday = ops.tokenUsedToday || 0;
        State.tokenDate = ops.tokenDate || '';
      }
      if (acct) State.account = acct;
      State.bridge.mt5Alive = !!(health && (health.ok === true || health.terminalConnected === true ||
        health.status === 'ok' || (typeof health === 'object' && Object.keys(health).length > 0)));
      State.bridge.checked = true;
    });
  }

  function clockTick() { State.clock = new Date().toISOString(); }

  // ==================== 根组件 ====================
  var Root = {
    template: [
      '<div class="shell">',
      // ---- 侧栏 ----
      '  <aside class="sidebar">',
      '    <div class="brand">',
      '      <div class="brand-mark"></div>',
      '      <div><div class="brand-name">quanta</div><div class="brand-sub">Trading Terminal</div></div>',
      '    </div>',
      '    <nav class="nav">',
      '      <div class="nav-group-label">工作台</div>',
      '      <div v-for="m in meta" :key="m.key" class="nav-item" :class="{active: state.route===m.key}" @click="go(m.key)">',
      '        <svg class="nav-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" v-html="m.icon"></svg>',
      '        <span class="nav-label">{{ m.label }}</span>',
      '      </div>',
      '    </nav>',
      '    <div class="sidebar-foot">',
      '      <div class="flex ai-c gap-8"><span class="dot" :class="state.connected?\'on\':\'off\'"></span>{{ state.connected ? \'后端在线\' : \'后端离线\' }}</div>',
      '      <div class="flex ai-c gap-8"><span class="dot" :class="state.bridge.mt5Alive?\'on\':\'warn\'"></span>{{ state.bridge.mt5Alive ? \'MT5 桥接在线\' : \'MT5 桥接未知\' }}</div>',
      '      <div class="muted" style="margin-top:4px">v0.3 · GOLD 一期</div>',
      '    </div>',
      '  </aside>',
      // ---- 主区 ----
      '  <div class="main-area">',
      '    <header class="topbar">',
      '      <div class="topbar-title">{{ metaTitle.title }}<small>{{ metaTitle.sub }}</small></div>',
      '      <div class="topbar-spacer"></div>',
      '      <div class="mode-chip" :class="state.runMode" @click="openMode">',
      '        <span class="mode-dot"></span>{{ modeLabel }}',
      '      </div>',
      '      <div class="topstat"><span class="topstat-label">净值 EQUITY</span>',
      '        <span class="topstat-value" :class="pnlClass(equity)">{{ U.fmtNum(equity) }}</span></div>',
      '      <div class="topstat"><span class="topstat-label">浮动盈亏</span>',
      '        <span class="topstat-value" :class="pnlClass(acctProfit)">{{ U.fmtMoney(acctProfit) }}</span></div>',
      '      <div class="topstat"><span class="topstat-label">今日 TOKEN</span>',
      '        <span class="topstat-value">{{ U.fmtNum(state.tokenUsedToday,0) }}</span></div>',
      '      <div class="topstat"><span class="topstat-label">本地时间</span>',
      '        <span class="topstat-value">{{ U.fmtTimeShort(state.clock) }}</span></div>',
      '    </header>',
      '    <div class="view-wrap">',
      '      <component :is="currentView" :key="state.route"></component>',
      '    </div>',
      '  </div>',
      // ---- 模式切换 ----
      '  <div class="overlay" v-if="modeOpen" @click.self="modeOpen=false">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">切换运行模式</div></div>',
      '      <div class="modal-body">',
      '        <div class="hint" style="margin-bottom:12px">运行模式决定系统是否自动研判与下单（写操作都会落审计并通知会话）。</div>',
      '        <div v-for="m in modes" :key="m.key" class="list-item" :class="{on: state.runMode===m.key}" @click="setMode(m.key)">',
      '          <div class="list-item-title"><span class="dot" :class="m.dot"></span>{{ m.label }}</div>',
      '          <div class="list-item-meta">{{ m.desc }}</div>',
      '        </div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="modeOpen=false">关闭</button></div>',
      '    </div>',
      '  </div>',
      // ---- Toast ----
      '  <div class="toast-wrap">',
      '    <div v-for="t in state.toasts" :key="t.id" class="toast" :class="t.type">',
      '      <div class="toast-msg">{{ t.message }}',
      '        <div v-if="t.traceId" class="toast-trace">trace: {{ t.traceId }}</div>',
      '      </div>',
      '      <div class="toast-x" @click="closeToast(t.id)">×</div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return { meta: VIEW_META, modeOpen: false, U: U,
        modes: [
          { key: 'AUTO', label: 'AUTO · 自动', desc: '系统按触发源自动研判与下单', dot: 'on' },
          { key: 'PAUSED', label: 'PAUSED · 暂停', desc: '只采集与研判信号，不自动下单', dot: 'warn' },
          { key: 'TAKEOVER', label: 'TAKEOVER · 接管', desc: '仅人工操作，停用自动写入', dot: 'off' },
        ] };
    },

    computed: {
      state: function () { return State; },
      metaTitle: function () { return titleOf(State.route); },
      currentView: function () {
        var v = global.QuantaViews && global.QuantaViews[State.route];
        return v || (global.QuantaViews && global.QuantaViews.positions);
      },
      modeLabel: function () {
        return { AUTO: 'AUTO 自动', PAUSED: 'PAUSED 暂停', TAKEOVER: 'TAKEOVER 接管' }[State.runMode] || State.runMode;
      },
      equity: function () { return State.account ? State.account.equity : null; },
      acctProfit: function () { return State.account ? State.account.profit : null; },
    },

    methods: {
      go: function (r) { goto(r); },
      openMode: function () { this.modeOpen = true; },
      setMode: function (mode) {
        var self = this;
        API.opsMode(mode).then(function (s) {
          State.runMode = s.runMode;
          self.modeOpen = false;
          global.QuantaToast.ok('运行模式已切换为 ' + s.runMode);
        }).catch(function (e) { API.fail(e, '切换运行模式失败'); });
      },
      closeToast: function (id) { global.QuantaToast.remove(id); },
      pnlClass: function (v) { return U.signClass(v); },
      onHash: function () { State.route = parseHash(); },
    },

    mounted: function () {
      State.route = parseHash();
      window.addEventListener('hashchange', this.onHash);
      clockTick();
      topbarTick();
      topbarTimer = setInterval(function () { clockTick(); topbarTick(); }, CFG.poll.topbarMs);
    },

    beforeUnmount: function () {
      window.removeEventListener('hashchange', this.onHash);
      if (topbarTimer) clearInterval(topbarTimer);
    },
  };

  function boot() {
    if (!global.Vue) {
      document.getElementById('app').innerHTML =
        '<div class="empty">Vue 运行时未加载：请确认 vendor/vue.global.prod.js 存在。</div>';
      return;
    }
    global.Vue.createApp(Root).mount('#app');
  }

  function boot() {
    if (!global.Vue) {
      document.getElementById('app').innerHTML =
        '<div class="empty">Vue 运行时未加载：请确认 vendor/vue.global.prod.js 存在。</div>';
      return;
    }
    var app = global.Vue.createApp(Root);
    if (global.QuantaAIChat) app.component('quanta-ai-chat', global.QuantaAIChat);
    app.mount('#app');
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window);
