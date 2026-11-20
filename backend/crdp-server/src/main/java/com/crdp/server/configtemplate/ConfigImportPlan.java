package com.crdp.server.configtemplate;

import static com.crdp.server.configtemplate.ConfigTemplateImportService.asLong;

import com.crdp.platform.configtemplate.ConfigCopyMode;
import com.crdp.platform.configtemplate.ConfigImportMode;
import com.crdp.platform.configtemplate.ConfigImportSpec;
import com.crdp.platform.configtemplate.ConfigImportSpecs;
import com.crdp.platform.configtemplate.ConfigTableRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** OQR-02：只在内存中生成应用计划；预检与执行共用，不向数据库写入试探性数据。 */
final class ConfigImportPlan {
    record Target(List<String> columns, List<Map<String, Object>> rows) {}

    record Change(String table, int sourceRow, String action, List<String> columns, String note) {}

    /** 负数 ID 仅为本计划内的新行引用，执行时替换为目标库生成的真实 ID。 */
    record Write(String table, String action, Map<String, Object> values, Long id) {}

    final List<Write> writes = new ArrayList<>();
    final List<Change> changes = new ArrayList<>();
    final List<ConfigTemplateImportService.TableResult> tables = new ArrayList<>();
    private final Map<String, Target> targets;
    private final Map<String, Map<Long, Long>> ids = new LinkedHashMap<>();
    private final Set<Long> assignable;
    private long nextId = -1;
    private final Set<Long> retainedPlans = new java.util.HashSet<>();
    private final ConfigPackage source;
    private final boolean initialization;

    ConfigImportPlan(
            ConfigPackage source,
            Map<String, Target> targets,
            Set<Long> assignable,
            boolean initialization) {
        this.source = source;
        this.assignable = assignable;
        this.initialization = initialization;
        this.targets = new LinkedHashMap<>();
        targets.forEach(
                (table, target) ->
                        this.targets.put(
                                table,
                                new Target(
                                        target.columns(),
                                        target.rows().stream()
                                                .map(
                                                        row ->
                                                                (Map<String, Object>)
                                                                        new LinkedHashMap<>(row))
                                                .collect(
                                                        java.util.stream.Collectors.toCollection(
                                                                ArrayList::new)))));
        // 只按代码注册表的拓扑顺序执行，不能依赖包内节的顺序。
        for (var entry : ConfigTableRegistry.ENTRIES) {
            var section =
                    source.tables().stream()
                            .filter(s -> s.table().equals(entry.table()))
                            .findFirst()
                            .orElse(null);
            if (section != null) section(section);
        }
    }

    boolean applicable() {
        return changes.stream().noneMatch(c -> c.action().equals("CONFLICT"));
    }

    private void section(ConfigPackage.TableSection section) {
        String table = section.table();
        var entry = ConfigTableRegistry.entry(table);
        ConfigImportSpec spec = ConfigImportSpecs.of(table);
        Target target = targets.get(table);
        long inserted = 0, merged = 0, skipped = 0, dropped = 0;
        if (entry.mode() == ConfigCopyMode.STRUCTURE_ONLY
                || !section.present()
                || spec == null
                || target == null) {
            tables.add(
                    ConfigTemplateImportService.TableResult.skip(
                            table,
                            entry.mode().name(),
                            section.rows().size(),
                            "只复制结构、源节为空或目标库缺表，未写入"));
            return;
        }
        if (spec.mode() == ConfigImportMode.DROP_ROWS) {
            tables.add(
                    new ConfigTemplateImportService.TableResult(
                            table,
                            entry.mode().name(),
                            true,
                            section.rows().size(),
                            0,
                            0,
                            0,
                            section.rows().size(),
                            "组织架构不复制，科室范围行丢弃"));
            return;
        }
        List<String> columns =
                section.columns().stream().filter(target.columns()::contains).toList();
        if (!columns.containsAll(spec.keyColumns())) {
            changes.add(new Change(table, 0, "CONFLICT", spec.keyColumns(), "目标缺少自然键列"));
            return;
        }
        List<Map<String, Object>> ordered = new ArrayList<>(section.rows());
        if (table.equals("fu_plan_node")) {
            ordered.sort(Comparator.comparing(row -> asLong(row.get("parent_id")) != null));
        }
        int index = 0;
        for (Map<String, Object> original : ordered) {
            index++;
            Map<String, Object> row = new LinkedHashMap<>();
            columns.forEach(column -> row.put(column, original.get(column)));
            Long sourceId = asLong(row.get("id"));
            if (table.equals("fu_plan_node")
                    && retainedPlans.contains(asLong(row.get("plan_id")))) {
                skipped++;
                changes.add(new Change(table, index, "KEEP", List.of(), "保留已有计划节点及 ID"));
                continue;
            }
            if (ConfigTemplateImportService.prepareRow(row, spec, ids) != null
                    || (spec.menuRangeFilter()
                            && !assignable.contains(asLong(row.get("menu_id"))))) {
                dropped++;
                changes.add(new Change(table, index, "DROP", List.of(), "引用不可映射或超出可分配范围"));
                continue;
            }
            Map<String, Object> existing;
            if (table.equals("fu_plan")) {
                existing = matchingPlan(original, target.rows());
                if (existing != null) {
                    ids.computeIfAbsent(table, t -> new LinkedHashMap<>())
                            .put(sourceId, asLong(existing.get("id")));
                    retainedPlans.add(sourceId);
                    skipped++;
                    changes.add(new Change(table, index, "KEEP", List.of(), "已有相同内容版本，保留版本及节点"));
                    continue;
                }
                // 初始化仍按源版本导入；在用更新绝不覆盖任何既有计划版本（包括草稿）。
                boolean collision =
                        target.rows().stream().anyMatch(r -> sameKey(r, row, spec.keyColumns()));
                if (!initialization || collision) {
                    int max =
                            target.rows().stream()
                                    .filter(r -> Objects.equals(r.get("code"), row.get("code")))
                                    .mapToInt(r -> asLong(r.get("version")).intValue())
                                    .max()
                                    .orElse(0);
                    row.put("version", Math.max(max + 1, asLong(row.get("version")).intValue()));
                    row.put("status", "UNPUBLISHED");
                    row.put("published_at", null);
                    row.put("published_by", null);
                    row.put("lock_version", 0);
                }
                existing = null;
            } else if (spec.mode() == ConfigImportMode.REPLACE_ROWS) {
                existing = null; // 新版本的新节点；已有版本已经在上方整体保留，永不 DELETE。
            } else {
                existing =
                        target.rows().stream()
                                .filter(r -> sameKey(r, row, spec.keyColumns()))
                                .findFirst()
                                .orElse(null);
            }
            if (table.equals("fe_form_version_field")
                    && existing == null
                    && asLong(row.get("version_id")) != null
                    && asLong(row.get("version_id")) > 0) {
                changes.add(
                        new Change(
                                table,
                                index,
                                "CONFLICT",
                                List.of("field_key"),
                                "不能向已有发布快照追加字段，请发布新版本"));
                continue;
            }
            List<String> changed = new ArrayList<>();
            if (existing != null) {
                for (String column : columns) {
                    if (!column.equals("id")
                            && !ConfigImportSpecs.USER_REFERENCE_COLUMNS.contains(column)
                            && !column.equals("created_at")
                            && !column.equals("updated_at")
                            && !column.equals("published_at")
                            && !equivalent(existing.get(column), row.get(column)))
                        changed.add(column);
                }
            }
            Long id =
                    existing == null
                            ? (spec.mode() == ConfigImportMode.SINGLE_ROW
                                    ? sourceId
                                    : Long.valueOf(nextId--))
                            : asLong(existing.get("id"));
            if (sourceId != null && id != null)
                ids.computeIfAbsent(table, t -> new LinkedHashMap<>()).put(sourceId, id);
            if (existing != null && changed.isEmpty()) {
                skipped++;
                changes.add(new Change(table, index, "KEEP", List.of(), "内容相同，保留目标行"));
                continue;
            }
            // 已发布表单快照也不可通过导入改写，须在源端发布新版本再导入。
            if (existing != null
                    && (table.equals("fe_form_version") || table.equals("fe_form_version_field"))) {
                changes.add(
                        new Change(table, index, "CONFLICT", changed, "已有表单版本快照不同，请发布新版本后重新导出"));
                continue;
            }
            row.remove("id");
            if (existing != null) {
                Map<String, Object> update = new LinkedHashMap<>();
                for (String column : changed) update.put(column, row.get(column));
                // 用户引用不复制，不将已有目标操作人的身份清空。
                writes.add(new Write(table, "UPDATE", update, id));
                existing.putAll(update);
                merged++;
            } else {
                writes.add(new Write(table, "INSERT", new LinkedHashMap<>(row), id));
                Map<String, Object> added = new LinkedHashMap<>(row);
                if (id != null) added.put("id", id);
                target.rows().add(added);
                inserted++;
            }
            changes.add(
                    new Change(
                            table,
                            index,
                            existing == null ? "INSERT" : "UPDATE",
                            existing == null ? List.copyOf(row.keySet()) : changed,
                            table.equals("fu_plan") ? "导入为新版本；在用更新须另行审核发布" : "仅列出字段名，不返回配置值"));
        }
        tables.add(
                new ConfigTemplateImportService.TableResult(
                        table,
                        entry.mode().name(),
                        true,
                        section.rows().size(),
                        inserted,
                        merged,
                        skipped,
                        dropped,
                        "真实目标对账；保留未出现在包内的配置"));
    }

    private Map<String, Object> matchingPlan(
            Map<String, Object> sourcePlan, List<Map<String, Object>> candidates) {
        for (var candidate : candidates) {
            if (!Objects.equals(sourcePlan.get("code"), candidate.get("code"))) continue;
            if (initialization && !equivalent(sourcePlan.get("version"), candidate.get("version")))
                continue;
            if (!planBody(sourcePlan).equals(planBody(candidate))) continue;
            List<Map<String, Object>> sourceNodes =
                    source.tables().stream()
                            .filter(s -> s.table().equals("fu_plan_node"))
                            .flatMap(s -> s.rows().stream())
                            .filter(
                                    r ->
                                            Objects.equals(
                                                    asLong(r.get("plan_id")),
                                                    asLong(sourcePlan.get("id"))))
                            .toList();
            Target nodes = targets.get("fu_plan_node");
            List<Map<String, Object>> targetNodes =
                    nodes == null
                            ? List.of()
                            : nodes.rows().stream()
                                    .filter(
                                            r ->
                                                    Objects.equals(
                                                            asLong(r.get("plan_id")),
                                                            asLong(candidate.get("id"))))
                                    .toList();
            if (nodeBodies(sourceNodes).equals(nodeBodies(targetNodes))) return candidate;
        }
        return null;
    }

    private static String planBody(Map<String, Object> row) {
        Map<String, Object> body = new TreeMap<>(row);
        for (String column :
                List.of(
                        "id",
                        "version",
                        "status",
                        "lock_version",
                        "published_at",
                        "published_by",
                        "created_by",
                        "updated_by",
                        "created_at",
                        "updated_at")) body.remove(column);
        return canonical(body);
    }

    private static List<String> nodeBodies(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> nodeBody(row, rows, new java.util.HashSet<>()))
                .sorted()
                .toList();
    }

    private static String nodeBody(
            Map<String, Object> row, List<Map<String, Object>> rows, Set<Long> path) {
        Long id = asLong(row.get("id"));
        if (id != null && !path.add(id))
            throw new com.crdp.framework.web.ApiException(409, 40901, "计划节点存在循环引用");
        Map<String, Object> body = new TreeMap<>(row);
        for (String column : List.of("id", "plan_id", "parent_id", "created_at"))
            body.remove(column);
        Long parent = asLong(row.get("parent_id"));
        if (parent != null) {
            var parentRow =
                    rows.stream()
                            .filter(r -> parent.equals(asLong(r.get("id"))))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new com.crdp.framework.web.ApiException(
                                                    409, 40901, "计划节点父引用缺失"));
            body.put("parent", nodeBody(parentRow, rows, path));
        }
        return canonical(body);
    }

    private static boolean sameKey(
            Map<String, Object> left, Map<String, Object> right, List<String> columns) {
        return columns.stream().allMatch(c -> equivalent(left.get(c), right.get(c)));
    }

    private static boolean equivalent(Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (left instanceof Number
                || left instanceof Boolean
                || right instanceof Number
                || right instanceof Boolean) {
            try {
                String l = left instanceof Boolean b ? (b ? "1" : "0") : left.toString();
                String r = right instanceof Boolean b ? (b ? "1" : "0") : right.toString();
                return new java.math.BigDecimal(l).compareTo(new java.math.BigDecimal(r)) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return canonical(left).equals(canonical(right));
    }

    static String canonical(Object value) {
        try {
            return new ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS,
                            true)
                    .writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法计算配置差异摘要", ex);
        }
    }
}
