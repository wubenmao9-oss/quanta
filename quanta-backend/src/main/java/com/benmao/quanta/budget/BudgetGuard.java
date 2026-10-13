package com.benmao.quanta.budget;

import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.entity.OperationAudit;
import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.OperationAuditMapper;
import com.benmao.quanta.notify.NotifyService;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskProperties;
import com.benmao.quanta.state.SystemStateService;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Token 预算与降级守卫 BudgetGuard（M12，设计 §8.4）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>按日聚合</b> {@code agent_decision_log.tokens}，与「激活 LLM 配置」的 {@code tokenBudgetDaily} 比较
 *       （为空 = 无上限）；</li>
 *   <li>超限 → 邮件告警 + 进入 <b>DEGRADED</b>（落库 {@code sys_state}，{@link com.benmao.quanta.schedule.TriggerEngine} 随之关闭全部触发）；</li>
 *   <li>遍历持仓：{@code profit > 0} 立即平仓（审计 {@code TOKEN_EXHAUSTED}），{@code profit ≤ 0} 保留；</li>
 *   <li>对保留仓位确保存在 broker 侧 <b>HARD SL</b>（原无 SL 的按固定点数补挂）；</li>
 *   <li>恢复：次日 00:00 自动重置，或人工重置。</li>
 * </ol>
 *
 * <p><b>幂等 / 可重入</b>：已降级再次执行不会重复告警；盈利仓已平、保留仓已有 SL 后重复执行不再产生动作。
 *
 * <p><b>确定性</b>：全流程纯 Java 执行（此时 AI 已停用）；平仓/补挂一律经 {@link TradingService}
 * （仍过 {@code RiskGuard}，Agent 层无任何路径可绕过风控）。
 */
@Service
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    /** 审计原因标记（operation_audit.after_val 前缀）。 */
    public static final String REASON = "TOKEN_EXHAUSTED";

    private final BudgetProperties props;
    private final ConfigService configService;
    private final SystemStateService stateService;
    private final NotifyService notifyService;
    private final AgentDecisionLogMapper decisionLogMapper;
    private final OperationAuditMapper auditMapper;
    private final Mt5BridgeService bridge;
    private final TradingService tradingService;
    private final RiskProperties riskProps;
    private final Clock clock;

    @Autowired
    public BudgetGuard(BudgetProperties props,
                       ConfigService configService,
                       SystemStateService stateService,
                       NotifyService notifyService,
                       AgentDecisionLogMapper decisionLogMapper,
                       OperationAuditMapper auditMapper,
                       Mt5BridgeService bridge,
                       TradingService tradingService,
                       RiskProperties riskProps) {
        this(props, configService, stateService, notifyService, decisionLogMapper, auditMapper,
                bridge, tradingService, riskProps, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    BudgetGuard(BudgetProperties props,
                ConfigService configService,
                SystemStateService stateService,
                NotifyService notifyService,
                AgentDecisionLogMapper decisionLogMapper,
                OperationAuditMapper auditMapper,
                Mt5BridgeService bridge,
                TradingService tradingService,
                RiskProperties riskProps,
                Clock clock) {
        this.props = props;
        this.configService = configService;
        this.stateService = stateService;
        this.notifyService = notifyService;
        this.decisionLogMapper = decisionLogMapper;
        this.auditMapper = auditMapper;
        this.bridge = bridge;
        this.tradingService = tradingService;
        this.riskProps = riskProps;
        this.clock = clock;
    }

    // ==================== 对外 API ====================

    /** 预算状态（只读；跨日时顺带完成自动重置）。 */
    public BudgetStatus status() {
        rolloverIfNewDay();
        return computeStatus();
    }

    /** 当日已用 Token（按 {@code agent_decision_log.tokens} 按日聚合）。 */
    public long usedTokensToday() {
        return aggregateUsed(today());
    }

    /**
     * 检查并按需执行降级流程（供定时任务与人工触发）。
     *
     * @return 降级流程执行结果
     */
    public DegradeOutcome check() {
        rolloverIfNewDay();
        BudgetStatus st = computeStatus();
        stateService.recordTokenUsage(today(), st.usedTokens());
        if (!st.exceeded()) {
            return DegradeOutcome.notTriggered(
                    "未超限：已用 " + st.usedTokens() + "，预算 " + describeBudget(st.budget()));
        }
        return degrade(st);
    }

    /**
     * 人工重置：解除降级态。
     *
     * <p>注意：预算仍持续生效——若当日用量仍超预算，下一次检查会再次进入降级；
     * 如需继续运行，请同时上调 LLM 配置的日 Token 预算或等待次日自动重置。
     *
     * @return 是否发生了状态变化（幂等）
     */
    public boolean reset(String reason) {
        boolean changed = stateService.clearDegraded();
        if (changed) {
            audit("reset", reason == null || reason.isBlank() ? "manual" : reason,
                    "degraded=true", "degraded=false:RESET");
            log.info("Token 预算：人工重置解除降级（{}）", reason);
        }
        return changed;
    }

    // ==================== 定时任务 ====================

    /** 定时检查（间隔由 {@code quanta.budget.check-interval-ms} 决定）；异常不外抛。 */
    @Scheduled(fixedDelayString = "${quanta.budget.check-interval-ms:60000}")
    public void scheduledCheck() {
        if (!props.isEnabled() || props.getCheckIntervalMs() <= 0) {
            return;
        }
        try {
            check();
        } catch (RuntimeException e) {
            log.warn("Token 预算定时检查异常（已隔离）：{}", msg(e));
        }
    }

    /** 次日 00:00 自动重置（cron 由 {@code quanta.budget.reset-cron} 决定）。 */
    @Scheduled(cron = "${quanta.budget.reset-cron:0 0 0 * * *}")
    public void scheduledReset() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            rolloverIfNewDay();
        } catch (RuntimeException e) {
            log.warn("Token 预算次日重置异常（已隔离）：{}", msg(e));
        }
    }

    // ==================== 降级流程 ====================

    private DegradeOutcome degrade(BudgetStatus st) {
        boolean alreadyDegraded = stateService.isDegraded();
        boolean newlyDegraded = stateService.markDegraded();
        if (newlyDegraded) {
            audit("degrade", "budget", "degraded=false", "degraded=true:" + REASON);
            log.warn("Token 超限，进入 DEGRADED：已用 {}，预算 {}", st.usedTokens(), st.budget());
        }

        boolean notified = false;
        if (newlyDegraded) {
            notified = notifyService.email(props.getNotifyType(),
                    "[quanta] 日 Token 预算超限，已降级",
                    "当日已用 Token " + st.usedTokens() + "，预算 " + describeBudget(st.budget())
                            + "。系统已进入 DEGRADED：触发全部关闭，盈利仓将被平掉，亏损仓保留并补挂 HARD SL。");
        }

        List<Long> closed = new ArrayList<>();
        List<Long> kept = new ArrayList<>();
        List<Long> protectedTickets = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        List<Position> positions = bridge.positions(null);
        for (Position pos : positions) {
            long ticket = pos.ticket();
            if (pos.profit() > 0) {
                closeForTokenExhausted(pos, closed, failures);
            } else {
                kept.add(ticket);
                ensureHardSl(pos, protectedTickets, failures);
            }
        }

        String note = "降级处置：平盈利仓 " + closed.size() + " 个，保留 " + kept.size() + " 个，补挂 HARD SL "
                + protectedTickets.size() + " 个，失败 " + failures.size() + " 个";
        log.warn("Token 降级完成：{}（首次降级={}，已告警={}）", note, newlyDegraded, notified);
        return new DegradeOutcome(true, newlyDegraded, alreadyDegraded, closed, kept, protectedTickets,
                failures, notified, note);
    }

    /** profit > 0 → 立即平仓并审计；失败逐条记录，不中断其它处置。 */
    private void closeForTokenExhausted(Position pos, List<Long> closed, List<String> failures) {
        long ticket = pos.ticket();
        try {
            tradingService.closePosition(new TradeCommand(RiskAction.CLOSE, pos.symbol(), null, null, null,
                    null, null, null, ticket, null, null, traceId()));
            closed.add(ticket);
            audit("close", "ticket=" + ticket, "profit=" + pos.profit(), "CLOSED:" + REASON);
        } catch (RuntimeException e) {
            failures.add("平仓 ticket=" + ticket + " 失败：" + msg(e));
            log.warn("Token 降级平仓失败 ticket={}：{}", ticket, msg(e));
        }
    }

    /** 保留仓位：若无 HARD SL 则按固定点数补挂（SHORT 在上方、LONG 在下方）。 */
    private void ensureHardSl(Position pos, List<Long> protectedTickets, List<String> failures) {
        long ticket = pos.ticket();
        if (pos.sl() != 0) {
            return;
        }
        double points = props.getDegrade().getHardSlPoints();
        if (points <= 0) {
            failures.add("ticket=" + ticket + " 未补挂 SL：hard-sl-points<=0");
            return;
        }
        try {
            double distance = points * riskProps.getPoint();
            boolean buy = "buy".equalsIgnoreCase(pos.type());
            double raw = buy ? pos.currentPrice() - distance : pos.currentPrice() + distance;
            BigDecimal sl = roundPrice(raw);
            tradingService.modifySlTp(new TradeCommand(RiskAction.MODIFY, pos.symbol(), null, null, null,
                    sl, null, null, ticket, null, null, traceId()));
            protectedTickets.add(ticket);
            audit("protect", "ticket=" + ticket, "sl=" + pos.sl(), "sl=" + sl + ":" + REASON);
        } catch (RuntimeException e) {
            failures.add("补挂 SL ticket=" + ticket + " 失败：" + msg(e));
            log.warn("Token 降级补挂 HARD SL 失败 ticket={}：{}", ticket, msg(e));
        }
    }

    // ==================== 内部实现 ====================

    /** 跨日则用量归零；若处于降级态则自动恢复（设计 §8.4 恢复）。 */
    private boolean rolloverIfNewDay() {
        LocalDate today = today();
        SysState cur = stateService.current();
        if (cur.getTokenDate() != null && cur.getTokenDate().equals(today)) {
            return false;
        }
        if (Boolean.TRUE.equals(cur.getDegraded())) {
            stateService.clearDegraded();
            audit("reset", "auto-new-day", "degraded=true", "degraded=false:NEW_DAY");
            log.info("Token 预算：进入新的一天（{}），自动解除降级", today);
        }
        stateService.recordTokenUsage(today, 0L);
        return true;
    }

    private BudgetStatus computeStatus() {
        LocalDate today = today();
        long used = aggregateUsed(today);
        Long budget = currentBudget();
        boolean exceeded = budget != null && used > budget;
        boolean degraded = stateService.isDegraded();

        Double ratio = null;
        Long remaining = null;
        String note;
        if (budget == null) {
            note = "未设置日 Token 预算（无上限）";
        } else {
            ratio = budget <= 0 ? null : (double) used / budget;
            remaining = Math.max(0L, budget - used);
            note = exceeded ? "已超限，应进入降级" : "未超限";
        }
        return new BudgetStatus(today, used, budget, exceeded, degraded, ratio, remaining, note);
    }

    private long aggregateUsed(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        Long sum = decisionLogMapper.sumTokensBetween(start, end);
        return sum == null ? 0L : sum;
    }

    /** 取激活 LLM 配置的日预算；未配置 / 无法读取 → {@code null}（无上限，不误降级）。 */
    private Long currentBudget() {
        Map<String, Object> cfg;
        try {
            cfg = configService.activeDecrypted(ConfigType.LLM);
        } catch (RuntimeException e) {
            log.warn("读取激活 LLM 配置失败，按「无上限」处理：{}", msg(e));
            return null;
        }
        if (cfg == null) {
            return null;
        }
        return longOf(cfg.get("tokenBudgetDaily"));
    }

    private void audit(String opType, String target, String before, String after) {
        try {
            OperationAudit a = new OperationAudit();
            a.setOperator(props.getAuditOperator());
            a.setOpType("TOKEN_DEGRADE:" + opType);
            a.setTarget(clip(target, 64));
            a.setBeforeVal(clip(before, 255));
            a.setAfterVal(clip(after, 255));
            a.setTraceId(traceId());
            auditMapper.insert(a);
        } catch (RuntimeException e) {
            log.warn("operation_audit 落库失败：{}", msg(e));
        }
    }

    private BigDecimal roundPrice(double value) {
        int scale = Math.max(0, (int) Math.round(-Math.log10(riskProps.getPoint())));
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static String describeBudget(Long budget) {
        return budget == null ? "无上限" : budget.toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String traceId() {
        String t = MDC.get(com.benmao.quanta.common.Result.TRACE_ID_KEY);
        if (t != null && !t.isBlank()) {
            return t;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String msg(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
