package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.TradeOrder;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    // ==================== 风控层（M5）查询 ====================

    /** R6 日亏熔断：某时点之后已平仓单的已实现盈亏合计（profit + swap）。 */
    @Select("SELECT COALESCE(SUM(profit + COALESCE(swap, 0)), 0) FROM trade_order "
            + "WHERE status = 'CLOSED' AND close_time >= #{since}")
    BigDecimal sumRealizedPnlSince(@Param("since") LocalDateTime since);

    /** R7 冷静期：最近已平仓单（按平仓时间倒序）。 */
    @Select("SELECT * FROM trade_order WHERE status = 'CLOSED' AND close_time IS NOT NULL "
            + "ORDER BY close_time DESC LIMIT #{limit}")
    List<TradeOrder> listRecentClosed(@Param("limit") int limit);

    /** R8 幂等：某时点之后是否存在相同 trace_id 的下单。 */
    @Select("SELECT COUNT(1) FROM trade_order WHERE trace_id = #{traceId} AND open_time >= #{since}")
    int countByTraceIdSince(@Param("traceId") String traceId, @Param("since") LocalDateTime since);

    /** R8 幂等：某时点之后是否存在同参数（品种/方向/手数/SL/TP）的下单。 */
    @Select("SELECT COUNT(1) FROM trade_order WHERE symbol = #{symbol} AND cmd = #{cmd} "
            + "AND volume = #{volume} AND sl <=> #{sl} AND tp <=> #{tp} AND open_time >= #{since}")
    int countSameParamsSince(@Param("symbol") String symbol,
                             @Param("cmd") String cmd,
                             @Param("volume") BigDecimal volume,
                             @Param("sl") BigDecimal sl,
                             @Param("tp") BigDecimal tp,
                             @Param("since") LocalDateTime since);

    /** R13 持仓互斥：按 ticket 找在场订单（含发起会话，用于判断是否被其他会话占用）。 */
    @Select("SELECT * FROM trade_order WHERE ticket = #{ticket} ORDER BY id DESC LIMIT 1")
    TradeOrder findLatestByTicket(@Param("ticket") long ticket);
}