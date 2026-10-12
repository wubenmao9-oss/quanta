package com.benmao.quanta.backtest;

import java.time.LocalDateTime;

/**
 * 历史 K 线回填结果（M8 数据准备：从 MT5 拉最近 N 天 M1 落库 market_kline）。
 *
 * @param fetched  本轮从桥接拉取的 K 线总数
 * @param inserted 实际新入库的行数（已存在的按唯一键去重，不计入）
 * @param stored   回填窗口内在库的 K 线总数（核对用）
 * @param pages    向历史请求的分块批次（每批一个「天窗口」）
 */
public record BackfillResult(String symbol,
                             String timeframe,
                             int days,
                             int pages,
                             int fetched,
                             int inserted,
                             long stored,
                             LocalDateTime cutoff,
                             LocalDateTime now) {
}
