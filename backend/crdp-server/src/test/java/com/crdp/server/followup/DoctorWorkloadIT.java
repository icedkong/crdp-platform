package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.stats.app.DoctorWorkloadStatsService;
import com.crdp.followup.stats.app.NodeOccurrenceRefreshService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * E19-T07 端到端（tasks.csv 验收「按责任人统计」，A-DASH-009 / C-DASH-010）。A 年度形态（虚构）： 两位责任医生（张负责 4 名患者：2 在随访 +
 * 已终止 + 已死亡；李负责 1 名在随访）+ 1 名无责任医生的患者（不出行）。
 *
 * <ul>
 *   <li>行按患者责任医生归口（OQ-118 面板口径）：负责患者数 / 在随访 / 本月应随访（计划日期 = 本月业务日）/ 逾期（OVERDUE 任务）与每年随访率（Q38：年度节点患者
 *       × 年度去重，张 = 1/2、李 = 0/1）；
 *   <li>每列内联钻取目标（患者列表 doctorId〔/ status=ACTIVE〕、任务列表 tab=all + dueFrom/dueTo / status=OVERDUE）；
 *   <li>病区筛选收窄到无患者病区 → 空行集；范围外会话空行集（@DataScope）；wardId 非法 40001；
 *   <li>无权限 40300（回收 FOLLOWUP_DOCTOR 的 followup:dashboard:view 后新会话即拒绝）；
 *   <li>双租户：demob 只见本院患者，demoa 行不出现在 demob。
 * </ul>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（前缀 IT-E19T07 / PLAN-IT-E19T07）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorWorkloadIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T07";
    private static final String PLAN_CODE = "PLAN-IT-E19T07";

    /** 事实表刷新统计日：年度任务窗口（2026-03-08 ~ 03-22）已结束（与 DashboardRateCardsIT 同形态）。 */
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private NodeOccurrenceRefreshService refreshService;
    @Autowired private DoctorWorkloadStatsService workloadService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 单计划世界：关掉 E23-T02 预置计划的常驻自动入组（口径同 DashboardRateCardsIT）
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        jdbc.update(
                                "DELETE FROM st_node_occurrence WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
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
                        // 两位虚构责任医生（只作 responsible_user_id 引用，不登录）
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE ?", "it-e19t07-%");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：按责任人统计

    @Test
    void workloadCountsPerResponsibleDoctorWithDrills() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        // 两计划并行：A 计划供随访率（第 1 年窗口已结束）、B 计划供任务两列（本月 / 逾期，
        // 同一入组同一节点同一次数只能一条任务——uk_fu_task_occurrence）
        long planA = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");
        long planB = createPublishedAnnualPlan(admin, PLAN_CODE + "-B");
        long zhang = insertDoctor("demoa", "it-e19t07-a", "张负责（示例）");
        long li = insertDoctor("demoa", "it-e19t07-b", "李负责（示例）");

        // 张负责：2 在随访 + 1 已终止 + 1 已死亡（负责患者数 4、在随访 2；终止 / 死亡不入组——
        // 不计随访率分母，Q38 窗口开始日回看不符合条件）
        long p1 = registerPatient("demoa", admin, "W1", zhang);
        long p2 = registerPatient("demoa", admin, "W2", zhang);
        registerPatientWithStatus("demoa", admin, "W3", zhang, "TERMINATED", 1);
        registerPatientWithStatus("demoa", admin, "W4", zhang, "DEAD", 1);
        // 李负责：1 在随访；无责任医生患者 W5 不出行
        long p5 = registerPatient("demoa", admin, "W6", li);
        registerPatient("demoa", admin, "W5", null);

        long nodeA = nodeIdOf("demoa", planA);
        long nodeB = nodeIdOf("demoa", planB);
        // 每年随访率（Q38）：第 1 年窗口 2026-03-08 ~ 03-22（已结束）——张的 p1 完成、p2 未做；李的 p5 未做
        insertCompletedTask("demoa", p1, planA, nodeA, LocalDate.of(2026, 3, 15));
        insertPendingTask("demoa", p2, planA, nodeA, LocalDate.of(2026, 3, 15));
        insertPendingTask("demoa", p5, planA, nodeA, LocalDate.of(2026, 3, 15));
        // 本月应随访：p1 / p5 各一条计划日期 = 本月业务日的任务（张、李各 1）
        insertPendingTask("demoa", p1, planB, nodeB, TODAY);
        insertPendingTask("demoa", p5, planB, nodeB, TODAY);
        // 逾期：p2 一条 OVERDUE 任务（计划日期上月，不进「本月应随访」）
        insertOverdueTask("demoa", p2, planB, nodeB, TODAY.minusMonths(1).withDayOfMonth(10));

        refreshService.refreshForDate(AS_OF);

        Map<?, ?> view = data(get(statsPath(null), admin));
        assertEquals(AS_OF.toString(), String.valueOf(view.get("asOf")), "asOf = 刷新统计日");
        assertEquals(
                TODAY.getYear() + "-" + String.format("%02d", TODAY.getMonthValue()),
                view.get("asOfMonth"),
                "本月口径月");
        List<?> rows = (List<?>) view.get("rows");
        assertEquals(2, rows.size(), "按责任医生一行；无责任医生的患者不出行");
        Map<?, ?> zhangRow = (Map<?, ?>) rows.get(0);
        assertEquals(String.valueOf(zhang), zhangRow.get("doctorId"), "doctorId 字符串");
        assertEquals("张负责（示例）", zhangRow.get("doctorName"));
        assertEquals(4L, num(zhangRow.get("totalPatients")), "负责患者数 = 在册患者（含已终止 / 已死亡）");
        assertEquals(2L, num(zhangRow.get("activePatients")), "在随访 = ACTIVE");
        assertEquals(1L, num(zhangRow.get("monthDue")), "本月应随访 = p1 的本月任务");
        assertEquals(1L, num(zhangRow.get("overdue")), "逾期 = p2 的 OVERDUE 任务");
        assertEquals(2L, num(zhangRow.get("rateDueCount")), "随访率分母 = p1 + p2 第 1 年");
        assertEquals(1L, num(zhangRow.get("rateFollowedCount")), "已随访 = p1（逾期完成 / 补录同理计入，Q38）");
        assertEquals(50.0d, zhangRow.get("followRatePct"), "每年随访率 = 1/2");
        // 钻取目标内联（患者列表 doctorId〔/ status〕、任务列表 tab=all + 窗口 / 状态）
        assertEquals("PATIENT_LIST", ((Map<?, ?>) zhangRow.get("totalDrill")).get("target"));
        assertEquals(
                String.valueOf(zhang),
                ((Map<?, ?>) ((Map<?, ?>) zhangRow.get("totalDrill")).get("query"))
                        .get("doctorId"));
        Map<?, ?> monthDueQuery =
                (Map<?, ?>) ((Map<?, ?>) zhangRow.get("monthDueDrill")).get("query");
        assertEquals("all", monthDueQuery.get("tab"));
        assertEquals(
                TODAY.withDayOfMonth(1).toString(), String.valueOf(monthDueQuery.get("dueFrom")));
        assertEquals(
                TODAY.withDayOfMonth(TODAY.lengthOfMonth()).toString(),
                String.valueOf(monthDueQuery.get("dueTo")));
        Map<?, ?> overdueQuery =
                (Map<?, ?>) ((Map<?, ?>) zhangRow.get("overdueDrill")).get("query");
        assertEquals("TASK_LIST", ((Map<?, ?>) zhangRow.get("overdueDrill")).get("target"));
        assertEquals("OVERDUE", overdueQuery.get("status"));

        Map<?, ?> liRow = (Map<?, ?>) rows.get(1);
        assertEquals("李负责（示例）", liRow.get("doctorName"));
        assertEquals(1L, num(liRow.get("totalPatients")));
        assertEquals(1L, num(liRow.get("activePatients")));
        assertEquals(1L, num(liRow.get("monthDue")));
        assertEquals(0L, num(liRow.get("overdue")));
        assertEquals(1L, num(liRow.get("rateDueCount")));
        assertEquals(0L, num(liRow.get("rateFollowedCount")));
        assertEquals(0.0d, liRow.get("followRatePct"), "分母 1 分子 0 → 0%");
    }

    // ---------------------------------------------------------------- 病区筛选 / 范围 / 校验

    /** 病区筛选：收窄到无患者的病区 → 空行集（范围内再收窄，不报错）。 */
    @Test
    void wardFilterNarrowsToEmptyRows() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-B");
        long doctor = insertDoctor("demoa", "it-e19t07-a", "张负责（示例）");
        registerPatient("demoa", admin, "N1", doctor);
        long absentWard =
                runInTenant(
                        "demoa",
                        () -> jdbc.queryForObject("SELECT MAX(id) + 1000 FROM ward", Long.class));
        assertEquals(
                List.of(),
                (List<?>) ((Map<?, ?>) data(get(statsPath(absentWard), admin))).get("rows"));
        assertEquals(40001, codeOf(get(statsPath(0L), admin).getBody()), "wardId 非正整数 40001");
        assertTrue(planId > 0);
    }

    /** 数据范围外会话：空行集（stats.md §1「范围为空 → 空，不报错」）。 */
    @Test
    void outOfScopeSessionSeesEmptyRows() {
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        DoctorWorkloadStatsService.DoctorWorkloadView view =
                withHospitalSession(outsider, () -> workloadService.workload(null));
        assertTrue(view.rows().isEmpty(), "范围外会话空行集");
    }

    // ---------------------------------------------------------------- 权限 / 双租户

    /** 回收 FOLLOWUP_DOCTOR 的卡片权限 → 新会话 40300（权限按库内当前授权实时计算，登录即生效）。 */
    @Test
    void doctorWithoutPermissionRejectedAndRestored() {
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        assertEquals(0, codeOf(get(statsPath(null), doctor).getBody()), "种子 V129 已授医生角色");
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r"
                                        + " ON r.id = rm.role_id"
                                        + " WHERE r.code = 'FOLLOWUP_DOCTOR' AND rm.menu_id = 234"));
        try {
            String revoked = hospitalToken("demoa", "doctor.demo", PASSWORD);
            assertEquals(40300, codeOf(get(statsPath(null), revoked).getBody()), "回收后 40300");
            ResponseEntity<Map> anonymous = rest.getForEntity(statsPath(null), Map.class);
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
        assertEquals(0, codeOf(get(statsPath(null), doctor).getBody()), "恢复后可用");
    }

    /** 双租户：demob 只统计本院患者（demoa 的责任医生行不出现在 demob）。 */
    @Test
    void tenantIsolationKeepsRowsPerTenant() {
        String adminA = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String adminB = hospitalToken("demob", "nurse.demo", PASSWORD);
        long doctor = insertDoctor("demoa", "it-e19t07-a", "张负责（示例）");
        registerPatient("demoa", adminA, "T1", doctor);

        List<?> rowsB = (List<?>) ((Map<?, ?>) data(get(statsPath(null), adminB))).get("rows");
        assertFalse(
                rowsB.stream()
                        .map(row -> ((Map<?, ?>) row).get("doctorId"))
                        .anyMatch(id -> String.valueOf(doctor).equals(id)),
                "demoa 责任医生的行不出现在 demob");
    }

    // ---------------------------------------------------------------- 辅助（DashboardRateCardsIT 同口径）

    private static String statsPath(Long wardId) {
        return "/api/hospital/followup/stats/doctor-workload"
                + (wardId == null ? "?" : "?wardId=" + wardId + "&");
    }

    /** 虚构责任医生（不登录，只作 responsible_user_id 引用；自建自清）。 */
    private long insertDoctor(String tenant, String username, String displayName) {
        return runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status)"
                                    + " VALUES (?, ?, 'PENDING_DEMO_HASH', 'ENABLED')",
                            username,
                            displayName);
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
                });
    }

    /** A 年度形态计划（+1 年、窗口 ±7 天；DashboardRateCardsIT 同款）。 */
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

    /** 登记患者（legacy_no 自清锚点；落 MIN(id) 病区；可指定责任医生）。 */
    private long registerPatient(String tenant, String token, String suffix, Long doctorId) {
        return registerPatientWithStatus(tenant, token, suffix, doctorId, "ACTIVE", 1);
    }

    private long registerPatientWithStatus(
            String tenant,
            String token,
            String suffix,
            Long doctorId,
            String status,
            int dischargeStatus) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E19T07-" + suffix);
        body.put("name", "钱示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1950-06-01");
        body.put("wardId", wardId);
        body.put("dischargeDate", "2025-03-15");
        body.put("admissionDate", "2025-03-01");
        body.put("surgeryDate", "2025-03-08");
        body.put("dischargeStatus", dischargeStatus);
        body.put("legacyNo", LEGACY + "-" + suffix + "-" + tenant);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        long patientId = Long.parseLong((String) data(created).get("id"));
        if (doctorId != null || !"ACTIVE".equals(status)) {
            runInTenant(
                    tenant,
                    () ->
                            jdbc.update(
                                    "UPDATE pt_patient SET responsible_user_id = ?,"
                                            + " followup_status = ? WHERE id = ?",
                                    doctorId,
                                    status,
                                    patientId));
        }
        return patientId;
    }

    /** 服务层入组并回拨 created_at（清掉入组即建任务，任务由用例直接落库保持口径可控）； 同一患者多次插任务时复用在组行（幂等）。 */
    private long enrollBackdated(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    List<Long> existing =
                            jdbc.queryForList(
                                    "SELECT id FROM fu_enrollment WHERE patient_id = ? AND"
                                            + " plan_id = ? AND status IN ('ACTIVE', 'PAUSED')"
                                            + " ORDER BY id DESC LIMIT 1",
                                    Long.class,
                                    patientId,
                                    planId);
                    if (!existing.isEmpty()) {
                        return existing.get(0);
                    }
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                            LocalDate.of(2025, 4, 1).atStartOfDay(),
                            enrollmentId);
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    return enrollmentId;
                });
    }

    /** 节点 id（本用例计划的唯一 FORM 节点）。 */
    private long nodeIdOf(String tenant, long planId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT MIN(n.id) FROM fu_plan_node n WHERE n.plan_id = ?"
                                        + " AND n.node_type = 'NODE'",
                                Long.class,
                                planId));
    }

    /** 已完成任务（窗口内完成 → 已随访，Q38）。 */
    private void insertCompletedTask(
            String tenant, long patientId, long planId, long nodeId, LocalDate planDate) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId);
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                        + " occurrence, task_type, form_code, owner_type, plan_date,"
                                        + " remind_start, window_start, window_end, open_date,"
                                        + " create_type, status, complete_kind, completed_at,"
                                        + " followup_seq, created_logical_date)"
                                        + " VALUES (?, ?, ?, ?, 1, 'FORM', 'fu_annual_v1',"
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', 'COMPLETED',"
                                        + " 'ON_TIME', ?, 0, ?)",
                                enrollmentId,
                                patientId,
                                planId,
                                nodeId,
                                planDate,
                                planDate.minusDays(14),
                                planDate.minusDays(7),
                                planDate.plusDays(7),
                                planDate.minusDays(14),
                                planDate.atTime(9, 0).toString().replace('T', ' '),
                                TODAY));
    }

    /** 待随访任务（计划日期决定「本月应随访」；窗口未结束的年度行不进随访率分母）。 */
    private void insertPendingTask(
            String tenant, long patientId, long planId, long nodeId, LocalDate planDate) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId);
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                        + " occurrence, task_type, form_code, owner_type, plan_date,"
                                        + " remind_start, window_start, window_end, open_date,"
                                        + " create_type, status, followup_seq, created_logical_date)"
                                        + " VALUES (?, ?, ?, ?, 1, 'FORM', 'fu_annual_v1',"
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', 'PENDING', 0, ?)",
                                enrollmentId,
                                patientId,
                                planId,
                                nodeId,
                                planDate,
                                planDate.minusDays(14),
                                planDate.minusDays(7),
                                planDate.plusDays(7),
                                planDate.minusDays(14),
                                TODAY));
    }

    /** 逾期任务（status = OVERDUE，计划日期上月 → 不进「本月应随访」）。 */
    private void insertOverdueTask(
            String tenant, long patientId, long planId, long nodeId, LocalDate planDate) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId);
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                        + " occurrence, task_type, form_code, owner_type, plan_date,"
                                        + " remind_start, window_start, window_end, open_date,"
                                        + " create_type, status, followup_seq, created_logical_date)"
                                        + " VALUES (?, ?, ?, ?, 1, 'FORM', 'fu_annual_v1',"
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', 'OVERDUE', 0, ?)",
                                enrollmentId,
                                patientId,
                                planId,
                                nodeId,
                                planDate,
                                planDate.minusDays(14),
                                planDate.minusDays(7),
                                planDate.plusDays(7),
                                planDate.minusDays(14),
                                TODAY));
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
                List.of(),
                List.of("followup"),
                1L,
                ScopeSnapshot.restricted(List.of(absentWard), List.of(), false),
                false,
                null);
    }

    /** 以给定会话执行（同 TokenAuthenticationFilter 的 ThreadLocal 装配方式）。 */
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
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static <T> T data(ResponseEntity<Map> response) {
        return (T) data(response.getBody());
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong((String) data(response).get("id"));
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
