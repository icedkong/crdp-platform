package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.outbox.OutboxDispatcher;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.time.LocalDate;
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
 * E15-T03 端到端 / A-FU-009（完整应用 + 真实 MySQL，HTTP 直连 + 服务层造数）。 验收原文：改派后新责任人收到通知。
 *
 * <p>覆盖：改派（单个与批量共用 batch-assign）——负责人更新、TASK_REASSIGNED 站内信送达新负责人（通知规则引擎消费 outbox）、 汇总审计、逐条跳过口径（越范围
 * TASK_NOT_FOUND / 终态 TASK_NOT_OPEN / 负责人未变 TASK_UNCHANGED / 跨病区 DOCTOR_NOT_IN_WARD / 并发
 * TASK_CONFLICT）； 认领（公共池 assignee NULL → 当前用户、已有负责人 40901、越范围 40400、终态 40901、审计 TASK_CLAIM、不发通知）；
 * 待认领筛选（unassigned=true）；无权限 40300（认领只要求 task:view）；模块关闭 40301；demoa / demob 双租户隔离。后台 outbox 分发停用（1
 * 小时间隔），测试内同步 dispatchForCurrentTenant 保证送达断言确定。门控 CRDP_IT_MYSQL_PORT。全部虚构数据（legacy IT-E15T03-*）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "crdp.outbox.dispatch.interval-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskAssignmentIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T03";
    private static final String PLAN_CODE = "PLAN-IT-E15T03";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private OutboxDispatcher dispatcher;

    @BeforeEach
    @AfterEach
    void cleanState() {
        SecurityContextHolderClear.clear();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN ('TASK_ASSIGN', 'TASK_CLAIM')");
                        jdbc.update("DELETE FROM sys_outbox WHERE event_type = 'TASK_REASSIGNED'");
                        jdbc.update("DELETE FROM ntf_message WHERE event_type = 'TASK_REASSIGNED'");
                        jdbc.update(
                                "DELETE FROM ntf_send_log WHERE event_type = 'TASK_REASSIGNED'");
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
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-E15T03'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u"
                                        + " ON u.id = ur.user_id WHERE u.username LIKE 'IT-U-E15T03-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON ph.user_id = u.id WHERE u.username LIKE 'IT-U-E15T03-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E15T03-%'");
                        for (String roleCode :
                                List.of(
                                        "IT-R-E15T03-TASKVIEW",
                                        "IT-R-E15T03-WARDONLY",
                                        "IT-R-E15T03-NOTASK")) {
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
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 改派：验收主链路（新责任人收到通知）

    @Test
    void reassignNotifiesNewAssigneeByInboxMessage() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "N1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        long doctorId = userIdOf("demoa", "doctor.demo");
        long adminId = userIdOf("demoa", "nurse.demo");
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = ? WHERE id = ?", doctorId, taskId);

        // 改派给护士管理员（TENANT_ADMIN 全院范围，可作负责人；改派人 = 责任医生本人，患者在其病区）
        Map<?, ?> reassigned =
                post(
                        "/api/hospital/followup/tasks/batch-assign",
                        doctor,
                        Map.of(
                                "taskIds",
                                List.of(taskId),
                                "assigneeId",
                                adminId,
                                "reason",
                                "医生休假（示例）"));
        assertEquals(0, code(reassigned), "改派失败：" + reassigned);
        List<Map<?, ?>> items = items(reassigned);
        assertEquals(1, items.size());
        assertEquals("SUCCESS", items.get(0).get("outcome"));
        assertEquals(
                adminId,
                (long)
                        jdbcQuery(
                                "demoa",
                                "SELECT assignee_id FROM fu_task WHERE id = ?",
                                Long.class,
                                taskId),
                "负责人已更新为新责任人");
        assertEquals(
                1,
                jdbcQuery(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_ASSIGN'",
                        Long.class),
                "写一条 TASK_ASSIGN 汇总审计（A-NFR-004 改派记入审计）");

        // 验收：改派后新责任人收到通知（outbox → 通知规则引擎 → 站内信）
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        Map<String, Object> message =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT recipient_id, title, summary, target_type, target_id"
                                                + " FROM ntf_message WHERE event_type ="
                                                + " 'TASK_REASSIGNED' ORDER BY id DESC LIMIT 1"));
        long recipient = ((Number) message.get("recipient_id")).longValue();
        assertEquals(adminId, recipient, "站内信接收人 = 新责任人（A 规格 §7「任务改派 → 新负责人」）");
        assertEquals("TASK", message.get("target_type"));
        assertEquals(taskId, ((Number) message.get("target_id")).longValue());
        assertTrue(
                String.valueOf(message.get("title")).contains("任务改派"),
                "标题为事件名 + 节点：" + message.get("title"));
        assertTrue(
                String.valueOf(message.get("summary")).contains("医生休假（示例）"),
                "摘要含改派原因：" + message.get("summary"));
        assertEquals(
                0,
                jdbcQuery(
                        "demoa",
                        "SELECT COUNT(*) FROM ntf_message WHERE event_type = 'TASK_REASSIGNED'"
                                + " AND recipient_id = ?",
                        Long.class,
                        doctorId),
                "原负责人不再收到改派通知");

        // 负责人未变：不发通知、不改行
        Map<?, ?> again =
                post(
                        "/api/hospital/followup/tasks/batch-assign",
                        doctor,
                        Map.of("taskIds", List.of(taskId), "assigneeId", adminId));
        assertEquals(0, code(again));
        assertEquals("TASK_UNCHANGED", items(again).get(0).get("reason"), "负责人未变按跳过返回");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox WHERE event_type ="
                                                + " 'TASK_REASSIGNED'",
                                        Long.class)),
                "未变行不重复发事件（只有一个事件）");
    }

    // ---------------------------------------------------------------- 改派：逐条跳过口径

    @Test
    void reassignReportsPerItemOutcomes() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long doctorId = userIdOf("demoa", "doctor.demo");
        // 跨病区改派目标：启用用户，但数据范围 WARD 无病区归属、非全院（不负责患者病区）
        newUser(
                "demoa",
                "IT-U-E15T03-CROSSWARD",
                roleWithMenus("IT-R-E15T03-WARDONLY", List.of(22L), "WARD"));
        long crossWardUser = userIdOf("demoa", "IT-U-E15T03-CROSSWARD");
        long inScope =
                enrollCurrentTask(
                        "demoa",
                        registerPatient("demoa", doctor, "B1", today.minusDays(20)),
                        planId);
        long outScope =
                enrollCurrentTask(
                        "demoa",
                        registerPatientInWard(
                                "demoa", admin, "B2", today.minusDays(20), ensureOtherWard()),
                        planId);
        long terminal =
                enrollCurrentTask(
                        "demoa",
                        registerPatient("demoa", doctor, "B3", today.minusDays(20)),
                        planId);
        long unchanged =
                enrollCurrentTask(
                        "demoa",
                        registerPatient("demoa", doctor, "B4", today.minusDays(20)),
                        planId);
        long crossWardTarget =
                enrollCurrentTask(
                        "demoa",
                        registerPatient("demoa", doctor, "B5", today.minusDays(20)),
                        planId);
        jdbcUpdate("demoa", "UPDATE fu_task SET status = 'COMPLETED' WHERE id = ?", terminal);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET assignee_id = ? WHERE id = ?",
                crossWardUser,
                unchanged);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET assignee_id = ? WHERE id = ?",
                doctorId,
                crossWardTarget);

        // 改派人 = 医生（本病区患者可见；越范围 B2 不暴露存在）。目标 = 跨病区用户：四条全部跳过、零事件
        Map<?, ?> result =
                post(
                        "/api/hospital/followup/tasks/batch-assign",
                        doctor,
                        Map.of(
                                "taskIds",
                                List.of(outScope, terminal, unchanged, crossWardTarget),
                                "assigneeId",
                                crossWardUser));
        assertEquals(0, code(result), "批量改派失败：" + result);
        List<Map<?, ?>> items = items(result);
        assertEquals(4, items.size(), "逐条结果与请求顺序一致");
        assertEquals("TASK_NOT_FOUND", items.get(0).get("reason"), "越范围任务不暴露存在");
        assertNull(items.get(0).get("patientName"), "越范围行不带患者摘要");
        assertEquals("TASK_NOT_OPEN", items.get(1).get("reason"), "终态任务不可改派");
        assertEquals("TASK_UNCHANGED", items.get(2).get("reason"), "负责人未变跳过");
        assertEquals("DOCTOR_NOT_IN_WARD", items.get(3).get("reason"), "新负责人不负责患者病区且非全院 → 跳过");
        assertEquals(0, ((Number) data(result).get("succeeded")).intValue());
        assertEquals(4, ((Number) data(result).get("skipped")).intValue());
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox WHERE event_type ="
                                                + " 'TASK_REASSIGNED'",
                                        Long.class)),
                "跳过行不发通知事件");

        // 目标换全院管理员（可作负责人）：范围内未完成任务成功改派，行内姓名脱敏
        Map<?, ?> success =
                post(
                        "/api/hospital/followup/tasks/batch-assign",
                        doctor,
                        Map.of(
                                "taskIds",
                                List.of(inScope),
                                "assigneeId",
                                userIdOf("demoa", "nurse.demo")));
        assertEquals(0, code(success), "改派失败：" + success);
        assertEquals("SUCCESS", items(success).get(0).get("outcome"));
        assertTrue(
                String.valueOf(items(success).get(0).get("patientName")).contains("*"),
                "成功行姓名脱敏：" + items(success).get(0).get("patientName"));
        assertEquals(1, ((Number) data(success).get("succeeded")).intValue());

        // 参数校验：空清单 / 停用负责人 40001
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/tasks/batch-assign",
                                doctor,
                                Map.of("assigneeId", doctorId))),
                "空任务清单 40001");
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/tasks/batch-assign",
                                doctor,
                                Map.of("taskIds", List.of(inScope), "assigneeId", 999999999L))),
                "不存在 / 停用负责人 40001");
    }

    // ---------------------------------------------------------------- 认领（公共池）

    @Test
    void claimTakesPoolTaskWritesAuditAndSendsNoNotice() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "C1", today.minusDays(20));
        long poolTask = enrollCurrentTask("demoa", patientId, planId);
        long doctorId = userIdOf("demoa", "doctor.demo");
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", poolTask);

        Map<?, ?> claimed =
                post("/api/hospital/followup/tasks/" + poolTask + "/claim", doctor, Map.of());
        assertEquals(0, code(claimed), "认领失败：" + claimed);
        assertEquals(
                doctorId,
                (long)
                        jdbcQuery(
                                "demoa",
                                "SELECT assignee_id FROM fu_task WHERE id = ?",
                                Long.class,
                                poolTask),
                "认领后负责人 = 当前登录用户");
        assertEquals(
                1,
                jdbcQuery(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_CLAIM'",
                        Long.class),
                "写一条 TASK_CLAIM 审计（A-NFR-004 认领记入审计）");

        // 认领后进入「我的待办」
        Map<?, ?> mine =
                post("/api/hospital/followup/tasks/search", doctor, Map.of("scope", "MINE"));
        assertTrue(ids(mine).contains(String.valueOf(poolTask)), "认领后进入我的待办");

        // 已有负责人：40901（走改派）；本人重复认领同样 40901
        assertEquals(
                40901,
                code(post("/api/hospital/followup/tasks/" + poolTask + "/claim", admin, Map.of())),
                "已有负责人 40901");
        assertEquals(
                40901,
                code(post("/api/hospital/followup/tasks/" + poolTask + "/claim", doctor, Map.of())),
                "重复认领 40901");

        // 终态任务与越范围任务：40901 / 40400
        long terminal =
                enrollCurrentTask(
                        "demoa",
                        registerPatient("demoa", doctor, "C2", today.minusDays(20)),
                        planId);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET status = 'COMPLETED', assignee_id = NULL WHERE id = ?",
                terminal);
        assertEquals(
                40901,
                code(post("/api/hospital/followup/tasks/" + terminal + "/claim", doctor, Map.of())),
                "终态任务认领 40901");
        long outScope =
                enrollCurrentTask(
                        "demoa",
                        registerPatientInWard(
                                "demoa", admin, "C3", today.minusDays(20), ensureOtherWard()),
                        planId);
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", outScope);
        assertEquals(
                40400,
                code(post("/api/hospital/followup/tasks/" + outScope + "/claim", doctor, Map.of())),
                "越范围任务认领 40400（不暴露存在）");
        assertEquals(
                40400,
                code(post("/api/hospital/followup/tasks/999999999/claim", doctor, Map.of())),
                "不存在的任务认领 40400");

        // 认领是本人自取：不产生 TASK_REASSIGNED 事件（不给自己发通知）
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox WHERE event_type ="
                                                + " 'TASK_REASSIGNED'",
                                        Long.class)),
                "认领不发改派通知");
    }

    // ---------------------------------------------------------------- 待认领筛选

    @Test
    void searchUnassignedFilterReturnsOnlyPoolTasks() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "U1", today.minusDays(20));
        long assigned = enrollCurrentTask("demoa", patientId, planId);
        long pool = enrollOccurrenceTask("demoa", patientId, planId, 2, today.plusDays(10));
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", pool);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET assignee_id = ? WHERE id = ?",
                userIdOf("demoa", "doctor.demo"),
                assigned);

        Map<?, ?> unassigned =
                post(
                        "/api/hospital/followup/tasks/search",
                        doctor,
                        Map.of("scope", "ALL", "unassigned", true));
        assertEquals(0, code(unassigned), "检索失败：" + unassigned);
        List<String> ids = ids(unassigned);
        assertTrue(ids.contains(String.valueOf(pool)), "待认领任务在筛选结果内");
        assertFalse(ids.contains(String.valueOf(assigned)), "已有负责人的任务被过滤");
        for (Map<?, ?> row : items(unassigned)) {
            assertNull(row.get("assigneeName"), "待认领行负责人为空（前端显示「待认领」）");
        }

        Map<?, ?> all = post("/api/hospital/followup/tasks/search", doctor, Map.of("scope", "ALL"));
        assertTrue(
                ids(all).containsAll(List.of(String.valueOf(pool), String.valueOf(assigned))),
                "不带筛选两条件都在");
    }

    // ---------------------------------------------------------------- 权限与模块开关

    @Test
    void permissionAndModuleGateAreEnforced() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "G1", today.minusDays(20));
        long poolTask = enrollCurrentTask("demoa", patientId, planId);
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", poolTask);

        // 只授任务列表（22 + 223，followup:task:view）：认领可用（自助操作）、改派 40300
        long taskViewRoleId = roleWithMenus("IT-R-E15T03-TASKVIEW", List.of(22L, 223L), "ALL");
        String taskViewToken = newUser("demoa", "IT-U-E15T03-TASKVIEW", taskViewRoleId);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + poolTask + "/claim",
                                taskViewToken,
                                Map.of())),
                "认领只要求 followup:task:view（docs/api/followup.md §2）");
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", poolTask);
        assertEquals(
                40300,
                code(
                        post(
                                "/api/hospital/followup/tasks/batch-assign",
                                taskViewToken,
                                Map.of(
                                        "taskIds",
                                        List.of(poolTask),
                                        "assigneeId",
                                        userIdOf("demoa", "doctor.demo")))),
                "无 followup:task:assign 改派 40300");

        // 完全无任务权限：认领也 40300
        long noTaskRoleId = roleWithMenus("IT-R-E15T03-NOTASK", List.of(10L), "ALL");
        String noTaskToken = newUser("demoa", "IT-U-E15T03-NOTASK", noTaskRoleId);
        assertEquals(
                40300,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + poolTask + "/claim",
                                noTaskToken,
                                Map.of())),
                "无 followup:task:view 认领 40300");

        // 模块关闭：认领与改派都 40301
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(
                            post(
                                    "/api/hospital/followup/tasks/" + poolTask + "/claim",
                                    doctor,
                                    Map.of())),
                    "模块关闭认领 40301");
            assertEquals(
                    40301,
                    code(
                            post(
                                    "/api/hospital/followup/tasks/batch-assign",
                                    doctor,
                                    Map.of("taskIds", List.of(poolTask), "assigneeId", 1L))),
                    "模块关闭改派 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 双租户隔离

    @Test
    void assignmentIsIsolatedPerTenant() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "D1", today.minusDays(20));
        long poolTask = enrollCurrentTask("demoa", patientId, planId);
        jdbcUpdate("demoa", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", poolTask);

        // demoa 的任务在 demob 独立库中不存在：认领 40400、改派逐条 TASK_NOT_FOUND（不暴露存在）
        String demobDoctor = hospitalToken("demob", "doctor.demo", PASSWORD);
        assertEquals(
                0,
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE id = " + poolTask,
                                        Long.class)),
                "前提：demoa 任务 id 在 demob 库不存在");
        assertEquals(
                40400,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + poolTask + "/claim",
                                demobDoctor,
                                Map.of())),
                "跨租户认领 40400");
        Map<?, ?> cross =
                post(
                        "/api/hospital/followup/tasks/batch-assign",
                        demobDoctor,
                        Map.of(
                                "taskIds",
                                List.of(poolTask),
                                "assigneeId",
                                userIdOf("demob", "doctor.demo")));
        assertEquals(0, code(cross));
        assertEquals("TASK_NOT_FOUND", items(cross).get(0).get("reason"), "跨租户改派逐条 TASK_NOT_FOUND");
        assertNull(
                jdbcQuery(
                        "demoa",
                        "SELECT assignee_id FROM fu_task WHERE id = ?",
                        Long.class,
                        poolTask),
                "demoa 的任务不受 demob 操作影响");

        // demob 内自成闭环：本租户建任务 → 认领成功
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        long demobPlanId = publishedPlanInTenant("demob", demobAdmin);
        long demobPatientId = registerPatient("demob", demobDoctor, "D2", today.minusDays(20));
        long demobTask = enrollCurrentTask("demob", demobPatientId, demobPlanId);
        jdbcUpdate("demob", "UPDATE fu_task SET assignee_id = NULL WHERE id = ?", demobTask);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + demobTask + "/claim",
                                demobDoctor,
                                Map.of())),
                "demob 内认领成功");
    }

    // ---------------------------------------------------------------- 造数与工具（与 FollowupWorkbenchIT
    // 同口径）

    /** 发布计划：单节点电话随访（偏移 20 天、窗口 ±3 天、FORM A_ANNUAL_FU）——起算 T−20 时窗口覆盖今天。 */
    private long publishedWindowPlan(String token) {
        return publishedPlanInTenant("demoa", token);
    }

    private long publishedPlanInTenant(String tenant, String token) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 认领改派计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        Map<?, ?> created = post("/api/hospital/followup/plans", token, body);
        assertEquals(0, code(created), "新建计划失败：" + created);
        long planId = Long.parseLong((String) data(created).get("id"));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "循环随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 365);
        subplan.put("cycleUnit", "DAY");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 3);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 3);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 3);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "A_ANNUAL_FU");
        node.put("sortNo", 20);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                code(post("/api/hospital/followup/plans/" + planId + "/publish", token, Map.of())),
                "发布失败");
        return planId;
    }

    /** 登记患者（默认病区 = 医生病区，范围内）。 */
    private long registerPatient(String tenant, String token, String suffix, LocalDate discharge) {
        return register(tenant, token, LEGACY + "-" + suffix, defaultWardId(tenant), discharge);
    }

    private long registerPatientInWard(
            String tenant, String token, String suffix, LocalDate discharge, long wardId) {
        return register(tenant, token, LEGACY + "-" + suffix, wardId, discharge);
    }

    private long register(
            String tenant, String token, String legacyNo, long wardId, LocalDate discharge) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周认领（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(3).toString());
        body.put("surgeryDate", discharge.minusDays(2).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 入组（E14-T05 入组即建当期任务：起算 T−20 + 偏移 20 → 计划日期 = 今天、窗口 ±3 已开启）。 */
    private long enrollCurrentTask(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    return jdbc.queryForObject(
                            "SELECT id FROM fu_task WHERE enrollment_id = ? ORDER BY id DESC LIMIT 1",
                            Long.class,
                            enrollmentId);
                });
    }

    /** 直接落指定次数的任务行（待认领筛选要同一患者两条任务；uk = 入组 + 节点 + 第 N 次）。 */
    private long enrollOccurrenceTask(
            String tenant, long patientId, long planId, int occurrence, LocalDate planDate) {
        long enrollmentId = enrollmentIdOf(tenant, patientId, planId);
        return runInTenant(
                tenant,
                () -> {
                    Long existing =
                            jdbc.query(
                                    "SELECT id FROM fu_task WHERE enrollment_id = ? AND occurrence = ?",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null,
                                    enrollmentId,
                                    occurrence);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                    + " occurrence, task_type, form_code, owner_type, plan_date,"
                                    + " remind_start, window_start, window_end, open_date,"
                                    + " create_type, status, created_logical_date)"
                                    + " SELECT ?, ?, plan_id, id, ?, 'FORM', form_code,"
                                    + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', 'PENDING', ?"
                                    + " FROM fu_plan_node WHERE plan_id = ? LIMIT 1",
                            enrollmentId,
                            patientId,
                            occurrence,
                            planDate,
                            planDate.minusDays(3),
                            planDate.minusDays(3),
                            planDate.plusDays(3),
                            planDate.minusDays(3),
                            planDate.minusDays(3),
                            planId);
                    return jdbc.queryForObject(
                            "SELECT id FROM fu_task WHERE enrollment_id = ? AND occurrence = ?",
                            Long.class,
                            enrollmentId,
                            occurrence);
                });
    }

    /** 该患者在计划的在组入组 id（无则入组一次）。 */
    private long enrollmentIdOf(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    Long existing =
                            jdbc.query(
                                    "SELECT id FROM fu_enrollment WHERE patient_id = ? AND plan_id = ?"
                                            + " AND status = 'ACTIVE'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null,
                                    patientId,
                                    planId);
                    if (existing != null) {
                        return existing;
                    }
                    return Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                });
    }

    /** 建角色并授权菜单与数据范围（scopeType = ALL / WARD）。 */
    private long roleWithMenus(String roleCode, List<Long> menuIds, String scopeType) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status) VALUES (?, 'IT 角色（示例）', 0, 'ENABLED')",
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
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)",
                            roleId,
                            scopeType);
                    return roleId;
                });
    }

    /** 建用户（走系统接口，首密改口令后登录）并返回 token。 */
    private String newUser(String tenant, String username, long roleId) {
        long deptId =
                runInTenant(
                        tenant,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalToken(tenant, "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 用户（示例）",
                                "phone",
                                "1390000133" + Math.abs(username.hashCode() % 10),
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken(tenant, username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken(tenant, username, PASSWORD);
    }

    private long userIdOf(String tenant, String username) {
        return jdbcQuery(
                tenant, "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private void jdbcUpdate(String tenant, String sql, Object... args) {
        runInTenant(tenant, () -> jdbc.update(sql, args));
    }

    private <T> T jdbcQuery(String tenant, String sql, Class<T> type, Object... args) {
        return runInTenant(tenant, () -> jdbc.queryForObject(sql, type, args));
    }

    /** 越范围病区（医生数据范围外；存在即复用，cleanState 删除）。 */
    private long ensureOtherWard() {
        return runInTenant(
                "demoa",
                () -> {
                    Long existing =
                            jdbc.query(
                                    "SELECT id FROM ward WHERE code = 'W-IT-E15T03'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status)"
                                    + " VALUES ('W-IT-E15T03', 'IT 越范围病区（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-E15T03'", Long.class);
                });
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
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
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<?, ?> response = rest.exchange(path, method, entity, Map.class).getBody();
        return response == null ? Map.of() : response;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> items(Map<?, ?> response) {
        return (List<Map<?, ?>>) data(response).get("items");
    }

    private static List<String> ids(Map<?, ?> response) {
        return items(response).stream().map(item -> String.valueOf(item.get("id"))).toList();
    }

    /** 测试内会话清理（服务层造数可能装配认证上下文）。 */
    static final class SecurityContextHolderClear {
        private SecurityContextHolderClear() {}

        static void clear() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
