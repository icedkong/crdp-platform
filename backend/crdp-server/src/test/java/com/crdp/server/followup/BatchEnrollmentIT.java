package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * E13-T07 端到端（A-PAT-016、A-PAT-017、C-PAT-016；tasks.csv 验收「批量操作结果逐条可查」、 规格验收「批量选择 20 名患者加入计划，其中 2
 * 名已在组，结果显示成功 18、跳过 2」）。场景：
 *
 * <ol>
 *   <li>批量入组：20 名患者（2 名已在组）→ 成功 18、跳过 2，逐条明细（脱敏姓名 / 编号 / 病区 / 原因码）与请求顺序一致； fu_enrollment 实增 18
 *       行；再跑一次全量 20 → 全部 ALREADY_ENROLLED（幂等语义的逐条口径）；
 *   <li>预检：将加入 / 将跳过分类（已在组 / 已从该计划移除 / 已死亡 / 越范围），不写任何数据；
 *   <li>批量指定责任医生：医生负责病区 A → 病区 A 患者成功（pt_patient.responsible_user_id 落库）、 病区 B 患者逐条
 *       DOCTOR_NOT_IN_WARD 跳过（A-PAT-017「只能选该病区的医生」）、 doctor.demo（受限范围）视角下其他病区患者 NOT_FOUND
 *       不暴露存在；doctor-options 只出启用病区的启用医生；
 *   <li>批量入组带责任医生：入组同时指定（成功条目医生落库，跨病区医生逐条跳过）；
 *   <li>权限与隔离：无 plan:enroll 40300、无 task:assign 40300、followup 模块关闭 40301、双租户互不可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E13T07 前缀、病案号 IT-E13T07-
 * 前缀）。全部虚构数据。节点形态：远期窗口（偏移 300 天）使入组即建任务为 0 补录 / 0 当期（任务侧细节由 E14-T05 的 EnrollmentBackfillIT 锁定）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchEnrollmentIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String MRN = "IT-E13T07";
    private static final String PLAN_CODE = "PLAN-IT-E13T07";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type = 'OPERATION'"
                                    + " AND action IN ('PLAN_ENROLL_BATCH', 'PATIENT_BATCH_ASSIGN'))"
                                    + " OR (action = 'PLAN_ENROLL'"
                                    + " AND detail LIKE '%"
                                    + PLAN_CODE
                                    + "%')"
                                    + " OR (event_type = 'DATA_ACCESS'"
                                    + " AND detail LIKE '%批量入组预检%')");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            MRN + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                            MRN + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code LIKE ?",
                            PLAN_CODE + "%");
                    jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                    jdbc.update(
                            "DELETE FROM pt_status_history WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            MRN + "-%");
                    jdbc.update(
                            "DELETE FROM pt_patient_summary WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            MRN + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", MRN + "-%");
                    jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-E13T07-%'");
                    jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E13T07-%'");
                    jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-E13T07'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 批量入组（规格验收 18 / 2）

    @Test
    void batchEnrollReturnsPerPatientResults() {
        String admin = adminToken();
        long planId = createPublishedFuturePlan(admin, PLAN_CODE + "-B");
        // 20 名患者，其中 2 名先入组（已在组）
        List<Long> patientIds = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            patientIds.add(registerPatient(admin, "B" + i, today().minusDays(30)));
        }
        runInTenant(
                "demoa",
                () -> {
                    enrollmentService.enroll(patientIds.get(0), planId, null);
                    enrollmentService.enroll(patientIds.get(1), planId, null);
                });

        Map<?, ?> result =
                post(
                        "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                        admin,
                        Map.of("patientIds", patientIds));

        assertEquals(0, codeOf(result), "批量入组失败：" + result);
        Map<?, ?> data = data(result);
        assertEquals(18, ((Number) data.get("succeeded")).intValue(), "成功 18（规格验收）");
        assertEquals(2, ((Number) data.get("skipped")).intValue(), "跳过 2");
        List<Map<String, Object>> items = itemsOf(data);
        assertEquals(20, items.size(), "逐条结果与请求顺序一致");
        assertEquals("SKIP", items.get(0).get("outcome"), "第 1 名已在组 → 跳过");
        assertEquals("ALREADY_ENROLLED", items.get(0).get("reason"), "第 1 名已在组");
        assertEquals("SKIP", items.get(1).get("outcome"), "第 2 名已在组 → 跳过");
        assertEquals("ALREADY_ENROLLED", items.get(1).get("reason"), "第 2 名已在组");
        for (int i = 2; i < 20; i++) {
            assertEquals("SUCCESS", items.get(i).get("outcome"), "其余全部成功：" + items.get(i));
            assertNotNull(items.get(i).get("name"), "逐条结果含脱敏姓名");
            assertNotNull(items.get(i).get("patientNo"), "逐条结果含脱敏编号");
        }
        assertTrue(String.valueOf(items.get(2).get("name")).contains("*"), "姓名为脱敏值（张*明形态）");
        assertEquals(20, enrollmentCountOf(planId), "fu_enrollment 共 20 行（新增 18 + 先入组 2）");

        // 幂等语义：对同一批再执行一次 → 全部已在组跳过（无新增）
        Map<?, ?> again =
                post(
                        "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                        admin,
                        Map.of("patientIds", patientIds));
        assertEquals(0, codeOf(again));
        assertEquals(0, ((Number) data(again).get("succeeded")).intValue(), "第二次全跳过");
        assertEquals(20, ((Number) data(again).get("skipped")).intValue());
        assertEquals(20, enrollmentCountOf(planId), "不重复入组");
    }

    // ---------------------------------------------------------------- 预检（确认前显示将加入 / 将跳过）

    @Test
    void batchPreviewClassifiesWithoutWriting() {
        String admin = adminToken();
        long planId = createPublishedFuturePlan(admin, PLAN_CODE + "-P");
        long fresh = registerPatient(admin, "P1", today().minusDays(30));
        long enrolled = registerPatient(admin, "P2", today().minusDays(30));
        long removed = registerPatient(admin, "P3", today().minusDays(30));
        runInTenant(
                "demoa",
                () -> {
                    enrollmentService.enroll(enrolled, planId, null);
                    long removedEnrollmentId =
                            Long.parseLong(enrollmentService.enroll(removed, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET status = 'REMOVED',"
                                    + " remove_reason = '拒访（示例）' WHERE id = ?",
                            removedEnrollmentId);
                    // 患者级随移出转 TERMINATED（E14-T06）——批量口径仍应列「已从该计划移除」
                    jdbc.update(
                            "UPDATE pt_patient SET followup_status = 'TERMINATED' WHERE id = ?",
                            removed);
                });
        long dead = registerPatient(admin, "P4", today().minusDays(30));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE pt_patient SET followup_status = 'DEAD' WHERE id = ?",
                                dead));
        long outOfScope = registerPatientInOtherWard(admin, "P5", today().minusDays(30));

        // admin（ALL 范围）视角：已在组 / 已移除 / 已死亡三类跳过 + 可入组
        Map<?, ?> preview =
                post(
                        "/api/hospital/followup/plans/" + planId + "/enrollments/batch-preview",
                        admin,
                        Map.of("patientIds", List.of(fresh, enrolled, removed, dead)));

        assertEquals(0, codeOf(preview), "预检失败：" + preview);
        Map<?, ?> data = data(preview);
        assertEquals(1, ((Number) data.get("succeeded")).intValue(), "将加入 1");
        assertEquals(3, ((Number) data.get("skipped")).intValue(), "将跳过 3");
        List<Map<String, Object>> items = itemsOf(data);
        assertEquals("ENROLL", items.get(0).get("outcome"));
        assertEquals("ALREADY_ENROLLED", items.get(1).get("reason"));
        assertEquals("REMOVED", items.get(2).get("reason"), "已从该计划移除（优先于患者级已终止）");
        assertEquals("DEAD", items.get(3).get("reason"), "已死亡");
        // 预检不写数据
        assertEquals(2, enrollmentCountOf(planId), "预检不写入（在组 1 + 已移除 1 行保持原状）");

        // 越范围视角：doctor.demo（WARD 范围）看不到其他病区患者——fresh 可见 ENROLL、越范围 NOT_FOUND
        Map<?, ?> doctorPreview =
                post(
                        "/api/hospital/followup/plans/" + planId + "/enrollments/batch-preview",
                        doctorToken(),
                        Map.of("patientIds", List.of(fresh, outOfScope)));
        assertEquals(0, codeOf(doctorPreview));
        List<Map<String, Object>> doctorItems = itemsOf(data(doctorPreview));
        assertEquals("ENROLL", doctorItems.get(0).get("outcome"), "默认病区患者 doctor.demo 可见");
        assertEquals(
                "NOT_FOUND", doctorItems.get(1).get("reason"), "病区医生视角越范围患者逐条 NOT_FOUND（不暴露存在）");
        assertTrue(doctorItems.get(1).get("name") == null, "越范围患者不带摘要");
    }

    // ---------------------------------------------------------------- 批量指定责任医生（A-PAT-017）

    @Test
    void batchAssignDoctorOnlyWithinWard() {
        String admin = adminToken();
        long wardA = defaultWardId();
        long wardB = createWard("W-IT-E13T07-B", "IT 虚构病区二（示例）");
        long inWardA = registerPatient(admin, "D1", today().minusDays(30), wardA);
        long inWardB = registerPatient(admin, "D2", today().minusDays(30), wardB);
        long outOfScope = registerPatientInOtherWard(admin, "D3", today().minusDays(30));
        // doctor.demo 只负责默认病区（种子 W-DEMO-1）；nurse.demo（TENANT_ADMIN）不在任何病区
        long doctorId = doctorUserId();

        // 候选：doctor-options 只出启用病区的启用医生（doctor.demo 负责默认病区）
        Map<?, ?> options =
                get("/api/hospital/followup/patients/doctor-options?wardIds=" + wardA, admin);
        assertEquals(0, codeOf(options), "候选查询失败：" + options);
        List<Map<String, Object>> doctors = itemsOf(data(options), "doctors");
        assertTrue(doctors.size() >= 1, "默认病区至少有 doctor.demo");
        assertTrue(
                doctors.stream()
                        .anyMatch(
                                d -> String.valueOf(d.get("id")).equals(String.valueOf(doctorId))),
                "doctor.demo 在候选中");

        Map<?, ?> result =
                post(
                        "/api/hospital/followup/patients/batch-assign",
                        admin,
                        Map.of(
                                "patientIds",
                                List.of(inWardA, inWardB, outOfScope),
                                "doctorId",
                                doctorId));

        assertEquals(0, codeOf(result), "批量指定失败：" + result);
        Map<?, ?> data = data(result);
        // admin 为 ALL 范围：三名患者均可见，后两名因 doctor.demo 不负责其病区逐条跳过
        assertEquals(1, ((Number) data.get("succeeded")).intValue(), "只有病区 A 患者成功");
        assertEquals(2, ((Number) data.get("skipped")).intValue());
        List<Map<String, Object>> items = itemsOf(data);
        assertEquals("SUCCESS", items.get(0).get("outcome"));
        assertEquals("DOCTOR_NOT_IN_WARD", items.get(1).get("reason"), "医生不负责病区 B（A-PAT-017）");
        assertEquals(
                "DOCTOR_NOT_IN_WARD", items.get(2).get("reason"), "医生不负责越范围病区（admin 视角同样按病区拦截）");
        assertEquals(doctorId, responsibleDoctorOf(inWardA), "responsible_user_id 落库");
        assertNullResponsible(inWardB);

        // 受限视角：doctor.demo（WARD 范围）看不到其他病区患者 → NOT_FOUND 逐条列明不暴露存在
        Map<?, ?> scoped =
                post(
                        "/api/hospital/followup/patients/batch-assign",
                        doctorToken(),
                        Map.of("patientIds", List.of(outOfScope), "doctorId", doctorId));
        assertEquals(0, codeOf(scoped));
        List<Map<String, Object>> scopedItems = itemsOf(data(scoped));
        assertEquals("NOT_FOUND", scopedItems.get(0).get("reason"), "越范围逐条列明");
        assertTrue(scopedItems.get(0).get("name") == null, "不带摘要（不暴露存在）");

        // 整体校验：医生不存在 40001、空清单 40001
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/batch-assign",
                                admin,
                                Map.of("patientIds", List.of(inWardA), "doctorId", 9_999_999L))),
                "医生不存在 40001");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/batch-assign",
                                admin,
                                Map.of("patientIds", List.of(), "doctorId", doctorId))),
                "空清单 40001");
    }

    // ---------------------------------------------------------------- 批量入组带责任医生

    @Test
    void batchEnrollWithDoctorAssignsAndSkipsCrossWard() {
        String admin = adminToken();
        long planId = createPublishedFuturePlan(admin, PLAN_CODE + "-D");
        long wardA = defaultWardId();
        long wardB = createWard("W-IT-E13T07-D", "IT 虚构病区三（示例）");
        long inWardA = registerPatient(admin, "W1", today().minusDays(30), wardA);
        long inWardB = registerPatient(admin, "W2", today().minusDays(30), wardB);
        long doctorId = doctorUserId();

        Map<?, ?> result =
                post(
                        "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                        admin,
                        Map.of("patientIds", List.of(inWardA, inWardB), "doctorId", doctorId));

        assertEquals(0, codeOf(result), "批量入组（带责任医生）失败：" + result);
        Map<?, ?> data = data(result);
        assertEquals(1, ((Number) data.get("succeeded")).intValue());
        assertEquals(1, ((Number) data.get("skipped")).intValue());
        List<Map<String, Object>> items = itemsOf(data);
        assertEquals("SUCCESS", items.get(0).get("outcome"));
        assertEquals("DOCTOR_NOT_IN_WARD", items.get(1).get("reason"), "跨病区医生逐条跳过");
        assertEquals(doctorId, responsibleDoctorOf(inWardA), "入组患者同时指定责任医生");
        assertEquals(1, enrollmentCountOf(planId), "只有病区 A 患者入组");
        // 跨病区患者未入组、未指定
        assertNullResponsible(inWardB);
    }

    // ---------------------------------------------------------------- 权限与隔离

    @Test
    void permissionsAndModuleSwitch() {
        String admin = adminToken();
        long planId = createPublishedFuturePlan(admin, PLAN_CODE + "-R");
        long patientId = registerPatient(admin, "R1", today().minusDays(30));
        long doctorId = doctorUserId();

        // 无 plan:enroll / task:assign 的角色（只有患者列表 21）
        String none = userWithoutBatchPermissions();
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                                none,
                                Map.of("patientIds", List.of(patientId)))),
                "无 followup:plan:enroll 40300");
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/batch-assign",
                                none,
                                Map.of("patientIds", List.of(patientId), "doctorId", doctorId))),
                "无 followup:task:assign 40300");
        assertEquals(
                40300,
                codeOf(get("/api/hospital/followup/patients/doctor-options", none)),
                "医生候选同口径 40300");

        // 模块关闭 40301
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    codeOf(
                            post(
                                    "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                                    admin,
                                    Map.of("patientIds", List.of(patientId)))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }

        // 双租户互不可见：demob 无该计划（独立租户库）40400
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        assertEquals(
                40400,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                                demobAdmin,
                                Map.of("patientIds", List.of(patientId)))),
                "demob 看不到 demoa 的计划（租户库隔离）");
    }

    // ---------------------------------------------------------------- 辅助

    /** 远期窗口循环节点：偏移 300 天（未来）→ 入组即建 0 补录 / 0 当期，入组侧细节不干扰批量断言。 */
    private long createPublishedFuturePlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 批量入组计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "年度随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 1);
        subplan.put("cycleUnit", "YEAR");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 300);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 7);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 14);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 14);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_annual_v1");
        node.put("sortNo", 20);
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/publish",
                                token,
                                Map.of())),
                "发布失败");
        return planId;
    }

    private long registerPatient(String token, String suffix, LocalDate discharge) {
        return registerPatient(token, suffix, discharge, defaultWardId());
    }

    private long registerPatient(String token, String suffix, LocalDate discharge, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", MRN + "-" + suffix);
        body.put("name", "周批量（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1952-03-01");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", MRN + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 登记患者到新建病区（doctor.demo 不可见，越范围用例）。 */
    private long registerPatientInOtherWard(String token, String suffix, LocalDate discharge) {
        long wardId = createWard("W-IT-E13T07-O" + suffix, "IT 越范围病区（示例 " + suffix + "）");
        return registerPatient(token, suffix, discharge, wardId);
    }

    private long createWard(String code, String name) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long enrollmentCountOf(long planId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_enrollment WHERE plan_id = ?",
                                Long.class,
                                planId));
    }

    private long responsibleDoctorOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT responsible_user_id FROM pt_patient WHERE id = ?",
                                Long.class,
                                patientId));
    }

    private void assertNullResponsible(long patientId) {
        Long doctor =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT responsible_user_id FROM pt_patient WHERE id = ?",
                                        Long.class,
                                        patientId));
        assertTrue(doctor == null, "未指定的患者 responsible_user_id 保持空");
    }

    private long doctorUserId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = 'doctor.demo'",
                                Long.class));
    }

    private long defaultWardId() {
        return runInTenant(
                "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    /** 建一个只有患者列表（21）的角色与用户，登录并改密后返回 token（无批量权限）。 */
    private String userWithoutBatchPermissions() {
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-E13T07', 'IT 无批量权限（示例）', 0, 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-E13T07'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 21)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId =
                runInTenant(
                        "demoa",
                        () -> jdbc.queryForObject("SELECT MIN(id) FROM sys_dept", Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken(),
                        Map.of(
                                "username", "IT-U-E13T07-1",
                                "displayName", "IT 无批量权限用户（示例）",
                                "phone", "13900001307",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-E13T07-1", temporary);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return token;
    }

    private static LocalDate today() {
        return PlanDateCalculator.businessToday(Instant.now());
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败：" + response);
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
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<?, ?> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<?, ?> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong((String) data(response).get("id"));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
