package com.benmao.quanta.config.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置凭据加解密（AES-256-GCM，纯 JDK 实现，不引入额外加密依赖）。
 *
 * <p>密钥来源：{@code quanta.security.aes-key}（Base64 编码的 32 字节），
 * 优先由环境变量 {@code QUANTA_CONFIG_AES_KEY} 注入，本地开发可写在 application-local.yml。
 * <b>密钥为空或长度非法时启动即失败</b>——宁可起不来，也不允许明文存凭据。
 *
 * <p>密文格式：{@code Base64( IV(12B) ‖ ciphertext+tag(16B) )}，每次加密使用随机 IV，
 * 因此同一明文两次加密结果不同。
 */
@Component
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public CryptoService(@Value("${quanta.security.aes-key:}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException("缺少配置加密密钥 quanta.security.aes-key："
                    + "请设置环境变量 QUANTA_CONFIG_AES_KEY（Base64 编码的 32 字节），"
                    + "或在 application-local.yml 中配置 quanta.security.aes-key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("配置加密密钥不是合法的 Base64 字符串", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("配置加密密钥长度非法：需 " + KEY_BYTES + " 字节（Base64 解码后），当前 " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密明文，返回 Base64 密文。 */
    public String encrypt(String plainText) {
        if (plainText == null) {
            throw new IllegalArgumentException("待加密内容不能为 null");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密 Base64 密文，返回明文；密钥不匹配 / 内容被篡改会抛异常。 */
    public String decrypt(String cipherBase64) {
        if (cipherBase64 == null) {
            throw new IllegalArgumentException("待解密内容不能为 null");
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            if (all.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（密钥不匹配或内容被篡改）: " + e.getMessage(), e);
        }
    }
}