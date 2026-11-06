package com.crdp.server.configtemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 租户配置模板包（E12-T01 / A-FLD-014）：从源租户导出的、带版本的配置快照。 存平台库 {@code plt_config_template.package_body}（gzip
 * JSON）；导入与 ID 重映射是 E12-T02。
 *
 * <p>包内只含 {@code ConfigTableRegistry} 登记的配置表节；值经 {@link #cellValue} 归一化成 可再导入的字符串 / 基本类型（DATETIME →
 * {@code yyyy-MM-dd HH:mm:ss[.SSS]}，二进制 → Base64）。
 *
 * @param formatVersion 包结构版本（当前 1；结构变化时递增并兼容旧包）
 * @param tenantCode 源租户编码
 * @param tenantName 源租户名称（导出时快照）
 * @param exportedAt 导出时间（ISO-8601 带时区）
 * @param tables 配置表节，按注册表顺序
 */
public record ConfigPackage(
        int formatVersion,
        String tenantCode,
        String tenantName,
        String exportedAt,
        List<TableSection> tables) {

    /**
     * 单张配置表的节。
     *
     * @param table 表名
     * @param module 所属模块（base / followup）
     * @param mode COPY / STRUCTURE_ONLY（注册表口径）
     * @param category A-FLD-014 类别名
     * @param present 源租户库是否存在该表（模块未开通 → false，rows / columns 为空）
     * @param rowCount 入包行数（STRUCTURE_ONLY 恒 0）
     * @param excludedRowCount 被行排除谓词剔除的行数（如术者取值）
     * @param sourceRowCount 源表总行数（只计数不带数据；STRUCTURE_ONLY 也有计数便于核对）
     * @param columns 列名（information_schema 顺序；absent 为空）
     * @param rows 行数据（键为列名，值经 {@link #cellValue} 归一化；STRUCTURE_ONLY / absent 为空）
     */
    public record TableSection(
            String table,
            String module,
            String mode,
            String category,
            boolean present,
            long rowCount,
            long excludedRowCount,
            long sourceRowCount,
            List<String> columns,
            List<Map<String, Object>> rows) {

        /** 详情视图用：不带行数据的元信息（预览、核对）。 */
        public TableSectionMeta toMeta() {
            return new TableSectionMeta(
                    table,
                    module,
                    mode,
                    category,
                    present,
                    rowCount,
                    excludedRowCount,
                    sourceRowCount,
                    columns);
        }
    }

    /** 表节元信息（不带行，详情 / 预览用）。 */
    public record TableSectionMeta(
            String table,
            String module,
            String mode,
            String category,
            boolean present,
            long rowCount,
            long excludedRowCount,
            long sourceRowCount,
            List<String> columns) {}

    /** 数据库单元格值归一化：日期时间 → MySQL 可直接再插入的字符串；二进制 → Base64；其余原样。 配置表不应有二进制列，兜底转 Base64 保证包永远可序列化。 */
    public static Object cellValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return format(time);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return format(timestamp.toLocalDateTime());
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalTime time) {
            return time.toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    private static String format(LocalDateTime time) {
        String text = time.toString().replace('T', ' ');
        return text;
    }
}
