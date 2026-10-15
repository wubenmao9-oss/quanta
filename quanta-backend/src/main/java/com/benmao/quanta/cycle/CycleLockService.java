package com.benmao.quanta.cycle;

import com.benmao.quanta.backtest.BasketStrategyProperties;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * cycle 执行器的锁闸门服务（日锁 + 总锁 + 单轮硬止损）：
 * <ul>
 *   <li><b>单轮锁</b>：轮内浮亏 ≥ 轮初权益 × {@code singleLossPct}% → 整轮硬止损平仓（由 {@link CycleEngine} 判定）；</li>
 *   <li><b>日锁</b>：当日已实现净亏 ≥ 余额 × {@code dailyLossPct}% → 当日禁止新开轮（跨日自动解除，无需落库）。</li>
 *   <li><b>总锁</b>：相对参照权益的累计已实现净亏 ≥ {@code totalLossPct}% → 持久化锁 {@code lockHours} 小时，需人工解除。</li>
 * </ul>
 *
 * <p><b>阈值来源（人工可改，优先级：表 &gt; 配置）</b>：
 * {@code cycle_setting} 表（前端「策略锁档」面板写入）优先；字段为 NULL 时回落
 * {@link BasketStrategyProperties.Locks} 的配置默认。任一档取值为 <b>0 或负数</b> → 该档<b>不启用</b>（即无上限）。
 */
@Service
public class CycleLockService {

    private static final Logger log = LoggerFactory.getLogger(CycleLockService.class);
    private static final String KEY_TOTAL = "TOTAL";
    /** cycle_setting 单行键。 */
    private static final String KEY_SETTING = "DEFAULT";

    private final CycleLockMapper lockMapper;
    private final CycleSettingMapper settingMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final BasketStrategyProperties basketProps;

    public CycleLockService(CycleLockMapper lockMapper,
                            CycleSettingMapper settingMapper,
                            TradeOrderMapper tradeOrderMapper,
                            BasketStrategyProperties basketProps) {
        this.lockMapper = lockMapper;
        this.settingMapper = settingMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.basketProps = basketProps;
    }

    // ==================== 生效阈值（表 → 配置兜底） ====================

    /** 生效的单轮最大亏损%（0 或负数 = 不启用）。 */
    public double singleLossPct() {
        return pctOf(setting().getMaxSingleLossPct(), basketProps.getLocks().getMaxSingleLossPct());
    }

    /** 生效的单日最大亏损%（0 或负数 = 不启用）。 */
    public double dailyLossPct() {
        return pctOf(setting().getMaxDailyLossPct(), basketProps.getLocks().getMaxDailyLossPct());
    }

    /** 生效的累计最大亏损%（0 或负数 = 不启用）。 */
    public double totalLossPct() {
        return pctOf(setting().getMaxTotalLossPct(), basketProps.getLocks().getMaxTotalLossPct());
    }

    /** 生效的锁定时长（小时）。 */
    public int lockHours() {
        CycleSetting s = setting();
        return s.getLockHours() != null ? s.getLockHours() : basketProps.getLocks().getLockHours();
    }

    /** 当前锁档设置（NULL 字段已回填为生效值），供前端面板回显。 */
    public CycleSetting currentSetting() {
        CycleSetting s = setting();
        s.setMaxSingleLossPct(singleLossPct());
        s.setMaxDailyLossPct(dailyLossPct());
        s.setMaxTotalLossPct(totalLossPct());
        s.setLockHours(lockHours());
        return s;
    }

    /** 保存锁档设置（幂等 upsert）；返回保存后的生效值。 */
    public CycleSetting saveSetting(Double singlePct, Double dailyPct, Double totalPct, Integer hours) {
        checkRange(singlePct, "单轮最大亏损%");
        checkRange(dailyPct, "单日最大亏损%");
        checkRange(totalPct, "累计最大亏损%");
        if (hours != null && hours < 0) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "锁定时长不得为负", 400);
        }
        CycleSetting s = new CycleSetting();
        s.setSettingKey(KEY_SETTING);
        s.setMaxSingleLossPct(singlePct);
        s.setMaxDailyLossPct(dailyPct);
        s.setMaxTotalLossPct(totalPct);
        s.setLockHours(hours);
        if (settingMapper.getByKey(KEY_SETTING) == null) {
            settingMapper.insert(s);
        } else {
            settingMapper.updateByKey(s);
        }
        CycleSetting saved = currentSetting();
        log.info("cycle 锁档已更新：单轮={}% 日={}% 累计={}% 锁定时长={}h（0=不启用）",
                saved.getMaxSingleLossPct(), saved.getMaxDailyLossPct(),
                saved.getMaxTotalLossPct(), saved.getLockHours());
        if (allOff(saved)) {
            log.warn("cycle 锁档三层全为 0：整轮止损/日锁/总锁全部关闭！持仓将只能依赖券商侧单腿黑障兜底");
        }
        return saved;
    }

    /** 三层亏损锁是否全为 0（= 完全关闭止损）。 */
    private static boolean allOff(CycleSetting s) {
        return pctOf(s.getMaxSingleLossPct(), 0) <= 0
                && pctOf(s.getMaxDailyLossPct(), 0) <= 0
                && pctOf(s.getMaxTotalLossPct(), 0) <= 0;
    }

    /** 当日是否已触发日亏锁（跨日自动重置：统计起点为当日 00:00）。 */
    public boolean dayLocked(double balance) {
        double pct = dailyLossPct();
        if (pct <= 0 || balance <= 0) {
            return false;
        }
        double today = realizedSince(LocalDate.now().atStartOfDay());
        return today <= -(balance * pct / 100.0);
    }

    /** 总锁是否生效（持久化锁未过期，或累计亏损已触阈）。 */
    public boolean totalLocked(double balance) {
        double pct = totalLossPct();
        CycleLock row = lockMapper.getByKey(KEY_TOTAL);
        if (row == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (row.getLockUntil() != null && row.getLockUntil().isAfter(now)) {
            return true;
        }
        if (pct <= 0) {
            return false;
        }
        double ref = row.getRefEquity() == null ? balance : row.getRefEquity();
        if (ref <= 0 || row.getRefTime() == null) {
            return false;
        }
        double cum = realizedSince(row.getRefTime());
        if (cum <= -(ref * pct / 100.0)) {
            lockTotal(row);
            return true;
        }
        return false;
    }

    /** 确保总锁参照行存在（首次以当前余额/时间为基准）。 */
    public void ensureRef(double balance) {
        if (lockMapper.getByKey(KEY_TOTAL) == null) {
            CycleLock e = new CycleLock();
            e.setLockKey(KEY_TOTAL);
            e.setRefEquity(balance);
            e.setRefTime(LocalDateTime.now());
            lockMapper.insert(e);
            log.info("cycle 总锁参照已初始化：refEquity={}", balance);
        }
    }

    /** 触发总锁：锁 {@code lockHours} 小时（需人工 resume 或到期自动解除）。 */
    public void lockTotal(CycleLock row) {
        int hours = Math.max(1, lockHours());
        row.setLockUntil(LocalDateTime.now().plusHours(hours));
        lockMapper.updateByKey(row);
        log.warn("cycle 总亏锁已触发：锁至 {}", row.getLockUntil());
    }

    /** 人工解除总锁（供控制器/运维使用）。 */
    public void unlockTotal() {
        CycleLock row = lockMapper.getByKey(KEY_TOTAL);
        if (row != null) {
            row.setLockUntil(null);
            row.setRefEquity(null);
            row.setRefTime(null);
            lockMapper.updateByKey(row);
            log.info("cycle 总亏锁已人工解除");
        }
    }

    // ==================== 内部 ====================

    /** 读 cycle_setting 单行；不存在时返回空实体（字段全 null → 全部回落配置默认）。 */
    private CycleSetting setting() {
        CycleSetting s = settingMapper.getByKey(KEY_SETTING);
        return s != null ? s : new CycleSetting();
    }

    /** 生效百分比：表值非 null 用表值（0/负数 = 不启用），否则回落配置默认。 */
    private static double pctOf(Double tableValue, double configDefault) {
        return tableValue != null ? tableValue : configDefault;
    }

    /** 百分比合法范围：0（=不启用/无上限）或 [0.01, 100]。 */
    private static void checkRange(Double pct, String label) {
        if (pct == null) {
            return;
        }
        if (pct < 0 || pct > 100) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    label + "须在 0~100 之间（0 = 不启用/无上限）", 400);
        }
        if (pct > 0 && pct < 0.01) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    label + "过小（最小 0.01%），如需关闭请填 0", 400);
        }
    }

    private double realizedSince(LocalDateTime since) {
        BigDecimal v = tradeOrderMapper.sumRealizedPnlSince(since);
        return v == null ? 0 : v.doubleValue();
    }
}
