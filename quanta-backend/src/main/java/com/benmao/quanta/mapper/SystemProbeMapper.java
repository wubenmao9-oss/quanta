package com.benmao.quanta.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 系统探针 Mapper：仅用于验证数据源连通性（M0 阶段无业务表）。 */
@Mapper
public interface SystemProbeMapper {

    @Select("SELECT 1")
    Integer ping();
}