package com.benmao.quanta.cycle;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** cycle 锁闸门持久化（{@code cycle_lock}）。 */
@Mapper
public interface CycleLockMapper {

    @Insert("INSERT INTO cycle_lock(lock_key, lock_until, ref_equity, ref_time) "
            + "VALUES(#{lockKey}, #{lockUntil}, #{refEquity}, #{refTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CycleLock e);

    @Select("SELECT * FROM cycle_lock WHERE lock_key = #{lockKey} LIMIT 1")
    CycleLock getByKey(@Param("lockKey") String lockKey);

    @Update("UPDATE cycle_lock SET lock_until = #{lockUntil}, ref_equity = #{refEquity}, ref_time = #{refTime} "
            + "WHERE lock_key = #{lockKey}")
    int updateByKey(CycleLock e);
}
