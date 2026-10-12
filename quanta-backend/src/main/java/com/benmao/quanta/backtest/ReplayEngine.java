package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.risk.RiskSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 事件驱动重放引擎（设计 §13.1）：逐根 K 线推进，<b>复用实盘同一套</b>规则策略。
 *
 * <p>核心保证：
 * <ul>
 *   <li><b>防未来函数</b>：策略只能通过 {@link ReplayView}（游标 = 当前 bar）读取数据，越界即抛错；</li>
 *   <li><b>成本建模</b>：入场 / 出场均经 {@link CostModel}（单边整点差 + 滑点），持仓跨结算点计 swap；</li>
 *   <li><b>盘中触发</b>：用每根 K 线的 high/low 判定 SL/TP；同一根内 SL 与 TP 都触价时，<b>悲观取 SL 先触发</b>；</li>
 *   <li><b>可复现</b>：全程确定性、无随机，同输入必得同输出。</li>
 * </ul>
 */
public class ReplayEngine {

    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    private final BacktestProperties props;
    private final ZoneId zone;

    public ReplayEngine(BacktestProperties props, ZoneId zone) {
        this.props = props;
        this.zone = zone;
    }

    /**
     * 跑一遍回测。
     *
     * @param bars            历史 K 线（按时间升序）
     * @param runner          有状态规则实例（每次回测独立）
     * @param spreadPoints    点差（points）
     * @param slippagePoints  滑点（points）
     */
    public EngineResult run(List<KlineBar> bars, RuleRunner runner, double spreadPoints, double slippagePoints) {
        double point = props.getPoint();
        double contractSize = props.getContractSizePerLot();
        double volume = props.getVolume();
        CostModel cost = new CostModel(point, spreadPoints, slippagePoints, props.getSpreadSideFactor());

        List<ClosedTrade> trades = new ArrayList<>();
        double equity = props.getInitialEquity();
        double peak = equity;
        double maxDrawdown = 0;

        if (bars == null || bars.isEmpty()) {
            return new EngineResult(List.of(), props.getInitialEquity(), equity, 0);
        }

        OpenPosition position = null;
        int n = bars.size();

        for (int i = 0; i < n; i++) {
            KlineBar bar = bars.get(i);

            // 1) 持仓中：用本根 high/low 判定 SL/TP（悲观：同根都触则先 SL）
            if (position != null) {
                ClosedTrade closed = tryClose(position, bar, i, cost, contractSize, volume, bars);
                if (closed != null) {
                    trades.add(closed);
                    equity += closed.netProfit();
                    peak = Math.max(peak, equity);
                    maxDrawdown = Math.max(maxDrawdown, peak - equity);
                    position = null;
                }
            }

            // 2) 每根都推进策略（维护增量指标状态）；仅未持仓时的有效信号才开仓
            ReplayView view = new ReplayView(bars, i);
            EntrySignal signal = runner.onBar(view, position);
            if (position == null && signal != null && signal.stopDistance() > 0 && signal.targetRr() > 0) {
                double ref = bar.close();
                double entry = cost.fillPrice(signal.side(), ref);
                double sl = signal.side() == RiskSide.BUY
                        ? ref - signal.stopDistance() : ref + signal.stopDistance();
                double tp = signal.side() == RiskSide.BUY
                        ? ref + signal.targetRr() * signal.stopDistance()
                        : ref - signal.targetRr() * signal.stopDistance();
                position = new OpenPosition(signal.side(), i, entry, sl, tp);
            }
        }

        // 3) 收尾：区间末仍持仓 → 按最后一根收盘平掉（计入统计）
        if (position != null) {
            int last = n - 1;
            ClosedTrade closed = closeAt(position, last, bars.get(last).close(), "END", cost, contractSize, volume, bars);
            trades.add(closed);
            equity += closed.netProfit();
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
        }

        log.debug("回测完成：bars={}, spread={}, slippage={}, trades={}, finalEquity={}",
                n, spreadPoints, slippagePoints, trades.size(), equity);
        return new EngineResult(List.copyOf(trades), props.getInitialEquity(), equity, maxDrawdown);
    }

    // ==================== 内部实现 ====================

    private ClosedTrade tryClose(OpenPosition pos, KlineBar bar, int index,
                                 CostModel cost, double contractSize, double volume, List<KlineBar> bars) {
        boolean slHit;
        boolean tpHit;
        if (pos.side() == RiskSide.BUY) {
            slHit = bar.low() <= pos.sl();
            tpHit = bar.high() >= pos.tp();
        } else {
            slHit = bar.high() >= pos.sl();
            tpHit = bar.low() <= pos.tp();
        }
        if (slHit) {
            return closeAt(pos, index, pos.sl(), "SL", cost, contractSize, volume, bars);
        }
        if (tpHit) {
            return closeAt(pos, index, pos.tp(), "TP", cost, contractSize, volume, bars);
        }
        return null;
    }

    private ClosedTrade closeAt(OpenPosition pos, int index, double refPrice, String reason,
                                CostModel cost, double contractSize, double volume, List<KlineBar> bars) {
        RiskSide exitSide = pos.side() == RiskSide.BUY ? RiskSide.SELL : RiskSide.BUY;
        double closePrice = cost.fillPrice(exitSide, refPrice);
        double gross = cost.profit(pos.side(), pos.entryPrice(), closePrice, volume, contractSize);

        long openEpoch = bars.get(pos.entryIndex()).time();
        long closeEpoch = bars.get(index).time();
        double swap = computeSwap(openEpoch, closeEpoch, volume);
        int holdSec = (int) Math.max(0, closeEpoch - openEpoch);

        return new ClosedTrade(pos.entryIndex(), index,
                toLocalDateTime(openEpoch), toLocalDateTime(closeEpoch),
                pos.side(), volume, pos.entryPrice(), closePrice, pos.sl(), pos.tp(),
                gross, swap, holdSec, reason);
    }

    /** 隔夜费：持仓跨越每日结算点则计一次，周三（可配）计三倍（固定日均近似）。 */
    private double computeSwap(long openEpoch, long closeEpoch, double volume) {
        if (!props.isSwapOn() || props.getSwapPerLotPerDay() == 0) {
            return 0;
        }
        DayOfWeek triple = parseWeekday(props.getSwapTripleWeekday());
        int hour = Math.min(23, Math.max(0, props.getSettlementHour()));
        LocalDate openDate = toLocalDate(openEpoch);
        LocalDate closeDate = toLocalDate(closeEpoch);
        double total = 0;
        for (LocalDate d = openDate; !d.isAfter(closeDate); d = d.plusDays(1)) {
            long settlement = d.atTime(hour, 0).atZone(zone).toEpochSecond();
            if (settlement > openEpoch && settlement <= closeEpoch) {
                double factor = (triple != null && d.getDayOfWeek() == triple) ? 3.0 : 1.0;
                total += props.getSwapPerLotPerDay() * volume * factor;
            }
        }
        return total;
    }

    private LocalDateTime toLocalDateTime(long epochSecond) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone);
    }

    private LocalDate toLocalDate(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate();
    }

    private static DayOfWeek parseWeekday(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
