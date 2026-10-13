package com.benmao.quanta.bridge;

import com.benmao.mt5.Mt5;
import com.benmao.mt5.Mt5Client;
import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.Deal;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.OrderResult;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.mt5.model.SessionInfo;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 桥接层服务：Java 侧取账户 / 报价 / K 线 / 持仓的唯一入口（复用 java-client 门面）。
 *
 * <p>要点：
 * <ul>
 *   <li>真实登录：用 sys_config 里**激活的 MT5 凭据**登录桥接服务（M1 的「测试连接」只探活）；
 *       登录成功后本进程内只登录一次，避免每次调用都重连；改了配置想立即生效，
 *       调用 {@link #connect()} 强制重登（接口 <code>POST /api/mt5/connect</code>）；</li>
 *   <li>名称映射：{@code XAUUSD} 等逻辑名统一解析为券商真实名（FxPro 为 {@code GOLD}）；</li>
 *   <li>失败一律转成可读的业务异常，不把裸 HTTP/JSON 抛给上层。</li>
 * </ul>
 */
@Service
public class Mt5BridgeService {

    private static final Logger log = LoggerFactory.getLogger(Mt5BridgeService.class);

    private final Mt5 mt5;
    private final ConfigService configService;
    private final boolean autoLogin;

    /** 已下发登录的账号（避免重复登录）。 */
    private volatile Integer loggedInLogin;

    public Mt5BridgeService(Mt5 mt5,
                            ConfigService configService,
                            @Value("${quanta.mt5.auto-login:true}") boolean autoLogin) {
        this.mt5 = mt5;
        this.configService = configService;
        this.autoLogin = autoLogin;
    }

    /** 桥接服务 + 终端连通性（探活，不校验凭据）。 */
    public Map<String, Object> health() {
        return call("MT5 探活", () -> mt5.session.health());
    }

    /** 用库内激活的 MT5 凭据真实登录（强制重登，供改了配置后立即生效）。 */
    public SessionInfo connect() {
        Map<String, Object> cfg = configService.activeDecrypted(ConfigType.MT5);
        if (cfg == null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "没有激活的 MT5 配置，无法登录；请先在配置层保存并激活一条 MT5 配置", 400);
        }
        return login(cfg);
    }

    /** 账户资金（结余 / 净值 / 可用保证金）。 */
    public AccountInfo account() {
        ensureLoggedIn();
        return call("取账户资金", mt5.account::getAccountInfo);
    }

    /** 实时报价（symbol 可为逻辑名，如 XAUUSD）。 */
    public Quote quote(String symbol) {
        ensureLoggedIn();
        return call("取 " + symbol + " 报价", () -> mt5.market.getQuote(symbol));
    }

    /** K 线（每根 OHLCV）。 */
    public List<KlineBar> klines(String symbol, String timeframe, int count) {
        ensureLoggedIn();
        return call("取 " + symbol + " K 线",
                () -> mt5.market.getKlines(symbol, timeframe, count));
    }

    /**
     * 按 [fromEpoch, toEpoch] 区间取 K 线（UTC epoch 秒）。
     *
     * <p>历史回填用区间而非偏移（终端偏移翻页取不到更早历史），见 {@code /klines/range}。
     */
    public List<KlineBar> klinesRange(String symbol, String timeframe, long fromEpoch, long toEpoch) {
        ensureLoggedIn();
        return call("取 " + symbol + " K 线区间",
                () -> mt5.market.getKlinesRange(symbol, timeframe, fromEpoch, toEpoch));
    }

    /** 持仓；symbol 为空 = 全部品种。 */
    public List<Position> positions(String symbol) {
        ensureLoggedIn();
        return call("取持仓", () -> (symbol == null || symbol.isBlank())
                ? mt5.account.getPositions()
                : mt5.account.getPositions(symbol));
    }

    /** 成交历史（UTC epoch 秒区间 [fromEpoch, toEpoch)）；对账用。 */
    public List<Deal> historyDeals(long fromEpoch, long toEpoch) {
        ensureLoggedIn();
        return call("取成交历史", () -> mt5.account.historyDeals(fromEpoch, toEpoch));
    }

    /** 券商可用品种清单（名称映射校验用）。 */
    public List<String> symbols(String query) {
        ensureLoggedIn();
        return call("取品种清单", () -> mt5.symbols.search(query));
    }

    /** 逻辑名 → 券商真实品种名。 */
    public String resolveSymbol(String logical) {
        return mt5.symbols.resolve(logical);
    }

    /** 一期默认品种的券商名（GOLD）。 */
    public String defaultSymbol() {
        return mt5.symbols.defaultSymbol();
    }

    // ==================== 写操作（M6 交易执行；仅 TradingService 调用） ====================

    /**
     * 市价开仓。{@code buy=true} 买入、{@code false} 卖出；sl/tp 为绝对价格，0 表示不设置。
     *
     * <p>注意：券商侧失败会以 {@code OrderResult.success=false} 正常返回（HTTP 200），
     * 由上层 {@code TradingService} 统一做返回码归一化，本方法只在传输层异常时抛错。
     */
    public OrderResult openMarket(String symbol, boolean buy, double volume, double sl, double tp) {
        ensureLoggedIn();
        return call("市价开仓", () -> buy
                ? mt5.trading.buyMarket(symbol, volume, sl, tp)
                : mt5.trading.sellMarket(symbol, volume, sl, tp));
    }

    /** 按 ticket 平仓。 */
    public OrderResult closePosition(String symbol, long ticket) {
        ensureLoggedIn();
        return call("平仓", () -> mt5.trading.closePosition(symbol, ticket));
    }

    /** 修改持仓 SL/TP；sl 或 tp 传 null 表示保留原值（避免把原有止盈/止损清空）。 */
    public OrderResult modifySlTp(String symbol, long ticket, Double sl, Double tp) {
        ensureLoggedIn();
        return call("修改 SL/TP", () -> mt5.trading.modifySlTp(symbol, ticket, sl, tp));
    }

    // ==================== 内部实现 ====================

    private void ensureLoggedIn() {
        if (!autoLogin || loggedInLogin != null) {
            return;
        }
        Map<String, Object> cfg = configService.activeDecrypted(ConfigType.MT5);
        if (cfg == null) {
            log.debug("无激活的 MT5 配置，沿用桥接服务自身会话（.env）");
            return;
        }
        if (toInt(cfg.get("mt5Login")) == null) {
            log.warn("激活的 MT5 配置缺少 mt5Login，跳过真实登录");
            return;
        }
        login(cfg);
    }

    private SessionInfo login(Map<String, Object> cfg) {
        Integer login = toInt(cfg.get("mt5Login"));
        String password = toStr(cfg.get("mt5Password"));
        String server = toStr(cfg.get("mt5Server"));
        SessionInfo info = call("MT5 登录",
                () -> mt5.session.connect(login, password, server));
        if (!info.ok()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "MT5 登录失败（账号 " + login + "）：" + info.error(), 503);
        }
        loggedInLogin = login;
        log.info("MT5 已用库内凭据登录: login={}, server={}", login, info.server());
        return info;
    }

    private <T> T call(String action, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Mt5Client.Mt5ApiException e) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    action + "失败（mt5_service 是否已启动？）: " + e.getMessage(), 503);
        }
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toStr(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}