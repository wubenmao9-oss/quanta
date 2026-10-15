package com.benmao.quanta.reconcile;

import com.benmao.mt5.model.Deal;
import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.ops.AlertService;
import com.benmao.quanta.ops.OperationAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对账与断线恢复（M13，设计 §17）。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>启动认领</b>（{@link #claimExistingPositions()}）：重启后把「MT5 有持仓但本地无流水」的持仓
 *       补建为认领单（status=FILLED、trace_id=CLAIM-&lt;ticket&gt;），杜绝"孤儿持仓"；</li>
 *   <li><b>对账</b>（{@link #reconcile()}）：用 MT5 成交历史核对 {@code trade_order}，
 *       差异<b>只报告</b>（落 operation_audit + 告警），<b>绝不改账</b>；</li>
 *   <li><b>平仓回写修复</b>（{@link #repairClosedFromDeals()}）：券商侧已触发平仓（成交历史里有 OUT 成交）
 *       但本地仍为 FILLED 时，据 MT5 真实成交回写为 CLOSED，杜绝「本地幽灵持仓」（定时对账自动执行）。</li>
 * </ol>
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** MT5 deal.entry：0=IN 开仓、1=OUT 平仓。 */
    private static final int DEAL_ENTRY_OUT = 1;

    /** 审计中最多罗列的差异条数（避免超长被裁剪后丢失关键信息）。 */
    private static final int MAX_SUMMARY_ITEMS = 10;

    private final Mt5BridgeService bridge;
    private final TradeOrderMapper tradeOrderMapper;
    private final OperationAuditService auditService;
    private final AlertService alertService;
    private final ReconcileProperties props;
    private final Clock clock;

    /** 已告警过的差异键（{@code type#ticket}）：同一差异只报一次，仅出现**新差异**时才再报。 */
    private final Set<String> notifiedDiffKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReconcileService(Mt5BridgeService bridge,
                            TradeOrderMapper tradeOrderMapper,
                            OperationAuditService auditService,
                            AlertService alertService,
                            ReconcileProperties props) {
        this(bridge, tradeOrderMapper, auditService, alertService, props, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    ReconcileService(Mt5BridgeService bridge,
                     TradeOrderMapper tradeOrderMapper,
                     OperationAuditService auditService,
                     AlertService alertService,
                     ReconcileProperties props,
                     Clock clock) {
        this.bridge = bridge;
        this.tradeOrderMapper = tradeOrderMapper;
        this.auditService = auditService;
        this.alertService = alertService;
        this.props = props;
        this.clock = clock;
    }

    /** 启动认领：MT5 可能尚未就绪，失败只记日志、不影响启动。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isEnabled() || !props.isClaimOnStartup()) {
            return;
        }
        try {
            List<Long> claimed = claimExistingPositions();
            if (!claimed.isEmpty()) {
                log.warn("启动认领完成：新认领持仓 {} 个 {}", claimed.size(), claimed);
            } else {
                log.info("启动认领完成：无孤儿持仓");
            }
        } catch (RuntimeException e) {
            log.warn("启动认领失败（MT5 可能未就绪，已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 认领所有「MT5 有持仓但本地无流水」的持仓：补建 status=FILLED 的认领单 + 审计 + 告警。
     *
     * @return 本次新认领的 ticket 列表
     */
    public List<Long> claimExistingPositions() {
        List<Position> positions = bridge.positions(null);
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<Long> claimed = new ArrayList<>();
        for (Position p : positions) {
            if (tradeOrderMapper.findLatestByTicket(p.ticket()) != null) {
                continue;
            }
            TradeOrder e = buildClaim(p);
            tradeOrderMapper.insert(e);
            auditService.write(props.getAuditOperator(), "CLAIM", String.valueOf(p.ticket()), null,
                    "ticket=" + p.ticket() + " symbol=" + e.getSymbol() + " volume=" + e.getVolume()
                            + " price=" + e.getPrice(), e.getTraceId());
            alertService.orphanClaimed("ticket=" + p.ticket() + " symbol=" + e.getSymbol()
                    + " volume=" + e.getVolume());
            claimed.add(p.ticket());
            log.warn("认领孤儿持仓 ticket={} symbol={} volume={} price={}",
                    p.ticket(), e.getSymbol(), e.getVolume(), e.getPrice());
        }
        return claimed;
    }

    /**
     * 对账：核对 MT5 窗口内成交历史与本地流水，返回差异清单（不修改任何 trade_order）。
     */
    public ReconcileReport reconcile() {
        Instant now = clock.instant();
        long toEpoch = now.getEpochSecond();
        long fromEpoch = now.minusSeconds(Math.max(1, props.getLookbackHours()) * 3600L).getEpochSecond();

        List<Deal> deals = bridge.historyDeals(fromEpoch, toEpoch);
        if (deals == null) {
            deals = List.of();
        }

        Map<Long, List<Deal>> byTicket = new LinkedHashMap<>();
        for (Deal d : deals) {
            if (d.positionId() <= 0) {
                continue;
            }
            byTicket.computeIfAbsent(d.positionId(), k -> new ArrayList<>()).add(d);
        }

        List<Diff> diffs = new ArrayList<>();
        for (Map.Entry<Long, List<Deal>> en : byTicket.entrySet()) {
            long ticket = en.getKey();
            List<Deal> group = en.getValue();
            boolean hasOut = group.stream().anyMatch(d -> d.entry() == DEAL_ENTRY_OUT);
            double mt5Pnl = group.stream().filter(d -> d.entry() == DEAL_ENTRY_OUT)
                    .mapToDouble(d -> d.profit() + d.swap()).sum();

            TradeOrder local = tradeOrderMapper.findLatestByTicket(ticket);
            if (local == null) {
                diffs.add(new Diff("MT5_DEAL_WITHOUT_LOCAL", ticket,
                        "MT5 有成交（" + group.size() + " 笔）但本地无 trade_order"));
                continue;
            }
            if (hasOut && !"CLOSED".equals(local.getStatus())) {
                diffs.add(new Diff("LOCAL_STATUS_DRIFT", ticket,
                        "MT5 已平仓但本地状态=" + local.getStatus()));
            }
            if (hasOut && local.getProfit() != null) {
                double localPnl = local.getProfit().doubleValue()
                        + (local.getSwap() == null ? 0d : local.getSwap().doubleValue());
                if (Math.abs(localPnl - mt5Pnl) > props.getProfitTolerance()) {
                    diffs.add(new Diff("PROFIT_MISMATCH", ticket,
                            "本地盈亏 " + round(localPnl) + " ≠ MT5 " + round(mt5Pnl)));
                }
            }
        }

        // 反向核对：本地窗口内流水在 MT5 成交历史中找不到 → 疑似幽灵记录
        LocalDateTime from = LocalDateTime.ofInstant(Instant.ofEpochSecond(fromEpoch), clock.getZone());
        LocalDateTime to = LocalDateTime.ofInstant(Instant.ofEpochSecond(toEpoch), clock.getZone());
        for (TradeOrder o : tradeOrderMapper.listAll()) {
            Long ticket = o.getTicket();
            if (ticket == null || ticket <= 0 || byTicket.containsKey(ticket)) {
                continue;
            }
            if (within(o.getOpenTime(), from, to) || within(o.getCloseTime(), from, to)) {
                diffs.add(new Diff("LOCAL_WITHOUT_MT5_DEAL", ticket,
                        "本地有流水（status=" + o.getStatus() + "）但 MT5 窗口内无成交"));
            }
        }

        if (!diffs.isEmpty()) {
            Set<String> current = new LinkedHashSet<>();
            for (Diff d : diffs) {
                current.add(d.type() + "#" + d.ticket());
            }
            Set<String> fresh = new LinkedHashSet<>(current);
            fresh.removeAll(notifiedDiffKeys);
            notifiedDiffKeys.retainAll(current);   // 已消失的差异移出，日后重现时可再报
            notifiedDiffKeys.addAll(current);

            if (fresh.isEmpty()) {
                log.debug("对账差异与上次一致（{} 处），不再重复告警", diffs.size());
            } else {
                List<Diff> freshDiffs = new ArrayList<>();
                for (Diff d : diffs) {
                    if (fresh.contains(d.type() + "#" + d.ticket())) {
                        freshDiffs.add(d);
                    }
                }
                String summary = summarize(freshDiffs);
                auditService.write(props.getAuditOperator(), "RECONCILE_DIFF",
                        "mt5Deals=" + deals.size() + " newDiffs=" + freshDiffs.size(), null, summary, null);
                if (props.isNotifyOnDiff()) {
                    alertService.reconcileDiff(summary);
                }
                log.warn("对账发现差异（总 {} 处，新增 {} 处）：{}", diffs.size(), freshDiffs.size(), summary);
            }
        }
        return new ReconcileReport(from, to, deals.size(), diffs);
    }

    /**
     * 平仓回写修复：当 MT5 成交历史里存在某持仓的 OUT（平仓）成交，而本地 {@code trade_order}
     * 仍为 FILLED 时，据 MT5 的真实成交回写为 CLOSED（平仓价 / 盈亏 / 掉期 / 平仓时间），
     * 修复「券商已平仓但本地仍显示在场」导致的状态漂移与盈亏统计失真。
     *
     * <p>只依据成交历史（不做「本地有单、券商无持仓」的推断），因此不会误杀真正的在场单。
     *
     * @return 本次回写的 ticket 列表
     */
    public List<Long> repairClosedFromDeals() {
        Instant now = clock.instant();
        long toEpoch = now.getEpochSecond();
        long fromEpoch = now.minusSeconds(Math.max(1, props.getLookbackHours()) * 3600L).getEpochSecond();

        List<Deal> deals = bridge.historyDeals(fromEpoch, toEpoch);
        if (deals == null || deals.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Deal>> byTicket = new LinkedHashMap<>();
        for (Deal d : deals) {
            if (d.positionId() > 0) {
                byTicket.computeIfAbsent(d.positionId(), k -> new ArrayList<>()).add(d);
            }
        }

        List<Long> repaired = new ArrayList<>();
        for (Map.Entry<Long, List<Deal>> en : byTicket.entrySet()) {
            long ticket = en.getKey();
            List<Deal> outs = en.getValue().stream().filter(d -> d.entry() == DEAL_ENTRY_OUT).toList();
            if (outs.isEmpty()) {
                continue;
            }
            TradeOrder local = tradeOrderMapper.findLatestByTicket(ticket);
            if (local == null || "CLOSED".equalsIgnoreCase(local.getStatus())) {
                continue;
            }
            double profit = outs.stream().mapToDouble(Deal::profit).sum();
            double swap = outs.stream().mapToDouble(Deal::swap).sum();
            Deal last = outs.get(outs.size() - 1);

            local.setStatus("CLOSED");
            local.setClosePrice(BigDecimal.valueOf(last.price()));
            local.setProfit(BigDecimal.valueOf(profit));
            local.setSwap(BigDecimal.valueOf(swap));
            local.setCloseTime(parseTime(last.time()));
            tradeOrderMapper.update(local);

            auditService.write(props.getAuditOperator(), "REPAIR_CLOSE", String.valueOf(ticket),
                    "status=FILLED", "CLOSED:profit=" + round(profit), local.getTraceId());
            repaired.add(ticket);
            log.warn("对账回写：ticket={} 券商已平仓，本地 FILLED → CLOSED（profit={}, swap={}）",
                    ticket, round(profit), round(swap));
        }
        return repaired;
    }

    /** 定时对账（cron 由 {@code quanta.reconcile.cron} 决定）；失败只记日志。 */
    @Scheduled(cron = "${quanta.reconcile.cron:0 */5 * * * *}")
    public void scheduledReconcile() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            reconcile();
            List<Long> repaired = repairClosedFromDeals();
            if (!repaired.isEmpty()) {
                alertService.reconcileDiff("自动回写平仓 " + repaired.size() + " 笔：" + repaired);
            }
        } catch (RuntimeException e) {
            log.warn("定时对账失败（已忽略）：{}", e.getMessage());
        }
    }

    // ==================== 内部实现 ====================

    /** 由 MT5 持仓补建认领单：status=FILLED、trace_id=CLAIM-&lt;ticket&gt;、tpMode=NONE。 */
    private static TradeOrder buildClaim(Position p) {
        TradeOrder e = new TradeOrder();
        e.setTraceId("CLAIM-" + p.ticket());
        e.setTicket(p.ticket());
        e.setSymbol(p.symbol());
        e.setCmd(p.type() == null ? null : p.type().toUpperCase(Locale.ROOT));
        e.setVolume(BigDecimal.valueOf(p.volume()));
        e.setPrice(BigDecimal.valueOf(p.openPrice()));
        e.setSl(p.sl() == 0 ? null : BigDecimal.valueOf(p.sl()));
        e.setTp(p.tp() == 0 ? null : BigDecimal.valueOf(p.tp()));
        e.setTpMode("NONE");
        e.setTrailMode("NONE");
        e.setStatus("FILLED");
        e.setOpenTime(parseTime(p.openTime()));
        return e;
    }

    /** 容错解析 MT5 返回的持仓开仓时间（ISO-8601 带时区 / 空格分隔两种）。 */
    private static LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 无时区信息，按本地时间解析
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    private static boolean within(LocalDateTime t, LocalDateTime from, LocalDateTime to) {
        return t != null && !t.isBefore(from) && t.isBefore(to);
    }

    private static String summarize(List<Diff> diffs) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Diff d : diffs) {
            if (n > 0) {
                sb.append("；");
            }
            sb.append(d.type()).append("(ticket=").append(d.ticket()).append(")");
            if (++n >= MAX_SUMMARY_ITEMS) {
                sb.append("；…共 ").append(diffs.size()).append(" 处");
                break;
            }
        }
        return sb.toString();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 对账报告（只读快照）。 */
    public record ReconcileReport(LocalDateTime from, LocalDateTime to, int mt5DealCount, List<Diff> diffs) {

        public ReconcileReport {
            diffs = diffs == null ? List.of() : List.copyOf(diffs);
        }

        /** 是否完全一致。 */
        public boolean consistent() {
            return diffs.isEmpty();
        }
    }

    /** 单条差异。 */
    public record Diff(String type, long ticket, String detail) {
    }
}
