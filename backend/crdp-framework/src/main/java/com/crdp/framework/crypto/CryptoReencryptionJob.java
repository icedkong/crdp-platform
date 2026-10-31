package com.crdp.framework.crypto;

import com.crdp.framework.job.TenantJob;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 后台重加密作业（E03-T05，NFR-CRY-002 / NFR-CRY-004）：把存量密文迁移到当前密钥版本、重算盲索引。 经 {@code
 * JobScheduler.runForEachTenant} 在各租户上下文内执行；作业编码与补跑语义沿用 ADR 0003，每日定时的注册 是 E14-T04。
 *
 * <p>断点续跑：游标（已扫到的最大主键）与目标版本持久化在租户库 crypto_reencrypt_progress（迁移 V5），每处理完一批推进一次。 中断后重跑从游标继续；
 * 已在目标版本的行重扫时跳过，因此断点附近重复扫描无副作用。目标版本变化（再次轮换）自动从头开始新的一趟。 盲索引没有版本标记，用进度行的 index_refresh_pending /
 * target_index_version 判断是否需要全量刷新。
 *
 * <p>密文无法解析或解密的行让本趟失败（job_tenant_run 记 FAILED、游标停在该行之前），人工处置后重跑即续。
 */
public class CryptoReencryptionJob implements TenantJob {

    /** 一行待巡检的存量数据：主键与密文列当前值。 */
    public record StoredValue(long id, String value) {}

    /** 单列巡检结果，用于观测与测试；不含任何明文。 */
    public record ColumnResult(String tableName, String columnName, int scanned, int reencrypted) {}

    private static final String LOAD_PROGRESS =
            """
            SELECT target_key_version, target_index_version, index_refresh_pending, last_id, status
            FROM crypto_reencrypt_progress WHERE table_name = ? AND column_name = ?
            """;

    private final CryptoService cryptoService;
    private final TenantDataKeyService dataKeys;
    private final JdbcTemplate jdbcTemplate;
    private final List<EncryptedColumn> columns;
    private final int batchSize;

    public CryptoReencryptionJob(
            CryptoService cryptoService,
            TenantDataKeyService dataKeys,
            JdbcTemplate jdbcTemplate,
            List<EncryptedColumn> columns,
            int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        this.cryptoService = cryptoService;
        this.dataKeys = dataKeys;
        this.jdbcTemplate = jdbcTemplate;
        this.columns = List.copyOf(columns);
        this.batchSize = batchSize;
    }

    @Override
    public void run(LocalDate logicalDate) {
        migrateAll();
    }

    /** 巡检全部登记的加密列。必须在租户上下文内调用。 */
    public List<ColumnResult> migrateAll() {
        List<ColumnResult> results = new ArrayList<>();
        for (EncryptedColumn column : columns) {
            results.add(migrateColumn(column));
        }
        return results;
    }

    private ColumnResult migrateColumn(EncryptedColumn column) {
        int targetKeyVersion = dataKeys.currentVersion(column.cipherPurpose());
        Integer targetIndexVersion =
                column.hasBlindIndex() ? dataKeys.currentVersion(column.blindIndexPurpose()) : null;
        Map<String, Object> progress = loadProgress(column);
        Pass pass = planPass(progress, targetKeyVersion, targetIndexVersion);
        if (pass == null) {
            return new ColumnResult(column.tableName(), column.valueColumn(), 0, 0);
        }
        writeRunning(
                column,
                progress != null,
                targetKeyVersion,
                targetIndexVersion,
                pass.indexPending(),
                pass.startId());
        int scanned = 0;
        int reencrypted = 0;
        long cursor = pass.startId();
        while (true) {
            List<StoredValue> batch = fetchBatch(column, cursor);
            if (batch.isEmpty()) {
                break;
            }
            for (StoredValue row : batch) {
                scanned++;
                if (row.value() == null || row.value().isBlank()) {
                    continue;
                }
                Ciphertext parsed = Ciphertext.parse(row.value());
                boolean cipherStale = parsed.keyVersion() != targetKeyVersion;
                if (!cipherStale && !pass.indexPending()) {
                    continue;
                }
                String plaintext = cryptoService.decrypt(row.value());
                String rewritten = cryptoService.encrypt(column.cipherPurpose(), plaintext);
                String index =
                        column.hasBlindIndex()
                                ? cryptoService.blindIndex(column.blindIndexPurpose(), plaintext)
                                : null;
                updateRow(column, row.id(), rewritten, index);
                reencrypted++;
            }
            cursor = batch.get(batch.size() - 1).id();
            jdbcTemplate.update(
                    "UPDATE crypto_reencrypt_progress SET last_id = ?"
                            + " WHERE table_name = ? AND column_name = ?",
                    cursor,
                    column.tableName(),
                    column.valueColumn());
            if (batch.size() < batchSize) {
                break;
            }
        }
        jdbcTemplate.update(
                "UPDATE crypto_reencrypt_progress SET status = 'DONE', index_refresh_pending = 0"
                        + " WHERE table_name = ? AND column_name = ?",
                column.tableName(),
                column.valueColumn());
        return new ColumnResult(column.tableName(), column.valueColumn(), scanned, reencrypted);
    }

    private record Pass(long startId, boolean indexPending) {}

    /** 已在目标版本 → null（跳过）；版本一致且 RUNNING → 断点续跑；否则新的一趟从 0 开始。 */
    private static Pass planPass(
            Map<String, Object> progress, int targetKeyVersion, Integer targetIndexVersion) {
        if (progress == null) {
            // 首趟：从未轮换过盲索引密钥（只有版本 1）时无需全量刷新
            return new Pass(0, targetIndexVersion != null && targetIndexVersion > 1);
        }
        int storedKeyVersion = ((Number) progress.get("target_key_version")).intValue();
        Object rawIndexVersion = progress.get("target_index_version");
        Integer storedIndexVersion =
                rawIndexVersion == null ? null : ((Number) rawIndexVersion).intValue();
        boolean storedPending = ((Number) progress.get("index_refresh_pending")).intValue() == 1;
        long lastId = ((Number) progress.get("last_id")).longValue();
        String status = String.valueOf(progress.get("status"));
        boolean sameVersions =
                storedKeyVersion == targetKeyVersion
                        && Objects.equals(storedIndexVersion, targetIndexVersion);
        if ("DONE".equals(status) && sameVersions) {
            return null;
        }
        if ("RUNNING".equals(status) && sameVersions) {
            return new Pass(lastId, storedPending);
        }
        boolean indexPending =
                targetIndexVersion != null
                        && (!Objects.equals(storedIndexVersion, targetIndexVersion)
                                || storedPending);
        return new Pass(0, indexPending);
    }

    private Map<String, Object> loadProgress(EncryptedColumn column) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(LOAD_PROGRESS, column.tableName(), column.valueColumn());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeRunning(
            EncryptedColumn column,
            boolean exists,
            int targetKeyVersion,
            Integer targetIndexVersion,
            boolean indexPending,
            long lastId) {
        if (!exists) {
            jdbcTemplate.update(
                    """
                    INSERT INTO crypto_reencrypt_progress
                      (table_name, column_name, target_key_version, target_index_version,
                       index_refresh_pending, last_id, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'RUNNING')
                    """,
                    column.tableName(),
                    column.valueColumn(),
                    targetKeyVersion,
                    targetIndexVersion,
                    indexPending ? 1 : 0,
                    lastId);
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE crypto_reencrypt_progress
                SET target_key_version = ?, target_index_version = ?, index_refresh_pending = ?,
                    last_id = ?, status = 'RUNNING'
                WHERE table_name = ? AND column_name = ?
                """,
                targetKeyVersion,
                targetIndexVersion,
                indexPending ? 1 : 0,
                lastId,
                column.tableName(),
                column.valueColumn());
    }

    private List<StoredValue> fetchBatch(EncryptedColumn column, long cursor) {
        String sql =
                "SELECT "
                        + column.idColumn()
                        + " AS row_id, "
                        + column.valueColumn()
                        + " AS row_value FROM "
                        + column.tableName()
                        + " WHERE "
                        + column.idColumn()
                        + " > ? ORDER BY "
                        + column.idColumn()
                        + " LIMIT ?";
        return jdbcTemplate.query(
                sql,
                (rs, i) -> new StoredValue(rs.getLong("row_id"), rs.getString("row_value")),
                cursor,
                batchSize);
    }

    private void updateRow(EncryptedColumn column, long id, String rewritten, String index) {
        if (column.hasBlindIndex()) {
            jdbcTemplate.update(
                    "UPDATE "
                            + column.tableName()
                            + " SET "
                            + column.valueColumn()
                            + " = ?, "
                            + column.blindIndexColumn()
                            + " = ? WHERE "
                            + column.idColumn()
                            + " = ?",
                    rewritten,
                    index,
                    id);
            return;
        }
        jdbcTemplate.update(
                "UPDATE "
                        + column.tableName()
                        + " SET "
                        + column.valueColumn()
                        + " = ? WHERE "
                        + column.idColumn()
                        + " = ?",
                rewritten,
                id);
    }
}
