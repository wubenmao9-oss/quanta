package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.MarketKline;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** market_kline 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface MarketKlineMapper {

    @Insert("INSERT INTO market_kline(symbol, timeframe, bar_time, open, high, low, close, volume) "
            + "VALUES(#{symbol}, #{timeframe}, #{barTime}, #{open}, #{high}, #{low}, #{close}, #{volume})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MarketKline e);

    @Select("SELECT * FROM market_kline WHERE id = #{id}")
    MarketKline getById(@Param("id") Long id);

    @Select("SELECT * FROM market_kline ORDER BY id")
    List<MarketKline> listAll();

    @Update("UPDATE market_kline SET symbol = #{symbol}, timeframe = #{timeframe}, bar_time = #{barTime}, "
            + "open = #{open}, high = #{high}, low = #{low}, close = #{close}, volume = #{volume} WHERE id = #{id}")
    int update(MarketKline e);

    @Delete("DELETE FROM market_kline WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    // ==================== M7 调度层新增：盯盘 K 线去重落库 ====================

    /**
     * 按唯一键 (symbol, timeframe, bar_time) 幂等插入 K 线，重复则忽略。
     *
     * <p>供 MarketWatcher 边盯盘边落库，天然去重、无需先查后插。
     */
    @Insert("INSERT IGNORE INTO market_kline(symbol, timeframe, bar_time, open, high, low, close, volume) "
            + "VALUES(#{symbol}, #{timeframe}, #{barTime}, #{open}, #{high}, #{low}, #{close}, #{volume})")
    int insertIgnore(@Param("symbol") String symbol,
                     @Param("timeframe") String timeframe,
                     @Param("barTime") LocalDateTime barTime,
                     @Param("open") double open,
                     @Param("high") double high,
                     @Param("low") double low,
                     @Param("close") double close,
                     @Param("volume") long volume);
}