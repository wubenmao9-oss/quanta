package com.benmao.quanta.controller;

import com.benmao.quanta.common.GlobalExceptionHandler;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.dto.ConfigTestResult;
import com.benmao.quanta.config.dto.SysConfigView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SysConfigController 单元测试：统一返回体、参数校验、失败拒存回执。 */
class SysConfigControllerTest {

    private ConfigService service;
    private MockMvc mvc;

    private static final String LLM_BODY =
            "{\"configType\":\"LLM\",\"name\":\"a\",\"baseUrl\":\"https://api.deepseek.com/v1\","
                    + "\"modelName\":\"deepseek-chat\",\"apiKey\":\"sk-x\"}";

    @BeforeEach
    void setUp() {
        service = mock(ConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SysConfigController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testEndpoint_returnsTestResult() throws Exception {
        when(service.test(any())).thenReturn(new ConfigTestResult(true, "ok", 12, Map.of("baseUrl", "x")));
        mvc.perform(post("/api/configs/test").contentType(MediaType.APPLICATION_JSON).content(LLM_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    void save_whenTestFails_returns400WithBizCode() throws Exception {
        when(service.save(any()))
                .thenThrow(new BizException(50000, "配置未通过连接测试，已拒绝保存：boom", 400));
        mvc.perform(post("/api/configs").contentType(MediaType.APPLICATION_JSON).content(LLM_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(50000));
    }

    @Test
    void save_whenValidationFails_returnsParamErrorAndSkipsService() throws Exception {
        mvc.perform(post("/api/configs").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        verify(service, never()).save(any());
    }

    @Test
    void list_returnsMaskedViews() throws Exception {
        SysConfigView view = new SysConfigView();
        view.setId(1L);
        view.setConfigType("LLM");
        view.setName("a");
        view.setIsActive(true);
        view.setConfig(Map.of("apiKey", "sk-****"));
        when(service.list("LLM")).thenReturn(List.of(view));

        mvc.perform(get("/api/configs").param("type", "LLM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].config.apiKey").value("sk-****"))
                .andExpect(jsonPath("$.data[0].isActive").value(true));
    }
}