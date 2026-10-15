package com.benmao.quanta.strategy;

import com.benmao.quanta.backtest.BasketStrategy;
import com.benmao.quanta.backtest.BasketStrategyProperties;
import com.benmao.quanta.backtest.RuleStrategy;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.strategy.rule.BenmaoGoldRuleStrategy;
import com.benmao.quanta.strategy.rule.BenmaoMartinGridRuleStrategy;
import com.benmao.quanta.strategy.rule.RangeRsiBollRuleStrategy;
import com.benmao.quanta.strategy.rule.ScalpEmaRuleStrategy;
import com.benmao.quanta.strategy.rule.TinyScalpRuleStrategy;
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
        List<RuleStrategy> rules = List.of(new TrendEmaAdxRuleStrategy(props), new RangeRsiBollRuleStrategy(props),
                new ScalpEmaRuleStrategy(props), new TinyScalpRuleStrategy(props), new BenmaoGoldRuleStrategy(props));
        BasketStrategyProperties basketProps = new BasketStrategyProperties();
        List<BasketStrategy> baskets = List.of(new BenmaoMartinGridRuleStrategy(basketProps));
        svc = new StrategyCatalogService(props, basketProps, mapper, rules, baskets,
                new ObjectMapper());
    }

    @Test
    @DisplayName("首次导入：六策略均以 DRAFT 落库，含 regime/target_rr/indicator_params/prompt")
    void importsBothAsDraft() {
        when(mapper.findByNameVersion(anyString(), anyInt())).thenReturn(null);

        List<Strategy> out = svc.ensureImported();

        assertEquals(6, out.size());
        verify(mapper, times(6)).insert(any(Strategy.class));
        verify(mapper, never()).update(any(Strategy.class));
        for (Strategy s : out) {
            assertEquals("DRAFT", s.getStatus());
            assertFalse(s.getApproved());
            assertEquals("MANUAL", s.getOrigin());
            assertTrue(List.of("TREND", "RANGE", "SCALP").contains(s.getRegimeType()));
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
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_SCALP, 1)).thenReturn(null);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_TINY, 1)).thenReturn(null);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_BENMAO_GOLD, 1)).thenReturn(null);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_MARTIN_GRID, 1)).thenReturn(null);

        List<Strategy> out = svc.ensureImported();

        assertEquals(6, out.size());
        verify(mapper, times(5)).insert(any(Strategy.class));   // 仅震荡 + 两个演练策略 + 笨猫黄金 + 马丁网格新建
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
        Strategy sc = new Strategy();
        sc.setName(StrategyCatalogService.NAME_SCALP);
        sc.setStatus("DRAFT");
        Strategy ti = new Strategy();
        ti.setName(StrategyCatalogService.NAME_TINY);
        ti.setStatus("DRAFT");
        Strategy bgold = new Strategy();
        bgold.setName(StrategyCatalogService.NAME_BENMAO_GOLD);
        bgold.setStatus("DRAFT");
        Strategy mgrid = new Strategy();
        mgrid.setName(StrategyCatalogService.NAME_MARTIN_GRID);
        mgrid.setStatus("DRAFT");
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_TREND, 1)).thenReturn(t);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_RANGE, 1)).thenReturn(r);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_SCALP, 1)).thenReturn(sc);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_TINY, 1)).thenReturn(ti);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_BENMAO_GOLD, 1)).thenReturn(bgold);
        when(mapper.findByNameVersion(StrategyCatalogService.NAME_MARTIN_GRID, 1)).thenReturn(mgrid);

        svc.ensureImported();

        verify(mapper, never()).insert(any(Strategy.class));
        verify(mapper, times(6)).update(any(Strategy.class));
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

        Strategy sc = new Strategy();
        sc.setName(StrategyCatalogService.NAME_SCALP);
        assertEquals(ScalpEmaRuleStrategy.KEY, svc.ruleKeyOf(sc));

        Strategy ti = new Strategy();
        ti.setName(StrategyCatalogService.NAME_TINY);
        assertEquals(TinyScalpRuleStrategy.KEY, svc.ruleKeyOf(ti));

        Strategy bgold = new Strategy();
        bgold.setName(StrategyCatalogService.NAME_BENMAO_GOLD);
        assertEquals(BenmaoGoldRuleStrategy.KEY, svc.ruleKeyOf(bgold));

        Strategy bad = new Strategy();
        bad.setName("不存在");
        BizException ex = assertThrows(BizException.class, () -> svc.ruleKeyOf(bad));
        assertTrue(ex.getMessage().contains("可选"), ex.getMessage());
    }
}
