/**
 * api.js —— 统一请求层（防坑②核心）
 *
 * 规则：
 *  1. 所有请求都走 request()，统一解包后端 Result{code,message,data,traceId,timestamp}：
 *     code==0 -> 返回 data；否则抛 Error（message 带 traceId）。
 *  2. 返回的 data 递归做 snake_case -> camelCase 归一化（如 free_margin -> freeMargin），
 *     前端渲染一律只读 camelCase，避免字段错位/空白。
 *  3. 所有接口路径集中在此，页面不得散写 fetch。
 */
(function (global) {
  'use strict';

  var BASE = (global.QuantaConfig && global.QuantaConfig.apiBase) || '';

  // 已知或潜在的 snake_case 键 -> camelCase（递归处理，仅在键名含下划线时转换）
  function toCamel(key) {
    if (key.indexOf('_') === -1) return key;
    return key.replace(/_([a-z0-9])/g, function (_, c) { return c.toUpperCase(); });
  }

  function normalize(value) {
    if (value === null || value === undefined) return value;
    if (Array.isArray(value)) return value.map(normalize);
    if (typeof value === 'object') {
      var out = {};
      for (var k in value) {
        if (!Object.prototype.hasOwnProperty.call(value, k)) continue;
        out[toCamel(k)] = normalize(value[k]);
      }
      return out;
    }
    return value;
  }

  function buildQuery(params) {
    if (!params) return '';
    var parts = [];
    for (var k in params) {
      if (!Object.prototype.hasOwnProperty.call(params, k)) continue;
      var v = params[k];
      if (v === null || v === undefined || v === '') continue;
      parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    }
    return parts.length ? ('?' + parts.join('&')) : '';
  }

  /**
   * 底层请求。options: { method, query, body, signal }
   * 返回：归一化后的 data。
   */
  function request(path, options) {
    options = options || {};
    var url = BASE + path + buildQuery(options.query);
    var init = {
      method: options.method || 'GET',
      headers: { 'Accept': 'application/json' },
      signal: options.signal,
    };
    if (options.body !== undefined && options.body !== null) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(options.body);
    }
    return fetch(url, init).then(function (resp) {
      return resp.text().then(function (text) {
        var payload = null;
        if (text) { try { payload = JSON.parse(text); } catch (e) { payload = null; } }
        if (payload && typeof payload === 'object' && 'code' in payload) {
          if (payload.code === 0) return normalize(payload.data);
          var err = new Error(payload.message || ('请求失败(code=' + payload.code + ')'));
          err.traceId = payload.traceId;
          err.code = payload.code;
          throw err;
        }
        if (!resp.ok) {
          var e2 = new Error('HTTP ' + resp.status + (text ? (' · ' + text.slice(0, 200)) : ''));
          e2.status = resp.status;
          throw e2;
        }
        return normalize(payload);
      });
    });
  }

  var get = function (path, query, signal) { return request(path, { query: query, signal: signal }); };
  var post = function (path, body, query, signal) { return request(path, { method: 'POST', body: body, query: query, signal: signal }); };
  var put = function (path, body, query, signal) { return request(path, { method: 'PUT', body: body, query: query, signal: signal }); };
  var del = function (path, query, signal) { return request(path, { method: 'DELETE', query: query, signal: signal }); };

  // ==================== 运维 / 顶栏 ====================
  var API = {
    request: request,
    normalize: normalize,

    opsState: function (signal) { return get('/api/ops/state', null, signal); },
    opsMode: function (mode) { return post('/api/ops/mode', { mode: mode }); },
    opsReconcile: function () { return post('/api/ops/reconcile', {}); },

    // ==================== MT5 桥接（只读） ====================
    mt5Health: function (signal) { return get('/api/mt5/health', null, signal); },
    mt5Connect: function () { return post('/api/mt5/connect', {}); },
    mt5Account: function (signal) { return get('/api/mt5/account', null, signal); },
    mt5Quote: function (symbol, signal) { return get('/api/mt5/quote', { symbol: symbol }, signal); },
    mt5Klines: function (symbol, timeframe, count, signal) {
      return get('/api/mt5/klines', { symbol: symbol, timeframe: timeframe, count: count }, signal);
    },
    mt5Positions: function (symbol, signal) { return get('/api/mt5/positions', { symbol: symbol }, signal); },
    mt5Symbols: function (query, signal) { return get('/api/mt5/symbols', { query: query }, signal); },

    // ==================== 交易（写操作，务必二次确认） ====================
    tradeOpen: function (body) { return post('/api/trade/open', body); },
    tradeClose: function (body) { return post('/api/trade/close', body); },
    tradeModify: function (body) { return post('/api/trade/modify', body); },
    // 逐笔成交流水（只读）：status 可空=全部，常用 CLOSED（已平仓）
    tradeOrders: function (status, limit, signal) { return get('/api/trade/orders', { status: status, limit: limit }, signal); },

    // ==================== 风控 / 预算 ====================
    riskThresholds: function (signal) { return get('/api/risk/thresholds', null, signal); },
    // 改账户级风控阈值（risk_config 唯一行；含 R6 日亏熔断 maxDailyLoss，单位 USD）
    riskThresholdsPut: function (body) { return put('/api/risk/thresholds', body); },
    budgetStatus: function (signal) { return get('/api/budget/status', null, signal); },

    // ==================== 策略 ====================
    strategyList: function (signal) { return get('/api/strategy/list', null, signal); },
    strategyGate: function (id, body) { return post('/api/strategy/' + id + '/gate', body || {}); },
    strategyGateAll: function (body) { return post('/api/strategy/gate-all', body || {}); },
    strategyApprove: function (id, body) { return post('/api/strategy/' + id + '/approve', body || {}); },
    // 新建策略（入库为 DRAFT，需门禁+人工批准后才可能 ACTIVE）
    strategyCreate: function (body) { return post('/api/strategy', body); },

    // ==================== 回测 ====================
    backtestStrategies: function (signal) { return get('/api/backtest/strategies', null, signal); },
    backtestRun: function (spec) { return post('/api/backtest/run', spec); },
    backtestTiers: function (spec) { return post('/api/backtest/tiers', spec); },
    backtestGetRun: function (runId, signal) { return get('/api/backtest/' + runId, null, signal); },
    backtestBackfill: function (body) { return post('/api/backtest/backfill', body); },

    // ==================== Agent ====================
    agentDecide: function (body) { return post('/api/agent/decide', body || {}); },
    agentDecisions: function (limit, signal) { return get('/api/agent/decisions', { limit: limit }, signal); },
    agentTools: function (signal) { return get('/api/agent/tools', null, signal); },
    // AI 自由问答（非流式：一次返回回答 + 工具调用轨迹）
    agentChat: function (body) { return post('/api/agent/chat', body); },
    // 会话生命周期：status=ACTIVE(默认，=正在管理在场持仓的会话)|CLOSED|all
    agentSessions: function (status, limit, signal) { return get('/api/agent/sessions', { status: status, limit: limit }, signal); },
    // 会话内既有对话消息（还原聊天记录）
    agentSessionMessages: function (id, signal) { return get('/api/agent/sessions/' + id + '/messages', null, signal); },
    agentSessionClose: function (id) { return post('/api/agent/sessions/' + id + '/close', {}); },

    // ==================== 复盘 ====================
    reviewReports: function (limit, signal) { return get('/api/review/reports', { limit: limit }, signal); },
    reviewReport: function (orderId, signal) { return get('/api/review/report/' + orderId, null, signal); },
    reviewStats: function (params, signal) { return get('/api/review/stats', params, signal); },
    reviewLosses: function (params, signal) { return get('/api/review/losses', params, signal); },
    reviewStrategy: function (params, signal) { return get('/api/review/strategy', params, signal); },
    reviewCost: function (runId, signal) { return get('/api/review/cost', { runId: runId }, signal); },

    // ==================== 调度 ====================
    scheduleStatus: function (signal) { return get('/api/schedule/status', null, signal); },
    scheduleWake: function (reason) { return post('/api/schedule/wake', { reason: reason || '' }); },

    // ==================== 记忆 ====================
    memoryList: function (params, signal) { return get('/api/memory', params, signal); },

    // ==================== 配置 ====================
    configsList: function (type, signal) { return get('/api/configs', { type: type }, signal); },
    configGet: function (id, signal) { return get('/api/configs/' + id, null, signal); },
    configTest: function (body) { return post('/api/configs/test', body); },
    configSave: function (body) { return post('/api/configs', body); },
    configUpdate: function (id, body) { return put('/api/configs/' + id, body); },
    configDelete: function (id) { return del('/api/configs/' + id); },
    configActivate: function (id) { return post('/api/configs/' + id + '/activate', {}); },

    // ==================== 策略锁档（cycle 执行器） ====================
    // 读锁档：三层百分比 + 锁定时长（0 = 该档不启用/无上限）
    cycleSettingsGet: function (signal) { return get('/api/cycle/settings', null, signal); },
    // 改锁档：{maxSingleLossPct, maxDailyLossPct, maxTotalLossPct, lockHours}
    cycleSettingsPut: function (body) { return put('/api/cycle/settings', body); },

    // 统一错误兜底：把 Error 转成 Toast（带 traceId）
    fail: function (err, prefix) {
      var msg = (prefix ? prefix + '：' : '') + (err && err.message ? err.message : String(err));
      global.QuantaToast.err(msg, err && err.traceId);
      return msg;
    },
  };

  global.QuantaAPI = API;
})(window);
