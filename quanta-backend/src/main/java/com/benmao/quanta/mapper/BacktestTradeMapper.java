package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.BacktestTrade;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** backtest_trade 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface BacktestTradeMapper {

    @Insert("INSERT INTO backtest_trade(run_id, open_time, close_time, cmd, volume, open_price, close_price, "
            + "sl, tp, profit, swap, hold_sec, exit_reason, cycle_no, level, engine_kind) "
            + "VALUES(#{runId}, #{openTime}, #{closeTime}, #{cmd}, #{volume}, #{openPrice}, #{closePrice}, "
            + "#{sl}, #{tp}, #{profit}, #{swap}, #{holdSec}, #{exitReason}, #{cycleNo}, #{level}, #{engineKind})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BacktestTrade e);

    @Select("SELECT * FROM backtest_trade WHERE id = #{id}")
    BacktestTrade getById(@Param("id") Long id);

    @Select("SELECT * FROM backtest_trade ORDER BY id")
    List<BacktestTrade> listAll();

    /** 取某次回测的全部交易明细（按开仓时间升序）。 */
    @Select("SELECT * FROM backtest_trade WHERE run_id = #{runId} ORDER BY open_time")
    List<BacktestTrade> listByRunId(@Param("runId") Long runId);

    @Update("UPDATE backtest_trade SET run_id = #{runId}, open_time = #{openTime}, close_time = #{closeTime}, "
            + "cmd = #{cmd}, volume = #{volume}, open_price = #{openPrice}, close_price = #{closePrice}, sl = #{sl}, "
            + "tp = #{tp}, profit = #{profit}, swap = #{swap}, hold_sec = #{holdSec}, exit_reason = #{exitReason}, "
            + "cycle_no = #{cycleNo}, level = #{level}, engine_kind = #{engineKind} WHERE id = #{id}")
    int update(BacktestTrade e);

    @Delete("DELETE FROM backtest_trade WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}