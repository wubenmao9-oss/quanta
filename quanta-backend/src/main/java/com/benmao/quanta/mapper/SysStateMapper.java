package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.SysState;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** sys_state 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface SysStateMapper {

    @Insert("INSERT INTO sys_state(run_mode, degraded, token_used_today, token_date, active_llm_id, "
            + "active_strategy_id) "
            + "VALUES(#{runMode}, #{degraded}, #{tokenUsedToday}, #{tokenDate}, #{activeLlmId}, #{activeStrategyId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SysState e);

    @Select("SELECT * FROM sys_state WHERE id = #{id}")
    SysState getById(@Param("id") Long id);

    @Select("SELECT * FROM sys_state ORDER BY id")
    List<SysState> listAll();

    @Update("UPDATE sys_state SET run_mode = #{runMode}, degraded = #{degraded}, "
            + "token_used_today = #{tokenUsedToday}, token_date = #{tokenDate}, active_llm_id = #{activeLlmId}, "
            + "active_strategy_id = #{activeStrategyId} WHERE id = #{id}")
    int update(SysState e);

    @Delete("DELETE FROM sys_state WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}