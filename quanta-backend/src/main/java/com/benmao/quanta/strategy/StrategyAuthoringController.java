package com.benmao.quanta.strategy;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.StrategyMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 策略入库接口（新增）：{@code POST /api/strategy}。
 *
 * <p>供前端「AI 策略对话框」把打磨好的策略 Prompt 保存进策略库。落库一律为
 * {@code status=DRAFT} + {@code approved=false}，<b>不</b>能被 Agent 选中执行；
 * 必须再走 {@code /api/strategy/{id}/gate} 门禁与 {@code /api/strategy/{id}/approve} 人工批准
 * 才可能置 ACTIVE，因此不会绕过既有的策略门禁。
 */
@RestController
@RequestMapping("/api/strategy")
public class StrategyAuthoringController {

    private final StrategyMapper strategyMapper;

    public StrategyAuthoringController(StrategyMapper strategyMapper) {
        this.strategyMapper = strategyMapper;
    }

    /** 保存一条新策略（草稿）。 */
    @PostMapping
    public Result<Strategy> create(@RequestBody CreateReq req) {
        if (req == null || isBlank(req.name())) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "策略名 name 不能为空", 400);
        }
        if (isBlank(req.prompt())) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "策略 Prompt 不能为空", 400);
        }
        Strategy s = new Strategy();
        s.setName(req.name().trim());
        s.setPrompt(req.prompt());
        s.setVersion(1);
        s.setStatus("DRAFT");
        s.setOrigin(isBlank(req.origin()) ? "AI" : req.origin().trim());
        s.setApproved(false);
        s.setRegimeType(isBlank(req.regimeType()) ? "TREND" : req.regimeType().trim().toUpperCase());
        s.setTargetRr(req.targetRr() == null ? BigDecimal.valueOf(1.5) : req.targetRr());
        s.setExpectedWinRate(req.expectedWinRate());
        s.setIndicatorParams(req.indicatorParams());
        s.setDescription(req.description());
        strategyMapper.insert(s);
        return Result.ok(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 建策略请求体。 */
    public record CreateReq(String name,
                            String prompt,
                            String description,
                            String regimeType,
                            BigDecimal targetRr,
                            BigDecimal expectedWinRate,
                            String indicatorParams,
                            String origin) {
    }
}
