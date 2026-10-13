package com.benmao.quanta.budget;

import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.NotifyLog;
import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.NotifyLogMapper;
import com.benmao.quanta.mapper.SysStateMapper;
import com.benmao.quanta.notify.NotifyService;
import com.benmao.quanta.state.SystemStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12 Token 预算与降级真库集成测试：真实读写 {@code sys_state} / {@code agent_decision_log} / {@code notify_log}。
 *
 * <p>确定性保障：用 {@link TestPropertySource} 换掉 AES 密钥 → 库内 LLM 配置解密失败 →
 * {@code activeDecrypted(LLM)} 抛错 → 预算按「无上限」处理，<b>本 IT 不会误触发真实降级、不会发真实邮件</b>。
 *
 * <p>验证点（对应 M12 验收）：
 * <ol>
 *   <li>降级态<b>落库</b>且「重启（换实例）后仍保持」，可人工恢复（幂等）；</li>
 *   <li>{@code agent_decision_log.tokens} <b>按日聚合</b>（半开区间 [since, until)），无记录返回 0；</li>
 *   <li>无可用 MAIL 配置时告警邮件 <b>best-effort</b>：不抛异常、落 {@code notify_log.status=FAILED}；</li>
 *   <li>无预算时不误降级，且当日用量落库。</li>
 * </ol>
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=BudgetMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@TestPropertySource(properties = {
        "quanta.security.aes-key=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY="
})
class BudgetMySqlIT {

    @Autowired
    private BudgetGuard budgetGuard;

    @Autowired
    private SystemStateService systemStateService;

    @Autowired
    private NotifyService notifyService;

    @Autowired
    private SysStateMapper sysStateMapper;

    @Autowired
    private AgentDecisionLogMapper decisionLogMapper;

    @Autowired
    private NotifyLogMapper notifyLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 原始 sys_state（为空表示测试前库里无该行）。 */
    private SysState originalState;

    private final List<Long> createdLogIds = new ArrayList<>();
    private final List<Long> createdNotifyIds = new ArrayList<>();

    @BeforeEach
    void captureOriginalState() {
        List<SysState> rows = sysStateMapper.listAll();
        originalState = rows.isEmpty() ? null : rows.get(0);
    }

    @AfterEach
    void cleanup() {
        createdLogIds.forEach(decisionLogMapper::deleteById);
        createdNotifyIds.forEach(notifyLogMapper::deleteById);
        createdLogIds.clear();
        createdNotifyIds.clear();
        if (originalState == null) {
            sysStateMapper.listAll().forEach(r -> sysStateMapper.deleteById(r.getId()));
        } else {
            sysStateMapper.update(originalState);
        }
    }

    // ==================== ① 降级态落库 + 重启保持 ====================

    @Test
    @DisplayName("降级态落库 → 换实例（模拟重启）仍保持 → 人工恢复幂等")
    void degradedPersistsAcrossRestart() {
        systemStateService.clearDegraded();
        assertFalse(systemStateService.isDegraded());

        assertTrue(systemStateService.markDegraded(), "首次置降级应为 true");
        assertFalse(systemStateService.markDegraded(), "已降级再置应为 false（幂等）");

        // 换一个 Service 实例读同一张表 = 模拟进程重启
        SystemStateService afterRestart = new SystemStateService(sysStateMapper);
        assertTrue(afterRestart.isDegraded(), "重启后降级态应仍存在");

        assertTrue(systemStateService.clearDegraded(), "恢复应返回 true");
        assertFalse(afterRestart.isDegraded(), "恢复后重启实例也应读到未降级");
    }

    // ==================== ② 按日聚合 ====================

    @Test
    @DisplayName("tokens 按日聚合：半开区间 [since, until)，无记录返回 0")
    void sumTokensByHalfOpenWindow() {
        LocalDate day = LocalDate.of(2030, 1, 15);
        Long a = insertDecisionLog("WAIT", 111);
        Long b = insertDecisionLog("OPEN", 222);
        Long boundary = insertDecisionLog("CLOSE", 999); // 落在 until 边界，应被排除

        setCreateTime(a, day.atTime(10, 0));
        setCreateTime(b, day.atTime(23, 59));
        setCreateTime(boundary, day.plusDays(1).atStartOfDay());

        Long sum = decisionLogMapper.sumTokensBetween(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        assertEquals(333L, sum, "仅统计 [since, until) 内的 tokens");

        Long empty = decisionLogMapper.sumTokensBetween(
                LocalDate.of(2040, 1, 1).atStartOfDay(), LocalDate.of(2040, 1, 2).atStartOfDay());
        assertEquals(0L, empty, "无记录应返回 0（COALESCE）");
    }

    // ==================== ③ 告警邮件 best-effort ====================

    @Test
    @DisplayName("无可用 MAIL 配置 → 不抛异常、落 notify_log.status=FAILED")
    void notifyEmailBestEffortOnFailure() {
        List<NotifyLog> before = notifyLogMapper.listAll();
        long maxId = before.stream().mapToLong(NotifyLog::getId).max().orElse(0L);

        boolean ok = notifyService.email("TOKEN_DEGRADE", "IT 主题 " + UUID.randomUUID(), "IT 正文");

        assertFalse(ok, "无可用 MAIL 配置时应返回 false");
        List<NotifyLog> created = notifyLogMapper.listAll().stream()
                .filter(n -> n.getId() > maxId)
                .toList();
        assertFalse(created.isEmpty(), "应落一条 notify_log");
        NotifyLog n = created.get(created.size() - 1);
        createdNotifyIds.add(n.getId());
        assertEquals("FAILED", n.getStatus());
        assertEquals(NotifyService.CHANNEL_EMAIL, n.getChannel());
        assertNotNull(n.getError());
    }

    // ==================== ④ 无预算不误降级 + 用量落库 ====================

    @Test
    @DisplayName("无预算（LLM 配置读不到）→ 不误降级；当日用量写入 sys_state")
    void checkRecordsUsageWithoutBudget() {
        DegradeOutcome outcome = budgetGuard.check();

        assertFalse(outcome.triggered(), "无预算不应触发降级");

        BudgetStatus st = budgetGuard.status();
        assertNull(st.budget(), "读不到 LLM 预算 → 无上限");
        assertFalse(st.exceeded());
        assertFalse(st.degraded());

        SysState now = sysStateMapper.listAll().get(0);
        assertEquals(LocalDate.now(), now.getTokenDate(), "当日日期应落库");
        assertNotNull(now.getTokenUsedToday());
        assertEquals(Boolean.FALSE, now.getDegraded());
    }

    // ==================== 辅助 ====================

    private Long insertDecisionLog(String action, int tokens) {
        AgentDecisionLog e = new AgentDecisionLog();
        e.setSessionId(1L);
        e.setTraceId("itb" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        e.setTriggerType("T6");
        e.setAction(action);
        e.setModel("it-model");
        e.setPromptVersion(1);
        e.setTokens(tokens);
        e.setLatencyMs(10);
        decisionLogMapper.insert(e);
        createdLogIds.add(e.getId());
        return e.getId();
    }

    /** 用 JdbcTemplate 精确指定 create_time（insert 走库默认 CURRENT_TIMESTAMP）。 */
    private void setCreateTime(Long id, LocalDateTime time) {
        jdbcTemplate.update("UPDATE agent_decision_log SET create_time = ? WHERE id = ?", time, id);
    }
}
