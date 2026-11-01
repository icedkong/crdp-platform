package com.crdp.framework.audit;

import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审计哈希链验链（E08-T02 / PLT-AUD-009，验收：篡改任一条验链即报错）。
 *
 * <p>按表内主键升序逐行重算 hash = SHA-256(prev_hash + 规范化内容) 并核对衔接：
 *
 * <ul>
 *   <li>{@code BAD_HASH}：存量 hash 与按行内容重算值不符——内容被篡改（含 prev_hash 被改，prev 参与本体哈希）；
 *   <li>{@code BROKEN_LINK}：prev_hash 与实际前序（上一条已封存行的 hash，链首应为 GENESIS）不衔接——中间行被删、被调序；
 *   <li>{@code MISSING_HASH}：链已开始后又出现未封存行——绕过写入服务的直插。
 * </ul>
 *
 * <p>E08-T01 遗留的未封存前缀（链首之前、hash 为 NULL 的行）跳过。 只读操作，不修复、不补写；断链处置由调用方（验链作业记 BROKEN
 * 结果并告警，PLT-AUD-009）。
 */
@Component
public class AuditChainVerifier {

    /** 断链类型。 */
    public enum BreakKind {
        /** 存量 hash 与重算值不符（内容或 prev_hash 被改）。 */
        BAD_HASH,

        /** prev_hash 与实际前序不衔接（中间行被删 / 调序 / 链首锚错）。 */
        BROKEN_LINK,

        /** 链已开始后又出现未封存行。 */
        MISSING_HASH
    }

    /** 一处断链。detail 不含行内容（审计值可能敏感），只含定位信息。 */
    public record ChainBreak(String table, long id, BreakKind kind, String detail) {}

    /** 一张表的验链结果。 */
    public record TableVerification(
            String table, long scannedRows, long hashedRows, List<ChainBreak> breaks) {

        public boolean intact() {
            return breaks.isEmpty();
        }
    }

    /** 验链扫描到的一行（列值与登记列对齐 + prev_hash / hash）。 */
    record ChainRow(long id, List<Object> values, String prevHash, String hash) {}

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final DigestService digest;

    public AuditChainVerifier(JdbcTemplate jdbc, DigestService digest) {
        this.jdbc = jdbc;
        this.digest = digest;
    }

    /** 校验当前租户库三张审计表（须在租户上下文内）。 */
    public List<TableVerification> verifyTenantDatabase() {
        TenantContext.require();
        return verifyTables(AuditChainTable.tenantTables());
    }

    /** 校验平台库三张审计表（自动切平台上下文，可在任意上下文调用）。 */
    public List<TableVerification> verifyPlatformDatabase() {
        return TenantContext.callAsPlatform(() -> verifyTables(AuditChainTable.platformTables()));
    }

    private List<TableVerification> verifyTables(List<AuditChainTable> tables) {
        List<TableVerification> results = new ArrayList<>(tables.size());
        for (AuditChainTable table : tables) {
            results.add(verifyTable(table));
        }
        return results;
    }

    /** 校验单表（在表所在库的上下文内调用）。分页扫描，内存占用与批大小成正比。 */
    public TableVerification verifyTable(AuditChainTable table) {
        String columns = String.join(", ", table.hashColumns());
        String sql =
                "SELECT id, "
                        + columns
                        + ", prev_hash, hash FROM "
                        + table.tableName()
                        + " WHERE id > ? ORDER BY id LIMIT "
                        + BATCH_SIZE;
        long scanned = 0;
        long hashed = 0;
        List<ChainBreak> breaks = new ArrayList<>();
        String expectedPrev = AuditChainTable.GENESIS;
        boolean chainStarted = false;
        long cursor = 0L;
        while (true) {
            List<ChainRow> batch =
                    jdbc.query(
                            sql,
                            (rs, rowNum) ->
                                    new ChainRow(
                                            rs.getLong("id"),
                                            readValues(rs, table),
                                            rs.getString("prev_hash"),
                                            rs.getString("hash")),
                            cursor);
            for (ChainRow row : batch) {
                scanned++;
                if (row.hash() == null) {
                    if (chainStarted) {
                        breaks.add(
                                new ChainBreak(
                                        table.tableName(),
                                        row.id(),
                                        BreakKind.MISSING_HASH,
                                        "链已开始后出现未封存行"));
                    }
                    continue;
                }
                chainStarted = true;
                hashed++;
                String recomputed = digest.hashHex(table.hashInput(row.prevHash(), row.values()));
                if (!recomputed.equals(row.hash())) {
                    breaks.add(
                            new ChainBreak(
                                    table.tableName(),
                                    row.id(),
                                    BreakKind.BAD_HASH,
                                    "存量 hash 与按内容重算值不符"));
                }
                if (!expectedPrev.equals(row.prevHash())) {
                    breaks.add(
                            new ChainBreak(
                                    table.tableName(),
                                    row.id(),
                                    BreakKind.BROKEN_LINK,
                                    "prev_hash 与实际前序不衔接"));
                }
                expectedPrev = row.hash();
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            cursor = batch.get(batch.size() - 1).id();
        }
        return new TableVerification(table.tableName(), scanned, hashed, List.copyOf(breaks));
    }

    private static List<Object> readValues(java.sql.ResultSet rs, AuditChainTable table)
            throws java.sql.SQLException {
        List<Object> values = new ArrayList<>(table.hashColumns().size());
        for (String column : table.hashColumns()) {
            values.add(rs.getObject(column));
        }
        return values;
    }
}
