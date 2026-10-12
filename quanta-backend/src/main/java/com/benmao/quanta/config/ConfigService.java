package com.benmao.quanta.config;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.crypto.CryptoService;
import com.benmao.quanta.config.dto.ConfigRequest;
import com.benmao.quanta.config.dto.ConfigTestResult;
import com.benmao.quanta.config.dto.SysConfigView;
import com.benmao.quanta.config.tester.ConnectionTester;
import com.benmao.quanta.entity.SysConfig;
import com.benmao.quanta.mapper.SysConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配置层核心：MT5 / LLM / MAIL 三类配置的增删改查 + 连接测试。
 *
 * <p>铁律：<b>测试连接成功才可保存</b>（新增与更新都先跑测试，失败即抛业务异常且不落库）；
 * 存库的 {@code config_json} 为 AES 密文；对外回显一律掩码。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** 需要掩码回显的敏感字段名。 */
    private static final Set<String> SECRET_KEYS = Set.of("mt5Password", "apiKey", "authCode");

    private static final String TEST_OK = "SUCCESS";

    private final SysConfigMapper mapper;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final Map<ConfigType, ConnectionTester> testers;

    public ConfigService(SysConfigMapper mapper,
                         CryptoService crypto,
                         ObjectMapper objectMapper,
                         List<ConnectionTester> testers) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.testers = testers.stream().collect(Collectors.toMap(ConnectionTester::type, Function.identity()));
    }

    // ==================== 对外 API ====================

    /** 只测不存：构造载荷并分派到对应测试器。 */
    public ConfigTestResult test(ConfigRequest request) {
        ConfigType type = ConfigType.parse(request.getConfigType());
        Map<String, Object> payload = buildPayload(type, request);
        return testerFor(type).test(payload);
    }

    /** 新增：测试通过才落库（密文入库，标记 test_status=SUCCESS）。 */
    public SysConfigView save(ConfigRequest request) {
        ConfigType type = ConfigType.parse(request.getConfigType());
        String name = requireName(request.getName());
        Map<String, Object> payload = buildPayload(type, request);

        ConfigTestResult result = testerFor(type).test(payload);
        if (!result.isSuccess()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "配置未通过连接测试，已拒绝保存：" + result.getMessage(), 400);
        }
        if (mapper.getByTypeAndName(type.name(), name) != null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(), "同类型下已存在同名配置：" + name, 400);
        }

        SysConfig entity = new SysConfig();
        entity.setConfigType(type.name());
        entity.setName(name);
        entity.setConfigJson(crypto.encrypt(toJson(payload)));
        entity.setIsActive(Boolean.TRUE.equals(request.getActive()));
        entity.setTestStatus(TEST_OK);
        entity.setTestedAt(LocalDateTime.now());
        mapper.insert(entity);
        log.info("配置已保存: type={}, name={}, id={}", type, name, entity.getId());
        return toView(mapper.getById(entity.getId()));
    }

    /** 列表：type 为空则返回全部；密文字段掩码。 */
    public List<SysConfigView> list(String type) {
        List<SysConfig> entities = (type == null || type.isBlank())
                ? mapper.listAll()
                : mapper.listByType(ConfigType.parse(type).name());
        return entities.stream().map(this::toView).toList();
    }

    /** 详情（掩码）。 */
    public SysConfigView get(Long id) {
        return toView(requireExisting(id));
    }

    /** 更新：重新测试通过才写；不支持改类型与改名（保证唯一键语义清晰）。 */
    public SysConfigView update(Long id, ConfigRequest request) {
        SysConfig existing = requireExisting(id);
        ConfigType type = ConfigType.parse(request.getConfigType());
        if (!type.name().equals(existing.getConfigType())) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "不允许修改配置类型（原 " + existing.getConfigType() + "）");
        }
        String name = requireName(request.getName());
        if (!name.equals(existing.getName())) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "更新不支持改名；如需改名请删除后重建：" + existing.getName());
        }
        Map<String, Object> payload = buildPayload(type, request);

        ConfigTestResult result = testerFor(type).test(payload);
        if (!result.isSuccess()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "配置未通过连接测试，已拒绝更新：" + result.getMessage(), 400);
        }

        existing.setConfigJson(crypto.encrypt(toJson(payload)));
        existing.setIsActive(Boolean.TRUE.equals(request.getActive()));
        existing.setTestStatus(TEST_OK);
        existing.setTestedAt(LocalDateTime.now());
        mapper.update(existing);
        log.info("配置已更新: type={}, name={}, id={}", type, name, id);
        return toView(mapper.getById(id));
    }

    /** 删除。 */
    public void delete(Long id) {
        requireExisting(id);
        mapper.deleteById(id);
        log.info("配置已删除: id={}", id);
    }

    /** 激活：同类型先全部置非激活，再激活本条（同类型互斥）。 */
    public SysConfigView activate(Long id) {
        SysConfig existing = requireExisting(id);
        mapper.deactivateByType(existing.getConfigType());
        mapper.activateById(id);
        log.info("配置已激活: id={}, type={}", id, existing.getConfigType());
        return toView(mapper.getById(id));
    }

    // ==================== 内部实现 ====================

    private ConnectionTester testerFor(ConfigType type) {
        ConnectionTester tester = testers.get(type);
        if (tester == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "缺少 " + type + " 的连接测试器", 500);
        }
        return tester;
    }

    private SysConfig requireExisting(Long id) {
        SysConfig entity = mapper.getById(id);
        if (entity == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "配置不存在: id=" + id, 404);
        }
        return entity;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "name 不能为空");
        }
        return name.trim();
    }

    /** 按类型抽取字段成载荷（不含 name；name 是表列）。缺必填字段抛参数错误。 */
    private Map<String, Object> buildPayload(ConfigType type, ConfigRequest r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (type) {
            case MT5 -> {
                payload.put("mt5Login", require(r.getMt5Login(), "mt5Login"));
                payload.put("mt5Password", require(r.getMt5Password(), "mt5Password"));
                payload.put("mt5Server", require(r.getMt5Server(), "mt5Server"));
            }
            case LLM -> {
                payload.put("interfaceType", blankToDefault(r.getInterfaceType(), "OPENAI_COMPAT"));
                payload.put("baseUrl", require(r.getBaseUrl(), "baseUrl"));
                payload.put("modelName", require(r.getModelName(), "modelName"));
                payload.put("apiKey", require(r.getApiKey(), "apiKey"));
                Integer budget = r.getTokenBudgetDaily();
                if (budget != null && budget < 0) {
                    throw new BizException(ResultCode.PARAM_ERROR.code(), "tokenBudgetDaily 不能为负");
                }
                payload.put("tokenBudgetDaily", budget);
            }
            case MAIL -> {
                payload.put("provider", trimToNull(r.getProvider()));
                payload.put("account", require(r.getAccount(), "account"));
                payload.put("imapHost", trimToNull(r.getImapHost()));
                payload.put("imapPort", r.getImapPort());
                payload.put("smtpHost", require(r.getSmtpHost(), "smtpHost"));
                payload.put("smtpPort", r.getSmtpPort());
                payload.put("ssl", r.getSsl() == null || r.getSsl());
                payload.put("authCode", require(r.getAuthCode(), "authCode"));
                payload.put("folder", trimToNull(r.getFolder()));
                if (r.getRecipients() == null || r.getRecipients().isEmpty()) {
                    throw new BizException(ResultCode.PARAM_ERROR.code(), "recipients 至少需要一个收件人");
                }
                payload.put("recipients", r.getRecipients());
            }
        }
        return payload;
    }

    private SysConfigView toView(SysConfig entity) {
        SysConfigView view = new SysConfigView();
        view.setId(entity.getId());
        view.setConfigType(entity.getConfigType());
        view.setName(entity.getName());
        view.setIsActive(entity.getIsActive());
        view.setTestStatus(entity.getTestStatus());
        view.setTestedAt(entity.getTestedAt());
        view.setCreateTime(entity.getCreateTime());
        view.setUpdateTime(entity.getUpdateTime());
        try {
            Map<String, Object> config = readJson(crypto.decrypt(entity.getConfigJson()));
            for (String key : SECRET_KEYS) {
                Object value = config.get(key);
                if (value != null) {
                    config.put(key, ConfigMasker.mask(String.valueOf(value)));
                }
            }
            view.setConfig(config);
        } catch (Exception e) {
            log.warn("配置解密失败（密钥可能已更换）: id={}, msg={}", entity.getId(), e.getMessage());
            view.setConfig(Map.of("_error", "解密失败（加密密钥可能已更换）"));
        }
        return view;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "配置序列化失败: " + e.getMessage(), 500);
        }
    }

    private Map<String, Object> readJson(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), field + " 不能为空");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}