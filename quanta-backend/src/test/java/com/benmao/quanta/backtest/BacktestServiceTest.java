package com.benmao.quanta.backtest;

import com.benmao.quanta.backtest.rule.EmaAtrRuleStrategy;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.BacktestRun;
import com.benmao.quanta.entity.MarketKline;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import com.benmao.quanta.mapper.MarketKlineMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回测服务单测：防幻觉入参校验（周期白名单 / 点差·滑点边界 / 时间区间 / 空数据）、落库、多档扫描汇总。
 */
class BacktestServiceTest {

    private MarketKlineMapper klineMapper;
    private BacktestRunMapper runMapper;
    private BacktestTradeMapper tradeMapper;
    private StrategyMapper strategyMapper;
    private BacktestProperties props;
    private BacktestService service;

    @BeforeEach
    void setUp() {
        klineMapper = mock(MarketKlineMapper.class);
        runMapper = mock(BacktestRunMapper.class);
        tradeMapper = mock(BacktestTradeMapper.class);
        strategyMapper = mock(StrategyMapper.class);

        props = new BacktestProperties();
        props.setSymbol("GOLD");
        props.setTimeframe("M1");
        props.setPoint(0.01);
        props.setContractSizePerLot(100.0);
        props.setVolume(0.01);
        props.setSpreadSideFactor(1.0);
        props.setSwapOn(false);
        props.setInitialEquity(500.0);
        props.setDefaultSlippage(0);

        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("UTC"));
        service = new BacktestService(props, klineMapper, runMapper, tradeMapper, strategyMapper,
                List.of(new EmaAtrRuleStrategy(props)), clock);

        when(runMapper.insert(any(BacktestRun.class))).thenAnswer(inv -> {
            BacktestRun r = inv.getArgument(0);
            r.setId(123L);
            return 1;
        });
        Strategy existing = new Strategy();
        existing.setId(7L);
        existing.setName("内置规则-EMA_ATR");
        when(strategyMapper.findByNameVersion(anyString(), anyInt())).thenReturn(existing);
        when(tradeMapper.insert(any())).thenReturn(1);
    }

    private static List<MarketKline> rows(int n) {
        List<MarketKline> rows = new ArrayList<>();
        LocalDateTime base = LocalDateTime.parse("2026-09-12T00:00:00");
        for (int i = 0; i < n; i++) {
            double c = i < n / 2 ? 2000 - i : 2000 - (n / 2) + (i - n / 2);
            MarketKline k = new MarketKline();
            k.setSymbol("GOLD");
            k.setTimeframe("M1");
            k.setBarTime(base.plusMinutes(i));
            k.setOpen(BigDecimal.valueOf(c));
            k.setHigh(BigDecimal.valueOf(c + 0.2));
            k.setLow(BigDecimal.valueOf(c - 0.2));
            k.setClose(BigDecimal.valueOf(c));
            k.setVolume(100L);
            rows.add(k);
        }
        return rows;
    }

    @Test
    @DisplayName("非法周期被拒并回执可选周期")
    void rejectsUnknownTimeframe() {
        BizException ex = assertThrows(BizException.class, () -> service.run(
                new BacktestService.RunSpec("GOLD", "M2", "2026-09-12T00:00:00", "2026-09-16T00:00:00", 0, 0, null)));
        assertTrue(ex.getMessage().contains("可选"), ex.getMessage());
    }

    @Test
    @DisplayName("点差越界被拒并回执合法范围")
    void rejectsSpreadOutOfRange() {
        BizException ex = assertThrows(BizException.class, () -> service.run(
                new BacktestService.RunSpec("GOLD", "M1", "2026-09-12T00:00:00", "2026-09-16T00:00:00", 999, 0, null)));
        assertTrue(ex.getMessage().contains("[0, 200]"), ex.getMessage());
    }

    @Test
    @DisplayName("时间区间非法被拒")
    void rejectsBadRange() {
        BizException ex = assertThrows(BizException.class, () -> service.run(
                new BacktestService.RunSpec("GOLD", "M1", "2026-09-16T00:00:00", "2026-09-12T00:00:00", 0, 0, null)));
        assertTrue(ex.getMessage().contains("必须早于"), ex.getMessage());
    }

    @Test
    @DisplayName("区间内无 K 线数据被拒并提示回填")
    void rejectsWhenNoData() {
        when(klineMapper.listRange(anyString(), anyString(), any(), any())).thenReturn(List.of());
        BizException ex = assertThrows(BizException.class, () -> service.run(
                new BacktestService.RunSpec("GOLD", "M1", "2026-09-12T00:00:00", "2026-09-16T00:00:00", 0, 0, null)));
        assertTrue(ex.getMessage().contains("无 K 线数据"), ex.getMessage());
    }

    @Test
    @DisplayName("未知策略被拒并回执可选策略")
    void rejectsUnknownStrategy() {
        BizException ex = assertThrows(BizException.class, () -> service.run(
                new BacktestService.RunSpec("GOLD", "M1", "2026-09-12T00:00:00", "2026-09-16T00:00:00", 0, 0, "NOPE")));
        assertTrue(ex.getMessage().contains("可选"), ex.getMessage());
    }

    @Test
    @DisplayName("回测落库并返回报告（run + 明细）")
    void runPersistsAndReports() {
        when(klineMapper.listRange(anyString(), anyString(), any(), any())).thenReturn(rows(120));

        BacktestReport report = service.run(new BacktestService.RunSpec(
                "GOLD", "M1", "2026-09-12 00:00:00", "2026-09-16T00:00:00", 0, 0, null));

        assertEquals(123L, report.runId());
        assertEquals(120, report.barCount());
        assertEquals("GOLD", report.symbol());
        assertTrue(report.tradeCount() >= 1, "先跌后涨行情应至少成交一笔");
        verify(runMapper, times(1)).insert(any(BacktestRun.class));
        verify(tradeMapper, times(report.tradeCount())).insert(any());
        assertNotNull(report.disclaimer());
    }

    @Test
    @DisplayName("多档点差扫描：每档一条汇总 + 稳健性结论")
    void tiersScanSummarises() {
        when(klineMapper.listRange(anyString(), anyString(), any(), any())).thenReturn(rows(120));

        TierScanResult result = service.runTiers(new BacktestService.TierSpec(
                "GOLD", "M1", "2026-09-12T00:00:00", "2026-09-16T00:00:00", null, null, null));

        assertEquals(4, result.tiers().size()); // 默认 {0,16,30,50}
        assertEquals(0, result.tiers().get(0).spreadPoints(), 1e-9);
        assertEquals(50, result.tiers().get(3).spreadPoints(), 1e-9);
        assertNotNull(result.verdict());
        verify(runMapper, times(4)).insert(any(BacktestRun.class));
    }

    @Test
    @DisplayName("时间字符串支持 ISO / 空格 / 仅日期三种格式")
    void acceptsFlexibleTimeFormats() {
        when(klineMapper.listRange(anyString(), anyString(), any(), any())).thenReturn(rows(120));
        BacktestReport r = service.run(new BacktestService.RunSpec(
                "GOLD", "M1", "2026-09-12", "2026-09-16", 0, 0, null));
        assertEquals(123L, r.runId());
    }
}
