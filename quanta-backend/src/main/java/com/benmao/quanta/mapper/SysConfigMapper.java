package com.benmao.quanta.mapper;

import com.benmao.quanta.config.entity.SysConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** sys_config 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface SysConfigMapper {

    @Select("SELECT * FROM sys_config WHERE config_type = #{configType} ORDER BY id")
    List<SysConfig> listByType(@Param("configType") String configType);

    @Select("SELECT * FROM sys_config ORDER BY id")
    List<SysConfig> listAll();

    @Select("SELECT * FROM sys_config WHERE id = #{id}")
    SysConfig getById(@Param("id") Long id);

    @Select("SELECT * FROM sys_config WHERE config_type = #{configType} AND name = #{name}")
    SysConfig getByTypeAndName(@Param("configType") String configType, @Param("name") String name);

    /** 新增：create_time/update_time 由数据库默认值填充；is_active/test_status/tested_at 显式写入。 */
    @Insert("INSERT INTO sys_config(config_type, name, config_json, is_active, test_status, tested_at) "
            + "VALUES(#{configType}, #{name}, #{configJson}, #{isActive}, #{testStatus}, #{testedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SysConfig entity);

    /** 更新：只改内容与测试状态，不动 config_type/name（name 变更需走删除重建，避免唯一键歧义）。 */
    @Update("UPDATE sys_config SET config_json = #{configJson}, is_active = #{isActive}, "
            + "test_status = #{testStatus}, tested_at = #{testedAt} WHERE id = #{id}")
    int update(SysConfig entity);

    @Delete("DELETE FROM sys_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 同类型全部置为非激活。 */
    @Update("UPDATE sys_config SET is_active = 0 WHERE config_type = #{configType}")
    int deactivateByType(@Param("configType") String configType);

    /** 指定 id 置为激活。 */
    @Update("UPDATE sys_config SET is_active = 1 WHERE id = #{id}")
    int activateById(@Param("id") Long id);
}