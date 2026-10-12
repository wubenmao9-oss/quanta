package com.benmao.quanta.config;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.crypto.CryptoService;
import com.benmao.quanta.config.dto.ConfigRequest;
import com.benmao.quanta.config.dto.ConfigTestResult;
import com.benmao.quanta.config.dto.SysConfigView;
import com.benmao.quanta.config.entity.SysConfig;
import com.benmao.quanta.config.tester.ConnectionTester;
import com.benmao.quanta.mapper.SysConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ConfigService 单元测试：测试通过才保存、密文入库、掩码回显、激活互斥、参数校验。 */
class ConfigServiceTest {

    private static final String AES_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private SysConfigMapper mapper;
    private CryptoService crypto;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = mock(SysConfigMapper.class);
        crypto = new CryptoService(AES_KEY);
    }

    private ConfigService service(ConnectionTester... testers) {
        return new ConfigService(mapper, crypto, json, List.of(testers));
    }

    private static ConnectionTester okTester(ConfigType type) {
        return new ConnectionTester() {
            @Override
            public ConfigType type() {
                return type;
            }

            @Override
            public ConfigTestResult test(Map<String, Object> payload) {
                return new ConfigTestResult(true, "ok", 1, Map.of());
            }
        };
    }

    private static ConnectionTester failTester(ConfigType type) {
        return new ConnectionTester() {
            @Override
            public ConfigType type() {
                return type;
            }

            @Override
            public ConfigTestResult test(Map<String, Object> payload) {
                return new ConfigTestResult(false, "boom", 1, Map.of());
            }
        };
    }

    private static ConfigRequest llmRequest(String name, String apiKey) {
        ConfigRequest r = new ConfigRequest();
        r.setConfigType("LLM");
        r.setName(name);
        r.setBaseUrl("https://api.deepseek.com/v1");
        r.setModelName("deepseek-chat");
        r.setApiKey(apiKey);
        return r;
    }

    private static SysConfig entity(Long id, String type, String name, String configJson, boolean active) {
        SysConfig e = new SysConfig();
        e.setId(id);
        e.setConfigType(type);
        e.setName(name);
        e.setConfigJson(configJson);
        e.setIsActive(active);
        e.setTestStatus("SUCCESS");
        e.setTestedAt(LocalDateTime.now());
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        return e;
    }

    @Test
    void save_whenTestFails_throwsAndDoesNotInsert() {
        ConfigService svc = service(failTester(ConfigType.LLM));
        BizException ex = assertThrows(BizException.class, () -> svc.save(llmRequest("a", "sk-x")));
        assertEquals(ResultCode.BIZ_ERROR.code(), ex.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void save_whenMissingRequiredField_throwsParamError() {
        ConfigService svc = service(okTester(ConfigType.LLM));
        BizException ex = assertThrows(BizException.class, () -> svc.save(llmRequest("a", null)));
        assertEquals(ResultCode.PARAM_ERROR.code(), ex.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void save_whenDuplicateName_throwsAndDoesNotInsert() throws Exception {
        ConfigService svc = service(okTester(ConfigType.LLM));
        when(mapper.getByTypeAndName(anyString(), anyString()))
                .thenReturn(entity(9L, "LLM", "a", crypto.encrypt("{}"), false));
        BizException ex = assertThrows(BizException.class, () -> svc.save(llmRequest("a", "sk-x")));
        assertEquals(ResultCode.BIZ_ERROR.code(), ex.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void save_whenTestPasses_storesCipherTextAndMasksEcho() throws Exception {
        ConfigService svc = service(okTester(ConfigType.LLM));
        when(mapper.getByTypeAndName(anyString(), anyString())).thenReturn(null);
        final SysConfig[] holder = new SysConfig[1];
        when(mapper.getById(any())).thenAnswer(i -> holder[0]);
        when(mapper.insert(any())).thenAnswer(i -> {
            SysConfig e = i.getArgument(0);
            e.setId(1L);
            holder[0] = e;
            return 1;
        });

        SysConfigView view = svc.save(llmRequest("llm-a", "sk-real-123456"));

        assertNotNull(holder[0]);
        assertEquals("SUCCESS", holder[0].getTestStatus());
        assertFalse(holder[0].getIsActive());
        // 落库为密文，且能解密回明文
        assertNotEquals("sk-real-123456", holder[0].getConfigJson());
        Map<?, ?> decrypted = json.readValue(crypto.decrypt(holder[0].getConfigJson()), Map.class);
        assertEquals("sk-real-123456", decrypted.get("apiKey"));
        // 回显掩码
        assertEquals("sk-****", view.getConfig().get("apiKey"));
    }

    @Test
    void get_masksSecrets() throws Exception {
        String plain = json.writeValueAsString(Map.of("apiKey", "sk-real-123456", "modelName", "deepseek-chat"));
        when(mapper.getById(1L)).thenReturn(entity(1L, "LLM", "a", crypto.encrypt(plain), true));

        SysConfigView view = service(okTester(ConfigType.LLM)).get(1L);

        assertEquals("sk-****", view.getConfig().get("apiKey"));
        assertEquals("deepseek-chat", view.getConfig().get("modelName"));
        assertNotEquals("sk-real-123456", view.getConfig().get("apiKey"));
    }

    @Test
    void get_notFound_throws404() {
        when(mapper.getById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service().get(404L));
        assertEquals(ResultCode.NOT_FOUND.code(), ex.getCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void activate_deactivatesSameTypeThenActivates() {
        when(mapper.getById(5L)).thenReturn(entity(5L, "MT5", "acc", crypto.encrypt("{}"), false));
        service(okTester(ConfigType.MT5)).activate(5L);
        verify(mapper).deactivateByType("MT5");
        verify(mapper).activateById(5L);
    }

    @Test
    void update_rejectsRename() throws Exception {
        when(mapper.getById(3L)).thenReturn(entity(3L, "LLM", "old", crypto.encrypt("{}"), false));
        BizException ex = assertThrows(BizException.class,
                () -> service(okTester(ConfigType.LLM)).update(3L, llmRequest("new", "sk-x")));
        assertEquals(ResultCode.PARAM_ERROR.code(), ex.getCode());
        verify(mapper, never()).update(any());
    }

    @Test
    void test_withUnsupportedType_throwsParamError() {
        ConfigRequest r = llmRequest("a", "sk-x");
        r.setConfigType("FOO");
        BizException ex = assertThrows(BizException.class, () -> service(okTester(ConfigType.LLM)).test(r));
        assertEquals(ResultCode.PARAM_ERROR.code(), ex.getCode());
    }
}