package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.Test;

class CiphertextTest {

    @Test
    void parsesEveryFieldAndReEncodesToTheSameValue() {
        String value =
                new Ciphertext(1, "AES-256-GCM", 1, "field", new byte[12], "tand".getBytes())
                        .encode();

        Ciphertext parsed = Ciphertext.parse(value);

        assertEquals(1, parsed.version());
        assertEquals("AES-256-GCM", parsed.algorithm());
        assertEquals(1, parsed.keyVersion());
        assertEquals("field", parsed.purpose());
        assertEquals(12, parsed.iv().length, "AES-GCM 的 IV 应为 12 字节");
        assertTrue(parsed.data().length > 0, "密文与认证标签应有内容");
        assertEquals(value, parsed.encode(), "解析后重新编码应得到原样密文");
    }

    @Test
    void parsesWellFormedButUnsupportedAlgorithms() {
        Ciphertext parsed = Ciphertext.parse("v1.SM4-GCM.1.field.AAAAAAAAAAAAAAAA.dGFuZA==");

        assertEquals("SM4-GCM", parsed.algorithm(), "解析只管语法，算法是否支持由解密方分派");
    }

    @Test
    void rejectsMalformedValues() {
        assertRejects(null);
        assertRejects("");
        assertRejects("v1");
        assertRejects("v1.AES-256-GCM.1.field.AAAAAAAAAAAAAAAA");
        assertRejects("v2.AES-256-GCM.1.field.AAAAAAAAAAAAAAAA.dGFuZA==", "未知的密文格式版本");
        assertRejects("v1.AES-256-GCM.0.field.AAAAAAAAAAAAAAAA.dGFuZA==", "密钥版本必须为正整数");
        assertRejects("v1.AES-256-GCM.x.field.AAAAAAAAAAAAAAAA.dGFuZA==", "密钥版本必须是数字");
        assertRejects("v1.AES-256-GCM.1.BAD-PURPOSE.AAAAAAAAAAAAAAAA.dGFuZA==", "用途不合法");
        assertRejects("v1.AES-256-GCM.1.field.!!not-base64!!.dGFuZA==", "IV 不是 Base64");
        assertRejects("v1.AES-256-GCM.1.field.AAAAAAAAAAAAAAAA.!!not-base64!!", "密文不是 Base64");
    }

    private static void assertRejects(String value) {
        ApiException ex = assertThrows(ApiException.class, () -> Ciphertext.parse(value));
        assertEquals(40002, ex.code());
    }

    private static void assertRejects(String value, String because) {
        ApiException ex = assertThrows(ApiException.class, () -> Ciphertext.parse(value), because);
        assertEquals(40002, ex.code());
    }
}
