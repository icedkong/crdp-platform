package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.app.PlanService;
import com.crdp.followup.stats.app.NodeOccurrenceRefreshService;
import com.crdp.followup.stats.app.NodeOccurrenceStatsService;
import com.crdp.followup.task.app.FollowupTaskCreationService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E19-T01 端到端（tasks.csv 验收「按节点出应随访、已随访数」）。A 年度形态（虚构）：起算 2025-03-15、±7 天窗口， 第 1 年窗口 2026-03-08 ~
 * 2026-03-22，统计日 2026-06-01（窗口已结束）：
 *
 * <ol>
 *   <li>患者甲：任务已完成（ON_TIME）→ 应随访 + 已随访；
 *   <li>患者乙：任务待随访 → 应随访、未随访（分母计入、分子不计）；
 *   <li>患者丙：窗口开始日（03-08）前已死亡（状态时间线 03-01 生效）→ 不在应随访（窗口开始日回看， Q38）；无任务 → 事实行来自计划推算（due_reason =
 *       NOT_QUALIFIED）；
 *   <li>每日刷新（00:10 调度在任务创建后调用，st-node-refresh）重建整表幂等；job_tenant_run 记 SUCCESS；
 *   <li>查询服务按节点 × 第 N 次出应随访 / 已随访数（{@code @DataScope}：全院管理员见 2/1；范围外病区会话见 空——统计按当前用户范围，范围为空返回空不报错）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E19T01 前缀、 病案号 IT-E19T01-
 * 前缀、job_tenant_run 本作业行、st_node_occurrence 本类患者行、STATS_NODE_REFRESH 审计）。 全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeOccurrenceStatsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T01";
    private static final String PLAN_CODE = "PLAN-IT-E19T01";
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanService planService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private NodeOccurrenceRefreshService refreshService;
    @Autowired private NodeOccurrenceStatsService statsService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 单计划世界：关掉 E23-T02 预置计划的常驻自动入组（口径同 FollowupTaskSchedulerIT）
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        // 审计先行（target 子查询要用还没删的行）
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
                                        + " OR action = 'STATS_NODE_REFRESH'"
                                        + " OR (action = 'TASK_CREATE' AND detail LIKE '%每日调度%')");
                        jdbc.update(
                                "DELETE FROM st_node_occurrence WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM job_tenant_run WHERE job_code = 'st-node-refresh'");
                        jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
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
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：按节点出应随访、已随访数

    @Test
    void dailyRefreshProducesPerNodeDueAndFollowedCounts() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin, PLAN_CODE + "-A");
        long done = registerPatient("demoa", admin, "F1", "2025-03-15");
        long pending = registerPatient("demoa", admin, "F2", "2025-03-15");
        long dead = registerPatient("demoa", admin, "F3", "2025-03-15");
        enrollBackdated("demoa", done, planId, LocalDate.of(2025, 4, 1));
        enrollBackdated("demoa", pending, planId, LocalDate.of(2025, 4, 1));
        enrollBackdated("demoa", dead, planId, LocalDate.of(2025, 4, 1));
        markDeadBeforeWindowStart("demoa", dead, LocalDate.of(2026, 3, 1));

        // 开启日（2026-03-08）跑任务创建：甲乙照常创建，丙已死亡不建（A-FU-007）
        creationService.runForDate(LocalDate.of(2026, 3, 8));
        assertEquals(1, taskCount("demoa", done));
        assertEquals(1, taskCount("demoa", pending));
        assertEquals(0, taskCount("demoa", dead), "死亡患者不再创建任务");
        // 甲完成随访（窗口内提交，E15-T01 口径）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_task SET status = 'COMPLETED', complete_kind = 'ON_TIME',"
                                        + " completed_at = '2026-03-15 09:00:00'"
                                        + " WHERE patient_id = ?",
                                done));

        // 每日刷新（00:10 调度内、任务创建之后）
        refreshService.refreshForDate(AS_OF);

        // 事实行：按节点第 1 次一人一行，口径见类注释
        Map<String, Object> doneRow = factRow("demoa", done);
        assertEquals(1, ((Number) doneRow.get("occurrence")).intValue());
        assertEquals(1, ((Number) doneRow.get("due")).intValue(), "甲：应随访");
        assertEquals(1, ((Number) doneRow.get("followed")).intValue(), "甲：已随访");
        assertEquals("ON_TIME", doneRow.get("complete_kind"));
        assertEquals(1, ((Number) doneRow.get("in_window")).intValue());
        assertEquals(1, ((Number) doneRow.get("followup_year")).intValue(), "年度节点随访年度 = 第 N 次");
        assertEquals(2025, ((Number) doneRow.get("cohort_year")).intValue(), "队列年份 = 起算年份");
        assertEquals(AS_OF, date(doneRow.get("refreshed_date")));
        Map<String, Object> pendingRow = factRow("demoa", pending);
        assertEquals(1, ((Number) pendingRow.get("due")).intValue(), "乙：应随访（未完成不计分子）");
        assertEquals(0, ((Number) pendingRow.get("followed")).intValue(), "乙：未随访");
        Map<String, Object> deadRow = factRow("demoa", dead);
        assertEquals(0, ((Number) deadRow.get("due")).intValue(), "丙：窗口开始日前已死亡不在应随访");
        assertEquals("NOT_QUALIFIED", deadRow.get("due_reason"));
        assertEquals(null, deadRow.get("task_id"), "丙无任务：事实行来自计划推算（窗口已结束即出行，暂停 / 死亡不漏分母口径）");

        // 执行台账 SUCCESS + 一条汇总审计
        assertEquals(
                "SUCCESS",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status FROM job_tenant_run"
                                                + " WHERE job_code = 'st-node-refresh'"
                                                + " AND run_date = ?",
                                        String.class,
                                        AS_OF)));
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log"
                                                + " WHERE action = 'STATS_NODE_REFRESH'",
                                        Integer.class)));

        // 重复刷新幂等：整表重建，行数不变
        refreshService.refreshForDate(AS_OF);
        assertEquals(
                3,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM st_node_occurrence o"
                                                + " JOIN pt_patient p ON p.id = o.patient_id"
                                                + " WHERE p.legacy_no LIKE ?",
                                        Integer.class,
                                        LEGACY + "-%")),
                "三名患者第 1 次各一行（第 2 次窗口未结束不排）");

        // 验收口径：按节点出应随访、已随访数（全院管理员范围）
        List<NodeOccurrenceStatsService.NodeCountView> counts =
                withHospitalSession(adminPrincipal("demoa"), () -> statsService.nodeCounts(planId));
        assertEquals(1, counts.size(), "单节点单次数一行");
        NodeOccurrenceStatsService.NodeCountView count = counts.get(0);
        assertEquals(2, count.dueCount(), "应随访 = 甲 + 乙");
        assertEquals(1, count.followedCount(), "已随访 = 甲");
        assertEquals(1, count.inWindowCount(), "窗口内完成（参考）= 甲");
        assertEquals(0, count.lostCount());
        assertEquals(0, count.deathCount(), "丙不在应随访，死亡人数不计（窗口开始前已死亡）");
        assertEquals(1, count.occurrence());

        // 数据范围：范围外病区的会话见空（统计按当前用户范围；范围为空返回空列表不报错）
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        List<NodeOccurrenceStatsService.NodeCountView> scoped =
                withHospitalSession(outsider, () -> statsService.nodeCounts(planId));
        assertTrue(scoped.isEmpty(), "病区范围外的会话查不到任何节点计数");
    }

    // ---------------------------------------------------------------- 辅助（同 FollowupTaskSchedulerIT
    // 口径）

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
        body.put("medicalRecordNo", "IT-E19T01-" + suffix);
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

    /** 服务层入组并回拨 created_at 到入组早期（清掉入组即建任务，保持调度时序语义）。 */
    private void enrollBackdated(String tenant, long patientId, long planId, LocalDate backdateTo) {
        runInTenant(
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
                });
    }

    /** 窗口开始日前死亡：患者状态置 DEAD + 状态时间线写生效行（统计按窗口开始日回看，V39）。 */
    private void markDeadBeforeWindowStart(String tenant, long patientId, LocalDate effective) {
        runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "UPDATE pt_patient SET followup_status = 'DEAD' WHERE id = ?",
                            patientId);
                    jdbc.update(
                            "INSERT INTO pt_status_history"
                                    + " (patient_id, status, effective_date, reason, source)"
                                    + " VALUES (?, 'DEAD', ?, 'IT 用例死亡（示例）', 'MANUAL')",
                            patientId,
                            effective);
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

    private Map<String, Object> factRow(String tenant, long patientId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForMap(
                                "SELECT * FROM st_node_occurrence WHERE patient_id = ?"
                                        + " ORDER BY occurrence LIMIT 1",
                                patientId));
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
        // 复用登录后的会话：通过 /me 不额外暴露；此处直接以全院范围构造（nurse.demo 为租户管理员，看全院）
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

    /** JDBC 取回的 DATE 列统一换算 LocalDate 再断言。 */
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
