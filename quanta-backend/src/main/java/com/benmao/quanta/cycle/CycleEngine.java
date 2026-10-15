package com.benmao.quanta.cycle;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.Deal;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.agent.AgentSessionService;
import com.benmao.quanta.backtest.BasketStrategyProperties;
import com.benmao.quanta.backtest.MartinMath;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.risk.RiskProperties;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.strategy.rule.MarketRegimeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实盘 <b>cycle 状态机执行器</b>（P2）：把「笨猫马丁-网格双边」的马丁子模式做成确定性执行器。
 *
 * <p>职责边界：
 * <ul>
 *   <li>唯一写出口 {@link CycleWriteTools}（→ {@code TradingService} → {@code RiskGuard} 的 GRID 档），<b>不接受 LLM 指令</b>；</li>
 *   <li>方向为<b>规则方向</b>（EMA 快慢线），形态判定复用回测同一套 {@link MarketRegimeClassifier}，杜绝口径分叉；</li>
 *   <li>与回测同口径的机械计算全部来自 {@link MartinMath}（参数取自 {@link BasketStrategyProperties}）；</li>
 *   <li>一轮 {@code agent_session} 1:1。</li>
 * </ul>
 *
 * <p><b>本期范围</b>：仅马丁（MARTIN）子模式的市价执行；网格双边挂单需 P3，故 {@code regime=GRID} 不开新轮。
 *
 * <p><b>安全</b>：{@code quanta.cycle.enabled=false}（默认关）时不动作；{@code dryRun=true} 时只打日志。
 */
@Service
public class CycleEngine {

    private static final Logger log = LoggerFactory.getLogger(CycleEngine.class);

    private final CycleProperties props;
    private final BasketStrategyProperties basketProps;
    private final RiskProperties riskProps;
    private final Mt5BridgeService bridge;
    private final CycleWriteTools writeTools;
    private final CycleMapper cycleMapper;
    private final CycleLegMapper legMapper;
    private final CycleLockService lockService;
    private final AgentSessionService sessionService;
    private final StrategyMapper strategyMapper;
    private final TradeOrderMapper tradeOrderMapper;

    /** 连续开首腿失败计数 / 冷却截止（进程内，重启清零）。 */
    private int startFails = 0;
    private LocalDateTime startBlockedUntil = null;

    /** 上一次「不开新轮」的原因（用于抑制 3 秒一轮的重复日志；仅状态变化时打印一次）。 */
    private String lastSkipReason = null;

    /** MT5 成交明细 entry 取值：1=平仓（OUT）。 */
    private static final int DEAL_ENTRY_OUT = 1;
    /** 读取成交明细的回看窗口（秒）：够覆盖一轮持仓即可。 */
    private static final long DEAL_LOOKBACK_SECONDS = 6 * 3600L;

    public CycleEngine(CycleProperties props,
                       BasketStrategyProperties basketProps,
                       RiskProperties riskProps,
                       Mt5BridgeService bridge,
                       CycleWriteTools writeTools,
                       CycleMapper cycleMapper,
                       CycleLegMapper legMapper,
                       CycleLockService lockService,
                       AgentSessionService sessionService,
                       StrategyMapper strategyMapper,
                       TradeOrderMapper tradeOrderMapper) {
        this.props = props;
        this.basketProps = basketProps;
        this.riskProps = riskProps;
        this.bridge = bridge;
        this.writeTools = writeTools;
        this.cycleMapper = cycleMapper;
        this.legMapper = legMapper;
        this.lockService = lockService;
        this.sessionService = sessionService;
        this.strategyMapper = strategyMapper;
        this.tradeOrderMapper = tradeOrderMapper;
    }

    @Scheduled(fixedDelayString = "${quanta.cycle.tick-interval-ms:3000}")
    public void tick() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            runTick();
        } catch (Exception e) {
            log.warn("cycle tick 异常：{}", e.toString());
        }
    }

    private void runTick() {
        Strategy strategy = targetStrategy();
        if (strategy == null) {
            return;
        }
        AccountInfo acc = bridge.account();
        double balance = acc.balance();
        double equity = acc.equity();
        lockService.ensureRef(balance);

        MartinCycle active = cycleMapper.findActive();
        if (active != null) {
            manage(active, balance);
            return;
        }
        maybeStart(strategy, balance, equity);
    }

    // ==================== 开新轮 ====================

    private void maybeStart(Strategy strategy, double balance, double equity) {
        if (startBlockedUntil != null && LocalDateTime.now().isBefore(startBlockedUntil)) {
            skip("开首腿冷却中（至 " + startBlockedUntil + "）");
            return;
        }
        if (lockService.totalLocked(balance)) {
            skip("总亏锁生效（余额 " + round2(balance) + "，需人工解除）");
            return;
        }
        if (lockService.dayLocked(balance)) {
            skip("日亏锁生效（余额 " + round2(balance) + "，自然日 00:00 自动解除）");
            return;
        }
        if (!inSessionWindow()) {
            skip("不在交易时段");
            return;
        }
        MarketRegimeClassifier.Regime regime = regime();
        if (regime != MarketRegimeClassifier.Regime.MARTIN) {
            // GRID（需 P3 挂单桥接）与 WAIT 均不开新轮
            skip("形态=" + regime + "（非 MARTIN，不新开轮）");
            return;
        }
        RiskSide side = direction();
        if (side == null) {
            skip("方向未定（EMA 快慢线重合）");
            return;
        }
        List<KlineBar> bars = bridge.klines(props.getSymbol(), props.getTimeframe(), props.getKlineCount());
        Quote q = bridge.quote(props.getSymbol());
        if (q == null || bars.isEmpty()) {
            skip("行情/报价不可用");
            return;
        }
        double entry = side == RiskSide.BUY ? q.ask() : q.bid();
        BasketStrategyProperties.Martin m = basketProps.getMartin();
        double base = MartinMath.baseVolume(equity, m.getBaseLotPct(), m.getVolumeStep(),
                m.getVolumeMin(), m.getMaxVolume(), m.getMultiplier(), m.getMaxLevels());
        double sl = round5(MartinMath.legSl(side, entry, m.getSlPoints(), riskProps.getPoint()));

        if (props.isDryRun()) {
            log.info("[DRY-RUN] 将开首腿：symbol={} side={} volume={} entry={} sl={} 形态={}",
                    props.getSymbol(), side, base, round5(entry), sl, regime);
            return;
        }

        String brokerSymbol = bridge.resolveSymbol(props.getSymbol());
        Long sessionId = sessionService.ensureSession(null, brokerSymbol, strategy.getId(), null, props.getSessionType());
        try {
            TradeOrder o = writeTools.openLeg(brokerSymbol, side, base, sl, sessionId, traceId("OPEN"));
            MartinCycle c = new MartinCycle();
            c.setCycleNo(cycleMapper.maxCycleNo() + 1);
            c.setSymbol(brokerSymbol);
            c.setSide(side.name());
            c.setMode("MARTIN");
            c.setState("S2_ENTERED");
            c.setLevel(1);
            c.setBaseVolume(base);
            c.setTotalVolume(base);
            c.setAvgEntry(o.getPrice() == null ? round2(entry) : o.getPrice().doubleValue());
            c.setBasketTp(round5(MartinMath.basketTp(side, c.getAvgEntry(), m.getTpPoints(),
                    riskProps.getPoint(), m.getBasketTargetRr())));
            c.setCycleStartEquity(equity);
            c.setSessionId(sessionId);
            c.setOpenedAt(LocalDateTime.now());
            cycleMapper.insert(c);

            insertLeg(c.getId(), 1, o, side, base, sl);
            sessionService.linkTicket(sessionId, o.getTicket());
            startFails = 0;
            lastSkipReason = null;
            double singlePct = lockService.singleLossPct();
            log.info("cycle 开新轮：cycleId={} no={} side={} volume={} entry={} sl={} basketTp={} sessionId={}"
                            + " 单轮止损阈值={}USD（占总权益{}%）单腿黑障={}USD",
                    c.getId(), c.getCycleNo(), side, base, c.getAvgEntry(), sl, c.getBasketTp(), sessionId,
                    round2(equity * singlePct / 100.0), singlePct, round2(m.getSlPoints() * riskProps.getPoint()));
            if (singlePct <= 0) {
                log.warn("cycle 本轮无整轮止损（单轮亏损%=0）：只能依赖单腿黑障兜底，风险极高！cycleId={}", c.getId());
            }
        } catch (Exception e) {
            sessionService.close(sessionId);
            onStartFail(e);
        }
    }

    /** 记录「本轮不开新轮」的原因：仅当原因变化时打印一次，避免 3 秒一轮刷屏。 */
    private void skip(String reason) {
        if (!reason.equals(lastSkipReason)) {
            log.info("cycle 不开新轮：{}", reason);
            lastSkipReason = reason;
        }
    }

    private void onStartFail(Exception e) {
        startFails++;
        log.warn("cycle 开首腿失败（{}/{}）：{}", startFails, props.getStartFailLimit(), e.getMessage());
        if (startFails >= props.getStartFailLimit()) {
            startBlockedUntil = LocalDateTime.now().plusMinutes(props.getStartCooldownMinutes());
            startFails = 0;
            log.warn("cycle 开首腿连续失败达上限，冷却至 {}", startBlockedUntil);
        }
    }

    // ==================== 管理活跃轮 ====================

    private void manage(MartinCycle c, double balance) {
        List<MartinCycleLeg> legs = legMapper.listByCycle(c.getId());
        List<Position> all = safePositions();
        if (all == null) {
            // 快照不可用：本轮不做任何判断（尤其不能收尾），等下一 tick
            return;
        }

        // 1) 与券商对账：本轮的腿是否还被持有
        List<MartinCycleLeg> openLegs = new ArrayList<>();
        double floating = 0;
        for (MartinCycleLeg leg : legs) {
            Position p = findPosition(all, leg.getTicket());
            if (p != null) {
                openLegs.add(leg);
                floating += p.profit();
            }
        }
        if (openLegs.isEmpty()) {
            // 已被券商平掉（触 SL/TP 或人工）→ 收尾
            finalizeByBroker(c, legs);
            return;
        }

        // 2) 锁闸门
        if (lockService.totalLocked(balance)) {
            closeBasket(c, openLegs, legs, "TOTAL_LOCK");
            return;
        }
        if (lockService.dayLocked(balance)) {
            closeBasket(c, openLegs, legs, "DAY_LOCK");
            return;
        }

        // 3) 整轮止盈（软件口径）
        Quote q = bridge.quote(props.getSymbol());
        if (q == null) {
            return;
        }
        RiskSide side = RiskSide.valueOf(c.getSide());
        double price = side == RiskSide.BUY ? q.bid() : q.ask();
        if (c.getBasketTp() != null) {
            boolean tp = side == RiskSide.BUY ? price >= c.getBasketTp() : price <= c.getBasketTp();
            if (tp) {
                closeBasket(c, openLegs, legs, "TP");
                return;
            }
        }

        // 4) 轮级硬止损（净值级）；阈值同走锁档设置（表优先、0=不启用）
        double singlePct = lockService.singleLossPct();
        double lossLimit = c.getCycleStartEquity() * singlePct / 100.0;
        if (singlePct > 0 && lossLimit > 0 && floating <= -lossLimit) {
            closeBasket(c, openLegs, legs, "HARD_SL");
            return;
        }

        // 5) 逆势补腿：以「该轮历史最高 level」为基准（不能用在仓腿数，
        //    否则某腿被券商单独止损后计数缩回，会出现同 level 重复、手数阶梯乱序）
        BasketStrategyProperties.Martin m = basketProps.getMartin();
        if (maxLevel(legs) < m.getMaxLevels()) {
            MartinCycleLeg last = openLegs.get(openLegs.size() - 1);
            double trigger = MartinMath.addTrigger(side, last.getEntryPrice(), m.getAddLegDistancePoints(), riskProps.getPoint());
            boolean hit = side == RiskSide.BUY ? price <= trigger : price >= trigger;
            if (hit) {
                addLeg(c, openLegs, side, price);
            }
        }
    }

    private void addLeg(MartinCycle c, List<MartinCycleLeg> openLegs, RiskSide side, double price) {
        BasketStrategyProperties.Martin m = basketProps.getMartin();
        List<MartinCycleLeg> history = legMapper.listByCycle(c.getId());
        int level = maxLevel(history) + 1;
        if (level > m.getMaxLevels()) {
            log.info("cycle 已达最大腿数 {}（历史最高 level={}），不再补腿：cycleId={}",
                    m.getMaxLevels(), level - 1, c.getId());
            return;
        }
        double volume = MartinMath.legVolume(c.getBaseVolume(), level, m.getMultiplier());
        double sl = round5(MartinMath.legSl(side, price, m.getSlPoints(), riskProps.getPoint()));

        if (props.isDryRun()) {
            log.info("[DRY-RUN] 将补第 {} 腿：volume={} price={} sl={}", level, volume, round5(price), sl);
            return;
        }
        try {
            TradeOrder o = writeTools.openLeg(c.getSymbol(), side, volume, sl, c.getSessionId(), traceId("ADD"));
            insertLeg(c.getId(), level, o, side, volume, sl);

            List<MartinCycleLeg> all = legMapper.listByCycle(c.getId());
            double avg = avgEntry(all);
            c.setLevel(level);
            c.setTotalVolume(round2(totalVolume(all)));
            c.setAvgEntry(round2(avg));
            c.setBasketTp(round5(MartinMath.basketTp(side, avg, m.getTpPoints(),
                    riskProps.getPoint(), m.getBasketTargetRr())));
            c.setState("S3_ADDED");
            cycleMapper.update(c);
            log.info("cycle 补第 {} 腿：cycleId={} volume={} entry={} avg={} basketTp={}",
                    level, c.getId(), volume, o.getPrice(), c.getAvgEntry(), c.getBasketTp());
        } catch (Exception e) {
            log.warn("cycle 补腿失败：cycleId={} level={} 原因={}", c.getId(), level, e.getMessage());
        }
    }

    // ==================== 收尾 ====================

    /**
     * 平掉本轮在仓腿并收尾。
     *
     * @param openLegs 仍被券商持有的腿（执行器需主动平掉）
     * @param allLegs  本轮<b>全部</b>腿；其中不在 {@code openLegs} 里的说明已被券商单独止损/止盈平掉，
     *                 必须一并回填真实盈亏，否则轮级 {@code realized_pnl} 只算执行器亲手平的腿，账面失真
     */
    private void closeBasket(MartinCycle c, List<MartinCycleLeg> openLegs, List<MartinCycleLeg> allLegs, String reason) {
        double realized = 0;
        // 1) 先回填「已被券商平掉的腿」的真实盈亏（从本地订单或成交明细取）
        for (MartinCycleLeg leg : allLegs) {
            if (containsTicket(openLegs, leg.getTicket())) {
                continue;
            }
            if (leg.getClosedAt() != null) {
                realized += leg.getRealizedPnl() == null ? 0 : leg.getRealizedPnl();
                continue;
            }
            double p = realizedOf(leg);
            realized += p;
            leg.setClosedAt(LocalDateTime.now());
            leg.setRealizedPnl(round2(p));
            legMapper.update(leg);
        }
        // 2) 再平掉仍在仓的腿
        List<Position> all = safePositions();
        for (MartinCycleLeg leg : openLegs) {
            // 快照可用时以券商为准；快照不可用（null）则按调用方给出的在仓腿直接平，避免漏平
            if (all != null && findPosition(all, leg.getTicket()) == null) {
                continue;
            }
            try {
                TradeOrder o = writeTools.closeLeg(c.getSymbol(), leg.getTicket(), c.getSessionId(), traceId("CLOSE"));
                realized += o.getProfit() == null ? 0 : o.getProfit().doubleValue();
                leg.setClosedAt(LocalDateTime.now());
                leg.setRealizedPnl(o.getProfit() == null ? null : round2(o.getProfit().doubleValue()));
                legMapper.update(leg);
            } catch (Exception e) {
                log.warn("cycle 平腿失败：ticket={} 原因={}", leg.getTicket(), e.getMessage());
            }
        }
        finish(c, reason, realized);
    }

    /** 券商侧已把腿平光（触挂单 SL 等），据此收尾。 */
    private void finalizeByBroker(MartinCycle c, List<MartinCycleLeg> legs) {
        double realized = 0;
        for (MartinCycleLeg leg : legs) {
            if (leg.getClosedAt() != null) {
                realized += leg.getRealizedPnl() == null ? 0 : leg.getRealizedPnl();
                continue;
            }
            double p = realizedOf(leg);
            realized += p;
            leg.setClosedAt(LocalDateTime.now());
            leg.setRealizedPnl(round2(p));
            legMapper.update(leg);
        }
        String reason = realized > 0 ? "TP_BROKER" : realized < 0 ? "SL_BROKER" : "BROKER_CLOSED";
        finish(c, reason, realized);
    }

    /**
     * 单腿在券商侧的已实现盈亏：优先取本地订单已回写的 profit；
     * 券商自动平仓到对账回写之间存在延迟（对账 5 分钟一轮），此时本地 profit 仍为 null，
     * 只能从成交明细（OUT 腿）的 profit+swap 取，否则会把 SL 亏损错记成 0。
     */
    private double realizedOf(MartinCycleLeg leg) {
        TradeOrder o = tradeOrderMapper.findLatestByTicket(leg.getTicket());
        if (o != null && o.getProfit() != null) {
            return o.getProfit().doubleValue();
        }
        double p = brokerRealized(leg.getTicket());
        if (p == 0) {
            log.warn("ticket={} 未取到成交明细，本轮盈亏按 0 记（对账回写后可在 trade_order 中核对）", leg.getTicket());
        }
        return p;
    }

    /** 从券商成交明细累计指定 ticket 的平仓盈亏（profit+swap）；异常返回 0。 */
    private double brokerRealized(long ticket) {
        try {
            long to = ZonedDateTime.now(ZoneId.of("UTC")).toEpochSecond();
            List<Deal> deals = bridge.historyDeals(to - DEAL_LOOKBACK_SECONDS, to);
            if (deals == null) {
                return 0;
            }
            double p = 0;
            for (Deal d : deals) {
                if (d.positionId() == ticket && d.entry() == DEAL_ENTRY_OUT) {
                    p += d.profit() + d.swap();
                }
            }
            return p;
        } catch (Exception e) {
            log.warn("读取成交明细失败，ticket={}：{}", ticket, e.toString());
            return 0;
        }
    }

    private void finish(MartinCycle c, String reason, double realized) {
        c.setState("CLOSED");
        c.setCloseReason(reason);
        c.setClosedAt(LocalDateTime.now());
        c.setRealizedPnl(round2(realized));
        cycleMapper.update(c);
        if (c.getSessionId() != null) {
            sessionService.close(c.getSessionId());
        }
        log.info("cycle 收尾：cycleId={} no={} reason={} realized={}", c.getId(), c.getCycleNo(), reason, round2(realized));
    }

    /** 人工强制平轮（不受 {@code enabled} 开关限制，仍全部经风控）。 */
    public MartinCycle forceClose(long cycleId, String reason) {
        MartinCycle c = cycleMapper.getById(cycleId);
        if (c == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "轮次不存在：id=" + cycleId, 404);
        }
        if ("CLOSED".equals(c.getState())) {
            return c;
        }
        List<MartinCycleLeg> legs = legMapper.listByCycle(cycleId);
        List<Position> all = safePositions();
        if (all == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "券商持仓快照不可用，请稍后重试", 503);
        }
        List<MartinCycleLeg> open = new ArrayList<>();
        for (MartinCycleLeg leg : legs) {
            if (findPosition(all, leg.getTicket()) != null) {
                open.add(leg);
            }
        }
        if (open.isEmpty()) {
            finalizeByBroker(c, legs);
        } else {
            closeBasket(c, open, legs, (reason == null || reason.isBlank()) ? "MANUAL" : reason);
        }
        return cycleMapper.getById(cycleId);
    }

    // ==================== 工具 ====================

    private Strategy targetStrategy() {
        Strategy s = strategyMapper.findByNameVersion(props.getStrategyName(), 1);
        if (s == null) {
            return null;
        }
        String st = s.getStatus();
        return ("DRILL".equals(st) || "ACTIVE".equals(st)) ? s : null;
    }

    private MarketRegimeClassifier.Regime regime() {
        BasketStrategyProperties.Session se = basketProps.getSession();
        MarketRegimeClassifier clf = new MarketRegimeClassifier(se.getEmaFast(), se.getEmaSlow(), se.getAdxPeriod(),
                se.getTrendEpsilonPct(), se.getAdxGridMax(), se.getAdxTrendMin());
        List<KlineBar> bars = bridge.klines(props.getSymbol(), props.getTimeframe(), props.getKlineCount());
        for (int i = 0; i < bars.size(); i++) {
            clf.update(bars.get(i), i);
        }
        return clf.regime();
    }

    private RiskSide direction() {
        BasketStrategyProperties.Session se = basketProps.getSession();
        MarketRegimeClassifier clf = new MarketRegimeClassifier(se.getEmaFast(), se.getEmaSlow(), se.getAdxPeriod(),
                se.getTrendEpsilonPct(), se.getAdxGridMax(), se.getAdxTrendMin());
        List<KlineBar> bars = bridge.klines(props.getSymbol(), props.getTimeframe(), props.getKlineCount());
        for (int i = 0; i < bars.size(); i++) {
            clf.update(bars.get(i), i);
        }
        double ef = clf.emaFast();
        double es = clf.emaSlow();
        if (Double.isNaN(ef) || Double.isNaN(es) || ef == es) {
            return null;
        }
        return ef > es ? RiskSide.BUY : RiskSide.SELL;
    }

    private boolean inSessionWindow() {
        BasketStrategyProperties.Session se = basketProps.getSession();
        ZoneId zone = ZoneId.of(se.getTimeZone());
        int h = ZonedDateTime.now(zone).getHour();
        return h >= se.getStartHour() && h < se.getEndHour();
    }

    /**
     * 券商持仓快照。桥接不可用时返回 {@code null}（与"确实没有持仓"语义严格区分）：
     * 调用方必须据此<b>跳过本轮</b>，否则会把一次网络抖动误判成"腿已被券商平掉"而错误收尾，
     * 导致真实持仓变成无人管理的孤儿仓。
     */
    private List<Position> safePositions() {
        try {
            return bridge.positions(null);
        } catch (Exception e) {
            log.warn("券商场持仓快照读取失败，本轮跳过：{}", e.toString());
            return null;
        }
    }

    private static Position findPosition(List<Position> positions, Long ticket) {
        if (ticket == null) {
            return null;
        }
        String t = String.valueOf(ticket);
        for (Position p : positions) {
            if (t.equals(String.valueOf(p.ticket()))) {
                return p;
            }
        }
        return null;
    }

    private void insertLeg(Long cycleId, int level, TradeOrder o, RiskSide side, double volume, double sl) {
        MartinCycleLeg leg = new MartinCycleLeg();
        leg.setCycleId(cycleId);
        leg.setLevel(level);
        leg.setTicket(o.getTicket());
        leg.setSide(side.name());
        leg.setVolume(volume);
        leg.setEntryPrice(o.getPrice() == null ? null : o.getPrice().doubleValue());
        leg.setSl(sl);
        leg.setOpenedAt(LocalDateTime.now());
        legMapper.insert(leg);
    }

    private static double avgEntry(List<MartinCycleLeg> legs) {
        double vol = 0;
        double sum = 0;
        for (MartinCycleLeg l : legs) {
            double v = l.getVolume() == null ? 0 : l.getVolume();
            double p = l.getEntryPrice() == null ? 0 : l.getEntryPrice();
            vol += v;
            sum += v * p;
        }
        return vol == 0 ? 0 : sum / vol;
    }

    private static double totalVolume(List<MartinCycleLeg> legs) {
        double v = 0;
        for (MartinCycleLeg l : legs) {
            v += l.getVolume() == null ? 0 : l.getVolume();
        }
        return v;
    }

    /** 该轮已出现过的最高 level（含已被止损平仓的腿）；无腿返回 0。 */
    private static int maxLevel(List<MartinCycleLeg> legs) {
        int max = 0;
        for (MartinCycleLeg l : legs) {
            if (l.getLevel() != null && l.getLevel() > max) {
                max = l.getLevel();
            }
        }
        return max;
    }

    /** 指定 ticket 是否在给定腿列表中。 */
    private static boolean containsTicket(List<MartinCycleLeg> legs, Long ticket) {
        if (ticket == null) {
            return false;
        }
        for (MartinCycleLeg l : legs) {
            if (ticket.equals(l.getTicket())) {
                return true;
            }
        }
        return false;
    }

    private static String traceId(String tag) {
        return tag + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round5(double v) {
        return BigDecimal.valueOf(v).setScale(5, RoundingMode.HALF_UP).doubleValue();
    }
}
