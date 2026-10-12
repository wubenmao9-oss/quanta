package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.Strategy;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** strategy 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface StrategyMapper {

    @Insert("INSERT INTO strategy(name, prompt, version, status, parent_id, origin, approved, regime_type, "
            + "target_rr, expected_win_rate, indicator_params, description) "
            + "VALUES(#{name}, #{prompt}, #{version}, #{status}, #{parentId}, #{origin}, #{approved}, #{regimeType}, "
            + "#{targetRr}, #{expectedWinRate}, #{indicatorParams}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Strategy e);

    @Select("SELECT * FROM strategy WHERE id = #{id}")
    Strategy getById(@Param("id") Long id);

    @Select("SELECT * FROM strategy ORDER BY id")
    List<Strategy> listAll();

    /** 按 (name, version) 查（唯一键）；回测落库时用于幂等获取内置规则策略行。 */
    @Select("SELECT * FROM strategy WHERE name = #{name} AND version = #{version}")
    Strategy findByNameVersion(@Param("name") String name, @Param("version") int version);

    @Update("UPDATE strategy SET name = #{name}, prompt = #{prompt}, version = #{version}, status = #{status}, "
            + "parent_id = #{parentId}, origin = #{origin}, approved = #{approved}, regime_type = #{regimeType}, "
            + "target_rr = #{targetRr}, expected_win_rate = #{expectedWinRate}, indicator_params = #{indicatorParams}, "
            + "description = #{description} WHERE id = #{id}")
    int update(Strategy e);

    @Delete("DELETE FROM strategy WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}