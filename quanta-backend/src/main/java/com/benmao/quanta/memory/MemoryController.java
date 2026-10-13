package com.benmao.quanta.memory;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.AgentMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆卡接口（M11）。
 *
 * <ul>
 *   <li>{@code GET  /api/memory}：按 scope/symbol/strategyId/tag 检索记忆卡（设计 §11 一期标签检索）；</li>
 *   <li>{@code POST /api/memory/summarize}：对指定会话强制产出一张记忆卡（手工/验收用，同样走结构化拼装、不调大模型）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 检索记忆卡（过滤优先级：tag > strategyId > scope > symbol；limit<=0 用默认条数，上限 200）。 */
    @GetMapping
    public Result<List<AgentMemory>> list(@RequestParam(required = false) String scope,
                                          @RequestParam(required = false) String symbol,
                                          @RequestParam(required = false) Long strategyId,
                                          @RequestParam(required = false) String tag,
                                          @RequestParam(defaultValue = "0") int limit) {
        return Result.ok(memoryService.search(scope, symbol, strategyId, tag, limit));
    }

    /** 对某会话的最近若干次决策强制产出记忆卡（结构化拼装，不调用大模型）。 */
    @PostMapping("/summarize")
    public Result<AgentMemory> summarize(@RequestParam Long sessionId,
                                         @RequestParam(required = false) String symbol,
                                         @RequestParam(required = false) Long strategyId,
                                         @RequestParam(required = false) String regime) {
        return Result.ok(memoryService.summarize(sessionId, symbol, strategyId, regime));
    }
}
