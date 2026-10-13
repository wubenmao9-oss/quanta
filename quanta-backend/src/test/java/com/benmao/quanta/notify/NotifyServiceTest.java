package com.benmao.quanta.notify;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.entity.NotifyLog;
import com.benmao.quanta.mapper.NotifyLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifyService 单测（M12）：无可用 MAIL 配置时<b>不抛异常</b>，落 {@code notify_log.status=FAILED}。
 *
 * <p>成功发信依赖真实 SMTP，不在单测覆盖（由真库/联调验证）；此处只验证 best-effort 的失败路径与留痕。
 */
class NotifyServiceTest {

    private ConfigService configService;
    private NotifyLogMapper notifyLogMapper;
    private NotifyService service;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        notifyLogMapper = mock(NotifyLogMapper.class);
        service = new NotifyService(configService, notifyLogMapper);
    }

    @Test
    @DisplayName("无激活 MAIL 配置 → 返回 false 且落 FAILED（不抛异常）")
    void noActiveMailConfigRecordsFailed() {
        when(configService.activeDecrypted(ConfigType.MAIL)).thenReturn(null);

        boolean ok = service.email("TOKEN_DEGRADE", "主题", "正文");

        assertFalse(ok);
        NotifyLog n = capture();
        assertEquals("TOKEN_DEGRADE", n.getNotifyType());
        assertEquals(NotifyService.CHANNEL_EMAIL, n.getChannel());
        assertEquals("主题", n.getSubject());
        assertEquals("正文", n.getContent());
        assertEquals("FAILED", n.getStatus());
        assertEquals("无激活的 MAIL 配置，无法发信", n.getError());
    }

    @Test
    @DisplayName("读取 MAIL 配置抛异常（如密钥不符解密失败）→ 返回 false 且落 FAILED")
    void configReadErrorRecordsFailed() {
        when(configService.activeDecrypted(ConfigType.MAIL))
                .thenThrow(new BizException(ResultCode.SYSTEM_ERROR.code(), "解密失败", 500));

        boolean ok = service.email("TOKEN_DEGRADE", "主题", "正文");

        assertFalse(ok);
        NotifyLog n = capture();
        assertEquals("FAILED", n.getStatus());
        assertEquals("读取激活 MAIL 配置失败：解密失败", n.getError());
    }

    @Test
    @DisplayName("MAIL 配置缺少收件人 → 返回 false 且落 FAILED（不发信）")
    void missingRecipientsRecordsFailed() {
        when(configService.activeDecrypted(ConfigType.MAIL)).thenReturn(Map.of(
                "smtpHost", "smtp.qq.com",
                "account", "a@qq.com",
                "authCode", "code",
                "recipients", List.of()));

        boolean ok = service.email("TOKEN_DEGRADE", "主题", "正文");

        assertFalse(ok);
        NotifyLog n = capture();
        assertEquals("FAILED", n.getStatus());
        assertEquals("MAIL 配置缺少 smtpHost/account/authCode/recipients", n.getError());
    }

    @Test
    @DisplayName("落库失败不影响返回值语义（best-effort）")
    void recordFailureStillReturnsFalse() {
        when(configService.activeDecrypted(ConfigType.MAIL)).thenReturn(null);
        when(notifyLogMapper.insert(any())).thenThrow(new RuntimeException("db down"));

        boolean ok = service.email("TOKEN_DEGRADE", "主题", "正文");

        assertFalse(ok);
    }

    private NotifyLog capture() {
        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(notifyLogMapper).insert(captor.capture());
        NotifyLog n = captor.getValue();
        assertNull(n.getId());
        return n;
    }
}
