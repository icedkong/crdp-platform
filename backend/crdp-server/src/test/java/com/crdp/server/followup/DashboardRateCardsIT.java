package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.stats.app.NodeOccurrenceRefreshService;
import com.crdp.followup.stats.app.NodeOccurrenceStatsService;
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
 * E19-T04 端到端（tasks.csv 验收「逾期完成与补录计入」，口径 Q38）。A 年度形态（虚构）：起算 2025-03-15、±7 天窗口， 第 1 年窗口 2026-03-08 ~
 * 2026-03-22，统计日 2026-06-01（窗口已结束）：
 *
 * <ul>
 *   <li>患者甲：任务窗口内完成（ON_TIME）→ 已随访 + 窗口内完成；
 *   <li>患者乙：任务逾期完成（OVERDUE，完成日 2026-04-01）→ 已随访、窗口内不计（验收「逾期完成计入」）；
 *   <li>患者丙：任务未完成 + 无锚点手工补录记录（A-FU-014，visit_date 落窗口内）→ 已随访（验收「补录计入」）；
 *   <li>患者丁：任务待随访 → 只计入分母；
 *   <li>yearly_rate（A-DASH-006）第 1 年与 node_rate（A-DASH-012）年度节点行数字一致（同一事实表查询）；
 *   <li>node_rate 受 node_follow_rate 开关控制（关闭 40301，AGENTS §5.7）；GET /auth/features 下发开关（OQ-03）；
 *   <li>病区筛选收窄到无患者病区 → 空行集；范围外会话空行集（@DataScope）；参数与 cardCode 校验 40001。
 * </ul>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（前缀 IT-E19T04 / PLAN-IT-E19T04）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardRateCardsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T04";
    private static final String PLAN_CODE = "PLAN-IT-E19T04";
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private NodeOccurrenceRefreshService refreshService;
    @Autowired private NodeOccurrenceStatsService statsService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 单计划世界：关掉 E23-T02 预置计划的常驻自动入组（口径同 NodeOccurrenceStatsIT）
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        // 开关恢复默认（种子 V100：node_follow_rate 开）
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1"
                                        + " WHERE feature_code = 'node_follow_rate'");
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
                                        + " OR action = 'STATS_NODE_REFRESH'");
                        jdbc.update(
                                "DELETE FROM st_node_occurrence WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM job_tenant_run WHERE job_code = 'st-node-refresh'");
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

    // ---------------------------------------------------------------- 验收：逾期完成与补录计入

    @Test
    void yearlyAndNodeRateCountOverdueAndBackfillAsFollowed() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");
        long onTime = registerPatient("demoa", admin, "F1", "2025-03-15");
        long overdue = registerPatient("demoa", admin, "F2", "2025-03-15");
        long backfill = registerPatient("demoa", admin, "F3", "2025-03-15");
        long pending = registerPatient("demoa", admin, "F4", "2025-03-15");
        long nodeId = nodeIdOf("demoa", planId);
        LocalDate planDate = LocalDate.of(2026, 3, 15);
        insertCompletedTask("demoa", onTime, planId, nodeId, planDate, "ON_TIME", "2026-03-15");
        // 逾期完成：窗口（03-08 ~ 03-22）结束后的 04-01 完成（验收「逾期完成计入」）
        insertCompletedTask("demoa", overdue, planId, nodeId, planDate, "OVERDUE", "2026-04-01");
        insertPendingTask("demoa", backfill, planId, nodeId, planDate);
        // 无锚点手工补录（A-FU-014）：实际随访日期落在窗口内 → 按最近计划日期归属第 1 次（OQ-106②）
        insertFloatingBackfill("demoa", backfill, "2026-03-10");
        insertPendingTask("demoa", pending, planId, nodeId, planDate);

        refreshService.refreshForDate(AS_OF);

        // 每年随访率（A-DASH-006）：第 1 年应随访 4、已随访 3（窗口内 1 + 逾期 1 + 补录 1）、窗口内完成 2（含补录）
        Map<?, ?> yearly = data(get(cardPath("yearly_rate"), admin));
        assertEquals(AS_OF.toString(), String.valueOf(yearly.get("asOf")), "asOf = 刷新统计日");
        List<?> yearRows = (List<?>) yearly.get("rows");
        assertEquals(1, yearRows.size(), "只列有应随访患者的年度（第 2 年窗口未结束不排）");
        Map<?, ?> year1 = (Map<?, ?>) yearRows.get(0);
        assertEquals("第1年", year1.get("label"));
        assertEquals(4L, num(year1.get("dueCount")), "应随访 = 甲乙丙丁");
        assertEquals(3L, num(year1.get("followedCount")), "已随访含逾期完成与补录（Q38）");
        assertEquals(2L, num(year1.get("inWindowCount")), "窗口内完成 = 甲（ON_TIME）+ 丙（补录日期在窗口内）");
        assertEquals(75.0d, year1.get("followRatePct"), "随访率主指标 = 3/4");
        assertEquals(50.0d, year1.get("inWindowRatePct"), "窗口内完成率（参考）= 2/4");
        assertEquals(0.0d, year1.get("lostRatePct"));
        assertEquals(0L, num(year1.get("deathCount")));
        assertEquals(List.of(2025), yearly.get("cohortYears"), "队列年份 = 起算（出院）年份");

        // 队列筛选：出院年份 2030 无患者 → 空行集
        Map<?, ?> emptyCohort = data(get(cardPath("yearly_rate") + "&cohortYear=2030", admin));
        assertEquals(List.of(), emptyCohort.get("rows"));

        // 节点随访率（A-DASH-012 / C-DASH-004）：同一计划同一数字（「与每年随访率的关系」）
        Map<?, ?> node = data(get(cardPath("node_rate") + "&planId=" + planId, admin));
        assertEquals(planId, num(node.get("planId")));
        assertEquals("示例 IT 年度随访计划（" + PLAN_CODE + "-A）", node.get("planName"));
        List<?> nodeRows = (List<?>) node.get("rows");
        assertEquals(1, nodeRows.size());
        Map<?, ?> nodeRow = (Map<?, ?>) nodeRows.get(0);
        assertEquals("第1年", nodeRow.get("label"), "年度循环节点行标签 = 第 N 年");
        assertEquals("电话随访（示例）", nodeRow.get("nodeName"));
        assertEquals(4L, num(nodeRow.get("dueCount")), "同一筛选下与每年随访率一致");
        assertEquals(3L, num(nodeRow.get("followedCount")));
        assertEquals(2L, num(nodeRow.get("inWindowCount")));
        assertEquals(75.0d, nodeRow.get("followRatePct"));
        // 计划候选与缺省计划（A-DASH-012「默认本租户的预置计划」= id 最小的已发布版本）
        List<?> plans = (List<?>) node.get("plans");
        assertTrue(plans.size() >= 2, "候选含预置计划与本用例计划");
        Map<?, ?> defaultCard = data(get(cardPath("node_rate"), admin));
        assertEquals(
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT MIN(id) FROM fu_plan WHERE status = 'PUBLISHED'",
                                        Long.class)),
                num(defaultCard.get("planId")),
                "缺省 = id 最小的已发布计划（预置在前）");

        // 病区筛选：收窄到无患者的病区 → 空行集（范围内再收窄，不报错）
        long absentWard =
                runInTenant(
                        "demoa",
                        () -> jdbc.queryForObject("SELECT MAX(id) + 1000 FROM ward", Long.class));
        Map<?, ?> emptyWard =
                data(
                        get(
                                cardPath("node_rate")
                                        + "&planId="
                                        + planId
                                        + "&wardId="
                                        + absentWard,
                                admin));
        assertEquals(List.of(), emptyWard.get("rows"));

        // 开关（AGENTS §5.7）：关闭 → 40301；每年随访率不受影响
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0"
                                        + " WHERE feature_code = 'node_follow_rate'"));
        try {
            assertEquals(
                    40301,
                    codeOf(get(cardPath("node_rate") + "&planId=" + planId, admin).getBody()),
                    "开关关闭 40301");
            assertEquals(0, codeOf(get(cardPath("yearly_rate"), admin).getBody()), "每年随访率无开关");
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 1"
                                            + " WHERE feature_code = 'node_follow_rate'"));
        }
    }

    // ---------------------------------------------------------------- 功能开关下发（OQ-03 / 前端隐藏入口）

    @Test
    void authFeaturesEndpointExposesSwitchesForEntryHiding() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        ResponseEntity<Map> response = get("/api/hospital/auth/features", admin);
        assertEquals(0, codeOf(response.getBody()));
        Map<?, ?> features = data(response.getBody());
        assertEquals(Boolean.TRUE, features.get("node_follow_rate"), "种子 V100 默认开");
        assertEquals(Boolean.FALSE, features.get("implant"), "demoa 未开 implant");
    }

    // ---------------------------------------------------------------- 校验 / 数据范围 / 双租户

    @Test
    void invalidCardCodeAndParametersRejected() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        assertEquals(40001, codeOf(get(cardPath("no_such_card"), admin).getBody()));
        assertEquals(
                40001,
                codeOf(get(cardPath("yearly_rate") + "&wardId=0", admin).getBody()),
                "wardId 非正整数");
        assertEquals(
                40001,
                codeOf(get(cardPath("node_rate") + "&cohortYear=1700", admin).getBody()),
                "cohortYear 越界");
    }

    /** 数据范围外会话：两张卡片都返回空行集（stats.md §1「范围为空 → 空，不报错」）。 */
    @Test
    void outOfScopeSessionSeesEmptyRows() {
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        NodeOccurrenceStatsService.YearlyRateCardView yearly =
                withHospitalSession(outsider, () -> statsService.yearlyRate(null, null));
        assertTrue(yearly.rows().isEmpty(), "范围外会话每年随访率空行集");
        NodeOccurrenceStatsService.NodeRateCardView node =
                withHospitalSession(outsider, () -> statsService.nodeRate(null, null, null));
        assertTrue(node.rows().isEmpty(), "范围外会话节点随访率空行集");
        // 队列候选同样按范围出行
        assertTrue(yearly.cohortYears().isEmpty());
    }

    /** 双租户：demob 卡片只聚合本院事实行（demoa 的第 1 年 4 人不进 demob 计数）。 */
    @Test
    void tenantIsolationOnYearlyRateCounts() {
        String adminA = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(adminA, PLAN_CODE + "-B");
        long patient = registerPatient("demoa", adminA, "I1", "2025-03-15");
        long nodeId = nodeIdOf("demoa", planId);
        insertPendingTask("demoa", patient, planId, nodeId, LocalDate.of(2026, 3, 15));
        refreshService.refreshForDate(AS_OF);

        Map<?, ?> demobYearly =
                data(get(cardPath("yearly_rate"), hospitalToken("demob", "nurse.demo", PASSWORD)));
        long demobDueSum =
                ((List<?>) demobYearly.get("rows"))
                        .stream().mapToLong(row -> num(((Map<?, ?>) row).get("dueCount"))).sum();
        long demobOwnDue =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(DISTINCT CONCAT(patient_id, '-', followup_year))"
                                                + " FROM st_node_occurrence"
                                                + " WHERE due = 1 AND followup_year IS NOT NULL",
                                        Long.class));
        assertEquals(demobOwnDue, demobDueSum, "demob 卡片 = 本院事实表（患者 × 年度去重），不含 demoa 患者");
        assertEquals(
                0L,
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM st_node_occurrence o"
                                                + " JOIN pt_patient p ON p.id = o.patient_id"
                                                + " WHERE p.legacy_no LIKE ?",
                                        Long.class,
                                        LEGACY + "-%")),
                "demoa 患者的事实行不出现在 demob 库");
    }

    // ---------------------------------------------------------------- 辅助（同 NodeOccurrenceStatsIT /
    // DashboardCardsIT 口径）

    private static String cardPath(String cardCode) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode + "?";
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

    /** 登记患者（legacy_no 自清锚点；全部落 MIN(id) 病区）。 */
    private long registerPatient(String tenant, String token, String suffix, String dischargeDate) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E19T04-" + suffix);
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

    /** 服务层入组并回拨 created_at（清掉入组即建任务，任务由用例直接落库保持口径可控）。 */
    private long enrollBackdated(String tenant, long patientId, long planId, LocalDate backdateTo) {
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

    /** 已完成任务（completeKind：ON_TIME 窗口内 / OVERDUE 逾期——Q38 两类都计入已随访）。 */
    private void insertCompletedTask(
            String tenant,
            long patientId,
            long planId,
            long nodeId,
            LocalDate planDate,
            String completeKind,
            String completedOn) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId, LocalDate.of(2025, 4, 1));
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
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', 'COMPLETED', ?,"
                                        + " ?, 0, ?)",
                                enrollmentId,
                                patientId,
                                planId,
                                nodeId,
                                planDate,
                                planDate.minusDays(14),
                                planDate.minusDays(7),
                                planDate.plusDays(7),
                                planDate.minusDays(14),
                                completeKind,
                                completedOn + " 09:00:00",
                                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    /** 待随访任务（只计分母不计分子）。 */
    private void insertPendingTask(
            String tenant, long patientId, long planId, long nodeId, LocalDate planDate) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId, LocalDate.of(2025, 4, 1));
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
                                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    /** 无锚点手工补录记录（A-FU-014：source=BACKFILL、已提交、实际随访日期，无任务 / 节点锚点）。 */
    private void insertFloatingBackfill(String tenant, long patientId, String visitDate) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_record (patient_id, source, visit_date, form_code,"
                                        + " template_version, status)"
                                        + " VALUES (?, 'BACKFILL', ?, 'fu_annual_v1', 1, 'SUBMITTED')",
                                patientId,
                                visitDate));
    }

    /** 登录会话（供 @DataScope 切面以登录快照出行——与 HTTP 请求同一装配链）。 */
    private SessionPrincipal adminPrincipal(String tenant) {
        return new SessionPrincipal(
                "it-token",
                "HOSPITAL",
                "demoa".equals(tenant) ? 1L : 2L,
                tenant,
                tenant + "（示例）",
                1L,
                "nurse.demo",
                "护士长（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
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
                List.of(),
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
