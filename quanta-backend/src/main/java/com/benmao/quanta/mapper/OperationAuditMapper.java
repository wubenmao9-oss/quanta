package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.OperationAudit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** operation_audit 的持久化操作（注解 SQL；列名 → 字段靠 map-underscore-to-camel-case）。 */
@Mapper
public interface OperationAuditMapper {

    @Insert("INSERT INTO operation_audit(operator, op_type, target, before_val, after_val, trace_id) "
            + "VALUES(#{operator}, #{opType}, #{target}, #{beforeVal}, #{afterVal}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OperationAudit e);

    @Select("SELECT * FROM operation_audit WHERE id = #{id}")
    OperationAudit getById(@Param("id") Long id);

    @Select("SELECT * FROM operation_audit ORDER BY id")
    List<OperationAudit> listAll();

    @Update("UPDATE operation_audit SET operator = #{operator}, op_type = #{opType}, target = #{target}, "
            + "before_val = #{beforeVal}, after_val = #{afterVal}, trace_id = #{traceId} WHERE id = #{id}")
    int update(OperationAudit e);

    @Delete("DELETE FROM operation_audit WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}