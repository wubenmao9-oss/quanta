package com.benmao.quanta.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 中枢可调参数（设计 §3 返回契约 / §6 调度 / §18 可调参数总表）。
 *
 * <p>铁律：阈值与开关一律<b>集中配置</b>，核心代码不得写死。全部可在 {@code application.yml}
 * 的 {@code quanta.agent.*} 下覆写。
 *
 * <p>其中 {@code confidence-threshold} 对应设计 §3 的「置信度门控」（默认 0.6），
 * {@code confidence < 阈值} 时强制把动作降级为 {@code WAIT}。
 */
@Component
@ConfigurationProperties(prefix = "quanta.agent")
public class AgentProperties {

    /** Agent 中枢总开关；关闭后拒绝一切决策请求。 */
    private boolean enabled = true;

    /** 置信度门控阈值（0~1）；{@code confidence < 阈值} → 强制降级 WAIT。 */
    private double confidenceThreshold = 0.6;

    /** 默认品种（券商真实名，一期 GOLD）。 */
    private String defaultSymbol = "GOLD";

    /** 默认 K 线周期。 */
    private String defaultTimeframe = "M5";

    /** 工具/快照默认拉取的 K 线根数。 */
    private int klineCount = 200;

    /** 快照中回传给模型的最近 K 线根数（控制 token）。 */
    private int snapshotBarCount = 20;

    /** 单次大模型调用超时（秒）。 */
    private int modelTimeoutSeconds = 60;

    /** 单次大模型输出 token 上限；空 = 不限制。 */
    private Integer maxTokens = null;

    /** 模型未给手数时使用的默认手数。 */
    private double defaultVolume = 0.01;

    /** 决策日志列表默认条数。 */
    private int decisionListDefaultLimit = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public String getDefaultSymbol() {
        return defaultSymbol;
    }

    public void setDefaultSymbol(String defaultSymbol) {
        this.defaultSymbol = defaultSymbol;
    }

    public String getDefaultTimeframe() {
        return defaultTimeframe;
    }

    public void setDefaultTimeframe(String defaultTimeframe) {
        this.defaultTimeframe = defaultTimeframe;
    }

    public int getKlineCount() {
        return klineCount;
    }

    public void setKlineCount(int klineCount) {
        this.klineCount = klineCount;
    }

    public int getSnapshotBarCount() {
        return snapshotBarCount;
    }

    public void setSnapshotBarCount(int snapshotBarCount) {
        this.snapshotBarCount = snapshotBarCount;
    }

    public int getModelTimeoutSeconds() {
        return modelTimeoutSeconds;
    }

    public void setModelTimeoutSeconds(int modelTimeoutSeconds) {
        this.modelTimeoutSeconds = modelTimeoutSeconds;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getDefaultVolume() {
        return defaultVolume;
    }

    public void setDefaultVolume(double defaultVolume) {
        this.defaultVolume = defaultVolume;
    }

    public int getDecisionListDefaultLimit() {
        return decisionListDefaultLimit;
    }

    public void setDecisionListDefaultLimit(int decisionListDefaultLimit) {
        this.decisionListDefaultLimit = decisionListDefaultLimit;
    }
}
