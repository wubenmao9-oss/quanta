/**
 * views/positions.js —— 持仓 · K线（P0 交易主屏）
 * 账户卡 + 持仓表 + 一键平仓 + 改 SL/TP + 实时 Canvas K线 + 新建交易
 */
(function (global) {
  'use strict';
  global.QuantaViews = global.QuantaViews || {};

  var API = global.QuantaAPI, U = global.QuantaUtil, State = global.QuantaState,
      CFG = global.QuantaConfig, Toast = global.QuantaToast;

  global.QuantaViews.positions = {
    template: [
      '<div class="view">',
      // ---------- 账户 KPI ----------
      '  <div class="grid g-6">',
      '    <div class="kpi"><div class="kpi-label">结余 BALANCE</div><div class="kpi-value sm">{{ U.fmtNum(acct.balance) }}</div><div class="kpi-foot">{{ currency }} · 账本 {{ acct.login || \'-\' }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">净值 EQUITY</div><div class="kpi-value sm" :class="flash.equity">{{ U.fmtNum(acct.equity) }}</div><div class="kpi-foot">浮动 {{ U.fmtMoney(acct.profit) }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">已用保证金</div><div class="kpi-value sm">{{ U.fmtNum(acct.margin) }}</div><div class="kpi-foot">杠杆 1:{{ acct.leverage || \'-\' }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">可用保证金</div><div class="kpi-value sm">{{ U.fmtNum(acct.freeMargin) }}</div><div class="kpi-foot">服务器 {{ acct.server || \'-\' }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">持仓浮动盈亏</div><div class="kpi-value sm" :class="pnlClass(totalProfit)">{{ U.fmtMoney(totalProfit) }}</div><div class="kpi-foot">{{ positions.length }} 笔持仓</div></div>',
      '    <div class="kpi"><div class="kpi-label">实时报价 {{ sym }}</div>',
      '      <div class="kpi-value sm"><span class="mono">{{ U.fmtPrice(quote.bid, digits) }}</span> <span class="muted" style="font-size:12px">/ {{ U.fmtPrice(quote.ask, digits) }}</span></div>',
      '      <div class="kpi-foot">点差 {{ quote.spread != null ? U.fmtNum(quote.spread,0) : \'-\' }} 点</div></div>',
      '  </div>',
      // ---------- K线 + 新建交易 ----------
      '  <div class="grid mt-16" style="grid-template-columns: minmax(0,1fr) 336px">',
      '    <div class="panel">',
      '      <div class="panel-head">',
      '        <div class="kline-head">',
      '          <span class="kline-symbol">{{ ctx.symbol }}</span>',
      '          <span class="kline-last" :class="pnlClass(lastChange)">{{ U.fmtPrice(quote.bid, digits) }}</span>',
      '          <span class="badge" :class="klineError?\'fail\':\'ok\'">{{ klineError ? \'行情异常\' : \'实时刷新中\' }}</span>',
      '        </div>',
      '        <div class="right kline-toolbar">',
      '          <input class="inp mono" style="width:88px" v-model="symInput" @keyup.enter="applySymbol" placeholder="品种" title="输入品种后回车">',
      '          <div class="seg">',
      '            <button v-for="tf in timeframes" :key="tf" :class="{on: ctx.timeframe===tf}" @click="switchTf(tf)">{{ tf }}</button>',
      '          </div>',
      '          <select class="inp" style="width:88px" v-model.number="ctx.count" @change="switchCount">',
      '            <option v-for="c in counts" :key="c" :value="c">{{ c }} 根</option>',
      '          </select>',
      '          <button class="btn sm primary" @click="aiOpen=true">AI 助手</button>',
      '        </div>',
      '      </div>',
      '      <div class="panel-body tight">',
      '        <div class="kline-canvas-wrap"><canvas ref="kline" class="kline-canvas"></canvas></div>',
      '        <div class="hint" style="margin-top:6px">滚轮缩放 · 拖拽平移 · 双击回到最新 · 鼠标悬停查看 OHLC</div>',
      '      </div>',
      '    </div>',
      // 新建交易
      '    <div class="panel">',
      '      <div class="panel-head"><div class="panel-title">新建交易</div><div class="right"><span class="panel-sub">市价单</span></div></div>',
      '      <div class="panel-body">',
      '        <div class="field"><label>方向</label>',
      '          <div class="seg" style="width:100%">',
      '            <button style="flex:1" :class="{on: form.side===\'BUY\'}" @click="form.side=\'BUY\'">BUY 做多</button>',
      '            <button style="flex:1" :class="{on: form.side===\'SELL\'}" @click="form.side=\'SELL\'">SELL 做空</button>',
      '          </div>',
      '        </div>',
      '        <div class="inp-row">',
      '          <div class="field"><label>手数 volume</label><input class="inp mono" type="number" step="0.01" min="0.01" v-model.number="form.volume"></div>',
      '          <div class="field"><label>品种</label><input class="inp mono" v-model="form.symbol"></div>',
      '        </div>',
      '        <div class="inp-row">',
      '          <div class="field"><label>止损 SL（价）</label><input class="inp mono" type="number" step="0.01" v-model.number="form.sl" placeholder="可留空"></div>',
      '          <div class="field"><label>止盈 TP（价）</label><input class="inp mono" type="number" step="0.01" v-model.number="form.tp" placeholder="可留空"></div>',
      '        </div>',
      '        <div class="flex ai-c gap-8 wrap" style="margin:-4px 0 12px">',
      '          <button class="btn xs" @click="autoSLTP">按现价 ±{{ CFG.defaults.slUsd }}/{{ CFG.defaults.tpUsd }} 美元</button>',
      '          <button class="btn xs ghost" @click="form.sl=null;form.tp=null">清空</button>',
      '        </div>',
      '        <div class="field"><label>tpMode（可留空 = 后端默认）</label><input class="inp mono" v-model="form.tpMode" placeholder="如 PRICE / MONEY"></div>',
      '        <button class="btn primary" style="width:100%" :disabled="submitting" @click="askOpen">{{ submitting ? \'提交中…\' : \'确认开仓\' }}</button>',
      '        <div class="hint" style="margin-top:8px">所有下单都经过风控层（RiskGuard），被拒会回执原因。</div>',
      '      </div>',
      '    </div>',
      '  </div>',
      // ---------- 持仓表 ----------
      '  <div class="panel mt-16">',
      '    <div class="panel-head">',
      '      <div class="panel-title">当前持仓</div>',
      '      <div class="right flex gap-8">',
      '        <span class="panel-sub">共 {{ positions.length }} 笔 · 浮动 <span :class="pnlClass(totalProfit)">{{ U.fmtMoney(totalProfit) }}</span></span>',
      '        <button class="btn sm ok" :disabled="!winCount" @click="askCloseBatch(\'win\')">平盈利 ({{ winCount }})</button>',
      '        <button class="btn sm danger" :disabled="!lossCount" @click="askCloseBatch(\'loss\')">平亏损 ({{ lossCount }})</button>',
      '        <button class="btn sm danger" :disabled="!positions.length" @click="askCloseBatch(\'all\')">全部平仓</button>',
      '      </div>',
      '    </div>',
      '    <div class="panel-body nopad" style="max-height:420px;overflow:auto">',
      '      <table class="tbl" v-if="positions.length">',
      '        <thead><tr>',
      '          <th>Ticket</th><th>品种</th><th class="c">方向</th><th class="r">手数</th>',
      '          <th class="r">开仓价</th><th class="r">现价</th><th class="r">SL</th><th class="r">TP</th>',
      '          <th class="r">浮动盈亏</th><th>开仓时间</th><th class="c">操作</th>',
      '        </tr></thead>',
      '        <tbody>',
      '          <tr v-for="p in positions" :key="p.ticket">',
      '            <td class="mono">{{ p.ticket }}</td>',
      '            <td>{{ p.symbol }}</td>',
      '            <td class="c"><span class="badge" :class="p.type">{{ p.type===\'buy\'?\'BUY\':\'SELL\' }}</span></td>',
      '            <td class="r mono">{{ p.volume }}</td>',
      '            <td class="r mono">{{ U.fmtPrice(p.openPrice, digits) }}</td>',
      '            <td class="r mono">{{ U.fmtPrice(p.currentPrice, digits) }}</td>',
      '            <td class="r mono">{{ p.sl ? U.fmtPrice(p.sl, digits) : \'-\' }}</td>',
      '            <td class="r mono">{{ p.tp ? U.fmtPrice(p.tp, digits) : \'-\' }}</td>',
      '            <td class="r mono" :class="pnlClass(p.profit)">{{ U.fmtMoney(p.profit) }}<span v-if="p.swap" class="muted" style="font-size:10px"> (swap {{ p.swap }})</span></td>',
      '            <td class="muted">{{ U.fmtTime(p.openTime) }}</td>',
      '            <td class="c nowrap">',
      '              <button class="btn xs" @click="openModify(p)">改SL/TP</button>',
      '              <button class="btn xs danger" @click="askClose(p)">平仓</button>',
      '            </td>',
      '          </tr>',
      '        </tbody>',
      '      </table>',
      '      <div v-else class="empty"><div class="empty-ico">◍</div>当前无持仓</div>',
      '    </div>',
      '  </div>',
      // ---------- 改 SL/TP 弹窗 ----------
      '  <div class="overlay" v-if="modifyRow" @click.self="modifyRow=null">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">修改 SL / TP</div><div class="right mono muted">{{ modifyRow.symbol }} #{{ modifyRow.ticket }}</div></div>',
      '      <div class="modal-body">',
      '        <div class="kv" style="margin-bottom:12px">',
      '          <dt>开仓价</dt><dd>{{ U.fmtPrice(modifyRow.openPrice, digits) }}</dd>',
      '          <dt>现价</dt><dd>{{ U.fmtPrice(modifyRow.currentPrice, digits) }}</dd>',
      '        </div>',
      '        <div class="inp-row">',
      '          <div class="field"><label>止损 SL</label><input class="inp mono" type="number" step="0.01" v-model.number="modifyForm.sl"></div>',
      '          <div class="field"><label>止盈 TP</label><input class="inp mono" type="number" step="0.01" v-model.number="modifyForm.tp"></div>',
      '        </div>',
      '        <div class="hint">留空的字段保持原值；改动会经风控校验后下发券商。</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="modifyRow=null">取消</button><button class="btn primary" :disabled="submitting" @click="doModify">确认修改</button></div>',
      '    </div>',
      '  </div>',
      // ---------- 平仓确认 ----------
      '  <div class="overlay" v-if="confirm" @click.self="confirm=null">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">确认{{ confirm.title }}</div></div>',
      '      <div class="modal-body"><div>{{ confirm.desc }}</div>',
      '        <div v-if="confirm.list && confirm.list.length" class="jsonbox" style="margin-top:12px;max-height:200px">{{ confirm.list.join(\'\\n\') }}</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="confirm=null">取消</button>',
      '        <button class="btn danger" :disabled="submitting" @click="confirm.run">{{ submitting ? \'执行中…\' : \'确认执行\' }}</button></div>',
      '    </div>',
      '  </div>',
      // ---------- 开仓确认 ----------
      '  <div class="overlay" v-if="openConfirm" @click.self="openConfirm=false">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">确认开仓</div></div>',
      '      <div class="modal-body">',
      '        <dl class="kv">',
      '          <dt>方向</dt><dd :class="form.side===\'BUY\'?\'pos\':\'neg\'">{{ form.side }}</dd>',
      '          <dt>品种 / 手数</dt><dd>{{ form.symbol }} · {{ form.volume }}</dd>',
      '          <dt>止损 / 止盈</dt><dd>{{ form.sl || \'-\' }} / {{ form.tp || \'-\' }}</dd>',
      '          <dt>参考现价</dt><dd>{{ form.side===\'BUY\' ? U.fmtPrice(quote.ask,digits)+\' (ask)\' : U.fmtPrice(quote.bid,digits)+\' (bid)\' }}</dd>',
      '        </dl>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="openConfirm=false">取消</button><button class="btn primary" :disabled="submitting" @click="doOpen">确认下单</button></div>',
      '    </div>',
      '  </div>',
      // ---------- 盘面 AI 助手（对话框抽屉）----------
      '  <quanta-ai-chat v-model="aiOpen" persona="GENERAL" title="盘面 AI 助手"',
      '    :symbol="ctx.symbol" :timeframe="ctx.timeframe" :context="aiContext"',
      '    :greeting="aiGreeting" :quick="aiQuick" />',
      '</div>'
    ].join(''),

    data: function () {
      return {
        CFG: CFG, U: U,
        timeframes: CFG.kline.timeframes,
        counts: CFG.limits.klineCountOptions,
        ctx: { symbol: CFG.kline.symbol, timeframe: CFG.kline.defaultTimeframe, count: CFG.kline.defaultCount },
        symInput: CFG.kline.symbol,
        account: null,
        positions: [],
        quote: {},
        klineError: null,
        lastChange: 0,
        prevBid: null,
        flash: { equity: '' },
        prevEquity: null,
        submitting: false,
        modifyRow: null,
        modifyForm: { sl: null, tp: null },
        confirm: null,
        openConfirm: false,
        aiOpen: false,
        form: {
          side: 'BUY', symbol: CFG.kline.symbol, volume: CFG.defaults.volume,
          sl: null, tp: null, tpMode: '',
        },
      };
    },

    computed: {
      state: function () { return State; },
      acct: function () {
        return this.account || { balance: null, equity: null, margin: null, freeMargin: null, profit: null, leverage: null, login: null, server: null, currency: 'USD' };
      },
      currency: function () { return (this.account && this.account.currency) || 'USD'; },
      digits: function () { return this.quote && this.quote.digits ? this.quote.digits : 2; },
      sym: function () { return this.ctx.symbol; },
      totalProfit: function () {
        var s = 0; for (var i = 0; i < this.positions.length; i++) s += Number(this.positions[i].profit || 0);
        return s;
      },
      winCount: function () { return this.positions.filter(function (p) { return Number(p.profit) > 0; }).length; },
      lossCount: function () { return this.positions.filter(function (p) { return Number(p.profit) < 0; }).length; },
      aiGreeting: function () {
        return '我是盘面助手，可以帮你查账户资金、持仓、报价、K线与指标，也能跑一段回测、翻看历史成交与最近决策。'
          + '有问题直接问，或先点下面的快捷问题。';
      },
      aiQuick: function () {
        return [
          '现在的账户与持仓情况怎么样？',
          '分析一下当前 ' + this.ctx.symbol + ' ' + this.ctx.timeframe + ' 的形态与主要指标',
          '当前点差对超短线是否有利？',
        ];
      },
      aiContext: function () {
        var q = this.quote || {};
        return '当前盘面：品种=' + this.ctx.symbol + '，周期=' + this.ctx.timeframe
          + '，最新 bid=' + (q.bid != null ? q.bid : '-') + '，ask=' + (q.ask != null ? q.ask : '-')
          + '，点差=' + (q.spread != null ? q.spread : '-') + ' 点；当前持仓 ' + this.positions.length + ' 笔。';
      },
    },

    methods: {
      pnlClass: function (v) { return U.signClass(v); },

      loadAccount: function () {
        var self = this;
        return API.mt5Account().then(function (a) {
          if (!a) return;
          if (self.prevEquity !== null && Number(a.equity) !== self.prevEquity) {
            self.flash.equity = Number(a.equity) > self.prevEquity ? 'flash-up' : 'flash-down';
            setTimeout(function () { self.flash.equity = ''; }, 620);
          }
          self.prevEquity = Number(a.equity);
          self.account = a;
        }).catch(function () { /* 顶栏已兜底提示，视图内静默 */ });
      },

      loadPositions: function () {
        var self = this;
        return API.mt5Positions(null).then(function (list) { self.positions = list || []; })
          .catch(function () { self.positions = []; });
      },

      loadQuote: function () {
        var self = this;
        return API.mt5Quote(this.ctx.symbol).then(function (q) {
          if (!q) return;
          if (self.prevBid !== null) self.lastChange = q.bid - self.prevBid;
          self.prevBid = q.bid;
          self.quote = q;
        }).catch(function () { /* ignore */ });
      },

      refreshTrade: function () { this.loadPositions(); this.loadQuote(); },

      switchTf: function (tf) {
        this.ctx.timeframe = tf;
        if (this.klineChart) this.klineChart.setParams({ timeframe: tf });
      },
      switchCount: function () {
        if (this.klineChart) this.klineChart.setParams({ count: this.ctx.count });
      },
      applySymbol: function () {
        var s = (this.symInput || '').trim().toUpperCase();
        if (!s) { this.symInput = this.ctx.symbol; return; }
        this.ctx.symbol = s;
        this.form.symbol = s;
        this.prevBid = null;
        if (this.klineChart) this.klineChart.setParams({ symbol: s });
        this.loadQuote();
      },

      autoSLTP: function () {
        var q = this.quote || {};
        var base = this.form.side === 'BUY' ? (q.ask || q.bid) : (q.bid || q.ask);
        if (!base) { Toast.warn('暂无报价，无法自动计算'); return; }
        var d = Math.pow(10, this.digits);
        if (this.form.side === 'BUY') {
          this.form.sl = Math.round((base - CFG.defaults.slUsd) * d) / d;
          this.form.tp = Math.round((base + CFG.defaults.tpUsd) * d) / d;
        } else {
          this.form.sl = Math.round((base + CFG.defaults.slUsd) * d) / d;
          this.form.tp = Math.round((base - CFG.defaults.tpUsd) * d) / d;
        }
      },

      askOpen: function () {
        if (!this.form.symbol) { Toast.warn('请填写品种'); return; }
        if (!this.form.volume || this.form.volume <= 0) { Toast.warn('手数必须大于 0'); return; }
        this.openConfirm = true;
      },
      doOpen: function () {
        var self = this;
        this.submitting = true;
        API.tradeOpen({
          symbol: this.form.symbol, side: this.form.side, volume: this.form.volume,
          price: null, sl: this.form.sl || null, tp: this.form.tp || null,
          tpMode: this.form.tpMode || null,
        }).then(function (order) {
          Toast.ok('开仓成功：ticket ' + (order.ticket || '-') + '，状态 ' + (order.status || '-'));
          self.openConfirm = false;
          self.refreshTrade();
          self.loadAccount();
        }).catch(function (e) { API.fail(e, '开仓被拒'); })
          .then(function () { self.submitting = false; });
      },

      openModify: function (p) {
        this.modifyRow = p;
        this.modifyForm = { sl: p.sl || null, tp: p.tp || null };
      },
      doModify: function () {
        var self = this, p = this.modifyRow;
        this.submitting = true;
        API.tradeModify({
          symbol: p.symbol, ticket: p.ticket, sl: this.modifyForm.sl || null, tp: this.modifyForm.tp || null,
        }).then(function () {
          Toast.ok('已修改 SL/TP：# ' + p.ticket);
          self.modifyRow = null; self.refreshTrade();
        }).catch(function (e) { API.fail(e, '改单被拒'); })
          .then(function () { self.submitting = false; });
      },

      askClose: function (p) {
        var self = this;
        this.confirm = {
          title: '平仓', desc: '将对 ticket ' + p.ticket + '（' + p.symbol + ' ' + p.type + '）执行市价平仓。',
          run: function () { self.doCloseOne(p); },
        };
      },
      doCloseOne: function (p) {
        var self = this;
        this.submitting = true;
        API.tradeClose({ symbol: p.symbol, ticket: p.ticket }).then(function () {
          Toast.ok('已平仓：# ' + p.ticket);
          self.confirm = null; self.refreshTrade(); self.loadAccount();
        }).catch(function (e) { API.fail(e, '平仓被拒'); })
          .then(function () { self.submitting = false; });
      },

      askCloseBatch: function (mode) {
        var self = this;
        var list = this.positions.slice();
        if (mode === 'win') list = list.filter(function (p) { return Number(p.profit) > 0; });
        if (mode === 'loss') list = list.filter(function (p) { return Number(p.profit) < 0; });
        if (!list.length) return;
        var label = mode === 'all' ? '全部平仓' : (mode === 'win' ? '平所有盈利单' : '平所有亏损单');
        this.confirm = {
          title: label,
          desc: '将对以下 ' + list.length + ' 笔持仓逐笔执行市价平仓：',
          list: list.map(function (p) { return '#' + p.ticket + '  ' + p.symbol + ' ' + p.type + '  ' + p.volume + '手  浮动 ' + U.fmtMoney(p.profit); }),
          run: function () { self.doCloseBatch(list, label); },
        };
      },
      doCloseBatch: function (list, label) {
        var self = this;
        this.submitting = true;
        var ok = 0, failed = [];
        var chain = Promise.resolve();
        list.forEach(function (p) {
          chain = chain.then(function () {
            return API.tradeClose({ symbol: p.symbol, ticket: p.ticket })
              .then(function () { ok++; })
              .catch(function (e) { failed.push('#' + p.ticket + ': ' + e.message); });
          });
        });
        chain.then(function () {
          if (failed.length) Toast.warn(label + '完成：成功 ' + ok + ' 笔，失败 ' + failed.length + ' 笔');
          else Toast.ok(label + '完成：成功 ' + ok + ' 笔');
          failed.forEach(function (m) { Toast.err(m); });
          self.confirm = null; self.refreshTrade(); self.loadAccount();
          self.submitting = false;
        });
      },
    },

    mounted: function () {
      var self = this;
      this.loadAccount(); this.loadPositions(); this.loadQuote();

      this.klineChart = global.QuantaKline.mount(this.$refs.kline, {
        symbol: this.ctx.symbol, timeframe: this.ctx.timeframe, count: this.ctx.count,
        onStatus: function (s) { self.klineError = s.ok ? null : (s.error && s.error.message) || '行情加载失败'; },
      });
      this.klineChart.start();

      this.tAccount = setInterval(function () { self.loadAccount(); }, CFG.poll.accountMs);
      this.tTrade = setInterval(function () { self.refreshTrade(); }, CFG.poll.positionsMs);
    },

    beforeUnmount: function () {
      if (this.tAccount) clearInterval(this.tAccount);
      if (this.tTrade) clearInterval(this.tTrade);
      if (this.klineChart) this.klineChart.destroy();
      this.klineChart = null;
    },
  };
})(window);
