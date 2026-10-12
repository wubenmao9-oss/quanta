package com.benmao.quanta.schedule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.mapper.MarketKlineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 盯盘器：Java 侧持续拉 K 线、产出 {@link MarketSnapshot} 供触发判定。
 *
 * <p><b>成本≈0、不消耗 token</b>：本类只做行情采集与缓存，不持有也不调用任何 LLM。
 *
 * <p>鲁棒性：
 * <ul>
 *   <li>任一轮失败只标记「断开」，并按指数退避（{@code reconnectBaseMs} → {@code reconnectMaxMs}）重试；</li>
 *   <li>失败达到阈值时主动探测/重登桥接（{@link Mt5BridgeService#health()} / {@link Mt5BridgeService#connect()}）；</li>
 *   <li>K 线落库失败被吞掉，绝不影响盯盘主流程。</li>
 * </ul>
 */
@Service
public class MarketWatcher {

    private static final Logger log = LoggerFactory.getLogger(MarketWatcher.class);

    private final Mt5BridgeService bridge;
    private final MarketKlineMapper klineMapper;
    private final ScheduleProperties props;
    private final Clock clock;

    /** 最新行情快照（volatile 保证调度线程写、触发线程读的可见性）。 */
    private volatile MarketSnapshot snapshot;
    /** 是否处于已连接状态。 */
    private volatile boolean connected;
    /** 连续失败次数。 */
    private volatile long consecutiveFailures;
    /** 退避重试的最早时刻（null = 立即可试）。 */
    private volatile Instant nextRetryAt;
    /** 已落库的最新 K 线时间（用于增量落库）。 */
    private volatile LocalDateTime lastPersistedBarTime;

    @Autowired
    public MarketWatcher(Mt5BridgeService bridge,
                         MarketKlineMapper klineMapper,
                         ScheduleProperties props) {
        this(bridge, klineMapper, props, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    MarketWatcher(Mt5BridgeService bridge,
                  MarketKlineMapper klineMapper,
                  ScheduleProperties props,
                  Clock clock) {
        this.bridge = bridge;
        this.klineMapper = klineMapper;
        this.props = props;
        this.clock = clock;
    }

    /** 定时盯盘（间隔由 {@code quanta.schedule.market-watcher.poll-interval-ms} 决定）。 */
    @Scheduled(fixedDelayString = "${quanta.schedule.market-watcher.poll-interval-ms:2000}")
    public void poll() {
        if (!props.isEnabled() || !props.getMarketWatcher().isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant retry = nextRetryAt;
        if (retry != null && now.isBefore(retry)) {
            return;
        }

        String symbol = symbol();
        String timeframe = props.getMarketWatcher().getTimeframe();
        try {
            List<KlineBar> bars = bridge.klines(symbol, timeframe, props.getMarketWatcher().getKlineCount());
            if (bars == null || bars.isEmpty()) {
                throw new IllegalStateException("桥接返回空 K 线");
            }
            Quote quote = safeQuote(symbol);
            MarketSnapshot fresh = build(symbol, timeframe, bars, quote, now);
            snapshot = fresh;

            if (!connected) {
                log.info("MarketWatcher 已连接{}：symbol={}, timeframe={}, bars={}",
                        consecutiveFailures > 0 ? "（断线恢复）" : "", symbol, timeframe, bars.size());
            }
            connected = true;
            consecutiveFailures = 0;
            nextRetryAt = null;

            if (props.getMarketWatcher().isPersistKlines()) {
                persist(symbol, timeframe, bars);
            }
        } catch (RuntimeException e) {
            consecutiveFailures++;
            connected = false;
            long backoff = backoffMs(consecutiveFailures);
            nextRetryAt = now.plusMillis(backoff);
            log.warn("MarketWatcher 盯盘失败（第 {} 次），{}ms 后自动重试：{}",
                    consecutiveFailures, backoff, e.getMessage());
            attemptReconnect();
        }
    }

    /** 最新快照；尚未成功拉取过时为 null。 */
    public MarketSnapshot snapshot() {
        return snapshot;
    }

    /** 是否已连接（最近一轮成功）。 */
    public boolean connected() {
        return connected;
    }

    /** 连续失败次数。 */
    public long consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 盯盘品种。 */
    public String symbol() {
        String s = props.getSymbol();
        return (s == null || s.isBlank()) ? "GOLD" : s.trim();
    }

    /** 盯盘周期。 */
    public String timeframe() {
        return props.getMarketWatcher().getTimeframe();
    }

    // ==================== 内部实现 ====================

    private MarketSnapshot build(String symbol, String timeframe, List<KlineBar> bars, Quote quote, Instant now) {
        KlineBar last = bars.get(bars.size() - 1);
        LocalDateTime barTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(last.time()), clock.getZone());
        double bid = quote != null ? quote.bid() : last.close();
        double ask = quote != null ? quote.ask() : last.close();
        int spread = quote != null ? (int) quote.spread() : 0;
        return new MarketSnapshot(symbol, timeframe, barTime, bid, ask, spread,
                last.close(), last.high(), last.low(), List.copyOf(bars), now);
    }

    private Quote safeQuote(String symbol) {
        try {
            return bridge.quote(symbol);
        } catch (RuntimeException e) {
            log.debug("取报价失败，用最新收盘价兜底：{}", e.getMessage());
            return null;
        }
    }

    /** K 线增量落库（按 bar_time 去重）；失败仅记日志，绝不影响盯盘。 */
    private void persist(String symbol, String timeframe, List<KlineBar> bars) {
        try {
            LocalDateTime marker = lastPersistedBarTime;
            for (KlineBar bar : bars) {
                LocalDateTime barTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(bar.time()), clock.getZone());
                if (marker != null && !barTime.isAfter(marker)) {
                    continue;
                }
                klineMapper.insertIgnore(symbol, timeframe, barTime,
                        bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
                lastPersistedBarTime = barTime;
            }
        } catch (RuntimeException e) {
            log.debug("K 线落库失败（忽略）：{}", e.getMessage());
        }
    }

    private void attemptReconnect() {
        try {
            Map<String, Object> health = bridge.health();
            log.info("MarketWatcher 重连探测：{}", health);
            if (consecutiveFailures >= 2) {
                bridge.connect();
                log.info("MarketWatcher 已主动重登桥接");
            }
        } catch (RuntimeException e) {
            log.debug("MarketWatcher 重连探测未成功：{}", e.getMessage());
        }
    }

    private long backoffMs(long failures) {
        long base = Math.max(1, props.getMarketWatcher().getReconnectBaseMs());
        long max = Math.max(base, props.getMarketWatcher().getReconnectMaxMs());
        long shift = Math.min(Math.max(failures - 1, 0), 16);
        return Math.min(base << shift, max);
    }
}
