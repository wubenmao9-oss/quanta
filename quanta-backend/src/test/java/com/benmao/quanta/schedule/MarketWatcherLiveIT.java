package com.benmao.quanta.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketWatcher 真环境验收（M7）：真实拉 K 线产出快照，且全程不涉及任何 LLM。
 *
 * <p>默认不执行；需真环境下（mt5_service 已起）：
 * {@code $env:RUN_MT5_IT='true'; mvn -pl quanta-backend -am test -Dtest=MarketWatcherLiveIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 *
 * <p>注：测试 profile 默认关闭调度，这里显式打开以便直接调用 {@link MarketWatcher#poll()}。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "quanta.schedule.enabled=true",
        "quanta.schedule.market-watcher.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "RUN_MT5_IT", matches = "true")
class MarketWatcherLiveIT {

    @Autowired
    private MarketWatcher watcher;

    @Test
    void pollsRealMarket() {
        watcher.poll();

        assertTrue(watcher.connected(), "应能从真实桥接拉到 K 线");
        MarketSnapshot snapshot = watcher.snapshot();
        assertNotNull(snapshot);
        assertFalse(snapshot.bars().isEmpty(), "快照应含 K 线");
        assertEquals(watcher.symbol(), snapshot.symbol());
        assertEquals(watcher.timeframe(), snapshot.timeframe());
    }
}
