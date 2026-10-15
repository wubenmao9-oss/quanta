/**
 * store.js —— 集中配置（所有可调参数都在这里，禁止在业务代码里硬编码）
 * 依赖：vendor/vue.global.prod.js（先于本文件加载，可用 Vue.reactive）
 */
(function (global) {
  'use strict';

  // ==================== 可调常量 ====================
  var QuantaConfig = {
    apiBase: '',                 // 同源（生产直连 8080；预览走反代 8099，均为相对路径）

    poll: {                      // 轮询间隔（ms），集中可调
      klineMs: 1000,
      quoteMs: 1000,
      accountMs: 3000,
      positionsMs: 2000,
      topbarMs: 5000,
      decisionsMs: 8000,
    },

    kline: {
      symbol: 'GOLD',
      defaultTimeframe: 'M5',
      defaultCount: 300,
      timeframes: ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1'],
      candleWidth: 7,
      candleGap: 2,
      minCandleWidth: 2,
      maxCandleWidth: 28,
      rightPad: 72,              // 右侧留白（价格轴）
      topPad: 12,
      bottomPad: 26,             // 底部时间轴
      axisFont: 10,
      axisFontFamily: "'JetBrains Mono','SFMono-Regular',Consolas,monospace",
      gridColor: 'rgba(255,255,255,.05)',
      axisTextColor: '#64748B',
      crosshairColor: 'rgba(148,163,184,.55)',
      maPeriods: [9, 21, 50],
      maColors: ['#22D3EE', '#F59E0B', '#A78BFA'],
    },

    colors: {
      up: '#22C55E',
      down: '#F43F5E',
      warn: '#F59E0B',
      accent: '#3B82F6',
      accent2: '#22D3EE',
      text: '#E5EAF3',
      textDim: '#94A3B8',
      textMuted: '#64748B',
      panel: '#111827',
      panel2: '#141B2D',
      bg: '#0A0F1A',
      border: 'rgba(255,255,255,.06)',
    },

    defaults: {                  // 新建交易默认值
      volume: 0.01,
      slUsd: 2.5,                // 相对现价的止损美元数
      tpUsd: 3.0,
      tpMode: 'PRICE',           // 后端 tpMode 兼容取值：PRICE / MONEY 等，见 UI 下拉
    },

    limits: {
      reportsLimit: 200,
      decisionsLimit: 200,
      memoryLimit: 100,
      minSample: 100,            // 策略门禁最小样本量（笔）
      backtestSpreads: [0, 10, 20, 30, 40],
      backtestSlippages: [0],
      klineCountOptions: [100, 200, 300, 500],
    },

    // 触发源中文名（T1~T8），未知的走原样兜底
    triggerLabels: {
      T1: '计划触发', T2: '定时窗口', T3: '盯盘信号', T4: '持仓事件',
      T5: '止损止盈', T6: '人工唤醒', T7: '系统事件', T8: '复盘学习',
    },
  };

  // ==================== 全局响应式状态 ====================
  var QuantaState = Vue.reactive({
    route: 'positions',
    clock: '',
    runMode: 'AUTO',
    degraded: false,
    tokenUsedToday: 0,
    tokenDate: '',
    bridge: { mt5Alive: false, checked: false },
    account: null,
    quote: null,
    connected: false,            // 最近一次顶栏轮询是否成功
    toasts: [],
    // 视图间共享的 K 线上下文（positions 页使用）
    kline: { symbol: QuantaConfig.kline.symbol, timeframe: QuantaConfig.kline.defaultTimeframe },
  });

  // ==================== 工具函数 ====================
  var QuantaUtil = {
    fmtNum: function (v, digits) {
      if (v === null || v === undefined || v === '' || isNaN(v)) return '-';
      var n = Number(v);
      digits = digits === undefined ? 2 : digits;
      return n.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
    },
    fmtMoney: function (v, digits) {
      if (v === null || v === undefined || isNaN(v)) return '-';
      var n = Number(v);
      digits = digits === undefined ? 2 : digits;
      var s = n.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
      return (n > 0 ? '+' : '') + s;
    },
    fmtPrice: function (v, digits) {
      if (v === null || v === undefined || isNaN(v)) return '-';
      return Number(v).toFixed(digits === undefined ? 2 : digits);
    },
    signClass: function (v) {
      if (v === null || v === undefined || isNaN(v)) return '';
      var n = Number(v);
      return n > 0 ? 'pos' : (n < 0 ? 'neg' : '');
    },
    // 后端 LocalDateTime -> "MM-DD HH:mm:ss"
    fmtTime: function (s) {
      if (!s) return '-';
      var d = parseDate(s);
      if (!d) return String(s);
      return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' +
        pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    },
    fmtTimeShort: function (s) {
      if (!s) return '-';
      var d = parseDate(s);
      if (!d) return String(s);
      return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    },
    fmtDateTimeFull: function (s) {
      if (!s) return '-';
      var d = parseDate(s);
      if (!d) return String(s);
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' +
        pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    },
    parseDate: parseDate,
    // Unix 秒 -> 本地时间字符串
    fmtBarTime: function (epochSec, pattern) {
      if (!epochSec) return '-';
      var d = new Date(epochSec * 1000);
      if (pattern === 'axis') {
        return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
      }
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' +
        pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    },
    // 安全 JSON 解析（用于 indicatorParams / actionParams / inputSnapshot 等 JSON 串）
    safeParse: function (s, fallback) {
      if (s === null || s === undefined || s === '') return fallback === undefined ? null : fallback;
      if (typeof s === 'object') return s;
      try { return JSON.parse(s); } catch (e) { return fallback === undefined ? null : fallback; }
    },
    prettyJSON: function (v) {
      try { return JSON.stringify(typeof v === 'string' ? JSON.parse(v) : v, null, 2); }
      catch (e) { return String(v); }
    },
    // 秒 -> 可读时长
    fmtDuration: function (sec) {
      if (sec === null || sec === undefined || isNaN(sec)) return '-';
      sec = Math.round(Number(sec));
      if (sec < 60) return sec + 's';
      if (sec < 3600) return Math.floor(sec / 60) + 'm' + (sec % 60 ? (sec % 60) + 's' : '');
      return Math.floor(sec / 3600) + 'h' + Math.floor((sec % 3600) / 60) + 'm';
    },
    // 今天是 YYYY-MM-DD
    today: function () { var d = new Date(); return isoDate(d); },
    daysAgo: function (n) { var d = new Date(); d.setDate(d.getDate() - n); return isoDate(d); },
    isoDate: isoDate,
    clamp: function (v, lo, hi) { return Math.max(lo, Math.min(hi, v)); },
    // 生成满足 @DateTimeFormat(ISO.DATE_TIME) 的字符串：YYYY-MM-DDTHH:mm:ss
    isoDateTime: function (dateStr, endOfDay) {
      if (!dateStr) return null;
      return dateStr + (endOfDay ? 'T23:59:59' : 'T00:00:00');
    },
  };

  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function isoDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function parseDate(s) {
    if (s instanceof Date) return s;
    if (typeof s !== 'string') return null;
    // 兼容 "2026-10-14T13:17:21" 与 "2026-10-14 13:17:21"
    var iso = s.replace(' ', 'T');
    var d = new Date(iso);
    return isNaN(d.getTime()) ? null : d;
  }

  // ==================== Toast ====================
  var QuantaToast = {
    push: function (type, message, traceId) {
      var id = Date.now() + Math.random();
      QuantaState.toasts.push({ id: id, type: type, message: message, traceId: traceId || null });
      setTimeout(function () { QuantaToast.remove(id); }, type === 'error' ? 8000 : 4200);
      return id;
    },
    remove: function (id) {
      var i = QuantaState.toasts.findIndex(function (t) { return t.id === id; });
      if (i >= 0) QuantaState.toasts.splice(i, 1);
    },
    ok: function (m, t) { return QuantaToast.push('ok', m, t); },
    err: function (m, t) { return QuantaToast.push('error', m, t); },
    warn: function (m, t) { return QuantaToast.push('warn', m, t); },
    info: function (m, t) { return QuantaToast.push('info', m, t); },
  };

  global.QuantaConfig = QuantaConfig;
  global.QuantaState = QuantaState;
  global.QuantaUtil = QuantaUtil;
  global.QuantaToast = QuantaToast;
})(window);
