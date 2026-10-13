package com.benmao.quanta.strategy;

import com.benmao.quanta.backtest.RuleStrategy;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.strategy.rule.RangeRsiBollRuleStrategy;
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

    private final StrategyProperties props;
    private final StrategyMapper strategyMapper;
    private final Map<String, RuleStrategy> rules;
    private final ObjectMapper json;

    public StrategyCatalogService(StrategyProperties props,
                                  StrategyMapper strategyMapper,
                                  List<RuleStrategy> strategies,
                                  ObjectMapper json) {
        this.props = props;
        this.strategyMapper = strategyMapper;
        this.json = json;
        Map<String, RuleStrategy> map = new LinkedHashMap<>();
        for (RuleStrategy s : strategies) {
            map.put(s.key(), s);
        }
        this.rules = map;
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
        return defs;
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
                strategyMapper.insert(s);
                log.info("策略导入：name={}, version={}, status=DRAFT, id={}", def.name(), VERSION, s.getId());
                out.add(s);
            } else if ("DRAFT".equals(existing.getStatus())) {
                existing.setPrompt(def.prompt());
                existing.setRegimeType(def.regimeType());
                existing.setTargetRr(BigDecimal.valueOf(def.targetRr()));
                existing.setIndicatorParams(def.indicatorParamsJson());
                existing.setDescription(def.description());
                strategyMapper.update(existing);
                out.add(existing);
            } else {
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
        RuleStrategy rule = rules.get(ruleKey);
        if (rule == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "缺少规则策略实现：" + ruleKey, 500);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleKey", ruleKey);
        m.putAll(rule.indicatorParams());
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "指标参数序列化失败：" + ruleKey, 500);
        }
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

    private static String trim(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 策略定义（导入 strategy 表 / 门禁解析规则键）。 */
    public record StrategyDefinition(String name,
                                     String ruleKey,
                                     String regimeType,
                                     double targetRr,
                                     String prompt,
                                     String indicatorParamsJson,
                                     String description) {
    }
}
