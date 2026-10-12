package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.NotifyLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** notify_log 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface NotifyLogMapper {

    @Insert("INSERT INTO notify_log(notify_type, channel, subject, content, status, error) "
            + "VALUES(#{notifyType}, #{channel}, #{subject}, #{content}, #{status}, #{error})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NotifyLog e);

    @Select("SELECT * FROM notify_log WHERE id = #{id}")
    NotifyLog getById(@Param("id") Long id);

    @Select("SELECT * FROM notify_log ORDER BY id")
    List<NotifyLog> listAll();

    @Update("UPDATE notify_log SET notify_type = #{notifyType}, channel = #{channel}, subject = #{subject}, "
            + "content = #{content}, status = #{status}, error = #{error} WHERE id = #{id}")
    int update(NotifyLog e);

    @Delete("DELETE FROM notify_log WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}