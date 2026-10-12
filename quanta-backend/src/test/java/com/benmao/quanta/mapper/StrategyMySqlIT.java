package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.IndicatorConfig;
import com.benmao.quanta.entity.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * strategy / indicator_config 真库集成测试。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=StrategyMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class StrategyMySqlIT {

    @Autowired
    private StrategyMapper strategyMapper;

    @Autowired
    private IndicatorConfigMapper indicatorConfigMapper;

    @Test
    void strategy_insertUpdateDelete() {
        Strategy e = new Strategy();
        e.setName("it-strategy-" + System.nanoTime());
        e.setPrompt("it prompt");
        e.setVersion(1);
        e.setStatus("DRAFT");
        e.setParentId(999L);
        e.setOrigin("MANUAL");
        e.setApproved(Boolean.TRUE);
        e.setRegimeType("TREND");
        e.setTargetRr(new BigDecimal("2.50"));
        e.setExpectedWinRate(new BigDecimal("55.00"));
        e.setIndicatorParams("{\"period\": 14}");
        e.setDescription("it desc");

        strategyMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        Strategy loaded = strategyMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getName(), loaded.getName());
        assertEquals(e.getPrompt(), loaded.getPrompt());
        assertEquals(e.getVersion(), loaded.getVersion());
        assertEquals(e.getStatus(), loaded.getStatus());
        assertEquals(e.getParentId(), loaded.getParentId());
        assertEquals(e.getOrigin(), loaded.getOrigin());
        assertEquals(e.getApproved(), loaded.getApproved());
        assertEquals(e.getRegimeType(), loaded.getRegimeType());
        assertEquals(0, e.getTargetRr().compareTo(loaded.getTargetRr()));
        assertEquals(0, e.getExpectedWinRate().compareTo(loaded.getExpectedWinRate()));
        assertEquals(e.getIndicatorParams(), loaded.getIndicatorParams());
        assertEquals(e.getDescription(), loaded.getDescription());

        loaded.setStatus("ACTIVE");
        loaded.setVersion(2);
        strategyMapper.update(loaded);
        Strategy updated = strategyMapper.getById(e.getId());
        assertEquals("ACTIVE", updated.getStatus());
        assertEquals(2, updated.getVersion());

        strategyMapper.deleteById(e.getId());
        assertNull(strategyMapper.getById(e.getId()));
    }

    @Test
    void indicatorConfig_insertUpdateDelete() {
        IndicatorConfig e = new IndicatorConfig();
        e.setStrategyId(12345L);
        e.setIndicatorName("RSI");
        e.setParams("{\"period\": 14}");

        indicatorConfigMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        IndicatorConfig loaded = indicatorConfigMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getStrategyId(), loaded.getStrategyId());
        assertEquals(e.getIndicatorName(), loaded.getIndicatorName());
        assertEquals(e.getParams(), loaded.getParams());

        loaded.setIndicatorName("MACD");
        loaded.setStrategyId(67890L);
        indicatorConfigMapper.update(loaded);
        IndicatorConfig updated = indicatorConfigMapper.getById(e.getId());
        assertEquals("MACD", updated.getIndicatorName());
        assertEquals(67890L, updated.getStrategyId());

        indicatorConfigMapper.deleteById(e.getId());
        assertNull(indicatorConfigMapper.getById(e.getId()));
    }
}