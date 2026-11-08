package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.dashboard.app.DashboardKpiService;
import com.crdp.followup.dashboard.app.DashboardTrendService;
import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.app.PlanService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E19-T02 端到端（tasks.csv 验收「数字与列表钻取一致」）：首页指标卡 / 趋势图卡片接口 与列表接口在同一数据上总数一致（A 规格 §9 验收：患者总数 =
 * 患者列表条数、当前逾期 = 逾期任务列表、趋势图「出院日期 · 季度 · 按病区分组」各季度合计 = 按出院日期筛选的患者列表）。
 *
 * <p>虚构数据（demoa）：5 名患者（在随访 2、死亡 / 终止 / 失访各 1），2 名入组并各挂一条任务（一条本月 PENDING → 本月应随访、 一条 OVERDUE →
 * 当前逾期），1 条近 30 天含标红的已提交记录；钻取参数（tab=all&status=…、dueFrom/dueTo）与列表检索条件逐项对齐。
 *
 * <p>另覆盖：无权限 40300（回收 FOLLOWUP_DOCTOR 的 followup:dashboard:view 后变更即生效）、数据范围外会话全 0 不报错、 双租户隔离（demob
 * 独立计数）。门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（前缀 IT-E19T02 / PLAN-IT-E19T02）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardCardsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T02";
    private static final String PLAN_CODE = "PLAN-IT-E19T02";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanService planService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private DashboardKpiService kpiService;
    @Autowired private DashboardTrendService trendService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type LIKE 'PLAN_%'"
                                        + " AND (detail LIKE '%"
                                        + PLAN_CODE
                                        + "%'"
                                        + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE '"
                                        + PLAN_CODE
                                        + "%')))");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
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
                                "DELETE FROM pt_status_history WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：数字与列表钻取一致

    @Test
    void kpiNumbersMatchListTotalsAndDrillFilters() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");

        // 在随访 2 名（其一入组挂本月 PENDING 任务）；死亡 / 终止 / 失访各 1 名
        long activeMonthDue = registerPatient("demoa", admin, "F1", "2025-06-10");
        long activePlain = registerPatient("demoa", admin, "F2", today.toString());
        long dead = registerPatient("demoa", admin, "F3", "2025-08-15");
        long terminated = registerPatient("demoa", admin, "F4", "2025-12-05");
        long lost = registerPatient("demoa", admin, "F5", "2026-02-20");
        setStatus("demoa", dead, "DEAD");
        setStatus("demoa", terminated, "TERMINATED");
        setStatus("demoa", lost, "LOST");

        // 入组 + 直接落两条任务：F1 本月 PENDING（本月应随访）、F2 上月 OVERDUE（当前逾期）
        long enrollmentMonthDue = enroll("demoa", activeMonthDue, planId);
        long enrollmentOverdue = enroll("demoa", activePlain, planId);
        long nodeId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT MIN(n.id) FROM fu_plan_node n JOIN fu_plan p"
                                                + " ON p.id = n.plan_id WHERE p.code = ?",
                                        Long.class,
                                        PLAN_CODE + "-A"));
        insertTask("demoa", enrollmentMonthDue, activeMonthDue, planId, nodeId, today, "PENDING");
        insertTask(
                "demoa",
                enrollmentOverdue,
                activePlain,
                planId,
                nodeId,
                today.withDayOfMonth(1).minusDays(10),
                "OVERDUE");

        // 近 30 天含标红参数的已提交记录（C-DASH-001；highlight 由 E17-T04 复算落列）
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO fu_record (patient_id, source, form_code,"
                                    + " template_version, status, highlight)"
                                    + " VALUES (?, 'BACKFILL', 'fu_annual_v1', 1, 'SUBMITTED',"
                                    + " '[{\"key\":\"f010\",\"message\":\"示例标红（示例）\"}]')",
                            activePlain);
                    jdbc.update(
                            "INSERT INTO fu_record (patient_id, source, form_code,"
                                    + " template_version, status)"
                                    + " VALUES (?, 'BACKFILL', 'fu_annual_v1', 1, 'SUBMITTED')",
                            activeMonthDue);
                });

        Map<?, ?> kpi = data(get(cardPath("kpi"), admin));
        assertEquals("A", kpi.get("style"), "demoa 未开 implant → 医院 A 指标集");
        Map<String, Object> metrics = metricsByCode(kpi);

        // 患者总数 = 患者列表（无筛选）总数（A 规格 §9 验收第一条）
        long patientTotal = searchTotal(admin, "/api/hospital/followup/patients/search", Map.of());
        assertEquals(
                patientTotal,
                number(metrics.get("total")),
                "患者总数与患者列表条数一致（kpi=" + number(metrics.get("total")) + "）");
        assertTrue(patientTotal >= 5, "含本用例 5 名虚构患者，实际总数 " + patientTotal);
        assertEquals(2L, number(metrics.get("active")));
        assertEquals(1L, number(metrics.get("dead")));
        assertEquals(1L, number(metrics.get("terminated")));
        assertEquals(1L, number(metrics.get("lost")));

        // 钻取筛选下的列表总数 = 指标数字（status 筛选）
        assertEquals(
                searchTotal(
                        admin,
                        "/api/hospital/followup/patients/search",
                        Map.of("filters", Map.of("status", List.of("DEAD")))),
                number(metrics.get("dead")),
                "已死亡 = 患者列表 status=DEAD 总数");

        // 本月应随访：计划日期在本月的常规任务（口径：任务列表计划日期窗口）
        assertEquals(1L, number(metrics.get("monthDue")));
        Map<?, ?> monthDueDrill = (Map<?, ?>) ((Map<?, ?>) metrics.get("monthDue")).get("drill");
        assertEquals("TASK_LIST", monthDueDrill.get("target"));
        assertEquals(
                today.withDayOfMonth(1).toString(),
                ((Map<?, ?>) monthDueDrill.get("query")).get("dueFrom"),
                "钻取带本月起始日");
        assertEquals(
                today.withDayOfMonth(today.lengthOfMonth()).toString(),
                ((Map<?, ?>) monthDueDrill.get("query")).get("dueTo"),
                "钻取带本月截止日");

        // 当前逾期 = 任务列表 status=OVERDUE&scope=ALL 总数（A 规格 §9 验收「点击当前逾期进入已筛选好的逾期任务列表」）
        long overdueTotal =
                searchTotal(
                        admin,
                        "/api/hospital/followup/tasks/search",
                        Map.of("scope", "ALL", "status", List.of("OVERDUE")));
        assertEquals(overdueTotal, number(metrics.get("overdue")), "当前逾期与任务列表总数一致");
        assertTrue(overdueTotal >= 1);
        // 钻取参数与列表检索一致（tab=all + status）
        Map<?, ?> overdueDrill = (Map<?, ?>) ((Map<?, ?>) metrics.get("overdue")).get("drill");
        assertEquals("TASK_LIST", overdueDrill.get("target"));
        assertEquals("all", ((Map<?, ?>) overdueDrill.get("query")).get("tab"));
        assertEquals("OVERDUE", ((Map<?, ?>) overdueDrill.get("query")).get("status"));

        // C 指标集（implant 开）：近 30 天标红 = 1，ERI / 扫描件占位不出数
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
        try {
            Map<?, ?> cKpi = data(get(cardPath("kpi"), admin));
            assertEquals("C", cKpi.get("style"));
            Map<String, Object> cMetrics = metricsByCode(cKpi);
            assertEquals(1L, number(cMetrics.get("highlightRecent")), "近 30 天含标红参数的记录数");
            assertEquals(false, ((Map<?, ?>) cMetrics.get("eriNear")).get("available"));
            assertEquals(null, ((Map<?, ?>) cMetrics.get("eriNear")).get("value"));
            assertEquals(false, ((Map<?, ?>) cMetrics.get("scanPending")).get("available"));
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        }
    }

    // ---------------------------------------------------------------- 验收：趋势图各周期合计与患者列表一致

    @Test
    void trendQuarterWardTotalsMatchDischargeFilteredPatientList() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        registerPatient("demoa", admin, "T1", "2025-06-10");
        registerPatient("demoa", admin, "T2", "2025-07-21");
        registerPatient("demoa", admin, "T3", today.toString());
        registerPatient("demoa", admin, "T4", "2026-02-20");

        LocalDate from = LocalDate.of(2025, 1, 1);
        Map<?, ?> trend =
                data(
                        get(
                                cardPath("trend")
                                        + "&timeDimension=discharge&granularity=quarter"
                                        + "&groupBy=ward&from="
                                        + from
                                        + "&to="
                                        + today,
                                admin));

        assertEquals("DISCHARGE", trend.get("timeDimension"));
        assertEquals("QUARTER", trend.get("granularity"));
        assertEquals("WARD", trend.get("groupBy"));
        List<?> buckets = (List<?>) trend.get("buckets");
        assertFalse(buckets.isEmpty(), "2025-Q1 起连续分桶");
        long trendSum =
                buckets.stream()
                        .mapToLong(
                                bucket -> ((Number) ((Map<?, ?>) bucket).get("total")).longValue())
                        .sum();
        // 同一日期区间筛选的患者列表总数（A 规格 §9 验收第二条）
        long listTotal =
                searchTotal(
                        admin,
                        "/api/hospital/followup/patients/search",
                        Map.of(
                                "filters",
                                Map.of(
                                        "dischargeFrom",
                                        from.toString(),
                                        "dischargeTo",
                                        today.toString())));
        assertEquals(listTotal, trendSum, "各季度合计与按出院日期筛选的患者列表一致");
        assertTrue(listTotal >= 4, "含本用例 4 名虚构患者");

        // 各桶内分组数 = 桶 total（按病区分组不重不漏）
        for (Object item : buckets) {
            Map<?, ?> bucket = (Map<?, ?>) item;
            long groupSum =
                    ((List<?>) bucket.get("groups"))
                            .stream()
                                    .mapToLong(
                                            g ->
                                                    ((Number) ((Map<?, ?>) g).get("count"))
                                                            .longValue())
                                    .sum();
            assertEquals(
                    ((Number) bucket.get("total")).longValue(),
                    groupSum,
                    "桶 " + bucket.get("bucket") + " 分组合计 = 桶 total");
        }
    }

    // ---------------------------------------------------------------- 权限 / 数据范围 / 租户隔离

    /** 回收 FOLLOWUP_DOCTOR 的卡片权限 → 新会话 40300（权限按库内当前授权实时计算，登录即生效）。 */
    @Test
    void doctorWithoutPermissionRejectedAndRestored() {
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        assertEquals(0, codeOf(get(cardPath("kpi"), doctor).getBody()), "种子 V129 已授医生角色");
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r"
                                        + " ON r.id = rm.role_id"
                                        + " WHERE r.code = 'FOLLOWUP_DOCTOR' AND rm.menu_id = 234"));
        try {
            // 授权变更后重新登录：登录按库内当前授权计算权限（直接 SQL 改库不递增版本号，旧令牌仍持有回收前的权限）
            String revoked = hospitalToken("demoa", "doctor.demo", PASSWORD);
            assertEquals(40300, codeOf(get(cardPath("kpi"), revoked).getBody()), "回收后 40300");
            ResponseEntity<Map> anonymous = rest.getForEntity(cardPath("kpi"), Map.class);
            assertEquals(40100, ((Number) anonymous.getBody().get("code")).intValue(), "未登录 40100");
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id)"
                                            + " SELECT r.id, 234 FROM sys_role r"
                                            + " WHERE r.code = 'FOLLOWUP_DOCTOR'"
                                            + " AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm"
                                            + " WHERE rm.role_id = r.id AND rm.menu_id = 234)"));
        }
        assertEquals(0, codeOf(get(cardPath("kpi"), doctor).getBody()), "恢复后可用");
    }

    /** 数据范围外会话：指标全 0、趋势全零桶（stats.md §1「范围为空 → 全 0，不报错」）。 */
    @Test
    void outOfScopeSessionSeesZerosNotErrors() {
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        DashboardKpiService.KpiCardView kpi =
                withHospitalSession(outsider, () -> kpiService.kpi(null));
        assertTrue(
                kpi.metrics().stream()
                        .allMatch(metric -> metric.value() == null || metric.value() == 0L),
                "范围外会话全部指标为 0");
        DashboardTrendService.TrendCardView trend =
                withHospitalSession(
                        outsider,
                        () ->
                                trendService.trend(
                                        null,
                                        LocalDate.of(2025, 1, 1),
                                        LocalDate.of(2026, 12, 31),
                                        null,
                                        null,
                                        null));
        assertTrue(trend.buckets().stream().allMatch(bucket -> bucket.total() == 0), "范围外会话趋势全零");
    }

    /** 双租户隔离：demoa 的患者不影响 demob 的卡片计数。 */
    @Test
    void tenantIsolationOnKpiCounts() {
        String adminA = hospitalToken("demoa", "nurse.demo", PASSWORD);
        registerPatient("demoa", adminA, "I1", "2025-06-10");
        long demobPatients =
                runInTenant(
                        "demob",
                        () -> jdbc.queryForObject("SELECT COUNT(*) FROM pt_patient", Long.class));
        DashboardKpiService.KpiCardView kpiB =
                withHospitalSession(adminPrincipal("demob"), () -> kpiService.kpi(null));
        long totalB =
                kpiB.metrics().stream()
                        .filter(metric -> "total".equals(metric.code()))
                        .findFirst()
                        .orElseThrow()
                        .value();
        assertEquals(demobPatients, totalB, "demob 卡片只数本院患者");
    }

    // ---------------------------------------------------------------- 辅助（同 NodeOccurrenceStatsIT
    // 口径）

    private static String cardPath(String cardCode) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode + "?";
    }

    private static Map<String, Object> metricsByCode(Map<?, ?> kpi) {
        Map<String, Object> byCode = new HashMap<>();
        for (Object item : (List<?>) kpi.get("metrics")) {
            Map<?, ?> metric = (Map<?, ?>) item;
            byCode.put(String.valueOf(metric.get("code")), metric);
        }
        return byCode;
    }

    private static long number(Object metricOrValue) {
        Object value = metricOrValue instanceof Map<?, ?> map ? map.get("value") : metricOrValue;
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** 列表检索总数（分页 total；与卡片同一登录会话与数据范围）。 */
    private long searchTotal(String token, String path, Map<String, Object> body) {
        Map<?, ?> response = post(path, token, body);
        assertEquals(0, codeOf(response), "列表检索失败：" + response);
        return ((Number) data(response).get("total")).longValue();
    }

    /** A 年度形态（+1 年、提前 7 天、窗口 ±7 天）。 */
    private long createPublishedAnnualPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 年度随访计划（" + code + "）");
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
        node.put("passedWindowAction", "BACKFILL");
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

    /** 登记患者（legacy_no 自清锚点）。 */
    private long registerPatient(String tenant, String token, String suffix, String dischargeDate) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E19T02-" + suffix);
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

    /** 患者随访状态置值（指标卡按 pt_patient.followup_status 计数；状态时间线随 E14-T06，本测直接置列）。 */
    private void setStatus(String tenant, long patientId, String status) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "UPDATE pt_patient SET followup_status = ? WHERE id = ?",
                                status,
                                patientId));
    }

    /** 服务层入组（回拨 created_at 清掉入组即建任务，保持用例任务完全可控）。 */
    private long enroll(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                            LocalDate.of(2025, 1, 1).atStartOfDay(),
                            enrollmentId);
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    return enrollmentId;
                });
    }

    /** 直接落一条常规任务（occurrence=1、followup_seq=0；口径见 DashboardStatsMapper.countMonthDue）。 */
    private void insertTask(
            String tenant,
            long enrollmentId,
            long patientId,
            long planId,
            long nodeId,
            LocalDate planDate,
            String status) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                        + " occurrence, task_type, form_code, owner_type, plan_date,"
                                        + " remind_start, window_start, window_end, open_date,"
                                        + " create_type, status, followup_seq, created_logical_date)"
                                        + " VALUES (?, ?, ?, ?, 1, 'FORM', 'fu_annual_v1',"
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', ?, 0, ?)",
                                enrollmentId,
                                patientId,
                                planId,
                                nodeId,
                                planDate,
                                planDate.minusDays(14),
                                planDate.minusDays(7),
                                planDate.plusDays(7),
                                planDate.minusDays(14),
                                status,
                                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    /** 登录会话（供 @DataScope 切面以登录快照出行——与 HTTP 请求同一装配链）。 */
    private SessionPrincipal adminPrincipal(String tenant) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                tenant,
                                "username",
                                "nurse.demo",
                                "password",
                                PASSWORD));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        String token = (String) data(response).get("token");
        return new SessionPrincipal(
                token,
                "HOSPITAL",
                "demoa".equals(tenant) ? 1L : 2L,
                tenant,
                tenant + "（示例）",
                1L,
                "nurse.demo",
                "护士长（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:dashboard:view"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    /** 范围外病区的会话（不存在的病区 id：任何患者都不在范围内）。 */
    private SessionPrincipal restrictedPrincipal(String tenant) {
        long absentWard =
                runInTenant(
                        tenant,
                        () -> jdbc.queryForObject("SELECT MAX(id) + 1000 FROM ward", Long.class));
        return new SessionPrincipal(
                "it-restricted-token",
                "HOSPITAL",
                1L,
                tenant,
                tenant + "（示例）",
                2L,
                "it.outsider",
                "范围外医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:dashboard:view"),
                List.of("followup"),
                1L,
                ScopeSnapshot.restricted(List.of(absentWard), List.of(), false),
                false,
                null);
    }

    /** 以给定会话执行（同 TokenAuthenticationFilter 的 ThreadLocal 装配方式，DataScopeMaskingIT 口径）。 */
    private <T> T withHospitalSession(
            SessionPrincipal principal, java.util.function.Supplier<T> action) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try {
            return TenantContext.callAsTenant(
                    principal.tenantId(), principal.tenantCode(), action::get);
        } finally {
            SecurityContextHolder.clearContext();
        }
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

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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

    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
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
