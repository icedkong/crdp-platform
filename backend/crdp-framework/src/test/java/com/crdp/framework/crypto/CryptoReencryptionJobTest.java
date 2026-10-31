package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** E03-T05：重加密作业的续跑决策——完成跳过、断点续跑、版本变化从头、盲索引轮换全量刷新。 */
class CryptoReencryptionJobTest {

    private static final String V1_CIPHER =
            "v1.AES-256-GCM.1.field-name.AAAAAAAAAAAAAAAA.AAAAAAAAAAAA";
    private static final String V2_CIPHER =
            "v1.AES-256-GCM.2.field-name.AAAAAAAAAAAAAAAA.AAAAAAAAAAAA";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final TenantDataKeyService dataKeys = mock(TenantDataKeyService.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void columnAlreadyDoneWithCurrentVersionsIsSkippedEntirely() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(1, 1);
        stubProgress(done(1, 1, 0, 9L));

        job(column).migrateAll();

        verify(jdbcTemplate, never())
                .query(contains("FROM it_patient"), any(RowMapper.class), any());
    }

    @Test
    void interruptedPassResumesFromThePersistedCheckpoint() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(2, 1);
        stubProgress(running(2, 1, 0, 5L));
        stubBatch(5L, List.of()); // 从 5 继续后没有更多行

        job(column).migrateAll();

        verify(jdbcTemplate)
                .query(contains("FROM it_patient"), any(RowMapper.class), eq(5L), eq(200));
        verify(jdbcTemplate)
                .update(contains("SET status = 'DONE'"), eq("it_patient"), eq("name_cipher"));
    }

    @Test
    void changedKeyVersionRestartsThePassFromTheBeginning() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(2, 1);
        stubProgress(done(1, 1, 0, 9L)); // 上一趟完成在版本 1，现在目标是 2
        stubBatch(0L, List.of());

        job(column).migrateAll();

        verify(jdbcTemplate)
                .query(contains("FROM it_patient"), any(RowMapper.class), eq(0L), eq(200));
    }

    @Test
    void indexKeyRotationAloneRefreshesRowsEvenWhenCipherIsCurrent() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(1, 2); // 字段密钥没轮换，盲索引密钥轮换到 2
        stubProgress(done(1, 1, 0, 9L));
        stubBatch(0L, List.of(new CryptoReencryptionJob.StoredValue(1L, V1_CIPHER)));
        when(cryptoService.decrypt(V1_CIPHER)).thenReturn("张医生（示例）");
        when(cryptoService.encrypt("field-name", "张医生（示例）")).thenReturn("re-encrypted");
        when(cryptoService.blindIndex("idx-name", "张医生（示例）")).thenReturn("bi");

        job(column).migrateAll();

        verify(jdbcTemplate)
                .update(
                        contains("UPDATE it_patient SET name_cipher = ?, name_bi = ? WHERE id = ?"),
                        eq("re-encrypted"),
                        eq("bi"),
                        eq(1L));
    }

    @Test
    void rowAlreadyOnTargetVersionIsNotTouchedWhenIndexIsCurrent() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(2, 1);
        stubProgress(running(2, 1, 0, 0L));
        stubBatch(0L, List.of(new CryptoReencryptionJob.StoredValue(1L, V2_CIPHER)));

        job(column).migrateAll();

        verify(jdbcTemplate, never())
                .update(contains("UPDATE it_patient SET"), any(Object[].class));
    }

    @Test
    void malformedCiphertextFailsThePassWithoutAdvancingTheCheckpoint() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(2, 1);
        stubProgress(running(2, 1, 0, 0L));
        stubBatch(0L, List.of(new CryptoReencryptionJob.StoredValue(1L, "not-a-ciphertext")));

        assertThrows(ApiException.class, () -> job(column).migrateAll());

        verify(jdbcTemplate, never()).update(contains("SET last_id ="), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("SET status = 'DONE'"), any(Object[].class));
    }

    @Test
    void implementsTenantJobSoTheSchedulerCanFanItOut() {
        TenantContext.useTenant(1L, "demoa");
        EncryptedColumn column = column();
        stubVersions(1, 1);
        stubProgress(null);

        job(column).run(LocalDate.of(2026, 12, 14));

        verify(jdbcTemplate)
                .query(contains("FROM it_patient"), any(RowMapper.class), eq(0L), eq(200));
    }

    @Test
    void invalidIdentifierInColumnDefinitionIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EncryptedColumn(
                                "it_patient; DROP TABLE it_patient",
                                "id",
                                "name_cipher",
                                "field-name",
                                null,
                                null));
    }

    private CryptoReencryptionJob job(EncryptedColumn column) {
        return new CryptoReencryptionJob(
                cryptoService, dataKeys, jdbcTemplate, List.of(column), 200);
    }

    private static EncryptedColumn column() {
        return new EncryptedColumn(
                "it_patient", "id", "name_cipher", "field-name", "name_bi", "idx-name");
    }

    private void stubVersions(int cipherVersion, int indexVersion) {
        when(dataKeys.currentVersion("field-name")).thenReturn(cipherVersion);
        when(dataKeys.currentVersion("idx-name")).thenReturn(indexVersion);
    }

    private void stubProgress(Map<String, Object> row) {
        List<Map<String, Object>> rows = row == null ? List.of() : List.of(row);
        when(jdbcTemplate.queryForList(
                        contains("FROM crypto_reencrypt_progress"),
                        eq("it_patient"),
                        eq("name_cipher")))
                .thenReturn(rows);
    }

    private void stubBatch(long afterId, List<CryptoReencryptionJob.StoredValue> rows) {
        when(jdbcTemplate.query(
                        contains("FROM it_patient"), any(RowMapper.class), eq(afterId), eq(200)))
                .thenAnswer(invocation -> rows);
    }

    private static Map<String, Object> done(
            int targetKeyVersion, int targetIndexVersion, int pending, long lastId) {
        return progress("DONE", targetKeyVersion, targetIndexVersion, pending, lastId);
    }

    private static Map<String, Object> running(
            int targetKeyVersion, int targetIndexVersion, int pending, long lastId) {
        return progress("RUNNING", targetKeyVersion, targetIndexVersion, pending, lastId);
    }

    private static Map<String, Object> progress(
            String status, int targetKeyVersion, int targetIndexVersion, int pending, long lastId) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status);
        row.put("target_key_version", targetKeyVersion);
        row.put("target_index_version", targetIndexVersion);
        row.put("index_refresh_pending", pending);
        row.put("last_id", lastId);
        return row;
    }
}
