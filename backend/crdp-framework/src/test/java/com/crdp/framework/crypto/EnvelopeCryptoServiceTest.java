package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
