package com.benmao.quanta.ops;

import com.benmao.quanta.entity.OperationAudit;
import com.benmao.quanta.mapper.OperationAuditMapper;
import org.springframework.stereotype.Service;

/**
 * 人工介入审计写入（{@code operation_audit}，设计 §8.3）。
 *
 * <p>统一入口，字段按列长裁剪，避免超长写入失败；任何人工/运维动作都应经此留痕。
 */
@Service
public class OperationAuditService {

    private static final int LEN_OPERATOR = 32;
    private static final int LEN_OP_TYPE = 32;
    private static final int LEN_TARGET = 64;
    private static final int LEN_VAL = 255;
    private static final int LEN_TRACE = 32;

    private final OperationAuditMapper mapper;

    public OperationAuditService(OperationAuditMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 写一条审计。
     *
     * @param operator  操作者（人工/系统标识）
     * @param opType    操作类型（如 MANUAL_OPEN / MODE_TAKEOVER / CLAIM / RECONCILE_DIFF）
     * @param target    作用对象（品种/ticket/字段名等）
     * @param beforeVal 原值
     * @param afterVal  新值 / 结果
     * @param traceId   追踪号
     */
    public OperationAudit write(String operator, String opType, String target,
                                String beforeVal, String afterVal, String traceId) {
        OperationAudit a = new OperationAudit();
        a.setOperator(clip(operator, LEN_OPERATOR));
        a.setOpType(clip(opType, LEN_OP_TYPE));
        a.setTarget(clip(target, LEN_TARGET));
        a.setBeforeVal(clip(beforeVal, LEN_VAL));
        a.setAfterVal(clip(afterVal, LEN_VAL));
        a.setTraceId(clip(traceId, LEN_TRACE));
        mapper.insert(a);
        return a;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
