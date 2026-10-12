package com.benmao.quanta.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实 MySQL 连通性集成测试。
 *
 * <p>默认不执行：类名以 {@code IT} 结尾不被 Surefire 默认包含；
 * 且额外用环境变量 {@code RUN_DB_IT=true} 门控。
 * 需要真库时：
 * {@code mvn -pl quanta-backend test -Dtest=QuantaMySqlIT -DRUN_DB_IT=true}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class QuantaMySqlIT {

    @Autowired
    private SystemProbeMapper systemProbeMapper;

    @Test
    void shouldConnectToMySql() {
        assertEquals(1, systemProbeMapper.ping());
    }
}