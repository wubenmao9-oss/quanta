package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.BacktestRun;
import com.benmao.quanta.entity.BacktestTrade;
import com.benmao.quanta.entity.MarketKline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * market_kline / backtest_run / backtest_trade 真库集成测试。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=BacktestMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class BacktestMySqlIT {

    @Autowired
    private MarketKlineMapper marketKlineMapper;

    @Autowired
    private BacktestRunMapper backtestRunMapper;

    @Autowired
    private BacktestTradeMapper backtestTradeMapper;

    @Test
    void marketKline_insertUpdateDelete() {
        MarketKline e = new MarketKline();
        e.setSymbol("IT" + System.nanoTime());
        e.setTimeframe("M1");
        e.setBarTime(LocalDateTime.of(2024, 3, 4, 5, 6, 7));
        e.setOpen(new BigDecimal("2000.10000"));
        e.setHigh(new BigDecimal("2010.00000"));
        e.setLow(new BigDecimal("1990.00000"));
        e.setClose(new BigDecimal("2005.55000"));
        e.setVolume(123456L);

        marketKlineMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        MarketKline loaded = marketKlineMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getSymbol(), loaded.getSymbol());
        assertEquals(e.getTimeframe(), loaded.getTimeframe());
        assertEquals(e.getBarTime(), loaded.getBarTime());
        assertEquals(0, e.getOpen().compareTo(loaded.getOpen()));
        assertEquals(0, e.getHigh().compareTo(loaded.getHigh()));
        assertEquals(0, e.getLow().compareTo(loaded.getLow()));
        assertEquals(0, e.getClose().compareTo(loaded.getClose()));
        assertEquals(e.getVolume(), loaded.getVolume());

        loaded.setClose(new BigDecimal("2006.00000"));
        loaded.setVolume(130000L);
        marketKlineMapper.update(loaded);
        MarketKline updated = marketKlineMapper.getById(e.getId());
        assertEquals(0, new BigDecimal("2006.00000").compareTo(updated.getClose()));
        assertEquals(130000L, updated.getVolume());

        marketKlineMapper.deleteById(e.getId());
        assertNull(marketKlineMapper.getById(e.getId()));
    }

    @Test
    void backtestRun_insertUpdateDelete() {
        BacktestRun e = new BacktestRun();
        e.setStrategyId(51L);
        e.setSymbol("GOLD");
        e.setDataTimeframe("M1");
        e.setStartTime(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        e.setEndTime(LocalDateTime.of(2024, 2, 1, 0, 0, 0));
        e.setParams("{\"target_rr\": 2}");
        e.setSpreadMode("FIXED");
        e.setSpread(new BigDecimal("20.00"));
        e.setSlippage(new BigDecimal("5.00"));
        e.setSwapOn(Boolean.FALSE);
        e.setMode("AGENT");
        e.setWinRate(new BigDecimal("58.00"));
        e.setProfitFactor(new BigDecimal("1.75"));
        e.setMaxDrawdown(new BigDecimal("120.50"));
        e.setTradeCount(88);
        e.setAvgHoldSec(3600);
        e.setStatus("DONE");

        backtestRunMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        BacktestRun loaded = backtestRunMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getStrategyId(), loaded.getStrategyId());
        assertEquals(e.getSymbol(), loaded.getSymbol());
        assertEquals(e.getDataTimeframe(), loaded.getDataTimeframe());
        assertEquals(e.getStartTime(), loaded.getStartTime());
        assertEquals(e.getEndTime(), loaded.getEndTime());
        assertEquals(e.getParams(), loaded.getParams());
        assertEquals(e.getSpreadMode(), loaded.getSpreadMode());
        assertEquals(0, e.getSpread().compareTo(loaded.getSpread()));
        assertEquals(0, e.getSlippage().compareTo(loaded.getSlippage()));
        assertEquals(e.getSwapOn(), loaded.getSwapOn());
        assertEquals(e.getMode(), loaded.getMode());
        assertEquals(0, e.getWinRate().compareTo(loaded.getWinRate()));
        assertEquals(0, e.getProfitFactor().compareTo(loaded.getProfitFactor()));
        assertEquals(0, e.getMaxDrawdown().compareTo(loaded.getMaxDrawdown()));
        assertEquals(e.getTradeCount(), loaded.getTradeCount());
        assertEquals(e.getAvgHoldSec(), loaded.getAvgHoldSec());
        assertEquals(e.getStatus(), loaded.getStatus());

        loaded.setStatus("RUNNING");
        loaded.setTradeCount(99);
        backtestRunMapper.update(loaded);
        BacktestRun updated = backtestRunMapper.getById(e.getId());
        assertEquals("RUNNING", updated.getStatus());
        assertEquals(99, updated.getTradeCount());

        backtestRunMapper.deleteById(e.getId());
        assertNull(backtestRunMapper.getById(e.getId()));
    }

    @Test
    void backtestTrade_insertUpdateDelete() {
        BacktestTrade e = new BacktestTrade();
        e.setRunId(61L);
        e.setOpenTime(LocalDateTime.of(2024, 4, 5, 6, 7, 8));
        e.setCloseTime(LocalDateTime.of(2024, 4, 5, 7, 8, 9));
        e.setCmd("SELL");
        e.setVolume(new BigDecimal("0.20"));
        e.setOpenPrice(new BigDecimal("2000.00000"));
        e.setClosePrice(new BigDecimal("1995.00000"));
        e.setSl(new BigDecimal("2010.00000"));
        e.setTp(new BigDecimal("1980.00000"));
        e.setProfit(new BigDecimal("10.00"));
        e.setSwap(new BigDecimal("-0.50"));
        e.setHoldSec(3601);

        backtestTradeMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        BacktestTrade loaded = backtestTradeMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getRunId(), loaded.getRunId());
        assertEquals(e.getOpenTime(), loaded.getOpenTime());
        assertEquals(e.getCloseTime(), loaded.getCloseTime());
        assertEquals(e.getCmd(), loaded.getCmd());
        assertEquals(0, e.getVolume().compareTo(loaded.getVolume()));
        assertEquals(0, e.getOpenPrice().compareTo(loaded.getOpenPrice()));
        assertEquals(0, e.getClosePrice().compareTo(loaded.getClosePrice()));
        assertEquals(0, e.getSl().compareTo(loaded.getSl()));
        assertEquals(0, e.getTp().compareTo(loaded.getTp()));
        assertEquals(0, e.getProfit().compareTo(loaded.getProfit()));
        assertEquals(0, e.getSwap().compareTo(loaded.getSwap()));
        assertEquals(e.getHoldSec(), loaded.getHoldSec());

        loaded.setCmd("BUY");
        loaded.setHoldSec(4000);
        backtestTradeMapper.update(loaded);
        BacktestTrade updated = backtestTradeMapper.getById(e.getId());
        assertEquals("BUY", updated.getCmd());
        assertEquals(4000, updated.getHoldSec());

        backtestTradeMapper.deleteById(e.getId());
        assertNull(backtestTradeMapper.getById(e.getId()));
    }
}