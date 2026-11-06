package com.crdp.platform.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E12-T02 / A-FLD-014：导入规格全集自检。核心口径：注册表全部 COPY 条目都有导入规格（缺一即类加载失败）、 数值引用都指向注册表内的表（患者数据表永不被指向）、
 * fu_plan_node 用整组替换防重放重复、sys_role_menu 走可分配范围交集。
 */
class ConfigImportSpecsTest {

    @Test
    void coversEveryCopyEntryOfRegistry() {
        List<String> copyTables =
                ConfigTableRegistry.ENTRIES.stream()
                        .filter(e -> e.mode() == ConfigCopyMode.COPY)
                        .map(ConfigTableEntry::table)
                        .toList();
        assertThat(ConfigImportSpecs.SPECS)
                .extracting(ConfigImportSpec::table)
                .containsExactlyElementsOf(copyTables);
    }

    @Test
    void structureOnlyTablesHaveNoSpec() {
        // ward（病区 / 中心）只复制结构，导入侧不落行
        assertThat(ConfigImportSpecs.of("ward")).isNull();
    }

    @Test
    void numericReferencesPointToRegisteredTablesOnly() {
        for (ConfigImportSpec spec : ConfigImportSpecs.SPECS) {
            for (Map.Entry<String, String> ref : spec.references().entrySet()) {
                assertThat(ConfigTableRegistry.entry(ref.getValue()))
                        .as("%s.%s -> %s", spec.table(), ref.getKey(), ref.getValue())
                        .isNotNull();
                assertThat(ConfigTableRegistry.NEVER_EXPORT_TABLES)
                        .as("%s.%s 不得指向患者 / 业务数据表", spec.table(), ref.getKey())
                        .doesNotContain(ref.getValue());
            }
        }
    }

    @Test
    void idRemappingReferencesAreDeclared() {
        // 验收「导入后引用关系正确」的四个数值重映射点 + 自引用
        assertThat(ConfigImportSpecs.of("sys_role_menu").references())
                .containsEntry("role_id", "sys_role");
        assertThat(ConfigImportSpecs.of("sys_role_scope").references())
                .containsEntry("role_id", "sys_role");
        assertThat(ConfigImportSpecs.of("sys_role_field_policy").references())
                .containsEntry("role_id", "sys_role");
        assertThat(ConfigImportSpecs.of("fe_form_version_field").references())
                .containsEntry("version_id", "fe_form_version");
        assertThat(ConfigImportSpecs.of("fu_plan_node").references())
                .containsEntry("plan_id", "fu_plan")
                .containsEntry("parent_id", "fu_plan_node");
    }

    @Test
    void roleMenuGrantsAreRangeFilteredAndDeptScopesDropped() {
        assertThat(ConfigImportSpecs.of("sys_role_menu").menuRangeFilter()).isTrue();
        assertThat(ConfigImportSpecs.of("sys_role_menu").mode())
                .isEqualTo(ConfigImportMode.LINK_ROWS);
        // sys_dept 不随模板复制（OQ-90 ②），科室范围行无法重映射，整表丢弃
        assertThat(ConfigImportSpecs.of("sys_role_scope_dept").mode())
                .isEqualTo(ConfigImportMode.DROP_ROWS);
    }

    @Test
    void planNodesReplaceByParentAndSettingIsSingleRow() {
        // fu_plan_node 无自然键：按 plan_id 整组替换（重放幂等，不叠加重复节点）
        assertThat(ConfigImportSpecs.of("fu_plan_node").mode())
                .isEqualTo(ConfigImportMode.REPLACE_ROWS);
        assertThat(ConfigImportSpecs.of("ntf_setting").mode())
                .isEqualTo(ConfigImportMode.SINGLE_ROW);
    }

    @Test
    void userReferenceColumnsCoverAuditTrailWriters() {
        assertThat(ConfigImportSpecs.USER_REFERENCE_COLUMNS)
                .containsExactlyInAnyOrder("created_by", "updated_by", "published_by");
    }

    @Test
    void malformedSpecsFailFast() {
        // 引用患者数据表 / 未登记的表 → 拒绝
        List<ConfigImportSpec> badReference = new ArrayList<>(ConfigImportSpecs.SPECS);
        badReference.removeIf(s -> s.table().equals("cfg_param"));
        badReference.add(
                ConfigImportSpec.copy(
                        "cfg_param", List.of("param_key"), Map.of("updated_by", "pt_patient")));
        assertThatThrownBy(() -> ConfigImportSpecs.validate(badReference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pt_patient");
        // 覆盖不全（丢一个 COPY 条目）→ 拒绝
        List<ConfigImportSpec> missing = new ArrayList<>(ConfigImportSpecs.SPECS);
        missing.removeIf(s -> s.table().equals("cfg_param"));
        assertThatThrownBy(() -> ConfigImportSpecs.validate(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cfg_param");
        // 自然键缺失 → 拒绝
        List<ConfigImportSpec> noKey = new ArrayList<>(ConfigImportSpecs.SPECS);
        noKey.removeIf(s -> s.table().equals("cfg_dict"));
        noKey.add(ConfigImportSpec.copy("cfg_dict", List.of()));
        assertThatThrownBy(() -> ConfigImportSpecs.validate(noKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cfg_dict");
        // 全量常量本身自洽
        assertThatCode(() -> ConfigImportSpecs.validate(ConfigImportSpecs.SPECS))
                .doesNotThrowAnyException();
    }
}
