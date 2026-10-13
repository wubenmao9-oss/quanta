package com.benmao.quanta.review;

import com.benmao.quanta.entity.TradeOrder;

/**
 * 平仓事件（M11）：{@code TradingService.closePosition} 事务提交后发布，
 * 由 {@link ReviewService} 监听并自动生成单笔全周期报告（设计 §5「平仓 → 生成全周期报告」）。
 *
 * <p>用事件解耦「交易执行」与「复盘」，且监听器挂在 {@code AFTER_COMMIT}：报告生成失败绝不回滚已成交的平仓。
 */
public record TradeClosedEvent(TradeOrder order) {
}
