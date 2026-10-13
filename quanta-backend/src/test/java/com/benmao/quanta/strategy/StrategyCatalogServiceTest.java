package com.benmao.quanta.strategy;

import com.benmao.quanta.backtest.RuleStrategy;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.strategy.rule.RangeRsiBollRuleStrategy;
import com.benmao.quanta.strategy.rule.TrendEmaAdxRuleStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 策略库目录单测：幂等导入（仅导入草稿、不覆盖已批准策略）+ 名称→规则键解析。 */
class StrategyCatalogServiceTest {

    private StrategyMapper mapper;
    private StrategyCatalogService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(StrategyMapper.class);
        StrategyProperties props = new StrategyProperties();
        List<RuleStrategy> rules = List.of(new TrendEmaAdxRuleStrategy(props), new RangeRsiBollRuleStrategy(props));
        svc = new StrategyCatalogService(props, mapper, rules, new ObjectMapper());
    }

    @Test
    @DisplayName("首次导入：两策略均以 DRAFT 落库，含 regime/target_rr/indicator_params/prompt")
    void importsBothAsDraft() {
        when(mapper.findByNameVersion(anyString(), anyInt())).thenReturn(null);

        List<Strategy> out = svc.ensureImported();

        assertEquals(2, out.size());
        verify(mapper, times(2)).insert(any(Strategy.class));
        verify(mapper, never()).update(any(Strategy.class));
        for (Strategy s : out) {
            assertEquals("DRAFT", s.getStatus());
            assertFalse(s.getApproved());
            assertEquals("MANUAL", s.getOrigin());
            assertTrue(s.getRegimeType().equals("TREND") || s.getRegimeType().equals("RANGE"));
            assertTrue(s.getTargetRr().compareTo(BigDecimal.ZERO) > 0);
            assertTrue(s.getIndicatorParams().contains("ruleKey"));
            assertTrue(s.getPrompt().contains("GOLD"));
        }
    }

    @Test
    @DisplayName("已存在且非 DRAFT（ACTIVE）→ 不覆盖、不更新")
    void doesNotOverwriteActive() {
        Strategy active = new Strategy();
        active.setName(StrategyCatalogService.NAME_TREND);
        active.setStatus("ACTIVE");
        active.setTargetRr(BigDecimal.valueOf(9.99));
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_TREND, 1)).thenReturn(active);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_RANGE, 1)).thenReturn(null);

        List<Strategy> out = svc.ensureImported();

        assertEquals(2, out.size());
        verify(mapper, times(1)).insert(any(Strategy.class));   // 仅震荡新建
        verify(mapper, never()).update(any(Strategy.class));     // 趋势不被覆盖
        assertTrue(out.stream().anyMatch(s -> "ACTIVE".equals(s.getStatus())));
    }

    @Test
    @DisplayName("已存在且为 DRAFT → 刷新参数与 Prompt（不新建）")
    void refreshesDraft() {
        Strategy t = new Strategy();
        t.setName(StrategyCatalogService.NAME_TREND);
        t.setStatus("DRAFT");
        Strategy r = new Strategy();
        r.setName(StrategyCatalogService.NAME_RANGE);
        r.setStatus("DRAFT");
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_TREND, 1)).thenReturn(t);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_RANGE, 1)).thenReturn(r);

        svc.ensureImported();

        verify(mapper, never()).insert(any(Strategy.class));
        verify(mapper, times(2)).update(any(Strategy.class));
    }

    @Test
    @DisplayName("名称→规则键解析；未知名称回执可选策略")
    void resolvesRuleKey() {
        Strategy t = new Strategy();
        t.setName(StrategyCatalogService.NAME_TREND);
        assertEquals(TrendEmaAdxRuleStrategy.KEY, svc.ruleKeyOf(t));

        Strategy r = new Strategy();
        r.setName(StrategyCatalogService.NAME_RANGE);
        assertEquals(RangeRsiBollRuleStrategy.KEY, svc.ruleKeyOf(r));

        Strategy bad = new Strategy();
        bad.setName("不存在");
        BizException ex = assertThrows(BizException.class, () -> svc.ruleKeyOf(bad));
        assertTrue(ex.getMessage().contains("可选"), ex.getMessage());
    }
}
