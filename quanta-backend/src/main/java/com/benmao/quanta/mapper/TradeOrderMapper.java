package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.TradeOrder;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** trade_order 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface TradeOrderMapper {

    @Insert("INSERT INTO trade_order(plan_id, session_id, trace_id, ticket, symbol, cmd, volume, price, sl, tp, "
            + "tp_mode, trail_mode, status, retcode, profit, swap, open_time, close_price, close_time) "
            + "VALUES(#{planId}, #{sessionId}, #{traceId}, #{ticket}, #{symbol}, #{cmd}, #{volume}, #{price}, #{sl}, "
            + "#{tp}, #{tpMode}, #{trailMode}, #{status}, #{retcode}, #{profit}, #{swap}, #{openTime}, "
            + "#{closePrice}, #{closeTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TradeOrder e);

    @Select("SELECT * FROM trade_order WHERE id = #{id}")
    TradeOrder getById(@Param("id") Long id);

    @Select("SELECT * FROM trade_order ORDER BY id")
    List<TradeOrder> listAll();

    @Update("UPDATE trade_order SET plan_id = #{planId}, session_id = #{sessionId}, trace_id = #{traceId}, "
            + "ticket = #{ticket}, symbol = #{symbol}, cmd = #{cmd}, volume = #{volume}, price = #{price}, sl = #{sl}, "
            + "tp = #{tp}, tp_mode = #{tpMode}, trail_mode = #{trailMode}, status = #{status}, retcode = #{retcode}, "
            + "profit = #{profit}, swap = #{swap}, open_time = #{openTime}, close_price = #{closePrice}, "
            + "close_time = #{closeTime} WHERE id = #{id}")
    int update(TradeOrder e);

    @Delete("DELETE FROM trade_order WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}