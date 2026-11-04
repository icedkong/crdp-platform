package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.app.StartDateRecalculationService;
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
 * E14-T07 端到端（tasks.csv 验收「改出院日期后未开始任务重算」；A-FU-011）。场景：
 *
 * <ol>
 *   <li>改出院日期（患者起算字段）后：入组起算快照更新、PENDING（尚无联系记录）任务按新日期重排窗口、 下次计划日期 / 开启日缓存实时刷新、写一条
 *       PLAN_START_DATE_RECALC 审计；
 *   <li>已完成（COMPLETED）与进行中（IN_PROGRESS——已有联系 / 录入口径，E15-T01 起由状态流转置位， 本用例 SQL 模拟该状态）任务原样不动；
 *   <li>新窗口尚未开启：未开始任务撤回（删行，时间轴回到「计划中 / 待开始」，A-FU-003 该显示态不对应实际任务）， 每日调度到新开启日照常按新日期重建；
 *   <li>补录（BACKFILL）任务同为未开始，窗口一并重算。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14T07 前缀、 病案号 IT-E14T07-
 * 前缀）。全部虚构数据。 患者基础信息编辑接口（PUT /patients/{id}，patient.md §2）随 E13-T02；本用例直接改列后调领域入口（与 E14-T06 死亡终止 IT
 * 同口径，OQ-81①）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartDateRecalculationIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E14T07";
    private static final String PLAN_CODE = "PLAN-IT-E14T07";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private StartDateRecalculationService recalculationService;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
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
                                    + " OR action = 'PLAN_START_DATE_RECALC'"
                                    + " OR (action = 'TASK_OVERDUE_MARK'"
                                    + " AND detail LIKE '%每日逾期刷新%')"
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
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE event_type = 'TASK_OVERDUE'"
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

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：未开始任务重排 + 缓存实时刷新

    @Test
    void dischargeDateChangeReschedulesUnstartedTaskAndRefreshesCache() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");
        long patientId = registerPatient("demoa", admin, "A1", "2025-03-15");
        long enrollmentId = enroll("demoa", patientId, planId, LocalDate.of(2025, 4, 1));

        // 旧起算 2025-03-15：第 1 次计划日期 2026-03-15，开启日 2026-03-08 创建 PENDING 任务
        creationService.runForDate(LocalDate.of(2026, 3, 8));
        Map<String, Object> before = taskOf("demoa", patientId);
        assertEquals("PENDING", before.get("status"));
        assertEquals(LocalDate.of(2026, 3, 15), date(before.get("plan_date")));
        Object assigneeBefore = before.get("assignee_id");

        // 改出院日期：→ 一年前（第 1 次新计划日期落在今天、窗口已开启 → 重排；不是撤回）
        LocalDate today = LocalDate.now();
        LocalDate newDischarge = today.minusYears(1);
        jdbcUpdate(
                "demoa",
                "UPDATE pt_patient SET discharge_date = ?, version = version + 1 WHERE id = ?",
                newDischarge,
                patientId);

        StartDateRecalculationService.RecalculationView view =
                runInTenant(
                        "demoa",
                        () -> recalculationService.recalculateOnBaseDateChange(patientId, 55L));

        Map<String, Object> after = taskOf("demoa", patientId);
        assertEquals(newDischarge.plusYears(1), date(after.get("plan_date")), "计划日期按新起算重排（验收）");
        assertEquals(newDischarge.plusYears(1).minusDays(7), date(after.get("remind_start")));
        assertEquals(newDischarge.plusYears(1).minusDays(7), date(after.get("window_start")));
        assertEquals(newDischarge.plusYears(1).plusDays(7), date(after.get("window_end")));
        assertEquals(newDischarge.plusYears(1).minusDays(7), date(after.get("open_date")));
        assertEquals("PENDING", after.get("status"), "状态不变，只重排日期组");
        assertEquals("NORMAL", after.get("create_type"));
        assertEquals(assigneeBefore, after.get("assignee_id"), "负责人不变");
        assertEquals(LocalDate.of(2026, 3, 8), date(after.get("created_logical_date")), "创建事实不回写");

        // 起算快照与下次计划日期 / 开启日缓存实时按新日期（下次 = 第 2 次）
        Map<String, Object> enrollment =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT start_date, next_plan_date, next_open_date"
                                                + " FROM fu_enrollment WHERE id = ?",
                                        enrollmentId));
        assertEquals(newDischarge, date(enrollment.get("start_date")), "起算日期快照更新");
        assertEquals(
                newDischarge.plusYears(2), date(enrollment.get("next_plan_date")), "下次计划日期实时重算");
        assertEquals(
                newDischarge.plusYears(2).minusDays(7), date(enrollment.get("next_open_date")));

        assertEquals(1, view.enrollments());
        assertEquals(1, view.rescheduled());
        assertEquals(0, view.withdrawn());
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log"
                                                + " WHERE action = 'PLAN_START_DATE_RECALC'",
                                        Integer.class)),
                "写一条重算审计");
    }

    // ---------------------------------------------------------------- 已完成 / 进行中（已有联系）不动

    @Test
    void completedAndContactedTasksAreUntouched() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-C");
        long patientId = registerPatient("demoa", admin, "C1", "2025-03-15");
        long enrollmentId = enroll("demoa", patientId, planId, LocalDate.of(2025, 4, 1));

        // 第 1、2 次均已创建；第 1 次置已完成、第 2 次置进行中（SQL 模拟 E15-T01 的流转结果）
        creationService.runForDate(LocalDate.of(2026, 3, 8));
        creationService.runForDate(LocalDate.of(2027, 3, 8));
        List<Map<String, Object>> tasks = tasksOf("demoa", patientId);
        assertEquals(2, tasks.size());
        long completedId = idOfOccurrence(tasks, 1);
        long inProgressId = idOfOccurrence(tasks, 2);
        jdbcUpdate("demoa", "UPDATE fu_task SET status = 'COMPLETED' WHERE id = ?", completedId);
        jdbcUpdate("demoa", "UPDATE fu_task SET status = 'IN_PROGRESS' WHERE id = ?", inProgressId);

        LocalDate today = LocalDate.now();
        LocalDate newDischarge = today.minusYears(2).minusDays(20);
        jdbcUpdate(
                "demoa",
                "UPDATE pt_patient SET discharge_date = ?, version = version + 1 WHERE id = ?",
                newDischarge,
                patientId);
        StartDateRecalculationService.RecalculationView view =
                runInTenant(
                        "demoa",
                        () -> recalculationService.recalculateOnBaseDateChange(patientId, 55L));

        Map<String, Object> completed =
                runInTenant(
                        "demoa",
                        () -> jdbc.queryForMap("SELECT * FROM fu_task WHERE id = ?", completedId));
        assertEquals("COMPLETED", completed.get("status"));
        assertEquals(
                LocalDate.of(2026, 3, 15), date(completed.get("plan_date")), "已完成任务不变（A-FU-011）");
        assertEquals(LocalDate.of(2026, 3, 8), date(completed.get("window_start")));
        Map<String, Object> inProgress =
                runInTenant(
                        "demoa",
                        () -> jdbc.queryForMap("SELECT * FROM fu_task WHERE id = ?", inProgressId));
        assertEquals("IN_PROGRESS", inProgress.get("status"));
        assertEquals(
                LocalDate.of(2027, 3, 15), date(inProgress.get("plan_date")), "进行中（已有联系记录）任务不变");

        // 快照与缓存照常刷新（下次 = 第 3 次，前两次开启日均已过）
        Map<String, Object> enrollment =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT start_date, next_plan_date FROM fu_enrollment"
                                                + " WHERE id = ?",
                                        enrollmentId));
        assertEquals(newDischarge, date(enrollment.get("start_date")));
        assertEquals(newDischarge.plusYears(3), date(enrollment.get("next_plan_date")));

        assertEquals(0, view.rescheduled(), "没有未开始任务被重排");
        assertEquals(0, view.withdrawn());
        assertEquals(1, view.enrollments());
    }

    // ---------------------------------------------------------------- 新窗口未开启 → 撤回为计划中，调度到点重建

    @Test
    void unopenedNewWindowWithdrawsTaskAndSchedulerRecreatesIt() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-W");
        long patientId = registerPatient("demoa", admin, "W1", "2025-03-15");
        enroll("demoa", patientId, planId, LocalDate.of(2025, 4, 1));

        creationService.runForDate(LocalDate.of(2026, 3, 8));
        assertEquals(1, taskCount("demoa", patientId), "第 1 次已按旧日期创建");

        // 出院日期改到今天：第 1 次新开启日在未来 → 撤回（删行，时间轴回「计划中 / 待开始」）
        LocalDate newDischarge = LocalDate.now();
        jdbcUpdate(
                "demoa",
                "UPDATE pt_patient SET discharge_date = ?, version = version + 1 WHERE id = ?",
                newDischarge,
                patientId);
        StartDateRecalculationService.RecalculationView view =
                runInTenant(
                        "demoa",
                        () -> recalculationService.recalculateOnBaseDateChange(patientId, 55L));

        assertEquals(1, view.withdrawn(), "新窗口尚未开启 → 撤回为计划中");
        assertEquals(0, view.rescheduled());
        assertEquals(0, taskCount("demoa", patientId), "任务行已删（「待开始」不对应实际任务，A-FU-003）");
        Map<String, Object> cache =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT start_date, next_plan_date FROM fu_enrollment"
                                                + " WHERE patient_id = ?",
                                        patientId));
        assertEquals(newDischarge, date(cache.get("start_date")));
        assertEquals(newDischarge.plusYears(1), date(cache.get("next_plan_date")), "下次计划日期按新日期");

        // 每日调度到新开启日：照常按新日期重建（uk 幂等口径不破坏）
        LocalDate newOpen = newDischarge.plusYears(1).minusDays(7);
        creationService.runForDate(newOpen);
        Map<String, Object> recreated = taskOf("demoa", patientId);
        assertEquals(newDischarge.plusYears(1), date(recreated.get("plan_date")), "按新日期重建");
        assertEquals(newDischarge.plusYears(1).minusDays(7), date(recreated.get("open_date")));
        assertEquals("PENDING", recreated.get("status"));
        assertEquals(newOpen, date(recreated.get("created_logical_date")), "重建行记自己的开启日");
    }

    // ---------------------------------------------------------------- 补录任务（BACKFILL）同为未开始，一并重算

    @Test
    void backfillPendingTaskIsRescheduledToo() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-B");
        long patientId = registerPatient("demoa", admin, "B1", "2025-03-15");
        enroll("demoa", patientId, planId, LocalDate.of(2025, 4, 1));

        creationService.runForDate(LocalDate.of(2026, 3, 8));
        // 把第 1 次改成补录创建（E14-T05 的 BACKFILL 形态，仍为 PENDING 待随访）
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET create_type = 'BACKFILL' WHERE patient_id = ? AND occurrence = 1",
                patientId);

        LocalDate newDischarge = LocalDate.now().minusYears(2).plusDays(10);
        jdbcUpdate(
                "demoa",
                "UPDATE pt_patient SET discharge_date = ?, version = version + 1 WHERE id = ?",
                newDischarge,
                patientId);
        runInTenant(
                "demoa",
                () -> {
                    recalculationService.recalculateOnBaseDateChange(patientId, 55L);
                    return null;
                });

        Map<String, Object> backfill =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT * FROM fu_task WHERE patient_id = ? AND occurrence = 1",
                                        patientId));
        LocalDate newPlan = newDischarge.plusYears(1);
        assertEquals("BACKFILL", backfill.get("create_type"), "创建方式不变");
        assertEquals(newPlan, date(backfill.get("plan_date")), "补录任务窗口一并按新日期重算");
        assertEquals(newPlan.minusDays(7), date(backfill.get("window_start")));
        assertEquals(newPlan.plusDays(7), date(backfill.get("window_end")));
    }

    // ---------------------------------------------------------------- 辅助

    /** A 年度形态（A-FU-017 预置同款参数：+1 年、提前 7 天、窗口 ±7 天），无补录策略。 */
    private long createPublishedAnnualPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 年度随访计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "循环随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 1);
        subplan.put("cycleUnit", "YEAR");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 1);
        node.put("offsetUnit", "YEAR");
        node.put("remindValue", 7);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 7);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 7);
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

    /** 登记患者（E13-T01 接口；legacy_no 便于自清）。 */
    private long registerPatient(String tenant, String token, String suffix, String dischargeDate) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E14T07-" + suffix);
        body.put("name", "孙示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1948-01-01");
        body.put("wardId", wardId);
        LocalDate discharge = LocalDate.parse(dischargeDate);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", dischargeDate);
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix + "-" + tenant);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 服务层入组并把 created_at 回拨（清掉入组即建的任务与事件，模拟「入组已久」时序）。 */
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

    private void jdbcUpdate(String tenant, String sql, Object... args) {
        runInTenant(
                tenant,
                () -> {
                    jdbc.update(sql, args);
                    return null;
                });
    }

    private int taskCount(String tenant, long patientId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?",
                                Integer.class,
                                patientId));
    }

    private Map<String, Object> taskOf(String tenant, long patientId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForMap(
                                "SELECT * FROM fu_task WHERE patient_id = ? ORDER BY occurrence LIMIT 1",
                                patientId));
    }

    private List<Map<String, Object>> tasksOf(String tenant, long patientId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForList(
                                "SELECT * FROM fu_task WHERE patient_id = ? ORDER BY occurrence",
                                patientId));
    }

    private static long idOfOccurrence(List<Map<String, Object>> tasks, int occurrence) {
        return tasks.stream()
                .filter(task -> ((Number) task.get("occurrence")).intValue() == occurrence)
                .map(task -> ((Number) task.get("id")).longValue())
                .findFirst()
                .orElseThrow();
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
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
