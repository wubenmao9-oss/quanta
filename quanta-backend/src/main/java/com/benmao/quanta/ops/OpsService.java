package com.benmao.quanta.ops;

import com.benmao.quanta.schedule.TriggerEngine;
import com.benmao.quanta.state.RunMode;
import com.benmao.quanta.state.SystemStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 运维服务（M13）：三态运行模式切换 + 状态观测（设计 §8.1 / §17）。
 *
 * <p>三态 {@link RunMode} 落 {@code sys_state.run_mode}（重启后保持）；每次人工切换都写
 * {@code operation_audit} 并通知关联会话（与人工操作同一套协议）。
 */
@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    private final SystemStateService stateService;
    private final OperationAuditService auditService;
    private final TriggerEngine triggerEngine;
    private final OpsProperties props;

    public OpsService(SystemStateService stateService,
                      OperationAuditService auditService,
                      TriggerEngine triggerEngine,
                      OpsProperties props) {
        this.stateService = stateService;
        this.auditService = auditService;
        this.triggerEngine = triggerEngine;
        this.props = props;
    }

    /** 当前运行状态快照（运行模式 / 降级态 / 当日 Token 用量）。 */
    public OpsState state() {
        return new OpsState(stateService.runMode().name(), stateService.isDegraded(),
                stateService.tokenUsedToday(), stateService.tokenDate());
    }

    /**
     * 切换运行模式并落库（设计 §8.1）；写审计 + 通知会话（幂等：相同模式不改库）。
     *
     * @return 是否发生了变化
     */
    public boolean setMode(String mode) {
        RunMode before = stateService.runMode();
        RunMode target = RunMode.parse(mode);
        boolean changed = stateService.setRunMode(target.name());
        auditService.write(props.getManualAuditOperator(), "MODE_" + target.name(),
                "sys_state.run_mode", before.name(), target.name(), null);
        if (changed) {
            notifySession(before, target);
        }
        return changed;
    }

    private void notifySession(RunMode before, RunMode target) {
        if (!props.isManualNotifySession()) {
            return;
        }
        try {
            triggerEngine.systemEvent("运行模式切换", before.name() + " → " + target.name());
        } catch (RuntimeException e) {
            log.warn("运行模式切换通知会话失败（已忽略）：{}", e.getMessage());
        }
    }

    /** 运行状态快照。 */
    public record OpsState(String runMode, boolean degraded, long tokenUsedToday, LocalDate tokenDate) {
    }
}
