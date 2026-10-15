package com.benmao.quanta.cycle;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 实盘轮腿表（{@code martin_cycle_leg}）持久化。 */
@Mapper
public interface CycleLegMapper {

    @Insert("INSERT INTO martin_cycle_leg(cycle_id, level, ticket, side, volume, entry_price, sl, opened_at, "
            + "closed_at, close_price, realized_pnl) "
            + "VALUES(#{cycleId}, #{level}, #{ticket}, #{side}, #{volume}, #{entryPrice}, #{sl}, #{openedAt}, "
            + "#{closedAt}, #{closePrice}, #{realizedPnl})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MartinCycleLeg e);

    @Select("SELECT * FROM martin_cycle_leg WHERE cycle_id = #{cycleId} ORDER BY level")
    List<MartinCycleLeg> listByCycle(@Param("cycleId") Long cycleId);

    @Select("SELECT * FROM martin_cycle_leg WHERE ticket = #{ticket} ORDER BY id DESC LIMIT 1")
    MartinCycleLeg getByTicket(@Param("ticket") Long ticket);

    @Update("UPDATE martin_cycle_leg SET cycle_id = #{cycleId}, level = #{level}, ticket = #{ticket}, side = #{side}, "
            + "volume = #{volume}, entry_price = #{entryPrice}, sl = #{sl}, opened_at = #{openedAt}, "
            + "closed_at = #{closedAt}, close_price = #{closePrice}, realized_pnl = #{realizedPnl} WHERE id = #{id}")
    int update(MartinCycleLeg e);
}
