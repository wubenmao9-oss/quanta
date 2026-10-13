package com.benmao.quanta.notify;

import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.entity.NotifyLog;
import com.benmao.quanta.mapper.NotifyLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 通知服务：用库内<b>激活的 MAIL 配置</b>发信，并写 {@code notify_log} 留痕。
 *
 * <p><b>best-effort</b>：本类不抛异常——发信失败只记日志与 {@code notify_log.status=FAILED}，
 * 绝不因「通知发不出去」而中断调用方（如 Token 降级流程）。
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    /** notify_log.channel 取值。 */
    public static final String CHANNEL_EMAIL = "EMAIL";

    private static final int DEFAULT_SMTP_PORT = 465;
    private static final int TIMEOUT_MS = 8000;

    private final ConfigService configService;
    private final NotifyLogMapper notifyLogMapper;

    public NotifyService(ConfigService configService, NotifyLogMapper notifyLogMapper) {
        this.configService = configService;
        this.notifyLogMapper = notifyLogMapper;
    }

    /**
     * 发一封告警邮件并落 {@code notify_log}。
     *
     * @return 是否发送成功
     */
    public boolean email(String notifyType, String subject, String content) {
        Map<String, Object> cfg;
        try {
            cfg = configService.activeDecrypted(ConfigType.MAIL);
        } catch (RuntimeException e) {
            return record(notifyType, subject, content, false, "读取激活 MAIL 配置失败：" + e.getMessage());
        }
        if (cfg == null) {
            return record(notifyType, subject, content, false, "无激活的 MAIL 配置，无法发信");
        }

        String smtpHost = str(cfg.get("smtpHost"));
        Integer smtpPort = intOf(cfg.get("smtpPort"));
        boolean ssl = boolOf(cfg.get("ssl"), true);
        String account = str(cfg.get("account"));
        String authCode = str(cfg.get("authCode"));
        List<String> recipients = listOf(cfg.get("recipients"));
        if (isBlank(smtpHost) || isBlank(account) || isBlank(authCode)
                || recipients.isEmpty() || isBlank(recipients.get(0))) {
            return record(notifyType, subject, content, false, "MAIL 配置缺少 smtpHost/account/authCode/recipients");
        }

        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtpHost);
            sender.setPort(smtpPort == null ? DEFAULT_SMTP_PORT : smtpPort);
            sender.setUsername(account);
            sender.setPassword(authCode);
            sender.setDefaultEncoding("UTF-8");

            Properties p = sender.getJavaMailProperties();
            p.put("mail.transport.protocol", "smtp");
            p.put("mail.smtp.auth", "true");
            p.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
            p.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
            p.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
            if (ssl) {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                p.put("mail.smtp.socketFactory.fallback", "false");
            } else {
                p.put("mail.smtp.starttls.enable", "true");
            }

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(account);
            message.setTo(recipients.toArray(new String[0]));
            message.setSubject(subject);
            message.setText(content);
            sender.send(message);

            log.info("通知邮件已发送 type={} to={}", notifyType, recipients);
            return record(notifyType, subject, content, true, null);
        } catch (Exception e) {
            log.warn("通知邮件发送失败 type={}：{}", notifyType, e.getMessage());
            return record(notifyType, subject, content, false, e.getMessage());
        }
    }

    /** 落 notify_log 并返回成功标记（落库失败不影响返回值语义）。 */
    private boolean record(String notifyType, String subject, String content, boolean success, String error) {
        try {
            NotifyLog n = new NotifyLog();
            n.setNotifyType(notifyType);
            n.setChannel(CHANNEL_EMAIL);
            n.setSubject(subject);
            n.setContent(content);
            n.setStatus(success ? "SENT" : "FAILED");
            n.setError(error);
            notifyLogMapper.insert(n);
        } catch (RuntimeException e) {
            log.warn("notify_log 落库失败：{}", e.getMessage());
        }
        return success;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Integer intOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean boolOf(Object v, boolean dft) {
        if (v == null) {
            return dft;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(x -> x == null ? "" : String.valueOf(x).trim()).toList();
        }
        return List.of();
    }
}
