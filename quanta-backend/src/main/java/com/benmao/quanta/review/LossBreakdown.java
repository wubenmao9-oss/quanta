package com.benmao.quanta.review;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 败因分类（设计 §12：无信号错过 / 逆势 / 早平 / 晚平 / 参数不适 / 系统错误）。
 *
 * <p>规则化统计，<b>不训练模型</b>；判据见 {@code notes}。
 *
 * @param symbol           品种（可空 = 全品种）
 * @param since            统计起点（可空）
 * @param until            统计终点（可空）
 * @param closedCount      已平仓交易数
 * @param lossCount        亏损单数（pnl &lt; 0）
 * @param earlyClose       早平：盈利单持仓短于早平阈值（错失后续利润）
 * @param lateClose        晚平：亏损单持仓达到晚平阈值（扛单过久）
 * @param systemError      系统错误：券商返回码非 0（retcode != 0）
 * @param counterTrendLoss 逆势亏损：其余亏损单归因（方向判断错误）
 * @param missedSignalWaits 无信号错过：区间内 AI 判 WAIT 的次数（决策日志统计）
 * @param notes            判据说明
 */
public record LossBreakdown(String symbol,
                            LocalDateTime since,
                            LocalDateTime until,
                            int closedCount,
                            int lossCount,
                            int earlyClose,
                            int lateClose,
                            int systemError,
                            int counterTrendLoss,
                            int missedSignalWaits,
                            List<String> notes) {
}
