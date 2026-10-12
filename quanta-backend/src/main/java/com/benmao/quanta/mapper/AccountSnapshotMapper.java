package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AccountSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** account_snapshot 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface AccountSnapshotMapper {

    @Insert("INSERT INTO account_snapshot(login, balance, equity, margin, free_margin, profit, currency, "
            + "snapshot_time) "
            + "VALUES(#{login}, #{balance}, #{equity}, #{margin}, #{freeMargin}, #{profit}, #{currency}, "
            + "#{snapshotTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AccountSnapshot e);

    @Select("SELECT * FROM account_snapshot WHERE id = #{id}")
    AccountSnapshot getById(@Param("id") Long id);

    @Select("SELECT * FROM account_snapshot ORDER BY id")
    List<AccountSnapshot> listAll();

    @Update("UPDATE account_snapshot SET login = #{login}, balance = #{balance}, equity = #{equity}, "
            + "margin = #{margin}, free_margin = #{freeMargin}, profit = #{profit}, currency = #{currency}, "
            + "snapshot_time = #{snapshotTime} WHERE id = #{id}")
    int update(AccountSnapshot e);

    @Delete("DELETE FROM account_snapshot WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}