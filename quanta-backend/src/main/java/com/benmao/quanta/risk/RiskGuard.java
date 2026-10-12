package com.benmao.quanta.risk;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 风控层 RiskGuard —— 一期命门，所有 OPEN/CLOSE/MODIFY 的<b>唯一决策入口</b>。
 *
 * <p>设计 §7 的 R1~R15 全量实现；任一条不通过即拒，并返回<b>结构化原因 + 合法边界值</b>，
 * 让 AI 能自我修正后重试（"AI 不可信但可纠错"）。本类只做决策，<b>不下单</b>（下单在 M6）。
 *
 * <p>失败即保守：无法取得行情/账户/持仓时直接拒绝，绝不"带病放行"。
 */
@Service
public class RiskGuard {

    private static final Logger log = LoggerFactory.getLogger(RiskGuard.class);

    /** 浮点比较容差。 */
    private static final double EPS = 1e-9;

    private final Mt5BridgeService bridge;
    private final RiskConfigService riskConfig;
    private final TradeOrderMapper tradeOrderMapper;
    private final SpreadHistory spreadHistory;
    private final RiskProperties props;
    private final Clock clock;

    @Autowired
    public RiskGuard(Mt5BridgeService bridge,
                     RiskConfigService riskConfig,
                     TradeOrderMapper tradeOrderMapper,
                     SpreadHistory spreadHistory,
                     RiskProperties props) {
        this(bridge, riskConfig, tradeOrderMapper, spreadHistory, props, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    RiskGuard(Mt5BridgeService bridge,
              RiskConfigService riskConfig,
              TradeOrderMapper tradeOrderMapper,
              SpreadHistory spreadHistory,
              RiskProperties props,
              Clock clock) {
        this.bridge = bridge;
        this.riskConfig = riskConfig;
        this.tradeOrderMapper = tradeOrderMapper;
        this.spreadHistory = spreadHistory;
        this.props = props;
        this.clock = clock;
    }

    // ==================== 唯一入口 ====================

    /** 校验一次写操作。返回放行/拒绝 + 逐条原因；不影响任何外部状态（除采样点差）。 */
    public RiskDecision evaluate(RiskRequest req) {
        if (req == null || req.action() == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "动作不能为空，可选：OPEN, CLOSE, MODIFY", 400);
        }
        RiskAction action = req.action();
        RiskThresholds th = riskConfig.thresholds();
        String runMode = riskConfig.runMode();

        List<RiskViolation> violations = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        // ---- 不依赖行情的规则（快速拒绝）----
        checkRunMode(action, runMode, violations, checked);          // R11
        if (action == RiskAction.OPEN) {
            checkWhitelist(req, th, violations, checked);            // R1
            checkVolume(req, th, violations, checked);               // R2
            checkOpenWindow(violations, checked);                    // R10
        }

        // ---- 依赖行情/账户/持仓的规则 ----
        if (violations.isEmpty()) {
            MarketSnapshot ms;
            try {
                ms = snapshot(req);
            } catch (RuntimeException e) {
                violations.add(RiskViolation.of("SYS", "MARKET_UNAVAILABLE",
                        "无法获取实时行情/账户/持仓，风控保守拒绝：" + e.getMessage()));
                return new RiskDecision(action, false, violations, checked);
            }
            switch (action) {
                case OPEN -> checkOpen(req, th, ms, violations, checked);
                case CLOSE -> checkClose(req, th, ms, violations, checked);
                case MODIFY -> checkModify(req, th, ms, violations, checked);
            }
        }

        RiskDecision decision = new RiskDecision(action, violations.isEmpty(), violations, checked);
        if (!decision.allowed()) {
            log.warn("风控拒绝 action={} symbol={} ticket={} 原因={}",
                    action, req.symbol(), req.ticket(),
                    violations.stream().map(RiskViolation::code).toList());
        }
        return decision;
    }

    // ==================== R1 品种白名单 ====================

    private void checkWhitelist(RiskRequest req, RiskThresholds th,
                                List<RiskViolation> v, List<String> checked) {
        checked.add("R1");
        String symbol = req.symbol();
        if (symbol == null || symbol.isBlank()) {
            v.add(RiskViolation.of("R1", "SYMBOL_REQUIRED", "品种不能为空",
                    Map.of("allow", th.symbolWhitelist()), "请填白名单内品种"));
            return;
        }
        String resolved;
        try {
            resolved = bridge.resolveSymbol(symbol);
        } catch (RuntimeException e) {
            resolved = null;
        }
        final String broker = resolved;
        boolean allowed = th.symbolWhitelist().stream()
                .anyMatch(w -> w.equalsIgnoreCase(symbol) || (broker != null && w.equalsIgnoreCase(broker)));
        if (!allowed || broker == null) {
            v.add(RiskViolation.of("R1", "SYMBOL_NOT_ALLOWED",
                    "品种 " + symbol + " 不在白名单内",
                    Map.of("allow", th.symbolWhitelist()),
                    "一期只允许：" + String.join(",", th.symbolWhitelist())));
        }
    }

    // ==================== R2 手数边界 ====================

    private void checkVolume(RiskRequest req, RiskThresholds th,
                             List<RiskViolation> v, List<String> checked) {
        checked.add("R2");
        BigDecimal vol = req.volume();
        double min = props.getVolumeMin();
        double step = props.getVolumeStep();
        double max = th.maxVolume().doubleValue();
        if (vol == null) {
            v.add(RiskViolation.of("R2", "VOLUME_REQUIRED", "手数不能为空",
                    bounds(min, max, step), "请在 [" + min + ", " + max + "] 内取 " + step + " 的整数倍"));
            return;
        }
        double value = vol.doubleValue();
        if (value < min - EPS || value > max + EPS) {
            v.add(RiskViolation.of("R2", "VOLUME_OUT_OF_RANGE",
                    "手数 " + vol + " 越界", bounds(min, max, step),
                    "请在 [" + min + ", " + max + "] 内取 " + step + " 的整数倍"));
            return;
        }
        double units = value / step;
        if (Math.abs(units - Math.rint(units)) > 1e-6) {
            v.add(RiskViolation.of("R2", "VOLUME_STEP_MISMATCH",
                    "手数 " + vol + " 不是步长 " + step + " 的整数倍", bounds(min, max, step),
                    "手数需为 " + step + " 的整数倍"));
        }
    }

    private static Map<String, Object> bounds(double min, double max, double step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", min);
        m.put("max", max);
        m.put("step", step);
        return m;
    }

    // ==================== R10 禁开窗口 ====================

    private void checkOpenWindow(List<RiskViolation> v, List<String> checked) {
        checked.add("R10");
        LocalDateTime now = now();
        LocalTime time = now.toLocalTime();
        LocalTime blkStart = parseTime(props.getDailyBlackoutStart());
        LocalTime blkEnd = parseTime(props.getDailyBlackoutEnd());

        boolean weekend = props.getClosedWeekdays().stream()
                .anyMatch(d -> d.equalsIgnoreCase(now.getDayOfWeek().name()));
        boolean blackout = blkStart != null && blkEnd != null && !time.isBefore(blkStart) && time.isBefore(blkEnd);

        if (weekend || blackout) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("reason", weekend ? "周末休市" : "每日禁开窗口");
            b.put("nextOpenAt", nextOpenAt(now, blkEnd).toString());
            if (blackout) {
                b.put("blackoutStart", blkStart.toString());
                b.put("blackoutEnd", blkEnd.toString());
            }
            v.add(RiskViolation.of("R10", "OPEN_WINDOW_CLOSED",
                    (weekend ? "当前为周末休市，" : "当前处于每日禁开窗口，") + "禁止开仓",
                    b, "最早可开时间见 nextOpenAt"));
        }
    }

    /** 粗略推算下一个可开时点：跳过禁开星期与每日禁开窗口（最多向后找 8 天）。 */
    private LocalDateTime nextOpenAt(LocalDateTime now, LocalTime blkEnd) {
        LocalTime open = blkEnd != null ? blkEnd : LocalTime.MIDNIGHT;
        for (int i = 0; i <= 8; i++) {
            LocalDateTime cand = now.toLocalDate().plusDays(i).atTime(open);
            if (cand.isBefore(now)) {
                continue;
            }
            if (props.getClosedWeekdays().stream()
                    .anyMatch(d -> d.equalsIgnoreCase(cand.getDayOfWeek().name()))) {
                continue;
            }
            return cand;
        }
        return now.plusDays(1);
    }

    private static LocalTime parseTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ==================== R11 人工开关（三态运行模式） ====================

    private void checkRunMode(RiskAction action, String runMode,
                              List<RiskViolation> v, List<String> checked) {
        checked.add("R11");
        Map<String, Object> b = Map.of("runMode", runMode);
        if ("PAUSED".equals(runMode) && action == RiskAction.OPEN) {
            v.add(RiskViolation.of("R11", "RUN_MODE_PAUSED",
                    "当前运行模式 PAUSED：暂停自动开仓（平仓/改仓仍允许）", b, "切回 AUTO 后可开仓"));
        } else if ("TAKEOVER".equals(runMode)) {
            v.add(RiskViolation.of("R11", "RUN_MODE_TAKEOVER",
                    "当前运行模式 TAKEOVER：AI 只出建议，执行需人工确认", b, "人工确认或切回 AUTO"));
        }
    }

    // ==================== 依赖行情的规则 ====================

    private void checkOpen(RiskRequest req, RiskThresholds th, MarketSnapshot ms,
                           List<RiskViolation> v, List<String> checked) {
        RiskSide side = req.side();
        if (side == null) {
            v.add(RiskViolation.of("R2", "SIDE_REQUIRED", "开仓方向不能为空，可选：BUY, SELL"));
            return;
        }
        Quote quote = ms.quote();
        double market = marketPrice(side, quote);
        double entry = req.price() != null ? req.price().doubleValue() : market;
        double volume = req.volume() == null ? 0 : req.volume().doubleValue();
        String cmd = side.name();
        String brokerSymbol = safeResolve(req.symbol());
        double spreadPoints = quote.spread();

        // R3 最大持仓
        checked.add("R3");
        int open = ms.positions().size();
        if (open >= th.maxPositions()) {
            v.add(RiskViolation.of("R3", "MAX_POSITIONS_REACHED",
                    "当前持仓 " + open + " 已达上限 " + th.maxPositions(),
                    Map.of("current", open, "max", th.maxPositions()), "先平掉部分持仓再开"));
        }

        // R12 价格偏离
        checked.add("R12");
        if (req.price() != null) {
            double devPct = Math.abs(entry - market) / market * 100.0;
            if (devPct > props.getMaxPriceDeviationPct() + EPS) {
                v.add(RiskViolation.of("R12", "PRICE_DEVIATION",
                        "开仓价与市价偏离 " + round(devPct) + "% 超过 " + props.getMaxPriceDeviationPct() + "%",
                        Map.of("marketPrice", market, "maxDeviationPct", props.getMaxPriceDeviationPct()),
                        "改用以市价为基准的价位"));
            }
        }

        // R9 动态点差阈值
        checked.add("R9");
        spreadHistory.record(spreadPoints, now());
        SpreadHistory.Stat st = spreadHistory.stat(now(), props.getSpreadWindowMinutes());
        double staticLimit = th.spreadLimit().doubleValue();
        double threshold = staticLimit;
        if (st.samples() >= props.getSpreadMinSamples() && !Double.isNaN(st.medianPoints())) {
            threshold = Math.max(staticLimit, st.medianPoints() * props.getSpreadMedianMultiplier());
        }
        if (spreadPoints > threshold + EPS) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("spreadPoints", spreadPoints);
            b.put("thresholdPoints", round(threshold));
            b.put("staticLimit", staticLimit);
            b.put("medianPoints", Double.isNaN(st.medianPoints()) ? null : round(st.medianPoints()));
            b.put("samples", st.samples());
            v.add(RiskViolation.of("R9", "SPREAD_TOO_WIDE",
                    "实时点差 " + spreadPoints + " points 超过阈值 " + round(threshold)
                            + "（max(上限 " + staticLimit + ", 中位×" + props.getSpreadMedianMultiplier() + ")）",
                    b, "等点差回落（多为新闻时段）再开仓"));
        }

        // R6 日亏熔断
        checked.add("R6");
        BigDecimal todayPnl = tradeOrderMapper.sumRealizedPnlSince(now().toLocalDate().atStartOfDay());
        if (todayPnl.compareTo(th.maxDailyLoss().negate()) <= 0) {
            v.add(RiskViolation.of("R6", "DAILY_LOSS_CIRCUIT_BREAKER",
                    "当日已实现亏损 " + todayPnl + " 达到上限 " + th.maxDailyLoss() + "，只允许平仓",
                    Map.of("todayPnl", todayPnl, "maxDailyLoss", th.maxDailyLoss()),
                    "次日或人工解锁后再开仓"));
        }

        // R7 连亏冷静期
        checked.add("R7");
        if (th.cooldownMinutes() > 0) {
            List<TradeOrder> recent = tradeOrderMapper.listRecentClosed(th.maxConsecutiveLoss());
            int consecutive = 0;
            LocalDateTime lastLoss = null;
            for (TradeOrder o : recent) {
                if (pnlOf(o).signum() < 0) {
                    consecutive++;
                    if (lastLoss == null) {
                        lastLoss = o.getCloseTime();
                    }
                } else {
                    break;
                }
            }
            if (consecutive >= th.maxConsecutiveLoss() && lastLoss != null) {
                LocalDateTime resumeAt = lastLoss.plusMinutes(th.cooldownMinutes());
                if (now().isBefore(resumeAt)) {
                    v.add(RiskViolation.of("R7", "COOLDOWN_ACTIVE",
                            "连亏 " + consecutive + " 笔触发冷静期，暂停开仓至 " + resumeAt,
                            Map.of("consecutiveLoss", consecutive, "cooldownMinutes", th.cooldownMinutes(),
                                    "resumeAt", resumeAt.toString()),
                            "冷静期内只允许平仓"));
                }
            }
        }

        // R4 单笔风险上限
        checked.add("R4");
        Double sl = req.sl() == null ? null : req.sl().doubleValue();
        if (sl != null && sl != 0 && slDirectionOk(side, entry, sl)) {
            double riskUsd = Math.abs(entry - sl) * volume * props.getContractSizePerLot();
            double budget = ms.account().equity() * th.maxRiskPct().doubleValue() / 100.0;
            if (riskUsd > budget + EPS) {
                double maxSlDistance = volume <= 0 ? 0 : budget / (volume * props.getContractSizePerLot());
                double denom = Math.abs(entry - sl) * props.getContractSizePerLot();
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("riskUsd", round(riskUsd));
                b.put("budgetUsd", round(budget));
                b.put("equity", ms.account().equity());
                b.put("maxRiskPct", th.maxRiskPct());
                b.put("maxSlDistance", round(maxSlDistance));
                if (denom > 0) {
                    b.put("maxVolume", round(budget / denom));
                }
                v.add(RiskViolation.of("R4", "SINGLE_TRADE_RISK_EXCEEDED",
                        "单笔风险 " + round(riskUsd) + " USD 超过预算 " + round(budget) + " USD",
                        b, "缩小手数或把 SL 收近到 " + round(maxSlDistance) + " 以内"));
            }
        }

        // R5 SL 必填且合法
        checked.add("R5");
        double spreadPrice = spreadPoints * props.getPoint();
        double minDistance = spreadPrice * th.minSlSpreadMult().doubleValue();
        if (sl == null || sl == 0) {
            v.add(RiskViolation.of("R5", "SL_REQUIRED", "开仓必须设置止损（SL 不能为空或 0）",
                    Map.of("minSlDistance", round(minDistance)), "SL 必须为方向正确的非零价"));
        } else if (!slDirectionOk(side, entry, sl)) {
            v.add(RiskViolation.of("R5", "SL_DIRECTION",
                    "SL 方向错误：" + (side == RiskSide.BUY ? "BUY 的 SL 必须低于开仓价" : "SELL 的 SL 必须高于开仓价"),
                    Map.of("entryPrice", entry), "按方向重新给 SL"));
        } else if (Math.abs(entry - sl) < minDistance - EPS) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("spreadPoints", spreadPoints);
            b.put("minSlDistance", round(minDistance));
            b.put("minSlPrice", round(side == RiskSide.BUY ? entry - minDistance : entry + minDistance));
            v.add(RiskViolation.of("R5", "SL_TOO_CLOSE",
                    "SL 距离开仓价 " + round(Math.abs(entry - sl)) + " 小于最小值 " + round(minDistance)
                            + "（= 点差 " + spreadPoints + " × " + th.minSlSpreadMult() + "）",
                    b, "把 SL 移到 minSlPrice 之外"));
        }

        // R15 盈亏比地板（设了 TP 才校验）
        checked.add("R15");
        Double tp = req.tp() == null ? null : req.tp().doubleValue();
        if (tp != null && tp != 0) {
            if (!tpDirectionOk(side, entry, tp)) {
                v.add(RiskViolation.of("R15", "TP_DIRECTION",
                        "TP 方向错误：" + (side == RiskSide.BUY ? "BUY 的 TP 必须高于开仓价" : "SELL 的 TP 必须低于开仓价"),
                        Map.of("entryPrice", entry), "按方向重新给 TP"));
            } else if (sl != null && sl != 0 && slDirectionOk(side, entry, sl)) {
                double slDist = Math.abs(entry - sl);
                double rr = slDist <= 0 ? Double.POSITIVE_INFINITY : Math.abs(tp - entry) / slDist;
                if (rr < th.minRr().doubleValue() - EPS) {
                    double minTpDist = th.minRr().doubleValue() * slDist;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("minRr", th.minRr());
                    b.put("currentRr", round(rr));
                    b.put("minTpPrice", round(side == RiskSide.BUY ? entry + minTpDist : entry - minTpDist));
                    v.add(RiskViolation.of("R15", "RR_BELOW_FLOOR",
                            "盈亏比 " + round(rr) + " 低于地板 " + th.minRr(), b,
                            "把 TP 推到 minTpPrice 之外，或收近 SL"));
                }
            }
        }

        // R8 幂等
        checkIdempotency(req, brokerSymbol, cmd, v, checked);
    }

    private void checkClose(RiskRequest req, RiskThresholds th, MarketSnapshot ms,
                            List<RiskViolation> v, List<String> checked) {
        if (req.ticket() == null) {
            v.add(RiskViolation.of("R13", "TICKET_REQUIRED", "平仓必须提供 ticket"));
            return;
        }
        checked.add("R13");
        Position pos = findPosition(ms.positions(), req.ticket());
        if (pos == null) {
            v.add(RiskViolation.of("R13", "POSITION_NOT_FOUND",
                    "持仓 " + req.ticket() + " 不存在或已平仓"));
        } else {
            checkMutex(req, v);
        }
        checkIdempotency(req, safeResolve(pos == null ? req.symbol() : pos.symbol()),
                pos == null ? null : pos.type(), v, checked);
    }

    private void checkModify(RiskRequest req, RiskThresholds th, MarketSnapshot ms,
                             List<RiskViolation> v, List<String> checked) {
        if (req.ticket() == null) {
            v.add(RiskViolation.of("R13", "TICKET_REQUIRED", "改单必须提供 ticket"));
            return;
        }
        checked.add("R13");
        Position pos = findPosition(ms.positions(), req.ticket());
        if (pos == null) {
            v.add(RiskViolation.of("R13", "POSITION_NOT_FOUND",
                    "持仓 " + req.ticket() + " 不存在或已平仓"));
            return;
        }
        checkMutex(req, v);

        RiskSide side;
        try {
            side = RiskSide.parse(pos.type());
        } catch (BizException e) {
            side = null;
        }
        Quote quote = ms.quote();
        double market = side == null ? 0 : (side == RiskSide.BUY ? quote.bid() : quote.ask());

        // R14 软位单调性：SL 只能收紧
        checked.add("R14");
        Double newSl = req.sl() == null ? null : req.sl().doubleValue();
        if (side != null && newSl != null && newSl != 0) {
            double oldSl = pos.sl();
            if (!slDirectionOk(side, market, newSl)) {
                v.add(RiskViolation.of("R14", "SL_DIRECTION",
                        "新 SL 方向错误：" + (side == RiskSide.BUY ? "必须低于现价" : "必须高于现价"),
                        Map.of("marketPrice", market), "按方向重新给 SL"));
            } else if (oldSl > 0) {
                boolean loosened = side == RiskSide.BUY ? newSl <= oldSl : newSl >= oldSl;
                if (loosened) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("currentSl", oldSl);
                    b.put("marketPrice", market);
                    b.put("allowed", side == RiskSide.BUY
                            ? "(" + oldSl + ", " + round(market) + ")"
                            : "(" + round(market) + ", " + oldSl + ")");
                    v.add(RiskViolation.of("R14", "SL_NOT_TIGHTENED",
                            "SL 只能收紧（更靠近价格），不得放大风险",
                            b, side == RiskSide.BUY ? "新 SL 必须大于 " + oldSl : "新 SL 必须小于 " + oldSl));
                }
            }
        }

        // R15 盈亏比（TP 可来自请求或原持仓）
        checked.add("R15");
        if (side != null) {
            double entry = pos.openPrice();
            Double slRef = (newSl != null && newSl != 0) ? newSl : (pos.sl() != 0 ? pos.sl() : null);
            Double tpRef = (req.tp() != null && req.tp().doubleValue() != 0)
                    ? req.tp().doubleValue() : (pos.tp() != 0 ? pos.tp() : null);
            if (tpRef != null && slRef != null && slRef != entry) {
                if (!tpDirectionOk(side, entry, tpRef)) {
                    v.add(RiskViolation.of("R15", "TP_DIRECTION",
                            "TP 方向错误", Map.of("entryPrice", entry), "按方向重新给 TP"));
                } else {
                    double slDist = Math.abs(entry - slRef);
                    double rr = slDist <= 0 ? Double.POSITIVE_INFINITY : Math.abs(tpRef - entry) / slDist;
                    if (rr < th.minRr().doubleValue() - EPS) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("minRr", th.minRr());
                        b.put("currentRr", round(rr));
                        v.add(RiskViolation.of("R15", "RR_BELOW_FLOOR",
                                "盈亏比 " + round(rr) + " 低于地板 " + th.minRr(), b, "重设 TP/SL 使盈亏比达标"));
                    }
                }
            }
        }

        checkIdempotency(req, safeResolve(pos.symbol()), pos.type(), v, checked);
    }

    // ==================== R13 持仓互斥 ====================

    private void checkMutex(RiskRequest req, List<RiskViolation> v) {
        TradeOrder order = tradeOrderMapper.findLatestByTicket(req.ticket());
        if (order != null && order.getSessionId() != null && req.sessionId() != null
                && !order.getSessionId().equals(req.sessionId())) {
            v.add(RiskViolation.of("R13", "POSITION_LOCKED_BY_SESSION",
                    "持仓 " + req.ticket() + " 正被会话 " + order.getSessionId() + " 接管",
                    Map.of("ownerSessionId", order.getSessionId(), "currentSessionId", req.sessionId()),
                    "由持有会话操作或先释放"));
        }
    }

    // ==================== R8 幂等 ====================

    private void checkIdempotency(RiskRequest req, String brokerSymbol, String cmd,
                                  List<RiskViolation> v, List<String> checked) {
        checked.add("R8");
        LocalDateTime since = now().minusSeconds(props.getIdempotencyWindowSeconds());
        if (req.traceId() != null && !req.traceId().isBlank()
                && tradeOrderMapper.countByTraceIdSince(req.traceId(), since) > 0) {
            v.add(RiskViolation.of("R8", "DUPLICATE_TRACE_ID",
                    "trace_id " + req.traceId() + " 在 " + props.getIdempotencyWindowSeconds() + "s 内已下单",
                    Map.of("windowSeconds", props.getIdempotencyWindowSeconds()), "换新 trace_id"));
            return;
        }
        if (brokerSymbol != null && cmd != null && req.volume() != null) {
            int dup = tradeOrderMapper.countSameParamsSince(brokerSymbol, cmd.toUpperCase(Locale.ROOT),
                    req.volume(), req.sl(), req.tp(), since);
            if (dup > 0) {
                v.add(RiskViolation.of("R8", "DUPLICATE_PARAMS",
                        "相同参数（品种/方向/手数/SL/TP）在 " + props.getIdempotencyWindowSeconds() + "s 内已下过单",
                        Map.of("windowSeconds", props.getIdempotencyWindowSeconds()), "确认非误触后再下单"));
            }
        }
    }

    // ==================== 辅助 ====================

    private MarketSnapshot snapshot(RiskRequest req) {
        List<Position> positions = bridge.positions(null);
        String symbol = req.symbol();
        if ((symbol == null || symbol.isBlank()) && req.ticket() != null) {
            Position p = findPosition(positions, req.ticket());
            symbol = p == null ? null : p.symbol();
        }
        if (symbol == null || symbol.isBlank()) {
            symbol = bridge.defaultSymbol();
        }
        Quote quote = bridge.quote(symbol);
        AccountInfo account = bridge.account();
        return new MarketSnapshot(quote, account, positions);
    }

    private String safeResolve(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        try {
            return bridge.resolveSymbol(symbol);
        } catch (RuntimeException e) {
            return symbol;
        }
    }

    private static Position findPosition(List<Position> positions, long ticket) {
        for (Position p : positions) {
            if (p.ticket() == ticket) {
                return p;
            }
        }
        return null;
    }

    private static double marketPrice(RiskSide side, Quote quote) {
        return side == RiskSide.BUY ? quote.ask() : quote.bid();
    }

    private static boolean slDirectionOk(RiskSide side, double entry, double sl) {
        return side == RiskSide.BUY ? sl < entry : sl > entry;
    }

    private static boolean tpDirectionOk(RiskSide side, double entry, double tp) {
        return side == RiskSide.BUY ? tp > entry : tp < entry;
    }

    private static BigDecimal pnlOf(TradeOrder o) {
        BigDecimal profit = o.getProfit() == null ? BigDecimal.ZERO : o.getProfit();
        BigDecimal swap = o.getSwap() == null ? BigDecimal.ZERO : o.getSwap();
        return profit.add(swap);
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 行情快照（报价 + 账户 + 持仓）。 */
    private record MarketSnapshot(Quote quote, AccountInfo account, List<Position> positions) {
    }
}
