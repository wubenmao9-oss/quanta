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

    // ==================== 记忆检索（M11，设计 §11 一期按标签检索） ====================

    /** 最近 N 张记忆卡（最新在前）。 */
    @Select("SELECT * FROM agent_memory ORDER BY id DESC LIMIT #{limit}")
    List<AgentMemory> listRecent(@Param("limit") int limit);

    /** 按作用域检索（GLOBAL/SYMBOL/STRATEGY）。 */
    @Select("SELECT * FROM agent_memory WHERE scope = #{scope} ORDER BY id DESC LIMIT #{limit}")
    List<AgentMemory> listByScope(@Param("scope") String scope, @Param("limit") int limit);

    /** 按品种检索。 */
    @Select("SELECT * FROM agent_memory WHERE symbol = #{symbol} ORDER BY id DESC LIMIT #{limit}")
    List<AgentMemory> listBySymbol(@Param("symbol") String symbol, @Param("limit") int limit);

    /** 按策略检索。 */
    @Select("SELECT * FROM agent_memory WHERE strategy_id = #{strategyId} ORDER BY id DESC LIMIT #{limit}")
    List<AgentMemory> listByStrategy(@Param("strategyId") Long strategyId, @Param("limit") int limit);

    /** 按标签模糊检索（tags 为逗号分隔标签串）。 */
    @Select("SELECT * FROM agent_memory WHERE tags LIKE CONCAT('%', #{tag}, '%') ORDER BY id DESC LIMIT #{limit}")
    List<AgentMemory> listByTag(@Param("tag") String tag, @Param("limit") int limit);
}