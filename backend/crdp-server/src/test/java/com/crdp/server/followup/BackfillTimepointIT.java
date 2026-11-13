package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.TenantModuleService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * E21-T14 端到端（验收原文：连续补录不需反复打开表单；C-FU-013/014/015）。场景（demoa 开 cfg_feature.implant，患者带植入记录、入组
 * C_PROGRAM_FU 计划）：
 *
 * <ol>
 *   <li>建议接口（GET /patients/{id}/records/suggest-timepoint）：距植入 31 天建议「术后1个月」（hospital-c-spec 验收原文：
 *       在补录页填写距植入 31 天的随访日期，随访时间点自动建议为「术后1个月」）；天数边界（0 / 7 / 8 空档 / 401 其后）； 最早植入日期下发（补录日期下界）；在组
 *       mode=ERI 且随访日期 ≥ eri_since 时优先建议「ERI期随访」；
 *   <li>补录创建（POST /patients/{id}/records source=BACKFILL）：建议时间点种进草稿（f304，可改选）； 随访日期早于最早植入 40001；
 *   <li>连续补录（C-FU-015 后端口径）：同一创建接口重复调用——第二条记录同患者、测量值天然清空（新记录）， 器械按新随访日期重新匹配（保留患者与器械维度）；
 *   <li>权限（无 followup:record:edit 40300）、模块关闭 40301、越范围患者 40400。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T14-）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackfillTimepointIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T14";
    private static final String PLAN_CODE = "PLAN-IT-E21T14";
    private static final String FORM = "C_PROGRAM_FU";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** C 表单随访时间点字段（V90：编码 visit_timepoint → 稳定键 f304，选项集 c_visit_timepoint）。 */
    private static final String TIMEPOINT_KEY = "f304";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private com.crdp.followup.plan.app.PlanEnrollmentService enrollmentService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    com.crdp.server.AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE event_type = 'RECORD_BACKFILL_CREATE'"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM fu_record r"
                                    + " JOIN pt_patient p ON p.id = r.patient_id WHERE p.legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE fh.record_type = 'FU_RECORD' AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code LIKE ?",
                            PLAN_CODE + "%");
                    jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                    jdbc.update(
                            "DELETE il FROM pt_implant_lead il"
                                    + " JOIN pt_patient p ON p.id = il.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM pt_lead l JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE i FROM pt_implant i JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u"
                                    + " ON u.id = ur.user_id WHERE u.username LIKE 'IT-U-E21T14-%'");
                    jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E21T14-%'");
                    for (String roleCode : List.of("IT-R-E21T14-VIEW")) {
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r"
                                        + " ON r.id = rm.role_id WHERE r.code = ?",
                                roleCode);
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r"
                                        + " ON r.id = rs.role_id WHERE r.code = ?",
                                roleCode);
                        jdbc.update("DELETE FROM sys_role WHERE code = ?", roleCode);
                    }
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
    }

    // ---------------------------------------------------------------- 建议（C-FU-014）

    @Test
    void suggestsTimepointByDaysSinceImplant() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now(ZONE).minusYears(2);
        long patientId = registerImplantPatient(admin, "S1", "DDD", implant);
        enroll(admin, patientId);

        // 验收原文：距植入 31 天 → 「术后1个月」（选项 2），自动关联当时器械
        Map<?, ?> oneMonth = suggest(admin, patientId, implant.plusDays(31));
        assertEquals(31, ((Number) oneMonth.get("daysSinceImplant")).intValue());
        assertEquals(2, ((Number) oneMonth.get("suggestedTimepoint")).intValue());
        assertEquals("术后1个月", oneMonth.get("suggestedLabel"));
        assertEquals("DDD", ((Map<?, ?>) oneMonth.get("device")).get("type"));
        assertEquals(false, oneMonth.get("eriFollowup"));

        // 天数边界：0 / 7 → 术后72小时；8 → 空档无建议；401 → 其后（每6-12个月）
        assertEquals(
                1,
                ((Number) suggest(admin, patientId, implant).get("suggestedTimepoint")).intValue());
        assertEquals(
                1,
                ((Number) suggest(admin, patientId, implant.plusDays(7)).get("suggestedTimepoint"))
                        .intValue(),
                "7 天是「术后72小时/出院前」区间上界");
        assertNull(
                suggest(admin, patientId, implant.plusDays(8)).get("suggestedTimepoint"),
                "8–24 天空档区间不建议（用户自选）");
        assertEquals(
                6,
                ((Number)
                                suggest(admin, patientId, implant.plusDays(401))
                                        .get("suggestedTimepoint"))
                        .intValue(),
                "晚于最后区间上界 → 其后「每6-12个月」");
        assertEquals(
                implant.toString(),
                String.valueOf(oneMonth.get("earliestImplantDate")),
                "最早植入日期下发（补录日期下界）");
    }

    @Test
    void eriEnrollmentSuggestsEriFollowupFirst() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now(ZONE).minusYears(2);
        long patientId = registerImplantPatient(admin, "S2", "DDD", implant);
        enroll(admin, patientId);
        // 在组翻 ERI（正常路径是随访记录提交 ERI 状态；IT 直接置位造场景，eri_since = 植入后 20 天）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET mode = 'ERI', eri_since = ?"
                                        + " WHERE patient_id = ? AND status = 'ACTIVE'",
                                java.sql.Date.valueOf(implant.plusDays(20)),
                                patientId));

        Map<?, ?> eri = suggest(admin, patientId, implant.plusDays(31));
        assertEquals(true, eri.get("eriFollowup"));
        assertEquals(7, ((Number) eri.get("suggestedTimepoint")).intValue(), "优先建议 ERI期随访");
        assertEquals("ERI期随访", eri.get("suggestedLabel"));

        // 解除（回 NORMAL）：恢复按天数建议
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET mode = 'NORMAL', eri_since = NULL"
                                        + " WHERE patient_id = ? AND status = 'ACTIVE'",
                                patientId));
        Map<?, ?> normal = suggest(admin, patientId, implant.plusDays(31));
        assertEquals(false, normal.get("eriFollowup"));
        assertEquals(2, ((Number) normal.get("suggestedTimepoint")).intValue());
    }

    // ---------------------------------------------------------------- 创建带入 + 连续补录（C-FU-013/015）

    @Test
    void backfillCreateSeedsTimepointAndSupportsContinuousEntry() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now(ZONE).minusYears(2);
        long patientId = registerImplantPatient(admin, "C1", "DDD", implant);
        enroll(admin, patientId);

        // 创建即种入建议时间点（可改选：表单里改）
        Map<?, ?> first =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of("source", "BACKFILL", "visitDate", implant.plusDays(31).toString()));
        assertEquals(0, code(first), "补录创建失败：" + first);
        Map<?, ?> firstDetail = recordDetail(admin, String.valueOf(data(first).get("recordId")));
        assertEquals(
                2,
                ((Number) ((Map<?, ?>) firstDetail.get("data")).get(TIMEPOINT_KEY)).intValue(),
                "建议时间点种进草稿（C-FU-014）");
        assertEquals("BACKFILL", firstDetail.get("source"), "记录来源标为补录（C-FU-013）");

        // 连续补录（C-FU-015 后端口径）：同一接口再建下一条——同患者、器械按新日期重新匹配、测量值为空（新记录）
        Map<?, ?> second =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of("source", "BACKFILL", "visitDate", implant.plusDays(92).toString()));
        assertEquals(0, code(second), "继续补录下一次（同一创建接口）：" + second);
        Map<?, ?> secondDetail = recordDetail(admin, String.valueOf(data(second).get("recordId")));
        assertEquals(
                String.valueOf(patientId),
                String.valueOf(((Map<?, ?>) secondDetail.get("patient")).get("patientId")),
                "保留患者（C-FU-015）");
        assertEquals(
                3,
                ((Number) ((Map<?, ?>) secondDetail.get("data")).get(TIMEPOINT_KEY)).intValue(),
                "92 天 → 建议术后3个月（连续补录同样种入）");
        assertNull(((Map<?, ?>) secondDetail.get("data")).get("f311"), "测量值清空（新记录不带上一次的值）");

        // 随访日期早于最早植入 → 40001（「不早于植入日期、不晚于今天」）
        Map<?, ?> tooEarly =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of("source", "BACKFILL", "visitDate", implant.minusDays(1).toString()));
        assertEquals(40001, code(tooEarly), "补录随访日期不能早于植入日期");
    }

    // ---------------------------------------------------------------- 权限 / 开关 / 范围

    @Test
    void permissionModuleAndScopeGuards() {
        String admin = adminToken();
        // doctor.demo 数据范围不含 IT 越范围病区（与 TempReminderUnplannedIT 同口径）；
        // nurse.demo（admin）负责登记但范围判定用 doctor 断言 40400
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        LocalDate implant = LocalDate.now(ZONE).minusYears(2);
        long patientId = registerImplantPatient(admin, "G1", "DDD", implant);
        enroll(admin, patientId);
        long outScope =
                registerImplantPatientInWard(admin, "G2", "DDD", implant, ensureOtherWard());

        // 越范围患者 40400（不暴露存在）
        assertEquals(40400, code(suggestRaw(doctor, outScope, implant.plusDays(31))));
        assertEquals(
                40400,
                code(
                        post(
                                "/api/hospital/followup/patients/" + outScope + "/records",
                                doctor,
                                Map.of(
                                        "source",
                                        "BACKFILL",
                                        "visitDate",
                                        implant.plusDays(31).toString()))));
        // 无 followup:record:edit 40300（只有任务查看菜单 22）
        String viewer =
                newUserToken("IT-U-E21T14-VIEW", roleWithMenus("IT-R-E21T14-VIEW", List.of(22L)));
        assertEquals(40300, code(suggestRaw(viewer, patientId, implant.plusDays(31))));
        // 模块关闭 40301
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, code(suggestRaw(admin, patientId, implant.plusDays(31))));
            assertEquals(
                    40301,
                    code(
                            post(
                                    "/api/hospital/followup/patients/" + patientId + "/records",
                                    admin,
                                    Map.of(
                                            "source",
                                            "BACKFILL",
                                            "visitDate",
                                            implant.plusDays(31).toString()))));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 数据准备（全部虚构）

    /** 发布「FORM 节点挂 C_PROGRAM_FU」计划并入组（建议接口的表单回落与补录创建共用）。 */
    private void enroll(String admin, long patientId) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "IT 计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        Map<?, ?> created = post("/api/hospital/followup/plans", admin, body);
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
        node.put("name", "程控随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
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
                                admin,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                code(post("/api/hospital/followup/plans/" + planId + "/publish", admin, Map.of())),
                "发布失败");
        runInTenant("demoa", () -> enrollmentService.enroll(patientId, planId, null));
    }

    /** C 模式登记（implant 开关切必填集，OQ-71①，与 DeviceContextIT 同口径）。 */
    private long registerImplantPatient(
            String token, String suffix, String deviceType, LocalDate implantDate) {
        return registerImplant(
                token, LEGACY + "-" + suffix, deviceType, implantDate, wardId("W-DEMO-1"));
    }

    private long registerImplantPatientInWard(
            String token, String suffix, String deviceType, LocalDate implantDate, long wardId) {
        return registerImplant(token, LEGACY + "-" + suffix, deviceType, implantDate, wardId);
    }

    private long registerImplant(
            String token, String mrn, String deviceType, LocalDate implantDate, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", "王补录（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        Long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        body.put("deptId", deptId);
        body.put("wardId", wardId);
        body.put("legacyNo", mrn);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate.toString());
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "SN-" + mrn);
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
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    // ---------------------------------------------------------------- HTTP 工具

    private Map<?, ?> suggest(String token, long patientId, LocalDate visitDate) {
        Map<?, ?> response = suggestRaw(token, patientId, visitDate);
        assertEquals(0, code(response), "建议接口失败：" + response);
        return data(response);
    }

    private Map<?, ?> suggestRaw(String token, long patientId, LocalDate visitDate) {
        return rest.exchange(
                        "/api/hospital/followup/patients/"
                                + patientId
                                + "/records/suggest-timepoint?visitDate="
                                + visitDate,
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(token)),
                        Map.class)
                .getBody();
    }

    private Map<?, ?> recordDetail(String token, String recordId) {
        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, token);
        assertEquals(0, code(detail), "详情失败：" + detail);
        return data(detail);
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
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

    private long wardId(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long ensureOtherWard() {
        return runInTenant(
                "demoa",
                () -> {
                    Long existing =
                            jdbc.query(
                                    "SELECT id FROM ward WHERE code = 'W-IT-E21T14'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status)"
                                    + " VALUES ('W-IT-E21T14', 'IT 越范围病区（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-E21T14'", Long.class);
                });
    }

    private long roleWithMenus(String roleCode, List<Long> menuIds) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES (?, 'IT 角色（示例）', 0, 'ENABLED')",
                            roleCode);
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
                    for (Long menuId : menuIds) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                roleId,
                                menuId);
                    }
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    return roleId;
                });
    }

    private String newUserToken(String username, long roleId) {
        long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalToken("demoa", "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 只读用户（示例）",
                                "phone",
                                "13900001361",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken("demoa", username, PASSWORD);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
