package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** agent_memory 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface AgentMemoryMapper {

    @Insert("INSERT INTO agent_memory(scope, symbol, strategy_id, regime, summary, tags, source_trade) "
            + "VALUES(#{scope}, #{symbol}, #{strategyId}, #{regime}, #{summary}, #{tags}, #{sourceTrade})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentMemory e);

    @Select("SELECT * FROM agent_memory WHERE id = #{id}")
    AgentMemory getById(@Param("id") Long id);

    @Select("SELECT * FROM agent_memory ORDER BY id")
    List<AgentMemory> listAll();

    @Update("UPDATE agent_memory SET scope = #{scope}, symbol = #{symbol}, strategy_id = #{strategyId}, "
            + "regime = #{regime}, summary = #{summary}, tags = #{tags}, source_trade = #{sourceTrade} "
            + "WHERE id = #{id}")
    int update(AgentMemory e);

    @Delete("DELETE FROM agent_memory WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}