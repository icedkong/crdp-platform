package com.crdp.framework.audit;

import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.storage.AuditArchiveStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审计归档器（E08-T03 / PLT-AUD-010，验收：归档对象锁定 15 年、在线保留 1 年；设计见 ADR 0005）。
 *
 * <p>审计表按月分区（迁移 V18 / V27）。两个职责：
 *
 * <ol>
 *   <li><b>分区维护</b> {@link #ensurePartitions}：从现有最高月分区之后补齐到「本月 + partitionsAhead」， 单条 REORGANIZE
 *       pmax 完成，月末新数据无需人工建分区；幂等，无缺月则不执行 DDL；
 *   <li><b>到期归档</b> {@link #archiveExpired}：上界早于「今天 − onlineRetentionMonths」的月分区， 导出为压缩 JSONL +
 *       清单（行数、首尾序号、首尾链值、摘要），<b>单次 PUT</b> 写入审计归档 WORM 桶并设对象级保留 = 归档日 + wormRetentionYears
 *       年，<b>读回校验</b>通过后先登记 aud_archive 再 DROP PARTITION（不触发行级只读触发器）。
 * </ol>
 *
 * <p>删除不可逆，顺序严格「上传 → 读回校验 → 登记 → 删分区」，任何一步失败都不删：
 *
 * <ul>
 *   <li>上传失败 / 读回不符 → FAILED，分区保留在线，作业计失败；
 *   <li>桶里已有同键但内容不同（WORM 不可覆盖）→ CONFLICT，转人工；
 *   <li>登记已存在而分区仍在（登记后、删分区前崩溃）→ 重导出、按对象摘要校验后直接删（RESUMED）， 不再上传；
 *   <li>WORM 对象键名确定性（{前缀}/{表}/{yyyyMM}.*），同分区重跑不产生第二份对象。
 * </ul>
 *
 * <p>归档桶只写北京四一份（不做异地副本，已确认）；桶级 WORM 与归档专用账号由运维开启（E01-T08 / E22-T08）， {@link
 * AuditArchiveStorage#enabled()} 为 false 时（无云凭证的本机 / CI）只做分区维护，不动在线数据。
 */
@Component
public class AuditArchiver {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiver.class);

    private static final Pattern MONTH_PARTITION = Pattern.compile("^p(\\d{4})(\\d{2})$");

    private static final DateTimeFormatter KEY_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 归档策略：在线保留月数（默认 12 = 在线 1 年）、WORM 保留年数（默认 15）、预建分区月数。 */
    public record ArchivePolicy(
            int onlineRetentionMonths, int wormRetentionYears, int partitionsAhead) {

        public ArchivePolicy {
            if (onlineRetentionMonths < 1 || wormRetentionYears < 1 || partitionsAhead < 0) {
                throw new IllegalArgumentException("归档策略参数必须为正（预建月数可为 0）：" + this);
            }
        }
    }

    /** 单分区处理结果；FAILED / CONFLICT 计为作业失败，其余成功。 */
    public enum OutcomeStatus {
        /** 本次上传、校验、登记并删除。 */
        ARCHIVED,
        /** 到期分区无数据：只登记（行数 0）并删除，不上传对象。 */
        EMPTY,
        /** 登记已存在（上次运行在删分区前中断）：校验对象后直接删除。 */
        RESUMED,
        /** 桶中已有同键不同内容的对象（WORM 不可覆盖），转人工处理。 */
        CONFLICT,
        /** 上传或读回校验失败，分区保留在线。 */
        FAILED
    }

    /** 一个分区的处理结果（detail 不含行内容）。 */
    public record PartitionOutcome(
            String table, String partition, OutcomeStatus status, String detail) {

        public boolean success() {
            return status != OutcomeStatus.CONFLICT && status != OutcomeStatus.FAILED;
        }
    }

    /** 按月分区名与覆盖月份（p202601 覆盖 2026-01，上界 2026-02-01）。 */
    record MonthPartition(String name, LocalDate monthStart) {

        LocalDate boundExclusive() {
            return monthStart.plusMonths(1);
        }

        static Optional<MonthPartition> parse(String partitionName) {
            var matcher = MONTH_PARTITION.matcher(partitionName);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            if (month < 1 || month > 12) {
                return Optional.empty();
            }
            return Optional.of(new MonthPartition(partitionName, LocalDate.of(year, month, 1)));
        }
    }

    /** 导出产物：JSONL 文本与清单用的统计量。 */
    private record Export(
            String jsonl,
            long rowCount,
            Long firstId,
            Long lastId,
            String firstPrevHash,
            String lastHash) {

        String sha256(DigestService digest) {
            return digest.hashHex(jsonl);
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditArchiveStorage store;
    private final DigestService digest;

    public AuditArchiver(JdbcTemplate jdbc, AuditArchiveStorage store, DigestService digest) {
        this.jdbc = jdbc;
        this.store = store;
        this.digest = digest;
    }

    // ---------------------------------------------------------------- 入口：一个库的完整归档轮次

    /** 对当前库（租户上下文或平台上下文内调用）的全部审计表执行一轮：先补齐未来分区，再归档到期分区。 返回全部到期分区的处理结果（未到期不产生条目）。 */
    public List<PartitionOutcome> runForDatabase(
            List<AuditChainTable> tables, String keyPrefix, ArchivePolicy policy, LocalDate today) {
        List<PartitionOutcome> outcomes = new ArrayList<>();
        for (AuditChainTable table : tables) {
            ensurePartitions(table, today, policy);
            outcomes.addAll(archiveExpired(table, keyPrefix, policy, today));
        }
        return outcomes;
    }

    // ---------------------------------------------------------------- 分区维护

    /** 补齐从现有最高月分区次月到「本月 + partitionsAhead」的缺月（REORGANIZE pmax，一次性追加，以 pmax 收尾）。 */
    public void ensurePartitions(AuditChainTable table, LocalDate today, ArchivePolicy policy) {
        YearMonth highest = null;
        for (String name : partitionNames(table)) {
            Optional<MonthPartition> parsed = MonthPartition.parse(name);
            if (parsed.isPresent()) {
                YearMonth month = YearMonth.from(parsed.get().monthStart());
                if (highest == null || month.isAfter(highest)) {
                    highest = month;
                }
            }
        }
        YearMonth next = highest == null ? YearMonth.from(today) : highest.plusMonths(1);
        YearMonth through = YearMonth.from(today).plusMonths(policy.partitionsAhead());
        if (next.isAfter(through)) {
            return;
        }
        int added = 0;
        StringBuilder ddl =
                new StringBuilder("ALTER TABLE ")
                        .append(table.tableName())
                        .append(" REORGANIZE PARTITION pmax INTO (");
        for (YearMonth month = next; !month.isAfter(through); month = month.plusMonths(1)) {
            LocalDate bound = month.atDay(1).plusMonths(1);
            ddl.append("PARTITION p")
                    .append(month.getYear())
                    .append(String.format("%02d", month.getMonthValue()))
                    .append(" VALUES LESS THAN (TO_DAYS('")
                    .append(bound)
                    .append("')), ");
            added++;
        }
        ddl.append("PARTITION pmax VALUES LESS THAN (MAXVALUE))");
        jdbc.update(ddl.toString());
        log.info("审计分区维护 {}：补齐 {} 至 {}（新增 {} 个月分区）", table.tableName(), next, through, added);
    }

    // ---------------------------------------------------------------- 到期归档

    /** 归档上界早于「今天 − 在线保留月数」的月分区；存储未配置时不动在线数据。 */
    public List<PartitionOutcome> archiveExpired(
            AuditChainTable table, String keyPrefix, ArchivePolicy policy, LocalDate today) {
        if (!store.enabled()) {
            log.debug("审计归档桶未配置，{} 只保留在线数据（分区维护照常）", table.tableName());
            return List.of();
        }
        LocalDate cutoff = today.minusMonths(policy.onlineRetentionMonths());
        List<PartitionOutcome> outcomes = new ArrayList<>();
        for (String name : partitionNames(table)) {
            Optional<MonthPartition> parsed = MonthPartition.parse(name);
            if (parsed.isEmpty()) {
                continue; // pmax 与非本约定命名的分区不参与到期判断
            }
            MonthPartition partition = parsed.get();
            if (partition.boundExclusive().isAfter(cutoff)) {
                continue; // 分区内仍可能含保留期内数据
            }
            outcomes.add(archiveOne(table, partition, keyPrefix, policy, today));
        }
        return outcomes;
    }

    private PartitionOutcome archiveOne(
            AuditChainTable table,
            MonthPartition partition,
            String keyPrefix,
            ArchivePolicy policy,
            LocalDate today) {
        try {
            return doArchive(table, partition, keyPrefix, policy, today);
        } catch (RuntimeException ex) {
            log.error(
                    "审计归档失败（分区保留在线）table={} partition={}", table.tableName(), partition.name(), ex);
            return new PartitionOutcome(
                    table.tableName(), partition.name(), OutcomeStatus.FAILED, abbreviate(ex));
        }
    }

    private PartitionOutcome doArchive(
            AuditChainTable table,
            MonthPartition partition,
            String keyPrefix,
            ArchivePolicy policy,
            LocalDate today) {
        String tableName = table.tableName();
        boolean registered =
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM aud_archive WHERE table_name = ? AND partition_name = ?",
                                Long.class,
                                tableName,
                                partition.name())
                        > 0;
        Export export = export(table, partition);
        LocalDate retainUntil = today.plusYears(policy.wormRetentionYears());
        String month = partition.monthStart().format(KEY_MONTH);
        String dataKey = keyPrefix + "/" + tableName + "/" + month + ".jsonl.gz";
        String manifestKey = keyPrefix + "/" + tableName + "/" + month + ".manifest.json";

        // 空分区无可归档内容：登记（行数 0）后直接删
        if (export.rowCount() == 0) {
            if (!registered) {
                insertRegistry(tableName, partition, null, export, null, retainUntil);
            }
            dropPartition(tableName, partition.name());
            return new PartitionOutcome(tableName, partition.name(), OutcomeStatus.EMPTY, null);
        }

        String sha = export.sha256(digest);
        byte[] existing = store.get(dataKey);
        if (existing == null) {
            store.putWithRetention(dataKey, gzip(export.jsonl()), "application/gzip", retainUntil);
        } else if (!sha.equals(digest.hashHex(gunzipToString(existing)))) {
            log.error("审计归档冲突：桶中已有同键不同内容对象（WORM 不可覆盖），转人工 key={}", dataKey);
            return new PartitionOutcome(
                    tableName, partition.name(), OutcomeStatus.CONFLICT, "同键对象内容不一致");
        }

        String manifest = manifestOf(table, partition, export, sha, today);
        if (store.get(manifestKey) == null) {
            store.putWithRetention(
                    manifestKey,
                    manifest.getBytes(StandardCharsets.UTF_8),
                    "application/json",
                    retainUntil);
        }

        // 读回校验：对象内容与清单都要对得上才允许删在线分区
        byte[] readBack = store.get(dataKey);
        if (readBack == null || !sha.equals(digest.hashHex(gunzipToString(readBack)))) {
            return new PartitionOutcome(
                    tableName, partition.name(), OutcomeStatus.FAILED, "数据对象读回校验失败");
        }
        byte[] manifestBack = store.get(manifestKey);
        if (manifestBack == null || !manifestMatches(manifestBack, export, sha)) {
            return new PartitionOutcome(
                    tableName, partition.name(), OutcomeStatus.FAILED, "清单读回校验失败");
        }

        if (!registered) {
            insertRegistry(tableName, partition, dataKey, export, sha, retainUntil);
        }
        dropPartition(tableName, partition.name());
        log.info(
                "审计分区已归档并删除 table={} partition={} rows={} retainUntil={}",
                tableName,
                partition.name(),
                export.rowCount(),
                retainUntil);
        return new PartitionOutcome(
                tableName,
                partition.name(),
                registered ? OutcomeStatus.RESUMED : OutcomeStatus.ARCHIVED,
                null);
    }

    // ---------------------------------------------------------------- 导出与清单

    /**
     * 导出分区全部行为 JSONL（每行一个 JSON 对象，SELECT * 全列保真），并统计首尾序号与链值。
     *
     * <p>按物理分区选取（{@code PARTITION (pYYYYMM)}）而不是按月份时间范围：首个分区横跨「早于其上界」的全部历史行
     * （装库前已存在的数据落在这里），按月份窗口导出会漏掉这些行——归档后删分区即丢数据。
     */
    private Export export(AuditChainTable table, MonthPartition partition) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + table.tableName()
                                + " PARTITION ("
                                + partition.name()
                                + ") ORDER BY id");
        StringBuilder jsonl = new StringBuilder();
        Long firstId = null;
        Long lastId = null;
        String firstPrevHash = null;
        String lastHash = null;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (i > 0) {
                jsonl.append('\n');
            }
            appendRow(jsonl, row);
            Object id = row.get("id");
            if (id instanceof Number number) {
                if (firstId == null) {
                    firstId = number.longValue();
                }
                lastId = number.longValue();
            }
            if (row.get("hash") != null) {
                if (firstPrevHash == null && row.get("prev_hash") != null) {
                    firstPrevHash = String.valueOf(row.get("prev_hash"));
                }
                lastHash = String.valueOf(row.get("hash"));
            }
        }
        return new Export(jsonl.toString(), rows.size(), firstId, lastId, firstPrevHash, lastHash);
    }

    private static void appendRow(StringBuilder jsonl, Map<String, Object> row) {
        jsonl.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> column : row.entrySet()) {
            if (!first) {
                jsonl.append(',');
            }
            first = false;
            jsonl.append('"')
                    .append(column.getKey())
                    .append("\":")
                    .append(jsonValue(column.getValue()));
        }
        jsonl.append('}');
    }

    /** 清单：行数、首尾序号、首尾链值、JSONL 摘要（tech-plan §14：行数、首尾序号、链值）。 */
    private String manifestOf(
            AuditChainTable table,
            MonthPartition partition,
            Export export,
            String sha,
            LocalDate today) {
        return "{"
                + "\"version\":1"
                + ",\"table\":\""
                + table.tableName()
                + "\""
                + ",\"partition\":\""
                + partition.name()
                + "\""
                + ",\"month\":\""
                + partition.monthStart().format(KEY_MONTH)
                + "\""
                + ",\"rowCount\":"
                + export.rowCount()
                + ",\"firstId\":"
                + jsonNumber(export.firstId())
                + ",\"lastId\":"
                + jsonNumber(export.lastId())
                + ",\"firstPrevHash\":"
                + jsonString(export.firstPrevHash())
                + ",\"lastHash\":"
                + jsonString(export.lastHash())
                + ",\"sha256\":"
                + jsonString(sha)
                + ",\"archivedAt\":\""
                + today
                + "\"}";
    }

    /** 清单读回校验：核对定位与摘要字段（archivedAt 等非确定性字段不比对）。 */
    private boolean manifestMatches(byte[] manifestBack, Export export, String sha) {
        String manifest = new String(manifestBack, StandardCharsets.UTF_8);
        return manifest.contains("\"rowCount\":" + export.rowCount())
                && manifest.contains("\"firstId\":" + jsonNumber(export.firstId()))
                && manifest.contains("\"lastId\":" + jsonNumber(export.lastId()))
                && manifest.contains("\"sha256\":" + jsonString(sha));
    }

    private void insertRegistry(
            String tableName,
            MonthPartition partition,
            String dataKey,
            Export export,
            String sha,
            LocalDate retainUntil) {
        jdbc.update(
                """
                INSERT INTO aud_archive
                  (table_name, partition_name, month_first_day, object_key, row_count,
                   first_id, last_id, first_prev_hash, last_hash, sha256, retention_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tableName,
                partition.name(),
                partition.monthStart(),
                dataKey,
                export.rowCount(),
                export.firstId(),
                export.lastId(),
                export.firstPrevHash(),
                export.lastHash(),
                sha,
                retainUntil);
    }

    private void dropPartition(String tableName, String partitionName) {
        // DROP PARTITION 是 DDL，不触发行级只读触发器（V17 / V26 注释口径）
        jdbc.update("ALTER TABLE " + tableName + " DROP PARTITION " + partitionName);
    }

    // ---------------------------------------------------------------- 基础设施

    private List<String> partitionNames(AuditChainTable table) {
        return jdbc.queryForList(
                """
                SELECT PARTITION_NAME FROM information_schema.PARTITIONS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL
                ORDER BY PARTITION_ORDINAL_POSITION
                """,
                String.class,
                table.tableName());
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return jsonString(Base64.getEncoder().encodeToString(bytes));
        }
        return jsonString(value.toString());
    }

    private static String jsonNumber(Long value) {
        return value == null ? "null" : value.toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String abbreviate(RuntimeException ex) {
        String message = ex.getMessage();
        message = message == null ? ex.getClass().getSimpleName() : message;
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /** 压缩为 gzip 字节（单次 PUT 的对象体）。 */
    public static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** 解压读回的对象体（校验用）；损坏数据抛 {@link UncheckedIOException}，由分区级兜底计 FAILED。 */
    public static String gunzipToString(byte[] gz) {
        try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
