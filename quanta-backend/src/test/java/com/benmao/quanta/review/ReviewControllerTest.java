package com.benmao.quanta.review;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.TradeReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ReviewController 单测：复盘五项接口的路由转发。 */
class ReviewControllerTest {

    private ReviewService service;
    private ReviewController controller;

    @BeforeEach
    void setUp() {
        service = mock(ReviewService.class);
        controller = new ReviewController(service);
    }

    @Test
    @DisplayName("读单笔报告：转发到 getReport")
    void reportDelegates() {
        TradeReport r = new TradeReport();
        r.setId(1L);
        when(service.getReport(101L)).thenReturn(r);

        assertEquals(1L, controller.report(101L).getData().getId());
        verify(service).getReport(101L);
    }

    @Test
    @DisplayName("生成单笔报告：转发到 generateReport")
    void generateDelegates() {
        TradeReport r = new TradeReport();
        r.setId(2L);
        when(service.generateReport(101L)).thenReturn(r);

        assertEquals(2L, controller.generate(101L).getData().getId());
        verify(service).generateReport(101L);
    }

    @Test
    @DisplayName("最近报告：转发到 listReports")
    void reportsDelegates() {
        when(service.listReports(5)).thenReturn(List.of(new TradeReport()));
        assertEquals(1, controller.reports(5).getData().size());
        verify(service).listReports(5);
    }

    @Test
    @DisplayName("周期统计：转发到 stats")
    void statsDelegates() {
        LocalDateTime since = LocalDateTime.of(2026, 10, 1, 0, 0);
        when(service.stats(eq("GOLD"), eq(since), isNull())).thenReturn(
                new PeriodStats("GOLD", since, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        Result<PeriodStats> r = controller.stats("GOLD", since, null);
        assertEquals(0, r.getCode());
        verify(service).stats("GOLD", since, null);
    }

    @Test
    @DisplayName("败因分类：转发到 classifyLosses")
    void lossesDelegates() {
        when(service.classifyLosses(eq("GOLD"), any(), isNull())).thenReturn(
                new LossBreakdown("GOLD", null, null, 0, 0, 0, 0, 0, 0, 0, List.of()));
        controller.losses("GOLD", null, null);
        verify(service).classifyLosses("GOLD", null, null);
    }

    @Test
    @DisplayName("策略评价：转发到 evaluateStrategies")
    void strategyDelegates() {
        when(service.evaluateStrategies(isNull(), any(), isNull())).thenReturn(List.of());
        controller.strategy(null, null, null);
        verify(service).evaluateStrategies(null, null, null);
    }

    @Test
    @DisplayName("成本拆解：转发到 analyzeCost")
    void costDelegates() {
        when(service.analyzeCost(1L)).thenReturn(
                new CostAnalysis(1L, 13L, "GOLD", "M1", 30, 2, true, 0, 0, 0, 0, 0, 0, 0,
                        null, null, null, false, "ok", "disclaimer"));
        controller.cost(1L);
        verify(service).analyzeCost(1L);
    }
}
