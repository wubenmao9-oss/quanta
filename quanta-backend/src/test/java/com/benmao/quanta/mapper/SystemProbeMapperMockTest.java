package com.benmao.quanta.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Mockito 在 mock-maker-subclass 配置下可用。 */
class SystemProbeMapperMockTest {

    @Test
    void mockPingShouldReturnStubbedValue() {
        SystemProbeMapper mapper = mock(SystemProbeMapper.class);
        when(mapper.ping()).thenReturn(1);
        assertEquals(1, mapper.ping());
    }
}