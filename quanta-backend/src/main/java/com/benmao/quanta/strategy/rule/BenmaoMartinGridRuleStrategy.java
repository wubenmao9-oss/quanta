package com.benmao.quanta.strategy.rule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.backtest.BasketAction;
import com.benmao.quanta.backtest.BasketEngineMode;
import com.benmao.quanta.backtest.BasketLeg;
import com.benmao.quanta.backtest.BasketPosition;
import com.benmao.quanta.backtest.BasketRuleRunner;
import com.benmao.quanta.backtest.BasketState;
import com.benmao.quanta.backtest.BasketStrategy;
import com.benmao.quanta.backtest.BasketStrategyProperties;
import com.benmao.quanta.backtest.GridPendingOrder;
import com.benmao.quanta.backtest.MartinMath;
import com.benmao.quanta.backtest.ReplayView;
import com.benmao.quanta.risk.RiskSide;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「笨猫马丁-网格双边」策略（BASKET 模式）：<b>按盘面形态在马丁与网格间自动切换</b>。
 *
 * <ul>
 *   <li>形态 = {@code MARTIN}（趋势，EMA 快慢线分离 + ADX 高）→ 开<b>马丁轮</b>：顺 EMA 慢线方向开第 1 腿，
 *       逆势每 {@code addLegDistancePoints} 补一腿、手数 ×{@code multiplier}，整轮按加权均价止盈；</li>
 *   <li>形态 = {@code GRID}（震荡，EMA 快慢线重合 + ADX 低）→ 双边挂 {@code BUY_STOP(rangeHigh+step)} /
 *       {@code SELL_STOP(rangeLow−step)}，逐腿独立止盈损，达上限/越界/锁定即撤单；</li>
 *   <li>{@code WAIT} 或时段外 / 日锁 / 总锁 → 不动作（锁定期内撤挂单）。</li>
 * </ul>
 *
 * <p>手数/均价/止盈价的机械计算全部由引擎（{@link com.benmao.quanta.backtest.BasketReplayEngine}）完成，
 * 本 runner 只表达「何时做何种动作」；参数全部取自 {@link BasketStrategyProperties} 集中配置。
 * 仅经 {@link ReplayView} 读取「当前及之前」K 线，杜绝未来函数。
 */
@Component
public class BenmaoMartinGridRuleStrategy implements BasketStrategy {

    public static final String KEY = "BENMAO_MA_GRID";

    private final BasketStrategyProperties bp;

    public BenmaoMartinGridRuleStrategy(BasketStrategyProperties bp) {
        this.bp = bp;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "笨猫马丁-网格双边（形态自适应：趋势马丁 / 震荡网格）";
    }

    @Override
    public Map<String, Object> indicatorParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        BasketStrategyProperties.Session s = bp.getSession();
        m.put("emaFast", s.getEmaFast());
        m.put("emaSlow", s.getEmaSlow());
        m.put("adxPeriod", s.getAdxPeriod());
        m.put("adxGridMax", s.getAdxGridMax());
        m.put("adxTrendMin", s.getAdxTrendMin());
        m.put("trendEpsilonPct", s.getTrendEpsilonPct());
        m.put("startHour", s.getStartHour());
        m.put("endHour", s.getEndHour());
        BasketStrategyProperties.Martin ma = bp.getMartin();
        m.put("maxLevels", ma.getMaxLevels());
        m.put("multiplier", ma.getMultiplier());
        m.put("baseLotPct", ma.getBaseLotPct());
        m.put("slPoints", ma.getSlPoints());
        m.put("tpPoints", ma.getTpPoints());
        m.put("addLegDistancePoints", ma.getAddLegDistancePoints());
        m.put("basketTargetRr", ma.getBasketTargetRr());
        BasketStrategyProperties.Grid g = bp.getGrid();
        m.put("gridStepPoints", g.getStepPoints());
        m.put("gridLegsPerSide", g.getLegsPerSide());
        m.put("gridTargetRr", g.getTargetRr());
        m.put("gridSlMult", g.getSlMult());
        m.put("gridRangeWindow", g.getRangeWindow());
        return m;
    }

    @Override
    public BasketRuleRunner newRunner() {
        return new BenmaoMartinGridRunner(bp);
    }

    /** 有状态运行实例：形态判定 + 马丁补腿 / 双边网格挂单调度。 */
    static final class BenmaoMartinGridRunner implements BasketRuleRunner {

        private final BasketStrategyProperties bp;
        private final ZoneId zone;
        private final MarketRegimeClassifier clf;

        BenmaoMartinGridRunner(BasketStrategyProperties bp) {
            this.bp = bp;
            this.zone = ZoneId.of(bp.getSession().getTimeZone());
            BasketStrategyProperties.Session s = bp.getSession();
            this.clf = new MarketRegimeClassifier(s.getEmaFast(), s.getEmaSlow(), s.getAdxPeriod(),
                    s.getTrendEpsilonPct(), s.getAdxGridMax(), s.getAdxTrendMin());
        }

        @Override
        public int warmupBars() {
            return clf.warmupBars() + 5;
        }

        @Override
        public List<BasketAction> onBar(BasketState state) {
            ReplayView view = state.view();
            KlineBar bar = view.current();
            clf.update(bar, view.cursor());

            List<BasketAction> acts = new ArrayList<>();
            if (view.cursor() < warmupBars()) {
                acts.add(new BasketAction.Idle());
                return acts;
            }

            BasketStrategyProperties.Session s = bp.getSession();
            int hour = bar.instant().atZone(zone).getHour();
            boolean inWindow = hour >= s.getStartHour() && hour < s.getEndHour();
            if (state.dayLocked() || state.totalLocked() || state.newsBlackout() || !inWindow) {
                // 锁定期 / 时段外：撤挂单（已开轮交由引擎的 SL/TP/硬止损处理，不主动强平）
                if (!state.pendings().isEmpty()) {
                    acts.add(new BasketAction.CancelAllPending());
                } else {
                    acts.add(new BasketAction.Idle());
                }
                return acts;
            }

            MarketRegimeClassifier.Regime regime = clf.regime();
            BasketPosition basket = state.basket();

            if (basket != null) {
                if (basket.mode() == BasketEngineMode.MARTIN) {
                    addMartinLeg(state, bar, basket, acts);
                } else {
                    addGridPendings(state, view, acts);
                }
                if (acts.isEmpty()) {
                    acts.add(new BasketAction.Idle());
                }
                return acts;
            }

            // 无轮
            if (regime == MarketRegimeClassifier.Regime.GRID) {
                addGridPendings(state, view, acts);
            } else {
                if (!state.pendings().isEmpty()) {
                    acts.add(new BasketAction.CancelAllPending());
                }
                if (regime == MarketRegimeClassifier.Regime.MARTIN) {
                    double slow = clf.emaSlow();
                    RiskSide side = bar.close() > slow ? RiskSide.BUY
                            : (bar.close() < slow ? RiskSide.SELL : null);
                    if (side != null) {
                        acts.add(new BasketAction.OpenCycle(side, BasketEngineMode.MARTIN));
                    }
                }
            }
            if (acts.isEmpty()) {
                acts.add(new BasketAction.Idle());
            }
            return acts;
        }

        /** 马丁：逆势补腿（浮亏达补腿距离且未达最大腿数）。 */
        private void addMartinLeg(BasketState state, KlineBar bar, BasketPosition basket, List<BasketAction> acts) {
            BasketStrategyProperties.Martin m = bp.getMartin();
            if (basket.level() >= m.getMaxLevels()) {
                return;
            }
            BasketLeg last = basket.legs().get(basket.legs().size() - 1);
            double trigger = MartinMath.addTrigger(basket.side(), last.entryPrice(),
                    m.getAddLegDistancePoints(), state.point());
            double adverse = basket.side() == RiskSide.BUY ? bar.low() : bar.high();
            boolean hit = basket.side() == RiskSide.BUY ? adverse <= trigger : adverse >= trigger;
            if (hit) {
                acts.add(new BasketAction.AddLeg());
            }
        }

        /** 网格：双边补挂（同一侧每补一腿向更远端推进一个步长；达上限/双向满仓则撤单）。 */
        private void addGridPendings(BasketState state, ReplayView view, List<BasketAction> acts) {
            BasketStrategyProperties.Grid g = bp.getGrid();
            double step = g.getStepPoints() * state.point();

            int buyLegs = 0;
            int sellLegs = 0;
            double lastBuy = Double.NaN;
            double lastSell = Double.NaN;
            if (state.basket() != null) {
                for (BasketLeg l : state.basket().legs()) {
                    if (l.side() == RiskSide.BUY) {
                        buyLegs++;
                        lastBuy = l.entryPrice();
                    } else {
                        sellLegs++;
                        lastSell = l.entryPrice();
                    }
                }
            }
            boolean buyPending = false;
            boolean sellPending = false;
            for (GridPendingOrder p : state.pendings()) {
                if (p.side() == RiskSide.BUY) {
                    buyPending = true;
                } else {
                    sellPending = true;
                }
            }

            int w = Math.max(2, g.getRangeWindow());
            int from = Math.max(0, view.cursor() - w + 1);
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int k = from; k <= view.cursor(); k++) {
                KlineBar b = view.bar(k);
                hi = Math.max(hi, b.high());
                lo = Math.min(lo, b.low());
            }

            if (!buyPending && buyLegs < g.getLegsPerSide()) {
                double price = Double.isNaN(lastBuy) ? hi + step : lastBuy + step;
                acts.add(new BasketAction.PlacePending(GridPendingOrder.Kind.BUY_STOP, price));
            }
            if (!sellPending && sellLegs < g.getLegsPerSide()) {
                double price = Double.isNaN(lastSell) ? lo - step : lastSell - step;
                acts.add(new BasketAction.PlacePending(GridPendingOrder.Kind.SELL_STOP, price));
            }
            if (buyLegs >= g.getLegsPerSide() && sellLegs >= g.getLegsPerSide()) {
                acts.add(new BasketAction.CancelAllPending());
            }
        }
    }
}
