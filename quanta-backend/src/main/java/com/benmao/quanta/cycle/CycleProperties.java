package com.benmao.quanta.cycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实盘 cycle 执行器集中配置（前缀 {@code quanta.cycle}）。
 *
 * <p><b>不写死原则</b>：执行器的全部可调量集中于此；核心状态机不得出现魔法数字。
 *
 * <p><b>安全默认</b>：{@code enabled=false}（总开关，默认关闭）+ {@code dryRun=true}（默认只演练不下单）。
 * 两者都需显式改为 {@code enabled=true, dryRun=false} 才会真实下单。
 *
 * <p>本执行器只实现 <b>马丁（MARTIN）子模式</b>的实盘；网格双边挂单需 P3（mt5_service 挂单桥接）后就绪，
 * 故 {@code regime=GRID} 时执行器不开新轮（避免无挂单能力时错误开单）。
 */
@Component
@ConfigurationProperties(prefix = "quanta.cycle")
public class CycleProperties {

    /** 总开关：false 时整个执行器不动作（默认关）。 */
    private boolean enabled = false;
    /** 演练开关：true 时状态机照跑但<b>不真实下单、不落库</b>（只打日志），用于验证逻辑。 */
    private boolean dryRun = true;
    /** 目标品种（券商真实名；执行器按需 resolveSymbol）。 */
    private String symbol = "GOLD";
    /** 形态判定所用周期。 */
    private String timeframe = "M5";
    /** 取 K 线根数（须 ≥ 形态判定预热期 max(emaSlow, 2×adxPeriod)）。 */
    private int klineCount = 300;
    /** 对应策略库中的策略名（用于查 id + 校验状态须为 DRILL/ACTIVE）。 */
    private String strategyName = "笨猫马丁-网格双边";
    /** 会话类型（AUTO/MANUAL）。 */
    private String sessionType = "AUTO";
    /** 连续开首腿失败到该次数后进入冷却。 */
    private int startFailLimit = 3;
    /** 开首腿冷却时长（分钟）。 */
    private int startCooldownMinutes = 5;
    /** 是否允许用 AI 方向提案（P2 默认关：用规则方向，避免改动 AgentService）。 */
    private boolean allowAiDirection = false;
    /** AI 方向提案的最低置信度（仅 allowAiDirection=true 时生效）。 */
    private double minStartConfidence = 0.6;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public int getKlineCount() {
        return klineCount;
    }

    public void setKlineCount(int klineCount) {
        this.klineCount = klineCount;
    }

    public String getStrategyName() {
        return strategyName;
    }

    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
    }

    public String getSessionType() {
        return sessionType;
    }

    public void setSessionType(String sessionType) {
        this.sessionType = sessionType;
    }

    public int getStartFailLimit() {
        return startFailLimit;
    }

    public void setStartFailLimit(int startFailLimit) {
        this.startFailLimit = startFailLimit;
    }

    public int getStartCooldownMinutes() {
        return startCooldownMinutes;
    }

    public void setStartCooldownMinutes(int startCooldownMinutes) {
        this.startCooldownMinutes = startCooldownMinutes;
    }

    public boolean isAllowAiDirection() {
        return allowAiDirection;
    }

    public void setAllowAiDirection(boolean allowAiDirection) {
        this.allowAiDirection = allowAiDirection;
    }

    public double getMinStartConfidence() {
        return minStartConfidence;
    }

    public void setMinStartConfidence(double minStartConfidence) {
        this.minStartConfidence = minStartConfidence;
    }
}
