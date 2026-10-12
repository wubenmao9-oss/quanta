package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.mapper.MarketKlineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史行情回填：从 MT5 桥接按页向历史拉取 K 线并落库 {@code market_kline}（设计 §13.1 数据源）。
 *
 * <p>回测（M8）需 M1 粒度历史数据，本服务把最近 N 天的 M1 拉进库缓存，回测再从库中按区间读取，
 * 保证「回测可复现」（同一份库内数据，结果确定）。
 */
@Service
public class MarketHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MarketHistoryService.class);

    private final Mt5BridgeService bridge;
    private final MarketKlineMapper klineMapper;
    private final BacktestProperties props;
    private final Clock clock;

    @Autowired
    public MarketHistoryService(Mt5BridgeService bridge,
                                MarketKlineMapper klineMapper,
                                BacktestProperties props) {
        this(bridge, klineMapper, props, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    MarketHistoryService(Mt5BridgeService bridge,
                         MarketKlineMapper klineMapper,
                         BacktestProperties props,
                         Clock clock) {
        this.bridge = bridge;
        this.klineMapper = klineMapper;
        this.props = props;
        this.clock = clock;
    }

    /** 回填最近 {@code days} 天（days 为空用配置默认）。 */
    public BackfillResult backfill(String symbol, String timeframe, Integer days) {
        String sym = (symbol == null || symbol.isBlank()) ? props.getSymbol() : symbol.trim();
        String tf = (timeframe == null || timeframe.isBlank()) ? props.getTimeframe() : timeframe.trim().toUpperCase();
        int d = days == null ? props.getBackfillDays() : days;
        if (d < 1 || d > 365) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "回填天数非法：" + d + "，合法范围 [1, 365]", 400);
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        LocalDateTime cutoff = now.minusDays(d);
        long nowEpoch = now.atZone(clock.getZone()).toEpochSecond();
        long cutoffEpoch = cutoff.atZone(clock.getZone()).toEpochSecond();

        // 按「天窗口」自近及远向历史分块请求：MT5 终端会按需补下历史，
        // 比偏移翻页稳定（偏移只读已加载到图表的历史）。循环由 days 决定，天然有界。
        long chunkSec = Math.max(1, props.getFetchChunkDays()) * 86400L;
        int pages = 0;
        int fetched = 0;
        int inserted = 0;
        for (long to = nowEpoch; to > cutoffEpoch; to -= chunkSec) {
            long from = Math.max(cutoffEpoch, to - chunkSec);
            List<KlineBar> page = bridge.klinesRange(sym, tf, from, to);
            pages++;
            if (page == null || page.isEmpty()) {
                continue;
            }
            fetched += page.size();
            for (KlineBar bar : page) {
                if (bar.time() < cutoffEpoch || bar.time() > nowEpoch) {
                    continue;
                }
                LocalDateTime barTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(bar.time()), clock.getZone());
                inserted += klineMapper.insertIgnore(sym, tf, barTime,
                        bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
            }
        }

        long stored = klineMapper.countRange(sym, tf, cutoff, now.plusDays(1));
        log.info("历史回填完成：symbol={}, timeframe={}, days={}, chunks={}, fetched={}, inserted={}, stored={}",
                sym, tf, d, pages, fetched, inserted, stored);
        return new BackfillResult(sym, tf, d, pages, fetched, inserted, stored, cutoff, now);
    }
}
