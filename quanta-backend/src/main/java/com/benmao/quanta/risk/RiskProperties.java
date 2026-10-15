package com.benmao.quanta.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 风控层的<b>运行期参数与阈值兜底默认</b>（集中配置，禁止在核心代码写死）。
 *
 * <p>两类配置：
 * <ul>
 *   <li><b>运行期行为参数</b>：合约规格、幂等窗口、价格偏离上限、点差历史窗口、禁开星期；
 *       这些不是"业务阈值"，属于实现细节，只在此处集中管理；</li>
 *   <li><b>阈值兜底默认（default-*）</b>：正式阈值以 {@code risk_config} 表为准（人工可改）；
 *       当表为空时用这里的默认值，与设计 §7 的 DDL 默认值一致。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "quanta.risk")
public class RiskProperties {

    // ---------------- 合约规格（GOLD 实测：1 手 = 100 盎司 → 1 USD 价差 = 100 USD/手） ----------------

    private double contractSizePerLot = 100.0;
    private double point = 0.01;
    private double volumeStep = 0.01;
    private double volumeMin = 0.01;

    // ---------------- 运行期行为参数 ----------------

    /** R8 幂等窗口（秒）。 */
    private int idempotencyWindowSeconds = 60;

    /** R12 开仓价与市价的最大偏离（%）。 */
    private double maxPriceDeviationPct = 0.5;

    /** R9 点差中位数统计窗口（分钟）。 */
    private int spreadWindowMinutes = 60;

    /** R9 样本不足此数量时，只按 spread_limit 静态阈值判定（不因样本不足而误拒）。 */
    private int spreadMinSamples = 12;

    /** R9 动态阈值倍数：max(spread_limit, 近窗口点差中位数 × 该倍数)。 */
    private double spreadMedianMultiplier = 3.0;

    /** R10 禁开星期（休市），默认周六、周日。 */
    private List<String> closedWeekdays = List.of("SATURDAY", "SUNDAY");

    /** R10 每日禁开窗口起点（HH:mm，空 = 不启用）。 */
    private String dailyBlackoutStart = "";

    /** R10 每日禁开窗口终点（HH:mm，空 = 不启用）。 */
    private String dailyBlackoutEnd = "";

    // ---------------- 阈值兜底默认（risk_config 表为空时使用） ----------------

    private BigDecimal defaultFixedVolume = new BigDecimal("0.01");
    private BigDecimal defaultMaxVolume = new BigDecimal("0.01");
    private int defaultMaxPositions = 3;
    private BigDecimal defaultMaxDailyLoss = new BigDecimal("50");
    private BigDecimal defaultMaxRiskPct = new BigDecimal("2.00");
    private BigDecimal defaultMinRr = new BigDecimal("1.00");
    private BigDecimal defaultMinSlSpreadMult = new BigDecimal("5.00");
    private int defaultMaxConsecutiveLoss = 3;
    private int defaultCooldownMinutes = 0;
    private BigDecimal defaultSpreadLimit = new BigDecimal("50");
    private String defaultSymbolWhitelist = "GOLD";

    // ---------------- GRID 专用档兜底默认（risk_config.grid_* 为空时使用） ----------------
    // 放宽 R2/R3/R4/R15；R1/R5/R6/R7/R9/R10/R11/R13/R14 两档共用同列（保持硬约束）。
    // R4（单笔风险 ≤ 净值×maxRiskPct%）必须放宽：马丁单腿的「灾难兜底 SL」距离极大，
    // 若沿用 DEFAULT 档的 2%，则高腿手数（如 0.16 手 × 5.00USD × 100 = 80USD）会整腿被拒。

    /** GRID 档单笔手数上限默认（须 ≥ 马丁最高腿手数：base×multiplier^(maxLevels-1)）。 */
    private BigDecimal defaultGridMaxVolume = new BigDecimal("2.00");
    /** GRID 档最大持仓数默认（须 ≥ 马丁最大腿数）。 */
    private int defaultGridMaxPositions = 5;
    /** GRID 档盈亏比地板默认（腿开仓不带 TP，通常不触发 R15）。 */
    private BigDecimal defaultGridMinRr = new BigDecimal("0.50");
    /**
     * GRID 档单笔风险预算默认（占净值百分比，覆盖 R4 的 maxRiskPct）。
     *
     * <p>取 25 是给「灾难兜底 SL」留出空间：满 5 腿 0.31 手下最大浮亏约 26USD，
     * 而 5 腿中最高一腿（0.16 手 × 5.00USD × 100 盎司）= 80USD，需 80/净值 的比例才能不被 R4 拒单。
     * 真正的净值级保护由锁闸门（单轮/日/总）承担，R4 在此档只作「单腿过大」的粗筛。
     */
    private BigDecimal defaultGridMaxRiskPct = new BigDecimal("25.00");

    public double getContractSizePerLot() {
        return contractSizePerLot;
    }

    public void setContractSizePerLot(double contractSizePerLot) {
        this.contractSizePerLot = contractSizePerLot;
    }

    public double getPoint() {
        return point;
    }

    public void setPoint(double point) {
        this.point = point;
    }

    public double getVolumeStep() {
        return volumeStep;
    }

    public void setVolumeStep(double volumeStep) {
        this.volumeStep = volumeStep;
    }

    public double getVolumeMin() {
        return volumeMin;
    }

    public void setVolumeMin(double volumeMin) {
        this.volumeMin = volumeMin;
    }

    public int getIdempotencyWindowSeconds() {
        return idempotencyWindowSeconds;
    }

    public void setIdempotencyWindowSeconds(int idempotencyWindowSeconds) {
        this.idempotencyWindowSeconds = idempotencyWindowSeconds;
    }

    public double getMaxPriceDeviationPct() {
        return maxPriceDeviationPct;
    }

    public void setMaxPriceDeviationPct(double maxPriceDeviationPct) {
        this.maxPriceDeviationPct = maxPriceDeviationPct;
    }

    public int getSpreadWindowMinutes() {
        return spreadWindowMinutes;
    }

    public void setSpreadWindowMinutes(int spreadWindowMinutes) {
        this.spreadWindowMinutes = spreadWindowMinutes;
    }

    public int getSpreadMinSamples() {
        return spreadMinSamples;
    }

    public void setSpreadMinSamples(int spreadMinSamples) {
        this.spreadMinSamples = spreadMinSamples;
    }

    public double getSpreadMedianMultiplier() {
        return spreadMedianMultiplier;
    }

    public void setSpreadMedianMultiplier(double spreadMedianMultiplier) {
        this.spreadMedianMultiplier = spreadMedianMultiplier;
    }

    public List<String> getClosedWeekdays() {
        return closedWeekdays;
    }

    public void setClosedWeekdays(List<String> closedWeekdays) {
        this.closedWeekdays = closedWeekdays;
    }

    public String getDailyBlackoutStart() {
        return dailyBlackoutStart;
    }

    public void setDailyBlackoutStart(String dailyBlackoutStart) {
        this.dailyBlackoutStart = dailyBlackoutStart;
    }

    public String getDailyBlackoutEnd() {
        return dailyBlackoutEnd;
    }

    public void setDailyBlackoutEnd(String dailyBlackoutEnd) {
        this.dailyBlackoutEnd = dailyBlackoutEnd;
    }

    public BigDecimal getDefaultFixedVolume() {
        return defaultFixedVolume;
    }

    public void setDefaultFixedVolume(BigDecimal defaultFixedVolume) {
        this.defaultFixedVolume = defaultFixedVolume;
    }

    public BigDecimal getDefaultMaxVolume() {
        return defaultMaxVolume;
    }

    public void setDefaultMaxVolume(BigDecimal defaultMaxVolume) {
        this.defaultMaxVolume = defaultMaxVolume;
    }

    public int getDefaultMaxPositions() {
        return defaultMaxPositions;
    }

    public void setDefaultMaxPositions(int defaultMaxPositions) {
        this.defaultMaxPositions = defaultMaxPositions;
    }

    public BigDecimal getDefaultMaxDailyLoss() {
        return defaultMaxDailyLoss;
    }

    public void setDefaultMaxDailyLoss(BigDecimal defaultMaxDailyLoss) {
        this.defaultMaxDailyLoss = defaultMaxDailyLoss;
    }

    public BigDecimal getDefaultMaxRiskPct() {
        return defaultMaxRiskPct;
    }

    public void setDefaultMaxRiskPct(BigDecimal defaultMaxRiskPct) {
        this.defaultMaxRiskPct = defaultMaxRiskPct;
    }

    public BigDecimal getDefaultMinRr() {
        return defaultMinRr;
    }

    public void setDefaultMinRr(BigDecimal defaultMinRr) {
        this.defaultMinRr = defaultMinRr;
    }

    public BigDecimal getDefaultMinSlSpreadMult() {
        return defaultMinSlSpreadMult;
    }

    public void setDefaultMinSlSpreadMult(BigDecimal defaultMinSlSpreadMult) {
        this.defaultMinSlSpreadMult = defaultMinSlSpreadMult;
    }

    public int getDefaultMaxConsecutiveLoss() {
        return defaultMaxConsecutiveLoss;
    }

    public void setDefaultMaxConsecutiveLoss(int defaultMaxConsecutiveLoss) {
        this.defaultMaxConsecutiveLoss = defaultMaxConsecutiveLoss;
    }

    public int getDefaultCooldownMinutes() {
        return defaultCooldownMinutes;
    }

    public void setDefaultCooldownMinutes(int defaultCooldownMinutes) {
        this.defaultCooldownMinutes = defaultCooldownMinutes;
    }

    public BigDecimal getDefaultSpreadLimit() {
        return defaultSpreadLimit;
    }

    public void setDefaultSpreadLimit(BigDecimal defaultSpreadLimit) {
        this.defaultSpreadLimit = defaultSpreadLimit;
    }

    public String getDefaultSymbolWhitelist() {
        return defaultSymbolWhitelist;
    }

    public void setDefaultSymbolWhitelist(String defaultSymbolWhitelist) {
        this.defaultSymbolWhitelist = defaultSymbolWhitelist;
    }

    public BigDecimal getDefaultGridMaxVolume() {
        return defaultGridMaxVolume;
    }

    public void setDefaultGridMaxVolume(BigDecimal defaultGridMaxVolume) {
        this.defaultGridMaxVolume = defaultGridMaxVolume;
    }

    public int getDefaultGridMaxPositions() {
        return defaultGridMaxPositions;
    }

    public void setDefaultGridMaxPositions(int defaultGridMaxPositions) {
        this.defaultGridMaxPositions = defaultGridMaxPositions;
    }

    public BigDecimal getDefaultGridMinRr() {
        return defaultGridMinRr;
    }

    public void setDefaultGridMinRr(BigDecimal defaultGridMinRr) {
        this.defaultGridMinRr = defaultGridMinRr;
    }

    public BigDecimal getDefaultGridMaxRiskPct() {
        return defaultGridMaxRiskPct;
    }

    public void setDefaultGridMaxRiskPct(BigDecimal defaultGridMaxRiskPct) {
        this.defaultGridMaxRiskPct = defaultGridMaxRiskPct;
    }
}
