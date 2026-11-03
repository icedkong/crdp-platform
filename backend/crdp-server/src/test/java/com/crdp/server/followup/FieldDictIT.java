package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E11-T01 端到端 / A-FLD-001、A-FLD-002、C-FLD-001（完整应用 + 真实 MySQL，HTTP 直连）。验收原文： 显示名与编码分离，字段可启停。
 *
 * <p>覆盖：字段清单按表单 / 分类过滤，视图同时带稳定键、字段编码与显示名，选项「编码 + 显示名」分离（吸烟史 0=否 / 1=是）；维护显示名 / 帮助 / 软范围 / 必勤与选项（新增
 * / 改显示名 / 停用，编码不可改不删除）； 字段停用 / 启用（状态 + 审计）；键、编码、类型、硬范围、敏感标记不可改（请求不携带）； 无权限 40300、 模块关闭 40301、校验失败
 * 40001、字段 / 表单不存在 40400、双租户字典隔离。E11-T08 / A-FLD-009、C-FLD-009：每次维护写 fe_field_dict_log
 * 变更记录（前后值、操作人、时间），GET /fields/{key}/logs 可查。E11-T09 /
 * A-FLD-013、C-FLD-008：手术年龄派生（AGE_YEARS(f267,f001)）与 Excel 批量导入（模板、新增 / 更新 / 单行失败、条件 DSL、权限 / 模块开关 /
 * 双租户隔离）。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldDictIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String FIELDS = "/api/hospital/form/fields";
    private static final String FORMS = "/api/hospital/form/forms";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 恢复被用例动过的字段与选项（真实字典形态，E23-T01 / V38）
                        jdbc.update(
                                "UPDATE fe_field SET label = '吸烟史',"
                                        + " help_text = '原表列名「吸烟史_否_0_是_1」',"
                                        + " required = 0, status = 'ENABLED', updated_by = NULL"
                                        + " WHERE form_code = 'A_BASELINE' AND field_key = 'f019'");
                        jdbc.update(
                                "UPDATE fe_field SET label = '身高cm',"
                                        + " help_text = '原表列名「身高_cm」',"
                                        + " soft_range = '{\"min\":130,\"max\":210}',"
                                        + " updated_by = NULL"
                                        + " WHERE form_code = 'A_BASELINE' AND field_key = 'f013'");
                        jdbc.update(
                                "DELETE FROM fe_option WHERE set_code = 'a_yesno'"
                                        + " AND option_code > 1");
                        jdbc.update(
                                "UPDATE fe_option SET label = CASE option_code WHEN 0 THEN '否'"
                                        + " WHEN 1 THEN '是' END, sort_no = option_code * 10 + 10,"
                                        + " status = 'ENABLED', updated_by = NULL"
                                        + " WHERE set_code = 'a_yesno'");
                        // E11-T09 导入用例的痕迹：导入的字段 / 选项集 / 选项 / 变更记录 / 审计
                        jdbc.update(
                                "DELETE FROM fe_field WHERE form_code = 'A_BASELINE'"
                                        + " AND field_key LIKE 'f8%'");
                        jdbc.update("DELETE FROM fe_option WHERE set_code LIKE 'it\\_%'");
                        jdbc.update("DELETE FROM fe_option_set WHERE code LIKE 'it\\_%'");
                        jdbc.update("DELETE FROM fe_field_dict_log");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action = 'FIELD_DICT_UPDATE'"
                                        + " OR action = 'FIELD_DICT_IMPORT'");
                    });
        }
    }

    // ---------------------------------------------------------------- 验收：显示名与编码分离

    /** 清单带 key / code / label 三套标识，选项是「编码 + 显示名」；分类过滤生效。 */
    @Test
    void listsFieldsWithKeyCodeAndLabelSeparated() {
        String token = hospitalToken("demoa");

        // 表单清单（登录即可）：基线与年度随访就位（E23-T01 录入）
        List<Map<?, ?>> forms = itemsOf(get(FORMS, token));
        assertEquals(0, code(get(FORMS, token)));
        assertTrue(
                forms.stream().anyMatch(form -> "A_BASELINE".equals(form.get("code"))),
                "表单清单含基线档案：" + forms);
        assertTrue(
                forms.stream().anyMatch(form -> "A_ANNUAL_FU".equals(form.get("code"))),
                "表单清单含年度随访：" + forms);

        Map<?, ?> list = get(FIELDS + "?formCode=A_BASELINE", token);
        assertEquals(0, code(list));
        List<Map<?, ?>> fields = itemsOf(list);
        assertTrue(fields.size() >= 200, "基线字段齐全（附录 A 分类 1–8 + 出生日期 = 213）：" + fields.size());

        Map<?, ?> smoking =
                fields.stream()
                        .filter(field -> "f019".equals(field.get("key")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("smoking_history", smoking.get("code"), "字段编码（ASCII，导入导出名；原表列名在帮助说明）");
        assertEquals("吸烟史", smoking.get("label"), "显示名（不带“(否=0，是=1)”编码说明）");
        assertEquals("case", smoking.get("categoryKey"));
        assertEquals("病例基本信息", smoking.get("categoryLabel"));
        List<Map<?, ?>> options = (List<Map<?, ?>>) smoking.get("options");
        assertEquals(2, options.size());
        assertEquals(0, ((Number) options.get(0).get("code")).intValue(), "数据库存编码 0");
        assertEquals("否", options.get(0).get("label"), "页面显示显示名");

        // 分类过滤：手术信息只剩手术日期 + 派生字段
        List<Map<?, ?>> surgery =
                itemsOf(get(FIELDS + "?formCode=A_BASELINE&categoryKey=surgery", token));
        assertTrue(surgery.size() >= 2);
        assertTrue(surgery.stream().allMatch(field -> "surgery".equals(field.get("categoryKey"))));

        // 表单不存在 40400
        assertEquals(40400, code(get(FIELDS + "?formCode=NOPE", token)));
    }

    // ---------------------------------------------------------------- 维护与启停

    /** 维护显示名 / 帮助 / 软范围 / 必填：返回视图与库内一致，键与编码原样。 */
    @Test
    void updatesEditableAttributesAndWritesAudit() {
        String token = hospitalToken("demoa");

        Map<?, ?> updated =
                put(
                        FIELDS + "/f019?formCode=A_BASELINE",
                        token,
                        Map.of(
                                "label", "吸烟史（既往）",
                                "helpText", "帮助说明（示例）",
                                "required", false,
                                "status", "ENABLED"));
        assertEquals(0, code(updated));
        assertEquals("吸烟史（既往）", data(updated).get("label"));
        assertEquals("smoking_history", data(updated).get("code"), "编码不可改也不被请求携带");
        assertEquals("f019", data(updated).get("key"));

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "吸烟史（既往）",
                            jdbc.queryForObject(
                                    "SELECT label FROM fe_field WHERE form_code = 'A_BASELINE'"
                                            + " AND field_key = 'f019'",
                                    String.class));
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'FIELD_DICT_UPDATE' AND object_id ="
                                                    + " 'A_BASELINE.f019'",
                                            Integer.class)
                                    >= 1,
                            "字段维护写审计");
                });
    }

    /** 验收「字段可启停」：停用后清单状态 DISABLED、审计点明停用；启用恢复。 */
    @Test
    void disablesAndReenablesField() {
        String token = hospitalToken("demoa");

        Map<?, ?> disabled =
                put(
                        FIELDS + "/f019?formCode=A_BASELINE",
                        token,
                        Map.of("label", "吸烟史", "status", "DISABLED"));
        assertEquals(0, code(disabled));
        assertEquals("DISABLED", data(disabled).get("status"));

        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                "DISABLED",
                                jdbc.queryForObject(
                                        "SELECT status FROM fe_field WHERE form_code ="
                                                + " 'A_BASELINE' AND field_key = 'f019'",
                                        String.class)));

        Map<?, ?> enabled =
                put(
                        FIELDS + "/f019?formCode=A_BASELINE",
                        token,
                        Map.of("label", "吸烟史", "status", "ENABLED"));
        assertEquals("ENABLED", data(enabled).get("status"));
    }

    /** 选项维护：新增「已戒烟」、改「是」显示名、停用「否」；重复编码 40001；非选项字段拒绝。 */
    @Test
    void maintainsOptionsWithoutChangingCodes() {
        String token = hospitalToken("demoa");

        Map<?, ?> updated =
                put(
                        FIELDS + "/f019?formCode=A_BASELINE",
                        token,
                        Map.of(
                                "label", "吸烟史",
                                "status", "ENABLED",
                                "options",
                                        List.of(
                                                Map.of("code", 1, "label", "是（目前吸烟）", "sortNo", 30),
                                                Map.of("code", 2, "label", "已戒烟（示例）", "sortNo", 40),
                                                Map.of(
                                                        "code",
                                                        0,
                                                        "label",
                                                        "否",
                                                        "status",
                                                        "DISABLED"))));
        assertEquals(0, code(updated));
        List<Map<?, ?>> options = (List<Map<?, ?>>) data(updated).get("options");
        assertEquals(3, options.size(), "新增的选项出现");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "是（目前吸烟）",
                            jdbc.queryForObject(
                                    "SELECT label FROM fe_option WHERE set_code = 'a_yesno'"
                                            + " AND option_code = 1",
                                    String.class));
                    assertEquals(
                            "DISABLED",
                            jdbc.queryForObject(
                                    "SELECT status FROM fe_option WHERE set_code = 'a_yesno'"
                                            + " AND option_code = 0",
                                    String.class));
                });

        // 重复编码 40001；非选项型字段（身高 f013）拒绝维护选项
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label", "吸烟史",
                                        "status", "ENABLED",
                                        "options",
                                                List.of(
                                                        Map.of("code", 1, "label", "是"),
                                                        Map.of("code", 1, "label", "否"))))));
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f013?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label", "身高",
                                        "status", "ENABLED",
                                        "options", List.of(Map.of("code", 1, "label", "是"))))));
    }

    // ---------------------------------------------------------------- E11-T08：变更记录

    /** 维护（含停用）写变更记录：倒序、前后值快照、操作人（sys_user 联查）与时间（A-FLD-009）。 */
    @Test
    void updateWritesChangeRecordLogs() {
        String token = hospitalToken("demoa");

        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史（修订）", "status", "ENABLED"))));
        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史（修订）", "status", "DISABLED"))));

        Map<?, ?> logsResponse = get(FIELDS + "/f019/logs?formCode=A_BASELINE", token);
        assertEquals(0, code(logsResponse));
        List<Map<?, ?>> logs = itemsOf(logsResponse);
        assertEquals(2, logs.size(), "两次维护各留一条：" + logs.size());

        Map<?, ?> latest = logs.get(0);
        assertEquals("FIELD_UPDATE", latest.get("action"));
        assertNotNull(latest.get("beforeJson"), "调整前快照");
        assertNotNull(latest.get("afterJson"), "调整后快照");
        assertTrue(((String) latest.get("afterJson")).contains("DISABLED"), "停用进快照");
        assertEquals("演示护士", latest.get("operatorName"), "操作人姓名（联查 sys_user）");
        assertNotNull(latest.get("createdAt"), "操作时间");

        Map<?, ?> first = logs.get(1);
        assertTrue(((String) first.get("afterJson")).contains("吸烟史（修订）"));

        // 未知字段 40400；无权限 40300（前端隐藏不能代替后端校验）
        assertEquals(40400, code(get(FIELDS + "/f999/logs?formCode=A_BASELINE", token)));
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(get(FIELDS + "/f019/logs?formCode=A_BASELINE", doctor)));
    }

    // ---------------------------------------------------------------- 校验、权限、模块开关、隔离

    /** 校验失败 40001（显示名空 / 软范围倒置 / 状态非法）；字段不存在 40400。 */
    @Test
    void rejectsInvalidUpdatesAndUnknownField() {
        String token = hospitalToken("demoa");

        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                token,
                                Map.of("label", " ", "status", "ENABLED"))));
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f013?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label", "身高",
                                        "status", "ENABLED",
                                        "softRange", Map.of("min", 210, "max", 130)))));
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史", "status", "PAUSED"))));
        assertEquals(
                40400,
                code(
                        put(
                                FIELDS + "/f999?formCode=A_BASELINE",
                                token,
                                Map.of("label", "不存在（示例）", "status", "ENABLED"))));
    }

    /** 无 followup:field:edit 的医生 40300（前端隐藏不能代替后端校验）。 */
    @Test
    void rejectsUserWithoutPermission() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(get(FIELDS + "?formCode=A_BASELINE", doctor)));
        assertEquals(
                40300,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                doctor,
                                Map.of("label", "吸烟史", "status", "ENABLED"))));
    }

    /** 模块关闭（40301）：followup 关闭后接口 40301，恢复即用（PLT-MOD-006）。 */
    @Test
    void closedFollowupModuleReturns40301() {
        String token = hospitalToken("demoa");
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, code(get(FIELDS + "?formCode=A_BASELINE", token)));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(get(FIELDS + "?formCode=A_BASELINE", token)));
    }

    /** 双租户隔离：demoa 改显示名，demob 的同键字段不受影响（每院一个库）。 */
    @Test
    void tenantChangesStayIsolated() {
        String tenantA = hospitalToken("demoa");
        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                tenantA,
                                Map.of("label", "吸烟史（A 院）", "status", "ENABLED"))));

        String tenantB = hospitalToken("demob");
        Map<?, ?> tenantBField =
                itemsOf(get(FIELDS + "?formCode=A_BASELINE", tenantB)).stream()
                        .filter(field -> "f019".equals(field.get("key")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("吸烟史", tenantBField.get("label"), "B 院字典不受 A 院修改影响");
        assertNotNull(tenantBField.get("options"));
    }

    // ---------------------------------------------------------------- E11-T09：Excel 批量导入

    /** A-FLD-013 端到端：E23-T01 录入的手术年龄（f005，基本信息分类）带 AGE_YEARS(f267,f001)，出生日期与手术日期就位。 */
    @Test
    void surgeryAgeDerivedFieldWiredToBirthDate() {
        String token = hospitalToken("demoa");

        Map<?, ?> surgeryAge =
                itemsOf(get(FIELDS + "?formCode=A_BASELINE&categoryKey=basic", token)).stream()
                        .filter(field -> "f005".equals(field.get("key")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("手术年龄 f005 应在基本信息分类"));
        assertEquals("surgery_age", surgeryAge.get("code"), "字段编码（ASCII；原表列名「年龄」在帮助说明）");
        assertEquals("手术年龄", surgeryAge.get("label"));
        Map<?, ?> derivedDef = (Map<?, ?>) surgeryAge.get("derivedDef");
        assertEquals("AGE_YEARS", derivedDef.get("fn"));
        assertEquals(List.of("f267", "f001"), derivedDef.get("args"), "手术日期 − 出生日期取周岁");
    }

    /** 模板下载（xlsx、表头与导入逐列一致、示例行带「示例」标记）。 */
    @Test
    void importTemplateDownloadsAsXlsx() {
        String token = hospitalToken("demoa");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<byte[]> response =
                rest.exchange(
                        FIELDS + "/import-template",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        byte[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders().getContentType().toString());
        assertNotNull(response.getHeaders().getContentDisposition().getFilename(), "附件名");

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(response.getBody()));
        assertEquals(FieldImportHeaders.EXPECTED, grid.get(0));
        assertTrue(grid.size() >= 3, "模板带示例行");
        assertTrue(grid.get(1).stream().anyMatch(cell -> cell.contains("示例")), "示例行全部虚构数据");
    }

    /** 导入（验收「产品可用 Excel 批量导入字段定义」）：新增 + 更新 + 单行失败并存，写审计与变更记录。 */
    @Test
    void importsFieldsFromExcel() {
        String token = hospitalToken("demoa");

        Map<?, ?> report =
                importFile(
                        token,
                        SimpleXlsx.write(
                                FieldImportHeaders.EXPECTED,
                                List.of(
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f801",
                                                "字段编码", "it_death_cause",
                                                "显示名", "死亡原因（示例）",
                                                "分类键", "case",
                                                "类型", "textarea",
                                                "必填", "是",
                                                "帮助说明", "示例帮助",
                                                "排序", "1200"),
                                        // C-FLD-008 囊袋检查形态：新选项集 + 需补充说明 + 别名
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f802",
                                                "字段编码", "it_pocket_check",
                                                "显示名", "囊袋/伤口检查（示例）",
                                                "分类键", "surgery",
                                                "类型", "multiselect",
                                                "选项集编码", "it_pocket_status",
                                                "选项", "0=愈合良好（示例）,6=其他（示例）",
                                                "需补充说明选项编码", "6",
                                                "选项别名", "0=愈合;切口愈合",
                                                "排序", "110"),
                                        // 更新：命中真实字典 f019 吸烟史（类型 radio、选项集 a_yesno），改显示名并补选项
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f019",
                                                "字段编码", "smoking_history",
                                                "显示名", "吸烟史（导入修订）",
                                                "分类键", "case",
                                                "类型", "radio",
                                                "必填", "是",
                                                "选项集编码", "a_yesno",
                                                "选项", "0=否,1=是,2=已戒烟（示例）",
                                                "排序", "190"),
                                        // 失败行：类型不可改（f013 身高，库内 number → select）
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f013",
                                                "字段编码", "height_cm",
                                                "显示名", "身高cm",
                                                "分类键", "case",
                                                "类型", "select",
                                                "选项集编码", "a_yesno",
                                                "排序", "130"))));

        assertEquals(4, ((Number) report.get("total")).intValue(), "总行数：" + report);
        assertEquals(2, ((Number) report.get("created")).intValue(), "新增两行：" + report);
        assertEquals(1, ((Number) report.get("updated")).intValue(), "更新 f019：" + report);
        assertEquals(1, ((Number) report.get("failed")).intValue(), "失败一行：" + report);
        List<Map<?, ?>> rows = (List<Map<?, ?>>) report.get("rows");
        assertTrue(
                rows.stream()
                        .anyMatch(
                                item ->
                                        "FAILED".equals(item.get("action"))
                                                && String.valueOf(item.get("message"))
                                                        .contains("不可改")),
                "失败原因点明不可改：" + rows);

        runInTenant(
                "demoa",
                () -> {
                    // 新字段全列落库
                    Map<String, Object> deathCause =
                            jdbc.queryForMap(
                                    "SELECT label, required, sensitive_flag, storage_type, help_text"
                                            + " FROM fe_field WHERE form_code = 'A_BASELINE'"
                                            + " AND field_key = 'f801'");
                    assertEquals("死亡原因（示例）", deathCause.get("label"));
                    assertEquals(1, ((Number) deathCause.get("required")).intValue());
                    assertEquals(0, ((Number) deathCause.get("sensitive_flag")).intValue());
                    assertEquals("示例帮助", deathCause.get("help_text"));
                    // 选项集自动创建，选项带「需补充说明」与别名
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_option_set WHERE code ="
                                                    + " 'it_pocket_status'",
                                            Integer.class)
                                    .intValue(),
                            "新选项集自动创建");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT needs_detail FROM fe_option WHERE set_code ="
                                                    + " 'it_pocket_status' AND option_code = 6",
                                            Integer.class)
                                    .intValue(),
                            "选项 6 需补充说明（选「其他」须填写说明，C-FLD-008 / Q07）");
                    assertEquals(
                            "愈合;切口愈合",
                            jdbc.queryForObject(
                                    "SELECT aliases FROM fe_option WHERE set_code = 'it_pocket_status'"
                                            + " AND option_code = 0",
                                    String.class),
                            "别名 CSV（数据导入表头匹配用）");
                    // 更新：f019 显示名改、补选项 2，原有选项不动
                    assertEquals(
                            "吸烟史（导入修订）",
                            jdbc.queryForObject(
                                    "SELECT label FROM fe_field WHERE form_code = 'A_BASELINE'"
                                            + " AND field_key = 'f019'",
                                    String.class));
                    assertEquals(
                            3,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_option WHERE set_code = 'a_yesno'",
                                            Integer.class)
                                    .intValue(),
                            "补选项不删旧选项");
                    // 失败行不落库：f019 的类型没变（radio）
                    assertEquals(
                            "radio",
                            jdbc.queryForObject(
                                    "SELECT type FROM fe_field WHERE form_code = 'A_BASELINE'"
                                            + " AND field_key = 'f019'",
                                    String.class));
                    // 审计（作业级）与变更记录（A-FLD-009）
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'FIELD_DICT_IMPORT'",
                                            Integer.class)
                                    >= 1,
                            "导入写 FIELD_DICT_IMPORT 审计");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_field_dict_log WHERE field_key"
                                                    + " = 'f801' AND action = 'FIELD_CREATE'",
                                            Integer.class)
                                    >= 1,
                            "新建字段写变更记录");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_field_dict_log WHERE field_key"
                                                    + " = 'f019' AND action = 'FIELD_UPDATE'",
                                            Integer.class)
                                    >= 1,
                            "更新字段写变更记录");
                });

        // GET /fields 能看到导入的字段（字典是表单、校验、导入导出的唯一来源）
        List<Map<?, ?>> fields = itemsOf(get(FIELDS + "?formCode=A_BASELINE", token));
        assertTrue(
                fields.stream().anyMatch(field -> "f801".equals(field.get("key"))), "导入字段出现在字典清单");
    }

    /** 条件 DSL 随导入落库（A-FLD-004 形态：选了再住院类型才显示再住院日期），解析为条件树下发。 */
    @Test
    void importsConditionDefinitionsAsConditionTree() {
        String token = hospitalToken("demoa");

        Map<?, ?> report =
                importFile(
                        token,
                        SimpleXlsx.write(
                                FieldImportHeaders.EXPECTED,
                                List.of(
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f803",
                                                "字段编码", "it_re_hospital_date",
                                                "显示名", "再住院日期（示例）",
                                                "分类键", "surgery",
                                                "类型", "date",
                                                "条件显示", "f804=1",
                                                "帮助说明", "示例：选了再住院类型才显示",
                                                "排序", "120"),
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f804",
                                                "字段编码", "it_re_hospital_type",
                                                "显示名", "再住院类型（示例）",
                                                "分类键", "surgery",
                                                "类型", "select",
                                                "选项集编码", "it_re_type",
                                                "选项", "1=再入院（示例）,2=再血管化（示例）",
                                                "排序", "130"))));
        assertEquals(0, ((Number) report.get("failed")).intValue(), "前向引用合法：" + report);

        Map<?, ?> reHospitalDate =
                itemsOf(get(FIELDS + "?formCode=A_BASELINE", token)).stream()
                        .filter(field -> "f803".equals(field.get("key")))
                        .findFirst()
                        .orElseThrow();
        Map<?, ?> visibleWhen = (Map<?, ?>) reHospitalDate.get("visibleWhen");
        assertNotNull(visibleWhen, "条件树已落库");
        Map<?, ?> leaf = (Map<?, ?>) ((List<?>) visibleWhen.get("all")).get(0);
        assertEquals("f804", leaf.get("field"));
        assertEquals("eq", leaf.get("op"));
        assertEquals(1, ((Number) leaf.get("value")).intValue(), "条件值按值编码（选项编码整数）");
    }

    /** 无权限 40300（前端隐藏不能代替后端校验）；文件级错误 40001（非 xlsx / 表头不符）。 */
    @Test
    void importRejectsWithoutPermissionAndBrokenFiles() {
        byte[] goodFile =
                SimpleXlsx.write(
                        FieldImportHeaders.EXPECTED,
                        List.of(
                                FieldImportHeaders.row(
                                        "表单编码", "A_BASELINE",
                                        "稳定键", "f805",
                                        "字段编码", "it_x",
                                        "显示名", "示例（示例）",
                                        "分类键", "case",
                                        "类型", "text")));
        assertEquals(
                40300,
                code(importFileBody(hospitalToken("demoa", "doctor.demo"), goodFile)),
                "无 followup:field:edit 40300");

        String token = hospitalToken("demoa");
        assertEquals(40001, code(importFileBody(token, "不是表格".getBytes())), "非 xlsx");
        assertEquals(
                40001,
                code(
                        importFileBody(
                                token, SimpleXlsx.write(List.of("错误表头"), List.of(List.of("值"))))),
                "表头不符");
    }

    /** 模块关闭 40301：followup 关闭后导入接口一并拒绝，恢复即用（PLT-MOD-006）。 */
    @Test
    void importBlockedWhenFollowupModuleClosed() {
        String token = hospitalToken("demoa");
        byte[] file =
                SimpleXlsx.write(
                        FieldImportHeaders.EXPECTED,
                        List.of(
                                FieldImportHeaders.row(
                                        "表单编码", "A_BASELINE",
                                        "稳定键", "f805",
                                        "字段编码", "it_x",
                                        "显示名", "示例（示例）",
                                        "分类键", "case",
                                        "类型", "text")));
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, code(importFileBody(token, file)), "模块关闭时导入 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(importFileBody(token, file)), "恢复即用");
    }

    /** 双租户隔离：demoa 导入的新字段在 demob 不可见（每院一个库）。 */
    @Test
    void importStaysTenantIsolated() {
        Map<?, ?> report =
                importFile(
                        hospitalToken("demoa"),
                        SimpleXlsx.write(
                                FieldImportHeaders.EXPECTED,
                                List.of(
                                        FieldImportHeaders.row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f806",
                                                "字段编码", "it_only_a",
                                                "显示名", "A 院专属字段（示例）",
                                                "分类键", "case",
                                                "类型", "text"))));
        assertEquals(1, ((Number) report.get("created")).intValue());

        assertTrue(
                itemsOf(get(FIELDS + "?formCode=A_BASELINE", hospitalToken("demob"))).stream()
                        .noneMatch(field -> "f806".equals(field.get("key"))),
                "B 院字典不受 A 院导入影响");
    }

    // ---------------------------------------------------------------- 导入辅助

    /** 表头与拼行（与 crdp-form 的 FieldImportService.HEADERS 同一清单、同序）。 */
    private static final class FieldImportHeaders {

        static final List<String> EXPECTED =
                List.of(
                        "表单编码",
                        "稳定键",
                        "字段编码",
                        "显示名",
                        "分类键",
                        "类型",
                        "单位",
                        "小数位",
                        "硬范围下限",
                        "硬范围上限",
                        "提示范围下限",
                        "提示范围上限",
                        "必填",
                        "敏感",
                        "存储方式",
                        "选项集编码",
                        "选项",
                        "选项序数",
                        "互斥选项编码",
                        "需补充说明选项编码",
                        "选项别名",
                        "派生公式",
                        "条件显示",
                        "条件必填",
                        "帮助说明",
                        "排序",
                        "状态");

        /** 按「表头名 → 值」拼一行，未提到的列留空。 */
        static List<String> row(String... pairs) {
            List<String> cells = new ArrayList<>(Collections.nCopies(EXPECTED.size(), ""));
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                int index = EXPECTED.indexOf(pairs[i]);
                if (index < 0) {
                    throw new IllegalArgumentException("测试写了不存在的表头：" + pairs[i]);
                }
                cells.set(index, pairs[i + 1]);
            }
            return cells;
        }

        private FieldImportHeaders() {}
    }

    /** 上传导入文件，返回完整响应体（错误码在 body 里）。 */
    private Map<?, ?> importFileBody(String token, byte[] file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new ByteArrayResource(file) {
                    @Override
                    public String getFilename() {
                        return "fields.xlsx";
                    }
                });
        return rest.postForEntity(FIELDS + "/import", new HttpEntity<>(parts, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> importFile(String token, byte[] file) {
        Map<?, ?> body = importFileBody(token, file);
        assertEquals(0, code(body), "导入请求成功：" + body);
        return data(body);
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> itemsOf(Map<?, ?> response) {
        return (List<Map<?, ?>>) response.get("data");
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
