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
}