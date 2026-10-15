/**
 * views/config.js —— 连接配置（MT5 / LLM / MAIL：增删改测激活）
 */
(function (global) {
  'use strict';
  global.QuantaViews = global.QuantaViews || {};

  var API = global.QuantaAPI, U = global.QuantaUtil, Toast = global.QuantaToast;

  function pick(map, aliases, fallback) {
    if (!map) return fallback;
    for (var i = 0; i < aliases.length; i++) {
      if (map[aliases[i]] !== undefined && map[aliases[i]] !== null) return map[aliases[i]];
    }
    return fallback;
  }

  /** 数值兜底：null/undefined/空串/NaN → fallback；否则转数字。 */
  function num(v, fallback) {
    if (v === null || v === undefined || v === '') return fallback;
    var n = Number(v);
    return isNaN(n) ? fallback : n;
  }

  function blankForm(type) {
    return {
      id: null, configType: type, name: '', active: true,
      // MT5
      mt5Login: '', mt5Password: '', mt5Server: '',
      // LLM
      interfaceType: '', baseUrl: '', modelName: '', apiKey: '', tokenBudgetDaily: '',
      // MAIL
      provider: '', account: '', imapHost: '', imapPort: '', smtpHost: '', smtpPort: '',
      ssl: true, authCode: '', folder: '', recipients: '',
    };
  }

  global.QuantaViews.config = {
    template: [
      '<div class="view">',
      // ---- 策略锁档面板（cycle 执行器三层亏损锁 + 锁定时长） ----
      '  <div class="panel" style="margin-bottom:14px">',
      '    <div class="panel-head"><div class="panel-title">策略锁档（笨猫马丁-网格双边）</div>',
      '      <div class="right flex gap-8 ai-c"><span class="panel-sub">填 0 = 该档不启用（无上限）</span>',
      '        <button class="btn sm ghost" :disabled="lockBusy" @click="loadLock">刷新</button>',
      '        <button class="btn sm primary" :disabled="lockBusy" @click="saveLock">{{ lockBusy ? \'保存中…\' : \'保存锁档\' }}</button></div></div>',
      '    <div class="panel-body">',
      '      <div class="inp-row">',
      '        <div class="field"><label>单轮最大亏损（%）</label><input class="inp mono" type="number" step="0.1" min="0" max="100" v-model.number="lock.maxSingleLossPct"></div>',
      '        <div class="field"><label>单日最大亏损（%）</label><input class="inp mono" type="number" step="0.1" min="0" max="100" v-model.number="lock.maxDailyLossPct"></div>',
      '      </div>',
      '      <div class="inp-row">',
      '        <div class="field"><label>累计最大亏损（%）</label><input class="inp mono" type="number" step="0.1" min="0" max="100" v-model.number="lock.maxTotalLossPct"></div>',
      '        <div class="field" style="max-width:200px"><label>锁定时长（小时）</label><input class="inp mono" type="number" step="1" min="0" v-model.number="lock.lockHours"></div>',
      '      </div>',
      '      <div class="hint" style="margin-top:6px">单轮=轮内浮亏触阈即整轮硬止损平仓；单日=当日实现净亏触阈后当日不再开新轮（次日 00:00 自动解除）；累计=相对参照权益的累计净亏触阈后持久锁定（需人工解除）。数值 0 表示该档关闭、不设上限。</div>',
      '      <div v-if="allLocksOff" class="hint neg" style="margin-top:6px">注意：三层全为 0 = 完全关闭止损，执行器的「整轮止损」不会生效，持仓只能靠券商侧的单腿黑障兜底。请至少给「单轮最大亏损」填一个正数。</div>',
      '    </div>',
      '  </div>',
      // ---- 账户风控阈值面板（RiskGuard 开仓门槛；写 risk_config 唯一行） ----
      '  <div class="panel" style="margin-bottom:14px">',
      '    <div class="panel-head"><div class="panel-title">账户风控阈值（RiskGuard 开仓门槛）</div>',
      '      <div class="right flex gap-8 ai-c"><span class="panel-sub">日亏熔断 0 = 一亏即熔断（不是关闭）</span>',
      '        <button class="btn sm ghost" :disabled="riskBusy" @click="loadRisk">刷新</button>',
      '        <button class="btn sm primary" :disabled="riskBusy" @click="saveRisk">{{ riskBusy ? \'保存中…\' : \'保存阈值\' }}</button></div></div>',
      '    <div class="panel-body">',
      '      <div class="inp-row">',
      '        <div class="field"><label>日亏熔断（USD）</label><input class="inp mono" type="number" step="1" min="0" v-model.number="risk.maxDailyLoss"></div>',
      '        <div class="field"><label>单笔手数上限</label><input class="inp mono" type="number" step="0.01" min="0" v-model.number="risk.maxVolume"></div>',
      '      </div>',
      '      <div class="inp-row">',
      '        <div class="field"><label>最大同时持仓</label><input class="inp mono" type="number" step="1" min="1" v-model.number="risk.maxPositions"></div>',
      '        <div class="field"><label>单笔风险（% 净值）</label><input class="inp mono" type="number" step="0.1" min="0" v-model.number="risk.maxRiskPct"></div>',
      '      </div>',
      '      <div class="inp-row">',
      '        <div class="field"><label>最小盈亏比</label><input class="inp mono" type="number" step="0.1" min="0" v-model.number="risk.minRr"></div>',
      '        <div class="field"><label>SL 最小点差倍数</label><input class="inp mono" type="number" step="0.1" min="0" v-model.number="risk.minSlSpreadMult"></div>',
      '      </div>',
      '      <div class="inp-row">',
      '        <div class="field"><label>连亏次数上限</label><input class="inp mono" type="number" step="1" min="0" v-model.number="risk.maxConsecutiveLoss"></div>',
      '        <div class="field"><label>连亏冷静期（分钟）</label><input class="inp mono" type="number" step="1" min="0" v-model.number="risk.cooldownMinutes"></div>',
      '      </div>',
      '      <div class="inp-row">',
      '        <div class="field"><label>点差上限</label><input class="inp mono" type="number" step="1" min="0" v-model.number="risk.spreadLimit"></div>',
      '        <div class="field"><label>品种白名单（逗号分隔）</label><input class="inp mono" v-model="risk.symbolWhitelist"></div>',
      '      </div>',
      '      <div class="hint" style="margin-top:6px"><b>日亏熔断</b>：当日已实现亏损达到该值即禁止开仓、只允许平仓，自然日 00:00 自动解除（对应风控告警邮件里的「R6」）。注意 <b>填 0 不是关闭</b>——表示只要当日亏损 ≥ 0 就熔断（最严）；要实质关闭须填一个很大的数。</div>',
      '      <div class="hint" style="margin-top:4px">多腿/马丁执行器走 GRID 专用档：单笔手数上限 / 最大持仓 / 最小盈亏比 / 单笔风险由 <span class="mono">grid_*</span> 列另算；<b>日亏熔断、SL 最小点差倍数、点差上限、白名单</b>两档共用，改这里即对全部策略生效。</div>',
      '    </div>',
      '  </div>',
      '  <div class="tabs">',
      '    <button v-for="t in types" :key="t.key" :class="{on: cur===t.key}" @click="switchTab(t.key)">{{ t.label }}</button>',
      '  </div>',
      '  <div class="panel">',
      '    <div class="panel-head"><div class="panel-title">{{ curLabel }} 配置</div>',
      '      <div class="right flex gap-8 ai-c"><span class="panel-sub">{{ list.length }} 条 · 同一时刻仅一条激活</span>',
      '        <button class="btn sm ghost" @click="load">刷新</button>',
      '        <button class="btn sm primary" @click="openNew">新增配置</button></div></div>',
      '    <div class="panel-body nopad">',
      '      <table class="tbl" v-if="list.length">',
      '        <thead><tr><th>名称</th><th class="c">激活</th><th class="c">测试状态</th><th>测试时间</th><th>配置详情</th><th class="c">操作</th></tr></thead>',
      '        <tbody>',
      '          <tr v-for="c in list" :key="c.id">',
      '            <td><div style="font-weight:600">{{ c.name || \'-\' }}</div><div class="muted" style="font-size:11px">#{{ c.id }}</div></td>',
      '            <td class="c"><span class="badge" :class="c.isActive?\'ok\':\'\'">{{ c.isActive ? \'激活中\' : \'未激活\' }}</span></td>',
      '            <td class="c"><span class="badge" :class="testClass(c.testStatus)">{{ c.testStatus || \'未测试\' }}</span></td>',
      '            <td class="muted">{{ U.fmtTime(c.testedAt) }}</td>',
      '            <td><div class="flex wrap gap-8">',
      '              <span v-for="(v,k) in c.config" :key="k" class="badge">{{ k }}: {{ v }}</span>',
      '              <span v-if="!c.config || !Object.keys(c.config).length" class="muted">-</span>',
      '            </div></td>',
      '            <td class="c nowrap">',
      '              <button class="btn xs ok" :disabled="c.isActive" @click="askActivate(c)">激活</button>',
      '              <button class="btn xs" @click="openEdit(c)">编辑</button>',
      '              <button class="btn xs danger" @click="askDelete(c)">删除</button>',
      '            </td>',
      '          </tr>',
      '        </tbody>',
      '      </table>',
      '      <div v-else class="empty"><div class="empty-ico">◍</div>暂无 {{ curLabel }} 配置</div>',
      '    </div>',
      '  </div>',
      // ---- 编辑弹窗 ----
      '  <div class="overlay" v-if="form" @click.self="form=null">',
      '    <div class="modal wide">',
      '      <div class="modal-head"><div class="modal-title">{{ form.id ? \'编辑\' : \'新增\' }} {{ curLabel }} 配置</div></div>',
      '      <div class="modal-body">',
      '        <div class="inp-row">',
      '          <div class="field"><label>配置名称</label><input class="inp" v-model="form.name" placeholder="如 主账户 / DeepSeek"></div>',
      '          <div class="field" style="max-width:140px"><label>启用</label><select class="inp" v-model="form.active"><option :value="true">是</option><option :value="false">否</option></select></div>',
      '        </div>',
      // MT5
      '        <template v-if="cur===\'MT5\'">',
      '          <div class="inp-row">',
      '            <div class="field"><label>账号 login</label><input class="inp mono" v-model="form.mt5Login"></div>',
      '            <div class="field"><label>服务器 server</label><input class="inp mono" v-model="form.mt5Server"></div>',
      '          </div>',
      '          <div class="field"><label>密码 password</label><input class="inp mono" type="password" v-model="form.mt5Password" placeholder="留空=不修改"></div>',
      '        </template>',
      // LLM
      '        <template v-if="cur===\'LLM\'">',
      '          <div class="inp-row">',
      '            <div class="field"><label>接口类型 interfaceType</label><input class="inp mono" v-model="form.interfaceType" placeholder="如 OPENAI / DEEPSEEK"></div>',
      '            <div class="field"><label>模型 modelName</label><input class="inp mono" v-model="form.modelName" placeholder="deepseek-chat"></div>',
      '          </div>',
      '          <div class="field"><label>BaseUrl</label><input class="inp mono" v-model="form.baseUrl" placeholder="https://api.deepseek.com"></div>',
      '          <div class="inp-row">',
      '            <div class="field"><label>API Key</label><input class="inp mono" type="password" v-model="form.apiKey" placeholder="留空=不修改"></div>',
      '            <div class="field" style="max-width:160px"><label>日 Token 预算</label><input class="inp mono" type="number" v-model.number="form.tokenBudgetDaily"></div>',
      '          </div>',
      '        </template>',
      // MAIL
      '        <template v-if="cur===\'MAIL\'">',
      '          <div class="inp-row">',
      '            <div class="field"><label>服务商 provider</label><input class="inp mono" v-model="form.provider" placeholder="QQ / 163"></div>',
      '            <div class="field"><label>邮箱账号</label><input class="inp mono" v-model="form.account"></div>',
      '          </div>',
      '          <div class="field"><label>授权码 authCode</label><input class="inp mono" type="password" v-model="form.authCode" placeholder="留空=不修改"></div>',
      '          <div class="inp-row">',
      '            <div class="field"><label>SMTP Host</label><input class="inp mono" v-model="form.smtpHost"></div>',
      '            <div class="field" style="max-width:120px"><label>SMTP 端口</label><input class="inp mono" type="number" v-model.number="form.smtpPort"></div>',
      '          </div>',
      '          <div class="inp-row">',
      '            <div class="field"><label>IMAP Host</label><input class="inp mono" v-model="form.imapHost"></div>',
      '            <div class="field" style="max-width:120px"><label>IMAP 端口</label><input class="inp mono" type="number" v-model.number="form.imapPort"></div>',
      '          </div>',
      '          <div class="inp-row">',
      '            <div class="field" style="max-width:120px"><label>SSL</label><select class="inp" v-model="form.ssl"><option :value="true">启用</option><option :value="false">关闭</option></select></div>',
      '            <div class="field"><label>收件文件夹</label><input class="inp mono" v-model="form.folder" placeholder="INBOX"></div>',
      '          </div>',
      '          <div class="field"><label>收件人（逗号分隔）</label><input class="inp mono" v-model="form.recipients" placeholder="a@x.com,b@y.com"></div>',
      '        </template>',
      '        <div v-if="testMsg" class="panel-2" style="border-radius:8px;padding:9px 12px;margin-top:6px;border:1px solid var(--border)">',
      '          <div class="panel-sub">最近测试结果</div><div style="margin-top:3px" :class="testOk?\'pos\':\'neg\'">{{ testMsg }}</div>',
      '        </div>',
      '        <div class="hint" style="margin-top:10px">凭据以密文入库，列表中仅展示掩码；编辑时凭据字段留空表示不修改。</div>',
      '      </div>',
      '      <div class="modal-foot">',
      '        <button class="btn ghost" :disabled="busy" @click="doTest">测试连接</button>',
      '        <div class="right flex gap-8"><button class="btn ghost" @click="form=null">取消</button>',
      '          <button class="btn primary" :disabled="busy" @click="doSave">{{ busy ? \'保存中…\' : \'保存\' }}</button></div>',
      '      </div>',
      '    </div>',
      '  </div>',
      // ---- 激活/删除确认 ----
      '  <div class="overlay" v-if="confirm" @click.self="confirm=null">',
      '    <div class="modal" style="width:400px">',
      '      <div class="modal-head"><div class="modal-title">{{ confirm.title }}</div></div>',
      '      <div class="modal-body">{{ confirm.desc }}</div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="confirm=null">取消</button>',
      '        <button class="btn danger" :disabled="busy" @click="confirm.run">确认</button></div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return {
        U: U,
        types: [{ key: 'MT5', label: 'MT5 桥接' }, { key: 'LLM', label: 'LLM 模型' }, { key: 'MAIL', label: 'MAIL 邮箱' }],
        cur: 'MT5',
        list: [],
        form: null,
        confirm: null,
        busy: false,
        testMsg: '',
        testOk: false,
        // 策略锁档（三层亏损锁 + 锁定时长；0 = 不启用/无上限）
        // 默认值与后端配置一致（5/3/10/24）：万一首次加载失败，面板也不能是「全关」，
        // 否则一次误保存就会把全部止损关掉（2026-10-15 已踩过一次）。
        lock: { maxSingleLossPct: 5, maxDailyLossPct: 3, maxTotalLossPct: 10, lockHours: 24 },
        lockBusy: false,
        // 账户风控阈值（RiskGuard；日亏熔断 maxDailyLoss 单位 USD）。默认值与后端兜底一致，
        // 避免首次加载失败时面板显示成 0（0 对日亏熔断是最严档，不是关闭）。
        risk: { maxDailyLoss: 50, maxVolume: 0.01, maxPositions: 3, maxRiskPct: 2, minRr: 1,
                minSlSpreadMult: 5, maxConsecutiveLoss: 3, cooldownMinutes: 0, spreadLimit: 50,
                symbolWhitelist: 'GOLD' },
        riskBusy: false,
      };
    },

    computed: {
      curLabel: function () {
        var t = this.types.filter(function (x) { return x.key === this.cur; }, this)[0];
        return t ? t.label : this.cur;
      },
      /** 三层锁是否全为 0（= 完全关闭止损）：用于面板告警与保存前二次确认。 */
      allLocksOff: function () {
        return !num(this.lock.maxSingleLossPct, 0)
          && !num(this.lock.maxDailyLossPct, 0)
          && !num(this.lock.maxTotalLossPct, 0);
      },
    },

    methods: {
      testClass: function (s) {
        s = (s || '').toUpperCase();
        if (s.indexOf('OK') >= 0 || s.indexOf('SUCCESS') >= 0 || s.indexOf('PASS') >= 0) return 'ok';
        if (s.indexOf('FAIL') >= 0 || s.indexOf('ERR') >= 0) return 'fail';
        return '';
      },
      switchTab: function (k) { this.cur = k; this.form = null; this.load(); },
      load: function () {
        var self = this;
        return API.configsList(this.cur).then(function (l) { self.list = l || []; })
          .catch(function (e) { self.list = []; API.fail(e, '读取配置失败'); });
      },

      // ---- 策略锁档 ----
      loadLock: function () {
        var self = this;
        this.lockBusy = true;
        return API.cycleSettingsGet().then(function (s) {
          if (!s) return;
          // 兜底值取后端配置默认（5/3/10/24），不用 0：避免"没读到"被当成"全关"
          self.lock = {
            maxSingleLossPct: num(s.maxSingleLossPct, 5),
            maxDailyLossPct: num(s.maxDailyLossPct, 3),
            maxTotalLossPct: num(s.maxTotalLossPct, 10),
            lockHours: num(s.lockHours, 24),
          };
        }).catch(function (e) { API.fail(e, '读取锁档失败'); })
          .then(function () { self.lockBusy = false; });
      },
      /** 三层全为 0 时先弹确认，避免一次误点就把全部止损关掉。 */
      saveLock: function () {
        var self = this;
        if (this.allLocksOff) {
          this.confirm = {
            title: '确认关闭全部止损？',
            desc: '三层亏损锁全为 0：执行器的「整轮止损」将不生效，持仓只能靠券商侧单腿黑障兜底，风险极高。确定要保存吗？',
            run: function () { self.confirm = null; self.doSaveLock(); },
          };
          return;
        }
        this.doSaveLock();
      },
      doSaveLock: function () {
        var self = this;
        this.lockBusy = true;
        API.cycleSettingsPut({
          maxSingleLossPct: num(this.lock.maxSingleLossPct, 5),
          maxDailyLossPct: num(this.lock.maxDailyLossPct, 3),
          maxTotalLossPct: num(this.lock.maxTotalLossPct, 10),
          lockHours: num(this.lock.lockHours, 24),
        }).then(function () {
          Toast.ok('锁档已保存');
          self.loadLock();
        }).catch(function (e) { API.fail(e, '保存锁档失败'); })
          .then(function () { self.lockBusy = false; });
      },

      // ---- 账户风控阈值（RiskGuard） ----
      loadRisk: function () {
        var self = this;
        this.riskBusy = true;
        return API.riskThresholds().then(function (r) {
          var t = (r && r.thresholds) || r || {};
          var wl = t.symbolWhitelist;
          if (Object.prototype.toString.call(wl) === '[object Array]') wl = wl.join(',');
          self.risk = {
            maxDailyLoss: num(t.maxDailyLoss, 50),
            maxVolume: num(t.maxVolume, 0.01),
            maxPositions: num(t.maxPositions, 3),
            maxRiskPct: num(t.maxRiskPct, 2),
            minRr: num(t.minRr, 1),
            minSlSpreadMult: num(t.minSlSpreadMult, 5),
            maxConsecutiveLoss: num(t.maxConsecutiveLoss, 3),
            cooldownMinutes: num(t.cooldownMinutes, 0),
            spreadLimit: num(t.spreadLimit, 50),
            symbolWhitelist: (wl === undefined || wl === null || wl === '') ? 'GOLD' : String(wl),
          };
        }).catch(function (e) { API.fail(e, '读取风控阈值失败'); })
          .then(function () { self.riskBusy = false; });
      },
      /** 日亏熔断填 0 是最严档（一亏即熔断），保存前先提醒一次。 */
      saveRisk: function () {
        var self = this;
        if (!num(this.risk.maxDailyLoss, 50)) {
          this.confirm = {
            title: '确认把日亏熔断设为 0？',
            desc: '日亏熔断填 0 不是「关闭」，而是「当日亏损 ≥ 0 就熔断」——等于开盘后只要出现任何亏损就立即禁止开仓。要实质关闭请改填一个很大的数（如 999999）。确定要保存吗？',
            run: function () { self.confirm = null; self.doSaveRisk(); },
          };
          return;
        }
        this.doSaveRisk();
      },
      doSaveRisk: function () {
        var self = this;
        this.riskBusy = true;
        API.riskThresholdsPut({
          maxDailyLoss: num(this.risk.maxDailyLoss, 50),
          maxVolume: num(this.risk.maxVolume, 0.01),
          maxPositions: num(this.risk.maxPositions, 3),
          maxRiskPct: num(this.risk.maxRiskPct, 2),
          minRr: num(this.risk.minRr, 1),
          minSlSpreadMult: num(this.risk.minSlSpreadMult, 5),
          maxConsecutiveLoss: num(this.risk.maxConsecutiveLoss, 3),
          cooldownMinutes: num(this.risk.cooldownMinutes, 0),
          spreadLimit: num(this.risk.spreadLimit, 50),
          symbolWhitelist: String(this.risk.symbolWhitelist || 'GOLD').split(',')
            .map(function (x) { return x.trim(); }).filter(Boolean),
        }).then(function () {
          Toast.ok('风控阈值已保存');
          self.loadRisk();
        }).catch(function (e) { API.fail(e, '保存风控阈值失败'); })
          .then(function () { self.riskBusy = false; });
      },

      openNew: function () { this.form = blankForm(this.cur); this.testMsg = ''; },
      openEdit: function (c) {
        var f = blankForm(this.cur);
        f.id = c.id;
        f.name = c.name || '';
        f.active = c.isActive !== undefined ? c.isActive : true;
        var m = c.config || {};
        if (this.cur === 'MT5') {
          f.mt5Login = pick(m, ['mt5Login', 'login', 'account', 'username'], '');
          f.mt5Server = pick(m, ['mt5Server', 'server'], '');
          f.mt5Password = '';
        } else if (this.cur === 'LLM') {
          f.interfaceType = pick(m, ['interfaceType', 'interface_type', 'type'], '');
          f.baseUrl = pick(m, ['baseUrl', 'base_url', 'url'], '');
          f.modelName = pick(m, ['modelName', 'model_name', 'model'], '');
          f.tokenBudgetDaily = pick(m, ['tokenBudgetDaily', 'token_budget_daily', 'budget'], '');
          f.apiKey = '';
        } else {
          f.provider = pick(m, ['provider'], '');
          f.account = pick(m, ['account', 'email', 'username'], '');
          f.imapHost = pick(m, ['imapHost', 'imap_host'], '');
          f.imapPort = pick(m, ['imapPort', 'imap_port'], '');
          f.smtpHost = pick(m, ['smtpHost', 'smtp_host'], '');
          f.smtpPort = pick(m, ['smtpPort', 'smtp_port'], '');
          f.ssl = pick(m, ['ssl', 'useSsl', 'use_ssl'], true);
          f.folder = pick(m, ['folder'], '');
          f.recipients = (pick(m, ['recipients', 'to'], []) || []).join ? pick(m, ['recipients', 'to'], []).join(',') : '';
          f.authCode = '';
        }
        this.form = f; this.testMsg = '';
      },

      buildBody: function () {
        var f = this.form;
        var b = { configType: this.cur, name: f.name, active: !!f.active };
        if (f.id) b.id = f.id;
        if (this.cur === 'MT5') {
          if (f.mt5Login) b.mt5Login = f.mt5Login;
          if (f.mt5Password) b.mt5Password = f.mt5Password;
          if (f.mt5Server) b.mt5Server = f.mt5Server;
        } else if (this.cur === 'LLM') {
          if (f.interfaceType) b.interfaceType = f.interfaceType;
          if (f.baseUrl) b.baseUrl = f.baseUrl;
          if (f.modelName) b.modelName = f.modelName;
          if (f.apiKey) b.apiKey = f.apiKey;
          if (f.tokenBudgetDaily) b.tokenBudgetDaily = f.tokenBudgetDaily;
        } else {
          if (f.provider) b.provider = f.provider;
          if (f.account) b.account = f.account;
          if (f.authCode) b.authCode = f.authCode;
          if (f.imapHost) b.imapHost = f.imapHost;
          if (f.imapPort) b.imapPort = f.imapPort;
          if (f.smtpHost) b.smtpHost = f.smtpHost;
          if (f.smtpPort) b.smtpPort = f.smtpPort;
          b.ssl = !!f.ssl;
          if (f.folder) b.folder = f.folder;
          b.recipients = String(f.recipients || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean);
        }
        return b;
      },

      doTest: function () {
        if (!this.form.name) { Toast.warn('请先填写配置名称'); return; }
        var self = this;
        this.busy = true; this.testMsg = '';
        API.configTest(this.buildBody()).then(function (r) {
          self.testOk = true;
          self.testMsg = '通过：' + (r && (r.message || r.detail)) || '连接正常';
          Toast.ok('测试通过');
        }).catch(function (e) {
          self.testOk = false;
          self.testMsg = '失败：' + (e.message || e);
          Toast.err('测试失败：' + (e.message || e), e.traceId);
        }).then(function () { self.busy = false; });
      },
      doSave: function () {
        if (!this.form.name) { Toast.warn('请先填写配置名称'); return; }
        var self = this, body = this.buildBody();
        this.busy = true;
        var p = body.id ? API.configUpdate(body.id, body) : API.configSave(body);
        p.then(function () {
          Toast.ok('配置已保存');
          self.form = null; self.load();
        }).catch(function (e) { API.fail(e, '保存失败'); })
          .then(function () { self.busy = false; });
      },

      askActivate: function (c) {
        var self = this;
        this.confirm = {
          title: '激活配置', desc: '将激活「' + (c.name || c.id) + '」，同类型其它配置会自动停用。',
          run: function () {
            self.busy = true;
            API.configActivate(c.id).then(function () { Toast.ok('已激活'); self.confirm = null; self.load(); })
              .catch(function (e) { API.fail(e, '激活失败'); }).then(function () { self.busy = false; });
          },
        };
      },
      askDelete: function (c) {
        var self = this;
        this.confirm = {
          title: '删除配置', desc: '将永久删除「' + (c.name || c.id) + '」，此操作不可恢复。',
          run: function () {
            self.busy = true;
            API.configDelete(c.id).then(function () { Toast.ok('已删除'); self.confirm = null; self.load(); })
              .catch(function (e) { API.fail(e, '删除失败'); }).then(function () { self.busy = false; });
          },
        };
      },
    },

    mounted: function () { this.load(); this.loadLock(); this.loadRisk(); },
  };
})(window);
