package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.BacktestRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** backtest_run 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface BacktestRunMapper {

    @Insert("INSERT INTO backtest_run(strategy_id, symbol, data_timeframe, start_time, end_time, params, "
            + "spread_mode, spread, slippage, swap_on, mode, win_rate, profit_factor, max_drawdown, trade_count, "
            + "avg_hold_sec, status) "
            + "VALUES(#{strategyId}, #{symbol}, #{dataTimeframe}, #{startTime}, #{endTime}, #{params}, "
            + "#{spreadMode}, #{spread}, #{slippage}, #{swapOn}, #{mode}, #{winRate}, #{profitFactor}, "
            + "#{maxDrawdown}, #{tradeCount}, #{avgHoldSec}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BacktestRun e);

    @Select("SELECT * FROM backtest_run WHERE id = #{id}")
    BacktestRun getById(@Param("id") Long id);

    @Select("SELECT * FROM backtest_run ORDER BY id")
    List<BacktestRun> listAll();

    @Update("UPDATE backtest_run SET strategy_id = #{strategyId}, symbol = #{symbol}, "
            + "data_timeframe = #{dataTimeframe}, start_time = #{startTime}, end_time = #{endTime}, "
            + "params = #{params}, spread_mode = #{spreadMode}, spread = #{spread}, slippage = #{slippage}, "
            + "swap_on = #{swapOn}, mode = #{mode}, win_rate = #{winRate}, profit_factor = #{profitFactor}, "
            + "max_drawdown = #{maxDrawdown}, trade_count = #{tradeCount}, avg_hold_sec = #{avgHoldSec}, "
            + "status = #{status} WHERE id = #{id}")
    int update(BacktestRun e);

    @Delete("DELETE FROM backtest_run WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}