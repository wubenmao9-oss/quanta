package com.benmao.quanta.cycle;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 实盘轮主表（{@code martin_cycle}）持久化（注解 SQL；列名→字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface CycleMapper {

    @Insert("INSERT INTO martin_cycle(cycle_no, symbol, side, mode, state, level, base_volume, total_volume, "
            + "avg_entry, basket_tp, cycle_start_equity, session_id, opened_at, closed_at, close_reason, realized_pnl) "
            + "VALUES(#{cycleNo}, #{symbol}, #{side}, #{mode}, #{state}, #{level}, #{baseVolume}, #{totalVolume}, "
            + "#{avgEntry}, #{basketTp}, #{cycleStartEquity}, #{sessionId}, #{openedAt}, #{closedAt}, #{closeReason}, "
            + "#{realizedPnl})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MartinCycle e);

    @Select("SELECT * FROM martin_cycle WHERE id = #{id}")
    MartinCycle getById(@Param("id") Long id);

    @Select("SELECT * FROM martin_cycle ORDER BY id DESC LIMIT #{limit}")
    List<MartinCycle> listRecent(@Param("limit") int limit);

    /** 当前活跃轮（未收尾的最新一条）。 */
    @Select("SELECT * FROM martin_cycle WHERE state <> 'CLOSED' ORDER BY id DESC LIMIT 1")
    MartinCycle findActive();

    /** 已用过的最大轮号（无则 0）。 */
    @Select("SELECT COALESCE(MAX(cycle_no), 0) FROM martin_cycle")
    int maxCycleNo();

    @Update("UPDATE martin_cycle SET cycle_no = #{cycleNo}, symbol = #{symbol}, side = #{side}, mode = #{mode}, "
            + "state = #{state}, level = #{level}, base_volume = #{baseVolume}, total_volume = #{totalVolume}, "
            + "avg_entry = #{avgEntry}, basket_tp = #{basketTp}, cycle_start_equity = #{cycleStartEquity}, "
            + "session_id = #{sessionId}, opened_at = #{openedAt}, closed_at = #{closedAt}, "
            + "close_reason = #{closeReason}, realized_pnl = #{realizedPnl} WHERE id = #{id}")
    int update(MartinCycle e);
}
