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

    /**
     * 最近成交流水（最新在前），可选按状态过滤（OPEN/FILLED/CLOSED/…），
     * 供 {@code GET /api/trade/orders} 逐笔列表与前端净值曲线取数。
     */
    @Select("<script>SELECT * FROM trade_order"
            + "<if test='status != null'> WHERE status = #{status}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<TradeOrder> listRecent(@Param("status") String status, @Param("limit") int limit);

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

    // ==================== 复盘层（M11）查询 ====================

    /** 已平仓单（可选品种/时间窗，按平仓时间升序）：周期统计与败因分类的取数口径。 */
    @Select("<script>SELECT * FROM trade_order WHERE status = 'CLOSED' AND close_time IS NOT NULL"
            + "<if test='symbol != null'> AND symbol = #{symbol}</if>"
            + "<if test='since != null'> AND close_time &gt;= #{since}</if>"
            + "<if test='until != null'> AND close_time &lt;= #{until}</if>"
            + " ORDER BY close_time ASC, id ASC</script>")
    List<TradeOrder> listClosedBetween(@Param("symbol") String symbol,
                                       @Param("since") LocalDateTime since,
                                       @Param("until") LocalDateTime until);

    /** 按策略归属（经 agent_session.strategy_id 关联）取已平仓单，用于策略评价。 */
    @Select("<script>SELECT o.* FROM trade_order o JOIN agent_session s ON o.session_id = s.id"
            + " WHERE o.status = 'CLOSED' AND o.close_time IS NOT NULL AND s.strategy_id = #{strategyId}"
            + "<if test='since != null'> AND o.close_time &gt;= #{since}</if>"
            + "<if test='until != null'> AND o.close_time &lt;= #{until}</if>"
            + " ORDER BY o.close_time ASC, o.id ASC</script>")
    List<TradeOrder> listClosedByStrategy(@Param("strategyId") Long strategyId,
                                          @Param("since") LocalDateTime since,
                                          @Param("until") LocalDateTime until);

    // ==================== 会话生命周期对账 ====================

    /**
     * 按会话聚合订单数：total=该会话下单总数，openCnt=其中仍为在场（status='FILLED'）的单数。
     * 供会话自动关闭对账使用（total&gt;0 且 openCnt=0 表示持仓生命周期已结束）。
     */
    @Select("SELECT session_id AS sessionId, COUNT(1) AS total, "
            + "SUM(CASE WHEN status = 'FILLED' THEN 1 ELSE 0 END) AS openCnt "
            + "FROM trade_order WHERE session_id IS NOT NULL GROUP BY session_id")
    List<java.util.Map<String, Object>> sessionOrderStats();

    /**
     * 在场（status='FILLED'）且已归属会话的订单：供「一持仓一会话」列表补充持仓明细。
     * 同一会话多笔在场单时以最末一笔为准（一期为「一次一笔」）。
     */
    @Select("SELECT * FROM trade_order WHERE status = 'FILLED' AND session_id IS NOT NULL ORDER BY id")
    List<TradeOrder> listOpenWithSession();
}