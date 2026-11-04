package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.app.PlanService;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
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
 * E14-T09 计划引擎回归用例·端到端（tasks.csv 验收「10 年以上年度随访用例通过」；多计划、长期年度）。场景：
 *
 * <ol>
 *   <li>长期年度（A-FU-001「N 从 1 起逐年递增、不设上限」、A-FU-017 年度预置、Q21 随访不设 10 年上限）： 2016 年出院的患者逐年随访 12
 *       年——每年开启日照常创建该年度任务（未来年度不预生成），12 次任务日期组逐次正确、 幂等，TaskOpened 事件与下次缓存指向第 13 年；
 *   <li>多计划（A 规格第 5 章「可同时加入不同计划」）：同一患者同时在年度计划与两年一次（如专病）计划， 两计划任务互不干扰、各自入组的 next 缓存独立，uk（入组 + 节点 + 第
 *       N 次）无跨计划冲突。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14T09 前缀、病案号 IT-E14T09-
 * 前缀、job_tenant_run 本作业行、TaskOpened 事件与 TASK_CREATE 审计）。全部虚构数据。 12 年逐日循环过慢：每个执行日在 job_tenant_run
 * 预置前一日 SUCCESS 台账（虚构「中间日期作业已成功执行」的稳态，与 E02-T06 补跑口径一致）， 使每次 runForDate 只处理一个逻辑日。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanEngineRegressionIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E14T09";
    private static final String PLAN_CODE = "PLAN-IT-E14T09";
    private static final LocalDate ANNUAL_BASIS = LocalDate.of(2016, 3, 15);
    private static final LocalDate MULTI_PLAN_BASIS = LocalDate.of(2020, 6, 15);

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanService planService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type LIKE 'PLAN_%'"
                                        + " AND (detail LIKE '%"
                                        + PLAN_CODE
                                        + "%'"
                                        + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE '"
                                        + PLAN_CODE
                                        + "%')))"
                                        + " OR (action = 'TASK_CREATE' AND detail LIKE '%每日调度%')"
                                        + " OR (event_type = 'PATIENT_CREATE'"
                                        + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                        + " WHERE legacy_no LIKE '"
                                        + LEGACY
                                        + "-%'))");
                        List<Long> patientIds =
                                jdbc.queryForList(
                                        "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                        Long.class,
                                        LEGACY + "-%");
                        for (Long patientId : patientIds) {
                            jdbc.update(
                                    "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                            + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                    patientId);
                        }
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                        + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                        + " WHERE p.code LIKE ?",
                                PLAN_CODE + "%");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：10 年以上年度随访用例通过

    @Test
    void annualFollowupKeepsCreatingTasksAcrossTwelveYears() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedPlan(admin, PLAN_CODE + "-A", 1, "YEAR", 1, "YEAR", 7, 7, 7);
        long patientId = registerPatient("demoa", admin, "A1", ANNUAL_BASIS);
        enroll("demoa", patientId, planId, LocalDate.of(2016, 4, 1));

        // 逐年跑开启日（第 N 次开启日 = 起算 + N 年 − 7 天）：2017-03-08 … 2028-03-08，共 12 年
        for (int year = 1; year <= 12; year++) {
            runOneLogicalDay(ANNUAL_BASIS.plusYears(year).minusDays(7));
            if (year == 5) {
                // 第 5 年创建后的年中日：未来年度不预先创建（A-FU-001），任务数停在 5
                runOneLogicalDay(LocalDate.of(2021, 9, 1));
                assertEquals(5, taskCount("demoa", patientId, planId), "年中运行不预生成未来年度");
            }
        }

        List<Map<String, Object>> tasks = tasksOf("demoa", patientId, planId);
        assertEquals(12, tasks.size(), "12 个年度（10 年以上）全部创建，不设上限（Q21）");
        for (int occurrence = 1; occurrence <= 12; occurrence++) {
            Map<String, Object> task = tasks.get(occurrence - 1);
            LocalDate planDate = ANNUAL_BASIS.plusYears(occurrence);
            assertEquals(occurrence, ((Number) task.get("occurrence")).intValue());
            assertEquals(
                    planDate, date(task.get("plan_date")), "第 " + occurrence + " 次计划日期 = 起算 + N 年");
            assertEquals(planDate.minusDays(7), date(task.get("remind_start")));
            assertEquals(planDate.minusDays(7), date(task.get("window_start")));
            assertEquals(planDate.plusDays(7), date(task.get("window_end")));
            assertEquals(planDate.minusDays(7), date(task.get("open_date")), "开启日 = 计划日期 − 7 天");
            assertEquals(
                    planDate.minusDays(7),
                    date(task.get("created_logical_date")),
                    "补跑口径：创建事实落在该次自己的开启日");
            assertEquals("NORMAL", task.get("create_type"));
            assertEquals("PENDING", task.get("status"), "12 年后创建的任务仍为待随访");
        }

        // 长期随访继续：下次缓存指向第 13 年（2029-03-15 / 03-08）
        Map<String, Object> cache = enrollmentCache("demoa", patientId, planId);
        assertEquals(LocalDate.of(2029, 3, 15), date(cache.get("next_plan_date")));
        assertEquals(LocalDate.of(2029, 3, 8), date(cache.get("next_open_date")));

        // 12 个年度各发一条 TaskOpened；重复执行幂等（任务与事件都不重复）
        assertEquals(12, taskOpenedCount("demoa", patientId));
        runOneLogicalDay(LocalDate.of(2028, 3, 8));
        assertEquals(12, taskCount("demoa", patientId, planId), "重复执行不重复创建（uk 幂等）");
        assertEquals(12, taskOpenedCount("demoa", patientId), "重复执行不重复发事件");
    }

    // ---------------------------------------------------------------- 多计划：同患者不同计划并行

    @Test
    void patientInTwoPlansGetsTasksFromBothPlansIndependently() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long annualPlanId =
                createPublishedPlan(admin, PLAN_CODE + "-M1", 1, "YEAR", 1, "YEAR", 7, 7, 7);
        long biennialPlanId =
                createPublishedPlan(admin, PLAN_CODE + "-M2", 2, "YEAR", 2, "YEAR", 14, 14, 30);
        long patientId = registerPatient("demoa", admin, "B1", MULTI_PLAN_BASIS);
        // A 规格第 5 章：同一患者不能重复加入同一计划，可同时加入不同计划（如专病计划）
        enroll("demoa", patientId, annualPlanId, LocalDate.of(2020, 7, 1));
        enroll("demoa", patientId, biennialPlanId, LocalDate.of(2020, 7, 1));

        // 两计划开启日交错：年度 = 起算 + N 年 − 7 天、两年一次 = 起算 + 2N 年 − 14 天，逐个执行日跑到第 4 年
        runOneLogicalDay(LocalDate.of(2021, 6, 8)); // 年度第 1 次
        runOneLogicalDay(LocalDate.of(2022, 6, 1)); // 两年一次第 1 次
        runOneLogicalDay(LocalDate.of(2022, 6, 8)); // 年度第 2 次
        runOneLogicalDay(LocalDate.of(2023, 6, 8)); // 年度第 3 次
        runOneLogicalDay(LocalDate.of(2024, 6, 1)); // 两年一次第 2 次（年度第 4 次开启日 06-08 未到）

        // 年度计划：第 1..3 次各一条（计划日期 2021/2022/2023-06-15），全为正常创建
        List<Map<String, Object>> annualTasks = tasksOf("demoa", patientId, annualPlanId);
        assertEquals(3, annualTasks.size(), "年度计划自己的次数照排");
        assertEquals(LocalDate.of(2021, 6, 15), date(annualTasks.get(0).get("plan_date")));
        assertEquals(LocalDate.of(2022, 6, 15), date(annualTasks.get(1).get("plan_date")));
        assertEquals(LocalDate.of(2023, 6, 15), date(annualTasks.get(2).get("plan_date")));
        assertEquals(LocalDate.of(2023, 6, 8), date(annualTasks.get(2).get("open_date")));
        for (Map<String, Object> annualTask : annualTasks) {
            assertEquals("NORMAL", annualTask.get("create_type"), "两计划并行不误伤对方开启日（否则会落到停用补偿口径）");
        }

        // 两年一次计划：第 1、2 次各一条（计划日期 2022/2024-06-15，提前 14 天、窗口前 14 后 30）
        List<Map<String, Object>> biennialTasks = tasksOf("demoa", patientId, biennialPlanId);
        assertEquals(2, biennialTasks.size(), "另一计划的次数不受年度计划影响");
        Map<String, Object> biennialFirst = biennialTasks.get(0);
        assertEquals(LocalDate.of(2022, 6, 15), date(biennialFirst.get("plan_date")));
        assertEquals(
                LocalDate.of(2022, 6, 1), date(biennialFirst.get("remind_start")), "提前 14 天提醒");
        assertEquals(
                LocalDate.of(2022, 6, 1),
                date(biennialFirst.get("open_date")),
                "开启日 = 计划日期 − 14 天");
        assertEquals(LocalDate.of(2022, 7, 15), date(biennialFirst.get("window_end")), "窗口后 30 天");
        assertEquals(LocalDate.of(2024, 6, 15), date(biennialTasks.get(1).get("plan_date")));

        // 同一患者两计划任务并存；两计划节点 id 各自独立，uk 无跨计划冲突
        assertEquals(5, taskCount("demoa", patientId, null), "两计划任务并存（年度 3 + 两年一次 2），互不覆盖");
        long annualNodeId = ((Number) annualTasks.get(0).get("node_id")).longValue();
        long biennialNodeId = ((Number) biennialFirst.get("node_id")).longValue();
        assertEquals(1, nodeBelongsToPlan("demoa", annualPlanId, annualNodeId), "年度计划用自己的节点");
        assertEquals(1, nodeBelongsToPlan("demoa", biennialPlanId, biennialNodeId), "两年一次计划用自己的节点");

        // 各入组 next 缓存独立：年度第 4 次（2024-06-15 / 06-08）、两年一次第 3 次（2026-06-15 / 06-01）
        Map<String, Object> annualCache = enrollmentCache("demoa", patientId, annualPlanId);
        assertEquals(LocalDate.of(2024, 6, 15), date(annualCache.get("next_plan_date")));
        assertEquals(LocalDate.of(2024, 6, 8), date(annualCache.get("next_open_date")));
        Map<String, Object> biennialCache = enrollmentCache("demoa", patientId, biennialPlanId);
        assertEquals(LocalDate.of(2026, 6, 15), date(biennialCache.get("next_plan_date")));
        assertEquals(LocalDate.of(2026, 6, 1), date(biennialCache.get("next_open_date")));

        // 重复执行：两计划都不重复创建
        runOneLogicalDay(LocalDate.of(2024, 6, 1));
        assertEquals(5, taskCount("demoa", patientId, null), "重复执行幂等（两计划各自 uk）");
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 只处理一个逻辑日：在前一日预置 SUCCESS 台账（虚构「中间日期作业已成功执行」稳态）， 使 runForDate 的补跑区间恰为本日；避免 12 年逐日循环拖慢用例（补跑语义与
     * E02-T06 相同）。
     */
    private void runOneLogicalDay(LocalDate logicalDate) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT IGNORE INTO job_tenant_run"
                                        + " (job_code, run_date, status, started_at, finished_at)"
                                        + " VALUES ('fu-task-create', ?, 'SUCCESS', ?, ?)",
                                logicalDate.minusDays(1),
                                logicalDate.minusDays(1).atTime(0, 10),
                                logicalDate.minusDays(1).atTime(0, 11)));
        creationService.runForDate(logicalDate);
    }

    /**
     * 持续循环子计划（周期 cycleValue × cycleUnit）+ 一个表单节点（偏移 offsetValue × offsetUnit、 提前 remindDays 天、窗口前
     * beforeDays / 后 afterDays 天），ONGOING 长期（不设结束年度，A-FU-002 / A-FU-017）。
     */
    private long createPublishedPlan(
            String token,
            String code,
            int cycleValue,
            String cycleUnit,
            int offsetValue,
            String offsetUnit,
            int remindDays,
            int beforeDays,
            int afterDays) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 回归随访计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "循环随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", cycleValue);
        subplan.put("cycleUnit", cycleUnit);
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", offsetValue);
        node.put("offsetUnit", offsetUnit);
        node.put("remindValue", remindDays);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", beforeDays);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", afterDays);
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

    /** 登记患者（E13-T01 接口；legacy_no 按租户后缀便于自清）。 */
    private long registerPatient(String tenant, String token, String suffix, LocalDate discharge) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E14T09-" + suffix);
        body.put("name", "孙示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1950-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix + "-" + tenant);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 服务层入组并把 created_at 回拨到入组早期（模拟「入组已久、调度逐年推进」的真实时序）； 回拨后清掉入组即建的任务与事件（E14-T05）。 */
    private long enroll(String tenant, long patientId, long planId, LocalDate backdateTo) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                            backdateTo.atStartOfDay(),
                            enrollmentId);
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                    + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                            patientId);
                    return enrollmentId;
                });
    }

    /** planId 为 null = 该患者全部任务（多计划并存断言用）。 */
    private int taskCount(String tenant, long patientId, Long planId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?"
                                        + (planId != null ? " AND plan_id = " + planId : ""),
                                Integer.class,
                                patientId));
    }

    private List<Map<String, Object>> tasksOf(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForList(
                                "SELECT * FROM fu_task WHERE patient_id = ? AND plan_id = ?"
                                        + " ORDER BY occurrence",
                                patientId,
                                planId));
    }

    /** 任务行的节点是否登记在该计划名下（节点 id 全局唯一 → 多计划不串节点）。 */
    private int nodeBelongsToPlan(String tenant, long planId, long nodeId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ? AND id = ?",
                                Integer.class,
                                planId,
                                nodeId));
    }

    private Map<String, Object> enrollmentCache(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForMap(
                                "SELECT next_plan_date, next_open_date FROM fu_enrollment"
                                        + " WHERE patient_id = ? AND plan_id = ?",
                                patientId,
                                planId));
    }

    private int taskOpenedCount(String tenant, long patientId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                Integer.class,
                                patientId));
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    /** JDBC 取回的 DATE 列统一换算 LocalDate 再断言（java.sql.Date 与 LocalDate 不 equals）。 */
    private static LocalDate date(Object value) {
        if (value instanceof LocalDate local) {
            return local;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        throw new IllegalArgumentException("不是日期列：" + value);
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
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
