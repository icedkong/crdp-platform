package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.util.Arrays;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** E03-T02：每租户独立数据密钥，密文落库、明文只在内存缓存。 */
class TenantDataKeyServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LocalEnvelopeKeyProvider provider =
            new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createsTheFirstVersionLazilyAndStoresOnlyTheWrappedKey() {
        TenantContext.useTenant(1L, "demoa");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("field")))
                .thenReturn(null);

        TenantDataKeyService keys = new TenantDataKeyService(provider, jdbcTemplate);
        int version = keys.currentVersion("field");

        assertEquals(1, version);
        verify(jdbcTemplate)
                .update(
                        contains("INSERT INTO crypto_data_key"),
                        eq("field"),
                        eq(1),
                        contains("v1.AES-256-GCM."));
    }

    @Test
    void returnsTheExistingLatestVersionWithoutGenerating() {
        TenantContext.useTenant(1L, "demoa");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("field")))
                .thenReturn(3);

        TenantDataKeyService keys = new TenantDataKeyService(provider, jdbcTemplate);

        assertEquals(3, keys.currentVersion("field"));
        verify(jdbcTemplate, never())
                .update(contains("INSERT INTO crypto_data_key"), any(Object[].class));
    }

    @Test
    void keyForLoadsUnwrapsAndCachesInMemory() {
        TenantContext.useTenant(1L, "demoa");
        GeneratedDataKey generated = provider.generateDataKey();
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(1)))
                .thenReturn(List.of(generated.wrappedKey()));

        TenantDataKeyService keys = new TenantDataKeyService(provider, jdbcTemplate);
        SecretKey first = keys.keyFor("field", 1);
        SecretKey second = keys.keyFor("field", 1);

        assertNotNull(first);
        assertTrue(Arrays.equals(first.getEncoded(), second.getEncoded()), "同租户同版本应命中内存缓存，拿到同一把密钥");
        verify(jdbcTemplate, times(1))
                .queryForList(contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(1));
    }

    @Test
    void missingVersionIsRejectedExplicitly() {
        TenantContext.useTenant(1L, "demoa");
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(7)))
                .thenReturn(List.of());

        TenantDataKeyService keys = new TenantDataKeyService(provider, jdbcTemplate);

        ApiException ex = assertThrows(ApiException.class, () -> keys.keyFor("field", 7));
        assertEquals(40002, ex.code());
    }

    @Test
    void concurrentCreateOnDuplicateKeyFallsBackToTheStoredRow() {
        TenantContext.useTenant(1L, "demoa");
        GeneratedDataKey winner = provider.generateDataKey();
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("field")))
                .thenReturn(null);
        doThrow(new DuplicateKeyException("uk_crypto_data_key"))
                .when(jdbcTemplate)
                .update(contains("INSERT INTO crypto_data_key"), any(Object[].class));
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(1)))
                .thenReturn(List.of(winner.wrappedKey()));

        TenantDataKeyService keys = new TenantDataKeyService(provider, jdbcTemplate);

        assertEquals(1, keys.currentVersion("field"), "插入冲突应回退到已存在的那一行");
    }

    @Test
    void cachesArePerTenant() {
        TenantDataKeyService keys = new TenantDataKeyService(provider, jdbcTemplate);
        GeneratedDataKey keyOfA = provider.generateDataKey();
        GeneratedDataKey keyOfB = provider.generateDataKey();

        TenantContext.useTenant(1L, "demoa");
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(1)))
                .thenReturn(List.of(keyOfA.wrappedKey()));
        SecretKey a1 = keys.keyFor("field", 1);

        TenantContext.useTenant(2L, "demob");
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(1)))
                .thenReturn(List.of(keyOfB.wrappedKey()));
        SecretKey b1 = keys.keyFor("field", 1);

        TenantContext.useTenant(1L, "demoa");
        SecretKey a2 = keys.keyFor("field", 1);

        assertFalse(Arrays.equals(a1.getEncoded(), b1.getEncoded()), "不同租户的数据密钥必须独立");
        assertTrue(Arrays.equals(a1.getEncoded(), a2.getEncoded()), "同租户再次取键应命中内存缓存");
    }
}
