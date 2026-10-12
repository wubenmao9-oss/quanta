package com.benmao.quanta.config.tester;

import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.config.dto.ConfigTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * MAIL 连接测试：用给定 SMTP 配置真实发一封测试邮件，无异常即成功。
 *
 * <p>设计只列了 IMAP（用于后续收信），但「测试发信」必须有 SMTP，
 * 因此 MAIL 载荷同时含 {@code smtpHost / smtpPort / ssl}。
 */
@Component
public class MailConnectionTester implements ConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(MailConnectionTester.class);

    private static final int DEFAULT_SMTP_PORT = 465;
    private static final int TIMEOUT_MS = 8000;

    @Override
    public ConfigType type() {
        return ConfigType.MAIL;
    }

    @Override
    public ConfigTestResult test(Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        Map<String, Object> detail = new LinkedHashMap<>();

        String smtpHost = Payloads.str(payload, "smtpHost");
        Integer smtpPort = Payloads.intOf(payload, "smtpPort");
        boolean ssl = Payloads.boolOf(payload, "ssl", true);
        String account = Payloads.str(payload, "account");
        String authCode = Payloads.str(payload, "authCode");
        List<String> recipients = Payloads.listOf(payload, "recipients");

        if (isBlank(smtpHost) || isBlank(account) || isBlank(authCode)) {
            return fail("smtpHost / account / authCode 均不能为空", start, detail);
        }
        if (recipients == null || recipients.isEmpty() || isBlank(recipients.get(0))) {
            return fail("recipients 至少需要一个有效收件人", start, detail);
        }

        int port = smtpPort == null ? DEFAULT_SMTP_PORT : smtpPort;
        String to = recipients.get(0).trim();
        detail.put("smtpHost", smtpHost);
        detail.put("smtpPort", port);
        detail.put("ssl", ssl);
        detail.put("to", to);

        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtpHost);
            sender.setPort(port);
            sender.setUsername(account);
            sender.setPassword(authCode);
            sender.setDefaultEncoding("UTF-8");

            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
            props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
            props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
            if (ssl) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(account);
            message.setTo(to);
            message.setSubject("[quanta] 配置测试邮件");
            message.setText("这是一封来自 quanta 配置层的测试邮件；收到即表示 MAIL 配置可用。");

            sender.send(message);
            return new ConfigTestResult(true, "测试邮件已发送至 " + to, System.currentTimeMillis() - start, detail);
        } catch (Exception e) {
            log.warn("MAIL 测试发信失败: {}", e.getMessage());
            return fail("测试发信失败: " + e.getMessage(), start, detail);
        }
    }

    private ConfigTestResult fail(String message, long start, Map<String, Object> detail) {
        return new ConfigTestResult(false, message, System.currentTimeMillis() - start, detail);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}