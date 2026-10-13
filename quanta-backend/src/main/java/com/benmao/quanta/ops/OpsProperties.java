package com.benmao.quanta.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运维层配置（{@code quanta.ops.*}）：人工介入审计口径 + 关键事件告警开关注入。
 *
 * <p>全部集中配置，核心代码不写死。
 */
@Component
@ConfigurationProperties(prefix = "quanta.ops")
public class OpsProperties {

    /** 是否发送关键事件告警（熔断/断线/对账差异/孤儿认领）；关闭则只留 notify_log 与日志。 */
    private boolean alertEnabled = true;

    /** 同类告警去抖窗口（秒）；0 = 不去抖（每次事件都告警）。 */
    private long alertCooldownSeconds = 300;

    /** 告警邮件的 notify_log.notify_type。 */
    private String alertNotifyType = "SYSTEM_ALERT";

    /** operation_audit.operator（人工手动操作）。 */
    private String manualAuditOperator = "MANUAL";

    /** 人工操作是否通过 T7 系统事件通知关联会话（让 AI 更新记忆）。 */
    private boolean manualNotifySession = true;

    public boolean isAlertEnabled() {
        return alertEnabled;
    }

    public void setAlertEnabled(boolean alertEnabled) {
        this.alertEnabled = alertEnabled;
    }

    public long getAlertCooldownSeconds() {
        return alertCooldownSeconds;
    }

    public void setAlertCooldownSeconds(long alertCooldownSeconds) {
        this.alertCooldownSeconds = alertCooldownSeconds;
    }

    public String getAlertNotifyType() {
        return alertNotifyType;
    }

    public void setAlertNotifyType(String alertNotifyType) {
        this.alertNotifyType = alertNotifyType;
    }

    public String getManualAuditOperator() {
        return manualAuditOperator;
    }

    public void setManualAuditOperator(String manualAuditOperator) {
        this.manualAuditOperator = manualAuditOperator;
    }

    public boolean isManualNotifySession() {
        return manualNotifySession;
    }

    public void setManualNotifySession(boolean manualNotifySession) {
        this.manualNotifySession = manualNotifySession;
    }
}
