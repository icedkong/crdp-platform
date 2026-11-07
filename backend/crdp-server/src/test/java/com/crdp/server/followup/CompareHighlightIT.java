package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E17-T04 端到端 / A-FU-027：对比标红结果持久化与规则变更重算。验收原文：改规则后可重算已提交记录。
 *
 * <p>链路（tech-plan §11「后端保存时用同一组规则再算一次，结果存 fu_record.highlight」，followup V69）： ①提交记录 →
 * 同一事务重算该患者该表单已提交记录并落 highlight（无命中 NULL）；②补录一条更早的记录 → 排在它后面的记录「上一次」 变化、highlight 级联重算（tech-plan
 * §11）；③PUT /compare-rules/{id} 调阈值 / 停用 → 该表单全部已提交记录按新规则重算 + COMPARE_RULE_RECALC
 * 汇总审计。规则种子与回落基线（f102 EF）为迁移 V67 口径；全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompareHighlightIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";
    private static final String DOCTOR = "doctor.demo";
    private static final String LEGACY = "IT-E17T04";
    private static final String PLAN_CODE = "PLAN-IT-E17T04";
    private static final String FORM = "A_ANNUAL_FU";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        runInTenantA(
                () -> {
                    restoreDemoRules(jdbc);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                    + " 'compare_highlight'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type IN"
                                    + " ('RECORD_SUBMIT', 'RECORD_BACKFILL_CREATE')"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                    + " FROM fu_record r JOIN pt_patient p"
                                    + " ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type LIKE 'PLAN_%'"
                                    + " AND target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                    + " WHERE code LIKE '"
                                    + PLAN_CODE
                                    + "%'))");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE fh.record_type = 'FU_RECORD'"
                                    + " AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM fu_record r JOIN pt_patient p"
                                    + " ON p.id = r.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE s FROM pt_baseline_section s JOIN pt_patient p"
                                    + " ON p.id = s.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code LIKE ?",
                            PLAN_CODE + "%");
                    jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    /** 恢复被用例改过的种子规则（ef_drop 阈值 10 启用，迁移 V67 同值；与 CompareRulesIT 同口径）。 */
    private static void restoreDemoRules(JdbcTemplate jdbc) {
        jdbc.update(
                "UPDATE fu_compare_rule SET params ="
                        + " JSON_OBJECT('direction', 'DROP', 'threshold', 10), status = 'ENABLED',"
                        + " updated_by = NULL WHERE form_code = 'A_ANNUAL_FU' AND rule_code = 'ef_drop'");
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 提交落库 + 补录级联（tech-plan §11）：EF 基线 55 → 提交补录 EF 44（today-2）命中 ef_drop（previousValue 55、下降
     * 11）；再补录一条更早的 EF 50（today-5）→ 排在它后面的记录「上一次」变为该补录，下降 6 &lt; 10 → highlight 清为 NULL。重算不动
     * updated_by / updated_at（系统重算不是用户编辑）。
     */
    @Test
    void submitPersistsHighlightAndBackfillRecalculatesLaterRecords() {
        LocalDate today = today();
        String doctor = login(DOCTOR);
        long planId = publishedCyclePlan();
        long patientId = registerPatient(doctor, "P1", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");

        String later = createBackfill(doctor, patientId, today.minusDays(2));
        assertEquals(
                0, code(submit(doctor, later, today.minusDays(2), Map.of("f248", 44))), "补录提交失败");
        Map<String, Object> hit = firstHitOf(later);
        assertEquals("ef_drop", hit.get("ruleId"), "提交即持久化命中");
        assertEquals(55, ((Number) hit.get("previousValue")).intValue(), "第 1 年回落基线（Q41）");
        assertEquals("下降 11 个百分点", hit.get("changeText"), "变化量文案与前端同形");
        assertEquals("f248", hit.get("key"));

        // 补录一条更早的记录（EF 50）：later 的上一次从基线变为它 → 下降 6 < 10 不再命中
        String earlier = createBackfill(doctor, patientId, today.minusDays(5));
        assertEquals(
                0, code(submit(doctor, earlier, today.minusDays(5), Map.of("f248", 50))), "补录提交失败");
        assertNull(highlightOf(later), "排在后面的记录按新「上一次」重算：不命中 → NULL");
        assertNull(highlightOf(earlier), "更早记录自身回落基线：上升 5 不命中 → NULL");
    }

    /**
     * 验收位（tasks.csv E17-T04「改规则后可重算已提交记录」）：提交 EF 44 命中（阈值 10）→ PUT 调阈值 20 → 已提交记录重算后 清为 NULL → PUT
     * 调回 10 → 命中恢复；写 COMPARE_RULE_RECALC 汇总审计。
     */
    @Test
    void ruleChangeRecalculatesSubmittedRecords() {
        LocalDate today = today();
        String doctor = login(DOCTOR);
        String admin = login(ADMIN);
        long planId = publishedCyclePlan();
        long patientId = registerPatient(doctor, "P2", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");

        String record = createBackfill(doctor, patientId, today.minusDays(2));
        assertEquals(
                0, code(submit(doctor, record, today.minusDays(2), Map.of("f248", 44))), "补录提交失败");
        assertEquals("ef_drop", firstHitOf(record).get("ruleId"), "阈值 10：下降 11 命中并已持久化");

        long ruleId = efDropRuleId(admin);
        // 阈值 10 → 20：已提交记录按新阈值重算，下降 11 < 20 → 清除
        assertEquals(
                0,
                code(
                        putRule(
                                admin,
                                ruleId,
                                "{\"enabled\":true,\"params\":{\"direction\":\"DROP\",\"threshold\":20}}")),
                "调阈值失败");
        assertNull(highlightOf(record), "改规则后已提交记录按新阈值重算（不再命中 → NULL）");
        // 调回 10：命中恢复（重算幂等，可来回切换）
        assertEquals(
                0,
                code(
                        putRule(
                                admin,
                                ruleId,
                                "{\"enabled\":true,\"params\":{\"direction\":\"DROP\",\"threshold\":10}}")),
                "调回阈值失败");
        assertEquals("ef_drop", firstHitOf(record).get("ruleId"), "调回后命中恢复");
        assertTrue(
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'COMPARE_RULE_RECALC'"
                                                        + " AND target = 'FU_COMPARE_RULE:ef_drop'",
                                                Integer.class)
                                        >= 1),
                "规则变更重算写汇总审计");
    }

    /** 停用规则 → 该表单已提交记录的 highlight 整体清除（V69 注释口径）。 */
    @Test
    void disablingRuleClearsSubmittedHighlights() {
        LocalDate today = today();
        String doctor = login(DOCTOR);
        String admin = login(ADMIN);
        long planId = publishedCyclePlan();
        long patientId = registerPatient(doctor, "P3", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");
        String record = createBackfill(doctor, patientId, today.minusDays(2));
        assertEquals(
                0, code(submit(doctor, record, today.minusDays(2), Map.of("f248", 44))), "补录提交失败");
        assertTrue(highlightOf(record) != null, "先确认有命中");

        assertEquals(
                0,
                code(
                        putRule(
                                admin,
                                efDropRuleId(admin),
                                "{\"enabled\":false,\"params\":{\"direction\":\"DROP\",\"threshold\":10}}")),
                "停用失败");
        assertNull(highlightOf(record), "停用即整表单清除（E17-T04）");
    }

    // ---------------------------------------------------------------- 造数与工具

    /** 基线出院前超声分节（A_BASELINE / echo；分节当前值即回落值）。 */
    private void seedBaselineEcho(long patientId, String dataJson) {
        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT INTO pt_baseline_section"
                                        + " (patient_id, form_code, category_key, template_version,"
                                        + " data, has_draft)"
                                        + " SELECT ?, 'A_BASELINE', 'echo', f.current_version,"
                                        + " CAST(? AS JSON), 0 FROM fe_form f"
                                        + " WHERE f.code = 'A_BASELINE'",
                                patientId,
                                dataJson));
    }

    /** 发布「循环 365 天 + 偏移 20 天」计划（与 CompareSourceIT 同口径）。 */
    private long publishedCyclePlan() {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 对比重算计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        Map<?, ?> created = post("/api/hospital/followup/plans", login(ADMIN), body);
        assertEquals(0, code(created), "新建计划失败：" + created);
        long planId = Long.parseLong((String) data(created).get("id"));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "年度随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 365);
        subplan.put("cycleUnit", "DAY");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 3);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 3);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 3);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", FORM);
        node.put("sortNo", 20);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                login(ADMIN),
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/publish",
                                login(ADMIN),
                                Map.of())),
                "发布失败");
        return planId;
    }

    /** ef_drop 规则 id（GET /compare-rules 清单里按 ruleCode 定位；V67 种子）。 */
    private long efDropRuleId(String adminToken) {
        Map<?, ?> list = get("/api/hospital/followup/compare-rules?formCode=" + FORM, adminToken);
        assertEquals(0, code(list), "规则清单失败：" + list);
        return ((Number)
                        ((List<?>) list.get("data"))
                                .stream()
                                        .map(item -> (Map<?, ?>) item)
                                        .filter(item -> "ef_drop".equals(item.get("ruleCode")))
                                        .findFirst()
                                        .orElseThrow()
                                        .get("id"))
                .longValue();
    }

    private Map<?, ?> putRule(String adminToken, long ruleId, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminToken);
        return rest.exchange(
                        "/api/hospital/followup/compare-rules/" + ruleId,
                        HttpMethod.PUT,
                        new HttpEntity<>(jsonBody, headers),
                        Map.class)
                .getBody();
    }

    private long registerPatient(String token, String suffix, LocalDate discharge) {
        long wardId =
                runInTenantA(() -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "周重算（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1956-02-02");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(3).toString());
        body.put("surgeryDate", discharge.minusDays(2).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 建补录草稿（HTTP 正口，A-FU-014）。 */
    private String createBackfill(String token, long patientId, LocalDate visitDate) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate.toString(),
                                "formCode",
                                FORM));
        assertEquals(0, code(created), "创建记录失败：" + created);
        return String.valueOf(data(created).get("recordId"));
    }

    /** 提交记录（必填与 fu_echo 条件显示口径与 CompareSourceIT 一致）。 */
    private Map<?, ?> submit(
            String token, String recordId, LocalDate visitDate, Map<String, Object> extras) {
        Map<String, Object> data = new HashMap<>();
        data.put("f214", visitDate.toString());
        data.put("f215", 0);
        data.put("f213", 1);
        data.put("f236", 1);
        data.put("f237", visitDate.toString());
        data.putAll(extras);
        return post(
                "/api/hospital/followup/records/" + recordId + "/submit",
                token,
                Map.of("baseVersion", 0, "data", data));
    }

    /** fu_record.highlight（租户库直读；NULL = 无命中 / 已清除——queryForObject 对 NULL 列返回 null）。 */
    private String highlightOf(String recordId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT CAST(highlight AS CHAR) FROM fu_record WHERE id = ?",
                                String.class,
                                Long.parseLong(recordId)));
    }

    /** 第一条命中（MySQL JSON 列输出带空格，断言按字段解析、不做字符串包含；无命中 null）。 */
    private Map<String, Object> firstHitOf(String recordId) {
        String stored = highlightOf(recordId);
        if (stored == null) {
            return null;
        }
        try {
            return JSON.readValue(stored, new TypeReference<List<Map<String, Object>>>() {}).get(0);
        } catch (Exception broken) {
            throw new IllegalStateException("highlight 解析失败：" + stored, broken);
        }
    }

    private void runInTenantA(Runnable action) {
        TenantContext.runAsTenant(TENANT_A_ID, TENANT_A, action);
    }

    private <T> T runInTenantA(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_A_ID, TENANT_A, action::get);
    }

    private String login(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT_A, "username", username, "password", PASSWORD));
        assertEquals(0, code(response), "登录失败：" + response);
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

    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<?, ?> response = rest.exchange(path, method, entity, Map.class).getBody();
        return response == null ? Map.of() : response;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
