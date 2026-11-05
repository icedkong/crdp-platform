package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.record.app.FollowupRecordService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E15-T02 端到端 / A-FU-004、A-FU-005、C-FU-020（完整应用 + 真实 MySQL，HTTP 直连 + 服务层造数）。
 * 验收原文：工作台可直接录入随访表单；任务列表按状态 / 病区筛选、默认我的待办。
 *
 * <p>覆盖：任务列表（页签 scope / 状态筛选 / 脱敏行 / 页签计数 / 数据范围出行——越范围患者任务不可见）； 开始录入（创建草稿 + 进行中 + 幂等 + REMINDER
 * 40901 + 越范围 40400）；草稿保存联动「任务变进行中」（OQ-77②）；记录详情（上下文 + 脱敏）；提交（校验失败 42200、成功后任务完成 + 完成方式 + 字段历史 +
 * 审计、重复提交 40901、是否死亡 = 是终止未开始任务 OQ-79②）；队列上一条 / 下一条；无权限 40300； 模块关闭 40301；demoa / demob 双租户隔离。门控
 * CRDP_IT_MYSQL_PORT。全部虚构数据（legacy IT-E15T02-*）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowupWorkbenchIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T02";
    private static final String PLAN_CODE = "PLAN-IT-E15T02";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupRecordService recordService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        SecurityContextHolder.clearContext();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type IN"
                                        + " ('TASK_START', 'RECORD_SUBMIT', 'TASK_PROGRESS',"
                                        + " 'TASK_COMPLETE', 'PATIENT_DEATH_TERMINATE')"
                                        + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                        + " JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%'))"
                                        + " OR ((event_type = 'RECORD_SUBMIT'"
                                        + " OR event_type = 'RECORD_BACKFILL_CREATE')"
                                        + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                        + " FROM fu_record r LEFT JOIN fu_task t ON t.id = r.task_id"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%'))"
                                        + " OR (event_type LIKE 'PLAN_%'"
                                        + " AND (detail LIKE '%"
                                        + PLAN_CODE
                                        + "%'"
                                        + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE '"
                                        + PLAN_CODE
                                        + "%')))");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE module = 'followup'"
                                        + " AND access_method = 'BATCH_QUERY' AND object_type = 'TASK'");
                        jdbc.update(
                                "DELETE fh FROM fe_field_history fh"
                                        + " JOIN fu_record fr ON fr.id = fh.record_id"
                                        + " JOIN pt_patient p ON p.id = fr.patient_id"
                                        + " WHERE fh.record_type = 'FU_RECORD'"
                                        + " AND p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE fr FROM fu_record fr JOIN pt_patient p"
                                        + " ON p.id = fr.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
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
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-E15T02'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u"
                                        + " ON u.id = ur.user_id WHERE u.username LIKE 'IT-U-E15T02-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username LIKE 'IT-U-E15T02-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E15T02-%'");
                        for (String roleCode :
                                List.of("IT-R-E15T02-TASKVIEW", "IT-R-E15T02-NOTASK")) {
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

    // ---------------------------------------------------------------- 任务列表（A-FU-004）

    @Test
    void searchDefaultsToMyOpenTasksWithMaskedPatient() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "S1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET assignee_id = ? WHERE id = ?",
                userIdOf("demoa", "doctor.demo"),
                taskId);

        Map<?, ?> response =
                post("/api/hospital/followup/tasks/search", doctor, Map.of("scope", "MINE"));
        assertEquals(0, code(response), "检索失败：" + response);
        List<Map<?, ?>> items = items(response);
        assertEquals(1, items.size(), "我的待办恰好一条（默认页签）：" + items);
        Map<?, ?> row = items.get(0);
        assertEquals(String.valueOf(taskId), row.get("id"));
        assertTrue(
                String.valueOf(row.get("patientName")).contains("*"),
                "姓名脱敏（列表不提供逐行明文）：" + row.get("patientName"));
        assertTrue(String.valueOf(row.get("mrn")).contains("*"), "病案号脱敏：" + row.get("mrn"));
        assertEquals("PENDING", row.get("status"));
        assertEquals("电话随访（示例）", row.get("nodeName"));
        assertNotNull(row.get("windowStart"), "窗口起止随行返回");
        assertEquals(1, ((Number) data(response).get("total")).longValue(), "计数与行数一致（当前筛选）");
        Map<?, ?> tabs = (Map<?, ?>) data(response).get("tabCounts");
        assertEquals(1, ((Number) tabs.get("mine")).longValue(), "页签徽标：我的待办 1");
        assertEquals(1, ((Number) tabs.get("ward")).longValue(), "页签徽标：本病区 1（患者在医生病区）");
        assertFalse(((List<?>) data(response).get("wards")).isEmpty(), "病区候选随页返回");
    }

    @Test
    void searchRespectsDataScopeAndStatusFilter() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long inScope = registerPatient("demoa", doctor, "S2", today.minusDays(20));
        long inScopeTask = enrollCurrentTask("demoa", inScope, planId);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET assignee_id = ? WHERE id = ?",
                userIdOf("demoa", "doctor.demo"),
                inScopeTask);
        // 越范围患者：独立病区（医生数据范围 WARD + RESPONSIBLE_PATIENT，不含该病区）
        long otherWardId = ensureOtherWard();
        long outScope =
                registerPatientInWard("demoa", admin, "S3", today.minusDays(20), otherWardId);
        long outScopeTask = enrollCurrentTask("demoa", outScope, planId);

        Map<?, ?> doctorAll =
                post("/api/hospital/followup/tasks/search", doctor, Map.of("scope", "ALL"));
        assertEquals(0, code(doctorAll), "检索失败：" + doctorAll);
        List<String> doctorIds = ids(doctorAll);
        assertTrue(doctorIds.contains(String.valueOf(inScopeTask)), "范围内任务可见");
        assertFalse(
                doctorIds.contains(String.valueOf(outScopeTask)),
                "越范围患者的任务不可见（数据范围出行，AGENTS §5.2）");

        Map<?, ?> adminAll =
                post("/api/hospital/followup/tasks/search", admin, Map.of("scope", "ALL"));
        assertTrue(
                ids(adminAll)
                        .containsAll(
                                List.of(String.valueOf(inScopeTask), String.valueOf(outScopeTask))),
                "ALL 范围角色两条都可见");

        Map<?, ?> statusFiltered =
                post(
                        "/api/hospital/followup/tasks/search",
                        doctor,
                        Map.of("scope", "ALL", "status", List.of("COMPLETED")));
        assertEquals(0, ((Number) data(statusFiltered).get("total")).longValue(), "状态筛选：无已完成任务");

        jdbcUpdate("demoa", "UPDATE fu_task SET status = 'COMPLETED' WHERE id = ?", inScopeTask);
        Map<?, ?> completedOnly =
                post(
                        "/api/hospital/followup/tasks/search",
                        doctor,
                        Map.of("scope", "MINE", "status", List.of("COMPLETED")));
        assertEquals(
                1,
                ((Number) data(completedOnly).get("total")).longValue(),
                "显式选状态时我的待办不再强制未完成（可回看已完成）");
    }

    // ---------------------------------------------------------------- 队列（上一条 / 下一条）

    @Test
    void queueWalksTheFilteredListOrder() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        // 起算 T−20：入组即建第 1 次（计划日期 = 今天、窗口已开启，E14-T05）；再手工落第 2 / 3 次控制顺序
        long patientId = registerPatient("demoa", doctor, "Q1", today.minusDays(20));
        long current = enrollCurrentTask("demoa", patientId, planId);
        long earlier = enrollOccurrenceTask("demoa", patientId, planId, 2, today.minusDays(2));
        long later = enrollOccurrenceTask("demoa", patientId, planId, 3, today.plusDays(2));

        Map<?, ?> queue =
                get(
                        "/api/hospital/followup/tasks/queue?cursor="
                                + current
                                + "&scope=ALL&status=PENDING",
                        doctor);
        assertEquals(0, code(queue), "队列失败：" + queue);
        assertEquals(String.valueOf(earlier), data(queue).get("prevId"), "上一条 = 计划日期更早");
        assertEquals(String.valueOf(later), data(queue).get("nextId"), "下一条 = 计划日期更晚");

        Map<?, ?> head =
                get("/api/hospital/followup/tasks/queue?cursor=" + earlier + "&scope=ALL", doctor);
        assertNull(data(head).get("prevId"), "队首无上一条");
        assertEquals(String.valueOf(current), data(head).get("nextId"));
    }

    // ---------------------------------------------------------------- 开始录入 + 详情 + 草稿联动

    @Test
    void startCreatesDraftMovesTaskInProgressAndIsIdempotent() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "W1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);

        Map<?, ?> started =
                post("/api/hospital/followup/tasks/" + taskId + "/start", doctor, Map.of());
        assertEquals(0, code(started), "开始录入失败：" + started);
        String recordId = String.valueOf(data(started).get("recordId"));
        assertFalse(recordId.isBlank(), "统一用 recordId 路由（pages.md §5）");
        assertEquals("IN_PROGRESS", taskStatusOf("demoa", taskId), "任务进入进行中");
        assertEquals("DRAFT", recordStatusOf("demoa", recordId), "草稿记录已创建（fu_record，E15-T07 载体）");

        Map<?, ?> again =
                post("/api/hospital/followup/tasks/" + taskId + "/start", doctor, Map.of());
        assertEquals(0, code(again), "重复开始幂等");
        assertEquals(recordId, String.valueOf(data(again).get("recordId")), "返回同一条草稿");
        assertEquals(
                1,
                jdbcQuery(
                        "demoa",
                        "SELECT COUNT(*) FROM fu_record WHERE id = ?",
                        Long.class,
                        recordId),
                "不重复创建记录");

        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, doctor);
        assertEquals(0, code(detail), "详情失败：" + detail);
        assertEquals(
                String.valueOf(taskId), ((Map<?, ?>) data(detail).get("context")).get("taskId"));
        assertTrue(
                String.valueOf(((Map<?, ?>) data(detail).get("context")).get("patientName"))
                        .contains("*"),
                "上下文姓名脱敏");
        assertEquals("DRAFT", data(detail).get("status"));
    }

    @Test
    void draftSaveMovesPendingTaskToInProgress() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "W2", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        // 直接建草稿并关联（不经 start），保持任务 PENDING——验证 OQ-77② 暂存草稿联动
        long recordId = createLinkedDraft("demoa", taskId);

        Map<?, ?> saved =
                put(
                        "/api/hospital/followup/records/" + recordId + "/draft",
                        doctor,
                        Map.of("baseVersion", 0, "changes", Map.of("f214", today.toString())));
        assertEquals(0, code(saved), "草稿保存失败：" + saved);
        assertEquals("IN_PROGRESS", taskStatusOf("demoa", taskId), "第一次暂存草稿 → 进行中（OQ-77②）");
    }

    @Test
    void reminderTaskAndMissingTaskAreRejected() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "W3", today.minusDays(20));
        long reminderId = enrollCurrentTask("demoa", patientId, planId);
        jdbcUpdate(
                "demoa",
                "UPDATE fu_task SET task_type = 'REMINDER', form_code = NULL WHERE id = ?",
                reminderId);

        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + reminderId + "/start",
                                doctor,
                                Map.of())),
                "站内提醒类任务无需填表（A-FU-026）");
        assertEquals(
                40400,
                code(post("/api/hospital/followup/tasks/999999999/start", doctor, Map.of())),
                "任务不存在 40400");
    }

    // ---------------------------------------------------------------- 提交（A-FU-005「提交后任务完成」）

    @Test
    void submitCompletesTaskWithHistoryAndAudit() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "C1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        String recordId =
                String.valueOf(
                        data(post(
                                        "/api/hospital/followup/tasks/" + taskId + "/start",
                                        doctor,
                                        Map.of()))
                                .get("recordId"));

        // 校验失败：必填缺失（f214 随访日期、f215 是否死亡）→ 42200，任务不动
        Map<?, ?> invalid =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        doctor,
                        Map.of("baseVersion", 0, "data", Map.of("f213", 1)));
        assertEquals(42200, code(invalid), "完整校验（必填缺失拦截）：" + invalid);
        assertEquals("IN_PROGRESS", taskStatusOf("demoa", taskId), "校验失败不流转");

        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        doctor,
                        Map.of(
                                "baseVersion",
                                0,
                                "data",
                                Map.of("f214", today.toString(), "f215", 0, "f213", 1)));
        assertEquals(0, code(submitted), "提交失败：" + submitted);
        assertEquals("COMPLETED", data(submitted).get("taskStatus"), "提交后任务完成（验收位）");
        assertEquals("ON_TIME", data(submitted).get("completeKind"), "窗口内完成");
        assertTrue(
                ((Number) data(submitted).get("fieldChanges")).intValue() > 0,
                "DRAFT→SUBMITTED 字段历史（E11-T06）");

        assertEquals("COMPLETED", taskStatusOf("demoa", taskId));
        assertEquals("ON_TIME", completeKindOf("demoa", taskId));
        assertNotNull(
                jdbcQuery(
                        "demoa",
                        "SELECT completed_by FROM fu_task WHERE id = ?",
                        Long.class,
                        taskId),
                "completed_by = 提交人（followup V53）");
        assertEquals("SUBMITTED", recordStatusOf("demoa", recordId));
        assertTrue(
                jdbcQuery(
                                "demoa",
                                "SELECT COUNT(*) FROM fe_field_history WHERE record_type ="
                                        + " 'FU_RECORD' AND record_id = ?",
                                Long.class,
                                recordId)
                        > 0,
                "fe_field_history 有提交 diff");
        assertEquals(
                1,
                jdbcQuery(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'RECORD_SUBMIT'"
                                + " AND target = ?",
                        Long.class,
                        "FU_RECORD:" + recordId),
                "RECORD_SUBMIT 审计（E15-T07 起载体 fu_record）");

        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/followup/records/" + recordId + "/submit",
                                doctor,
                                Map.of(
                                        "baseVersion",
                                        1,
                                        "data",
                                        Map.of("f214", today.toString(), "f215", 0)))),
                "重复提交 40901");
    }

    @Test
    void submitWithDeathTerminatesUnfinishedTasks() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "C2", today.minusDays(20));
        long current = enrollCurrentTask("demoa", patientId, planId);
        long later = enrollOccurrenceTask("demoa", patientId, planId, 2, today.plusDays(8));
        String recordId =
                String.valueOf(
                        data(post(
                                        "/api/hospital/followup/tasks/" + current + "/start",
                                        doctor,
                                        Map.of()))
                                .get("recordId"));

        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        doctor,
                        Map.of(
                                "baseVersion",
                                0,
                                "data",
                                // f215 = 1（是否死亡 = 是）触发条件必填：f216 死亡日期、f218 死亡原因（E11-T03）
                                Map.of(
                                        "f214",
                                        today.toString(),
                                        "f215",
                                        1,
                                        "f216",
                                        today.toString(),
                                        "f218",
                                        "心梗死亡（示例）")));
        assertEquals(0, code(submitted), "提交失败：" + submitted);

        assertEquals(
                "DEAD",
                jdbcQuery(
                        "demoa",
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "是否死亡 = 是 → 患者进入死亡登记（OQ-79②）");
        assertEquals("TERMINATED", taskStatusOf("demoa", later), "未开始任务全部终止（A-FU-007 验收）");
    }

    // ---------------------------------------------------------------- 权限、开关与双租户

    @Test
    void permissionAndModuleGateAreEnforced() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "P1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);

        // 只授任务列表（22 + 223），不授录入（222）：查得到、开不了
        long taskViewRoleId = roleWithMenus("IT-R-E15T02-TASKVIEW", List.of(22L, 223L));
        String taskViewToken = newUserToken("IT-U-E15T02-TASKVIEW", taskViewRoleId);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/tasks/search",
                                taskViewToken,
                                Map.of("scope", "ALL"))),
                "有任务列表权限可检索");
        assertEquals(
                40300,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + taskId + "/start",
                                taskViewToken,
                                Map.of())),
                "无 followup:record:edit 40300");

        // 完全无任务权限：检索 40300
        long noTaskRoleId = roleWithMenus("IT-R-E15T02-NOTASK", List.of(10L));
        String noTaskToken = newUserToken("IT-U-E15T02-NOTASK", noTaskRoleId);
        assertEquals(
                40300,
                code(post("/api/hospital/followup/tasks/search", noTaskToken, Map.of())),
                "无 followup:task:view 40300");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(post("/api/hospital/followup/tasks/search", doctor, Map.of())),
                    "模块关闭 40301");
            assertEquals(
                    40301,
                    code(
                            post(
                                    "/api/hospital/followup/tasks/" + taskId + "/start",
                                    doctor,
                                    Map.of())),
                    "模块关闭 40301（开始录入）");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    @Test
    void outOfScopeTaskIsHiddenAndStartReturns40400() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(admin);
        long otherWardId = ensureOtherWard();
        long outScope =
                registerPatientInWard("demoa", admin, "P2", today.minusDays(20), otherWardId);
        long outTask = enrollCurrentTask("demoa", outScope, planId);

        assertEquals(
                40400,
                code(post("/api/hospital/followup/tasks/" + outTask + "/start", doctor, Map.of())),
                "越范围任务开始录入 40400（不暴露存在）");
        Map<?, ?> queue =
                get("/api/hospital/followup/tasks/queue?cursor=" + outTask + "&scope=ALL", doctor);
        assertEquals(0, code(queue));
        assertNull(data(queue).get("prevId"), "越范围 cursor 队列两头置空");
        assertNull(data(queue).get("nextId"));
    }

    @Test
    void workbenchDataIsIsolatedPerTenant() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedWindowPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "T1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        String recordId =
                String.valueOf(
                        data(post(
                                        "/api/hospital/followup/tasks/" + taskId + "/start",
                                        doctor,
                                        Map.of()))
                                .get("recordId"));

        String demobDoctor = hospitalToken("demob", "doctor.demo", PASSWORD);
        Map<?, ?> demobSearch =
                post("/api/hospital/followup/tasks/search", demobDoctor, Map.of("scope", "ALL"));
        assertEquals(0, code(demobSearch));
        assertFalse(
                ids(demobSearch).contains(String.valueOf(taskId)), "demoa 的任务在 demob 不可见（每院独立库）");
        assertEquals(
                40400,
                code(get("/api/hospital/followup/records/" + recordId, demobDoctor)),
                "demoa 的记录在 demob 不可见");
    }

    // ---------------------------------------------------------------- 造数与工具（与 TaskStatusFlowIT /
    // RecordDraftIT 同口径）

    /** 发布计划：单节点电话随访（偏移 20 天、窗口 ±3 天、FORM A_ANNUAL_FU）——起算 T−20 时窗口覆盖今天。 */
    private long publishedWindowPlan(String token) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 工作台计划（" + code + "）");
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
        node.put("formCode", FORM);
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
        body.put("name", "周工作台（示例）");
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

    /** 直接落指定次数的任务行（队列测试要可控的计划日期顺序；uk = 入组 + 节点 + 第 N 次）。 */
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

    /** 该患者在计划的在组入组 id（无则入组一次；重复入组 40902，已在上组）。 */
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

    /** 直接建草稿并回填关联（不经 start）：保持任务 PENDING 以验证暂存草稿联动。 */
    private long createLinkedDraft(String tenant, long taskId) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                servicePrincipal(), null, List.of()));
        return runInTenant(
                tenant,
                () -> {
                    String formCode =
                            jdbc.queryForObject(
                                    "SELECT form_code FROM fu_task WHERE id = ?",
                                    String.class,
                                    taskId);
                    FollowupRecordService.RecordView created =
                            recordService.createDraft(
                                    new FollowupRecordService.CreateCommand(
                                            patientIdOfTask(taskId),
                                            taskId,
                                            FollowupRecordService.SOURCE_TASK,
                                            null,
                                            formCode,
                                            Map.of(),
                                            Map.of("it", LEGACY)));
                    jdbc.update(
                            "UPDATE fu_task SET record_id = ? WHERE id = ? AND record_id IS NULL",
                            created.id(),
                            taskId);
                    return created.id();
                });
    }

    private long patientIdOfTask(long taskId) {
        return jdbc.queryForObject(
                "SELECT patient_id FROM fu_task WHERE id = ?", Long.class, taskId);
    }

    /** 服务层直调的医院会话（IT 线程无 HTTP 过滤器链，手动装配；记录创建经 FollowupRecordService，OQ-83①）。 */
    private static SessionPrincipal servicePrincipal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                7L,
                "doctor.demo",
                "张医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:record:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                null);
    }

    private long roleWithMenus(String roleCode, List<Long> menuIds) {
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
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    return roleId;
                });
    }

    private String newUserToken(String username, long roleId) {
        long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalToken("demoa", "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 用户（示例）",
                                "phone",
                                "1390000132" + Math.abs(username.hashCode() % 10),
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken("demoa", username, PASSWORD);
    }

    private String taskStatusOf(String tenant, long taskId) {
        return jdbcQuery(
                "demoa".equals(tenant) ? "demoa" : tenant,
                "SELECT status FROM fu_task WHERE id = ?",
                String.class,
                taskId);
    }

    private String completeKindOf(String tenant, long taskId) {
        return jdbcQuery(
                tenant, "SELECT complete_kind FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private String recordStatusOf(String tenant, String recordId) {
        return jdbcQuery(
                tenant, "SELECT status FROM fu_record WHERE id = ?", String.class, recordId);
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
                                    "SELECT id FROM ward WHERE code = 'W-IT-E15T02'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status)"
                                    + " VALUES ('W-IT-E15T02', 'IT 越范围病区（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-E15T02'", Long.class);
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

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
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
}
