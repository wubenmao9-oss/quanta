/**
 * ai-chat.js —— 通用「AI 对话框」抽屉组件（全局注册为 <quanta-ai-chat>）
 *
 * 一处实现，三处复用：持仓页盘面助手 / 策略页策略设计助手（可扩展到其他页）。
 * 特性：
 *  - 多轮对话：服务端按 sessionId 续聊（同一次对话内不丢上下文），「新会话」即清空重开；
 *  - 工具轨迹：把后端返回的 toolCalls（名称/入参/结果/耗时/成败）完整可视化，可展开核对；
 *  - 保存钩子：当传入 save-label 时，底部出现保存按钮，点击 emit('save', {prompt, sessionId})，
 *    由父组件决定后续（如策略页弹「保存为策略」表单）；
 *  - 非流式：一次请求返回完整回答（与后端 /api/agent/chat 契约一致）。
 *
 * 使用：
 *   <quanta-ai-chat v-model="aiOpen" persona="STRATEGY" title="AI 策略设计"
 *                   :symbol="sym" :timeframe="tf" :context="draft" greeting="…"
 *                   :quick="['问题1','问题2']" save-label="保存为策略" @save="onSave" />
 */
(function (global) {
  'use strict';

  var API = global.QuantaAPI, U = global.QuantaUtil, CFG = global.QuantaConfig;

  var AI_CHAT = {
    name: 'quanta-ai-chat',
    props: {
      modelValue: { type: Boolean, default: false },
      title: { type: String, default: 'AI 助手' },
      persona: { type: String, default: 'GENERAL' },     // GENERAL | STRATEGY
      symbol: { type: String, default: '' },
      timeframe: { type: String, default: '' },
      context: { type: String, default: '' },
      greeting: { type: String, default: '' },
      quick: { type: Array, default: function () { return []; } },
      saveLabel: { type: String, default: '' },
      placeholder: { type: String, default: '输入问题，Enter 发送，Shift+Enter 换行' },
      // 绑定既有会话：传入后打开即还原该会话历史，并在此会话内续聊（用于「每个持仓会话内自由聊天」）
      chatSessionId: { type: [Number, String], default: null },
    },
    emits: ['update:modelValue', 'save'],

    template: [
      '<div class="overlay" v-if="modelValue" style="justify-content:flex-end;align-items:stretch" @click.self="close">',
      '  <div class="drawer ai-drawer">',
      '    <div class="drawer-head">',
      '      <div>',
      '        <div class="modal-title">{{ title }}</div>',
      '        <div class="panel-sub">{{ persona===\'STRATEGY\' ? \'策略设计助手\' : \'盘面交易助手\' }}',
      '          <span v-if="symbol"> · {{ symbol }}</span><span v-if="timeframe"> {{ timeframe }}</span>',
      '          <span v-if="sessionId"> · 会话 #{{ sessionId }}</span></div>',
      '      </div>',
      '      <div class="right flex gap-8" style="align-items:center">',
      '        <button v-if="!chatSessionId" class="btn sm ghost" :disabled="busy" @click="reset">新会话</button>',
      '        <button class="btn sm ghost" @click="close">关闭</button>',
      '      </div>',
      '    </div>',
      '    <div class="drawer-body ai-body" ref="body">',
      '      <div class="thread">',
      '        <div v-for="(m,i) in thread" :key="i" class="msg" :class="m.role===\'user\'?\'user\':\'ai\'">',
      '          <div class="msg-avatar">{{ m.role===\'user\' ? \'我\' : \'AI\' }}</div>',
      '          <div class="msg-body">',
      '            <div class="msg-card">',
      '              <div class="msg-reason">{{ m.content }}</div>',
      '              <div v-if="m.toolCalls && m.toolCalls.length" class="ai-tools">',
      '                <div v-for="(t,ti) in m.toolCalls" :key="ti" class="ai-tool">',
      '                  <div class="ai-tool-head" @click="toggleTool(i, ti)">',
      '                    <span class="badge" :class="t.ok?\'ok\':\'fail\'">{{ t.ok ? \'OK\' : \'FAIL\' }}</span>',
      '                    <span class="mono ai-tool-name">{{ t.name }}</span>',
      '                    <span class="muted" style="font-size:10px">{{ t.elapsedMs }}ms</span>',
      '                    <span class="right muted" style="font-size:10px">{{ toolOpen[toolKey(i,ti)] ? \'收起\' : \'参数/结果\' }}</span>',
      '                  </div>',
      '                  <div v-if="toolOpen[toolKey(i,ti)]" style="margin-top:6px">',
      '                    <div class="panel-sub" style="margin-bottom:4px">入参</div>',
      '                    <div class="jsonbox" style="max-height:120px">{{ pretty(t.arguments) }}</div>',
      '                    <div class="panel-sub" style="margin:8px 0 4px">返回</div>',
      '                    <div class="jsonbox" style="max-height:220px">{{ pretty(t.ok ? t.result : { error: t.error }) }}</div>',
      '                  </div>',
      '                </div>',
      '              </div>',
      '              <div v-if="m.meta" class="msg-foot"><span>{{ m.meta }}</span></div>',
      '            </div>',
      '          </div>',
      '        </div>',
      '        <div v-if="busy" class="msg ai">',
      '          <div class="msg-avatar">AI</div>',
      '          <div class="msg-body"><div class="msg-card"><span class="spinner"></span>',
      '            <span class="muted">正在思考并调用工具…</span></div></div>',
      '        </div>',
      '      </div>',
      '      <div v-if="quick.length && thread.length<=1" class="ai-quick">',
      '        <button v-for="(q,qi) in quick" :key="qi" class="btn sm ghost" @click="ask(q)">{{ q }}</button>',
      '      </div>',
      '    </div>',
      '    <div class="ai-composer">',
      '      <div v-if="err" class="hint neg" style="margin-bottom:6px">{{ err }}</div>',
      '      <textarea class="inp" rows="2" v-model="input" :placeholder="placeholder"',
      '        @keydown.enter.exact.prevent="send"></textarea>',
      '      <div class="flex gap-8" style="align-items:center;margin-top:8px">',
      '        <span class="hint">{{ hint }}</span>',
      '        <div class="right flex gap-8">',
      '          <button v-if="saveLabel" class="btn sm ok" :disabled="busy || !hasAssistant" @click="onSave">{{ saveLabel }}</button>',
      '          <button class="btn sm primary" :disabled="busy || !input.trim()" @click="send">{{ busy ? \'生成中…\' : \'发送\' }}</button>',
      '        </div>',
      '      </div>',
      '    </div>',
      '  </div>',
      '</div>'
    ].join(''),

    data: function () {
      return {
        U: U, CFG: CFG,
        thread: [], input: '', busy: false, sessionId: null, err: null, toolOpen: {},
        loadedSession: null,
      };
    },

    computed: {
      hint: function () {
        return this.persona === 'STRATEGY'
          ? '自动回测只能跑内置规则策略，不能直接回测自然语言 Prompt；新策略入库后仍需门禁+人工批准。'
          : 'AI 只给建议，不会替下单；下单/平仓请在界面手动确认。';
      },
      hasAssistant: function () {
        for (var i = this.thread.length - 1; i >= 0; i--) if (this.thread[i].role === 'assistant') return true;
        return false;
      },
    },

    watch: {
      modelValue: function (v) { if (v) this.initForOpen(); },
      chatSessionId: function () { if (this.modelValue) this.initForOpen(); },
    },

    methods: {
      close: function () { this.$emit('update:modelValue', false); },
      reset: function () {
        this.thread = []; this.sessionId = null; this.err = null; this.input = ''; this.toolOpen = {};
        this.loadedSession = null;
        if (this.greeting) this.thread.push({ role: 'assistant', content: this.greeting });
        this.scrollBottom();
      },

      /** 打开时初始化：绑定会话则还原其历史，否则沿用当前线程（无则开新会话）。 */
      initForOpen: function () {
        var bound = (this.chatSessionId === null || this.chatSessionId === undefined || this.chatSessionId === '')
          ? null : this.chatSessionId;
        if (bound !== null) {
          if (this.loadedSession === bound) return;
          this.loadHistory(bound);
        } else if (!this.thread.length) {
          this.reset();
        }
      },

      /** 还原绑定会话的既有对话（user/assistant）。 */
      loadHistory: function (sid) {
        var self = this;
        this.thread = []; this.sessionId = Number(sid); this.err = null; this.input = ''; this.toolOpen = {};
        this.loadedSession = sid;
        API.agentSessionMessages(sid).then(function (rows) {
          (rows || []).forEach(function (m) {
            self.thread.push({ role: m.role === 'user' ? 'user' : 'assistant', content: m.content });
          });
          if (!self.thread.length && self.greeting) {
            self.thread.push({ role: 'assistant', content: self.greeting });
          }
          self.scrollBottom();
        }).catch(function () {
          if (self.greeting) self.thread.push({ role: 'assistant', content: self.greeting });
        });
      },
      ask: function (q) { this.input = q; this.send(); },
      toggleTool: function (i, ti) { var k = this.toolKey(i, ti); this.toolOpen[k] = !this.toolOpen[k]; },
      toolKey: function (i, ti) { return i + '-' + ti; },
      pretty: function (v) { return U.prettyJSON(v); },

      send: function () {
        if (this.busy) return;
        var text = (this.input || '').trim();
        if (!text) return;
        var self = this;
        this.err = null;
        this.thread.push({ role: 'user', content: text });
        this.input = '';
        this.busy = true;
        this.scrollBottom();
        API.agentChat({
          sessionId: this.sessionId, message: text,
          symbol: this.symbol || null, timeframe: this.timeframe || null,
          persona: this.persona, context: this.context || null,
        }).then(function (r) {
          if (r && r.sessionId) self.sessionId = r.sessionId;
          self.thread.push({
            role: 'assistant',
            content: (r && r.reply) || '（无回答）',
            toolCalls: (r && r.toolCalls) || [],
            meta: metaLine(r),
          });
          if (r && r.error) self.err = r.error;
        }).catch(function (e) {
          self.err = (e && e.message) ? e.message : String(e);
          self.thread.push({ role: 'assistant', content: '调用失败：' + self.err });
        }).then(function () {
          self.busy = false;
          self.scrollBottom();
        });
      },

      lastAssistant: function () {
        for (var i = this.thread.length - 1; i >= 0; i--) {
          if (this.thread[i].role === 'assistant') return this.thread[i].content;
        }
        return '';
      },
      onSave: function () {
        this.$emit('save', { prompt: this.lastAssistant(), sessionId: this.sessionId });
      },

      scrollBottom: function () {
        var self = this;
        this.$nextTick(function () {
          var el = self.$refs.body;
          if (el) el.scrollTop = el.scrollHeight;
        });
      },
    },
  };

  function metaLine(r) {
    if (!r) return '';
    var p = [];
    if (r.model) p.push(r.model);
    if (r.tokens !== null && r.tokens !== undefined) p.push('tokens ' + r.tokens);
    if (r.latencyMs !== null && r.latencyMs !== undefined) p.push(r.latencyMs + 'ms');
    return p.join(' · ');
  }

  global.QuantaAIChat = AI_CHAT;
})(window);
