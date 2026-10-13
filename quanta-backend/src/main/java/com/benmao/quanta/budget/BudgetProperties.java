package com.benmao.quanta.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token 预算与降级（M12）可调参数（设计 §8.4 / §18：阈值与开关一律集中配置、不写死）。
 *
 * <p>全部可在 {@code application.yml} 的 {@code quanta.budget.*} 下覆写。
 */
@Component
@ConfigurationProperties(prefix = "quanta.budget")
public class BudgetProperties {

    /** 总开关：关闭后不再自动检查与降级。 */
    private boolean enabled = true;

    /** 定时检查间隔（毫秒），驱动 {@code @Scheduled}（0 或负数 = 不按间隔检查）。 */
    private long checkIntervalMs = 60000;

    /** 次日自动重置的 cron（默认每日 00:00）。 */
    private String resetCron = "0 0 0 * * *";

    /** operation_audit.operator 取值（谁做的：系统级降级）。 */
    private String auditOperator = "BUDGET_GUARD";

    /** notify_log.notify_type 取值。 */
    private String notifyType = "TOKEN_DEGRADE";

    private Degrade degrade = new Degrade();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    public void setCheckIntervalMs(long checkIntervalMs) {
        this.checkIntervalMs = checkIntervalMs;
    }

    public String getResetCron() {
        return resetCron;
    }

    public void setResetCron(String resetCron) {
        this.resetCron = resetCron;
    }

    public String getAuditOperator() {
        return auditOperator;
    }

    public void setAuditOperator(String auditOperator) {
        this.auditOperator = auditOperator;
    }

    public String getNotifyType() {
        return notifyType;
    }

    public void setNotifyType(String notifyType) {
        this.notifyType = notifyType;
    }

    public Degrade getDegrade() {
        return degrade;
    }

    public void setDegrade(Degrade degrade) {
        this.degrade = degrade;
    }

    /** 降级处置参数。 */
    public static class Degrade {

        /** 保留仓位补挂 HARD SL 的时间距离（points；>0）。 */
        private double hardSlPoints = 300.0;

        /** 是否同时补挂 HARD TP（默认否：验收只要求「至少保留 HARD SL」）。 */
        private boolean ensureHardTp = false;

        /** 补挂 HARD TP 的时间距离（points；仅 {@link #ensureHardTp}=true 时生效）。 */
        private double hardTpPoints = 300.0;

        public double getHardSlPoints() {
            return hardSlPoints;
        }

        public void setHardSlPoints(double hardSlPoints) {
            this.hardSlPoints = hardSlPoints;
        }

        public boolean isEnsureHardTp() {
            return ensureHardTp;
        }

        public void setEnsureHardTp(boolean ensureHardTp) {
            this.ensureHardTp = ensureHardTp;
        }

        public double getHardTpPoints() {
            return hardTpPoints;
        }

        public void setHardTpPoints(double hardTpPoints) {
            this.hardTpPoints = hardTpPoints;
        }
    }
}
