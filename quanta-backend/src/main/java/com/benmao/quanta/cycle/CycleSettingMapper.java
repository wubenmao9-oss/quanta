package com.benmao.quanta.cycle;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** cycle 锁档设置持久化（{@code cycle_setting}，单行 {@code setting_key=DEFAULT}）。 */
@Mapper
public interface CycleSettingMapper {

    @Insert("INSERT INTO cycle_setting(setting_key, max_single_loss_pct, max_daily_loss_pct, "
            + "max_total_loss_pct, lock_hours) "
            + "VALUES(#{settingKey}, #{maxSingleLossPct}, #{maxDailyLossPct}, "
            + "#{maxTotalLossPct}, #{lockHours})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CycleSetting e);

    @Select("SELECT * FROM cycle_setting WHERE setting_key = #{settingKey} LIMIT 1")
    CycleSetting getByKey(@Param("settingKey") String settingKey);

    @Update("UPDATE cycle_setting SET max_single_loss_pct = #{maxSingleLossPct}, "
            + "max_daily_loss_pct = #{maxDailyLossPct}, max_total_loss_pct = #{maxTotalLossPct}, "
            + "lock_hours = #{lockHours} WHERE setting_key = #{settingKey}")
    int updateByKey(CycleSetting e);
}
