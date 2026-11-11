package com.crdp.server.system.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * E08-T04 / PLT-AUD-007 导出文件渲染：带 UTF-8 BOM 的 CSV（Excel 可直接打开）， 每行首列是水印列（格式按 OQ-22： {@code 姓名（账号）·
 * 医院名称 · 时间（精确到分钟）}，导出场景时间取生成时刻）， 文件头两行是导出说明与列头。
 *
 * <p>暂行做法：M0 无 POI（ExcelGateway 占位，宽表流式读写属 E18），审计导出先用 CSV 文本水印； E13 / E18 引入 xlsx
 * 网关后若需页眉水印再切换，接口与文件哈希口径不变。
 */
public final class AuditExportCsv {

    /** 单次导出行数上限：防止单个导出作业撑爆内存与 LONGBLOB（超过时文件尾附提示行，状态仍 READY）。 */
    public static final int MAX_ROWS = 50_000;

    /** 水印时间精度：分钟（OQ-22）。 */
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(AuditQuerySupport.ZONE);

    private AuditExportCsv() {}

    /** 水印串：姓名（账号）· 机构名 · 时间（精确到分钟）。机构名传医院名称或「平台审计中心」。 */
    public static String watermark(
            String displayName, String username, String orgName, LocalDateTime at) {
        return displayName
                + "（"
                + username
                + "）· "
                + (orgName == null || orgName.isBlank() ? "平台审计中心" : orgName)
                + " · "
                + MINUTE.format(at.atZone(AuditQuerySupport.ZONE));
    }

    /**
     * 渲染 CSV。
     *
     * @param columns 列名（同时是 SELECT 列与 CSV 列头，按此顺序输出）
     * @param rows 行数据（key 为列名；缺列按空串输出）
     * @param watermark 水印串（每行首列）
     * @param note 文件头第一行的导出说明（如「审计操作日志导出 · 条件摘要」）
     */
    public static byte[] render(
            List<String> columns, List<Map<String, Object>> rows, String watermark, String note) {
        StringBuilder csv = new StringBuilder();
        csv.append(escape(note)).append('\n');
        csv.append("水印").append(',');
        csv.append(
                columns.stream()
                        .map(AuditExportCsv::escape)
                        .reduce((a, b) -> a + "," + b)
                        .orElse(""));
        csv.append('\n');
        long index = 0;
        for (Map<String, Object> row : rows) {
            csv.append(escape(watermark));
            for (String column : columns) {
                csv.append(',').append(escape(value(row.get(column))));
            }
            csv.append('\n');
            index++;
        }
        if (index >= MAX_ROWS) {
            csv.append(escape("已达单次导出上限 " + MAX_ROWS + " 行，结果被截断，请缩小条件分批导出")).append('\n');
        }
        return ('\uFEFF' + csv.toString()).getBytes(StandardCharsets.UTF_8);
    }

    /** CSV 转义：含逗号、引号、换行的字段用双引号包裹，内部引号翻倍。 */
    static String escape(String field) {
        if (field == null) {
            return "";
        }
        if (field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0) {
            return '"' + field.replace("\"", "\"\"") + '"';
        }
        return field;
    }

    private static String value(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof LocalDateTime time) {
            return AuditQuerySupport.format(time);
        }
        // queryForList 对 DATETIME 列返回 java.sql.Timestamp（资源变更记录导出，E05-T10）
        if (raw instanceof java.sql.Timestamp time) {
            return AuditQuerySupport.format(time.toLocalDateTime());
        }
        if (raw instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        return String.valueOf(raw);
    }
}
