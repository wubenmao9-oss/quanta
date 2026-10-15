/**
 * views/chat.js —— AI 决策会话（一持仓一会话：研判线程 + 自由对话 + 动作详情 + 撤销）
 *
 * 会话模型：当前有几条在场持仓，这里就有几条会话；每个会话都由 AI 管理，
 * 用户可在会话内与 AI 自由聊天（「AI 对话」按钮）；持仓平仓后该会话自动消失。
 */
(function (global) {
  'use strict';
  global.QuantaViews = global.QuantaViews || {};

  var API = global.QuantaAPI, U = global.QuantaUtil, CFG = global.QuantaConfig, Toast = global.QuantaToast;

  function actionClass(a) {
    if (!a) return '';
    a = String(a).toUpperCase();
    if (a.indexOf('OPEN') >= 0 || a.indexOf('BUY') >= 0) return 'buy';
    if (a.indexOf('CLOSE') >= 0 || a.indexOf('SELL') >= 0) return 'sell';
    if (a.indexOf('WAIT') >= 0 || a.indexOf('HOLD') >= 0 || a.indexOf('NONE') >= 0) return 'DRAFT';
    return 'warn';
  }

  function sideLabel(cmd) {
    if (!cmd) return '';
    var c = String(cmd).toUpperCase();
    if (c.indexOf('BUY') >= 0) return '多';
    if (c.indexOf('SELL') >= 0) return '空';
    return c;
  }

  global.QuantaViews.chat = {
    template: [
      '<div class="view">',
      '  <div class="split wide-right">',
      // ---- 会话列表（= 在场持仓） ----
      '    <div class="panel">',
      '      <div class="panel-head"><div class="panel-title">决策会话',
      '        <span class="panel-sub" style="margin-left:8px">{{ sessions.length }} 条（一持仓一会话）</span></div>',
      '        <div class="right flex gap-8 ai-c">',
      '          <button class="btn sm ghost" @click="toggleAll">{{ showAll ? \'仅看在场\' : \'显示历史\' }}</button>',
      '          <button class="btn sm ghost" @click="load">刷新</button>',
      '        </div></div>',
      '      <div class="list">',
      '        <div v-for="s in sessions" :key="s.id" class="list-item" :class="{on: s.id===current}" @click="select(s.id)">',
      '          <div class="list-item-title">会话 #{{ s.id }}',
      '            <span class="badge" :class="actionClass(s.lastAction)">{{ s.lastAction || \'-\' }}</span>',
      '            <span class="badge" :class="s.status===\'ACTIVE\'?\'ACTIVE\':\'DRAFT\'">{{ s.status || \'-\' }}</span>',
      '          </div>',
      '          <div class="list-item-meta">',
      '            <span v-if="s.symbol">{{ s.symbol }}</span>',
      '            <span v-if="s.linkedTicket">持仓 #{{ s.linkedTicket }} <i v-if="sideLabel(s.openCmd)">{{ sideLabel(s.openCmd) }} {{ s.openVolume }}手</i></span>',
      '            <span v-else class="muted">无在场持仓</span>',
      '            <span>{{ s.count }} 条研判</span>',
      '            <span>{{ U.fmtTime(s.lastTime || s.createTime) }}</span>',
      '          </div>',
      '          <div class="list-item-meta" style="margin-top:6px">',
      '            <button class="btn xs primary" @click.stop="openAi(s)">AI 对话</button>',
      '            <button v-if="s.status===\'ACTIVE\'" class="btn xs ghost" @click.stop="closeSession(s)">关闭</button>',
      '            <span v-if="s.strategyName" class="right muted">{{ s.strategyName }}</span>',
      '          </div>',
      '        </div>',
      '        <div v-if="!sessions.length" class="empty"><div class="empty-ico">◍</div>',
      '          {{ showAll ? \'暂无会话\' : \'当前无在场持仓，故无决策会话\' }}</div>',
      '      </div>',
      '    </div>',
      // ---- 研判线程 ----
      '    <div class="panel">',
      '      <div class="panel-head">',
      '        <div class="panel-title">{{ current ? (\'会话 #\' + current + \' 研判线程\') : \'研判线程\' }}</div>',
      '        <div class="right flex gap-8 ai-c">',
      '          <span class="panel-sub">{{ thread.length }} 条</span>',
      '          <button class="btn sm ghost" :disabled="!cur" @click="openAi(cur)">AI 对话</button>',
      '          <button class="btn sm primary" @click="askDecide">发起研判</button>',
      '        </div>',
      '      </div>',
      '      <div class="panel-body">',
      '        <div v-if="!current" class="empty"><div class="empty-ico">◍</div>请选择左侧会话；也可点任意会话的「AI 对话」与 AI 自由聊天</div>',
      '        <div v-else-if="!thread.length" class="empty"><div class="empty-ico">◍</div>该会话暂无研判记录，可点右上「AI 对话」自由提问</div>',
      '        <div v-else class="thread">',
      '          <div v-for="d in thread" :key="d.id" class="msg ai">',
      '            <div class="msg-avatar">AI</div>',
      '            <div class="msg-body">',
      '              <div class="msg-card">',
      '                <div class="msg-head">',
      '                  <span class="badge" :class="actionClass(d.action)">{{ d.action || \'-\' }}</span>',
      '                  <span class="muted">{{ d.triggerType || \'-\' }} · {{ triggerLabel(d.triggerType) }}</span>',
      '                  <span class="right muted mono">{{ U.fmtTime(d.createTime) }}</span>',
      '                </div>',
      '                <div class="msg-reason">{{ d.reason || \'（无说明）\' }}</div>',
      '                <div class="msg-foot">',
      '                  <span>置信 {{ d.confidence != null ? d.confidence : \'-\' }}</span>',
      '                  <span>tokens {{ d.tokens != null ? d.tokens : \'-\' }}</span>',
      '                  <span>延迟 {{ d.latencyMs != null ? d.latencyMs + \'ms\' : \'-\' }}</span>',
      '                  <span>{{ d.model || \'-\' }}</span>',
      '                </div>',
      '                <div class="flex gap-8" style="margin-top:9px">',
      '                  <button class="btn xs ghost" @click="toggle(d.id)">{{ expanded[d.id] ? \'收起详情\' : \'动作详情\' }}</button>',
      '                  <button class="btn xs danger" v-if="closableTicket(d)" @click="askUndo(d)">撤销（平掉 #{{ closableTicket(d) }})</button>',
      '                </div>',
      '                <div v-if="expanded[d.id]" style="margin-top:10px">',
      '                  <div class="panel-sub" style="margin-bottom:6px">动作参数 actionParams</div>',
      '                  <div class="jsonbox" style="max-height:160px">{{ pretty(d.actionParams) }}</div>',
      '                  <div class="panel-sub" style="margin:12px 0 6px">输入快照 inputSnapshot</div>',
      '                  <div class="kv" v-if="snapshotSummary(d)">',
      '                    <template v-for="(v,k) in snapshotSummary(d)" :key="k"><dt>{{ k }}</dt><dd>{{ v }}</dd></template>',
      '                  </div>',
      '                  <div class="flex" style="margin-top:8px">',
      '                    <button class="btn xs ghost" @click="rawOpen[d.id] = !rawOpen[d.id]">{{ rawOpen[d.id] ? \'隐藏原始 JSON\' : \'查看原始 JSON\' }}</button>',
      '                  </div>',
      '                  <div v-if="rawOpen[d.id]" class="jsonbox" style="margin-top:8px;max-height:260px">{{ pretty(d.inputSnapshot) }}</div>',
      '                  <div class="muted mono" style="margin-top:8px;font-size:10px">trace: {{ d.traceId || \'-\' }}</div>',
      '                </div>',
      '              </div>',
      '            </div>',
      '          </div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      // ---- 会话内 AI 自由对话 ----
      '  <quanta-ai-chat v-model="aiOpen" :chat-session-id="aiSessionId" persona="GENERAL"',
      '    title="决策会话 AI 对话" :symbol="aiSymbol" :context="aiContext"',
      '    :greeting="aiGreeting" :placeholder="\'向 AI 提问（可查询持仓/行情/指标/流水，只给建议不代下单）\'" />',
      // ---- 撤销确认 ----
      '  <div class="overlay" v-if="undoRow" @click.self="undoRow=null">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">撤销该动作</div></div>',
      '      <div class="modal-body">',
      '        <div>将对该决策开出的持仓执行市价平仓（ticket #{{ closableTicket(undoRow) }}）。</div>',
      '        <div class="hint" style="margin-top:8px">撤销本质是平仓写操作，会经风控层校验。</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="undoRow=null">取消</button>',
      '        <button class="btn danger" :disabled="busy" @click="doUndo">确认平仓</button></div>',
      '    </div>',
      '  </div>',
      // ---- 发起研判确认 ----
      '  <div class="overlay" v-if="decideConfirm" @click.self="decideConfirm=false">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">发起一次研判</div></div>',
      '      <div class="modal-body">',
      '        <div>将以「人工唤醒（T6）」触发一次 AI 研判，会消耗 Token 并可能产生下单动作。</div>',
      '        <div class="field" style="margin-top:12px"><label>唤醒原因（写入审计日志）</label>',
      '          <input class="inp" v-model="decideReason" placeholder="如：手动检查盘面">',
      '        </div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="decideConfirm=false">取消</button>',
      '        <button class="btn primary" :disabled="busy" @click="doDecide">确认发起</button></div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return {
        U: U, CFG: CFG,
        decisions: [],
        rawSessions: [],
        showAll: false,
        current: null,
        aiOpen: false,
        aiSessionId: null,
        aiSymbol: '',
        aiContext: '',
        aiGreeting: '',
        expanded: {},
        rawOpen: {},
        undoRow: null,
        decideConfirm: false,
        decideReason: '',
        busy: false,
        timer: null,
      };
    },

    computed: {
      // 会话来源始终是后端会话表（默认只返回「正在管理在场持仓」的会话）
      sessions: function () {
        var byS = {};
        this.decisions.forEach(function (d) {
          if (d.sessionId == null) return;
          var k = String(d.sessionId);
          var st = byS[k] || (byS[k] = { count: 0, lastAction: null, lastTime: null, lastId: -1 });
          st.count++;
          if ((d.id || 0) > st.lastId) {
            st.lastId = d.id || 0;
            st.lastAction = d.action;
            st.lastTime = d.createTime;
          }
        });
        return this.rawSessions.map(function (s) {
          var st = byS[String(s.id)] || {};
          return {
            id: s.id, symbol: s.symbol, status: s.status, sessionType: s.sessionType,
            linkedTicket: s.linkedTicket, managingPosition: s.managingPosition,
            openCmd: s.openCmd, openVolume: s.openVolume, openTime: s.openTime,
            strategyName: s.strategyName, createTime: s.createTime,
            count: st.count || 0, lastAction: st.lastAction || null,
            lastTime: st.lastTime || s.createTime,
          };
        });
      },
      cur: function () {
        var c = this.current, s = this.sessions;
        for (var i = 0; i < s.length; i++) if (s[i].id === c) return s[i];
        return null;
      },
      thread: function () {
        var cur = String(this.current);
        return this.decisions.filter(function (d) {
          return d.sessionId != null && String(d.sessionId) === cur;
        }).sort(function (a, b) { return (a.id || 0) - (b.id || 0); });
      },
    },

    methods: {
      actionClass: actionClass,
      sideLabel: sideLabel,
      triggerLabel: function (t) { return CFG.triggerLabels[t] || '未知触发源'; },
      pretty: function (v) { return U.prettyJSON(v); },

      load: function () {
        var self = this;
        var st = this.showAll ? 'all' : 'ACTIVE';
        return Promise.all([
          API.agentDecisions(CFG.limits.decisionsLimit).catch(function () { return []; }),
          API.agentSessions(st, 200).catch(function () { return []; }),
        ]).then(function (res) {
          self.decisions = res[0] || [];
          self.rawSessions = res[1] || [];
          var ids = self.sessions.map(function (s) { return s.id; });
          if (self.current == null || ids.indexOf(self.current) < 0) {
            self.current = self.sessions.length ? self.sessions[0].id : null;
          }
        });
      },

      toggleAll: function () { this.showAll = !this.showAll; this.load(); },

      closeSession: function (s) {
        var self = this;
        API.agentSessionClose(s.id).then(function () {
          Toast.ok('已关闭会话 #' + s.id);
          self.load();
        }).catch(function (e) { API.fail(e, '关闭会话失败'); });
      },

      select: function (sid) {
        this.current = sid;
        this.expanded = {}; this.rawOpen = {};
      },

      openAi: function (s) {
        if (!s) return;
        this.aiSessionId = s.id;
        this.aiSymbol = s.symbol || CFG.kline.symbol;
        this.aiGreeting = '正在管理持仓' + (s.linkedTicket ? ' #' + s.linkedTicket : '')
          + '（' + (s.symbol || '-') + (s.openCmd ? ' ' + sideLabel(s.openCmd) + ' ' + (s.openVolume || '') + '手' : '')
          + '）。你可以问我任意问题，我会调用真实接口查询后回答。';
        this.aiContext = '会话 #' + s.id + ' 归属策略：' + (s.strategyName || '-')
          + '；持仓 ticket=' + (s.linkedTicket || '-')
          + '，方向=' + (s.openCmd || '-') + '，手数=' + (s.openVolume || '-')
          + '，开仓价=' + (s.openPrice || '-')
          + '，止损=' + (s.openSl || '-') + '，止盈=' + (s.openTp || '-') + '。';
        this.aiOpen = true;
      },

      toggle: function (id) { this.expanded[id] = !this.expanded[id]; },

      parsed: function (d) { return U.safeParse(d.inputSnapshot, null); },
      parsedParams: function (d) { return U.safeParse(d.actionParams, null); },

      snapshotSummary: function (d) {
        var snap = this.parsed(d);
        if (!snap || typeof snap !== 'object') return null;
        var out = {};
        if (snap.quote) out['报价'] = (snap.quote.symbol || '') + '  bid ' + snap.quote.bid + ' / ask ' + snap.quote.ask + '  spread ' + snap.quote.spread;
        if (snap.indicators) {
          var parts = [];
          for (var k in snap.indicators) if (Object.prototype.hasOwnProperty.call(snap.indicators, k)) parts.push(k + '=' + snap.indicators[k]);
          if (parts.length) out['指标'] = parts.join('  ');
        }
        if (snap.account) out['账户'] = 'equity ' + snap.account.equity + '  free ' + snap.account.freeMargin;
        if (snap.positions) out['持仓'] = snap.positions.length + ' 笔';
        if (snap.recentBars) out['近K线'] = snap.recentBars.length + ' 根';
        return Object.keys(out).length ? out : null;
      },

      // 从 actionParams 里找出可撤销的 ticket
      closableTicket: function (d) {
        if (!d.action || String(d.action).toUpperCase().indexOf('OPEN') < 0) return null;
        var p = this.parsedParams(d);
        if (!p) return null;
        return p.ticket || p.ticketId || null;
      },
      closableSymbol: function (d) {
        var p = this.parsedParams(d) || {};
        if (p.symbol) return p.symbol;
        if (this.cur && this.cur.symbol) return this.cur.symbol;
        var snap = this.parsed(d);
        if (snap && snap.quote && snap.quote.symbol) return snap.quote.symbol;
        return CFG.kline.symbol;
      },

      askUndo: function (d) { this.undoRow = d; },
      doUndo: function () {
        var self = this, d = this.undoRow;
        var ticket = this.closableTicket(d);
        if (!ticket) { Toast.warn('该决策未记录 ticket，无法撤销'); return; }
        this.busy = true;
        API.tradeClose({ symbol: this.closableSymbol(d), ticket: ticket }).then(function () {
          Toast.ok('已撤销并平仓 #' + ticket);
          self.undoRow = null; self.load();
        }).catch(function (e) { API.fail(e, '撤销失败'); })
          .then(function () { self.busy = false; });
      },

      askDecide: function () { this.decideConfirm = true; },
      doDecide: function () {
        var self = this;
        this.busy = true;
        API.agentDecide({ triggerType: 'T6', reason: this.decideReason || '手动发起研判' }).then(function (r) {
          Toast.ok('研判完成：' + ((r && r.action) || '已返回'));
          self.decideConfirm = false; self.decideReason = '';
          self.load();
        }).catch(function (e) { API.fail(e, '研判失败'); })
          .then(function () { self.busy = false; });
      },
    },

    mounted: function () {
      var self = this;
      this.load();
      this.timer = setInterval(function () { self.load(); }, CFG.poll.decisionsMs);
    },
    beforeUnmount: function () { if (this.timer) clearInterval(this.timer); },
  };
})(window);
