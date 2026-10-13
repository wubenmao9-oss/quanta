package com.benmao.quanta.memory;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆层可调参数（设计 §11 记忆机制 / §18 可调参数总表）。
 *
 * <p>铁律：阈值与开关一律<b>集中配置</b>，核心代码不得写死。全部可在 {@code application.yml}
 * 的 {@code quanta.memory.*} 下覆写。
 */
@Component
@ConfigurationProperties(prefix = "quanta.memory")
public class MemoryProperties {

    /** 记忆层总开关；关闭后不再产出记忆卡。 */
    private boolean enabled = true;

    /** 记忆总结间隔：会话内每 N 次决策产出一张记忆卡（设计 §18 默认 10，合法范围 5~50）。 */
    private int summaryEvery = 10;

    /** 会话记忆条数：进上下文的最近条数（设计 §18 默认 10，合法范围 5~50）。 */
    private int memoryWindow = 10;

    /** 记忆卡摘要长度上限（对齐 agent_memory.summary VARCHAR(500)）。 */
    private int summaryMaxLen = 500;

    /** 记忆检索列表默认条数。 */
    private int listDefaultLimit = 20;

    /** 记忆总结间隔的合法下界。 */
    public static final int SUMMARY_EVERY_MIN = 5;
    /** 记忆总结间隔的合法上界。 */
    public static final int SUMMARY_EVERY_MAX = 50;
    /** 会话记忆条数的合法下界。 */
    public static final int MEMORY_WINDOW_MIN = 5;
    /** 会话记忆条数的合法上界。 */
    public static final int MEMORY_WINDOW_MAX = 50;

    /** 校验并返回合法的总结间隔；非法即拒绝并回执合法范围（防幻觉）。 */
    public int requireSummaryEvery() {
        if (summaryEvery < SUMMARY_EVERY_MIN || summaryEvery > SUMMARY_EVERY_MAX) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "记忆总结间隔 summary-every=" + summaryEvery + " 非法，合法范围 ["
                            + SUMMARY_EVERY_MIN + ", " + SUMMARY_EVERY_MAX + "]");
        }
        return summaryEvery;
    }

    /** 校验并返回合法的会话记忆条数；非法即拒绝并回执合法范围（防幻觉）。 */
    public int requireMemoryWindow() {
        if (memoryWindow < MEMORY_WINDOW_MIN || memoryWindow > MEMORY_WINDOW_MAX) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "会话记忆条数 memory-window=" + memoryWindow + " 非法，合法范围 ["
                            + MEMORY_WINDOW_MIN + ", " + MEMORY_WINDOW_MAX + "]");
        }
        return memoryWindow;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSummaryEvery() {
        return summaryEvery;
    }

    public void setSummaryEvery(int summaryEvery) {
        this.summaryEvery = summaryEvery;
    }

    public int getMemoryWindow() {
        return memoryWindow;
    }

    public void setMemoryWindow(int memoryWindow) {
        this.memoryWindow = memoryWindow;
    }

    public int getSummaryMaxLen() {
        return summaryMaxLen;
    }

    public void setSummaryMaxLen(int summaryMaxLen) {
        this.summaryMaxLen = summaryMaxLen;
    }

    public int getListDefaultLimit() {
        return listDefaultLimit;
    }

    public void setListDefaultLimit(int listDefaultLimit) {
        this.listDefaultLimit = listDefaultLimit;
    }
}
