package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** agent_session 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface AgentSessionMapper {

    @Insert("INSERT INTO agent_session(session_no, session_type, symbol, strategy_id, llm_config_id, status, "
            + "linked_ticket, message_count, close_time) "
            + "VALUES(#{sessionNo}, #{sessionType}, #{symbol}, #{strategyId}, #{llmConfigId}, #{status}, "
            + "#{linkedTicket}, #{messageCount}, #{closeTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentSession e);

    @Select("SELECT * FROM agent_session WHERE id = #{id}")
    AgentSession getById(@Param("id") Long id);

    @Select("SELECT * FROM agent_session ORDER BY id")
    List<AgentSession> listAll();

    @Update("UPDATE agent_session SET session_no = #{sessionNo}, session_type = #{sessionType}, symbol = #{symbol}, "
            + "strategy_id = #{strategyId}, llm_config_id = #{llmConfigId}, status = #{status}, "
            + "linked_ticket = #{linkedTicket}, message_count = #{messageCount}, close_time = #{closeTime} "
            + "WHERE id = #{id}")
    int update(AgentSession e);

    @Delete("DELETE FROM agent_session WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}