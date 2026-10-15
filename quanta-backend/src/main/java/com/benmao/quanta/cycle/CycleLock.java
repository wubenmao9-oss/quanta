package com.benmao.quanta.cycle;

import java.time.LocalDateTime;

/** cycle 执行器锁闸门实体（表 {@code cycle_lock}）：按 {@code lockKey}（DAY/TOTAL）各一行。 */
public class CycleLock {

    private Long id;
    /** 锁键：TOTAL（总亏锁，持久化）。 */
    private String lockKey;
    /** 锁到期时间（null = 未锁）。 */
    private LocalDateTime lockUntil;
    /** 参照权益（总亏基准）。 */
    private Double refEquity;
    /** 参照时间（统计已实现盈亏的起点）。 */
    private LocalDateTime refTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLockKey() {
        return lockKey;
    }

    public void setLockKey(String lockKey) {
        this.lockKey = lockKey;
    }

    public LocalDateTime getLockUntil() {
        return lockUntil;
    }

    public void setLockUntil(LocalDateTime lockUntil) {
        this.lockUntil = lockUntil;
    }

    public Double getRefEquity() {
        return refEquity;
    }

    public void setRefEquity(Double refEquity) {
        this.refEquity = refEquity;
    }

    public LocalDateTime getRefTime() {
        return refTime;
    }

    public void setRefTime(LocalDateTime refTime) {
        this.refTime = refTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
