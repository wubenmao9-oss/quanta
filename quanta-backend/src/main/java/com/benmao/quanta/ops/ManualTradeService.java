package com.benmao.quanta.ops;

import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.risk.RiskOrigin;
import com.benmao.quanta.schedule.TriggerEngine;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 人工手动交易入口（M13，设计 §8.3）。
 *
 * <p>铁律：人工操作<b>也必须</b>走 {@link TradingService} → 同一套
 * {@code RiskGuard}（人工不豁免任何硬限额）；本类只把来源标记为 {@link RiskOrigin#MANUAL}
 * 以豁免 R11 三态自拦截，并补齐「落 {@code operation_audit} + 通知关联会话」两件协议动作。
 *
 * <p>注意：本类<b>不加事务</b>——审计必须在底层交易事务之外独立提交，
 * 否则交易失败回滚时审计会一并丢失（人工操作无论成败都要留痕）。
 */
@Service
public class ManualTradeService {

    private static final Logger log = LoggerFactory.getLogger(ManualTradeService.class);

    private final TradingService tradingService;
    private final OperationAuditService auditService;
    private final TriggerEngine triggerEngine;
    private final OpsProperties props;

    public ManualTradeService(TradingService tradingService,
                              OperationAuditService auditService,
                              TriggerEngine triggerEngine,
                              OpsProperties props) {
        this.tradingService = tradingService;
        this.auditService = auditService;
        this.triggerEngine = triggerEngine;
        this.props = props;
    }

    /** 人工开仓：经 TradingService（origin=MANUAL）→ 审计 + 通知会话。 */
    public TradeOrder open(TradeCommand cmd) {
        TradeCommand manual = cmd.withOrigin(RiskOrigin.MANUAL);
        return execute("MANUAL_OPEN", manual, () -> tradingService.openPosition(manual));
    }

    /** 人工平仓：经 TradingService（origin=MANUAL）→ 审计 + 通知会话。 */
    public TradeOrder close(TradeCommand cmd) {
        TradeCommand manual = cmd.withOrigin(RiskOrigin.MANUAL);
        return execute("MANUAL_CLOSE", manual, () -> tradingService.closePosition(manual));
    }

    /** 人工改 SL/TP：经 TradingService（origin=MANUAL）→ 审计 + 通知会话。 */
    public TradeOrder modify(TradeCommand cmd) {
        TradeCommand manual = cmd.withOrigin(RiskOrigin.MANUAL);
        return execute("MANUAL_MODIFY", manual, () -> tradingService.modifySlTp(manual));
    }

    // ==================== 内部实现 ====================

    /** 执行 + 留痕：成功记结果、失败记原因，均写审计；成功后再通知会话。 */
    private TradeOrder execute(String opType, TradeCommand cmd, Supplier<TradeOrder> action) {
        String target = cmd.ticket() != null ? String.valueOf(cmd.ticket()) : cmd.symbol();
        try {
            TradeOrder order = action.get();
            auditService.write(props.getManualAuditOperator(), opType, target, null, describe(order),
                    order.getTraceId());
            notifySession(opType, order);
            return order;
        } catch (RuntimeException e) {
            auditService.write(props.getManualAuditOperator(), opType, target, null,
                    "FAILED: " + e.getMessage(), cmd.traceId());
            throw e;
        }
    }

    private static String describe(TradeOrder o) {
        return "status=" + o.getStatus() + " ticket=" + o.getTicket()
                + " symbol=" + o.getSymbol() + " cmd=" + o.getCmd()
                + " volume=" + o.getVolume() + " price=" + o.getPrice();
    }

    /** 通知关联会话（T7 系统事件），让 AI 更新记忆；best-effort。 */
    private void notifySession(String opType, TradeOrder order) {
        if (!props.isManualNotifySession()) {
            return;
        }
        try {
            triggerEngine.systemEvent("人工操作", opType + " ticket=" + order.getTicket()
                    + " symbol=" + order.getSymbol() + " status=" + order.getStatus());
        } catch (RuntimeException e) {
            log.warn("人工操作通知会话失败（已忽略）：{}", e.getMessage());
        }
    }
}
