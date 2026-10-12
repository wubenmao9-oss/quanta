package com.benmao.quanta.config.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 配置写入/测试请求（扁平结构，避免为三类配置各建一套 DTO）。
 *
 * <p>按 {@code configType} 取用对应字段：
 * <ul>
 *   <li>MT5：{@code mt5Login / mt5Password / mt5Server}</li>
 *   <li>LLM：{@code interfaceType / baseUrl / modelName / apiKey / tokenBudgetDaily(可空=无上限)}</li>
 *   <li>MAIL：{@code provider / account / imapHost / imapPort / smtpHost / smtpPort / ssl / authCode / folder / recipients}</li>
 * </ul>
 * Service 会按类型做必填校验（校验失败抛参数错误）。
 */
public class ConfigRequest {

    @NotBlank(message = "configType 不能为空")
    private String configType;

    @NotBlank(message = "name 不能为空")
    private String name;

    /** 是否激活（可选，默认 false）。 */
    private Boolean active;

    // ---- MT5 ----
    private String mt5Login;
    private String mt5Password;
    private String mt5Server;

    // ---- LLM ----
    /** 接口类型，一期仅支持 OPENAI_COMPAT。 */
    private String interfaceType;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    /** 日 Token 预算；null 或空 = 无上限。 */
    private Integer tokenBudgetDaily;

    // ---- MAIL ----
    private String provider;
    private String account;
    private String imapHost;
    private Integer imapPort;
    private String smtpHost;
    private Integer smtpPort;
    private Boolean ssl;
    /** 邮箱授权码（非登录密码）。 */
    private String authCode;
    private String folder;
    /** 测试发信收件人（至少 1 个）。 */
    private List<String> recipients;

    public String getConfigType() {
        return configType;
    }

    public void setConfigType(String configType) {
        this.configType = configType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getMt5Login() {
        return mt5Login;
    }

    public void setMt5Login(String mt5Login) {
        this.mt5Login = mt5Login;
    }

    public String getMt5Password() {
        return mt5Password;
    }

    public void setMt5Password(String mt5Password) {
        this.mt5Password = mt5Password;
    }

    public String getMt5Server() {
        return mt5Server;
    }

    public void setMt5Server(String mt5Server) {
        this.mt5Server = mt5Server;
    }

    public String getInterfaceType() {
        return interfaceType;
    }

    public void setInterfaceType(String interfaceType) {
        this.interfaceType = interfaceType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getTokenBudgetDaily() {
        return tokenBudgetDaily;
    }

    public void setTokenBudgetDaily(Integer tokenBudgetDaily) {
        this.tokenBudgetDaily = tokenBudgetDaily;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = imapHost;
    }

    public Integer getImapPort() {
        return imapPort;
    }

    public void setImapPort(Integer imapPort) {
        this.imapPort = imapPort;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public Integer getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(Integer smtpPort) {
        this.smtpPort = smtpPort;
    }

    public Boolean getSsl() {
        return ssl;
    }

    public void setSsl(Boolean ssl) {
        this.ssl = ssl;
    }

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public List<String> getRecipients() {
        return recipients;
    }

    public void setRecipients(List<String> recipients) {
        this.recipients = recipients;
    }
}