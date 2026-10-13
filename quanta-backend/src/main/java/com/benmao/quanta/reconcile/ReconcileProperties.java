package com.benmao.quanta.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对账 / 认领配置（{@code quanta.reconcile.*}）；全部集中配置，核心代码不写死。
 */
@Component
@ConfigurationProperties(prefix = "quanta.reconcile")
public class ReconcileProperties {

    /** 总开关：关闭后不启动认领、不做定时对账（/api/ops/reconcile 手工触发仍可用）。 */
    private boolean enabled = true;

    /** 定时对账 cron（默认每 5 分钟）。 */
    private String cron = "0 */5 * * * *";

    /** 启动时是否认领已有持仓（设计 §17 断线恢复）。 */
    private boolean claimOnStartup = true;

    /** 对账回看窗口（小时）：取最近 N 小时的 MT5 成交历史核对。 */
    private long lookbackHours = 24;

    /** operation_audit.operator（对账/认领留痕）。 */
    private String auditOperator = "RECONCILE";

    /** 盈亏核对容差（USD）；本地与 MT5 已实现盈亏差值超过该值才算差异。 */
    private double profitTolerance = 0.01;

    /** 发现差异时是否发告警。 */
    private boolean notifyOnDiff = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public boolean isClaimOnStartup() {
        return claimOnStartup;
    }

    public void setClaimOnStartup(boolean claimOnStartup) {
        this.claimOnStartup = claimOnStartup;
    }

    public long getLookbackHours() {
        return lookbackHours;
    }

    public void setLookbackHours(long lookbackHours) {
        this.lookbackHours = lookbackHours;
    }

    public String getAuditOperator() {
        return auditOperator;
    }

    public void setAuditOperator(String auditOperator) {
        this.auditOperator = auditOperator;
    }

    public double getProfitTolerance() {
        return profitTolerance;
    }

    public void setProfitTolerance(double profitTolerance) {
        this.profitTolerance = profitTolerance;
    }

    public boolean isNotifyOnDiff() {
        return notifyOnDiff;
    }

    public void setNotifyOnDiff(boolean notifyOnDiff) {
        this.notifyOnDiff = notifyOnDiff;
    }
}
