package com.crdp.server.platform;

import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 标准数据迁出包构建器（E05-T08 / PLT-TEN-019「关键字段 · 标准数据迁出包」）。
 *
 * <p>结构（zip，未加密；整包加密由调用方经 {@link com.crdp.framework.crypto.ArchiveCipher} 完成）：
 *
 * <ul>
 *   <li>{@code data/<表名>.csv 与 .json}：每表一份 CSV（UTF-8 含表头）与 JSON（规格「每表一份 CSV，同时提供 JSON」）； 二进制列（BLOB
 *       家族）不进表转储（文件内容在 files/ 单独承载），登记进 manifest；
 *   <li>{@code dictionary/data-dictionary.csv}：表、字段、类型、注释（规格「XLSX 或 HTML」的机器可读等价物）；
 *   <li>{@code files/}：sys_file 台账的原始文件（body 列暂存或对象存储读取），命名 {@code {id}__{原名}}；
 *   <li>{@code manifest.json}：文件清单、每表行数、每文件 SHA-256（规格：校验和用 SHA-256）。
 * </ul>
 *
 * <p>必须在租户上下文内调用（读租户库与租户前缀对象）。{@code onlyTables} 非空时只导指定表（法定保留封存用）， 不导原始文件与全库清单。
 */
@Component
public class TenantPackageBuilder {

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final ObjectMapper json;

    public TenantPackageBuilder(JdbcTemplate jdbc, ObjectStorage storage, ObjectMapper json) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.json = json;
    }

    /** 构建产物。 */
    public record BuiltPackage(
            byte[] zip,
            int tableCount,
            long totalRows,
            int fileCount,
            int missingFileCount,
            List<String> tableNames) {}

    /** 包内清单条目（manifest.json tables[] 的来源）。 */
    record TableEntry(
            String name, long rows, String csvSha256, String jsonSha256, List<String> omitted) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("rows", rows);
            map.put("csvSha256", csvSha256);
            map.put("jsonSha256", jsonSha256);
            if (!omitted.isEmpty()) {
                map.put("omittedBinaryColumns", omitted);
            }
            return map;
        }
    }

    /** 构建标准迁出包（zip 字节，未加密）。 */
    public BuiltPackage build(
            long tenantId, String tenantCode, String tenantName, List<String> onlyTables) {
        List<String> tables = tablesOf(onlyTables);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8);
            List<TableEntry> entries = new ArrayList<>();
            long totalRows = 0;
            for (String table : tables) {
                TableEntry entry = exportTable(zip, table);
                entries.add(entry);
                totalRows += entry.rows();
            }
            byte[] dictionary = dictionaryCsv(tables);
            putEntry(zip, "dictionary/data-dictionary.csv", dictionary);
            List<Map<String, Object>> files = new ArrayList<>();
            List<Map<String, Object>> missing = new ArrayList<>();
            if (onlyTables == null || onlyTables.isEmpty()) {
                exportFiles(zip, files, missing);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", "crdp-export-1");
            manifest.put("generatedAt", LocalDateTime.now().withNano(0).toString());
            Map<String, Object> tenant = new LinkedHashMap<>();
            tenant.put("id", tenantId);
            tenant.put("code", tenantCode);
            tenant.put("name", tenantName);
            manifest.put("tenant", tenant);
            manifest.put("tables", entries.stream().map(TableEntry::toMap).toList());
            manifest.put("dictionarySha256", TenantCancelService.sha256Hex(dictionary));
            manifest.put("files", files);
            manifest.put("missingFiles", missing);
            putEntry(zip, "manifest.json", json.writeValueAsBytes(manifest));
            zip.close();
            return new BuiltPackage(
                    buffer.toByteArray(),
                    entries.size(),
                    totalRows,
                    files.size(),
                    missing.size(),
                    tables);
        } catch (IOException ex) {
            throw new ApiException(500, 50000, "生成迁出包失败");
        }
    }

    private List<String> tablesOf(List<String> onlyTables) {
        List<String> all =
                jdbc.queryForList(
                        "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA ="
                                + " DATABASE() AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <>"
                                + " 'flyway_schema_history' ORDER BY TABLE_NAME",
                        String.class);
        if (onlyTables == null || onlyTables.isEmpty()) {
            return all;
        }
        for (String requested : onlyTables) {
            if (!all.contains(requested)) {
                throw new ApiException(400, 40001, "封存表在租户库中不存在：" + requested + "（请核对表名后重新登记）");
            }
        }
        return onlyTables.stream().distinct().sorted().toList();
    }

    private TableEntry exportTable(ZipOutputStream zip, String table) throws IOException {
        Set<String> binaryColumns = binaryColumnsOf(table);
        List<String> columns =
                jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA ="
                                + " DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                        String.class,
                        table);
        List<String> omitted = columns.stream().filter(binaryColumns::contains).toList();
        List<String> selectColumns =
                columns.stream().filter(column -> !binaryColumns.contains(column)).toList();
        List<Map<String, Object>> rows = List.of();
        if (!selectColumns.isEmpty()) {
            String select =
                    selectColumns.stream()
                            .map(column -> "`" + column + "`")
                            .collect(Collectors.joining(", "));
            rows = jdbc.queryForList("SELECT " + select + " FROM `" + table + "`");
        }
        StringBuilder csv = new StringBuilder(csvRow(selectColumns)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : selectColumns) {
                values.add(cell(row.get(column)));
            }
            csv.append(csvRow(values)).append('\n');
        }
        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] jsonBytes = json.writeValueAsBytes(rows);
        putEntry(zip, "data/" + table + ".csv", csvBytes);
        putEntry(zip, "data/" + table + ".json", jsonBytes);
        return new TableEntry(
                table,
                rows.size(),
                TenantCancelService.sha256Hex(csvBytes),
                TenantCancelService.sha256Hex(jsonBytes),
                omitted);
    }

    private Set<String> binaryColumnsOf(String table) {
        return jdbc
                .queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA ="
                                + " DATABASE() AND TABLE_NAME = ? AND DATA_TYPE IN ('binary',"
                                + " 'varbinary', 'tinyblob', 'blob', 'mediumblob', 'longblob')",
                        String.class,
                        table)
                .stream()
                .collect(Collectors.toSet());
    }

    private byte[] dictionaryCsv(List<String> tables) {
        StringBuilder csv = new StringBuilder("表名,字段名,序号,类型,可空,键,注释\n");
        for (String table : tables) {
            jdbc.query(
                    "SELECT COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY,"
                            + " COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA ="
                            + " DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                    (rs, rowNum) -> {
                        csv.append(
                                        csvRow(
                                                List.of(
                                                        table,
                                                        rs.getString(1),
                                                        String.valueOf(rs.getInt(2)),
                                                        rs.getString(3),
                                                        rs.getString(4),
                                                        rs.getString(5),
                                                        rs.getString(6))))
                                .append('\n');
                        return null;
                    },
                    table);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void exportFiles(
            ZipOutputStream zip, List<Map<String, Object>> files, List<Map<String, Object>> missing)
            throws IOException {
        Integer present =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA ="
                                + " DATABASE() AND TABLE_NAME = 'sys_file'",
                        Integer.class);
        if (present == null || present == 0) {
            return;
        }
        List<Map<String, Object>> fileRows =
                jdbc.queryForList(
                        "SELECT id, object_key, original_name FROM sys_file"
                                + " WHERE status <> 'DELETED' ORDER BY id");
        for (Map<String, Object> row : fileRows) {
            long id = ((Number) row.get("id")).longValue();
            String objectKey = String.valueOf(row.get("object_key"));
            byte[] body =
                    jdbc.query(
                            "SELECT body FROM sys_file WHERE id = ? AND body IS NOT NULL",
                            rs -> rs.next() ? rs.getBytes(1) : null,
                            id);
            if (body == null) {
                body = storage.get(objectKey);
            }
            String safeName = safeName(String.valueOf(row.get("original_name")));
            if (body == null) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("id", id);
                miss.put("objectKey", objectKey);
                miss.put("reason", "OBJECT_NOT_FOUND");
                missing.add(miss);
                continue;
            }
            putEntry(zip, "files/" + id + "__" + safeName, body);
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("id", id);
            file.put("objectKey", objectKey);
            file.put("name", safeName);
            file.put("sizeBytes", body.length);
            file.put("sha256", TenantCancelService.sha256Hex(body));
            files.add(file);
        }
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return "0x"
                    + TenantCancelService.sha256Hex(bytes).substring(0, 16)
                    + "…("
                    + bytes.length
                    + " 字节)";
        }
        return String.valueOf(value);
    }

    static String csvRow(List<String> values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            String value = values.get(i) == null ? "" : values.get(i);
            boolean quote =
                    value.indexOf(',') >= 0
                            || value.indexOf('"') >= 0
                            || value.indexOf('\n') >= 0
                            || value.indexOf('\r') >= 0;
            if (quote) {
                row.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                row.append(value);
            }
        }
        return row.toString();
    }

    static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return cleaned.isBlank() ? "file" : cleaned;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }
}
