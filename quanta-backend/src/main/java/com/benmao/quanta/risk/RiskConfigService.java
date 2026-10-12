package com.benmao.quanta.risk;

import com.benmao.quanta.entity.RiskConfig;
import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.RiskConfigMapper;
import com.benmao.quanta.mapper.SysStateMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 风控上下文提供者：读取生效阈值（{@code risk_config} 表，人工可改）与运行模式（{@code sys_state}）。
 * 表为空/字段为空时用 {@link RiskProperties} 的兜底默认，保证"开箱可用"且阈值不写死在代码里。
 */
@Service
public class RiskConfigService {

    /** 无 sys_state 记录时的默认运行模式。 */
    public static final String DEFAULT_RUN_MODE = "AUTO";

    private final RiskConfigMapper riskConfigMapper;
    private final SysStateMapper sysStateMapper;
    private final RiskProperties props;

    public RiskConfigService(RiskConfigMapper riskConfigMapper,
                             SysStateMapper sysStateMapper,
                             RiskProperties props) {
        this.riskConfigMapper = riskConfigMapper;
        this.sysStateMapper = sysStateMapper;
        this.props = props;
    }

    /** 生效阈值：取 risk_config 首行，缺失字段用兜底默认。 */
    public RiskThresholds thresholds() {
        List<RiskConfig> rows = riskConfigMapper.listAll();
        RiskConfig row = rows.isEmpty() ? null : rows.get(0);
        return new RiskThresholds(
                orDefault(row == null ? null : row.getFixedVolume(), props.getDefaultFixedVolume()),
                orDefault(row == null ? null : row.getMaxVolume(), props.getDefaultMaxVolume()),
                orDefault(row == null ? null : row.getMaxPositions(), props.getDefaultMaxPositions()),
                orDefault(row == null ? null : row.getMaxDailyLoss(), props.getDefaultMaxDailyLoss()),
                orDefault(row == null ? null : row.getMaxRiskPct(), props.getDefaultMaxRiskPct()),
                orDefault(row == null ? null : row.getMinRr(), props.getDefaultMinRr()),
                orDefault(row == null ? null : row.getMinSlSpreadMult(), props.getDefaultMinSlSpreadMult()),
                orDefault(row == null ? null : row.getMaxConsecutiveLoss(), props.getDefaultMaxConsecutiveLoss()),
                orDefault(row == null ? null : row.getCooldownMinutes(), props.getDefaultCooldownMinutes()),
                orDefault(row == null ? null : row.getSpreadLimit(), props.getDefaultSpreadLimit()),
                whitelist(row == null ? null : row.getSymbolWhitelist()));
    }

    /** 当前运行模式（AUTO / PAUSED / TAKEOVER）；无记录 → AUTO。 */
    public String runMode() {
        List<SysState> rows = sysStateMapper.listAll();
        if (rows.isEmpty() || rows.get(0).getRunMode() == null || rows.get(0).getRunMode().isBlank()) {
            return DEFAULT_RUN_MODE;
        }
        return rows.get(0).getRunMode().trim().toUpperCase(java.util.Locale.ROOT);
    }

    private List<String> whitelist(String raw) {
        String source = (raw == null || raw.isBlank()) ? props.getDefaultSymbolWhitelist() : raw;
        return Arrays.stream(source.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static BigDecimal orDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
