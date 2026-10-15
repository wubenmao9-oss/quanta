package com.benmao.quanta.risk;

import com.benmao.quanta.entity.RiskConfig;
import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.RiskConfigMapper;
import com.benmao.quanta.mapper.SysStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 风控上下文提供者：读取生效阈值（{@code risk_config} 表，人工可改）与运行模式（{@code sys_state}）。
 * 表为空/字段为空时用 {@link RiskProperties} 的兜底默认，保证"开箱可用"且阈值不写死在代码里。
 */
@Service
public class RiskConfigService {

    private static final Logger log = LoggerFactory.getLogger(RiskConfigService.class);

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

    /**
     * 生效阈值（{@link RiskProfile#DEFAULT} 档）：取 risk_config 首行，缺失字段用兜底默认。
     *
     * <p>保留此无参重载，<b>现有调用点零改动</b>。
     */
    public RiskThresholds thresholds() {
        return thresholds(RiskProfile.DEFAULT);
    }

    /**
     * 按风控档生效阈值。
     *
     * <p>{@link RiskProfile#DEFAULT}：走原列；{@link RiskProfile#GRID}（多腿/马丁执行器）：
     * 仅用 {@code grid_*} 覆盖 <b>maxVolume / maxPositions / minRr / maxRiskPct</b>
     * （放宽 R2/R3/R15/R4），
     * {@code maxDailyLoss / minSlSpreadMult / symbolWhitelist} 等<b>两档共用同列</b>（保持硬约束）。
     * 表为空 / 字段为空时回落 {@link RiskProperties} 对应默认。
     */
    public RiskThresholds thresholds(RiskProfile profile) {
        List<RiskConfig> rows = riskConfigMapper.listAll();
        RiskConfig row = rows.isEmpty() ? null : rows.get(0);

        BigDecimal maxVolume = orDefault(row == null ? null : row.getMaxVolume(), props.getDefaultMaxVolume());
        int maxPositions = orDefault(row == null ? null : row.getMaxPositions(), props.getDefaultMaxPositions());
        BigDecimal minRr = orDefault(row == null ? null : row.getMinRr(), props.getDefaultMinRr());
        BigDecimal maxRiskPct = orDefault(row == null ? null : row.getMaxRiskPct(), props.getDefaultMaxRiskPct());
        if (RiskProfile.GRID == profile) {
            maxVolume = orDefault(row == null ? null : row.getGridMaxVolume(), props.getDefaultGridMaxVolume());
            maxPositions = orDefault(row == null ? null : row.getGridMaxPositions(), props.getDefaultGridMaxPositions());
            minRr = orDefault(row == null ? null : row.getGridMinRr(), props.getDefaultGridMinRr());
            // R4 放宽：马丁单腿的兜底 SL 距离远大于普通单仓，沿用 2% 会让高腿手数整腿被拒
            maxRiskPct = orDefault(row == null ? null : row.getGridMaxRiskPct(), props.getDefaultGridMaxRiskPct());
        }

        return new RiskThresholds(
                orDefault(row == null ? null : row.getFixedVolume(), props.getDefaultFixedVolume()),
                maxVolume,
                maxPositions,
                orDefault(row == null ? null : row.getMaxDailyLoss(), props.getDefaultMaxDailyLoss()),
                maxRiskPct,
                minRr,
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

    /**
     * 保存账户级风控阈值（写 {@code risk_config} 唯一行：表空则插入首行，否则原位更新）。
     *
     * <p>只覆盖 {@code patch} 中<b>非空</b>的字段，其余保持当前生效值；GRID 专用列（grid_*）不在此处写，
     * 继续走 {@link RiskProperties} 兜底默认。R6 日亏熔断即 {@code maxDailyLoss}：当日已实现亏损
     * 达到该值即禁止开仓、只允许平仓，自然日 00:00 自动解除；<b>0 不是「关闭」而是「一亏即熔断」</b>。
     */
    @Transactional
    public RiskThresholds saveThresholds(Patch patch) {
        RiskThresholds cur = thresholds();
        List<RiskConfig> rows = riskConfigMapper.listAll();
        RiskConfig row = rows.isEmpty() ? new RiskConfig() : rows.get(0);

        row.setFixedVolume(cur.fixedVolume());
        row.setMaxVolume(orDefault(patch.maxVolume(), cur.maxVolume()));
        row.setMaxPositions(orDefault(patch.maxPositions(), cur.maxPositions()));
        row.setMaxDailyLoss(orDefault(patch.maxDailyLoss(), cur.maxDailyLoss()));
        row.setMaxRiskPct(orDefault(patch.maxRiskPct(), cur.maxRiskPct()));
        row.setMinRr(orDefault(patch.minRr(), cur.minRr()));
        row.setMinSlSpreadMult(orDefault(patch.minSlSpreadMult(), cur.minSlSpreadMult()));
        row.setMaxConsecutiveLoss(orDefault(patch.maxConsecutiveLoss(), cur.maxConsecutiveLoss()));
        row.setCooldownMinutes(orDefault(patch.cooldownMinutes(), cur.cooldownMinutes()));
        row.setSpreadLimit(orDefault(patch.spreadLimit(), cur.spreadLimit()));
        row.setSymbolWhitelist(patch.symbolWhitelist() == null || patch.symbolWhitelist().isEmpty()
                ? String.join(",", cur.symbolWhitelist())
                : String.join(",", patch.symbolWhitelist()));

        if (row.getId() == null) {
            riskConfigMapper.insert(row);
        } else {
            riskConfigMapper.update(row);
        }

        RiskThresholds saved = thresholds();
        log.info("风控阈值已保存：日亏熔断={}USD 单笔上限={} 最大持仓={} 单笔风险={}% 最小盈亏比={} "
                        + "SL点差倍数={} 连亏上限={} 冷静期={}min 点差上限={} 白名单={}",
                saved.maxDailyLoss(), saved.maxVolume(), saved.maxPositions(), saved.maxRiskPct(), saved.minRr(),
                saved.minSlSpreadMult(), saved.maxConsecutiveLoss(), saved.cooldownMinutes(),
                saved.spreadLimit(), saved.symbolWhitelist());
        return saved;
    }

    /** 账户级风控阈值增量保存入参（字段为 null / 白名单为空 = 保持当前生效值）。 */
    public record Patch(BigDecimal maxVolume,
                        Integer maxPositions,
                        BigDecimal maxDailyLoss,
                        BigDecimal maxRiskPct,
                        BigDecimal minRr,
                        BigDecimal minSlSpreadMult,
                        Integer maxConsecutiveLoss,
                        Integer cooldownMinutes,
                        BigDecimal spreadLimit,
                        List<String> symbolWhitelist) {
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
