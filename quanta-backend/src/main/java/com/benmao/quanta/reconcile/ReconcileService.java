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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对账与断线恢复（M13，设计 §17）。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>启动认领</b>（{@link #claimExistingPositions()}）：重启后把「MT5 有持仓但本地无流水」的持仓
 *       补建为认领单（status=FILLED、trace_id=CLAIM-&lt;ticket&gt;），杜绝"孤儿持仓"；</li>
 *   <li><b>对账</b>（{@link #reconcile()}）：用 MT5 成交历史核对 {@code trade_order}，
 *       差异<b>只报告</b>（落 operation_audit + 告警），<b>绝不改账</b>。</li>
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
            String summary = summarize(diffs);
            auditService.write(props.getAuditOperator(), "RECONCILE_DIFF", "mt5Deals=" + deals.size(),
                    null, summary, null);
            if (props.isNotifyOnDiff()) {
                alertService.reconcileDiff(summary);
            }
            log.warn("对账发现 {} 处差异：{}", diffs.size(), summary);
        }
        return new ReconcileReport(from, to, deals.size(), diffs);
    }

    /** 定时对账（cron 由 {@code quanta.reconcile.cron} 决定）；失败只记日志。 */
    @Scheduled(cron = "${quanta.reconcile.cron:0 */5 * * * *}")
    public void scheduledReconcile() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            reconcile();
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
