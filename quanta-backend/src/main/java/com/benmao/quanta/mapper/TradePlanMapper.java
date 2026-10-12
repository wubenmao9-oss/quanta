package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.TradePlan;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** trade_plan 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface TradePlanMapper {

    @Insert("INSERT INTO trade_plan(session_id, plan_type, target_price, invalid_cond, expire_at, status) "
            + "VALUES(#{sessionId}, #{planType}, #{targetPrice}, #{invalidCond}, #{expireAt}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TradePlan e);

    @Select("SELECT * FROM trade_plan WHERE id = #{id}")
    TradePlan getById(@Param("id") Long id);

    @Select("SELECT * FROM trade_plan ORDER BY id")
    List<TradePlan> listAll();

    @Update("UPDATE trade_plan SET session_id = #{sessionId}, plan_type = #{planType}, target_price = #{targetPrice}, "
            + "invalid_cond = #{invalidCond}, expire_at = #{expireAt}, status = #{status} WHERE id = #{id}")
    int update(TradePlan e);

    @Delete("DELETE FROM trade_plan WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}