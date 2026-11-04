package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.app.PlanService;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
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
 * E14-T04 端到端（tasks.csv 验收：任务在提醒开始日与窗口开始日较早一天创建）。场景：
 *
 * <ol>
 *   <li>开启日创建（A-FU-001、C-FU-004）：开启日前一天跑 → 不建；开启日当天跑 → PENDING / NORMAL 任务一条， 日期组齐全（开启日 =
 *       min(提醒开始日, 窗口开始日)）；重复执行幂等（uk 入组 + 节点 + 第 N 次）；job_tenant_run 记 SUCCESS、 TaskOpened 事件入
 *       outbox、TASK_CREATE 审计一条；入组缓存下次计划日期 / 开启日；
 *   <li>补跑（E02-T06 框架 + 本任务）：先跑到 D1，再直接跑 D2 → 从上次成功日 + 1 逐日推进，错过日期的次数全部补建， created_logical_date
 *       落各次自己的开启日；
 *   <li>入组前已结束窗口不属每日调度（留给 E14-T05 的 passed_window_action）：旧起算日期入组当天跑 → 0 任务， 缓存指向首个未开启次数；
 *   <li>followup 模块关闭的租户跳过（OQ-46② 模块作业扇出过滤）；
 *   <li>双租户各自独立建任务（每院独立库）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14T04 前缀、 病案号 IT-E14T04-
 * 前缀、job_tenant_run 本作业行、TaskOpened 事件与 TASK_CREATE 审计）。全部虚构数据。 入组行 created_at
 * 回拨到入组早期，模拟「入组已久、调度逐日推进」的真实时序。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowupTaskSchedulerIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E14T04";
    private static final String PLAN_CODE = "PLAN-IT-E14T04";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PlanService planService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 审计先行（target 子查询要用还没删的行）：计划 / 入组 / 任务 / 患者登记四类
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
                        // TaskOpened 事件按患者 id 清（payload 为 JSON，无法 join）
                        List<Long> patientIds =
                                jdbc.queryForList(
                                        "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                        Long.class,
                                        LEGACY + "-%");
                        for (Long patientId : patientIds) {
                            // payload 是 JSON 列（MySQL 归一化：键排序 + 冒号后带空格），LIKE 不可靠，用 JSON_EXTRACT
                            jdbc.update(
                                    "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                            + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                    patientId);
                        }
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        // 本作业执行台账整段清（含应用启动补跑写入的当天行，避免污染补跑区间）
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

    // ---------------------------------------------------------------- 验收：开启日创建 + 幂等 + 台账 / 事件 / 审计

    @Test
    void createsPendingTaskExactlyOnOpenDateAndIsIdempotent() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");
        long patientId = registerPatient("demoa", admin, "A1", "2025-03-15");
        long enrollmentId = enroll("demoa", patientId, planId, LocalDate.of(2025, 4, 1));

        // 开启日（2026-03-08 = 计划日期 2026-03-15 − 7 天）前一天：不创建
        creationService.runForDate(LocalDate.of(2026, 3, 7));
        assertEquals(0, taskCount("demoa", patientId), "开启日未到不创建（未来节点不预生成）");

        // 开启日当天：创建一条 PENDING / NORMAL
        creationService.runForDate(LocalDate.of(2026, 3, 8));
        Map<String, Object> task = taskOf("demoa", patientId);
        assertEquals("PENDING", task.get("status"), "创建即待随访（A-FU-003）");
        assertEquals("NORMAL", task.get("create_type"));
        assertEquals(1, ((Number) task.get("occurrence")).intValue());
        assertEquals(LocalDate.of(2026, 3, 15), date(task.get("plan_date")));
        assertEquals(LocalDate.of(2026, 3, 8), date(task.get("remind_start")));
        assertEquals(LocalDate.of(2026, 3, 8), date(task.get("window_start")));
        assertEquals(LocalDate.of(2026, 3, 22), date(task.get("window_end")));
        assertEquals(
                LocalDate.of(2026, 3, 8), date(task.get("open_date")), "开启日 = 提醒开始日与窗口开始日较早一天");
        assertEquals(LocalDate.of(2026, 3, 8), date(task.get("created_logical_date")));
        assertEquals("FORM", task.get("task_type"));
        assertEquals("fu_annual_v1", task.get("form_code"));

        // 执行台账 SUCCESS + outbox 事件 + 审计一条
        assertEquals(
                "SUCCESS",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status FROM job_tenant_run"
                                                + " WHERE job_code = 'fu-task-create'"
                                                + " AND run_date = '2026-03-08'",
                                        String.class)));
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox"
                                                + " WHERE event_type = 'TaskOpened'"
                                                + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                        Integer.class,
                                        patientId)),
                "新建任务写 TaskOpened 事件");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log"
                                                + " WHERE action = 'TASK_CREATE'"
                                                + " AND detail LIKE '%每日调度%'",
                                        Integer.class)),
                "全租户汇总写一条 TASK_CREATE 审计");

        // 入组缓存：下次（第 2 次）计划日期与开启日
        Map<String, Object> cache =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT next_plan_date, next_open_date FROM fu_enrollment"
                                                + " WHERE id = ?",
                                        enrollmentId));
        assertEquals(LocalDate.of(2027, 3, 15), date(cache.get("next_plan_date")));
        assertEquals(LocalDate.of(2027, 3, 8), date(cache.get("next_open_date")));

        // 重复执行：幂等，不重复建任务也不重复发事件
        creationService.runForDate(LocalDate.of(2026, 3, 8));
        assertEquals(1, taskCount("demoa", patientId), "uk（入组 + 节点 + 第 N 次）幂等");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox"
                                                + " WHERE event_type = 'TaskOpened'"
                                                + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                        Integer.class,
                                        patientId)));
    }

    // ---------------------------------------------------------------- 补跑

    @Test
    void catchUpCreatesMissedDaysFromLastSuccessDay() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedPlan(admin, PLAN_CODE + "-M", "MONTH", 1);
        long patientId = registerPatient("demoa", admin, "M1", "2026-01-15");
        enroll("demoa", patientId, planId, LocalDate.of(2026, 1, 20));

        // 先跑到 02-08：第 1 次创建
        creationService.runForDate(LocalDate.of(2026, 2, 8));
        assertEquals(1, taskCount("demoa", patientId));

        // 直接跳到 04-10（模拟停机错过 02-09..04-10）：从上次成功日 + 1 逐日推进补建第 2、3 次
        creationService.runForDate(LocalDate.of(2026, 4, 10));
        List<Map<String, Object>> tasks = tasksOf("demoa", patientId);
        assertEquals(3, tasks.size(), "补跑补齐开启日已到的全部次数");
        assertEquals(2, ((Number) tasks.get(1).get("occurrence")).intValue());
        assertEquals(LocalDate.of(2026, 3, 15), date(tasks.get(1).get("plan_date")));
        assertEquals(
                LocalDate.of(2026, 3, 8),
                date(tasks.get(1).get("created_logical_date")),
                "补建落在自己的开启日");
        assertEquals(3, ((Number) tasks.get(2).get("occurrence")).intValue());
        assertEquals(LocalDate.of(2026, 4, 8), date(tasks.get(2).get("created_logical_date")));

        // 缓存指向第 4 次（2026-05-15 / 05-08）
        Map<String, Object> cache =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT next_plan_date, next_open_date FROM fu_enrollment"
                                                + " WHERE patient_id = ?",
                                        patientId));
        assertEquals(LocalDate.of(2026, 5, 15), date(cache.get("next_plan_date")));
        assertEquals(LocalDate.of(2026, 5, 8), date(cache.get("next_open_date")));
    }

    // ---------------------------------------------------------------- 入组前已过窗口（E14-T05 领地）

    @Test
    void windowsEndedBeforeEnrollmentAreNotCreatedByDailyScheduler() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedPlan(admin, PLAN_CODE + "-O", "MONTH", 1);
        long patientId = registerPatient("demoa", admin, "O1", "2024-01-15");
        // 入组即今天（不回拨 created_at）：全部历史次数窗口都已在入组前结束；节点未配置补录策略（NULL → 跳过）
        enroll("demoa", patientId, planId, null);
        int atEnrollment = taskCount("demoa", patientId);

        creationService.runForDate(LocalDate.now());

        // 入组当天恰落在某个窗口内时（0 或 1 条当期任务）调度不重复建；入组前已结束窗口的次数一律不建
        assertEquals(
                atEnrollment,
                taskCount("demoa", patientId),
                "入组前已过窗口交给节点策略（E14-T05 补录 / 跳过），调度不重复建");
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task t JOIN fu_enrollment e"
                                                + " ON e.id = t.enrollment_id"
                                                + " WHERE t.patient_id = ?"
                                                + " AND t.window_end < DATE(e.created_at)",
                                        Integer.class,
                                        patientId)),
                "没有任何一条任务来自入组前已结束的窗口");
        Map<String, Object> cache =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT next_plan_date, next_open_date FROM fu_enrollment"
                                                + " WHERE patient_id = ?",
                                        patientId));
        // 下次 = 首个开启日在今天之后的次数（基准 2024-01-15 按月推进）
        LocalDate expectedPlan = LocalDate.of(2024, 1, 15).plusMonths(1);
        while (!expectedPlan.minusDays(7).isAfter(LocalDate.now())) {
            expectedPlan = expectedPlan.plusMonths(1);
        }
        assertEquals(expectedPlan, date(cache.get("next_plan_date")), "缓存指向首个未开启次数");
        assertEquals(expectedPlan.minusDays(7), date(cache.get("next_open_date")));
    }

    // ---------------------------------------------------------------- 模块闸门（OQ-46②）

    @Test
    void tenantWithClosedFollowupModuleIsSkipped() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-X");
        long patientId = registerPatient("demoa", admin, "X1", "2025-03-15");
        enroll("demoa", patientId, planId, LocalDate.of(2025, 4, 1));
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            creationService.runForDate(LocalDate.of(2026, 3, 8));
            assertEquals(0, taskCount("demoa", patientId), "模块关闭的租户不创建任务");
            // 框架仍记执行台账（SUCCESS = 作业体正常跳过，非失败），业务上以 0 任务体现闸门
            assertEquals(
                    "SUCCESS",
                    runInTenant(
                            "demoa",
                            () ->
                                    jdbc.queryForObject(
                                            "SELECT status FROM job_tenant_run"
                                                    + " WHERE job_code = 'fu-task-create'"
                                                    + " AND run_date = '2026-03-08'",
                                            String.class)),
                    "跳过不是失败（job_tenant_run 记 SUCCESS）");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        // 恢复后再跑：照常创建
        creationService.runForDate(LocalDate.of(2026, 3, 8));
        assertEquals(1, taskCount("demoa", patientId), "模块恢复后照常创建");
    }

    // ---------------------------------------------------------------- 双租户隔离

    @Test
    void tasksAreCreatedPerTenantIndependently() {
        String demoaAdmin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        long planA = createPublishedAnnualPlan(demoaAdmin, PLAN_CODE + "-T");
        long planB = createPublishedAnnualPlan(demobAdmin, PLAN_CODE + "-T");
        long patientA = registerPatient("demoa", demoaAdmin, "T1", "2025-03-15");
        long patientB = registerPatient("demob", demobAdmin, "T1", "2025-03-15");
        enroll("demoa", patientA, planA, LocalDate.of(2025, 4, 1));
        enroll("demob", patientB, planB, LocalDate.of(2025, 4, 1));

        creationService.runForDate(LocalDate.of(2026, 3, 8));

        assertEquals(1, taskCount("demoa", patientA), "demoa 建自己的任务");
        assertEquals(1, taskCount("demob", patientB), "demob 建自己的任务（每院独立库）");
        assertEquals(
                0,
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task t JOIN pt_patient p"
                                                + " ON p.id = t.patient_id WHERE p.legacy_no = ?",
                                        Integer.class,
                                        LEGACY + "-T1-demoa")),
                "demoa 患者任务不出现在 demob");
    }

    // ---------------------------------------------------------------- 辅助

    /** A 年度形态（A-FU-017 预置同款参数：+1 年、提前 7 天、窗口 ±7 天）。 */
    private long createPublishedAnnualPlan(String token, String code) {
        return createPublishedPlan(token, code, "YEAR", 1);
    }

    /** 持续循环子计划（周期 unit × value）+ 一个表单节点（+1 unit、提前 7 天、窗口 ±7 天）。 */
    private long createPublishedPlan(String token, String code, String cycleUnit, int cycleValue) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 随访计划（" + code + "）");
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
        node.put("offsetValue", 1);
        node.put("offsetUnit", cycleUnit);
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

    /** 登记患者（E13-T01 接口；legacy_no 按租户后缀便于自清与隔离断言）。 */
    private long registerPatient(String tenant, String token, String suffix, String dischargeDate) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E14T04-" + suffix);
        body.put("name", "钱示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1950-06-01");
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

    /**
     * 服务层入组并把 created_at 回拨到入组早期（null = 保持今天，用于「入组即已过窗口」场景）。 回拨场景另清掉入组即建的任务与事件（E14-T05 起入组当天即建补录 /
     * 当期任务），保持「入组已久、调度逐日推进」的时序语义。
     */
    private long enroll(String tenant, long patientId, long planId, LocalDate backdateTo) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    if (backdateTo != null) {
                        jdbc.update(
                                "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                                backdateTo.atStartOfDay(),
                                enrollmentId);
                        jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                patientId);
                    }
                    return enrollmentId;
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
                                "SELECT * FROM fu_task WHERE patient_id = ?"
                                        + " ORDER BY occurrence LIMIT 1",
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
