package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentDecisionLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** agent_decision_log 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface AgentDecisionLogMapper {

    @Insert("INSERT INTO agent_decision_log(session_id, trace_id, trigger_type, input_snapshot, model, "
            + "prompt_version, action, action_params, confidence, reason, tokens, latency_ms) "
            + "VALUES(#{sessionId}, #{traceId}, #{triggerType}, #{inputSnapshot}, #{model}, #{promptVersion}, "
            + "#{action}, #{actionParams}, #{confidence}, #{reason}, #{tokens}, #{latencyMs})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentDecisionLog e);

    @Select("SELECT * FROM agent_decision_log WHERE id = #{id}")
    AgentDecisionLog getById(@Param("id") Long id);

    @Select("SELECT * FROM agent_decision_log ORDER BY id")
    List<AgentDecisionLog> listAll();

    /** 最近 N 条（最新在前），供 /api/agent/decisions 观测。 */
    @Select("SELECT * FROM agent_decision_log ORDER BY id DESC LIMIT #{limit}")
    List<AgentDecisionLog> listRecent(@Param("limit") int limit);

    @Update("UPDATE agent_decision_log SET session_id = #{sessionId}, trace_id = #{traceId}, "
            + "trigger_type = #{triggerType}, input_snapshot = #{inputSnapshot}, model = #{model}, "
            + "prompt_version = #{promptVersion}, action = #{action}, action_params = #{actionParams}, "
            + "confidence = #{confidence}, reason = #{reason}, tokens = #{tokens}, latency_ms = #{latencyMs} "
            + "WHERE id = #{id}")
    int update(AgentDecisionLog e);

    @Delete("DELETE FROM agent_decision_log WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}