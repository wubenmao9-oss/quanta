/**
 * views/strategy.js —— 策略库（门禁/批准）+ 回测（单档 / 点差多档扫描）
 */
(function (global) {
  'use strict';
  global.QuantaViews = global.QuantaViews || {};

  var API = global.QuantaAPI, U = global.QuantaUtil, CH = global.QuantaChart,
      CFG = global.QuantaConfig, Toast = global.QuantaToast;

  global.QuantaViews.strategy = {
    template: [
      '<div class="view">',
      // ---- 策略库 ----
      '  <div class="panel">',
      '    <div class="panel-head"><div class="panel-title">策略库</div>',
      '      <div class="right flex gap-8 ai-c">',
      '        <span class="panel-sub">{{ strategies.length }} 个策略</span>',
      '        <button class="btn sm ghost" @click="loadList">刷新</button>',
      '        <button class="btn sm primary" @click="openAi(null)">新建策略（AI）</button>',
      '      </div></div>',
      '    <div class="panel-body nopad">',
      '      <table class="tbl" v-if="strategies.length">',
      '        <thead><tr><th>ID</th><th>名称</th><th class="c">状态</th><th class="c">已批准</th><th class="c">族</th><th>风格</th><th class="c">目标RR</th><th>指标参数</th><th class="c">操作</th></tr></thead>',
      '        <tbody>',
      '          <tr v-for="s in strategies" :key="s.id">',
      '            <td class="mono">{{ s.id }}</td>',
      '            <td><div style="font-weight:600">{{ s.name }}</div><div class="muted" style="font-size:11px">v{{ s.version }} · {{ s.origin || \'-\' }}</div></td>',
      '            <td class="c"><span class="badge" :class="s.status">{{ s.status }}</span></td>',
      '            <td class="c">{{ s.approved ? \'是\' : \'否\' }}</td>',
      '            <td class="c"><span class="badge" :class="s.family===\'BASKET\'?\'sell\':\'buy\'">{{ s.family===\'BASKET\'?\'多腿/网格\':\'单仓\' }}</span></td>',
      '            <td>{{ s.regimeType || \'-\' }}</td>',
      '            <td class="c mono">{{ s.targetRr != null ? s.targetRr : \'-\' }}</td>',
      '            <td><button class="btn xs ghost" @click="showParams(s)">查看参数</button></td>',
      '            <td class="c nowrap">',
      '              <button class="btn xs ghost" @click="polish(s)">AI 打磨</button>',
      '              <button class="btn xs" @click="runGate(s)">跑门禁</button>',
      '              <button class="btn xs ok" @click="askApprove(s)">批准上线</button>',
      '            </td>',
      '          </tr>',
      '        </tbody>',
      '      </table>',
      '      <div v-else class="empty"><div class="empty-ico">◍</div>暂无策略</div>',
      '    </div>',
      '  </div>',
      // ---- 回测 ----
      '  <div class="panel mt-16">',
      '    <div class="panel-head"><div class="panel-title">回测 · 点差多档扫描</div><div class="right panel-sub">下拉为内置规则策略（RULE/BASKET 模式，不调用大模型）</div></div>',
      '    <div class="panel-body">',
      '      <div class="flex gap-12 wrap ai-c">',
      '        <div class="field mb-0"><label>策略</label><select class="inp" style="width:230px" v-model="bt.strategyKey"><option v-for="s in btStrategies" :key="s.key" :value="s.key">{{ s.name }}{{ s.family===\'BASKET\' ? \'（多腿/网格）\' : \'\' }}</option></select></div>',
      '        <div class="field mb-0"><label>品种</label><input class="inp mono" style="width:90px" v-model="bt.symbol"></div>',
      '        <div class="field mb-0"><label>周期</label><select class="inp" style="width:78px" v-model="bt.timeframe"><option v-for="tf in btTfs" :key="tf" :value="tf">{{ tf }}</option></select></div>',
      '        <div class="field mb-0"><label>起</label><input class="inp" type="date" v-model="bt.from"></div>',
      '        <div class="field mb-0"><label>止</label><input class="inp" type="date" v-model="bt.to"></div>',
      '      </div>',
      '      <div class="flex gap-12 wrap ai-c mt-12">',
      '        <div class="field mb-0"><label>单档点差 points</label><input class="inp mono" style="width:90px" type="number" v-model.number="bt.spreadPoints"></div>',
      '        <div class="field mb-0"><label>滑点 points</label><input class="inp mono" style="width:90px" type="number" v-model.number="bt.slippagePoints"></div>',
      '        <div class="field mb-0" style="flex:1;min-width:200px"><label>扫描点差档（逗号分隔）</label><input class="inp mono" v-model="bt.spreads"></div>',
      '        <div class="flex gap-8" style="align-self:flex-end">',
      '          <button class="btn sm" :disabled="running" @click="runSingle">单档回测</button>',
      '          <button class="btn sm primary" :disabled="running" @click="runTiers">{{ running ? \'运行中…\' : \'多档扫描\' }}</button>',
      '          <button class="btn sm ghost" @click="backfill">回填历史</button>',
      '        </div>',
      '      </div>',
      '      <div class="hint" style="margin-top:10px">单仓门禁：最小样本 ≥100 笔 · 30 点差档期望为正 · 外样本仍为正 · 最大回撤 ≤15% · 胜率 &gt; 1/(1+R)；'
        + '多腿/网格（BASKET）门禁另加：轮数 ≥100 · 最大连亏轮 ≤3 · 平均每轮档数 ≤5（按<b>轮</b>而非按腿计口径，防马丁虚高）</div>',
      // 结果
      '      <div v-if="verdict" class="panel-2 mt-16" style="border-radius:10px;padding:12px 14px;border:1px solid var(--border)">',
      '        <div class="panel-sub">稳健性结论</div><div style="margin-top:4px">{{ verdict }}</div>',
      '      </div>',
      '      <div v-if="tiers.length" class="grid mt-16" style="grid-template-columns: minmax(0,1.2fr) minmax(0,1fr)">',
      '        <div class="panel panel-2"><div class="panel-head"><div class="panel-title">点差 — 收益对照</div></div>',
      '          <div class="panel-body"><div ref="tierChart" style="width:100%;height:250px"></div></div></div>',
      '        <div class="panel panel-2"><div class="panel-head"><div class="panel-title">逐档汇总</div></div>',
      '          <div class="panel-body nopad" style="max-height:250px;overflow:auto"><table class="tbl">',
      '            <thead><tr><th class="r">点差</th><th class="r">笔数</th><th class="r">胜率</th><th class="r">盈亏比</th><th class="r">净盈亏</th><th class="r">期望</th><th class="r">回撤</th></tr></thead>',
      '            <tbody><tr v-for="t in tiers" :key="t.runId">',
      '              <td class="r mono">{{ t.spreadPoints }}</td><td class="r mono">{{ t.tradeCount }}</td>',
      '              <td class="r mono">{{ U.fmtNum(t.winRatePct,1) }}%</td><td class="r mono">{{ U.fmtNum(t.profitFactor) }}</td>',
      '              <td class="r mono" :class="U.signClass(t.netProfit)">{{ U.fmtMoney(t.netProfit) }}</td>',
      '              <td class="r mono" :class="U.signClass(t.expectancy)">{{ U.fmtMoney(t.expectancy) }}</td>',
      '              <td class="r mono neg">{{ U.fmtNum(t.maxDrawdown) }}</td>',
      '            </tr></tbody>',
      '          </table></div></div>',
      '      </div>',
      // 单档报告
      '      <div v-if="report" class="panel panel-2 mt-16">',
      '        <div class="panel-head"><div class="panel-title">单档报告 · runId {{ report.runId }}</div>',
      '          <div class="right panel-sub">{{ report.strategyName || report.strategyKey }} · {{ report.symbol }} {{ report.timeframe }} · {{ report.barCount }} 根</div></div>',
      '        <div class="panel-body">',
      '          <div class="grid g-6">',
      '            <div class="kpi"><div class="kpi-label">交易数</div><div class="kpi-value sm">{{ report.tradeCount }}</div></div>',
      '            <div class="kpi"><div class="kpi-label">轮数</div><div class="kpi-value sm">{{ report.cycleCount != null ? report.cycleCount : report.tradeCount }}</div></div>',
      '            <div class="kpi"><div class="kpi-label">胜率</div><div class="kpi-value sm">{{ U.fmtNum(report.winRatePct,1) }}%</div></div>',
      '            <div class="kpi"><div class="kpi-label">盈亏比</div><div class="kpi-value sm">{{ U.fmtNum(report.profitFactor) }}</div></div>',
      '            <div class="kpi"><div class="kpi-label">净盈亏</div><div class="kpi-value sm" :class="U.signClass(report.netProfit)">{{ U.fmtMoney(report.netProfit) }}</div></div>',
      '            <div class="kpi"><div class="kpi-label">期望/笔</div><div class="kpi-value sm" :class="U.signClass(report.expectancy)">{{ U.fmtMoney(report.expectancy) }}</div></div>',
      '            <div class="kpi"><div class="kpi-label">最大回撤</div><div class="kpi-value sm neg">{{ U.fmtNum(report.maxDrawdown) }}</div></div>',
      '          </div>',
      '          <div class="panel-sub mt-16" style="margin-bottom:6px">逐笔明细（{{ (report.trades||[]).length }} 笔）</div>',
      '          <div style="max-height:300px;overflow:auto"><table class="tbl">',
      '            <thead><tr><th>方向</th><th class="r">轮</th><th class="r">档</th><th class="r">手数</th><th class="r">开仓</th><th class="r">平仓</th><th class="r">SL</th><th class="r">TP</th><th class="r">盈亏</th><th class="r">持仓</th><th>引擎</th><th>出场</th></tr></thead>',
      '            <tbody><tr v-for="(t,i) in (report.trades||[])" :key="i">',
      '              <td><span class="badge" :class="t.side===\'BUY\'?\'buy\':\'sell\'">{{ t.side }}</span></td>',
      '              <td class="r mono">{{ t.cycleNo != null ? t.cycleNo : \'-\' }}</td>',
      '              <td class="r mono">{{ t.level != null ? t.level : \'-\' }}</td>',
      '              <td class="r mono">{{ t.volume }}</td><td class="r mono">{{ U.fmtPrice(t.openPrice,2) }}</td><td class="r mono">{{ U.fmtPrice(t.closePrice,2) }}</td>',
      '              <td class="r mono">{{ U.fmtPrice(t.sl,2) }}</td><td class="r mono">{{ U.fmtPrice(t.tp,2) }}</td>',
      '              <td class="r mono" :class="U.signClass(t.profit + (t.swap||0))">{{ U.fmtMoney(t.profit + (t.swap||0)) }}</td>',
      '              <td class="r mono">{{ U.fmtDuration(t.holdSec) }}</td>',
      '              <td class="muted" style="font-size:11px">{{ t.engineKind || \'-\' }}</td>',
      '              <td class="muted">{{ t.exitReason || \'-\' }}</td>',
      '            </tr></tbody>',
      '          </table></div>',
      '          <div class="hint mt-12">{{ report.disclaimer || \'\' }}</div>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      // ---- 门禁报告弹窗 ----
      '  <div class="overlay" v-if="gateReport" @click.self="gateReport=null">',
      '    <div class="modal wide">',
      '      <div class="modal-head"><div class="modal-title">门禁评估报告 · 策略 #{{ gateReport.id }}</div></div>',
      '      <div class="modal-body">',
      '        <div v-if="gateReport.data && gateReport.data.criteria">',
      '          <div class="flex ai-c gap-12">',
      '            <span class="badge" :class="gateReport.data.passed?\'buy\':\'sell\'">{{ gateReport.data.passed?\'门禁通过\':\'门禁未通过\' }}</span>',
      '            <span class="panel-sub">{{ gateReport.data.strategyName }} · {{ gateReport.data.symbol }} {{ gateReport.data.timeframe }} · 点差 {{ gateReport.data.spreadPoints }} / 滑点 {{ gateReport.data.slippagePoints }}</span>',
      '          </div>',
      '          <table class="tbl mt-12"><thead><tr><th>判据</th><th class="c">结果</th><th class="r">实测</th><th class="r">阈值</th><th>说明</th></tr></thead>',
      '            <tbody><tr v-for="c in gateReport.data.criteria" :key="c.id">',
      '              <td><div style="font-weight:600">{{ c.name }}</div><div class="muted" style="font-size:11px">{{ c.id }}</div></td>',
      '              <td class="c"><span class="badge" :class="c.pass?\'buy\':\'sell\'">{{ c.pass?\'通过\':\'未过\' }}</span></td>',
      '              <td class="r mono">{{ c.actual }}</td><td class="r mono">{{ c.threshold }}</td>',
      '              <td class="muted" style="font-size:12px">{{ c.message }}</td>',
      '            </tr></tbody></table>',
      '          <div class="panel-sub" style="margin:14px 0 6px">A/B 对比 · {{ gateReport.data.abConclusion }}</div>',
      '          <div class="panel-sub" style="margin:14px 0 6px">总判据</div>',
      '          <div class="jsonbox" style="max-height:120px">{{ gateReport.data.verdict }}</div>',
      '          <details class="mt-12"><summary class="muted" style="cursor:pointer">原始 JSON</summary>',
      '            <div class="jsonbox" style="max-height:300px;margin-top:8px">{{ pretty(gateReport.data) }}</div></details>',
      '        </div>',
      '        <div v-else class="jsonbox" style="max-height:460px">{{ pretty(gateReport.data) }}</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="gateReport=null">关闭</button></div>',
      '    </div>',
      '  </div>',
      // ---- 参数弹窗 ----
      '  <div class="overlay" v-if="paramRow" @click.self="paramRow=null">',
      '    <div class="modal"><div class="modal-head"><div class="modal-title">指标参数 · {{ paramRow.name }}</div></div>',
      '      <div class="modal-body">',
      '        <div class="hint" style="margin-bottom:10px">{{ paramRow.description || \'\' }}</div>',
      '        <div class="jsonbox">{{ pretty(paramRow.indicatorParams) }}</div>',
      '        <div class="panel-sub" style="margin:14px 0 6px">Prompt 摘要</div>',
      '        <div class="jsonbox" style="max-height:200px">{{ paramRow.prompt || \'-\' }}</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="paramRow=null">关闭</button></div>',
      '    </div>',
      '  </div>',
      // ---- 批准确认 ----
      '  <div class="overlay" v-if="approveRow" @click.self="approveRow=null">',
      '    <div class="modal" style="width:420px">',
      '      <div class="modal-head"><div class="modal-title">批准上线</div></div>',
      '      <div class="modal-body">',
      '        <div>批准将重跑门禁，<b>全部通过才会置为 ACTIVE</b> 并参与自动研判：</div>',
      '        <div class="jsonbox" style="margin-top:10px">{{ approveRow.name }}（v{{ approveRow.version }}）</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="approveRow=null">取消</button><button class="btn primary" :disabled="busy" @click="doApprove">确认批准</button></div>',
      '    </div>',
      '  </div>',
      // ---- AI 策略对话框（多轮对话 + 回测工具 + 保存）----
      '  <quanta-ai-chat v-model="aiOpen" persona="STRATEGY" title="AI 策略设计"',
      '    :symbol="CFG.kline.symbol" :timeframe="CFG.kline.defaultTimeframe"',
      '    :context="aiContext" :greeting="aiGreeting" :quick="aiQuick"',
      '    save-label="保存为策略" @save="onAiSave" />',
      // ---- 保存为草稿策略弹窗 ----
      '  <div class="overlay" v-if="saveOpen" style="z-index:130" @click.self="saveOpen=false">',
      '    <div class="modal wide">',
      '      <div class="modal-head"><div class="modal-title">保存为策略（草稿）</div>',
      '        <div class="right panel-sub">入库为 DRAFT · 需门禁 + 人工批准</div></div>',
      '      <div class="modal-body">',
      '        <div class="field"><label>策略名 name</label><input class="inp" v-model="saveForm.name" placeholder="如：笨猫黄金-趋势跟踪"></div>',
      '        <div class="inp-row">',
      '          <div class="field"><label>风格 regimeType</label><select class="inp" v-model="saveForm.regimeType"><option value="TREND">TREND 趋势</option><option value="RANGE">RANGE 震荡</option></select></div>',
      '          <div class="field"><label>目标盈亏比 targetRr</label><input class="inp mono" type="number" step="0.1" v-model.number="saveForm.targetRr"></div>',
      '          <div class="field"><label>预期胜率 %</label><input class="inp mono" type="number" step="1" v-model.number="saveForm.expectedWinRate" placeholder="可留空"></div>',
      '        </div>',
      '        <div class="field"><label>策略 Prompt（可编辑）</label>',
      '          <textarea class="inp" rows="10" v-model="saveForm.prompt" placeholder="AI 产出的策略描述"></textarea></div>',
      '        <div class="hint">保存后可回到「策略库」查看；需跑门禁通过并人工批准后才可能被 Agent 选中执行。</div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="saveOpen=false">取消</button>',
      '        <button class="btn primary" :disabled="busy" @click="doSaveStrategy">{{ busy ? \'保存中…\' : \'保存为草稿\' }}</button></div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return {
        U: U, CFG: CFG,
        strategies: [],
        btStrategies: [],
        btTfs: ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1'],
        bt: {
          strategyKey: 'EMA_ATR', symbol: CFG.kline.symbol, timeframe: 'M5',
          from: U.daysAgo(7), to: U.today(),
          spreadPoints: 0, slippagePoints: 0,
          spreads: CFG.limits.backtestSpreads.join(','),
        },
        running: false,
        verdict: '',
        tiers: [],
        report: null,
        gateReport: null,
        paramRow: null,
        approveRow: null,
        busy: false,
        // —— AI 策略对话框 ——
        aiOpen: false,
        aiContext: '',
        saveOpen: false,
        saveForm: { name: '', regimeType: 'TREND', targetRr: 1.5, expectedWinRate: null, prompt: '' },
      };
    },

    computed: {
      aiGreeting: function () {
        return '我是策略设计助手。把你脑子里的策略讲出来（适用行情、入场/出场、过滤条件、交易时段、风险约束都行），'
          + '我帮你整理成一条可入库的策略 Prompt，并可用内置规则策略跑真实回测看数据。';
      },
      aiQuick: function () {
        return [
          '帮我把「黄金超短线薅点」思路整理成一条策略 Prompt',
          'TINY_SCALP 在 30 点点差下的期望是多少？',
          '看看我策略库里现在有哪些策略',
        ];
      },
    },

    methods: {
      pretty: function (v) { return U.prettyJSON(v); },
      loadList: function () {
        var self = this;
        API.strategyList().then(function (l) { self.strategies = l || []; });
      },
      loadBtStrategies: function () {
        var self = this;
        API.backtestStrategies().then(function (l) {
          self.btStrategies = l || [];
          if (self.btStrategies.length && !self.btStrategies.some(function (s) { return s.key === self.bt.strategyKey; })) {
            self.bt.strategyKey = self.btStrategies[0].key;
          }
        }).catch(function () { self.btStrategies = []; });
      },

      showParams: function (s) { this.paramRow = s; },

      // ---- AI 策略对话框 ----
      openAi: function () { this.aiContext = ''; this.aiOpen = true; },
      polish: function (s) {
        this.aiContext = '这是策略库中已有策略《' + s.name + '》(id=' + s.id + ') 的 Prompt，'
          + '请评估它的结构完整性、指出缺口，并给出改进后的完整 Prompt：\n' + (s.prompt || '（该策略暂无 prompt）');
        this.aiOpen = true;
      },
      onAiSave: function (payload) {
        var prompt = (payload && payload.prompt) || '';
        this.saveForm = {
          name: '', regimeType: 'TREND', targetRr: 1.5, expectedWinRate: null, prompt: prompt,
        };
        var m = /策略名[：:]\s*([^\n]{2,40})/.exec(prompt);
        if (m) this.saveForm.name = m[1].trim();
        this.saveOpen = true;
      },
      doSaveStrategy: function () {
        var self = this;
        if (!this.saveForm.name || !this.saveForm.name.trim()) { Toast.warn('请填写策略名'); return; }
        if (!this.saveForm.prompt || !this.saveForm.prompt.trim()) { Toast.warn('策略 Prompt 不能为空'); return; }
        this.busy = true;
        API.strategyCreate({
          name: this.saveForm.name.trim(),
          prompt: this.saveForm.prompt,
          regimeType: this.saveForm.regimeType,
          targetRr: this.saveForm.targetRr == null ? null : Number(this.saveForm.targetRr),
          expectedWinRate: (this.saveForm.expectedWinRate == null || this.saveForm.expectedWinRate === '')
            ? null : Number(this.saveForm.expectedWinRate),
          origin: 'AI',
        }).then(function (s) {
          Toast.ok('已保存为草稿策略 #' + (s && s.id));
          self.saveOpen = false;
          self.loadList();
        }).catch(function (e) { API.fail(e, '保存失败'); })
          .then(function () { self.busy = false; });
      },

      runGate: function (s) {
        var self = this;
        this.busy = true;
        API.strategyGate(s.id, {}).then(function (rep) {
          self.gateReport = { id: s.id, data: rep };
        }).catch(function (e) { API.fail(e, '门禁评估失败'); })
          .then(function () { self.busy = false; });
      },
      askApprove: function (s) { this.approveRow = s; },
      doApprove: function () {
        var self = this;
        this.busy = true;
        API.strategyApprove(this.approveRow.id, {}).then(function (s2) {
          Toast.ok('策略已置为 ' + (s2.status || 'ACTIVE'));
          self.approveRow = null; self.loadList();
        }).catch(function (e) { API.fail(e, '批准失败（门禁未全过？）'); })
          .then(function () { self.busy = false; });
      },

      spec: function () {
        return {
          symbol: this.bt.symbol, timeframe: this.bt.timeframe,
          from: this.bt.from, to: this.bt.to, strategyKey: this.bt.strategyKey,
        };
      },
      runSingle: function () {
        var self = this;
        this.running = true; this.report = null;
        var s = this.spec();
        s.spreadPoints = this.bt.spreadPoints; s.slippagePoints = this.bt.slippagePoints;
        API.backtestRun(s).then(function (rep) {
          self.report = rep; self.tiers = []; self.verdict = '';
          Toast.ok('回测完成：' + rep.tradeCount + ' 笔，净盈亏 ' + U.fmtMoney(rep.netProfit));
        }).catch(function (e) { API.fail(e, '回测失败'); })
          .then(function () { self.running = false; });
      },
      runTiers: function () {
        var self = this;
        this.running = true; this.report = null;
        var spreads = String(this.bt.spreads || '').split(',').map(function (x) { return parseInt(x.trim(), 10); })
          .filter(function (x) { return !isNaN(x); });
        var s = this.spec();
        s.spreads = spreads; s.slippages = [this.bt.slippagePoints || 0];
        API.backtestTiers(s).then(function (res) {
          self.tiers = (res && res.tiers) || [];
          self.verdict = (res && res.verdict) || '';
          self.renderTierChart();
          Toast.ok('多档扫描完成：' + self.tiers.length + ' 档');
        }).catch(function (e) { API.fail(e, '扫描失败'); })
          .then(function () { self.running = false; });
      },
      backfill: function () {
        API.backtestBackfill({ symbol: this.bt.symbol, timeframe: this.bt.timeframe, days: 7 })
          .then(function (r) { Toast.ok('已回填历史 K 线'); })
          .catch(function (e) { API.fail(e, '回填失败'); });
      },

      renderTierChart: function () {
        var self = this;
        this.$nextTick(function () {
          if (!self.$refs.tierChart || !self.tiers.length) return;
          var cats = [], net = [], exp = [];
          for (var i = 0; i < self.tiers.length; i++) {
            cats.push(self.tiers[i].spreadPoints + '');
            net.push(Number(self.tiers[i].netProfit || 0));
            exp.push(Number(self.tiers[i].expectancy || 0));
          }
          CH.setOption(self.$refs.tierChart, CH.tierOption(cats, net, exp));
        });
      },
      onResize: function () { CH.resize(this.$refs.tierChart); },
    },

    mounted: function () {
      this.loadList();
      this.loadBtStrategies();
      window.addEventListener('resize', this.onResize);
    },
    beforeUnmount: function () {
      window.removeEventListener('resize', this.onResize);
      CH.dispose(this.$refs.tierChart);
    },
  };
})(window);
