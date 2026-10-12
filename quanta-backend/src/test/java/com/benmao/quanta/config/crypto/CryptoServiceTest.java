package com.benmao.quanta.config.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CryptoService 单元测试：加解密往返、随机 IV、密钥校验。 */
class CryptoServiceTest {

    /** Base64("0123456789abcdef0123456789abcdef")，解码后 32 字节。 */
    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private CryptoService service() {
        return new CryptoService(KEY);
    }

    @Test
    void encryptThenDecrypt_roundTrip() {
        CryptoService crypto = service();
        String plain = "{\"apiKey\":\"sk-real-secret-1234\",\"modelName\":\"deepseek-chat\"}";
        String cipher = crypto.encrypt(plain);
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    void cipherTextDiffersFromPlain() {
        String plain = "password=example-secret";
        assertNotEquals(plain, service().encrypt(plain));
    }

    @Test
    void sameInputTwice_producesDifferentCipher() {
        CryptoService crypto = service();
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"));
    }

    @Test
    void decryptWithWrongKey_fails() {
        CryptoService other = new CryptoService(
                Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8)));
        String cipher = service().encrypt("secret");
        assertThrows(IllegalStateException.class, () -> other.decrypt(cipher));
    }

    @Test
    void blankKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService(""));
        assertThrows(IllegalStateException.class, () -> new CryptoService("   "));
    }

    @Test
    void wrongLengthKey_failsFast() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> new CryptoService(shortKey));
    }

    @Test
    void invalidBase64_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService("not@@base64"));
    }
}