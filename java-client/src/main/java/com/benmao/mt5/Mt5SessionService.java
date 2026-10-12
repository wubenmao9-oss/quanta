package com.benmao.mt5;

import com.benmao.mt5.model.ConnectRequest;
import com.benmao.mt5.model.SessionInfo;

import java.util.Map;

/**
 * 会话与连通性：{@code GET /health} 探活 + {@code POST /connect} 用真实凭据登录。
 *
 * <p>分工：健康检查只证明「桥接服务在线 + 终端已连接」；
 * 真实登录用 sys_config 里存的账号密码（M3 起由 Java 侧下发凭据）。
 */
public class Mt5SessionService {

    private final Mt5Client client;

    public Mt5SessionService(Mt5Client client) {
        this.client = client;
    }

    /** 服务/终端连通性（不校验凭据）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        return client.get("/health", Map.class);
    }

    /** 用指定凭据登录（幂等：已是同一账号则桥接侧不重连）。 */
    public SessionInfo connect(Integer login, String password, String server) {
        return connect(login, password, server, null);
    }

    /** 用指定凭据登录，可显式指定 terminal64.exe 路径。 */
    public SessionInfo connect(Integer login, String password, String server, String path) {
        return client.post("/connect", new ConnectRequest(login, password, server, path), SessionInfo.class);
    }
}