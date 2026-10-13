package com.benmao.quanta.budget;

import com.benmao.quanta.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BudgetController 单测（M12）：状态查询 / 立即检查 / 人工重置 三个路由。
 */
class BudgetControllerTest {

    private BudgetGuard guard;
    private BudgetController controller;

    @BeforeEach
    void setUp() {
        guard = mock(BudgetGuard.class);
        controller = new BudgetController(guard);
    }

    @Test
    @DisplayName("GET /status：转发到 BudgetGuard.status()")
    void statusDelegates() {
        BudgetStatus st = new BudgetStatus(LocalDate.of(2026, 10, 13), 800L, 1000L,
                false, false, 0.8, 200L, "未超限");
        when(guard.status()).thenReturn(st);

        Result<BudgetStatus> result = controller.status();

        assertEquals(0, result.getCode());
        assertEquals(st, result.getData());
    }

    @Test
    @DisplayName("POST /check：转发到 BudgetGuard.check()")
    void checkDelegates() {
        when(guard.check()).thenReturn(DegradeOutcome.notTriggered("未超限"));

        Result<DegradeOutcome> result = controller.check();

        assertEquals(0, result.getCode());
        assertFalse(result.getData().triggered());
        verify(guard).check();
    }

    @Test
    @DisplayName("POST /reset：转发 reason 并回执 changed")
    void resetDelegates() {
        when(guard.reset("manual")).thenReturn(true);

        Result<Map<String, Object>> result = controller.reset(new BudgetController.ResetReq("manual"));

        assertEquals(0, result.getCode());
        assertEquals(Boolean.TRUE, result.getData().get("changed"));
        assertEquals(Boolean.FALSE, result.getData().get("degraded"));
        verify(guard).reset("manual");
    }

    @Test
    @DisplayName("POST /reset：空 body 时 reason 为 null")
    void resetWithNullBody() {
        when(guard.reset(null)).thenReturn(false);

        Result<Map<String, Object>> result = controller.reset(null);

        assertEquals(Boolean.FALSE, result.getData().get("changed"));
        verify(guard).reset(null);
    }
}
