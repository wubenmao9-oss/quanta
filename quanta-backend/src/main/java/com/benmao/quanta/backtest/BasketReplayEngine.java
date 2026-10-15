package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.risk.RiskSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 多腿/网格<b>事件驱动重放引擎</b>（与 {@link ReplayEngine} 并行存在，互不影响）。
 *
 * <p>逐根 K 线严格顺序（悲观口径，沿用单仓引擎口径）：
 * <ol>
 *   <li>跨日重置当日起始权益；计算日/总锁闸门；触发即强平整轮并撤挂单；</li>
 *   <li>推进挂单：过期撤单 → 触发成交（跳空经 {@link CostModel#fillPrice} 与开盘价保护）；</li>
 *   <li>平仓判定：马丁先逐腿判 SL，再判轮级硬止损，最后判整轮均价止盈（<b>同根都触先判 SL</b>）；
 *       网格逐腿独立判 SL/TP；</li>
 *   <li>{@link BasketRuleRunner#onBar} 产动作序列并逐条应用（锁定期内丢弃开轮/补腿/挂单）；</li>
 * </ol>
 *
 * <p>每轮的子模式（马丁/网格）由 {@link BasketAction.OpenCycle} 或挂单成交决定，故同一策略可按盘面切换。
 * 机械手数/均价/止盈价由 {@link MartinMath} 计算；隔夜费与单仓引擎共用 {@link SwapModel}。
 */
public class BasketReplayEngine {

    private static final Logger log = LoggerFactory.getLogger(BasketReplayEngine.class);

    private final BacktestProperties props;
    private final BasketStrategyProperties bp;
    private final ZoneId zone;
    private final SwapModel swapModel;

    public BasketReplayEngine(BacktestProperties props, BasketStrategyProperties bp, ZoneId zone) {
        this.props = props;
        this.bp = bp;
        this.zone = zone;
        this.swapModel = new SwapModel(props, zone);
    }

    /**
     * 跑一遍多腿/网格回测。
     *
     * @param bars           历史 K 线（按时间升序）
     * @param strategy       多腿策略原型（每次回测独立实例）
     * @param spreadPoints   点差（points）
     * @param slippagePoints 滑点（points）
     */
    public BasketEngineResult run(List<KlineBar> bars, BasketStrategy strategy,
                                  int spreadPoints, int slippagePoints) {
        return run(bars, strategy, spreadPoints, slippagePoints, props.getInitialEquity());
    }

    /**
     * 跑一遍多腿/网格回测（可覆盖初始权益，供门禁按账户规模口径回测）。
     *
     * @param bars           历史 K 线（按时间升序）
     * @param strategy       多腿策略原型（每次回测独立实例）
     * @param spreadPoints   点差（points）
     * @param slippagePoints 滑点（points）
     * @param initialEquity  初始权益（USD）
     */
    public BasketEngineResult run(List<KlineBar> bars, BasketStrategy strategy,
                                  int spreadPoints, int slippagePoints, double initialEquity) {
        double point = props.getPoint();
        double contract = props.getContractSizePerLot();
        double init = initialEquity;
        CostModel cost = new CostModel(point, spreadPoints, slippagePoints, props.getSpreadSideFactor());
        BasketRuleRunner runner = strategy.newRunner();

        List<ClosedTrade> trades = new ArrayList<>();
        List<BasketEngineResult.CycleSummary> cycles = new ArrayList<>();

        if (bars == null || bars.isEmpty()) {
            return new BasketEngineResult(List.of(), List.of(), init, init, 0);
        }
        int n = bars.size();
        double equity = init;
        double peak = init;
        double maxDrawdown = 0;

        BasketPosition basket = null;
        CycleAcc acc = null;
        List<GridPendingOrder> pendings = new ArrayList<>();
        long pendingId = 0;
        int cycleNo = 0;
        boolean dayLocked = false;
        boolean totalLocked = false;
        LocalDate curDate = null;
        double dayStartEquity = init;

        for (int i = 0; i < n; i++) {
            KlineBar bar = bars.get(i);

            // ① 跨日重置 + 锁闸门（前置）
            LocalDate date = toLocalDate(bar.time());
            if (curDate == null) {
                curDate = date;
            } else if (!date.equals(curDate)) {
                curDate = date;
                dayStartEquity = equity;
                dayLocked = false;
            }
            boolean totalJust = !totalLocked
                    && equity <= init * (1.0 - bp.getLocks().getMaxTotalLossPct() / 100.0);
            if (totalJust) {
                totalLocked = true;
            }
            boolean dayJust = !dayLocked && dayStartEquity > 0
                    && equity <= dayStartEquity * (1.0 - bp.getLocks().getMaxDailyLossPct() / 100.0);
            if (dayJust) {
                dayLocked = true;
            }
            if ((totalJust || dayJust) && basket != null) {
                String reason = totalLocked ? "TOTAL_LOCK" : "DAY_LOCK";
                double net = closeAllLegs(trades, acc, basket, i, bar.close(), reason, cost, contract, bars, cycleNo);
                equity += net;
                peak = Math.max(peak, equity);
                maxDrawdown = Math.max(maxDrawdown, peak - equity);
                cycles.add(finalizeCycle(acc, i, bars));
                acc = null;
                basket = null;
                pendings.clear();
            }

            // ② 挂单：过期撤单 → 触发成交
            pendings.removeIf(p -> p.expireAtEpoch() > 0 && bar.time() > p.expireAtEpoch());
            List<GridPendingOrder> filled = new ArrayList<>();
            for (GridPendingOrder p : pendings) {
                if (p.triggeredBy(bar.high(), bar.low())) {
                    filled.add(p);
                }
            }
            for (GridPendingOrder p : filled) {
                pendings.remove(p);
                if (basket == null) {
                    cycleNo++;
                    basket = new BasketPosition(cycleNo, p.side(), BasketEngineMode.GRID, equity, p.volume(), i);
                    acc = new CycleAcc(cycleNo, BasketEngineMode.GRID, p.side(), i);
                }
                double entry = cost.fillPrice(p.side(), p.fillRef(bar.open()));
                double step = p.stepPoints() * point;
                double sl = p.side() == RiskSide.BUY ? entry - step * p.slMult() : entry + step * p.slMult();
                double tp = p.side() == RiskSide.BUY ? entry + step * p.targetRr() : entry - step * p.targetRr();
                BasketLeg leg = new BasketLeg(basket.level() + 1, p.side(), p.volume(), i, entry, sl, tp);
                basket.addLeg(leg);
                acc.addLeg(leg);
            }

            // ③ 平仓判定
            if (basket != null) {
                List<BasketLeg> slLegs = new ArrayList<>();
                List<BasketLeg> tpLegs = new ArrayList<>();
                for (BasketLeg l : basket.legs()) {
                    if (l.openIndex() >= i) {
                        continue; // 本根新开腿延后一根再判
                    }
                    if (slHit(l, bar)) {
                        slLegs.add(l);
                    } else if (l.tp() != null && tpHit(l.side(), l.tp(), bar)) {
                        tpLegs.add(l);
                    }
                }
                // 同根都触先判 SL（悲观）
                for (BasketLeg l : slLegs) {
                    double net = closeLeg(trades, acc, basket, l, i, l.sl(), "SL", cost, contract, bars, cycleNo);
                    equity += net;
                    peak = Math.max(peak, equity);
                    maxDrawdown = Math.max(maxDrawdown, peak - equity);
                    basket.removeLeg(l);
                }
                boolean closed = !slLegs.isEmpty();
                for (BasketLeg l : tpLegs) {
                    double net = closeLeg(trades, acc, basket, l, i, l.tp(), "TP", cost, contract, bars, cycleNo);
                    equity += net;
                    peak = Math.max(peak, equity);
                    maxDrawdown = Math.max(maxDrawdown, peak - equity);
                    basket.removeLeg(l);
                    closed = true;
                }
                // 马丁轮级硬止损（网格不适用）：浮亏达 轮启动净值×maxSingleLossPct → 强平整轮
                if (basket.mode() == BasketEngineMode.MARTIN && !basket.isEmpty()) {
                    double adverse = basket.side() == RiskSide.BUY ? bar.low() : bar.high();
                    double hard = -basket.cycleStartEquity() * bp.getLocks().getMaxSingleLossPct() / 100.0;
                    if (basket.floatingPnl(adverse, contract) <= hard) {
                        double net = closeAllLegs(trades, acc, basket, i, adverse, "HARD_SL", cost, contract, bars, cycleNo);
                        equity += net;
                        peak = Math.max(peak, equity);
                        maxDrawdown = Math.max(maxDrawdown, peak - equity);
                        closed = true;
                    } else if (!closed && !Double.isNaN(basket.basketTp())
                            && tpHit(basket.side(), basket.basketTp(), bar)) {
                        double net = closeAllLegs(trades, acc, basket, i, basket.basketTp(), "TP", cost, contract, bars, cycleNo);
                        equity += net;
                        peak = Math.max(peak, equity);
                        maxDrawdown = Math.max(maxDrawdown, peak - equity);
                        closed = true;
                    }
                }
                if (basket.isEmpty()) {
                    cycles.add(finalizeCycle(acc, i, bars));
                    acc = null;
                    basket = null;
                }
            }

            // ④ runner 产动作并应用
            ReplayView view = new ReplayView(bars, i);
            BasketState state = new BasketState(view, basket, List.copyOf(pendings), cycleNo, equity, dayStartEquity,
                    basket == null ? equity : basket.cycleStartEquity(), point, contract,
                    dayLocked, totalLocked, false);
            List<BasketAction> actions = runner.onBar(state);
            if (actions != null) {
                for (BasketAction a : actions) {
                    if (a == null) {
                        continue;
                    }
                    if (a instanceof BasketAction.OpenCycle oc) {
                        if (basket == null && !dayLocked && !totalLocked) {
                            cycleNo++;
                            BasketStrategyProperties.Martin m = bp.getMartin();
                            double base = MartinMath.baseVolume(equity, m.getBaseLotPct(), m.getVolumeStep(),
                                    m.getVolumeMin(), m.getMaxVolume(), m.getMultiplier(), m.getMaxLevels());
                            basket = new BasketPosition(cycleNo, oc.side(), oc.mode(), equity, base, i);
                            acc = new CycleAcc(cycleNo, oc.mode(), oc.side(), i);
                            double entry = cost.fillPrice(oc.side(), bar.close());
                            BasketLeg leg = new BasketLeg(1, oc.side(), base, i, entry,
                                    MartinMath.legSl(oc.side(), entry, m.getSlPoints(), point), null);
                            basket.addLeg(leg);
                            acc.addLeg(leg);
                            basket.setBasketTp(MartinMath.basketTp(oc.side(), basket.avgEntry(),
                                    m.getTpPoints(), point, m.getBasketTargetRr()));
                        }
                    } else if (a instanceof BasketAction.AddLeg) {
                        if (basket != null && !dayLocked && !totalLocked) {
                            BasketStrategyProperties.Martin m = bp.getMartin();
                            int level = basket.level() + 1;
                            if (level <= m.getMaxLevels()) {
                                double vol = MartinMath.legVolume(basket.baseVolume(), level, m.getMultiplier());
                                double entry = cost.fillPrice(basket.side(), bar.close());
                                BasketLeg leg = new BasketLeg(level, basket.side(), vol, i, entry,
                                        MartinMath.legSl(basket.side(), entry, m.getSlPoints(), point), null);
                                basket.addLeg(leg);
                                acc.addLeg(leg);
                                basket.setBasketTp(MartinMath.basketTp(basket.side(), basket.avgEntry(),
                                        m.getTpPoints(), point, m.getBasketTargetRr()));
                            }
                        }
                    } else if (a instanceof BasketAction.CloseCycle cc) {
                        if (basket != null) {
                            double net = closeAllLegs(trades, acc, basket, i, bar.close(), cc.reason(),
                                    cost, contract, bars, cycleNo);
                            equity += net;
                            peak = Math.max(peak, equity);
                            maxDrawdown = Math.max(maxDrawdown, peak - equity);
                            cycles.add(finalizeCycle(acc, i, bars));
                            acc = null;
                            basket = null;
                        }
                    } else if (a instanceof BasketAction.PlacePending pp) {
                        if (!dayLocked && !totalLocked) {
                            BasketStrategyProperties.Grid g = bp.getGrid();
                            pendings.add(new GridPendingOrder(++pendingId, pp.kind(), pp.price(), g.getVolume(), i,
                                    bar.time() + g.getExpireHours() * 3600L, g.getStepPoints(), g.getTargetRr(), g.getSlMult()));
                        }
                    } else if (a instanceof BasketAction.CancelAllPending) {
                        pendings.clear();
                    }
                    // Idle：无操作
                }
            }
        }

        // 收尾：区间末仍持仓 → 按最后一根收盘平掉（计入统计）
        if (basket != null) {
            int last = n - 1;
            double net = closeAllLegs(trades, acc, basket, last, bars.get(last).close(), "END",
                    cost, contract, bars, cycleNo);
            equity += net;
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
            cycles.add(finalizeCycle(acc, last, bars));
        }

        log.debug("多腿回测完成：bars={}, spread={}, legTrades={}, cycles={}, finalEquity={}",
                n, spreadPoints, trades.size(), cycles.size(), equity);
        return new BasketEngineResult(List.copyOf(trades), List.copyOf(cycles), init, equity, maxDrawdown);
    }

    // ==================== 内部实现 ====================

    /** 平掉一整轮全部腿（返回净利之和），并清空腿。 */
    private double closeAllLegs(List<ClosedTrade> trades, CycleAcc acc, BasketPosition basket, int closeIndex,
                                double refPrice, String reason, CostModel cost, double contract,
                                List<KlineBar> bars, int cycleNo) {
        double net = 0;
        for (BasketLeg l : new ArrayList<>(basket.legs())) {
            net += closeLeg(trades, acc, basket, l, closeIndex, refPrice, reason, cost, contract, bars, cycleNo);
        }
        basket.legs().clear();
        return net;
    }

    /** 平掉单腿，产出一条 {@link ClosedTrade} 并累计到轮摘要。 */
    private double closeLeg(List<ClosedTrade> trades, CycleAcc acc, BasketPosition basket, BasketLeg leg,
                            int closeIndex, double refPrice, String reason, CostModel cost, double contract,
                            List<KlineBar> bars, int cycleNo) {
        RiskSide exitSide = leg.side() == RiskSide.BUY ? RiskSide.SELL : RiskSide.BUY;
        double closePrice = cost.fillPrice(exitSide, refPrice);
        double gross = cost.profit(leg.side(), leg.entryPrice(), closePrice, leg.volume(), contract);
        long openEpoch = bars.get(leg.openIndex()).time();
        long closeEpoch = bars.get(closeIndex).time();
        double swap = swapModel.swapFor(openEpoch, closeEpoch, leg.volume());
        int holdSec = (int) Math.max(0, closeEpoch - openEpoch);
        ClosedTrade t = new ClosedTrade(leg.openIndex(), closeIndex,
                toLocalDateTime(openEpoch), toLocalDateTime(closeEpoch),
                leg.side(), leg.volume(), leg.entryPrice(), closePrice,
                leg.sl(), leg.tp() == null ? 0 : leg.tp(),
                gross, swap, holdSec, reason, cycleNo, leg.level(), basket.mode().name());
        trades.add(t);
        acc.addClose(t.netProfit(), reason);
        return t.netProfit();
    }

    private BasketEngineResult.CycleSummary finalizeCycle(CycleAcc acc, int closeIndex, List<KlineBar> bars) {
        long openEpoch = bars.get(acc.openIndex).time();
        long closeEpoch = bars.get(closeIndex).time();
        int holdSec = (int) Math.max(0, closeEpoch - openEpoch);
        double avg = acc.totalVolume == 0 ? 0 : acc.entrySum / acc.totalVolume;
        return new BasketEngineResult.CycleSummary(acc.cycleNo, acc.mode, acc.side, acc.levels,
                round2(acc.totalVolume), round2(avg), round2(acc.netProfit), holdSec, acc.reason);
    }

    private static boolean slHit(BasketLeg l, KlineBar bar) {
        return l.side() == RiskSide.BUY ? bar.low() <= l.sl() : bar.high() >= l.sl();
    }

    private static boolean tpHit(RiskSide side, double tp, KlineBar bar) {
        return side == RiskSide.BUY ? bar.high() >= tp : bar.low() <= tp;
    }

    private LocalDate toLocalDate(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate();
    }

    private LocalDateTime toLocalDateTime(long epochSecond) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 一轮的累计器（腿数 / 手数 / 加权和 / 净利 / 末次平仓原因）。 */
    private static final class CycleAcc {
        final int cycleNo;
        final BasketEngineMode mode;
        final RiskSide side;
        final int openIndex;
        int levels;
        double totalVolume;
        double entrySum;
        double netProfit;
        String reason = "END";

        CycleAcc(int cycleNo, BasketEngineMode mode, RiskSide side, int openIndex) {
            this.cycleNo = cycleNo;
            this.mode = mode;
            this.side = side;
            this.openIndex = openIndex;
        }

        void addLeg(BasketLeg l) {
            levels++;
            totalVolume += l.volume();
            entrySum += l.volume() * l.entryPrice();
        }

        void addClose(double net, String reason) {
            netProfit += net;
            this.reason = reason;
        }
    }
}
