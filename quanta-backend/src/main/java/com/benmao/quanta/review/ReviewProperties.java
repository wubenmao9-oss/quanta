package com.benmao.quanta.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 复盘层可调参数（设计 §12 复盘引擎 / §18 可调参数总表）。
 *
 * <p>铁律：阈值与开关一律<b>集中配置</b>，核心代码不得写死。全部可在 {@code application.yml}
 * 的 {@code quanta.review.*} 下覆写。
 */
@Component
@ConfigurationProperties(prefix = "quanta.review")
public class ReviewProperties {

    /** 复盘总开关；关闭后平仓不再自动出报告（统计接口仍可调用）。 */
    private boolean enabled = true;

    /** 列表默认条数。 */
    private int listDefaultLimit = 20;

    /** 初始权益（USD），用于周期统计的净值/回撤口径。 */
    private double initialEquity = 500.0;

    /** 「早平」判定：盈利单持仓短于该秒数即计入早平归因。 */
    private int earlyCloseSec = 120;

    /** 「晚平」判定：亏损单持仓达到该秒数即计入晚平归因。 */
    private int lateCloseSec = 3600;

    /** 周期统计默认回看天数（since 未显式给出时）。 */
    private int defaultLookbackDays = 30;

    /** 策略评价「样本充分」的最小已平仓样本量（不足只提示、不据此切换策略）。 */
    private int minSampleForVerdict = 30;

    /** 策略评价「达标」的最小盈亏比（与净盈亏为正同时满足才算达标）。 */
    private double minProfitFactorForPass = 1.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getListDefaultLimit() {
        return listDefaultLimit;
    }

    public void setListDefaultLimit(int listDefaultLimit) {
        this.listDefaultLimit = listDefaultLimit;
    }

    public double getInitialEquity() {
        return initialEquity;
    }

    public void setInitialEquity(double initialEquity) {
        this.initialEquity = initialEquity;
    }

    public int getEarlyCloseSec() {
        return earlyCloseSec;
    }

    public void setEarlyCloseSec(int earlyCloseSec) {
        this.earlyCloseSec = earlyCloseSec;
    }

    public int getLateCloseSec() {
        return lateCloseSec;
    }

    public void setLateCloseSec(int lateCloseSec) {
        this.lateCloseSec = lateCloseSec;
    }

    public int getDefaultLookbackDays() {
        return defaultLookbackDays;
    }

    public void setDefaultLookbackDays(int defaultLookbackDays) {
        this.defaultLookbackDays = defaultLookbackDays;
    }

    public int getMinSampleForVerdict() {
        return minSampleForVerdict;
    }

    public void setMinSampleForVerdict(int minSampleForVerdict) {
        this.minSampleForVerdict = minSampleForVerdict;
    }

    public double getMinProfitFactorForPass() {
        return minProfitFactorForPass;
    }

    public void setMinProfitFactorForPass(double minProfitFactorForPass) {
        this.minProfitFactorForPass = minProfitFactorForPass;
    }
}
