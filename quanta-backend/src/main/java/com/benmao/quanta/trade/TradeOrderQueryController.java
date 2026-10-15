package com.benmao.quanta.trade;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 逐笔成交流水查询（新增只读接口）：{@code GET /api/trade/orders}。
 *
 * <p>此前复盘报告（{@code /api/review/reports}）只覆盖「有报告」的单，导致前端净值曲线
 * 只显示零星两点。本接口直接读 {@code trade_order} 全量流水，供前端绘制真实逐笔与净值曲线。
 *
 * <p>与 {@link TradeController} 的写接口同挂 {@code /api/trade} 前缀但子路径不同，互不冲突。
 */
@RestController
@RequestMapping("/api/trade")
public class TradeOrderQueryController {

    private static final int MAX_LIMIT = 1000;

    private final TradeOrderMapper tradeOrderMapper;

    public TradeOrderQueryController(TradeOrderMapper tradeOrderMapper) {
        this.tradeOrderMapper = tradeOrderMapper;
    }

    /**
     * 最近成交流水（最新在前）。
     *
     * @param status 可空=全部；常用 CLOSED（已平仓）/ FILLED（已成交未平）
     * @param limit  条数上限（默认 200，最大 1000）
     */
    @GetMapping("/orders")
    public Result<List<TradeOrder>> orders(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int limit) {
        int n = limit > 0 ? Math.min(limit, MAX_LIMIT) : 200;
        String st = (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) ? null : status.trim();
        return Result.ok(tradeOrderMapper.listRecent(st, n));
    }
}
