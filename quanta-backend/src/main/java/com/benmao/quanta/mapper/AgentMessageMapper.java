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

    @Update("UPDATE agent_message SET session_id = #{sessionId}, role = #{role}, content = #{content}, "
            + "tokens = #{tokens} WHERE id = #{id}")
    int update(AgentMessage e);

    @Delete("DELETE FROM agent_message WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}