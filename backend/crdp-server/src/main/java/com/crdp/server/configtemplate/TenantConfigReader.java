package com.crdp.server.configtemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户库配置表读取器（E12-T01）。只在调用方已建立租户上下文时工作（{@link
 * com.crdp.framework.tenant.TenantContext#callAsTenant}），读 information_schema 元数据 + 按列清单取行。
 *
 * <p>表名与列名都来自代码注册表（{@code ConfigTableRegistry} / information_schema），不接受请求参数拼接；
 * 行排除谓词同样来自注册表代码，E12-T03 起为结构化定义（列 → 值），SQL 侧参数化绑定，不拼字面量。
 */
@Component
public class TenantConfigReader {

    private final JdbcTemplate jdbc;

    public TenantConfigReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 表在当前租户库是否存在（模块未开通 → false）。 */
    public boolean tablePresent(String table) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                        Integer.class,
                        table);
        return count != null && count > 0;
    }

    /** 列名（ordinal 顺序）；表不存在返回空列表。 */
    public List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " ORDER BY ORDINAL_POSITION",
                String.class,
                table);
    }

    /** 源表总行数（只计数，不带数据；STRUCTURE_ONLY 表也只取计数）。 */
    public long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 按列清单读行，值经 {@link ConfigPackage#cellValue} 归一化。
     *
     * @param excludeWhen 行排除谓词（注册表代码：列名 → 值的全等合取；空 = 不排除），生成 {@code WHERE NOT (c = ? AND ...)}
     *     参数化条件
     */
    public List<Map<String, Object>> rows(
            String table, List<String> columns, Map<String, String> excludeWhen) {
        String columnList =
                columns.stream().map(c -> "`" + c + "`").reduce((a, b) -> a + ", " + b).orElse("*");
        StringBuilder sql = new StringBuilder("SELECT " + columnList + " FROM `" + table + "`");
        List<String> params = new ArrayList<>();
        if (excludeWhen != null && !excludeWhen.isEmpty()) {
            sql.append(" WHERE NOT (");
            boolean first = true;
            for (Map.Entry<String, String> condition : excludeWhen.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append("`").append(condition.getKey()).append("` = ?");
                params.add(condition.getValue());
                first = false;
            }
            sql.append(")");
        }
        return jdbc.queryForList(sql.toString(), params.toArray()).stream()
                .map(
                        row -> {
                            Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                            row.forEach(
                                    (key, value) ->
                                            normalized.put(key, ConfigPackage.cellValue(value)));
                            return normalized;
                        })
                .toList();
    }
}
