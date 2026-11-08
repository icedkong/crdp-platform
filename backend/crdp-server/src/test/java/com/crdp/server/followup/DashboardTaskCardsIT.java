package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.dashboard.app.DashboardTaskService;
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
 * E19-T03 端到端（tasks.csv 验收「受病区隔离」）：首页任务看板（A-DASH-003 / A-DASH-004）与任务表（A-DASH-008 / C-DASH-003）卡片接口
 * ——列 / 页签计数与任务列表检索在同一数据上总数一致（「一个口径一个查询」）， 病区隔离双层生效：登录快照范围（越范围病区的任务不可见）+ 页面病区筛选（wardId 只在范围内收窄）；
 * 病区候选按快照病区清单交集（A-DASH-005「默认本人全部可见病区」）。
 *
 * <p>虚构数据（demoa）：4 名患者各挂一条任务（今日窗口 PENDING、进行中、逾期、本月已完成），分属两个病区—— 医生（doctor.demo，W-DEMO-1
 * 病区范围）只看得见本病区患者；今日页签只计窗口包含当日的未完成任务，钻取（windowFrom/windowTo）与列表总数逐项一致。 门控 CRDP_IT_MYSQL_PORT（CI 起
 * docker compose 注入端口）。自建自清（前缀 IT-E19T03 / PLAN-IT-E19T03）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardTaskCardsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T03";
    private static final String PLAN_CODE = "PLAN-IT-E19T03";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanService planService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private DashboardTaskService taskCardService;

    /** 本用例患者 / 任务所在的两个病区：医生可见（W-DEMO-1）与不可见（其余启用病区）。 */
    private long visibleWardId;

    private long hiddenWardId;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
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
                            "DELETE FROM fu_task WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
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
                    visibleWardId =
                            jdbc.queryForObject(
                                    "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class);
                    hiddenWardId =
                            jdbc.queryForObject(
                                    "SELECT MIN(id) FROM ward WHERE code <> 'W-DEMO-1'",
                                    Long.class);
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：列 / 页签计数与任务列表一致

    /** 看板四列总数 = 任务列表同条件检索总数（含已完成列完成时间窗口钻取一致）。 */
    @Test
    void boardColumnTotalsMatchTaskListSearch() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");
        seedTasks(admin, planId, today);

        Map<?, ?> board = data(get(cardPath("task_board") + "&scope=ALL", admin));
        assertEquals("ALL", board.get("scope"));
        assertEquals("本月已完成", board.get("doneColumn"), "demoa 未开 implant → 医院 A 口径");
        assertEquals(today.withDayOfMonth(1).toString(), board.get("doneFrom"), "已完成列 = 本月闭区间");
        assertEquals(today.withDayOfMonth(today.lengthOfMonth()).toString(), board.get("doneTo"));

        Map<String, Map<?, ?>> columns = columnsByStatus(board);
        for (String status : new String[] {"PENDING", "IN_PROGRESS", "OVERDUE", "COMPLETED"}) {
            Map<String, Object> search = new HashMap<>();
            search.put("scope", "ALL");
            search.put("status", List.of(status));
            if ("COMPLETED".equals(status)) {
                search.put("completedFrom", today.withDayOfMonth(1).toString());
                search.put("completedTo", today.withDayOfMonth(today.lengthOfMonth()).toString());
            }
            long listTotal = searchTotal(admin, "/api/hospital/followup/tasks/search", search);
            assertEquals(
                    listTotal,
                    number(columns.get(status).get("total")),
                    "看板列 " + status + " 总数与任务列表检索一致");
            assertTrue(listTotal >= 1, status + " 列含本用例虚构任务");
        }

        // 卡片行：脱敏姓名 + 窗口 + 负责人（A 规格 §9 看板卡片口径）
        Map<?, ?> pendingCard =
                (Map<?, ?>) ((List<?>) columns.get("PENDING").get("tasks")).getFirst();
        assertTrue(String.valueOf(pendingCard.get("patientName")).contains("*"), "看板卡片姓名为脱敏值");
        assertEquals(today.plusDays(7).toString(), pendingCard.get("windowEnd"));
        assertTrue(pendingCard.containsKey("assigneeName"));
        // 已完成列钻取带完成时间窗口（与列表 completedFrom / completedTo 同参数）
        Map<?, ?> doneDrill = (Map<?, ?>) columns.get("COMPLETED").get("drill");
        assertEquals("TASK_LIST", doneDrill.get("target"));
        assertEquals(
                today.withDayOfMonth(1).toString(),
                ((Map<?, ?>) doneDrill.get("query")).get("completedFrom"));
        assertEquals(
                today.withDayOfMonth(today.lengthOfMonth()).toString(),
                ((Map<?, ?>) doneDrill.get("query")).get("completedTo"));
    }

    /** 任务表三页签：今日 = 窗口包含当日的未完成任务（钻取 windowFrom/windowTo 与列表一致）、超时 = 逾期、我负责 = 本人未完成。 */
    @Test
    void tableTabTotalsMatchTaskListSearch() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-B");
        seedTasks(admin, planId, today);

        Map<?, ?> table = data(get(cardPath("task_table"), admin));
        assertEquals("TODAY", table.get("tab"));
        Map<String, Map<?, ?>> tabs = tabsByCode(table);
        assertEquals("今日任务", tabs.get("TODAY").get("title"));
        assertEquals("超时任务", tabs.get("OVERDUE").get("title"));
        assertEquals("我负责的任务", tabs.get("MINE").get("title"));

        // 今日任务：今天处于窗口内且未完成（含跟进任务与站内提醒的窗口口径）——钻取参数与列表检索逐项一致
        long todayListTotal =
                searchTotal(
                        admin,
                        "/api/hospital/followup/tasks/search",
                        Map.of(
                                "scope",
                                "ALL",
                                "status",
                                List.of(
                                        "PENDING",
                                        "IN_PROGRESS",
                                        "OVERDUE",
                                        "LOST_CANDIDATE",
                                        "SKIPPED_SUSPENDED"),
                                "windowFrom",
                                today.toString(),
                                "windowTo",
                                today.toString()));
        assertEquals(todayListTotal, number(tabs.get("TODAY").get("total")), "今日任务页签与列表窗口筛选总数一致");
        assertEquals(todayListTotal, number(table.get("total")), "当前页签 total 与页签徽标一致");

        // 超时任务 = 逾期列表（与指标卡「当前逾期」同一棵条件树）
        assertEquals(
                searchTotal(
                        admin,
                        "/api/hospital/followup/tasks/search",
                        Map.of("scope", "ALL", "status", List.of("OVERDUE"))),
                number(tabs.get("OVERDUE").get("total")),
                "超时任务页签与逾期列表总数一致");

        // 我负责的任务 = 列表 MINE 页签（默认未完成五态）
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long mineListTotal =
                searchTotal(doctor, "/api/hospital/followup/tasks/search", Map.of("scope", "MINE"));
        Map<?, ?> doctorTable = data(get(cardPath("task_table"), doctor));
        assertEquals(
                mineListTotal,
                number(tabsOf(doctorTable).get("MINE").get("total")),
                "我负责的任务页签与列表 MINE 页签一致");
    }

    // ---------------------------------------------------------------- 验收：受病区隔离

    /** 病区隔离（登录快照范围）：doctor.demo 只有 W-DEMO-1 病区范围——另一病区患者的任务在看板 / 任务表 / 列表都不可见； 病区候选只含本人可见病区。 */
    @Test
    void wardIsolationHidesOtherWardTasksAndFiltersOptions() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-C");
        seedTasks(admin, planId, today);

        // 管理员看得见两个病区的待随访任务；医生只看得见本病区（W-DEMO-1）的
        Map<?, ?> adminBoard = data(get(cardPath("task_board") + "&scope=ALL", admin));
        Map<?, ?> doctorBoard = data(get(cardPath("task_board") + "&scope=ALL", doctor));
        long adminPending = number(columnsOf(adminBoard).get("PENDING").get("total"));
        long doctorPending = number(columnsOf(doctorBoard).get("PENDING").get("total"));
        assertTrue(adminPending >= 2, "管理员可见两个病区的待随访任务（实际 " + adminPending + "）");
        assertEquals(adminPending - 1, doctorPending, "病区隔离：不可见病区的任务不在医生看板内");

        // 任务表页签同样隔离（今日任务）
        Map<?, ?> adminTable = data(get(cardPath("task_table"), admin));
        Map<?, ?> doctorTable = data(get(cardPath("task_table"), doctor));
        assertEquals(
                number(tabsOf(adminTable).get("TODAY").get("total")) - 1,
                number(tabsOf(doctorTable).get("TODAY").get("total")),
                "任务表今日页签同样受病区隔离");

        // 病区候选（A-DASH-005）：医生按登录快照病区清单收窄（W-DEMO-1），管理员（全院）见全部启用病区
        Map<?, ?> doctorWards = data(get("/api/hospital/followup/dashboard/wards", doctor));
        List<?> doctorWardList = (List<?>) doctorWards.get("wards");
        assertEquals(1, doctorWardList.size(), "医生病区候选 = 本人可见病区");
        assertEquals(
                String.valueOf(visibleWardId), ((Map<?, ?>) doctorWardList.getFirst()).get("id"));
        Map<?, ?> adminWards = data(get("/api/hospital/followup/dashboard/wards", admin));
        assertTrue(((List<?>) adminWards.get("wards")).size() >= 2, "全院范围候选 = 全部启用病区");
    }

    /** 页面病区筛选（A-DASH-005）：wardId 只在可见范围内收窄，各列与列表同参数总数一致；范围外会话全 0 不报错。 */
    @Test
    void wardFilterNarrowsWithinScopeAndOutOfRangeIsEmpty() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-D");
        seedTasks(admin, planId, today);

        Map<?, ?> board =
                data(get(cardPath("task_board") + "&scope=ALL&wardId=" + hiddenWardId, admin));
        Map<?, ?> pending = columnsOf(board).get("PENDING");
        assertEquals(
                searchTotal(
                        admin,
                        "/api/hospital/followup/tasks/search",
                        Map.of(
                                "scope", "ALL",
                                "status", List.of("PENDING"),
                                "wardIds", List.of(hiddenWardId))),
                number(pending.get("total")),
                "病区筛选下看板列与列表同参数总数一致");

        // 数据范围外会话（快照病区不存在）：全部 0 / 空列（stats.md §1，不报错）
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        DashboardTaskService.TaskBoardView outBoard =
                withHospitalSession(outsider, () -> taskCardService.board(null, "ALL", null));
        assertTrue(
                outBoard.columns().stream().allMatch(column -> column.total() == 0),
                "范围外会话看板各列全 0");
        assertTrue(
                outBoard.columns().stream().allMatch(column -> column.tasks().isEmpty()),
                "范围外会话看板无卡片");
        DashboardTaskService.TaskTableView outTable =
                withHospitalSession(outsider, () -> taskCardService.table(null, null));
        assertTrue(outTable.tabs().stream().allMatch(tab -> tab.total() == 0), "范围外会话任务表各页签全 0");
        assertTrue(outTable.items().isEmpty(), "范围外会话任务表无行");
    }

    /** 看板筛选（A-DASH-004）：scope=MINE 与负责人筛选（assigneeId ↔ 列表 doctorId）与列表总数一致。 */
    @Test
    void boardScopeAndAssigneeFiltersMatchListSearch() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-E");
        seedTasks(admin, planId, today);

        // scope=MINE：医生看板只数本人（负责人或责任医生）的任务，与列表 MINE + 状态筛选一致
        Map<?, ?> mineBoard = data(get(cardPath("task_board") + "&scope=MINE", doctor));
        assertEquals(
                searchTotal(
                        doctor,
                        "/api/hospital/followup/tasks/search",
                        Map.of("scope", "MINE", "status", List.of("PENDING"))),
                number(columnsOf(mineBoard).get("PENDING").get("total")),
                "看板 MINE 筛选与列表我的待办（待随访）一致");

        // 负责人筛选：assigneeId = 医生 → 与列表 doctorId 检索一致
        long doctorUserId = doctorIdOf("demoa", "doctor.demo");
        Map<?, ?> assignedBoard =
                data(get(cardPath("task_board") + "&scope=ALL&assigneeId=" + doctorUserId, admin));
        assertEquals(
                searchTotal(
                        admin,
                        "/api/hospital/followup/tasks/search",
                        Map.of(
                                "scope",
                                "ALL",
                                "status",
                                List.of("PENDING"),
                                "doctorId",
                                doctorUserId)),
                number(columnsOf(assignedBoard).get("PENDING").get("total")),
                "看板负责人筛选与列表 doctorId 检索一致");
        // 钻取延续负责人筛选（doctorId 随钻取带出）
        Map<?, ?> drill = (Map<?, ?>) columnsOf(assignedBoard).get("PENDING").get("drill");
        assertEquals(
                String.valueOf(doctorUserId),
                String.valueOf(((Map<?, ?>) drill.get("query")).get("doctorId")));
    }

    /** C 口径（C-DASH-003）：implant 开 → 已完成列为「本周完成」（周一 ~ 周日）。 */
    @Test
    void cStyleDoneColumnIsCurrentWeek() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
        try {
            Map<?, ?> board = data(get(cardPath("task_board") + "&scope=ALL", admin));
            assertEquals("本周完成", board.get("doneColumn"));
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            assertEquals(monday.toString(), board.get("doneFrom"));
            assertEquals(monday.plusDays(6).toString(), board.get("doneTo"));
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        }
    }

    // ---------------------------------------------------------------- 虚构数据（4 名患者 × 4 态任务，两病区）

    /**
     * P1（可见病区）今日窗口 PENDING、负责人 doctor.demo；P2（不可见病区）今日窗口 PENDING、负责人 doctor.demo； P3（可见病区）进行中；
     * P4（可见病区）逾期（窗口已过）；P5（可见病区）本月已完成。
     */
    private void seedTasks(String admin, long planId, LocalDate today) {
        long doctorUserId = doctorIdOf("demoa", "doctor.demo");
        long p1 = registerPatient("demoa", admin, "B1", today, visibleWardId);
        long p2 = registerPatient("demoa", admin, "B2", today, hiddenWardId);
        long p3 = registerPatient("demoa", admin, "B3", today, visibleWardId);
        long p4 = registerPatient("demoa", admin, "B4", today, visibleWardId);
        long p5 = registerPatient("demoa", admin, "B5", today, visibleWardId);
        long e1 = enroll("demoa", p1, planId);
        long e2 = enroll("demoa", p2, planId);
        long e3 = enroll("demoa", p3, planId);
        long e4 = enroll("demoa", p4, planId);
        long e5 = enroll("demoa", p5, planId);
        long nodeId = nodeIdOf(planId);
        insertTask("demoa", e1, p1, planId, nodeId, today, "PENDING", doctorUserId, null);
        // P2 不可见病区且负责人为空（医生范围 = 本病区 ∪ 本人负责，assignee = 医生会让任务经任务负责人分支可见，E07-T02）
        insertTask("demoa", e2, p2, planId, nodeId, today, "PENDING", null, null);
        insertTask("demoa", e3, p3, planId, nodeId, today, "IN_PROGRESS", doctorUserId, null);
        insertTask(
                "demoa",
                e4,
                p4,
                planId,
                nodeId,
                today.withDayOfMonth(1).minusDays(20),
                "OVERDUE",
                doctorUserId,
                null);
        insertTask(
                "demoa",
                e5,
                p5,
                planId,
                nodeId,
                today.withDayOfMonth(1).plusDays(2),
                "COMPLETED",
                doctorUserId,
                today.withDayOfMonth(1).plusDays(3).atStartOfDay());
    }

    private long nodeIdOf(long planId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT MIN(n.id) FROM fu_plan_node n WHERE n.plan_id = ?",
                                Long.class,
                                planId));
    }

    private long doctorIdOf(String tenant, String username) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    // ---------------------------------------------------------------- 辅助（同 DashboardCardsIT 口径）

    private static String cardPath(String cardCode) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode + "?";
    }

    private static Map<String, Map<?, ?>> columnsByStatus(Map<?, ?> board) {
        return columnsOf(board);
    }

    private static Map<String, Map<?, ?>> columnsOf(Map<?, ?> board) {
        Map<String, Map<?, ?>> byStatus = new HashMap<>();
        for (Object item : (List<?>) board.get("columns")) {
            Map<?, ?> column = (Map<?, ?>) item;
            byStatus.put(String.valueOf(column.get("status")), column);
        }
        return byStatus;
    }

    private static Map<String, Map<?, ?>> tabsOf(Map<?, ?> table) {
        Map<String, Map<?, ?>> byCode = new HashMap<>();
        for (Object item : (List<?>) table.get("tabs")) {
            Map<?, ?> tab = (Map<?, ?>) item;
            byCode.put(String.valueOf(tab.get("tab")), tab);
        }
        return byCode;
    }

    private static Map<String, Map<?, ?>> tabsByCode(Map<?, ?> table) {
        return tabsOf(table);
    }

    private static long number(Object value) {
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

    /** 登记患者（legacy_no 自清锚点；wardId 指定病区——隔离用例两病区各一）。 */
    private long registerPatient(
            String tenant, String token, String suffix, LocalDate discharge, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E19T03-" + suffix);
        body.put("name", "钱示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1950-06-01");
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

    /** 直接落一条常规任务（occurrence=1、followup_seq=0；窗口 = 计划日期 ±7 天，可指定负责人与完成时间）。 */
    private void insertTask(
            String tenant,
            long enrollmentId,
            long patientId,
            long planId,
            long nodeId,
            LocalDate planDate,
            String status,
            Long assigneeId,
            java.time.LocalDateTime completedAt) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                        + " occurrence, task_type, form_code, owner_type, plan_date,"
                                        + " remind_start, window_start, window_end, open_date,"
                                        + " create_type, status, followup_seq, assignee_id,"
                                        + " completed_at, created_logical_date)"
                                        + " VALUES (?, ?, ?, ?, 1, 'FORM', 'fu_annual_v1',"
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', ?, 0, ?, ?, ?)",
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
                                assigneeId,
                                completedAt,
                                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
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
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
