package com.benmao.mt5;

import java.util.Arrays;

/**
 * MT5 返回码归一化：把券商的数字 retcode 翻译成人可读的中文原因。
 *
 * <p>用途：RiskGuard / TradingService / Agent 拿到的失败原因必须是「可读且可纠错」的，
 * 而不是一串数字。典型：{@code 10018 = 市场休市}、{@code 10027 = 算法交易未打开}。
 */
public enum Mt5ErrorCode {

    SUCCESS(10009, "成功"),
    REQUOTE(10004, "报价已变（requote），请按最新价重试"),
    REJECT(10006, "请求被券商拒绝"),
    ORDER_PLACED(10008, "订单已提交（挂单）"),
    DONE_PARTIAL(10010, "部分成交"),
    HISTORY_TIMEOUT(10011, "请求处理超时"),
    INVALID_REQUEST(10013, "请求参数无效"),
    INVALID_VOLUME(10014, "手数无效或超出该品种限制"),
    INVALID_PRICE(10015, "价格无效"),
    INVALID_STOPS(10016, "止损/止盈价格无效（离市价太近或方向错误）"),
    MARKET_CLOSED(10018, "市场已休市（周末/节假日），请开盘后再试"),
    NO_MONEY(10019, "资金不足"),
    PRICE_CHANGED(10020, "价格已变，请重试"),
    NO_QUOTES(10021, "无报价"),
    SERVER_DISABLED(10024, "交易服务被禁用"),
    TRADE_DISABLED(10027, "MT5 终端的「算法交易 / Algo Trading」按钮未打开"),
    UNSUPPORTED_FILLING(10030, "券商不支持该填充模式（type_filling）"),
    POSITION_CLOSED(10036, "持仓已不存在（可能已被平掉）"),
    INVALID_ORDER_TYPE(10029, "无效的订单类型");

    private final int code;
    private final String message;

    Mt5ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 按返回码查枚举，未知返回码返回 null。 */
    public static Mt5ErrorCode from(Integer retcode) {
        if (retcode == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.code == retcode)
                .findFirst()
                .orElse(null);
    }

    /**
     * 返回码 → 可读原因。
     *
     * @param retcode 券商返回码，null 表示请求未送达（例如本地服务未启动）
     */
    public static String explain(Integer retcode) {
        if (retcode == null) {
            return "无返回码（请求未送达，检查本地 mt5_service 是否启动）";
        }
        Mt5ErrorCode code = from(retcode);
        return code != null ? code.message() : "retcode=" + retcode + "，详见 MT5 交易返回码表";
    }

    /** 是否为成功。 */
    public static boolean isSuccess(Integer retcode) {
        return retcode != null && retcode == SUCCESS.code;
    }
}