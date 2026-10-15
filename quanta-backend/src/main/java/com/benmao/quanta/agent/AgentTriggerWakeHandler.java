package com.benmao.quanta.agent;

import com.benmao.quanta.schedule.TriggerHit;
import com.benmao.quanta.schedule.TriggerWakeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「触发命中 → AI 研判」桥接实现（M14）。
 *
 * <p>由 {@code quanta.schedule.trigger.auto-wake.enabled} 开关控制（<b>默认关</b>）；开启后调度层在命中时
 * 回调本类，转成一次 {@link AgentDecideRequest} 交给 {@link AgentService} 执行——
 * 写动作仍由 {@code AgentWriteTools → TradingService → RiskGuard} 确定性执行并可能被风控拒绝，
 * 本类不改变任何风控/门控语义。
 *
 * <p>本实现放在 agent 包（而非调度包），以保持调度层「不引用任何 LLM 类型」的结构性约束。
 * 异常一律吞掉并记日志，绝不影响调度线程。
 */
@Component
public class AgentTriggerWakeHandler implements TriggerWakeHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentTriggerWakeHandler.class);

    private final AgentService agentService;
    private final AgentSessionService sessionService;

    public AgentTriggerWakeHandler(AgentService agentService, AgentSessionService sessionService) {
        this.agentService = agentService;
        this.sessionService = sessionService;
    }

    @Override
    public void onTrigger(TriggerHit hit) {
        if (hit == null) {
            return;
        }
        Long sessionId = null;
        boolean positionOpened = false;
        try {
            AgentDecideRequest req = new AgentDecideRequest(
                    hit.symbol(), null, null, null, hit.source().name(), hit.reason());
            AgentDecideResult r = agentService.decide(req);
            sessionId = r.sessionId();
            // 开仓成功的会话不再是一次性研判窗口，而是该持仓的「管理会话」：
            // 绑定 ticket 并保活，直到持仓了结由生命周期对账关闭。
            positionOpened = r.action() == AgentAction.OPEN && r.executed() && r.ticket() != null;
            if (positionOpened) {
                sessionService.linkTicket(sessionId, r.ticket());
            }
            log.info("自动唤醒完成：触发={} 会话={} 动作={} 置信度={} 执行={} ticket={}",
                    hit.source(), r.sessionId(), r.action(), r.confidence(), r.executed(), r.ticket());
        } catch (RuntimeException e) {
            log.warn("自动唤醒 Agent 研判失败（触发={}）：{}", hit.source(), e.getMessage());
        } finally {
            if (!positionOpened) {
                closeSession(sessionId);
            }
        }
    }

    /**
     * 未开仓的自动唤醒会话是<b>短生命周期「研判窗口」</b>：一次触发一次研判，结束即关闭，
     * 避免 ACTIVE 会话随每次唤醒无限累积；<b>开仓成功的会话不在此关闭</b>——它是持仓的管理会话，
     * 由持仓生命周期对账在「该持仓离场」时关闭（一持仓一会话）。
     */
    private void closeSession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            sessionService.close(sessionId);
        } catch (RuntimeException e) {
            log.warn("关闭自动唤醒会话失败：session={} err={}", sessionId, e.getMessage());
        }
    }
}
