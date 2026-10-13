package com.benmao.quanta.ops;

import com.benmao.quanta.entity.OperationAudit;
import com.benmao.quanta.mapper.OperationAuditMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** operation_audit 写入服务单测（M13）：字段透传 + 超长按列长裁剪。 */
class OperationAuditServiceTest {

    private final OperationAuditMapper mapper = mock(OperationAuditMapper.class);
    private final OperationAuditService service = new OperationAuditService(mapper);

    @Test
    @DisplayName("写入审计：operator/op_type/target/值/trace 全部透传")
    void writesAllFields() {
        service.write("MANUAL", "MANUAL_OPEN", "GOLD", null, "FILLED", "t1");

        ArgumentCaptor<OperationAudit> c = ArgumentCaptor.forClass(OperationAudit.class);
        verify(mapper).insert(c.capture());
        assertEquals("MANUAL", c.getValue().getOperator());
        assertEquals("MANUAL_OPEN", c.getValue().getOpType());
        assertEquals("GOLD", c.getValue().getTarget());
        assertEquals("FILLED", c.getValue().getAfterVal());
        assertEquals("t1", c.getValue().getTraceId());
    }

    @Test
    @DisplayName("超长字段按列长裁剪（operator≤32 / op_type≤32 / target≤64 / 值≤255 / trace≤32）")
    void clipsOverlongFields() {
        String longText = "x".repeat(300);
        service.write(longText, longText, longText, longText, longText, longText);

        ArgumentCaptor<OperationAudit> c = ArgumentCaptor.forClass(OperationAudit.class);
        verify(mapper).insert(c.capture());
        OperationAudit a = c.getValue();
        assertEquals(32, a.getOperator().length());
        assertEquals(32, a.getOpType().length());
        assertEquals(64, a.getTarget().length());
        assertEquals(255, a.getBeforeVal().length());
        assertEquals(255, a.getAfterVal().length());
        assertEquals(32, a.getTraceId().length());
    }
}
