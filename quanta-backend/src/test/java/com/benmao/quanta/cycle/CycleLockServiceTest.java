package com.benmao.quanta.cycle;

import com.benmao.quanta.backtest.BasketStrategyProperties;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁档服务单测：阈值来源「表 &gt; 配置」，0 = 该档不启用（无上限），越界拒绝。
 */
class CycleLockServiceTest {

    private CycleLockMapper lockMapper;
    private CycleSettingMapper settingMapper;
    private TradeOrderMapper tradeOrderMapper;
    private BasketStrategyProperties basketProps;

    @BeforeEach
    void setUp() {
        lockMapper = mock(CycleLockMapper.class);
        settingMapper = mock(CycleSettingMapper.class);
        tradeOrderMapper = mock(TradeOrderMapper.class);
        basketProps = new BasketStrategyProperties();
        // 配置默认：单轮 5 / 日 3 / 累计 10 / 锁定 24h
        assertTrue(basketProps.getLocks().getMaxSingleLossPct() == 5.0);
    }

    private CycleLockService service() {
        return new CycleLockService(lockMapper, settingMapper, tradeOrderMapper, basketProps);
    }

    @Test
    @DisplayName("表为空：三层阈值回落配置默认（5/3/10/24）")
    void defaultsWhenTableEmpty() {
        when(settingMapper.getByKey(anyString())).thenReturn(null);
        CycleLockService s = service();
        assertEquals(5.0, s.singleLossPct(), 1e-9);
        assertEquals(3.0, s.dailyLossPct(), 1e-9);
        assertEquals(10.0, s.totalLossPct(), 1e-9);
        assertEquals(24, s.lockHours());
    }

    @Test
    @DisplayName("表值 0：该档不启用（无上限）→ 日亏再大也不锁")
    void zeroMeansDisabled() {
        CycleSetting row = new CycleSetting();
        row.setSettingKey("DEFAULT");
        row.setMaxDailyLossPct(0.0);
        when(settingMapper.getByKey(anyString())).thenReturn(row);
        when(tradeOrderMapper.sumRealizedPnlSince(any())).thenReturn(new BigDecimal("-9999.00"));

        assertFalse(service().dayLocked(500.0));
    }

    @Test
    @DisplayName("表值正数：日亏触阈即锁（余额×pct%）")
    void positiveOverrideLocks() {
        CycleSetting row = new CycleSetting();
        row.setSettingKey("DEFAULT");
        row.setMaxDailyLossPct(2.0);
        when(settingMapper.getByKey(anyString())).thenReturn(row);
        // 余额 500 → 阈值 10.00；当日已实现 -12.00 触阈
        when(tradeOrderMapper.sumRealizedPnlSince(any())).thenReturn(new BigDecimal("-12.00"));

        assertTrue(service().dayLocked(500.0));
    }

    @Test
    @DisplayName("解锁判定：损失未达阈值不锁")
    void belowThresholdNotLocked() {
        CycleSetting row = new CycleSetting();
        row.setSettingKey("DEFAULT");
        row.setMaxDailyLossPct(2.0);
        when(settingMapper.getByKey(anyString())).thenReturn(row);
        when(tradeOrderMapper.sumRealizedPnlSince(any())).thenReturn(new BigDecimal("-9.00"));

        assertFalse(service().dayLocked(500.0));
    }

    @Test
    @DisplayName("保存锁档：0 合法（=不启用），越界(>100)或负值被拒")
    void saveValidatesRange() {
        // 用可变持有者模拟单行 upsert：insert/update 后 getByKey 返回同一行
        CycleSetting[] holder = new CycleSetting[1];
        when(settingMapper.getByKey(anyString())).thenAnswer(inv -> holder[0]);
        when(settingMapper.insert(any())).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            return 1;
        });
        when(settingMapper.updateByKey(any())).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            return 1;
        });
        CycleLockService s = service();

        // 0 合法（=该档不启用）
        assertEquals(0.0, s.saveSetting(0.0, 0.0, 0.0, 24).getMaxSingleLossPct(), 1e-9);
        // 正数生效
        assertEquals(6.5, s.saveSetting(6.5, 3.0, 10.0, 12).getMaxSingleLossPct(), 1e-9);
        assertEquals(12, s.currentSetting().getLockHours().intValue());
        // 越界 / 负值拒绝
        assertThrows(BizException.class, () -> s.saveSetting(101.0, null, null, null));
        assertThrows(BizException.class, () -> s.saveSetting(-1.0, null, null, null));
        assertThrows(BizException.class, () -> s.saveSetting(null, null, null, -1));
    }
}
