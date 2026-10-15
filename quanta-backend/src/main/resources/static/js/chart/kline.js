/**
 * kline.js —— 手写 Canvas 实时 K 线（防坑①核心）
 *
 * 关键防御：
 *  - 全局只有一个 setInterval（每 tick 读「当前」symbol/timeframe），绝不为每次切换新建 interval；
 *  - 每次参数变更 gen++ 并 AbortController.abort() 取消在途请求，响应按 gen 校验丢弃过期数据；
 *  - 请求异常只记录、绝不中断轮询循环；
 *  - 已有请求在途时跳过本 tick，避免请求叠加；
 *  - 页面隐藏时暂停，恢复可见时立即补一次。
 */
(function (global) {
  'use strict';

  var C = global.QuantaConfig.kline;
  var U = global.QuantaUtil;

  function KlineChart(canvas, opts) {
    opts = opts || {};
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.symbol = opts.symbol || C.symbol;
    this.timeframe = opts.timeframe || C.defaultTimeframe;
    this.count = opts.count || C.defaultCount;
    this.showMA = opts.showMA !== false;
    this.onStatus = opts.onStatus || function () {};

    this.bars = [];
    this.digits = 2;
    this.gen = 0;
    this.inflight = false;
    this.currentController = null;
    this.timer = null;
    this.stopped = true;
    this.error = null;

    this.candleWidth = C.candleWidth;
    this.offset = 0;              // 右侧被隐藏的历史 bar 数（0 = 最新在右边缘）
    this.crosshair = { x: 0, y: 0, visible: false };
    this.drag = null;
    this.hoverIndex = -1;
    this.width = 0;
    this.height = 0;

    this._bindEvents();
  }

  // ==================== 生命周期 ====================
  KlineChart.prototype.start = function () {
    if (this.timer) return;
    this.stopped = false;
    var self = this;
    // 单一轮询器：interval 只创建一次
    this.timer = setInterval(function () { self.tick(); }, global.QuantaConfig.poll.klineMs);
    this.tick();
  };

  KlineChart.prototype.stop = function () {
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
    this.stopped = true;
  };

  KlineChart.prototype.destroy = function () {
    this.stop();
    this.abortInflight();
    if (this._ro) { try { this._ro.disconnect(); } catch (e) {} }
    document.removeEventListener('visibilitychange', this._vis);
    window.removeEventListener('resize', this._resize);
  };

  /** 变更参数（品种/周期/根数）：世代号自增 + 取消在途 + 立即刷新。 */
  KlineChart.prototype.setParams = function (p) {
    var changed = false;
    if (p.symbol && p.symbol !== this.symbol) { this.symbol = p.symbol; changed = true; }
    if (p.timeframe && p.timeframe !== this.timeframe) { this.timeframe = p.timeframe; changed = true; }
    if (p.count && p.count !== this.count) { this.count = p.count; changed = true; }
    if (!changed) return;
    this.gen++;                      // 丢弃一切在途/迟到的旧响应
    this.abortInflight();
    this.offset = 0;                 // 切周期回到最新
    this.bars = [];                  // 立即清空，避免旧周期残影
    this.hoverIndex = -1;
    this.error = null;
    this.inflight = false;
    this.render();
    this.tick();                     // 立刻拉一次，不等下一个 tick
  };

  KlineChart.prototype.abortInflight = function () {
    if (this.currentController) {
      try { this.currentController.abort(); } catch (e) {}
      this.currentController = null;
    }
  };

  // ==================== 轮询 ====================
  KlineChart.prototype.tick = function () {
    if (this.stopped) return;
    if (document.hidden) return;
    if (this.inflight) return;       // 上一轮未回，跳过，避免请求叠加

    var self = this;
    var myGen = this.gen;
    var sym = this.symbol, tf = this.timeframe, cnt = this.count;
    var ctrl = new AbortController();
    this.currentController = ctrl;
    this.inflight = true;

    Promise.all([
      global.QuantaAPI.mt5Klines(sym, tf, cnt, ctrl.signal),
      global.QuantaAPI.mt5Quote(sym, ctrl.signal).catch(function () { return null; }),
    ]).then(function (res) {
      if (myGen !== self.gen) return;           // 过期响应直接丢弃
      var bars = res[0] || [];
      var quote = res[1];
      if (quote && quote.digits) self.digits = quote.digits;
      self.bars = bars;
      self.error = null;
      self.onStatus({ ok: true, count: bars.length, quote: quote });
      self.render();
    }).catch(function (e) {
      if (myGen !== self.gen) return;
      if (e && (e.name === 'AbortError' || e.code === 20)) return;  // 主动取消，忽略
      self.error = e;
      self.onStatus({ ok: false, error: e });
      // 注意：这里绝不 clearInterval，轮询必须继续
    }).then(function () {
      if (myGen === self.gen) self.inflight = false;
    });
  };

  // ==================== 事件绑定 ====================
  KlineChart.prototype._bindEvents = function () {
    var self = this;
    var cv = this.canvas;

    this._vis = function () { if (!document.hidden && !self.stopped) { self.tick(); } };
    document.addEventListener('visibilitychange', this._vis);

    this._resize = function () { self.render(); };
    window.addEventListener('resize', this._resize);
    if (global.ResizeObserver) {
      this._ro = new ResizeObserver(function () { self.render(); });
      try { this._ro.observe(cv.parentElement || cv); } catch (e) {}
    }

    cv.addEventListener('mousemove', function (e) {
      var r = cv.getBoundingClientRect();
      var x = e.clientX - r.left, y = e.clientY - r.top;
      if (self.drag) {
        var dx = x - self.drag.x;
        var moved = Math.round(-dx / self.slot());
        if (moved !== 0) {
          var maxOffset = Math.max(0, self.bars.length - self.visibleCount());
          self.offset = U.clamp(self.offset + moved, 0, maxOffset);
          self.drag.x = x;
          self.render();
        }
        self.crosshair = { x: x, y: y, visible: false };
        return;
      }
      self.crosshair = { x: x, y: y, visible: true };
      self.render();
    });

    cv.addEventListener('mouseleave', function () {
      self.crosshair.visible = false; self.drag = null; self.render();
    });

    cv.addEventListener('mousedown', function (e) {
      var r = cv.getBoundingClientRect();
      self.drag = { x: e.clientX - r.left };
      cv.style.cursor = 'grabbing';
    });

    window.addEventListener('mouseup', function () {
      if (self.drag) { self.drag = null; cv.style.cursor = 'crosshair'; }
    });

    cv.addEventListener('wheel', function (e) {
      e.preventDefault();
      var factor = e.deltaY < 0 ? 1.12 : 0.89;
      var next = U.clamp(Math.round(self.candleWidth * factor), C.minCandleWidth, C.maxCandleWidth);
      if (next !== self.candleWidth) {
        var maxOffset = Math.max(0, self.bars.length - self.visibleCount());
        self.offset = U.clamp(self.offset, 0, maxOffset);
        self.candleWidth = next;
        self.render();
      }
    }, { passive: false });

    cv.addEventListener('dblclick', function () { self.offset = 0; self.render(); });
  };

  // ==================== 布局计算 ====================
  KlineChart.prototype.plotRect = function () {
    return {
      x: 0, y: C.topPad,
      w: Math.max(40, this.width - C.rightPad),
      h: Math.max(40, this.height - C.topPad - C.bottomPad),
    };
  };

  KlineChart.prototype.slot = function () { return this.candleWidth + C.candleGap; };
  KlineChart.prototype.visibleCount = function () {
    return Math.max(8, Math.floor(this.plotRect().w / this.slot()));
  };

  KlineChart.prototype.visibleBars = function () {
    var n = this.visibleCount();
    var right = this.bars.length - 1 - this.offset;
    var left = right - n + 1;
    var out = [];
    for (var i = left; i <= right; i++) out.push({ i: i, bar: this.bars[i] });
    return out;
  };

  // ==================== 渲染 ====================
  KlineChart.prototype.render = function () {
    var cv = this.canvas, ctx = this.ctx;
    var dpr = window.devicePixelRatio || 1;
    var rect = cv.getBoundingClientRect();
    var w = Math.max(120, rect.width || cv.clientWidth || 400);
    var h = Math.max(120, rect.height || cv.clientHeight || 300);
    this.width = w; this.height = h;
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) {
      cv.width = Math.round(w * dpr);
      cv.height = Math.round(h * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    if (!this.bars.length) {
      ctx.fillStyle = C.axisTextColor;
      ctx.font = '12px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(this.error ? ('行情加载失败：' + (this.error.message || '')) : '正在加载 K 线…', w / 2, h / 2);
      return;
    }

    var vis = this.visibleBars();
    if (!vis.length) return;
    var rectP = this.plotRect();

    // ---- 价格范围 ----
    var minP = Infinity, maxP = -Infinity;
    for (var k = 0; k < vis.length; k++) {
      var b = vis[k].bar;
      if (b.low < minP) minP = b.low;
      if (b.high > maxP) maxP = b.high;
    }
    var mas = this.showMA ? this.computeMA() : null;
    if (mas) {
      for (var m = 0; m < mas.length; m++) {
        for (var t = 0; t < vis.length; t++) {
          var v = mas[m][vis[t].i];
          if (v !== null && !isNaN(v)) { if (v < minP) minP = v; if (v > maxP) maxP = v; }
        }
      }
    }
    var span = (maxP - minP) || 1;
    minP -= span * 0.08; maxP += span * 0.08;
    this.range = { min: minP, max: maxP };

    var self = this;
    function yOf(p) { return rectP.y + (maxP - p) / (maxP - minP) * rectP.h; }
    function xOf(pos) { return rectP.x + pos * self.slot() + self.candleWidth / 2; }

    // ---- 网格 + 价格轴 ----
    ctx.strokeStyle = C.gridColor; ctx.fillStyle = C.axisTextColor;
    ctx.font = C.axisFont + 'px ' + C.axisFontFamily;
    ctx.lineWidth = 1;
    var rows = 5;
    ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    for (var r = 0; r <= rows; r++) {
      var yy = rectP.y + rectP.h * r / rows;
      ctx.beginPath(); ctx.moveTo(rectP.x, yy + .5); ctx.lineTo(rectP.x + rectP.w, yy + .5); ctx.stroke();
      var price = maxP - (maxP - minP) * r / rows;
      ctx.fillText(price.toFixed(this.digits), rectP.x + rectP.w + 6, yy);
    }

    // ---- 蜡烛 ----
    for (var i = 0; i < vis.length; i++) {
      var item = vis[i], bar = item.bar;
      var up = bar.close >= bar.open;
      var color = up ? global.QuantaConfig.colors.up : global.QuantaConfig.colors.down;
      var cx = xOf(i);
      var bw = Math.max(1, this.candleWidth - 1);
      var yO = yOf(bar.open), yC = yOf(bar.close);
      var yH = yOf(bar.high), yL = yOf(bar.low);

      ctx.strokeStyle = color;
      ctx.beginPath();
      ctx.moveTo(Math.round(cx) + .5, yH);
      ctx.lineTo(Math.round(cx) + .5, yL);
      ctx.stroke();

      var top = Math.min(yO, yC), hgt = Math.max(1, Math.abs(yC - yO));
      ctx.fillStyle = color;
      ctx.fillRect(cx - bw / 2, top, bw, hgt);
    }

    // ---- 均线 ----
    if (mas) {
      for (var mi = 0; mi < mas.length; mi++) {
        ctx.strokeStyle = C.maColors[mi] || '#888';
        ctx.lineWidth = 1.2;
        ctx.beginPath();
        var started = false;
        for (var vi = 0; vi < vis.length; vi++) {
          var val = mas[mi][vis[vi].i];
          if (val === null || isNaN(val)) { started = false; continue; }
          var px = xOf(vi), py = yOf(val);
          if (!started) { ctx.moveTo(px, py); started = true; } else { ctx.lineTo(px, py); }
        }
        ctx.stroke();
      }
      // 图例
      ctx.textAlign = 'left'; ctx.textBaseline = 'top';
      var lx = 10, ly = 8;
      for (var li = 0; li < mas.length; li++) {
        ctx.fillStyle = C.maColors[li] || '#888';
        ctx.fillRect(lx, ly + 3, 10, 2);
        ctx.fillStyle = C.axisTextColor;
        ctx.fillText('MA' + C.maPeriods[li], lx + 13, ly);
        lx += 52;
      }
    }

    // ---- 最新价标记 ----
    var last = this.bars[this.bars.length - 1];
    var lastY = yOf(last.close);
    if (lastY > rectP.y && lastY < rectP.y + rectP.h) {
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = last.close >= last.open ? global.QuantaConfig.colors.up : global.QuantaConfig.colors.down;
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(rectP.x, lastY + .5); ctx.lineTo(rectP.x + rectP.w, lastY + .5); ctx.stroke();
      ctx.setLineDash([]);
    }

    // ---- 时间轴 ----
    ctx.fillStyle = C.axisTextColor; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    ctx.font = C.axisFont + 'px ' + C.axisFontFamily;
    var ticks = Math.min(6, vis.length);
    for (var ti = 0; ti < ticks; ti++) {
      var idx = Math.floor(vis.length * ti / Math.max(1, ticks - 1));
      if (idx >= vis.length) idx = vis.length - 1;
      var bb = vis[idx];
      if (!bb) continue;
      var tx = xOf(idx);
      if (tx > rectP.x + rectP.w - 20) continue;
      var barTime = new Date(bb.bar.time * 1000);
      var label = pad2(barTime.getHours()) + ':' + pad2(barTime.getMinutes());
      ctx.fillText(label, tx, rectP.y + rectP.h + 7);
    }

    // ---- 十字光标 ----
    if (this.crosshair.visible) {
      var cxh = this.crosshair.x, cyh = this.crosshair.y;
      if (cxh >= rectP.x && cxh <= rectP.x + rectP.w && cyh >= rectP.y && cyh <= rectP.y + rectP.h) {
        var rel = (cxh - rectP.x) / this.slot();
        var hi = Math.floor(rel);
        if (hi >= 0 && hi < vis.length) {
          this.hoverIndex = vis[hi].i;
          var snapX = xOf(hi);
          ctx.setLineDash([3, 3]); ctx.strokeStyle = C.crosshairColor; ctx.lineWidth = 1;
          ctx.beginPath();
          ctx.moveTo(Math.round(snapX) + .5, rectP.y); ctx.lineTo(Math.round(snapX) + .5, rectP.y + rectP.h);
          ctx.moveTo(rectP.x, Math.round(cyh) + .5); ctx.lineTo(rectP.x + rectP.w, Math.round(cyh) + .5);
          ctx.stroke(); ctx.setLineDash([]);

          var hp = maxP - (cyh - rectP.y) / rectP.h * (maxP - minP);
          ctx.fillStyle = '#0C1220';
          ctx.fillRect(rectP.x + rectP.w + 2, cyh - 9, 62, 18);
          ctx.strokeStyle = C.crosshairColor; ctx.strokeRect(rectP.x + rectP.w + 2.5, cyh - 8.5, 61, 17);
          ctx.fillStyle = global.QuantaConfig.colors.text; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
          ctx.fillText(hp.toFixed(this.digits), rectP.x + rectP.w + 6, cyh);

          this.drawOHLC(vis[hi].bar, rectP);
        }
      } else { this.hoverIndex = -1; }
    }
  };

  KlineChart.prototype.drawOHLC = function (bar, rectP) {
    var ctx = this.ctx;
    var txt = 'O ' + bar.open.toFixed(this.digits) + '  H ' + bar.high.toFixed(this.digits) +
      '  L ' + bar.low.toFixed(this.digits) + '  C ' + bar.close.toFixed(this.digits) +
      '  V ' + bar.volume;
    ctx.font = C.axisFont + 'px ' + C.axisFontFamily;
    ctx.textAlign = 'left'; ctx.textBaseline = 'top';
    ctx.fillStyle = C.axisTextColor;
    ctx.fillText(txt, 10, 24);
  };

  // 简单均线（SMA），返回 [periods][index] -> value|null
  KlineChart.prototype.computeMA = function () {
    var periods = C.maPeriods;
    var bars = this.bars;
    var out = [];
    for (var p = 0; p < periods.length; p++) {
      var n = periods[p];
      var arr = new Array(bars.length).fill(null);
      var sum = 0;
      for (var i = 0; i < bars.length; i++) {
        sum += bars[i].close;
        if (i >= n) sum -= bars[i - n].close;
        if (i >= n - 1) arr[i] = sum / n;
      }
      out.push(arr);
    }
    return out;
  };

  KlineChart.prototype.getState = function () {
    return {
      symbol: this.symbol,
      timeframe: this.timeframe,
      count: this.count,
      bars: this.bars,
      error: this.error ? (this.error.message || String(this.error)) : null,
    };
  };

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  global.QuantaKline = {
    mount: function (canvas, opts) { return new KlineChart(canvas, opts); },
    KlineChart: KlineChart,
  };
})(window);
