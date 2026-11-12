package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.framework.tenant.TenantContext;
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
 * E21-T10 端到端（tasks.csv 验收「编号规则可配置」；评审用例 C-ACC-08 / C-ACC-09）。场景（demoa 开 cfg_feature.implant = 医院 C
 * 口径，demob 保持关闭 = A / B 口径）：
 *
 * <ol>
 *   <li>自动生成（C-PAT-003 / Q10）：visitNo 留空 → 按本院生效规则「前缀-建档年份-年内 3 位流水号」生成（出厂默认前缀 105，OQ-131①），
 *       年度流水递增；registration-options 下发规则前缀与示例；
 *   <li>规则可配置（验收原文）：本院 cfg_param 覆盖前缀（followup.visitno.prefix）→ 新登记按新前缀发号（新前缀年度流水独立起算）；
 *   <li>历史患者建档（C-PAT-008）：2019 年植入日期可登记（允许过去日期），手填纸质原编号落库；该患者不创建任何随访任务 （C 登记按器械类型入组随
 *       E21-T05；入组前已过窗口不建任务、不算逾期的口径由 E14-T05 passed_window_action=SKIP 机制承接，
 *       TaskCreationPlannerTest「医院 C 口径（Q14）」锁定），全库 OVERDUE 数不增加；
 *   <li>唯一与校验：重复手填编号 40902（DENIED 审计 DUPLICATE_VISIT_NO）；超长 40001；demob（开关关闭）携带 visitNo 40001、 不携带则
 *       A 口径照常（visit_no 为 NULL）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T10-；测试数据全部虚构）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitNoRegistrationIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T10";
    private static final String PARAM_KEY = "followup.visitno.prefix";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        com.crdp.server.AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                        + " AND (target IN (SELECT CONCAT('PATIENT:', id) FROM"
                                        + " pt_patient WHERE legacy_no LIKE ?)"
                                        + " OR detail LIKE '%随访编号已存在%')",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_visit_no_seq WHERE prefix IN ('105', 'C21')");
                        jdbc.update("DELETE FROM cfg_param WHERE param_key = ?", PARAM_KEY);
                        jdbc.update("DELETE FROM cfg_param_log WHERE param_key = ?", PARAM_KEY);
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：编号规则可配置（自动生成 + 前缀覆盖）

    @Test
    void autoGeneratesSequentialNumbersAndPrefixIsTenantConfigurable() {
        enableImplant("demoa");
        String admin = adminToken();
        int businessYear =
                LocalDate.ofInstant(
                                java.time.Instant.now(),
                                com.crdp.followup.plan.domain.PlanDateCalculator.BUSINESS_ZONE)
                        .getYear();

        // 登记候选下发规则（默认前缀 105，OQ-131①）
        Map<?, ?> options = get("/api/hospital/followup/patients/registration-options", admin);
        assertEquals(0, code(options));
        Map<?, ?> rule = (Map<?, ?>) data(options).get("visitNoRule");
        assertNotNull(rule, "C 模式下发随访编号规则");
        assertEquals("105", rule.get("prefix"), "出厂默认前缀 105（OQ-131①）");
        assertEquals("105-" + businessYear + "-001", rule.get("sample"));

        // 留空 → 自动生成，年度流水递增（-001、-002）
        Map<?, ?> first =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        cBody("IT-MRN-E21T10-01", null, "2026-01-05"));
        assertEquals(0, code(first), "登记失败：" + first);
        assertEquals("105-" + businessYear + "-001", data(first).get("visitNo"), "年内首号");
        String firstPatientId = String.valueOf(data(first).get("id"));
        Map<?, ?> second =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        cBody("IT-MRN-E21T10-02", null, "2026-02-05"));
        assertEquals(0, code(second));
        assertEquals("105-" + businessYear + "-002", data(second).get("visitNo"), "年内流水递增");

        // 落库核对（编号为普通列，非密文）
        String stored =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT visit_no FROM pt_patient WHERE legacy_no = ?",
                                        String.class,
                                        LEGACY + "-IT-MRN-E21T10-02"));
        assertEquals("105-" + businessYear + "-002", stored);

        // 本院覆盖前缀（cfg_param，租户参数链路）→ 新登记按新前缀发号，年度流水独立起算
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO cfg_param (param_key, param_value, remark)"
                                        + " VALUES (?, 'C21', 'IT 覆盖前缀（示例）')",
                                PARAM_KEY));
        Map<?, ?> third =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        cBody("IT-MRN-E21T10-03", null, "2026-03-05"));
        assertEquals(0, code(third));
        assertEquals("C21-" + businessYear + "-001", data(third).get("visitNo"), "规则可配置（验收原文）");

        // 审计：登记描述记录编号与来源（按本人审计行计数——先跑的 C 登记 IT 也会留下
        // 「随访编号 105-…-001（自动生成）」审计且不随其清理删除，全局 LIKE 会多数）
        Long audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'PATIENT_CREATE' AND target = ? AND detail"
                                                + " LIKE '%随访编号 105-"
                                                + businessYear
                                                + "-001%（自动生成）%'",
                                        Long.class,
                                        "PATIENT:" + firstPatientId));
        assertEquals(1L, audits, "登记审计记录随访编号与来源");
    }

    // ---------------------------------------------------------------- 验收：历史患者建档（C-PAT-008 /
    // C-ACC-09）

    @Test
    void historicalPatientWithPastImplantDateRegistersWithoutTasksOrOverdue() {
        enableImplant("demoa");
        String admin = adminToken();
        Long overdueBefore =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE status = 'OVERDUE'",
                                        Long.class));

        // 2019 年植入的历史患者：过去植入日期 + 手填纸质原编号（C-PAT-008 + C-PAT-003）
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        cBody("IT-MRN-E21T10-11", "PAPER-2019-012", "2019-05-10"));
        assertEquals(0, code(created), "历史患者建档失败：" + created);
        String patientId = String.valueOf(data(created).get("id"));
        assertEquals("PAPER-2019-012", data(created).get("visitNo"), "手填纸质原编号原样落库");

        // 不创建任何任务（入组随 E21-T05），全库 OVERDUE 数不增加（首页逾期数不增加）
        Long tasks =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?",
                                        Long.class,
                                        Long.valueOf(patientId)));
        assertEquals(0L, tasks, "历史患者建档不创建任务");
        Long overdueAfter =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE status = 'OVERDUE'",
                                        Long.class));
        assertEquals(overdueBefore, overdueAfter, "首页逾期数不增加");

        // 重复手填编号 → 40902（DENIED 审计）
        Map<?, ?> duplicate =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        cBody("IT-MRN-E21T10-12", "PAPER-2019-012", "2019-06-10"));
        assertEquals(40902, code(duplicate), "重复编号被拒（C-ACC-08）");
        Long denied =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'PATIENT_CREATE' AND result = 'DENIED'"
                                                + " AND reason = 'DUPLICATE_VISIT_NO'",
                                        Long.class));
        assertEquals(1L, denied, "被拒操作写 DENIED 审计");

        // 自动生成的编号同样占用唯一性（手填撞自动号 → 40902）
        Map<?, ?> clash =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        cBody("IT-MRN-E21T10-13", "PAPER-2019-012 ", "2019-07-10"));
        assertEquals(40902, code(clash), "同一编号（含首尾空白）仍唯一");
    }

    // ---------------------------------------------------------------- 校验与 A / B 口径不受影响

    @Test
    void validationAndAModeRemainUnaffected() {
        enableImplant("demoa");
        String admin = adminToken();

        // 超长 → 40001
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/patients",
                                admin,
                                cBody("IT-MRN-E21T10-21", "x".repeat(65), "2026-01-05"))),
                "随访编号过长 40001");

        // demob（implant 关闭 = A / B 口径）：携带 visitNo → 40001；不带 → A 口径照常，visit_no 为 NULL
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        Map<String, Object> aWithVisitNo = aBody("demob", "IT-MRN-E21T10-22", "W-DEMO-1");
        aWithVisitNo.put("visitNo", "105-2026-001");
        assertEquals(
                40001,
                code(post("/api/hospital/followup/patients", demobAdmin, aWithVisitNo)),
                "开关关闭的租户携带随访编号 40001");
        Map<?, ?> aMode =
                post(
                        "/api/hospital/followup/patients",
                        demobAdmin,
                        aBody("demob", "IT-MRN-E21T10-23", "W-DEMO-1"));
        assertEquals(0, code(aMode), "A 口径登记不受影响");
        assertNull(data(aMode).get("visitNo"), "A 登记不生成随访编号");
        String stored =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT visit_no FROM pt_patient WHERE legacy_no = ?",
                                        String.class,
                                        LEGACY + "-IT-MRN-E21T10-23"));
        assertNull(stored, "A 登记 visit_no 落 NULL");
    }

    // ---------------------------------------------------------------- 辅助

    private void enableImplant(String tenant) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
    }

    /** C 登记请求体（visitNo 可空 = 自动生成；implantDate 允许过去日期，C-PAT-008）。 */
    private Map<String, Object> cBody(String mrn, String visitNo, String implantDate) {
        return cBodyTo("demoa", mrn, "W-DEMO-1", visitNo, implantDate);
    }

    private Map<String, Object> cBodyTo(
            String tenant, String mrn, String wardCode, String visitNo, String implantDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", "王李张（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId(tenant));
        body.put("wardId", wardId(tenant, wardCode));
        body.put("legacyNo", LEGACY + "-" + mrn);
        if (visitNo != null) {
            body.put("visitNo", visitNo);
        }
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate == null ? "2026-01-05" : implantDate);
        implant.put("deviceType", "DDD");
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-E21T10-" + mrn.substring(mrn.length() - 2));
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        implant.put(
                "leads",
                List.of(
                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                        Map.of("chamber", "RV", "position", "RV_APEX")));
        body.put("implant", implant);
        return body;
    }

    /** A 出院登记请求体（不带植入记录与随访编号）。 */
    private Map<String, Object> aBody(String tenant, String mrn, String wardCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", "陈褚卫（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId(tenant, wardCode));
        body.put("surgeryDate", "2026-03-01");
        body.put("admissionDate", "2026-02-25");
        body.put("dischargeDate", "2026-03-08");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        return body;
    }

    /** 病区解析（W-DEMO-1 由种子 V100 预置在每个演示租户库；租户库隔离，各查各库）。 */
    private long wardId(String tenant, String code) {
        return runInTenant(
                tenant,
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId(String tenant) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'", Long.class));
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class)
                .getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
