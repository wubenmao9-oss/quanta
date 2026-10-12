package com.benmao.quanta.schedule;

import com.benmao.quanta.entity.MarketKline;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.mapper.MarketKlineMapper;
import com.benmao.quanta.mapper.TradePlanMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度层持久化真库集成测试（M7）：T1 未过期计划查询 / 计划置位 / K 线去重落库。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=ScheduleMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class ScheduleMySqlIT {

    @Autowired
    private TradePlanMapper tradePlanMapper;

    @Autowired
    private MarketKlineMapper klineMapper;

    @Test
    void pendingPlan_isListedThenMarkedTriggered() {
        TradePlan plan = new TradePlan();
        plan.setSessionId(1L);
        plan.setPlanType("PRICE");
        plan.setTargetPrice(new BigDecimal("4200.00"));
        plan.setExpireAt(LocalDateTime.now().plusMinutes(10));
        plan.setStatus("PENDING");
        tradePlanMapper.insert(plan);
        try {
            List<TradePlan> pending = tradePlanMapper.listPendingUnexpired(LocalDateTime.now());
            assertTrue(pending.stream().anyMatch(p -> p.getId().equals(plan.getId())),
                    "未过期 PENDING 计划应被 T1 取到");

            assertEquals(1, tradePlanMapper.updateStatus(plan.getId(), "TRIGGERED"));

            List<TradePlan> after = tradePlanMapper.listPendingUnexpired(LocalDateTime.now());
            assertTrue(after.stream().noneMatch(p -> p.getId().equals(plan.getId())),
                    "已触发计划不应再被取到");
        } finally {
            tradePlanMapper.deleteById(plan.getId());
        }
    }

    @Test
    void expiredPlan_isExcludedFromPending() {
        TradePlan plan = new TradePlan();
        plan.setSessionId(1L);
        plan.setPlanType("PRICE");
        plan.setTargetPrice(new BigDecimal("4200.00"));
        plan.setExpireAt(LocalDateTime.now().minusMinutes(1));
        plan.setStatus("PENDING");
        tradePlanMapper.insert(plan);
        try {
            List<TradePlan> pending = tradePlanMapper.listPendingUnexpired(LocalDateTime.now());
            assertTrue(pending.stream().noneMatch(p -> p.getId().equals(plan.getId())),
                    "过期计划应被 DB 侧排除");
        } finally {
            tradePlanMapper.deleteById(plan.getId());
        }
    }

    @Test
    void klineInsertIgnore_dedupesByUniqueKey() {
        String symbol = "IT-" + System.currentTimeMillis();
        LocalDateTime barTime = LocalDateTime.of(2026, 10, 12, 16, 0);
        try {
            assertEquals(1, klineMapper.insertIgnore(symbol, "M5", barTime, 1.0, 2.0, 0.5, 1.5, 10));
            assertEquals(0, klineMapper.insertIgnore(symbol, "M5", barTime, 1.0, 2.0, 0.5, 1.5, 10),
                    "同 (symbol,timeframe,bar_time) 重复插入应被忽略");
        } finally {
            klineMapper.listAll().stream()
                    .filter(k -> symbol.equals(k.getSymbol()))
                    .map(MarketKline::getId)
                    .forEach(klineMapper::deleteById);
        }
    }
}
