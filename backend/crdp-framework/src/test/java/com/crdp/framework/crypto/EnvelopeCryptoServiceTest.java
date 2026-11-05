package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** E03-T02：信封加密 + 每租户数据密钥。密文格式、算法分派与密钥版本语义沿用 E03-T01。 */
class EnvelopeCryptoServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EnvelopeCryptoService crypto =
            new EnvelopeCryptoService(
                    new TenantDataKeyService(
                            new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                            jdbcTemplate));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** 进入一个尚未生成过数据密钥的租户。 */
    private void freshTenant(long tenantId, String code) {
        TenantContext.useTenant(tenantId, code);
        when(jdbcTemplate.queryForObject(
                        contains("MAX(key_version)"), eq(Integer.class), eq("field")))
                .thenReturn(null);
    }

    @Test
    void roundTripsAndBuildsAStableBlindIndex() {
        freshTenant(1L, "demoa");
        when(jdbcTemplate.queryForObject(
                        contains("MAX(key_version)"), eq(Integer.class), eq("blind")))
                .thenReturn(null, 1);

        String ciphertext = crypto.encrypt("field", "虚构姓名");

        assertEquals("虚构姓名", crypto.decrypt(ciphertext));
        assertEquals(
                crypto.blindIndex("blind", "MR-DEMO-001"),
                crypto.blindIndex("blind", "MR-DEMO-001"));
        assertNotEquals(
                crypto.blindIndex("blind", "MR-DEMO-001"),
                crypto.blindIndex("file", "MR-DEMO-001"));
    }

    @Test
    void sameValueInDifferentWritingsProducesTheSameBlindIndex() {
        freshTenant(1L, "demoa");
        when(jdbcTemplate.queryForObject(
                        contains("MAX(key_version)"), eq(Integer.class), eq("blind")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("blind"), eq(1)))
                .thenAnswer(invocation -> List.of(keyMaterial()));

        String first = crypto.blindIndex("blind", "138 0013-8000");
        String second = crypto.blindIndex("blind", "+8613800138000");

        assertEquals(first, second, "不同写法的同一电话必须得到同一盲索引（精确查找命中）");
    }

    private static String keyMaterial() {
        GeneratedDataKey generated =
                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key").generateDataKey();
        return generated.wrappedKey();
    }

    @Test
    void eachTenantEncryptsWithItsOwnDataKey() {
        freshTenant(1L, "demoa");
        String ciphertextOfA = crypto.encrypt("field", "虚构姓名");
        freshTenant(2L, "demob");
        String ciphertextOfB = crypto.encrypt("field", "虚构姓名");

        assertNotEquals(ciphertextOfA, ciphertextOfB, "各租户用自己的数据密钥，密文必然不同");

        ArgumentCaptor<String> wrappedKeys = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2))
                .update(
                        contains("INSERT INTO crypto_data_key"),
                        eq("field"),
                        eq(1),
                        wrappedKeys.capture());
        assertNotEquals(
                wrappedKeys.getAllValues().get(0),
                wrappedKeys.getAllValues().get(1),
                "落库的包装密钥必须是两把不同的数据密钥");

        assertEquals("虚构姓名", crypto.decrypt(ciphertextOfB));
        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(ciphertextOfA));
        assertEquals(40002, ex.code(), "乙院上下文解不开甲院的密文（密钥不同）");
    }

    @Test
    void decryptRejectsAlgorithmsThatAreWellFormedButUnsupported() {
        freshTenant(1L, "demoa");
        Ciphertext original = Ciphertext.parse(crypto.encrypt("field", "虚构姓名"));
        String other =
                new Ciphertext(
                                1,
                                "SM4-GCM",
                                original.keyVersion(),
                                "field",
                                original.iv(),
                                original.data())
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(other));
        assertEquals(40002, ex.code());
    }

    @Test
    void decryptRejectsUnknownKeyVersions() {
        freshTenant(1L, "demoa");
        Ciphertext original = Ciphertext.parse(crypto.encrypt("field", "虚构姓名"));
        when(jdbcTemplate.queryForObject(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(2)))
                .thenReturn(null);
        String rotated =
                new Ciphertext(1, "AES-256-GCM", 2, "field", original.iv(), original.data())
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(rotated));
        assertEquals(40002, ex.code(), "库中不存在的密钥版本应明确拒绝");
    }

    @Test
    void decryptDetectsTamperedCiphertext() {
        freshTenant(1L, "demoa");
        Ciphertext original = Ciphertext.parse(crypto.encrypt("field", "虚构姓名"));
        byte[] tampered = original.data().clone();
        tampered[0] ^= 0x01;
        String value =
                new Ciphertext(1, "AES-256-GCM", 1, "field", original.iv(), tampered).encode();

        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(value));
        assertEquals(40002, ex.code());
    }

    /** E03-T06：用途校验——用途决定用哪把密钥，不合法的用途必须拒绝而不是落到默认密钥上。 */
    @Test
    void encryptRejectsPurposesThatDoNotMatchTheContract() {
        freshTenant(1L, "demoa");

        assertRejectsPurpose(null, null);
        assertRejectsPurpose("", null);
        assertRejectsPurpose("Field-Name", "用途不允许大写");
        assertRejectsPurpose("field name", "用途不允许空格");
        assertRejectsPurpose("a".repeat(33), "用途最长 32 个字符");
    }

    private void assertRejectsPurpose(String purpose, String because) {
        ApiException ex =
                assertThrows(ApiException.class, () -> crypto.encrypt(purpose, "虚构姓名"), because);
        assertEquals(40002, ex.code());
    }

    /** encrypt/blindIndex 走同一个用途校验。 */
    @Test
    void blindIndexRejectsInvalidPurposesToo() {
        freshTenant(1L, "demoa");

        ApiException ex =
                assertThrows(ApiException.class, () -> crypto.blindIndex("BAD", "MR-DEMO-001"));
        assertEquals(40002, ex.code());
    }

    @Test
    void blindIndexOfNullValueIsNullInsteadOfAnIndex() {
        freshTenant(1L, "demoa");

        assertNull(crypto.blindIndex("blind", null), "空值不产生索引，配合可空列");
    }

    /** 随机 IV：同一明文两次加密得到不同密文，都能解回原文。 */
    @Test
    void samePlaintextEncryptsDifferentlyEveryTime() {
        freshTenant(1L, "demoa");
        // 首次查询补建版本 1，之后版本稳定为 1（模拟真实库中行已存在）
        when(jdbcTemplate.queryForObject(
                        contains("MAX(key_version)"), eq(Integer.class), eq("field")))
                .thenReturn(null, 1);

        String first = crypto.encrypt("field", "13800138000");
        String second = crypto.encrypt("field", "13800138000");

        assertNotEquals(first, second, "GCM 每次加密使用随机 IV，同一明文不得得到同一密文");
        assertEquals("13800138000", crypto.decrypt(first));
        assertEquals("13800138000", crypto.decrypt(second));
    }

    /**
     * E09-T06 Webhook 签名：外部共享密钥的 HMAC-SHA256（RFC 4231 测试向量 2，key="Jefe"）。 与加解密字段不同——密钥由调用方提供
     * （Webhook secret），不取租户数据密钥，因此不需要租户上下文。
     */
    @Test
    void computesHmacSha256WithExternalKey() {
        byte[] jefe = "Jefe".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                crypto.hmacSha256Hex(jefe, "what do ya want for nothing?"));
        assertNotEquals(
                crypto.hmacSha256Hex(jefe, "what do ya want for nothing?"),
                crypto.hmacSha256Hex(jefe, "what do ya want for nothing!"),
                "同密钥不同数据必须得到不同签名");
        assertNotEquals(
                crypto.hmacSha256Hex(jefe, "what do ya want for nothing?"),
                crypto.hmacSha256Hex(
                        "Ana".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        "what do ya want for nothing?"),
                "不同密钥同数据必须得到不同签名");
    }
}
