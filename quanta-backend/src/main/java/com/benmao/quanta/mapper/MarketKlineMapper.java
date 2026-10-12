package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.MarketKline;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}