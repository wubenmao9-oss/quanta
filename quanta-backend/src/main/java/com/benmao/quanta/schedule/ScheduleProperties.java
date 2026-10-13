package com.benmao.quanta.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度层可调参数（设计 §6 / §18：阈值与开关一律集中配置、不写死）。
 *
 * <p>全部可在 {@code application.yml} 的 {@code quanta.schedule.*} 下覆写。
 */
@Component
@ConfigurationProperties(prefix = "quanta.schedule")
public class ScheduleProperties {

    /** 调度层总开关；关闭后 MarketWatcher 与 TriggerEngine 都不工作。 */
    private boolean enabled = true;

    /** 会话窗口数上限，合法范围 1~20（设计 §6.3）。 */
    private int maxSession = 5;

    /** 盯盘品种（券商真实名，一期 GOLD）。 */
    private String symbol = "GOLD";

    private MarketWatcher marketWatcher = new MarketWatcher();

    private Trigger trigger = new Trigger();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSession() {
        return maxSession;
    }

    public void setMaxSession(int maxSession) {
        this.maxSession = maxSession;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public MarketWatcher getMarketWatcher() {
        return marketWatcher;
    }

    public void setMarketWatcher(MarketWatcher marketWatcher) {
        this.marketWatcher = marketWatcher;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public void setTrigger(Trigger trigger) {
        this.trigger = trigger;
    }

    /** 盯盘（MarketWatcher）参数。 */
    public static class MarketWatcher {

        /** 是否开启盯盘。 */
        private boolean enabled = true;

        /** 轮询间隔（毫秒），驱动 {@code @Scheduled}。 */
        private long pollIntervalMs = 2000;

        /** 拉取 K 线的时间周期（M1/M5/M15…）。 */
        private String timeframe = "M5";

        /** 每次拉取的 K 线根数。 */
        private int klineCount = 200;

        /** 断线退避起始间隔（毫秒）。 */
        private long reconnectBaseMs = 2000;

        /** 断线退避最大间隔（毫秒）。 */
        private long reconnectMaxMs = 60000;

        /** 是否把盯到的 K 线去重落库（market_kline），失败不影响盯盘。 */
        private boolean persistKlines = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public String getTimeframe() {
            return timeframe;
        }

        public void setTimeframe(String timeframe) {
            this.timeframe = timeframe;
        }

        public int getKlineCount() {
            return klineCount;
        }

        public void setKlineCount(int klineCount) {
            this.klineCount = klineCount;
        }

        public long getReconnectBaseMs() {
            return reconnectBaseMs;
        }

        public void setReconnectBaseMs(long reconnectBaseMs) {
            this.reconnectBaseMs = reconnectBaseMs;
        }

        public long getReconnectMaxMs() {
            return reconnectMaxMs;
        }

        public void setReconnectMaxMs(long reconnectMaxMs) {
            this.reconnectMaxMs = reconnectMaxMs;
        }

        public boolean isPersistKlines() {
            return persistKlines;
        }

        public void setPersistKlines(boolean persistKlines) {
            this.persistKlines = persistKlines;
        }
    }

    /** 触发（TriggerEngine）参数。 */
    public static class Trigger {

        /** 扫描间隔（毫秒），驱动 {@code @Scheduled}。 */
        private long scanIntervalMs = 1000;

        /** 每个触发源独立开关；未显式配置的源用 {@link #enabled(TriggerSource)} 的默认值。 */
        private Map<String, Boolean> switches = new LinkedHashMap<>();

        private Time time = new Time();

        private PositionEvent positionEvent = new PositionEvent();

        private Pattern pattern = new Pattern();

        private IndicatorRule indicator = new IndicatorRule();

        private AutoWake autoWake = new AutoWake();

        /**
         * 某触发源是否启用。显式配置优先；否则「必做」源（T1/T2/T5/T6/T7）默认开，
         * 插件源（T3/T4）与二期源（T8）默认关。
         */
        public boolean enabled(TriggerSource source) {
            if (source == null) {
                return false;
            }
            Boolean configured = switches.get(source.name());
            if (configured != null) {
                return configured;
            }
            return switch (source) {
                case T1, T2, T5, T6, T7 -> true;
                case T3, T4, T8 -> false;
            };
        }

        public long getScanIntervalMs() {
            return scanIntervalMs;
        }

        public void setScanIntervalMs(long scanIntervalMs) {
            this.scanIntervalMs = scanIntervalMs;
        }

        public Map<String, Boolean> getSwitches() {
            return switches;
        }

        public void setSwitches(Map<String, Boolean> switches) {
            this.switches = switches;
        }

        public Time getTime() {
            return time;
        }

        public void setTime(Time time) {
            this.time = time;
        }

        public PositionEvent getPositionEvent() {
            return positionEvent;
        }

        public void setPositionEvent(PositionEvent positionEvent) {
            this.positionEvent = positionEvent;
        }

        public Pattern getPattern() {
            return pattern;
        }

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        public IndicatorRule getIndicator() {
            return indicator;
        }

        public void setIndicator(IndicatorRule indicator) {
            this.indicator = indicator;
        }

        public AutoWake getAutoWake() {
            return autoWake;
        }

        public void setAutoWake(AutoWake autoWake) {
            this.autoWake = autoWake;
        }
    }

    /**
     * 触发命中后自动唤醒 AI 研判（M14，设计 §5「触发 → 唤醒会话研判」）。
     *
     * <p><b>默认关闭</b>：关闭时触发命中只分配会话、不唤醒 AI，保持一期「盯盘 0 token」；
     * 开启后（非降级、非 TAKEOVER、命中源在白名单内）由调度层异步回调
     * {@link TriggerWakeHandler}（实际实现 {@code AgentTriggerWakeHandler} → {@code AgentService}）。
     */
    public static class AutoWake {

        /** 总开关（默认关）。 */
        private boolean enabled = false;

        /** 最小唤醒间隔（秒），对频繁命中的触发做风暴节流；0 = 不节流。 */
        private long minIntervalSeconds = 60;

        /** 允许自动唤醒的触发源白名单（默认仅行情/持仓类 T1/T2/T5）。 */
        private List<String> sources = new ArrayList<>(List.of("T1", "T2", "T5"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMinIntervalSeconds() {
            return minIntervalSeconds;
        }

        public void setMinIntervalSeconds(long minIntervalSeconds) {
            this.minIntervalSeconds = minIntervalSeconds;
        }

        public List<String> getSources() {
            return sources;
        }

        public void setSources(List<String> sources) {
            this.sources = (sources == null) ? new ArrayList<>() : sources;
        }

        /** 该触发源是否在自动唤醒白名单内。 */
        public boolean allows(TriggerSource source) {
            return source != null && sources != null && sources.contains(source.name());
        }
    }

    /** T2 时间驱动参数。 */
    public static class Time {

        /** 每 N 秒唤醒一次（设计「每 N 分钟」的等价细粒度，<=0 表示不按间隔触发）。 */
        private long intervalSeconds = 300;

        /** 是否在每根 K 线收盘时也唤醒。 */
        private boolean barClose = false;

        public long getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(long intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public boolean isBarClose() {
            return barClose;
        }

        public void setBarClose(boolean barClose) {
            this.barClose = barClose;
        }
    }

    /** T5 持仓事件参数（浮动盈亏阈值，单位 USD）。 */
    public static class PositionEvent {

        /** 浮盈达到该值 → 唤醒（锁盈研判）。 */
        private double profitThreshold = 5.0;

        /** 浮亏达到该值 → 唤醒（止损研判）。 */
        private double lossThreshold = 5.0;

        public double getProfitThreshold() {
            return profitThreshold;
        }

        public void setProfitThreshold(double profitThreshold) {
            this.profitThreshold = profitThreshold;
        }

        public double getLossThreshold() {
            return lossThreshold;
        }

        public void setLossThreshold(double lossThreshold) {
            this.lossThreshold = lossThreshold;
        }
    }

    /** T3 形态插件参数（放量 + 吞没）。 */
    public static class Pattern {

        /** 放量判定系数：最新量 > 均量 × 系数。 */
        private double volumeSpikeMultiplier = 1.8;

        /** 均量周期。 */
        private int volumeMaPeriod = 20;

        public double getVolumeSpikeMultiplier() {
            return volumeSpikeMultiplier;
        }

        public void setVolumeSpikeMultiplier(double volumeSpikeMultiplier) {
            this.volumeSpikeMultiplier = volumeSpikeMultiplier;
        }

        public int getVolumeMaPeriod() {
            return volumeMaPeriod;
        }

        public void setVolumeMaPeriod(int volumeMaPeriod) {
            this.volumeMaPeriod = volumeMaPeriod;
        }
    }

    /** T4 指标插件参数（RSI + 布林带）。 */
    public static class IndicatorRule {

        /** RSI 超买阈值（配合触上轨 → 反弹/回落研判）。 */
        private double rsiOverbought = 70.0;

        /** RSI 超卖阈值（配合触下轨）。 */
        private double rsiOversold = 30.0;

        public double getRsiOverbought() {
            return rsiOverbought;
        }

        public void setRsiOverbought(double rsiOverbought) {
            this.rsiOverbought = rsiOverbought;
        }

        public double getRsiOversold() {
            return rsiOversold;
        }

        public void setRsiOversold(double rsiOversold) {
            this.rsiOversold = rsiOversold;
        }
    }
}
