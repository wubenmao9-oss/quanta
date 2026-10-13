package com.benmao.quanta.state;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.SysStateMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SystemStateService 单测（M12）：sys_state 单行读写、降级幂等、重启后保持。
 *
 * <p>用内存假 Mapper 模拟持久化，重点验证「重启（换一个 Service 实例读同一存储）后降级态仍在」。
 */
class SystemStateServiceTest {

    private final FakeSysStateMapper mapper = new FakeSysStateMapper();
    private final SystemStateService service = new SystemStateService(mapper);

    @Test
    @DisplayName("无记录时 current() 返回未落库默认态：AUTO / 未降级 / 0 / 日期空")
    void currentReturnsDefaultWhenEmpty() {
        SysState s = service.current();

        assertEquals(SystemStateService.DEFAULT_RUN_MODE, s.getRunMode());
        assertEquals(Boolean.FALSE, s.getDegraded());
        assertEquals(0L, s.getTokenUsedToday());
        assertNull(s.getTokenDate());
        assertFalse(service.isDegraded());
        assertEquals(0L, service.tokenUsedToday());
        assertNull(service.tokenDate());
    }

    @Test
    @DisplayName("markDegraded 幂等：首次 true，再次 false；状态落库")
    void markDegradedIsIdempotent() {
        assertTrue(service.markDegraded(), "首次置降级应返回 true");
        assertTrue(service.isDegraded());
        assertFalse(service.markDegraded(), "已降级再置应返回 false（幂等）");
        assertTrue(service.isDegraded());
    }

    @Test
    @DisplayName("clearDegraded 幂等：已降级时 true，未降级再清 false")
    void clearDegradedIsIdempotent() {
        assertFalse(service.clearDegraded(), "本未降级，清除应返回 false");
        service.markDegraded();
        assertTrue(service.clearDegraded(), "已降级，清除应返回 true");
        assertFalse(service.isDegraded());
    }

    @Test
    @DisplayName("recordTokenUsage 持久化用量与归属日期")
    void recordTokenUsagePersists() {
        service.recordTokenUsage(LocalDate.of(2026, 10, 13), 12345L);

        assertEquals(12345L, service.tokenUsedToday());
        assertEquals(LocalDate.of(2026, 10, 13), service.tokenDate());
        // 记录用量不应改动降级态
        assertFalse(service.isDegraded());
    }

    @Test
    @DisplayName("重启后仍保持：换一个 Service 实例读同一存储，降级态与日期不变")
    void degradedSurvivesRestart() {
        service.markDegraded();
        service.recordTokenUsage(LocalDate.of(2026, 10, 13), 999L);

        SystemStateService afterRestart = new SystemStateService(mapper);

        assertTrue(afterRestart.isDegraded(), "重启后应仍为降级态");
        assertEquals(LocalDate.of(2026, 10, 13), afterRestart.tokenDate());
        assertEquals(999L, afterRestart.tokenUsedToday());
    }

    // ==================== 三态运行模式（M13） ====================

    @Test
    @DisplayName("runMode：无记录或空 → AUTO")
    void runModeDefaultsToAuto() {
        assertEquals(RunMode.AUTO, service.runMode());
    }

    @Test
    @DisplayName("setRunMode：切换落库且重启后仍保持；同模式幂等返回 false")
    void setRunModePersistsAndIdempotent() {
        assertTrue(service.setRunMode("TAKEOVER"), "首次切换应返回 true");
        assertEquals(RunMode.TAKEOVER, service.runMode());

        assertFalse(service.setRunMode("takeover"), "同模式（大小写不敏感）应幂等返回 false");

        SystemStateService afterRestart = new SystemStateService(mapper);
        assertEquals(RunMode.TAKEOVER, afterRestart.runMode(), "重启后应仍为 TAKEOVER");
    }

    @Test
    @DisplayName("setRunMode：非法模式抛参数异常且回执合法取值")
    void setRunModeInvalidRejected() {
        BizException e = assertThrows(BizException.class, () -> service.setRunMode("BOGUS"));
        assertTrue(e.getMessage().contains("AUTO / PAUSED / TAKEOVER"), e.getMessage());
    }

    /** 内存假 Mapper：模拟 sys_state 单行存储（insert / update / listAll）。 */
    private static final class FakeSysStateMapper implements SysStateMapper {

        private final AtomicLong seq = new AtomicLong(1);
        private final List<SysState> rows = new ArrayList<>();

        @Override
        public int insert(SysState e) {
            e.setId(seq.getAndIncrement());
            rows.add(copy(e));
            return 1;
        }

        @Override
        public SysState getById(Long id) {
            return rows.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<SysState> listAll() {
            return new ArrayList<>(rows);
        }

        @Override
        public int update(SysState e) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).getId().equals(e.getId())) {
                    rows.set(i, copy(e));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int deleteById(Long id) {
            return rows.removeIf(r -> r.getId().equals(id)) ? 1 : 0;
        }

        private static SysState copy(SysState s) {
            SysState c = new SysState();
            c.setId(s.getId());
            c.setRunMode(s.getRunMode());
            c.setDegraded(s.getDegraded());
            c.setTokenUsedToday(s.getTokenUsedToday());
            c.setTokenDate(s.getTokenDate());
            c.setActiveLlmId(s.getActiveLlmId());
            c.setActiveStrategyId(s.getActiveStrategyId());
            c.setUpdateTime(s.getUpdateTime());
            return c;
        }
    }
}
