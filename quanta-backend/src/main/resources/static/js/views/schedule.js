/**
 * views/schedule.js —— 调度与触发（触发源开关 + 盯盘状态 + 最近命中 + 人工唤醒）
 */
(function (global) {
  'use strict';
  global.QuantaViews = global.QuantaViews || {};

  var API = global.QuantaAPI, U = global.QuantaUtil, CFG = global.QuantaConfig, Toast = global.QuantaToast;

  global.QuantaViews.schedule = {
    template: [
      '<div class="view">',
      '  <div class="grid g-4">',
      '    <div class="kpi"><div class="kpi-label">调度总开关</div>',
      '      <div class="kpi-value sm" :class="st.enabled?\'pos\':\'neg\'">{{ st.enabled ? \'已启用\' : \'已停用\' }}</div>',
      '      <div class="kpi-foot">最大并发会话 {{ st.maxSession != null ? st.maxSession : \'-\' }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">活跃会话</div><div class="kpi-value sm">{{ st.activeSessions != null ? st.activeSessions : \'-\' }}</div><div class="kpi-foot">排队 {{ st.queued != null ? st.queued : \'-\' }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">盯盘连接</div>',
      '      <div class="kpi-value sm flex ai-c gap-8"><span class="dot" :class="mw.connected?\'on\':\'off\'"></span>{{ mw.connected ? \'在线\' : \'离线\' }}</div>',
      '      <div class="kpi-foot">{{ mw.symbol || \'-\' }} · {{ mw.timeframe || \'-\' }} · 连续失败 {{ mw.consecutiveFailures != null ? mw.consecutiveFailures : 0 }}</div></div>',
      '    <div class="kpi"><div class="kpi-label">最新 K 线</div><div class="kpi-value sm mono">{{ U.fmtPrice(mw.last,2) }}</div>',
      '      <div class="kpi-foot">bar {{ U.fmtTime(mw.lastBarTime) }} · 采集 {{ U.fmtTimeShort(mw.capturedAt) }}</div></div>',
      '  </div>',
      // ---- 触发源 ----
      '  <div class="panel mt-16">',
      '    <div class="panel-head"><div class="panel-title">触发源（T1 ~ T8）</div>',
      '      <div class="right flex gap-8 ai-c"><button class="btn sm ghost" @click="load">刷新</button><button class="btn sm primary" @click="wakeOpen=true">人工唤醒</button></div></div>',
      '    <div class="panel-body">',
      '      <div class="grid g-4">',
      '        <div v-for="k in switchKeys" :key="k" class="panel panel-2" style="border-radius:10px;padding:11px 13px">',
      '          <div class="flex ai-c gap-8"><span class="dot" :class="sw[k]?\'on\':\'off\'"></span>',
      '            <span style="font-weight:600">{{ k }}</span><span class="muted">{{ label(k) }}</span></div>',
      '          <div class="hint" style="margin-top:5px">{{ sw[k] ? \'已启用\' : \'已停用\' }}</div>',
      '        </div>',
      '      </div>',
      '      <div v-if="!switchKeys.length" class="empty"><div class="empty-ico">◍</div>暂无触发源配置</div>',
      '    </div>',
      '  </div>',
      // ---- 最近命中 ----
      '  <div class="panel mt-16">',
      '    <div class="panel-head"><div class="panel-title">最近命中</div><div class="right panel-sub">{{ hits.length }} 条</div></div>',
      '    <div class="panel-body nopad" style="max-height:420px;overflow:auto">',
      '      <table class="tbl" v-if="hits.length">',
      '        <thead><tr><th>来源</th><th>品种</th><th>原因</th><th>计划</th><th>Ticket</th><th>Bar 时间</th><th>检测时间</th></tr></thead>',
      '        <tbody>',
      '          <tr v-for="(h,i) in hits" :key="i">',
      '            <td><span class="badge">{{ h.source || \'-\' }}</span></td>',
      '            <td>{{ h.symbol || \'-\' }}</td>',
      '            <td class="muted" style="max-width:340px">{{ h.reason || \'-\' }}</td>',
      '            <td class="mono">{{ h.planId != null ? h.planId : \'-\' }}</td>',
      '            <td class="mono">{{ h.ticket != null ? h.ticket : \'-\' }}</td>',
      '            <td class="muted">{{ U.fmtTime(h.barTime) }}</td>',
      '            <td class="muted">{{ U.fmtTime(h.detectedAt) }}</td>',
      '          </tr>',
      '        </tbody>',
      '      </table>',
      '      <div v-else class="empty"><div class="empty-ico">◍</div>暂无命中记录</div>',
      '    </div>',
      '  </div>',
      // ---- 唤醒确认 ----
      '  <div class="overlay" v-if="wakeOpen" @click.self="wakeOpen=false">',
      '    <div class="modal" style="width:420px">',
      '      <div class="modal-head"><div class="modal-title">人工唤醒</div></div>',
      '      <div class="modal-body">',
      '        <div>将向调度器投递一次唤醒事件（T6），可能触发自动研判与下单。请填写原因以便审计。</div>',
      '        <div class="field" style="margin-top:12px"><label>唤醒原因</label><input class="inp" v-model="wakeReason" placeholder="如：手动补一次盯盘"></div>',
      '      </div>',
      '      <div class="modal-foot"><button class="btn ghost" @click="wakeOpen=false">取消</button><button class="btn primary" :disabled="busy" @click="doWake">确认唤醒</button></div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return { U: U, CFG: CFG, st: {}, mw: {}, sw: {}, hits: [], wakeOpen: false, wakeReason: '', busy: false, timer: null };
    },

    computed: {
      switchKeys: function () {
        return Object.keys(this.sw || {}).sort(function (a, b) { return a.localeCompare(b); });
      },
    },

    methods: {
      label: function (k) { return CFG.triggerLabels[k] || '触发源'; },
      load: function () {
        var self = this;
        return API.scheduleStatus().then(function (s) {
          if (!s) return;
          self.st = s;
          self.mw = s.marketWatcher || {};
          self.sw = s.switches || {};
          self.hits = (s.recentHits || []).slice().reverse();
        });
      },
      doWake: function () {
        var self = this;
        this.busy = true;
        API.scheduleWake(this.wakeReason || '手动唤醒').then(function () {
          Toast.ok('已投递唤醒事件');
          self.wakeOpen = false; self.wakeReason = '';
          self.load();
        }).catch(function (e) { API.fail(e, '唤醒失败'); })
          .then(function () { self.busy = false; });
      },
    },

    mounted: function () {
      var self = this;
      this.load();
      this.timer = setInterval(function () { self.load(); }, CFG.poll.topbarMs);
    },
    beforeUnmount: function () { if (this.timer) clearInterval(this.timer); },
  };
})(window);
