package com.crdp.server.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigImportPlanTest {
    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static ConfigPackage.TableSection section(
            String table, List<Map<String, Object>> rows) {
        return new ConfigPackage.TableSection(
                table,
                "followup",
                "COPY",
                "配置（示例）",
                true,
                rows.size(),
                0,
                rows.size(),
                rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet()),
                rows);
    }

    private static ConfigPackage pkg(ConfigPackage.TableSection... sections) {
        return new ConfigPackage(
                1, "demoa", "医院（示例）", "2026-11-18T00:00:00+08:00", List.of(sections));
    }

    private static ConfigImportPlan.Target target(List<Map<String, Object>> rows) {
        return new ConfigImportPlan.Target(List.copyOf(rows.getFirst().keySet()), rows);
    }

    @Test
    void changedPlanCreatesUnpublishedVersionAndNeverDeletesOrChangesExistingNodes() {
        var sourcePlan =
                row(
                        "id",
                        1L,
                        "code",
                        "PLAN_EXAMPLE",
                        "version",
                        1,
                        "status",
                        "PUBLISHED",
                        "name",
                        "计划（示例）");
        var targetPlan =
                row(
                        "id",
                        80L,
                        "code",
                        "PLAN_EXAMPLE",
                        "version",
                        4,
                        "status",
                        "PUBLISHED",
                        "name",
                        "计划（示例）");
        var sourceNode = row("id", 2L, "plan_id", 1L, "parent_id", null, "name", "新节点（示例）");
        var targetNode = row("id", 90L, "plan_id", 80L, "parent_id", null, "name", "旧节点（示例）");
        var source =
                pkg(
                        section("fu_plan_node", List.of(sourceNode)),
                        section("fu_plan", List.of(sourcePlan)));
        var snapshot =
                Map.of(
                        "fu_plan",
                        target(List.of(targetPlan)),
                        "fu_plan_node",
                        target(List.of(targetNode)));
        var before = ConfigImportPlan.canonical(snapshot);
        var plan = new ConfigImportPlan(source, snapshot, Set.of(), false);
        assertThat(plan.applicable()).isTrue();
        assertThat(plan.writes)
                .hasSize(2)
                .allSatisfy(write -> assertThat(write.action()).isEqualTo("INSERT"));
        assertThat(plan.writes.getFirst().values())
                .containsEntry("version", 5)
                .containsEntry("status", "UNPUBLISHED");
        assertThat(plan.writes.get(1).values())
                .containsEntry("plan_id", plan.writes.getFirst().id());
        assertThat(ConfigImportPlan.canonical(snapshot)).isEqualTo(before);
        assertThat(sourcePlan).containsEntry("version", 1).containsEntry("status", "PUBLISHED");
    }

    @Test
    void matchingPlanUsesExistingVersionAndNodeIdsDespiteDifferentSourceIdsAndVersion() {
        var sourcePlan = row("id", 1L, "code", "PLAN_EXAMPLE", "version", 1, "name", "计划（示例）");
        var targetPlan = row("id", 80L, "code", "PLAN_EXAMPLE", "version", 4, "name", "计划（示例）");
        var sourceNodes =
                List.of(
                        row("id", 2L, "plan_id", 1L, "parent_id", null, "name", "父（示例）"),
                        row("id", 3L, "plan_id", 1L, "parent_id", 2L, "name", "子（示例）"));
        var targetNodes =
                List.of(
                        row("id", 90L, "plan_id", 80L, "parent_id", null, "name", "父（示例）"),
                        row("id", 91L, "plan_id", 80L, "parent_id", 90L, "name", "子（示例）"));
        var plan =
                new ConfigImportPlan(
                        pkg(
                                section("fu_plan", List.of(sourcePlan)),
                                section("fu_plan_node", sourceNodes)),
                        Map.of(
                                "fu_plan",
                                target(List.of(targetPlan)),
                                "fu_plan_node",
                                target(targetNodes)),
                        Set.of(),
                        false);
        assertThat(plan.writes).isEmpty();
        assertThat(plan.changes)
                .hasSize(3)
                .allSatisfy(c -> assertThat(c.action()).isEqualTo("KEEP"));
    }

    @Test
    void dryRunComputesUpdatesAndDropsWithoutMutatingInputOrExposingValues() {
        var sourceRow = row("id", 1L, "code", "EXAMPLE", "name", "新配置（示例）");
        var oldRow = row("id", 7L, "code", "EXAMPLE", "name", "原配置（示例）");
        var role = row("id", 8L, "code", "EXAMPLE_ROLE", "name", "角色（示例）");
        var link = row("role_id", 8L, "menu_id", 999L);
        var source =
                pkg(
                        section("cfg_dict", List.of(sourceRow)),
                        section("sys_role", List.of(role)),
                        section("sys_role_menu", List.of(link)));
        var targets =
                Map.of(
                        "cfg_dict",
                        target(List.of(oldRow)),
                        "sys_role",
                        new ConfigImportPlan.Target(List.copyOf(role.keySet()), List.of()),
                        "sys_role_menu",
                        new ConfigImportPlan.Target(List.of("role_id", "menu_id"), List.of()));
        var plan = new ConfigImportPlan(source, targets, Set.of(), false);
        assertThat(plan.tables.getFirst().merged()).isEqualTo(1);
        assertThat(plan.changes).anySatisfy(c -> assertThat(c.action()).isEqualTo("DROP"));
        assertThat(ConfigImportPlan.canonical(plan.changes)).doesNotContain("新配置", "原配置");
        assertThat(oldRow).containsEntry("name", "原配置（示例）");
        assertThat(sourceRow).containsEntry("id", 1L);
    }

    @Test
    void existingFormSnapshotDifferenceBlocksWholePlan() {
        var source = row("id", 1L, "form_code", "EXAMPLE", "version", 1, "categories_json", "[]");
        var old = row("id", 9L, "form_code", "EXAMPLE", "version", 1, "categories_json", "[{}]");
        var plan =
                new ConfigImportPlan(
                        pkg(section("fe_form_version", List.of(source))),
                        Map.of("fe_form_version", target(List.of(old))),
                        Set.of(),
                        false);
        assertThat(plan.applicable()).isFalse();
        assertThat(plan.writes).isEmpty();
        assertThat(plan.changes.getFirst().columns()).containsExactly("categories_json");
    }
}
