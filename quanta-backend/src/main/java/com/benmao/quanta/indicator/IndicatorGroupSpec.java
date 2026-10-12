package com.benmao.quanta.indicator;

import java.util.List;
import java.util.Map;

/**
 * 指标组（设计 §10）：多条规则按 {@link Logic} 联合命中，如「RSI 超卖 + 放量 + 布林下轨」。
 *
 * <p>每条规则形如 {@code 左操作数 OP 右操作数}，操作数可为常量、价格、或某个指标的某个输出字段。
 *
 * @param rules 规则列表
 * @param logic 组合逻辑，默认 {@link Logic#AND}
 */
public record IndicatorGroupSpec(List<Rule> rules, Logic logic) {

    public IndicatorGroupSpec {
        if (logic == null) {
            logic = Logic.AND;
        }
    }

    /** 组合逻辑。 */
    public enum Logic {AND, OR}

    /** 比较运算符（CROSS_* 需前一根 K 线）。 */
    public enum Op {GT, LT, GTE, LTE, CROSS_UP, CROSS_DOWN}

    /** 操作数取值方式。 */
    public enum Kind {CONST, PRICE, INDICATOR}

    /** 价格字段。 */
    public enum PriceField {OPEN, HIGH, LOW, CLOSE}

    /** 指标引用：指标 + 参数 + 输出字段（BOLL 的 mid/upper/lower、MACD 的 dif/dea/hist 等）。 */
    public record Ref(IndicatorType indicator, Map<String, Object> params, String field) {}

    /** 规则左/右操作数；走静态工厂构造，避免三个载荷同时出现。 */
    public record Operand(Kind kind, Double constant, PriceField priceField, Ref ref) {

        public static Operand ofConstant(double value) {
            return new Operand(Kind.CONST, value, null, null);
        }

        public static Operand ofPrice(PriceField field) {
            return new Operand(Kind.PRICE, null, field, null);
        }

        public static Operand ofIndicator(Ref ref) {
            return new Operand(Kind.INDICATOR, null, null, ref);
        }
    }

    /** 单条规则：左操作数 OP 右操作数；{@code name} 用于回执中标识该条是否命中。 */
    public record Rule(String name, Operand lhs, Op op, Operand rhs) {}

    /** 评估结果：整体是否命中 + 逐条命中情况。 */
    public record Evaluation(boolean matched, Map<String, Boolean> perRule) {}
}