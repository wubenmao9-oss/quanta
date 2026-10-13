package com.benmao.quanta.state;

import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.SysStateMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 系统运行状态（{@code sys_state} 单行）读写入口。
 *
 * <p>设计 §9.1 表 18 / §8.4：<b>降级态落库</b>，重启后依旧保持。
 * 本类不做缓存——每次从库读取，保证「重启后仍保持降级」与「多入口读到的状态一致」。
 *
 * <p>{@code sys_state} 约定只有一行（取 {@code listAll()} 首行）；无记录时在首次写入时补建默认行（AUTO / 未降级 / 0）。
 */
@Service
public class SystemStateService {

    /** 无记录时的默认运行模式。 */
    public static final String DEFAULT_RUN_MODE = "AUTO";

    private final SysStateMapper mapper;

    public SystemStateService(SysStateMapper mapper) {
        this.mapper = mapper;
    }

    /** 当前状态行；无记录返回未落库的默认态（AUTO / 未降级 / 0）。 */
    public SysState current() {
        List<SysState> rows = mapper.listAll();
        return rows.isEmpty() ? defaultState() : rows.get(0);
    }

    /** 是否处于 Token 超限降级态。 */
    public boolean isDegraded() {
        return Boolean.TRUE.equals(current().getDegraded());
    }

    /** 当前运行模式（AUTO / PAUSED / TAKEOVER）；无记录或空 → AUTO。 */
    public RunMode runMode() {
        String raw = current().getRunMode();
        return (raw == null || raw.isBlank()) ? RunMode.AUTO : RunMode.parse(raw);
    }

    /**
     * 设置运行模式并落库（设计 §8.1 三态，重启后保持）。
     *
     * @return 是否发生了变化（幂等：相同模式返回 {@code false}）
     */
    public boolean setRunMode(String mode) {
        RunMode target = RunMode.parse(mode);
        SysState s = ensureRow();
        if (target.name().equals(s.getRunMode())) {
            return false;
        }
        s.setRunMode(target.name());
        mapper.update(s);
        return true;
    }

    /** 当日已用 Token（sys_state.token_used_today）。 */
    public long tokenUsedToday() {
        Long v = current().getTokenUsedToday();
        return v == null ? 0L : v;
    }

    /** 用量归属日期（sys_state.token_date）。 */
    public LocalDate tokenDate() {
        return current().getTokenDate();
    }

    /**
     * 置为降级并落库。
     *
     * @return 是否为「本次新进入降级」（已降级再调用返回 {@code false}，幂等）
     */
    public boolean markDegraded() {
        SysState s = ensureRow();
        if (Boolean.TRUE.equals(s.getDegraded())) {
            return false;
        }
        s.setDegraded(true);
        mapper.update(s);
        return true;
    }

    /**
     * 清除降级并落库。
     *
     * @return 是否发生了变化（本已未降级返回 {@code false}，幂等）
     */
    public boolean clearDegraded() {
        SysState s = ensureRow();
        if (!Boolean.TRUE.equals(s.getDegraded())) {
            return false;
        }
        s.setDegraded(false);
        mapper.update(s);
        return true;
    }

    /** 记录当日 Token 用量与归属日期。 */
    public void recordTokenUsage(LocalDate date, long used) {
        SysState s = ensureRow();
        s.setTokenDate(date);
        s.setTokenUsedToday(used);
        mapper.update(s);
    }

    /** 取首行；不存在则插入一行默认态（AUTO / 未降级 / 0）。 */
    private SysState ensureRow() {
        List<SysState> rows = mapper.listAll();
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        SysState s = defaultState();
        mapper.insert(s);
        return s;
    }

    private static SysState defaultState() {
        SysState s = new SysState();
        s.setRunMode(DEFAULT_RUN_MODE);
        s.setDegraded(false);
        s.setTokenUsedToday(0L);
        return s;
    }
}
