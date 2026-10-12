package com.benmao.quanta.bridge;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.mt5.model.SessionInfo;
import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 桥接层只读接口：供人工验收与后续调度层（MarketWatcher）复用。
 *
 * <p>顺序写操作（开仓/平仓/改仓）<b>不</b>在此暴露——那是 M6 TradingService 的职责，
 * 必须穿过风控层 RiskGuard 后才能下达。
 */
@RestController
@RequestMapping("/api/mt5")
public class Mt5BridgeController {

    private final Mt5BridgeService bridge;

    public Mt5BridgeController(Mt5BridgeService bridge) {
        this.bridge = bridge;
    }

    /** 连通性探活（不校验凭据）。 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(bridge.health());
    }

    /** 用库内激活的 MT5 凭据真实登录（幂等）。 */
    @PostMapping("/connect")
    public Result<SessionInfo> connect() {
        return Result.ok(bridge.connect());
    }

    /** 账户资金。 */
    @GetMapping("/account")
    public Result<AccountInfo> account() {
        return Result.ok(bridge.account());
    }

    /** 实时报价；symbol 不传用默认品种（GOLD）。 */
    @GetMapping("/quote")
    public Result<Quote> quote(@RequestParam(value = "symbol", required = false) String symbol) {
        return Result.ok(bridge.quote(symbol == null ? bridge.defaultSymbol() : symbol));
    }

    /** K 线。 */
    @GetMapping("/klines")
    public Result<List<KlineBar>> klines(@RequestParam(value = "symbol", required = false) String symbol,
                                         @RequestParam(value = "timeframe", defaultValue = "M5") String timeframe,
                                         @RequestParam(value = "count", defaultValue = "200") int count) {
        return Result.ok(bridge.klines(symbol == null ? bridge.defaultSymbol() : symbol, timeframe, count));
    }

    /** 持仓；symbol 不传 = 全部品种。 */
    @GetMapping("/positions")
    public Result<List<Position>> positions(@RequestParam(value = "symbol", required = false) String symbol) {
        return Result.ok(bridge.positions(symbol));
    }

    /** 券商品种清单（校验名称映射：GOLD 是否存在）。 */
    @GetMapping("/symbols")
    public Result<List<String>> symbols(@RequestParam(value = "query", required = false) String query) {
        return Result.ok(bridge.symbols(query));
    }
}