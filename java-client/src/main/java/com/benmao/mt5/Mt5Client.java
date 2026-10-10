package com.benmao.mt5;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * HTTP 传输层：负责与本地 Python 服务（mt5_service）通信。
 * 只依赖 JDK 自带 java.net.http，无需额外框架。
 */
public class Mt5Client {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public Mt5Client(Mt5Config config) {
        this(config.baseUrl(), Duration.ofSeconds(30));
    }

    public Mt5Client(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** GET 返回单个对象。 */
    public <T> T get(String path, Class<T> type) {
        String body = send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
        return read(body, type);
    }

    /** GET 返回对象列表。 */
    public <T> List<T> getList(String path, TypeReference<List<T>> type) {
        String body = send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
        return read(body, type);
    }

    /** POST 返回单个对象。 */
    public <T> T post(String path, Object payload, Class<T> type) {
        String body = send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload), StandardCharsets.UTF_8))
                .build());
        return read(body, type);
    }

    /** POST 返回对象列表。 */
    public <T> List<T> postList(String path, Object payload, TypeReference<List<T>> type) {
        String body = send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload), StandardCharsets.UTF_8))
                .build());
        return read(body, type);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new Mt5ApiException(response.statusCode(),
                        "HTTP " + response.statusCode() + " <- " + request.uri() + " : " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new Mt5ApiException(0, "请求本地 MT5 服务失败（是否已启动？）: " + request.uri() + " -> " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Mt5ApiException(0, "请求被中断: " + request.uri(), e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new Mt5ApiException(0, "解析响应失败: " + e.getMessage() + " | body=" + body, e);
        }
    }

    private <T> T read(String body, TypeReference<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new Mt5ApiException(0, "解析响应失败: " + e.getMessage() + " | body=" + body, e);
        }
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new Mt5ApiException(0, "序列化请求失败: " + e.getMessage(), e);
        }
    }

    /** 调用失败时抛出。 */
    public static class Mt5ApiException extends RuntimeException {
        private final int statusCode;

        public Mt5ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public Mt5ApiException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}