package com.benmao.quanta.strategy;

import com.benmao.quanta.backtest.BasketStrategy;
import com.benmao.quanta.backtest.BasketStrategyProperties;
import com.benmao.quanta.backtest.RuleStrategy;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.strategy.rule.BenmaoGoldRuleStrategy;
import com.benmao.quanta.strategy.rule.BenmaoMartinGridRuleStrategy;
import com.benmao.quanta.strategy.rule.RangeRsiBollRuleStrategy;
import com.benmao.quanta.strategy.rule.ScalpEmaRuleStrategy;
import com.benmao.quanta.strategy.rule.TinyScalpRuleStrategy;
import com.benmao.quanta.strategy.rule.TrendEmaAdxRuleStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策略库目录（P0清单 M9 产出）：把首批<b>趋势 1 份 + 震荡 1 份</b>策略（各自带 Prompt / regime_type /
 * target_rr / indicator_params）<b>幂等</b>导入 {@code strategy} 表，初始 {@code status=DRAFT}。
 *
 * <p>安全约束（设计 §14.4）：只导入草稿；已存在且非 DRAFT（ACTIVE/CANDIDATE/ARCHIVED）的行<b>不覆盖</b>，
 * 避免误改已批准上线的策略。参数与 Prompt 均来自集中配置。
 */
@Service
public class StrategyCatalogService {

    private static final Logger log = LoggerFactory.getLogger(StrategyCatalogService.class);

    /** 策略版本（首批均为 v1；唯一键 (name, version)）。 */
    private static final int VERSION = 1;

    public static final String NAME_TREND = "趋势-EMA_ADX";
    public static final String NAME_RANGE = "震荡-RSI_BOLL";
    public static final String NAME_SCALP = "演练-高频_SCALP";
    public static final String NAME_TINY = "演练-高频薅点";
    public static final String NAME_BENMAO_GOLD = "笨猫黄金";
    public static final String NAME_MARTIN_GRID = "笨猫马丁-网格双边";

    private final StrategyProperties props;
    private final BasketStrategyProperties basketProps;
    private final StrategyMapper strategyMapper;
    private final Map<String, RuleStrategy> rules;
    private final Map<String, BasketStrategy> baskets;
    private final ObjectMapper json;

    public StrategyCatalogService(StrategyProperties props,
                                  BasketStrategyProperties basketProps,
                                  StrategyMapper strategyMapper,
                                  List<RuleStrategy> strategies,
                                  List<BasketStrategy> basketStrategies,
                                  ObjectMapper json) {
        this.props = props;
        this.basketProps = basketProps;
        this.strategyMapper = strategyMapper;
        this.json = json;
        Map<String, RuleStrategy> map = new LinkedHashMap<>();
        for (RuleStrategy s : strategies) {
            map.put(s.key(), s);
        }
        this.rules = map;
        Map<String, BasketStrategy> bmap = new LinkedHashMap<>();
        for (BasketStrategy s : basketStrategies) {
            bmap.put(s.key(), s);
        }
        this.baskets = bmap;
    }

    /** 首批策略定义（趋势 + 震荡）。 */
    public List<StrategyDefinition> definitions() {
        List<StrategyDefinition> defs = new ArrayList<>();
        StrategyProperties.Trend t = props.getTrend();
        defs.add(new StrategyDefinition(NAME_TREND, TrendEmaAdxRuleStrategy.KEY, "TREND", t.getTargetRr(),
                trendPrompt(t), indicatorParamsJson(TrendEmaAdxRuleStrategy.KEY),
                "首批趋势策略：EMA 金叉死叉 + ADX 趋势过滤 + ATR 止损（日内~隔日，GOLD）"));
        StrategyProperties.Range r = props.getRange();
        defs.add(new StrategyDefinition(NAME_RANGE, RangeRsiBollRuleStrategy.KEY, "RANGE", r.getTargetRr(),
                rangePrompt(r), indicatorParamsJson(RangeRsiBollRuleStrategy.KEY),
                "首批震荡策略：RSI 超买超卖 + 布林带回归 + ATR 止损（日内~隔日，GOLD）"));
        StrategyProperties.Scalp s = props.getScalp();
        defs.add(new StrategyDefinition(NAME_SCALP, ScalpEmaRuleStrategy.KEY, "SCALP", s.getTargetRr(),
                scalpPrompt(s), indicatorParamsJson(ScalpEmaRuleStrategy.KEY),
                "演练高频策略：EMA 金叉死叉（无趋势过滤）+ ATR 止损，仅用于验证 AI 能否真实下单，禁止实盘资金"));
        StrategyProperties.Tiny tz = props.getTiny();
        defs.add(new StrategyDefinition(NAME_TINY, TinyScalpRuleStrategy.KEY, "SCALP", tz.getTargetRr(),
                tinyPrompt(tz), indicatorParamsJson(TinyScalpRuleStrategy.KEY),
                "演练高频薅点策略：EMA 判方向（无趋势过滤）+ 固定 " + trim(tz.getSlDistanceUsd()) + "USD 止损 / "
                        + trim(tz.getSlDistanceUsd() * tz.getTargetRr()) + "USD 止盈，仅供演练验证 AI 能否真实高频下单，禁止实盘资金"));
        StrategyProperties.BenmaoGold bg = props.getBenmaoGold();
        defs.add(new StrategyDefinition(NAME_BENMAO_GOLD, BenmaoGoldRuleStrategy.KEY, "TREND", bg.getTargetRr(),
                benmaoGoldPrompt(bg), indicatorParamsJson(BenmaoGoldRuleStrategy.KEY),
                "笨猫黄金：单仓趋势跟随（改写自 MT5 源码「谷歌黄金」）——EMA" + bg.getEmaPeriod() + " 定方向 + RSI("
                        + bg.getRsiPeriod() + ") 动量 + ADX(" + bg.getAdxPeriod() + ")>" + trim(bg.getAdxThreshold())
                        + " 过滤 + 时段 " + bg.getStartHour() + "–" + bg.getEndHour() + " 点 + ATR("
                        + bg.getAtrPeriod() + ")×" + trim(bg.getAtrStopMult()) + " 止损 / 盈亏比 "
                        + trim(bg.getTargetRr()) + "（GOLD）"));
        BasketStrategyProperties.Martin mg = basketProps.getMartin();
        defs.add(new StrategyDefinition(NAME_MARTIN_GRID, BenmaoMartinGridRuleStrategy.KEY, "RANGE", mg.getBasketTargetRr(),
                martinGridPrompt(mg), indicatorParamsJson(BenmaoMartinGridRuleStrategy.KEY),
                "笨猫马丁-网格双边（BASKET）：形态自适应——趋势（EMA" + basketProps.getSession().getEmaFast() + "/"
                        + basketProps.getSession().getEmaSlow() + " 分离 + ADX>"
                        + trim(basketProps.getSession().getAdxTrendMin()) + "）走马丁（第 1 腿顺 EMA 慢线开、"
                        + "逆势每 " + trim(mg.getAddLegDistancePoints() / 100.0) + "USD 补腿、" + lotDesc(mg)
                        + "、整轮按加权均价止盈）；震荡（EMA 重合 + ADX≤"
                        + trim(basketProps.getSession().getAdxGridMax()) + "）走双边网格（BUY_STOP/SELL_STOP 各 "
                        + basketProps.getGrid().getLegsPerSide() + " 腿、逐腿止盈损）；含日/总亏损锁闸门。"
                        + "仅 GOLD、一次一轮；AI 只决定是否启动本轮及方向，机械步骤由执行器完成（BASKET）", "BASKET"));
        return defs;
    }

    /** 策略族：单仓（SINGLE_LEG）或多腿/网格（BASKET）。 */
    public String familyOf(Strategy strategy) {
        return familyOfRuleKey(ruleKeyOf(strategy));
    }

    /** 按规则键判定策略族（未知键 → 单仓族）。 */
    public String familyOfRuleKey(String ruleKey) {
        return baskets.containsKey(ruleKey) ? "BASKET" : "SINGLE_LEG";
    }

    /** 按策略名解析定义（门禁用名称 → 规则键映射）。 */
    public Optional<StrategyDefinition> byName(String name) {
        return definitions().stream().filter(d -> d.name().equals(name)).findFirst();
    }

    /** 幂等导入：返回导入后的两行策略记录。 */
    public List<Strategy> ensureImported() {
        List<Strategy> out = new ArrayList<>();
        for (StrategyDefinition def : definitions()) {
            Strategy existing = strategyMapper.findByNameVersion(def.name(), VERSION);
            if (existing == null) {
                Strategy s = new Strategy();
                s.setName(def.name());
                s.setPrompt(def.prompt());
                s.setVersion(VERSION);
                s.setStatus("DRAFT");
                s.setOrigin("MANUAL");
                s.setApproved(false);
                s.setRegimeType(def.regimeType());
                s.setTargetRr(BigDecimal.valueOf(def.targetRr()));
                s.setIndicatorParams(def.indicatorParamsJson());
                s.setDescription(def.description());
                s.setFamily(def.family());
                strategyMapper.insert(s);
                log.info("策略导入：name={}, version={}, status=DRAFT, family={}, id={}",
                        def.name(), VERSION, def.family(), s.getId());
                out.add(s);
            } else if ("DRAFT".equals(existing.getStatus())) {
                existing.setPrompt(def.prompt());
                existing.setRegimeType(def.regimeType());
                existing.setTargetRr(BigDecimal.valueOf(def.targetRr()));
                existing.setIndicatorParams(def.indicatorParamsJson());
                existing.setDescription(def.description());
                existing.setFamily(def.family());
                strategyMapper.update(existing);
                out.add(existing);
            } else {
                existing.setFamily(def.family());
                log.info("策略已存在且非 DRAFT（status={}），保持不覆盖：name={}", existing.getStatus(), def.name());
                out.add(existing);
            }
        }
        return out;
    }

    /** 取策略的规则键；未知策略名 → 抛错并回执可选策略。 */
    public String ruleKeyOf(Strategy strategy) {
        return byName(strategy.getName()).map(StrategyDefinition::ruleKey)
                .orElseThrow(() -> new BizException(ResultCode.PARAM_ERROR.code(),
                        "未知策略名称：" + strategy.getName() + "，可选：" + definitions().stream()
                                .map(StrategyDefinition::name).toList(), 400));
    }

    private String indicatorParamsJson(String ruleKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleKey", ruleKey);
        RuleStrategy rule = rules.get(ruleKey);
        BasketStrategy basket = baskets.get(ruleKey);
        if (rule != null) {
            m.putAll(rule.indicatorParams());
        } else if (basket != null) {
            m.put("family", "BASKET");
            m.putAll(basket.indicatorParams());
        } else {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "缺少策略实现：" + ruleKey, 500);
        }
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "指标参数序列化失败：" + ruleKey, 500);
        }
    }

    /** 手数口径描述：{@code multiplier=1.0} 为等手数（不马丁），否则为马丁倍投。 */
    private static String lotDesc(BasketStrategyProperties.Martin m) {
        return m.getMultiplier() == 1.0 ? "每腿等手数（不马丁）" : "手数 ×" + trim(m.getMultiplier()) + "（马丁倍投）";
    }

    /**
     * 「笨猫马丁-网格双边」策略 Prompt：明确 AI 只做「是否启动本轮/方向」的一次决策，机械步骤（补腿、
     * 手数递增、整轮均价止盈、网格挂单）由确定性执行器完成，AI 永不直接下单（设计 §16 混合模式）。
     */
    private String martinGridPrompt(BasketStrategyProperties.Martin m) {
        BasketStrategyProperties.Session s = basketProps.getSession();
        BasketStrategyProperties.Grid g = basketProps.getGrid();
        BasketStrategyProperties.Locks l = basketProps.getLocks();
        return "【多腿策略 · 笨猫马丁-网格双边 BASKET】你是 GOLD 形态自适应交易员，一次仅运行一轮。"
                + "形态判定：以 EMA(" + s.getEmaFast() + "/" + s.getEmaSlow() + ") 相对差与 ADX(" + s.getAdxPeriod()
                + ") 分类——ADX≤" + trim(s.getAdxGridMax()) + " 且 EMA 快慢几乎重合 → 震荡（GRID）；ADX≥"
                + trim(s.getAdxTrendMin()) + " 且 EMA 分离 → 趋势（MARTIN）；其余 → 观望（WAIT）。"
                + "决策（只需输出本轮是否启动及方向，其余机械步骤交给执行器）："
                + "趋势 → 以现价相对 EMA" + s.getEmaSlow() + " 定方向开马丁第 1 腿；震荡 → 交由双边 STOP 挂单自动触发建网格轮。"
                + "机械规则（执行器自动执行、无需 AI 下单）：马丁逆势每 " + trim(m.getAddLegDistancePoints() / 100.0)
                + "USD 补一腿、" + lotDesc(m) + "、最多 " + m.getMaxLevels()
                + " 腿、整轮按加权均价止盈 " + trim(m.getTpPoints() / 100.0) + "USD（盈亏比 "
                + trim(m.getBasketTargetRr()) + "）、单腿仅挂 " + trim(m.getSlPoints() / 100.0)
                + "USD 黑障止损（真正的止损是整轮亏损%硬止损，已达阈即整轮全平）；网格每 "
                + trim(g.getStepPoints() / 100.0) + "USD 一腿、每侧最多 "
                + g.getLegsPerSide() + " 腿、逐腿止盈损。"
                + "风控：单轮最大亏损 " + trim(l.getMaxSingleLossPct()) + "% 净值（硬止损）、日亏 " + trim(l.getMaxDailyLossPct())
                + "% 或总亏 " + trim(l.getMaxTotalLossPct()) + "% 触发锁闸门（锁定 " + l.getLockHours() + " 小时）。"
                + "纪律：仅 GOLD、时段 " + s.getStartHour() + "–" + s.getEndHour() + ":00、锁定期不新开、参数以 indicator_params 为准。";
    }

    private static String trendPrompt(StrategyProperties.Trend p) {
        return "【趋势策略 · TREND】你是 GOLD 日内趋势跟随交易员，持仓最长 1 天。"
                + "研判：以 EMA(快" + p.getEmaFast() + "/慢" + p.getEmaSlow() + ") 判定趋势方向，"
                + "以 ADX(" + p.getAdxPeriod() + ") ≥ " + trim(p.getAdxThreshold()) + " 确认趋势强度。"
                + "决策：EMA 金叉且 ADX≥阈值 → 开多；EMA 死叉且 ADX≥阈值 → 开空；ADX<阈值（震荡期）→ 观望。"
                + "风控：止损 = ATR(" + p.getAtrPeriod() + ")×" + trim(p.getAtrStopMult()) + "；"
                + "止盈 = 止损×" + trim(p.getTargetRr()) + "（目标盈亏比 " + trim(p.getTargetRr()) + "）；"
                + "单笔风险 2% 净值、硬止损必挂。纪律：一次仅一笔、不逆势、参数以 indicator_params 为准。";
    }

    private static String rangePrompt(StrategyProperties.Range p) {
        return "【震荡策略 · RANGE】你是 GOLD 日内区间回归交易员，持仓最长 1 天。"
                + "研判：以布林带(BOLL " + p.getBollPeriod() + "," + trim(p.getBollK()) + ") 判定区间上下轨，"
                + "以 RSI(" + p.getRsiPeriod() + ") 确认超买超卖。"
                + "决策：价格触下轨且 RSI<" + trim(p.getRsiOversold()) + " → 开多；触上轨且 RSI>"
                + trim(p.getRsiOverbought()) + " → 开空；未触轨 → 观望。"
                + "风控：止损 = ATR(" + p.getAtrPeriod() + ")×" + trim(p.getAtrStopMult()) + "；"
                + "止盈 = 止损×" + trim(p.getTargetRr()) + "（目标盈亏比 " + trim(p.getTargetRr()) + "，高胜率低盈亏比）；"
                + "单笔风险 2% 净值、硬止损必挂。纪律：仅做区间回归，突破走趋势时应停手；一次仅一笔。";
    }

    /**
     * 演练高频策略 Prompt：核心是「无持仓就必须开仓」，用于验证 AI 能否真实下单。
     * 这是唯一一个<b>强制开仓</b>的提示词，仅在「演练模式」下生效（策略状态为 DRILL）。
     */
    private static String scalpPrompt(StrategyProperties.Scalp p) {
        return "【演练高频策略 · SCALP】你是 GOLD 分钟级高频交易员。"
                + "本策略唯一目的是【验证决策链路能真实下单】，允许高频试错，不以盈利为考核，但务必每单都挂止损。"
                + "研判：用 EMA(快" + p.getEmaFast() + "/慢" + p.getEmaSlow() + ") 判定方向——快线在慢线上方 = 多头排列，"
                + "快线在慢线下方 = 空头排列。"
                + "决策（强制）：若当前【无持仓】，必须立即开仓——多头排列 → OPEN BUY，空头排列 → OPEN SELL；"
                + "若快慢线重合/方向不明，则与最近一根 K 线涨跌方向一致开仓。"
                + "严禁以「趋势强度不足 / 震荡 / ADX 偏低 / 等更清晰信号 / 观望」为由输出 WAIT。"
                + "若当前【已有持仓】，则本次输出 WAIT（一次仅一笔，不重复开仓、不逆势加仓）。"
                + "风控：OPEN 必须带 sl = 现价 ∓ ATR(" + p.getAtrPeriod() + ")×" + trim(p.getAtrStopMult()) + "（多头取下方、空头取上方），"
                + "tp = 现价 ± 止损距离×" + trim(p.getTargetRr()) + "，手数用默认 0.01；硬止损必挂。"
                + "confidence：请给出 ≥ 0.7 的置信度（本策略必须通过置信度门控，这是演练要求）。";
    }

    /**
     * 演练高频薅点策略 Prompt：与 {@link #scalpPrompt} 同为「无持仓就必须开仓」，
     * 但止损止盈用<b>固定美元距离</b>（GOLD 上止损 {@code slDistanceUsd} USD、止盈 = 止损 × targetRr），
     * 定位是「一小时高频薅小额、验证 AI 能否真实下单」。仅在演练模式（策略状态为 DRILL）下生效。
     */
    private static String tinyPrompt(StrategyProperties.Tiny p) {
        double sl = p.getSlDistanceUsd();
        double tp = p.getSlDistanceUsd() * p.getTargetRr();
        return "【演练高频薅点策略 · TINY_SCALP】你是 GOLD 分钟级高频薅点交易员。"
                + "本策略唯一目的是【验证决策链路能真实高频下单，一小时不少于 5 单】，允许高频试错，不以盈利为考核，但务必每单都挂止损。"
                + "研判：用 EMA(快" + p.getEmaFast() + "/慢" + p.getEmaSlow() + ") 判定方向——快线在慢线上方 = 多头排列，"
                + "快线在慢线下方 = 空头排列。"
                + "决策（强制）：若当前【无持仓】，必须立即开仓——多头排列 → OPEN BUY，空头排列 → OPEN SELL；"
                + "若快慢线重合/方向不明，则与最近一根 K 线涨跌方向一致开仓。"
                + "严禁以「趋势强度不足 / 震荡 / ADX 偏低 / 等更清晰信号 / 观望」为由输出 WAIT。"
                + "若当前【已有持仓】，则本次输出 WAIT（一次仅一笔，不重复开仓、不逆势加仓）。"
                + "风控：OPEN 必须带 sl = 现价 ∓ " + trim(sl) + "（GOLD 固定 " + trim(sl) + " 美元，多头取下方、空头取上方），"
                + "tp = 现价 ± " + trim(tp) + "（固定薅 " + trim(tp) + " 美元），"
                + "tpMode = HARD（券商侧硬止盈，到价自动平仓以便尽快腾出仓位），手数用默认 0.01；硬止损必挂。"
                + "confidence：请给出 ≥ " + trim(p.getConfidenceFloor()) + " 的置信度（本策略必须通过置信度门控，这是演练要求）。";
    }

    /**
     * 笨猫黄金策略 Prompt（改写自 MT5 源码「谷歌黄金，修改了动态止盈损，动态仓位，.mq5」）：
     * 以 EMA52 定趋势方向、RSI 相对 50 确认动量、ADX 过滤趋势强度，并限定 08:00–20:00 时段；
     * 止损 = ATR×2、止盈 = 止损×2（等价源码 SL=2×ATR / TP=4×ATR），一次仅一笔。
     */
    private static String benmaoGoldPrompt(StrategyProperties.BenmaoGold p) {
        return "【趋势策略 · 笨猫黄金】你是 GOLD 单仓趋势跟随交易员。"
                + "研判：以 EMA(" + p.getEmaPeriod() + ") 为趋势基准——现价在其上方为多头、下方为空头；"
                + "以 RSI(" + p.getRsiPeriod() + ") 相对 " + trim(p.getRsiMidline()) + " 确认动量；"
                + "以 ADX(" + p.getAdxPeriod() + ") > " + trim(p.getAdxThreshold()) + " 确认趋势强度。"
                + "决策：现价>EMA" + p.getEmaPeriod() + " 且 RSI>" + trim(p.getRsiMidline()) + " 且 ADX>"
                + trim(p.getAdxThreshold()) + " → 开多；现价<EMA" + p.getEmaPeriod() + " 且 RSI<"
                + trim(p.getRsiMidline()) + " 且 ADX>" + trim(p.getAdxThreshold()) + " → 开空；"
                + "ADX 不足（震荡期）或不在 " + p.getStartHour() + ":00–" + p.getEndHour() + ":00 时段内 → 观望。"
                + "风控：OPEN 必须带 sl = 现价 ∓ ATR(" + p.getAtrPeriod() + ")×" + trim(p.getAtrStopMult())
                + "（多头取下方、空头取上方），tp = 现价 ± 止损距离×" + trim(p.getTargetRr()) + "；"
                + "单笔风险 2% 净值、硬止损必挂。纪律：一次仅一笔、持仓期间不新开、参数以 indicator_params 为准。"
                + "（源码的 1.5×ATR 保本移动止损与按余额×风险% 的动态手数，本策略不覆盖。）";
    }

    private static String trim(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 策略定义（导入 strategy 表 / 门禁解析规则键）。family=SINGLE_LEG/BASKET。 */
    public record StrategyDefinition(String name,
                                     String ruleKey,
                                     String regimeType,
                                     double targetRr,
                                     String prompt,
                                     String indicatorParamsJson,
                                     String description,
                                     String family) {

        /** 兼容构造：默认单仓族。 */
        public StrategyDefinition(String name, String ruleKey, String regimeType, double targetRr,
                                  String prompt, String indicatorParamsJson, String description) {
            this(name, ruleKey, regimeType, targetRr, prompt, indicatorParamsJson, description, "SINGLE_LEG");
        }
    }
}
