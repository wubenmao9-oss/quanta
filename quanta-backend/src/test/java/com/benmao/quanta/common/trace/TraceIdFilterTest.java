package com.benmao.quanta.common.trace;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldGenerateTraceId_whenHeaderAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
        // 请求结束后 MDC 必须清理
        assertNull(MDC.get(TraceIdFilter.TRACE_ID_KEY));
    }

    @Test
    void shouldReuseTraceId_whenHeaderPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "abcdefabcdefabcdefabcdefabcdef12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("abcdefabcdefabcdefabcdefabcdef12",
                response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
    }

    @Test
    void shouldExposeTraceIdInMdc_duringRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = new String[1];
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                seen[0] = MDC.get(TraceIdFilter.TRACE_ID_KEY);
            }
        });

        filter.doFilter(request, response, chain);

        assertNotNull(seen[0]);
        assertEquals(response.getHeader(TraceIdFilter.TRACE_ID_HEADER), seen[0]);
    }

    @Test
    void generateTraceId_shouldBe32Chars() {
        assertEquals(32, TraceIdFilter.generateTraceId().length());
    }
}