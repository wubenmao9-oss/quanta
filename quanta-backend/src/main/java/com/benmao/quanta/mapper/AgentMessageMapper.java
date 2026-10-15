package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** agent_message 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface AgentMessageMapper {

    @Insert("INSERT INTO agent_message(session_id, role, content, tokens) "
            + "VALUES(#{sessionId}, #{role}, #{content}, #{tokens})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentMessage e);

    @Select("SELECT * FROM agent_message WHERE id = #{id}")
    AgentMessage getById(@Param("id") Long id);

    @Select("SELECT * FROM agent_message ORDER BY id")
    List<AgentMessage> listAll();

    /**
     * 会话内最近的「真正对话」消息（role=user/assistant，最新在前），
     * 供自由问答（{@code /api/agent/chat}）按 sessionId 拼接多轮上下文。
     */
    @Select("SELECT * FROM agent_message WHERE session_id = #{sessionId} "
            + "AND role IN ('user','assistant') ORDER BY id DESC LIMIT #{limit}")
    List<AgentMessage> listRecentDialogue(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    /**
     * 会话内全部「真正对话」消息（role=user/assistant，按时间升序），
     * 供前端在某个持仓会话中打开 AI 对话框时还原既有聊天记录。
     */
    @Select("SELECT * FROM agent_message WHERE session_id = #{sessionId} "
            + "AND role IN ('user','assistant') ORDER BY id ASC")
    List<AgentMessage> listDialogueAsc(@Param("sessionId") Long sessionId);

    @Update("UPDATE agent_message SET session_id = #{sessionId}, role = #{role}, content = #{content}, "
            + "tokens = #{tokens} WHERE id = #{id}")
    int update(AgentMessage e);

    @Delete("DELETE FROM agent_message WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}