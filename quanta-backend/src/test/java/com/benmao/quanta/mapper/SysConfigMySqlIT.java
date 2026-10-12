package com.benmao.quanta.mapper;

import com.benmao.quanta.config.crypto.CryptoService;
import com.benmao.quanta.config.entity.SysConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * sys_config 真库集成测试：验证「密文入库 + 可解密回原文」。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=SysConfigMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class SysConfigMySqlIT {

    @Autowired
    private SysConfigMapper mapper;

    @Autowired
    private CryptoService crypto;

    @Test
    void insertAndSelect_storesCipherTextOnly() {
        String plain = "{\"apiKey\":\"sk-plain-secret-1234\"}";
        SysConfig entity = new SysConfig();
        entity.setConfigType("LLM");
        entity.setName("it-llm-" + System.currentTimeMillis());
        entity.setConfigJson(crypto.encrypt(plain));
        entity.setIsActive(false);
        entity.setTestStatus("SUCCESS");
        entity.setTestedAt(LocalDateTime.now());

        mapper.insert(entity);
        assertNotNull(entity.getId(), "insert 应回填自增 id");

        SysConfig loaded = mapper.getById(entity.getId());
        assertNotNull(loaded);
        // 库中存的是密文，不是明文
        assertNotEquals(plain, loaded.getConfigJson());
        // 且可解密回原文
        assertEquals(plain, crypto.decrypt(loaded.getConfigJson()));

        // 清理，避免污染
        mapper.deleteById(entity.getId());
    }
}