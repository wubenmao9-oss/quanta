package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.IndicatorConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** indicator_config 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface IndicatorConfigMapper {

    @Insert("INSERT INTO indicator_config(strategy_id, indicator_name, params) "
            + "VALUES(#{strategyId}, #{indicatorName}, #{params})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IndicatorConfig e);

    @Select("SELECT * FROM indicator_config WHERE id = #{id}")
    IndicatorConfig getById(@Param("id") Long id);

    @Select("SELECT * FROM indicator_config ORDER BY id")
    List<IndicatorConfig> listAll();

    @Update("UPDATE indicator_config SET strategy_id = #{strategyId}, indicator_name = #{indicatorName}, "
            + "params = #{params} WHERE id = #{id}")
    int update(IndicatorConfig e);

    @Delete("DELETE FROM indicator_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}