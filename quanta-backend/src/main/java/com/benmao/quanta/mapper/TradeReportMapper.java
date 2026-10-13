package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.TradeReport;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** trade_report 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface TradeReportMapper {

    @Insert("INSERT INTO trade_report(order_id, session_id, action_timeline, summary, pnl, result, reviewed) "
            + "VALUES(#{orderId}, #{sessionId}, #{actionTimeline}, #{summary}, #{pnl}, #{result}, #{reviewed})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TradeReport e);

    @Select("SELECT * FROM trade_report WHERE id = #{id}")
    TradeReport getById(@Param("id") Long id);

    @Select("SELECT * FROM trade_report ORDER BY id")
    List<TradeReport> listAll();

    /** 按订单 id 取报告（用于幂等：同一订单只出一次报告）。 */
    @Select("SELECT * FROM trade_report WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1")
    TradeReport findByOrderId(@Param("orderId") Long orderId);

    /** 最近 N 份报告（最新在前），供复盘观测。 */
    @Select("SELECT * FROM trade_report ORDER BY id DESC LIMIT #{limit}")
    List<TradeReport> listRecent(@Param("limit") int limit);

    @Update("UPDATE trade_report SET order_id = #{orderId}, session_id = #{sessionId}, "
            + "action_timeline = #{actionTimeline}, summary = #{summary}, pnl = #{pnl}, result = #{result}, "
            + "reviewed = #{reviewed} WHERE id = #{id}")
    int update(TradeReport e);

    @Delete("DELETE FROM trade_report WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}