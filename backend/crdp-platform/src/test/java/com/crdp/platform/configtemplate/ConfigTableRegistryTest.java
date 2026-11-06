package com.crdp.platform.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E12-T01 / A-FLD-014：配置表注册表。导出包只含注册表里显式标注的配置表（tech-plan §6「配置表在注册表中显式标注，
 * 未标注的表一律不导出」），验收「导出包只含配置，不含患者数据」的第一道闸门在这里： 患者与业务数据表登记即初始化失败。 E12-T03：行排除谓词结构化（导出 SQL 与敏感检查共用）+
 * 组织架构 / 品牌 / Webhook 凭据显式排除。
 */
class ConfigTableRegistryTest {

    @Test
    void tableNamesAreUniqueAndWellFormed() {
        List<String> tables = ConfigTableRegistry.tables();
        assertThat(tables).doesNotHaveDuplicates();
        assertThat(new HashSet<>(tables)).hasSize(tables.size());
        assertThat(tables).allMatch(t -> t.matches("[a-z][a-z0-9_]*"));
    }

    @Test
    void neverExportCoversPatientAndBusinessDataTables() {
        // 患者、随访业务数据、账号、审计、作业、文件、日志、密钥、outbox——A-FLD-014「不复制」清单 + 仓库既有数据表
        assertThat(ConfigTableRegistry.NEVER_EXPORT_TABLES)
                .contains(
                        "pt_patient",
                        "pt_baseline_section",
                        "pt_contact",
                        "pt_status_history",
                        "pt_patient_summary",
                        "pt_ward_history",
                        "fu_task",
                        "fu_record",
                        "fu_enrollment",
                        "fu_contact_log",
                        "fu_contact_audio",
                        "fu_saved_filter",
                        "fe_record",
                        "fe_field_history",
                        "sys_user",
                        "sys_user_role",
                        "sys_file",
                        "ntf_message",
                        "ntf_send_log",
                        "ntf_user_pref",
                        "aud_log",
                        "job_export",
                        "crypto_data_key",
                        "sys_outbox");
    }

    @Test
    void noNeverExportTableIsRegistered() {
        Set<String> registered = Set.copyOf(ConfigTableRegistry.tables());
        for (String forbidden : ConfigTableRegistry.NEVER_EXPORT_TABLES) {
            assertThat(registered).doesNotContain(forbidden);
        }
    }

    @Test
    void registeringForbiddenTableFailsFast() {
        ConfigTableEntry patient =
                new ConfigTableEntry(
                        "pt_patient", "followup", ConfigCopyMode.COPY, "患者", "不应登记", null);
        assertThatThrownBy(() -> ConfigTableRegistry.validate(List.of(patient)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pt_patient");

        ConfigTableEntry account =
                new ConfigTableEntry("sys_user", "base", ConfigCopyMode.COPY, "账号", "不应登记", null);
        assertThatCode(() -> ConfigTableRegistry.validate(List.of(account)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSpecCopyCategoriesAreRegistered() {
        // A-FLD-014「复制」清单里、当前租户库已存在的表都要在注册表：字段字典与表单、患者类型字典、
        // 参数（统计口径）、角色与授权、通知设置、计划模板、功能开关
        assertThat(ConfigTableRegistry.tables())
                .contains(
                        "fe_field",
                        "fe_option_set",
                        "fe_option",
                        "fe_form",
                        "fe_form_version",
                        "fe_form_version_field",
                        "fe_rule",
                        "cfg_dict",
                        "cfg_dict_item",
                        "cfg_param",
                        "cfg_feature",
                        "sys_role",
                        "sys_role_menu",
                        "sys_role_scope",
                        "sys_role_scope_dept",
                        "sys_role_field_policy",
                        "ntf_rule",
                        "ntf_setting",
                        "fu_plan",
                        "fu_plan_node");
    }

    @Test
    void wardIsStructureOnly() {
        // A-FLD-014「只复制结构」：术者、病区 / 中心字典保留定义、不带取值。病区字典是 ward 表。
        ConfigTableEntry ward = ConfigTableRegistry.entry("ward");
        assertThat(ward.mode()).isEqualTo(ConfigCopyMode.STRUCTURE_ONLY);
    }

    @Test
    void surgeonDictValuesAreExcludedFromItems() {
        // 术者取值不走 STRUCTURE_ONLY 表（术者是 cfg_dict_item 里 dict_code='surgeon' 的行），
        // 用结构化行排除谓词表达「只复制结构、不复制取值」——导出 SQL 与敏感信息检查共用同一份定义。
        ConfigTableEntry items = ConfigTableRegistry.entry("cfg_dict_item");
        assertThat(items.excludeWhen()).containsEntry("dict_code", "surgeon");
        // 包行命中谓词（术者取值出现在包内 → 敏感违规）
        assertThat(items.rowExcluded(Map.of("dict_code", "surgeon", "item_code", "s1"))).isTrue();
        assertThat(items.rowExcluded(Map.of("dict_code", "patient_type", "item_code", "p1")))
                .isFalse();
        assertThat(items.rowExcluded(Map.of())).isFalse();
    }

    @Test
    void excludePredicateOnlyAppearsOnCopyEntries() {
        for (ConfigTableEntry entry : ConfigTableRegistry.ENTRIES) {
            if (entry.excludeWhen() != null && !entry.excludeWhen().isEmpty()) {
                assertThat(entry.mode())
                        .as("表 %s 的行排除谓词只应出现在 COPY 条目上", entry.table())
                        .isEqualTo(ConfigCopyMode.COPY);
            }
        }
    }

    @Test
    void nonCopyTablesAreExplicitlyExcluded() {
        // E12-T03 包含 / 排除规则定稿：组织架构（sys_org / sys_dept 不在 A-FLD-014 复制清单）、
        // 租户品牌（cfg_brand 属新租户自身信息）、Webhook 凭据（sys_webhook 含 secret_token）不随模板复制。
        assertThat(ConfigTableRegistry.NEVER_EXPORT_TABLES)
                .contains("sys_org", "sys_dept", "cfg_brand", "sys_webhook");
    }
}
