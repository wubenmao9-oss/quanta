package com.benmao.quanta.agent;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.AgentDecisionLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 中枢接口（M10）。
 *
 * <ul>
 *   <li>{@code POST /api/agent/decide}：发起一次研判（默认触发源 T6 人工唤醒，即人工唤醒入口）；</li>
 *   <li>{@code GET  /api/agent/decisions}：最近决策日志（含 input_snapshot / tokens / latency）；</li>
 *   <li>{@code GET  /api/agent/tools}：契约动作枚举 + 只读工具清单（并显式声明写工具不暴露给大模型）。</li>
 * </ul>
 *
 * <p>本接口只做薄封装；所有写动作仍由 {@link AgentService} 经 {@code TradingService → RiskGuard} 执行，
 * 无任何绕过风控的旁路。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 发起一次研判（请求体可空，全部走默认值）。 */
    @PostMapping("/decide")
    public Result<AgentDecideResult> decide(@RequestBody(required = false) AgentDecideRequest req) {
        return Result.ok(agentService.decide(req));
    }

    /** 最近决策日志（最新在前；limit<=0 用默认条数，上限 200）。 */
    @GetMapping("/decisions")
    public Result<List<AgentDecisionLog>> decisions(@RequestParam(defaultValue = "0") int limit) {
        return Result.ok(agentService.listDecisions(limit));
    }

    /** 契约与工具清单（观测/验收用）。 */
    @GetMapping("/tools")
    public Result<Map<String, Object>> tools() {
        return Result.ok(agentService.describeTools());
    }
}
