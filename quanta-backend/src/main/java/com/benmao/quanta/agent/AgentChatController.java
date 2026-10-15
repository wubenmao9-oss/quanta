package com.benmao.quanta.agent;

import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 自由问答与会话生命周期接口（新增，与 {@link AgentController} 的研判接口并存互不影响）。
 *
 * <ul>
 *   <li>{@code POST /api/agent/chat}               — 自由问 AI（非流式：一次性返回回答 + 工具调用轨迹）；</li>
 *   <li>{@code GET  /api/agent/sessions}           — 会话列表（默认只列「正在管理在场持仓」的会话，可传 all 看历史）；</li>
 *   <li>{@code GET  /api/agent/sessions/{id}/messages} — 会话内既有对话（供前端还原聊天记录）；</li>
 *   <li>{@code POST /api/agent/sessions/{id}/close}— 关闭会话（「对话随持仓生命周期销毁」的人工出口）。</li>
 * </ul>
 *
 * <p>本组接口<b>只读 + 只关闭会话</b>，不产生任何交易写操作，也不改变正在运行的策略行为。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private final AgentChatService chatService;
    private final AgentSessionQueryService sessionQueryService;

    public AgentChatController(AgentChatService chatService,
                               AgentSessionQueryService sessionQueryService) {
        this.chatService = chatService;
        this.sessionQueryService = sessionQueryService;
    }

    /** 自由问答。 */
    @PostMapping("/chat")
    public Result<AgentChatResult> chat(@RequestBody AgentChatRequest req) {
        return Result.ok(chatService.chat(req));
    }

    /** 会话列表（默认 ACTIVE）。 */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions(@RequestParam(defaultValue = "ACTIVE") String status,
                                                      @RequestParam(defaultValue = "0") int limit) {
        return Result.ok(sessionQueryService.list(status, limit));
    }

    /** 会话内既有对话消息（按时间升序）。 */
    @GetMapping("/sessions/{id}/messages")
    public Result<List<Map<String, Object>>> messages(@PathVariable long id) {
        return Result.ok(sessionQueryService.messages(id));
    }

    /** 关闭会话。 */
    @PostMapping("/sessions/{id}/close")
    public Result<Map<String, Object>> closeSession(@PathVariable long id) {
        return Result.ok(sessionQueryService.close(id));
    }
}
