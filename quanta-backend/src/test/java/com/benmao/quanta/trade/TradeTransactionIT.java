package com.benmao.quanta.trade;

import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 交易事务闭环真库验收（M6）：验证「同事务」语义——异常整体回滚，不留孤儿流水。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test "-Dtest=TradeTransactionIT" "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class TradeTransactionIT {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("异常 → 整体回滚：写流水后抛异常，不得留下孤儿流水")
    void exceptionRollsBackInsert() {
        int before = tradeOrderMapper.listAll().size();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            tradeOrderMapper.insert(newOrder());
            throw new IllegalStateException("人为制造异常：验证事务整体回滚");
        }));

        assertEquals(before, tradeOrderMapper.listAll().size(), "异常必须整体回滚，不得留下孤儿流水");
    }

    @Test
    @DisplayName("正常提交：流水确实落库（与回滚用例互为对照）")
    void commitPersistsRow() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Long id = tx.execute(status -> {
            TradeOrder e = newOrder();
            tradeOrderMapper.insert(e);
            assertNotNull(e.getId(), "insert 应回填自增 id");
            return e.getId();
        });

        TradeOrder loaded = tradeOrderMapper.getById(id);
        assertNotNull(loaded, "正常提交后应能查到流水");
        tradeOrderMapper.deleteById(id);
        assertNull(tradeOrderMapper.getById(id));
    }

    private static TradeOrder newOrder() {
        TradeOrder e = new TradeOrder();
        e.setTraceId("it-tx-" + System.nanoTime());
        e.setTicket(999_000_000L);
        e.setSymbol("GOLD");
        e.setCmd("BUY");
        e.setVolume(new BigDecimal("0.01"));
        e.setSl(new BigDecimal("4100.00"));
        e.setStatus("FILLED");
        e.setRetcode(10009);
        e.setOpenTime(LocalDateTime.now());
        return e;
    }
}
