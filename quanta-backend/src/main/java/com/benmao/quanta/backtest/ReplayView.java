package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;

import java.util.List;

/**
 * 回放游标视图：<b>引擎层强制防未来函数</b>的结构性保证。
 *
 * <p>规则策略只能通过本视图读取行情，且仅允许读取 {@code index ≤ cursor} 的数据；
 * 任何越界读取（试图读取未来 K 线）都会立即抛 {@link IllegalStateException}，
 * 从而在实现层面杜绝"未来函数"（设计 §13.5）。
 */
public final class ReplayView {

    private final List<KlineBar> bars;
    private final int cursor;

    ReplayView(List<KlineBar> bars, int cursor) {
        this.bars = bars;
        this.cursor = cursor;
    }

    /** 当前游标（= 可读取的最大索引，含）。 */
    public int cursor() {
        return cursor;
    }

    /** 可见 K 线数量（= cursor + 1）。 */
    public int size() {
        return cursor + 1;
    }

    /** 读取指定索引的 K 线；索引超出游标（未来数据）即抛错。 */
    public KlineBar bar(int index) {
        if (index < 0 || index > cursor) {
            throw new IllegalStateException(
                    "禁止读取未来 K 线：index=" + index + "，当前游标=" + cursor + "（防未来函数）");
        }
        return bars.get(index);
    }

    /** 当前 K 线（游标处）。 */
    public KlineBar current() {
        return bars.get(cursor);
    }

    /** 前一根 K 线；游标为 0 时返回 null。 */
    public KlineBar previous() {
        return cursor > 0 ? bars.get(cursor - 1) : null;
    }
}
