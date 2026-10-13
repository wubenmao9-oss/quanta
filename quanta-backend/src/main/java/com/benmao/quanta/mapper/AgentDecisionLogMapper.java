package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentDecisionLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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

    // ==================== 记忆层（M11）查询 ====================

    /** 会话内决策总数：记忆卡「每 N 次对话」的计数来源（设计 §11）。 */
    @Select("SELECT COUNT(1) FROM agent_decision_log WHERE session_id = #{sessionId}")
    int countBySession(@Param("sessionId") Long sessionId);

    /** 会话内最近 N 条决策（最新在前），用于拼装结构化记忆卡。 */
    @Select("SELECT * FROM agent_decision_log WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<AgentDecisionLog> listRecentBySession(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    // ==================== 复盘层（M11）查询 ====================

    /** 按 trace_id 取决策链（trace 贯穿 决策→下单→流水→报告）。 */
    @Select("SELECT * FROM agent_decision_log WHERE trace_id = #{traceId} ORDER BY id ASC")
    List<AgentDecisionLog> listByTraceId(@Param("traceId") String traceId);

    /** 区间内某类动作的决策次数（如统计 WAIT 次数 = 「无信号错过」）。 */
    @Select("<script>SELECT COUNT(1) FROM agent_decision_log WHERE action = #{action}"
            + "<if test='since != null'> AND create_time &gt;= #{since}</if>"
            + "<if test='until != null'> AND create_time &lt;= #{until}</if></script>")
    int countByActionBetween(@Param("action") String action,
                             @Param("since") LocalDateTime since,
                             @Param("until") LocalDateTime until);

    // ==================== 预算层（M12）查询 ====================

    /** 区间内 Token 用量之和（按日聚合预算：create_time ∈ [since, until)）；无记录返回 0。 */
    @Select("SELECT COALESCE(SUM(tokens), 0) FROM agent_decision_log "
            + "WHERE create_time >= #{since} AND create_time < #{until}")
    Long sumTokensBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Update("UPDATE agent_decision_log SET session_id = #{sessionId}, trace_id = #{traceId}, "
            + "trigger_type = #{triggerType}, input_snapshot = #{inputSnapshot}, model = #{model}, "
            + "prompt_version = #{promptVersion}, action = #{action}, action_params = #{actionParams}, "
            + "confidence = #{confidence}, reason = #{reason}, tokens = #{tokens}, latency_ms = #{latencyMs} "
            + "WHERE id = #{id}")
    int update(AgentDecisionLog e);

    @Delete("DELETE FROM agent_decision_log WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}