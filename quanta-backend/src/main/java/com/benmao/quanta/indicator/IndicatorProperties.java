package com.benmao.quanta.indicator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 指标层可调参数（设计 §18：阈值/参数一律集中配置、不写死）。
 *
 * <p>{@code periodMin}/{@code periodMax} 即 §10 的参数边界；其余为该指标的默认周期/系数。
 * 全部可在 {@code application.yml} 的 {@code quanta.indicator.*} 下覆写。
 */
@Component
@ConfigurationProperties(prefix = "quanta.indicator")
public class IndicatorProperties {

    /** 周期类参数的合法下界（含）。 */
    private int periodMin = 2;

    /** 周期类参数的合法上界（含）。 */
    private int periodMax = 200;

    /** BOLL 标准差系数下界。 */
    private double bollKMin = 0.5;

    /** BOLL 标准差系数上界。 */
    private double bollKMax = 5.0;

    private int smaPeriod = 20;
    private int emaPeriod = 20;
    private int rsiPeriod = 14;
    private int macdFast = 12;
    private int macdSlow = 26;
    private int macdSignal = 9;
    private int bollPeriod = 20;
    private double bollK = 2.0;
    private int atrPeriod = 14;
    private int volumeMaPeriod = 5;

    /** 通道/趋势类 ATR 倍数的合法下界。 */
    private double multiplierMin = 0.5;

    /** 通道/趋势类 ATR 倍数的合法上界。 */
    private double multiplierMax = 5.0;

    private int adxPeriod = 14;
    private int aroonPeriod = 14;
    private int stochPeriod = 9;
    private int stochKPeriod = 3;
    private int stochDPeriod = 3;
    private int cciPeriod = 20;
    private int willrPeriod = 14;
    private int rocPeriod = 10;
    private int keltnerPeriod = 20;
    private double keltnerK = 2.0;
    private int donchianPeriod = 20;
    private int bbwPeriod = 20;
    private double bbwK = 2.0;
    private int bbpPeriod = 20;
    private double bbpK = 2.0;
    private int stddevPeriod = 20;
    private int chopPeriod = 14;
    private int supertrendPeriod = 10;
    private double supertrendK = 3.0;
    private int adrPeriod = 14;

    public int getPeriodMin() {
        return periodMin;
    }

    public void setPeriodMin(int periodMin) {
        this.periodMin = periodMin;
    }

    public int getPeriodMax() {
        return periodMax;
    }

    public void setPeriodMax(int periodMax) {
        this.periodMax = periodMax;
    }

    public double getBollKMin() {
        return bollKMin;
    }

    public void setBollKMin(double bollKMin) {
        this.bollKMin = bollKMin;
    }

    public double getBollKMax() {
        return bollKMax;
    }

    public void setBollKMax(double bollKMax) {
        this.bollKMax = bollKMax;
    }

    public int getSmaPeriod() {
        return smaPeriod;
    }

    public void setSmaPeriod(int smaPeriod) {
        this.smaPeriod = smaPeriod;
    }

    public int getEmaPeriod() {
        return emaPeriod;
    }

    public void setEmaPeriod(int emaPeriod) {
        this.emaPeriod = emaPeriod;
    }

    public int getRsiPeriod() {
        return rsiPeriod;
    }

    public void setRsiPeriod(int rsiPeriod) {
        this.rsiPeriod = rsiPeriod;
    }

    public int getMacdFast() {
        return macdFast;
    }

    public void setMacdFast(int macdFast) {
        this.macdFast = macdFast;
    }

    public int getMacdSlow() {
        return macdSlow;
    }

    public void setMacdSlow(int macdSlow) {
        this.macdSlow = macdSlow;
    }

    public int getMacdSignal() {
        return macdSignal;
    }

    public void setMacdSignal(int macdSignal) {
        this.macdSignal = macdSignal;
    }

    public int getBollPeriod() {
        return bollPeriod;
    }

    public void setBollPeriod(int bollPeriod) {
        this.bollPeriod = bollPeriod;
    }

    public double getBollK() {
        return bollK;
    }

    public void setBollK(double bollK) {
        this.bollK = bollK;
    }

    public int getAtrPeriod() {
        return atrPeriod;
    }

    public void setAtrPeriod(int atrPeriod) {
        this.atrPeriod = atrPeriod;
    }

    public int getVolumeMaPeriod() {
        return volumeMaPeriod;
    }

    public void setVolumeMaPeriod(int volumeMaPeriod) {
        this.volumeMaPeriod = volumeMaPeriod;
    }

    public double getMultiplierMin() {
        return multiplierMin;
    }

    public void setMultiplierMin(double multiplierMin) {
        this.multiplierMin = multiplierMin;
    }

    public double getMultiplierMax() {
        return multiplierMax;
    }

    public void setMultiplierMax(double multiplierMax) {
        this.multiplierMax = multiplierMax;
    }

    public int getAdxPeriod() {
        return adxPeriod;
    }

    public void setAdxPeriod(int adxPeriod) {
        this.adxPeriod = adxPeriod;
    }

    public int getAroonPeriod() {
        return aroonPeriod;
    }

    public void setAroonPeriod(int aroonPeriod) {
        this.aroonPeriod = aroonPeriod;
    }

    public int getStochPeriod() {
        return stochPeriod;
    }

    public void setStochPeriod(int stochPeriod) {
        this.stochPeriod = stochPeriod;
    }

    public int getStochKPeriod() {
        return stochKPeriod;
    }

    public void setStochKPeriod(int stochKPeriod) {
        this.stochKPeriod = stochKPeriod;
    }

    public int getStochDPeriod() {
        return stochDPeriod;
    }

    public void setStochDPeriod(int stochDPeriod) {
        this.stochDPeriod = stochDPeriod;
    }

    public int getCciPeriod() {
        return cciPeriod;
    }

    public void setCciPeriod(int cciPeriod) {
        this.cciPeriod = cciPeriod;
    }

    public int getWillrPeriod() {
        return willrPeriod;
    }

    public void setWillrPeriod(int willrPeriod) {
        this.willrPeriod = willrPeriod;
    }

    public int getRocPeriod() {
        return rocPeriod;
    }

    public void setRocPeriod(int rocPeriod) {
        this.rocPeriod = rocPeriod;
    }

    public int getKeltnerPeriod() {
        return keltnerPeriod;
    }

    public void setKeltnerPeriod(int keltnerPeriod) {
        this.keltnerPeriod = keltnerPeriod;
    }

    public double getKeltnerK() {
        return keltnerK;
    }

    public void setKeltnerK(double keltnerK) {
        this.keltnerK = keltnerK;
    }

    public int getDonchianPeriod() {
        return donchianPeriod;
    }

    public void setDonchianPeriod(int donchianPeriod) {
        this.donchianPeriod = donchianPeriod;
    }

    public int getBbwPeriod() {
        return bbwPeriod;
    }

    public void setBbwPeriod(int bbwPeriod) {
        this.bbwPeriod = bbwPeriod;
    }

    public double getBbwK() {
        return bbwK;
    }

    public void setBbwK(double bbwK) {
        this.bbwK = bbwK;
    }

    public int getBbpPeriod() {
        return bbpPeriod;
    }

    public void setBbpPeriod(int bbpPeriod) {
        this.bbpPeriod = bbpPeriod;
    }

    public double getBbpK() {
        return bbpK;
    }

    public void setBbpK(double bbpK) {
        this.bbpK = bbpK;
    }

    public int getStddevPeriod() {
        return stddevPeriod;
    }

    public void setStddevPeriod(int stddevPeriod) {
        this.stddevPeriod = stddevPeriod;
    }

    public int getChopPeriod() {
        return chopPeriod;
    }

    public void setChopPeriod(int chopPeriod) {
        this.chopPeriod = chopPeriod;
    }

    public int getSupertrendPeriod() {
        return supertrendPeriod;
    }

    public void setSupertrendPeriod(int supertrendPeriod) {
        this.supertrendPeriod = supertrendPeriod;
    }

    public double getSupertrendK() {
        return supertrendK;
    }

    public void setSupertrendK(double supertrendK) {
        this.supertrendK = supertrendK;
    }

    public int getAdrPeriod() {
        return adrPeriod;
    }

    public void setAdrPeriod(int adrPeriod) {
        this.adrPeriod = adrPeriod;
    }
}