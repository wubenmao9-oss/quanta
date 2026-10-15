/**
 * echarts-theme.js —— ECharts 暗色主题 + 生命周期管理（防坑③核心）
 *
 * 关键防御：
 *  - 实例惰性创建并挂在 DOM 元素上（el.__chart），只在视图可见、容器有确定高度时才建；
 *  - 数据到达统一 setOption(option, notMerge=true)，强制全量替换，避免增量合并不重绘；
 *  - 视图显示后调用 resize()，卸载时 dispose()。
 */
(function (global) {
  'use strict';

  var COL = {
    text: '#94A3B8',
    textStrong: '#E5EAF3',
    axis: 'rgba(255,255,255,.08)',
    split: 'rgba(255,255,255,.05)',
    up: '#22C55E',
    down: '#F43F5E',
    accent: '#3B82F6',
    accent2: '#22D3EE',
    warn: '#F59E0B',
    purple: '#A78BFA',
  };

  var PALETTE = [COL.accent2, COL.up, COL.warn, COL.purple, COL.down, COL.accent];

  var THEME_NAME = 'quanta-dark';
  var registered = false;

  function ensureTheme() {
    if (registered || !global.echarts) return;
    global.echarts.registerTheme(THEME_NAME, {
      color: PALETTE,
      backgroundColor: 'transparent',
      textStyle: { color: COL.text, fontFamily: '-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif' },
      title: { textStyle: { color: COL.textStrong, fontSize: 13, fontWeight: 600 } },
      legend: { textStyle: { color: COL.text }, inactiveColor: '#475569' },
      grid: { borderColor: COL.axis },
      categoryAxis: {
        axisLine: { lineStyle: { color: COL.axis } },
        axisTick: { show: false },
        axisLabel: { color: COL.text, fontSize: 10 },
        splitLine: { show: false },
      },
      valueAxis: {
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: COL.text, fontSize: 10 },
        splitLine: { lineStyle: { color: COL.split } },
      },
      tooltip: {
        backgroundColor: '#0C1220',
        borderColor: 'rgba(255,255,255,.12)',
        borderWidth: 1,
        textStyle: { color: COL.textStrong, fontSize: 11 },
        axisPointer: { lineStyle: { color: 'rgba(148,163,184,.5)' }, crossStyle: { color: 'rgba(148,163,184,.5)' } },
      },
      line: { symbol: 'none', lineStyle: { width: 1.6 } },
      markLine: { lineStyle: { color: COL.warn, type: 'dashed' } },
    });
    registered = true;
  }

  var QuantaChart = {
    COLORS: COL,
    PALETTE: PALETTE,

    /** 惰性创建（只创建一次）并返回实例。 */
    ensure: function (el) {
      if (!el) return null;
      ensureTheme();
      if (!el.__chart) {
        el.__chart = global.echarts.init(el, THEME_NAME, { renderer: 'canvas' });
      }
      return el.__chart;
    },

    /** 数据到达统一入口：notMerge=true 全量替换 + resize（防坑③）。 */
    setOption: function (el, option) {
      var chart = QuantaChart.ensure(el);
      if (!chart) return null;
      chart.setOption(option, true);
      chart.resize();
      return chart;
    },

    resize: function (el) {
      if (el && el.__chart) el.__chart.resize();
    },

    dispose: function (el) {
      if (el && el.__chart) {
        try { el.__chart.dispose(); } catch (e) {}
        el.__chart = null;
      }
    },

    // ==================== 常用 option 工厂 ====================

    /** 净值曲线（逐笔累计） */
    equityOption: function (labels, values) {
      return {
        grid: { left: 54, right: 18, top: 18, bottom: 26 },
        tooltip: {
          trigger: 'axis',
          valueFormatter: function (v) { return (v === null || v === undefined) ? '-' : Number(v).toFixed(2); },
        },
        xAxis: { type: 'category', data: labels, boundaryGap: false },
        yAxis: { type: 'value', scale: true, axisLabel: { formatter: function (v) { return Number(v).toFixed(0); } } },
        series: [{
          type: 'line',
          data: values,
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 1.8, color: COL.accent2 },
          areaStyle: {
            color: new global.echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(34,211,238,.28)' },
              { offset: 1, color: 'rgba(34,211,238,.02)' },
            ]),
          },
          markLine: {
            silent: true, symbol: 'none',
            data: [{ yAxis: 0 }],
            lineStyle: { color: 'rgba(148,163,184,.35)', type: 'dashed' },
            label: { show: false },
          },
        }],
      };
    },

    /** 多档点差对比：柱状（净盈亏）+ 折线（期望） */
    tierOption: function (categories, netProfits, expectancies) {
      return {
        grid: { left: 58, right: 56, top: 30, bottom: 28 },
        legend: { top: 2, right: 8, itemWidth: 12, itemHeight: 8 },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        xAxis: { type: 'category', data: categories, name: '点差', nameTextStyle: { color: COL.text, fontSize: 10 } },
        yAxis: [
          { type: 'value', name: '净盈亏', nameTextStyle: { color: COL.text, fontSize: 10 }, axisLabel: { formatter: function (v) { return Number(v).toFixed(0); } } },
          { type: 'value', name: '期望/笔', nameTextStyle: { color: COL.text, fontSize: 10 }, splitLine: { show: false }, axisLabel: { formatter: function (v) { return Number(v).toFixed(1); } } },
        ],
        series: [
          {
            name: '净盈亏', type: 'bar', data: netProfits, barMaxWidth: 34,
            itemStyle: {
              borderRadius: [4, 4, 0, 0],
              color: function (p) { return p.value >= 0 ? COL.up : COL.down; },
            },
          },
          {
            name: '期望/笔', type: 'line', yAxisIndex: 1, data: expectancies,
            symbolSize: 6, lineStyle: { width: 2, color: COL.warn }, itemStyle: { color: COL.warn },
          },
        ],
      };
    },

    /** 败因分类分布（横向柱） */
    lossOption: function (labels, values) {
      return {
        grid: { left: 96, right: 30, top: 12, bottom: 20 },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        xAxis: { type: 'value', splitLine: { lineStyle: { color: COL.split } } },
        yAxis: { type: 'category', data: labels, axisLabel: { fontSize: 11 } },
        series: [{
          type: 'bar', data: values, barMaxWidth: 18,
          itemStyle: { borderRadius: [0, 4, 4, 0], color: COL.down },
          label: { show: true, position: 'right', color: COL.text, fontSize: 10 },
        }],
      };
    },

    /** 逐笔盈亏序列（柱状，正负着色） */
    pnlOption: function (labels, values) {
      return {
        grid: { left: 54, right: 18, top: 18, bottom: 26 },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        xAxis: { type: 'category', data: labels },
        yAxis: { type: 'value', axisLabel: { formatter: function (v) { return Number(v).toFixed(0); } } },
        series: [{
          type: 'bar', data: values, barMaxWidth: 12,
          itemStyle: { color: function (p) { return p.value >= 0 ? COL.up : COL.down; } },
        }],
      };
    },
  };

  global.QuantaChart = QuantaChart;
})(window);
