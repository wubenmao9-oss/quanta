package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.RiskConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** risk_config 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface RiskConfigMapper {

    @Insert("INSERT INTO risk_config(fixed_volume, max_volume, max_positions, max_daily_loss, max_risk_pct, "
            + "min_rr, min_sl_spread_mult, max_consecutive_loss, cooldown_minutes, spread_limit, symbol_whitelist) "
            + "VALUES(#{fixedVolume}, #{maxVolume}, #{maxPositions}, #{maxDailyLoss}, #{maxRiskPct}, #{minRr}, "
            + "#{minSlSpreadMult}, #{maxConsecutiveLoss}, #{cooldownMinutes}, #{spreadLimit}, #{symbolWhitelist})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RiskConfig e);

    @Select("SELECT * FROM risk_config WHERE id = #{id}")
    RiskConfig getById(@Param("id") Long id);

    @Select("SELECT * FROM risk_config ORDER BY id")
    List<RiskConfig> listAll();

    @Update("UPDATE risk_config SET fixed_volume = #{fixedVolume}, max_volume = #{maxVolume}, "
            + "max_positions = #{maxPositions}, max_daily_loss = #{maxDailyLoss}, max_risk_pct = #{maxRiskPct}, "
            + "min_rr = #{minRr}, min_sl_spread_mult = #{minSlSpreadMult}, "
            + "max_consecutive_loss = #{maxConsecutiveLoss}, cooldown_minutes = #{cooldownMinutes}, "
            + "spread_limit = #{spreadLimit}, symbol_whitelist = #{symbolWhitelist} WHERE id = #{id}")
    int update(RiskConfig e);

    @Delete("DELETE FROM risk_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}