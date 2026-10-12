package com.benmao.quanta.common;

import com.benmao.quanta.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bizException_shouldUseItsOwnCodeAndStatus() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BIZ_ERROR.code()))
                .andExpect(jsonPath("$.message").value("业务失败"));
    }

    @Test
    void illegalArgument_shouldMapToParamError() throws Exception {
        mockMvc.perform(get("/test/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.code()));
    }

    @Test
    void unexpectedException_shouldMapToSystemError() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ResultCode.SYSTEM_ERROR.code()));
    }

    /** 仅用于触发异常路径的测试控制器。 */
    @RestController
    static class TestController {

        @GetMapping("/test/biz")
        String biz() {
            throw new BizException("业务失败");
        }

        @GetMapping("/test/illegal")
        String illegal() {
            throw new IllegalArgumentException("参数非法");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new RuntimeException("未知错误");
        }
    }
}