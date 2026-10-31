package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.tenant.MissingTenantContextException;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** E03-T05：轮换产生新密钥版本、旧版本保留解密，轮换动作写审计（NFR-CRY-004 / NFR-CRY-005）。 */
class KeyRotationServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final LocalEnvelopeKeyProvider provider =
            new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void rotateCreatesTheNextVersionAndAudits() {
        TenantContext.useTenant(1L, "demoa");
        stubCurrentVersion(1);
        stubInsertOk();

        int next =
                new KeyRotationService(
                                new TenantDataKeyService(provider, jdbcTemplate), auditLogger)
                        .rotate("field-name");

        assertEquals(2, next);
        verify(jdbcTemplate)
                .update(
                        contains("INSERT INTO crypto_data_key"),
                        eq("field-name"),
                        eq(2),
                        contains("v1.AES-256-GCM."));
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).record(captor.capture());
        assertEquals("CRYPTO", captor.getValue().eventType());
        assertEquals("ROTATE", captor.getValue().action());
        assertEquals("crypto_data_key:field-name", captor.getValue().target());
    }

    @Test
    void rotateOnNeverUsedPurposeFirstCreatesVersionOneThenRotates() {
        TenantContext.useTenant(1L, "demoa");
        // 第一次查 MAX 为空（补建 v1），补建后再查就是 1
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("field-name")))
                .thenReturn(null, 1);
        stubInsertOk();

        int next =
                new KeyRotationService(
                                new TenantDataKeyService(provider, jdbcTemplate), auditLogger)
                        .rotate("field-name");

        assertEquals(2, next, "从未用过的用途先补建版本 1，再轮换出版本 2");
        verify(jdbcTemplate)
                .update(contains("INSERT INTO crypto_data_key"), eq("field-name"), eq(1), any());
        verify(jdbcTemplate)
                .update(contains("INSERT INTO crypto_data_key"), eq("field-name"), eq(2), any());
    }

    @Test
    void concurrentRotateConvergesOnTheStoredRow() {
        TenantContext.useTenant(1L, "demoa");
        stubCurrentVersion(1);
        GeneratedDataKey winner = provider.generateDataKey();
        doThrow(new DuplicateKeyException("uk_crypto_data_key"))
                .when(jdbcTemplate)
                .update(contains("INSERT INTO crypto_data_key"), any(Object[].class));
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field-name"), eq(2)))
                .thenReturn(List.of(winner.wrappedKey()));

        int next =
                new KeyRotationService(
                                new TenantDataKeyService(provider, jdbcTemplate), auditLogger)
                        .rotate("field-name");

        assertEquals(2, next, "插入冲突应回退到并发请求已写入的那一行，而不是报错");
    }

    @Test
    void rotationNeverDeletesOldVersions() {
        TenantContext.useTenant(1L, "demoa");
        stubCurrentVersion(3);
        stubInsertOk();

        new KeyRotationService(new TenantDataKeyService(provider, jdbcTemplate), auditLogger)
                .rotate("field-name");

        verify(jdbcTemplate, never())
                .update(contains("DELETE FROM crypto_data_key"), any(Object[].class));
        verify(jdbcTemplate, never())
                .update(contains("UPDATE crypto_data_key"), any(Object[].class));
    }

    @Test
    void rotateRequiresTenantContext() {
        KeyRotationService service =
                new KeyRotationService(
                        new TenantDataKeyService(provider, jdbcTemplate), auditLogger);

        assertThrows(MissingTenantContextException.class, () -> service.rotate("field-name"));
    }

    private void stubCurrentVersion(Integer version) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("field-name")))
                .thenReturn(version);
    }

    private void stubInsertOk() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    }
}
