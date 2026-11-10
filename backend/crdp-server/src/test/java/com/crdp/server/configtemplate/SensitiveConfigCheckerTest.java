package com.crdp.server.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E12-T03 / A-FLD-014：配置包敏感信息检查器（验收「含敏感信息的包被拦截」）。
 *
 * <p>五条规则：患者 / 业务数据表、未登记表、STRUCTURE_ONLY 带取值、被排除规则剔除的行（术者取值）、 疑似患者敏感值（手机号 / 证件号）。规则同时用于
 * 导出侧自检（导出前）与导入侧拦截（应用前）。
 */
class SensitiveConfigCheckerTest {

    @Test
    void cleanPackageHasNoViolations() {
        ConfigPackage pkg =
                packageOf(
                        section(
                                "cfg_dict",
                                List.of("id", "code", "updated_by"),
                                List.of(row(Map.of("id", 1L, "code", "patient_type")))),
                        absentSection("fe_field"));
        assertThat(SensitiveConfigChecker.check(pkg)).isEmpty();
    }

    @Test
    void patientDataTableIsViolation() {
        ConfigPackage pkg =
                packageOf(
                        section(
                                "pt_patient",
                                List.of("id", "mrn_cipher"),
                                List.of(row(Map.of("id", 1L, "mrn_cipher", "cipher（示例）")))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("FORBIDDEN_TABLE");
        assertThat(violations.get(0).table()).isEqualTo("pt_patient");
    }

    @Test
    void unregisteredTableIsViolation() {
        // 未在注册表标注的表一律不导不进（A-FLD-014）：包来自更新 / 篡改过的代码版本也要拦
        ConfigPackage pkg =
                packageOf(section("rpt_report_type", List.of("id"), List.of(row(Map.of()))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("UNREGISTERED_TABLE");
        assertThat(violations.get(0).table()).isEqualTo("rpt_report_type");
    }

    @Test
    void explicitlyExcludedTablesAreTreatedAsSensitive() {
        // sys_org / sys_dept / cfg_brand / sys_webhook（含 secret_token 凭据）被显式排除（E12-T03），
        // 出现在包内与患者表同样拦截
        ConfigPackage pkg =
                packageOf(section("sys_webhook", List.of("id"), List.of(row(Map.of()))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("FORBIDDEN_TABLE");
        assertThat(violations.get(0).table()).isEqualTo("sys_webhook");
    }

    @Test
    void structureOnlyTableMustNotCarryRows() {
        ConfigPackage pkg =
                packageOf(
                        structureSection(
                                "ward",
                                List.of("id", "code"),
                                List.of(row(Map.of("id", 5L, "code", "ward-1")))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("STRUCTURE_ROWS");
    }

    @Test
    void excludedSurgeonRowIsViolation() {
        // 术者「只复制结构」：cfg_dict_item 里 dict_code='surgeon' 的取值行不得出现在包内
        ConfigPackage pkg =
                packageOf(
                        section(
                                "cfg_dict_item",
                                List.of("id", "dict_code", "item_code"),
                                List.of(
                                        row(
                                                Map.of(
                                                        "id",
                                                        1L,
                                                        "dict_code",
                                                        "surgeon",
                                                        "item_code",
                                                        "s1")),
                                        row(
                                                Map.of(
                                                        "id",
                                                        2L,
                                                        "dict_code",
                                                        "patient_type",
                                                        "item_code",
                                                        "p1")))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("EXCLUDED_ROW");
        assertThat(violations.get(0).table()).isEqualTo("cfg_dict_item");
        assertThat(violations.get(0).hits()).isEqualTo(1L);
    }

    @Test
    void mobileNumberInAnyColumnIsViolation() {
        ConfigPackage pkg =
                packageOf(
                        section(
                                "ntf_rule",
                                List.of("id", "event_type", "remark"),
                                List.of(
                                        row(
                                                Map.of(
                                                        "id",
                                                        1L,
                                                        "event_type",
                                                        "TASK_OVERDUE",
                                                        "remark",
                                                        "联系 13812345678（示例）")))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("SENSITIVE_VALUE");
        assertThat(violations.get(0).hits()).isEqualTo(1L);
    }

    @Test
    void idCardNumberIsViolation() {
        // 11010519491231002X 是公开示例证件号（校验位正确）
        ConfigPackage pkg =
                packageOf(
                        section(
                                "fe_field",
                                List.of("id", "default_value"),
                                List.of(
                                        row(
                                                Map.of(
                                                        "id",
                                                        1L,
                                                        "default_value",
                                                        "证件 11010519491231002X（示例）")))));
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("SENSITIVE_VALUE");
    }

    @Test
    void digitStringsThatAreNotSensitivePass() {
        // 长编号、偏移天数、版本号、校验正则（含 \d 转义）都不是手机号 / 证件号
        ConfigPackage pkg =
                packageOf(
                        section(
                                "fe_field",
                                List.of("id", "code", "unit", "pattern", "span"),
                                List.of(
                                        row(
                                                Map.of(
                                                        "id",
                                                        1L,
                                                        "code",
                                                        "ITCFG-2026-0001",
                                                        "unit",
                                                        "次/分",
                                                        "pattern",
                                                        "^1[3-9]\\d{9}$",
                                                        "span",
                                                        "0-300")))));
        assertThat(SensitiveConfigChecker.check(pkg)).isEmpty();
    }

    @Test
    void idCardWithBrokenChecksumPasses() {
        // 形似证件号但校验位错误：不按敏感值拦（降低假阳性）。值为虚构演示号（AGENTS.md §5.5）
        ConfigPackage pkg =
                packageOf(
                        section(
                                "fe_field",
                                List.of("id", "default_value"),
                                List.of(
                                        row(
                                                Map.of(
                                                        "id",
                                                        1L,
                                                        "default_value",
                                                        "110105194912310021")))));
        assertThat(SensitiveConfigChecker.check(pkg)).isEmpty();
    }

    @Test
    void summaryListsRulesAndTables() {
        ConfigPackage pkg =
                packageOf(section("pt_patient", List.of("id"), List.of(row(Map.of("id", 1L)))));
        String summary = SensitiveConfigChecker.summarize(SensitiveConfigChecker.check(pkg));
        assertThat(summary).contains("pt_patient").contains("FORBIDDEN_TABLE");
    }

    // ---------------------------------------------------------------- 构造

    private static ConfigPackage packageOf(ConfigPackage.TableSection... tables) {
        return new ConfigPackage(
                ConfigTemplateExportService.FORMAT_VERSION,
                "demoa",
                "医院 A（示例）",
                "2026-11-06T00:00:00+08:00",
                List.of(tables));
    }

    private static ConfigPackage.TableSection section(
            String table, List<String> columns, List<Map<String, Object>> rows) {
        return new ConfigPackage.TableSection(
                table, "base", "COPY", "IT", true, rows.size(), 0, rows.size(), columns, rows);
    }

    private static ConfigPackage.TableSection structureSection(
            String table, List<String> columns, List<Map<String, Object>> rows) {
        return new ConfigPackage.TableSection(
                table,
                "followup",
                "STRUCTURE_ONLY",
                "IT",
                true,
                rows.size(),
                0,
                rows.size(),
                columns,
                rows);
    }

    private static ConfigPackage.TableSection absentSection(String table) {
        return new ConfigPackage.TableSection(
                table, "followup", "COPY", "IT", false, 0, 0, 0, List.of(), List.of());
    }

    private static Map<String, Object> row(Map<String, Object> values) {
        return new LinkedHashMap<>(values);
    }
}
