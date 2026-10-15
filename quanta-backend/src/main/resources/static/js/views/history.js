/**
 * views/history.js —— 历史交易复盘（日期筛选 + KPI + 逐笔 + 净值曲线）
 * 数据源：/api/review/reports（逐笔，含 pnl/createTime）+ /api/review/stats + /api/review/losses
 */
(function (global) {
  'use strict';
  global.QuantaViews = global.QuantaViews || {};

  var API = global.QuantaAPI, U = global.QuantaUtil, CH = global.QuantaChart,
      CFG = global.QuantaConfig, Toast = global.QuantaToast;

  global.QuantaViews.history = {
    template: [
      '<div class="view">',
      // ---- 筛选 ----
      '  <div class="panel">',
      '    <div class="panel-body tight flex ai-c gap-12 wrap">',
      '      <div class="flex ai-c gap-8"><span class="hint">品种</span><input class="inp mono" style="width:96px" v-model="filter.symbol" placeholder="全部"></div>',
      '      <div class="flex ai-c gap-8"><span class="hint">起</span><input class="inp" type="date" v-model="filter.from"></div>',
      '      <div class="flex ai-c gap-8"><span class="hint">止</span><input class="inp" type="date" v-model="filter.to"></div>',
      '      <button class="btn sm ghost" @click="quick(7)">近7天</button>',
      '      <button class="btn sm ghost" @click="quick(30)">近30天</button>',
      '      <button class="btn sm ghost" @click="quick(0)">全部</button>',
      '      <div class="right flex gap-8 ai-c">',
      '        <span class="hint" v-if="loading"><span class="spinner"></span> 查询中…</span>',
      '        <button class="btn sm primary" @click="load">查询</button>',
      '      </div>',
      '    </div>',
      '  </div>',
      // ---- KPI ----
      '  <div class="grid g-4 mt-16">',
      '    <div class="kpi"><div class="kpi-label">已平仓交易</div><div class="kpi-value sm">{{ n(stats.tradeCount,0) }}</div><div class="kpi-foot">胜 {{ n(stats.winCount,0) }} · 负 {{ n(stats.lossCount,0) }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">胜率</div><div class="kpi-value sm">{{ pct(stats.winRatePct) }}</div><div class="kpi-foot">{{ winGuard }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">盈亏比 PROFIT FACTOR</div><div class="kpi-value sm" :class="pfClass">{{ pfText }}</div><div class="kpi-foot">毛利 {{ n(stats.grossProfit) }} / 毛损 {{ n(-(stats.grossLoss||0)) }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">净盈亏</div><div class="kpi-value sm" :class="pnlClass(stats.netProfit)">{{ U.fmtMoney(stats.netProfit) }}</div><div class="kpi-foot">期望/笔 {{ U.fmtMoney(stats.expectancy) }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">最大回撤</div><div class="kpi-value sm warnc">{{ U.fmtNum(stats.maxDrawdown) }}</div><div class="kpi-foot">USD（正数=回撤幅度）</div></div>',
      '    <div class="kpi"><div class="kpi-label">最大连亏</div><div class="kpi-value sm">{{ n(stats.maxConsecutiveLoss,0) }}</div><div class="kpi-foot">笔</div></div>',
      '    <div class="kpi"><div class="kpi-label">平均持仓</div><div class="kpi-value sm">{{ U.fmtDuration(stats.avgHoldSec) }}</div><div class="kpi-foot">秒级超短线</div></div>',
      '    <div class="kpi"><div class="kpi-label">样本量门禁</div><div class="kpi-value sm" :class="sampleOk?\'pos\':\'warnc\'">{{ sampleOk ? \'达标\' : \'不足\' }}</div><div class="kpi-foot">要求 ≥ {{ CFG.limits.minSample }} 笔</div></div>',
      '  </div>',
      // ---- 图表 ----
      '  <div class="grid mt-16" style="grid-template-columns: minmax(0,2fr) minmax(0,1fr)">',
      '    <div class="panel">',
      '      <div class="panel-head"><div class="panel-title">净值曲线</div><div class="right panel-sub">累计盈亏（USD）· 逐笔成交流水</div></div>',
      '      <div class="panel-body"><div ref="equity" style="width:100%;height:260px"></div></div>',
      '    </div>',
      '    <div class="panel">',
      '      <div class="panel-head"><div class="panel-title">逐笔盈亏</div><div class="right panel-sub">{{ orders.length || rows.length }} 笔</div></div>',
      '      <div class="panel-body"><div ref="pnl" style="width:100%;height:260px"></div></div>',
      '    </div>',
      '  </div>',
      // ---- 败因分布 ----
      '  <div class="panel mt-16">',
      '    <div class="panel-head"><div class="panel-title">败因分类</div><div class="right panel-sub">{{ losses.notes && losses.notes.length ? losses.notes.join(\'；\') : \'规则化统计\' }}</div></div>',
      '    <div class="panel-body"><div ref="loss" style="width:100%;height:220px"></div></div>',
      '  </div>',
      // ---- 逐笔列表 ----
      '  <div class="panel mt-16">',
      '    <div class="panel-head"><div class="panel-title">逐笔复盘</div><div class="right panel-sub">点击行查看单笔详情</div></div>',
      '    <div class="panel-body nopad" style="max-height:460px;overflow:auto">',
      '      <table class="tbl" v-if="rows.length">',
      '        <thead><tr><th>#</th><th>订单</th><th>会话</th><th class="r">盈亏</th><th class="c">结果</th><th class="c">已复盘</th><th>时间</th><th>摘要</th></tr></thead>',
      '        <tbody>',
      '          <tr v-for="(r,i) in rowsView" :key="r.id" class="clickable" @click="openDetail(r)">',
      '            <td class="muted">{{ i+1 }}</td>',
      '            <td class="mono">{{ r.orderId }}</td>',
      '            <td class="mono">{{ r.sessionId != null ? r.sessionId : \'-\' }}</td>',
      '            <td class="r mono" :class="pnlClass(r.pnl)">{{ U.fmtMoney(r.pnl) }}</td>',
      '            <td class="c"><span class="badge" :class="pnlClass(r.pnl)===\'pos\'?\'ok\':(pnlClass(r.pnl)===\'neg\'?\'fail\':\'\')">{{ r.result || \'-\' }}</span></td>',
      '            <td class="c">{{ r.reviewed ? \'是\' : \'否\' }}</td>',
      '            <td class="muted">{{ U.fmtTime(r.createTime) }}</td>',
      '            <td class="muted" style="max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ r.summary || \'-\' }}</td>',
      '          </tr>',
      '        </tbody>',
      '      </table>',
      '      <div v-else class="empty"><div class="empty-ico">◍</div>该区间暂无已平仓记录</div>',
      '    </div>',
      '  </div>',
      // ---- 详情抽屉 ----
      '  <div v-if="detail" class="overlay" style="justify-content:flex-end;align-items:stretch" @click.self="detail=null">',
      '    <div class="drawer">',
      '      <div class="drawer-head"><div class="modal-title">单笔复盘 · 订单 #{{ detail.orderId }}</div><div class="right"><button class="btn sm ghost" @click="detail=null">关闭</button></div></div>',
      '      <div class="drawer-body">',
      '        <dl class="kv">',
      '          <dt>盈亏</dt><dd :class="pnlClass(detail.pnl)">{{ U.fmtMoney(detail.pnl) }}</dd>',
      '          <dt>结果</dt><dd>{{ detail.result || \'-\' }}</dd>',
      '          <dt>会话</dt><dd>{{ detail.sessionId != null ? detail.sessionId : \'-\' }}</dd>',
      '          <dt>已复盘</dt><dd>{{ detail.reviewed ? \'是\' : \'否\' }}</dd>',
      '          <dt>生成时间</dt><dd>{{ U.fmtDateTimeFull(detail.createTime) }}</dd>',
      '        </dl>',
      '        <div class="panel-sub" style="margin:16px 0 6px">摘要</div>',
      '        <div class="jsonbox" style="max-height:160px">{{ detail.summary || \'-\' }}</div>',
      '        <div class="panel-sub" style="margin:16px 0 6px">动作时间线</div>',
      '        <div class="jsonbox" style="max-height:320px">{{ pretty(detail.actionTimeline) }}</div>',
      '        <button v-if="detailMissing" class="btn sm primary mt-16" @click="genReport">生成/刷新复盘报告</button>',
      '      </div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return {
        U: U, CFG: CFG,
        filter: { symbol: '', from: U.daysAgo(365 * 3), to: U.today() },
        stats: {},
        losses: {},
        rows: [],
        orders: [],
        loading: false,
        detail: null,
        detailMissing: false,
        timer: null,
      };
    },

    computed: {
      rowsView: function () { return this.rows.slice().reverse(); },
      pfText: function () {
        var v = this.stats.profitFactor;
        if (v === null || v === undefined) return '-';
        if (!isFinite(v)) return '∞';
        return U.fmtNum(v);
      },
      pfClass: function () {
        var v = this.stats.profitFactor;
        if (v === null || v === undefined) return '';
        return (isFinite(v) && v >= 1) || !isFinite(v) ? 'pos' : 'neg';
      },
      winGuard: function () {
        var R = 1.5; // 参考盈亏比下的最低胜率要求（用于提示）
        return '参考要求 > ' + U.fmtNum(100 / (1 + R), 1) + '%（R=' + R + '）';
      },
      sampleOk: function () { return Number(this.stats.tradeCount || 0) >= CFG.limits.minSample; },
    },

    methods: {
      n: function (v, d) { return U.fmtNum(v, d === undefined ? 2 : d); },
      pct: function (v) { return v === null || v === undefined ? '-' : U.fmtNum(v, 1) + '%'; },
      pnlClass: function (v) { return U.signClass(v); },
      pretty: function (v) { return U.prettyJSON(v); },
      quick: function (days) {
        this.filter.from = days ? U.daysAgo(days) : U.daysAgo(365 * 3);
        this.filter.to = U.today();
        this.load();
      },

      load: function () {
        var self = this;
        this.loading = true;
        var params = {
          symbol: this.filter.symbol || null,
          since: U.isoDateTime(this.filter.from, false),
          until: U.isoDateTime(this.filter.to, true),
        };
        return Promise.all([
          API.reviewStats(params).catch(function () { return {}; }),
          API.reviewLosses(params).catch(function () { return {}; }),
          API.reviewReports(CFG.limits.reportsLimit).catch(function () { return []; }),
          API.tradeOrders('CLOSED', 500).catch(function () { return []; }),
        ]).then(function (res) {
          self.stats = res[0] || {};
          self.losses = res[1] || {};
          var all = res[2] || [];
          // 客户端按区间过滤（reports 接口无日期参数）
          self.rows = all.filter(function (r) {
            var d = U.parseDate(r.createTime);
            if (!d) return true;
            var from = new Date(self.filter.from + 'T00:00:00');
            var to = new Date(self.filter.to + 'T23:59:59');
            return d >= from && d <= to;
          }).sort(function (a, b) {
            var da = U.parseDate(a.createTime), db = U.parseDate(b.createTime);
            return (da ? da.getTime() : 0) - (db ? db.getTime() : 0);
          });
          // 净值/逐笔曲线数据源改用真实成交流水（trade_order），避免只显示零星两点
          self.orders = (res[3] || []).filter(function (o) {
            var d = U.parseDate(o.closeTime);
            if (!d) return false;
            var from = new Date(self.filter.from + 'T00:00:00');
            var to = new Date(self.filter.to + 'T23:59:59');
            var okSym = !self.filter.symbol || String(o.symbol || '').toUpperCase() === self.filter.symbol.toUpperCase();
            return okSym && d >= from && d <= to;
          }).sort(function (a, b) {
            return (U.parseDate(a.closeTime) || 0) - (U.parseDate(b.closeTime) || 0);
          });
          self.loading = false;
          self.renderCharts();
        });
      },

      renderCharts: function () {
        var self = this;
        this.$nextTick(function () {
          // 曲线数据源：优先真实成交流水（trade_order CLOSED），无则回退复盘报告
          var seq = [];
          var i;
          if (self.orders.length) {
            for (i = 0; i < self.orders.length; i++) {
              var o = self.orders[i];
              seq.push({ t: o.closeTime, v: Number(o.profit || 0) + Number(o.swap || 0) });
            }
          } else {
            for (i = 0; i < self.rows.length; i++) {
              seq.push({ t: self.rows[i].createTime, v: Number(self.rows[i].pnl || 0) });
            }
          }
          // 净值曲线：逐笔累计
          var labels = [], values = [], acc = 0;
          for (i = 0; i < seq.length; i++) {
            acc += seq[i].v;
            labels.push(U.fmtTime(seq[i].t).slice(3));
            values.push(Number(acc.toFixed(2)));
          }
          if (self.$refs.equity) {
            CH.setOption(self.$refs.equity, CH.equityOption(labels, values));
          }
          // 逐笔盈亏
          var pl = [], pv = [];
          for (i = 0; i < seq.length; i++) {
            pl.push(U.fmtTime(seq[i].t).slice(3));
            pv.push(Number(seq[i].v.toFixed(2)));
          }
          if (self.$refs.pnl) {
            CH.setOption(self.$refs.pnl, CH.pnlOption(pl, pv));
          }
          // 败因分布
          var ls = self.losses || {};
          var cats = ['早平', '晚平', '系统错误', '逆势亏损'];
          var vals = [ls.earlyClose || 0, ls.lateClose || 0, ls.systemError || 0, ls.counterTrendLoss || 0];
          if (self.$refs.loss) {
            CH.setOption(self.$refs.loss, CH.lossOption(cats, vals));
          }
        });
      },

      openDetail: function (r) {
        var self = this;
        this.detailMissing = false;
        API.reviewReport(r.orderId).then(function (rep) {
          self.detail = rep || r;
        }).catch(function () {
          // 报告不存在：退回用列表行展示，并提示可生成
          self.detail = r;
          self.detailMissing = true;
        });
      },

      genReport: function () {
        var self = this;
        var oid = this.detail && this.detail.orderId;
        if (!oid) return;
        API.request('/api/review/report/' + oid, { method: 'POST', body: {} }).then(function (rep) {
          Toast.ok('已生成复盘报告');
          self.detail = rep;
          self.detailMissing = false;
          self.load();
        }).catch(function (e) { API.fail(e, '生成失败'); });
      },

      onResize: function () {
        CH.resize(this.$refs.equity); CH.resize(this.$refs.pnl); CH.resize(this.$refs.loss);
      },
    },

    mounted: function () {
      this.load();
      window.addEventListener('resize', this.onResize);
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.onResize);
      CH.dispose(this.$refs.equity); CH.dispose(this.$refs.pnl); CH.dispose(this.$refs.loss);
    },
  };
})(window);
