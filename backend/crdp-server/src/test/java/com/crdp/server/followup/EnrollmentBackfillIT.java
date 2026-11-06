package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.Instant;
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
 * E14-T05 端到端（tasks.csv 验收：已结束窗口照常建任务并标补录）。场景（医院 B 形态，A 规格第 5 章 / Q44）：
 *
 * <ol>
 *   <li>自动入组（Q35）：出院登记（存活）当天自动加入 auto_enroll 的已发布计划；出院状态为死亡不加入；
 *   <li>建档补录：手术 8 天前出院建档——术后第 1 / 3 / 5 天窗口均已结束 → 建档当天创建任务、create_type = BACKFILL、PENDING
 *       可直接录入、使用完整随访记录表单；passed_window_action = SKIP 的节点不建；
 *   <li>补录不发通知：BACKFILL 任务不写 TaskOpened outbox 事件（不进即将逾期 / 已逾期 / 失访候选，A 规格第 5 章）；
 *   <li>3 个月节点窗口未开 → 只进下次计划日期 / 开启日缓存（未来节点不预生成）；
 *   <li>入组后每日调度不重复建、也不为 SKIP 节点补建（uk 幂等 + 入组前已过窗口不属每日调度，OQ-78③）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14T05 前缀、病案号 IT-E14T05-
 * 前缀）。日期相对「业务今天」构造，随运行日平移。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrollmentBackfillIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E14T05";
    private static final String PLAN_CODE = "PLAN-IT-E14T05";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FollowupTaskCreationService creationService;

    /** 业务今天（北京时间，A-NFR-006）：与入组即建任务用的同一口径。 */
    private static LocalDate today() {
        return PlanDateCalculator.businessToday(Instant.now());
    }

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    // E23-T02 预置计划常驻自动入组（A_ANNUAL_FU_PLAN）：本类按「单计划世界」断言，
                    // 先关掉它的自动入组；预置规格与行为由 APresetPlanSqlTest / APresetPlanVerifyIT 锁定
                    jdbc.update(
                            "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                    // 审计先行（target 子查询要用还没删的行）：计划 / 入组 / 登记
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
                                    + " OR (event_type = 'PLAN_ENROLL'"
                                    + " AND detail LIKE '%"
                                    + PLAN_CODE
                                    + "%')"
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

    // ---------------------------------------------------------------- 验收：已结束窗口照常建任务并标补录

    @Test
    void registrationAutoEnrollsAndBackfillsEndedWindowsOnEnrollmentDay() {
        String admin = hospitalToken();
        LocalDate surgery = today().minusDays(8);
        long planId = createPublishedPostopPlan(admin, PLAN_CODE + "-B");
        long patientId = registerPatient(admin, "B1", surgery, today(), 1);

        // Q35 自动入组：登记当天已入组该 auto_enroll 已发布计划
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_enrollment WHERE patient_id = ?",
                                        Integer.class,
                                        patientId)),
                "出院登记（存活）自动加入预置计划");

        // 建档补录：术后第 1 / 3 / 5 天窗口均已结束 → 当天创建、标补录、待随访可直接录入
        List<Map<String, Object>> tasks = tasksOf(patientId);
        assertEquals(3, tasks.size(), "三个已结束窗口的里程碑照常建任务（第 4 天节点 SKIP 不建）");
        Map<String, Object> first = tasks.get(0);
        assertEquals("BACKFILL", first.get("create_type"), "已结束窗口 → 标补录（验收）");
        assertEquals("PENDING", first.get("status"), "创建即待随访，可直接录入");
        assertEquals("FORM", first.get("task_type"));
        assertEquals("fu_postop_v1", first.get("form_code"), "使用完整随访记录表单");
        assertEquals(surgery.plusDays(1), date(first.get("plan_date")), "计划日期 = 手术 + 1 天");
        assertEquals(surgery.plusDays(1), date(first.get("window_start")));
        assertEquals(surgery.plusDays(2), date(first.get("window_end")));
        assertEquals(surgery.plusDays(1), date(first.get("open_date")));
        assertEquals(today(), date(first.get("created_logical_date")), "建档当天创建");
        assertEquals(surgery.plusDays(3), date(tasks.get(1).get("plan_date")));
        assertEquals(surgery.plusDays(5), date(tasks.get(2).get("plan_date")));
        // SKIP 节点（术后第 4 天，医院 C 口径 Q14 同位验证）不建任务
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task t JOIN fu_plan_node n"
                                                + " ON n.id = t.node_id WHERE t.patient_id = ?"
                                                + " AND n.passed_window_action = 'SKIP'",
                                        Integer.class,
                                        patientId)),
                "SKIP 策略的节点不为已结束窗口建任务");

        // 补录不发通知：不写 TaskOpened 事件（不进即将逾期 / 已逾期 / 失访候选通知）
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox"
                                                + " WHERE event_type = 'TaskOpened'"
                                                + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                        Integer.class,
                                        patientId)),
                "BACKFILL 任务不发 TaskOpened");

        // 3 个月节点未开启：不预生成，只进下次计划日期 / 开启日缓存
        Map<String, Object> cache =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT next_plan_date, next_open_date FROM fu_enrollment"
                                                + " WHERE patient_id = ?",
                                        patientId));
        assertEquals(surgery.plusMonths(3), date(cache.get("next_plan_date")));
        assertEquals(surgery.plusMonths(3).minusDays(14), date(cache.get("next_open_date")));

        // 入组后每日调度：不为入组前已过窗口重复建、不为 SKIP 节点补建（uk 幂等）
        creationService.runForDate(today().plusDays(1));
        assertEquals(3, tasksOf(patientId).size(), "每日调度不重复建、不补建 SKIP 节点");
    }

    // ---------------------------------------------------------------- Q35：出院状态为死亡不加入

    @Test
    void registrationWithDeathDischargeStatusDoesNotEnroll() {
        String admin = hospitalToken();
        createPublishedPostopPlan(admin, PLAN_CODE + "-D");
        long patientId = registerPatient(admin, "D1", today().minusDays(8), today(), 3);

        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_enrollment WHERE patient_id = ?",
                                        Integer.class,
                                        patientId)),
                "出院状态为死亡不自动入组（Q35）");
        assertEquals(0, tasksOf(patientId).size(), "不入组即不建任务");
    }

    // ---------------------------------------------------------------- 辅助

    /** 医院 B 形态：手术起算、FIXED×1 子计划；第 1/3/5 天 BACKFILL、第 4 天 SKIP、3 个月节点（窗口未开）。 */
    private long createPublishedPostopPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 术后随访计划（" + code + "）");
        body.put("startDateField", "SURGERY_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", true);
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "术后早期随访（示例）");
        subplan.put("repeatMode", "FIXED");
        subplan.put("repeatCount", 1);
        subplan.put("sortNo", 10);
        Map<String, Object> threeMonths = node("n3m", 3, "MONTH", 14, 14, 14, null);
        Map<String, Object> day1 = node("nd1", 1, "DAY", 0, 0, 1, "BACKFILL");
        Map<String, Object> day3 = node("nd3", 3, "DAY", 0, 0, 1, "BACKFILL");
        Map<String, Object> day4 = node("nd4", 4, "DAY", 0, 0, 1, "SKIP");
        Map<String, Object> day5 = node("nd5", 5, "DAY", 0, 0, 1, "BACKFILL");
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of(
                                        "baseVersion",
                                        0,
                                        "nodes",
                                        List.of(subplan, day1, day3, day4, day5, threeMonths)))),
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

    private static Map<String, Object> node(
            String key,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            int windowBefore,
            int windowAfter,
            String passedWindowAction) {
        Map<String, Object> node = new HashMap<>();
        node.put("key", key);
        node.put("parentKey", "s1");
        node.put("name", "随访节点 " + key + "（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", offsetValue);
        node.put("offsetUnit", offsetUnit);
        node.put("remindValue", remindValue);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", windowBefore);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", windowAfter);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_postop_v1");
        node.put("sortNo", 20);
        if (passedWindowAction != null) {
            node.put("passedWindowAction", passedWindowAction);
        }
        return node;
    }

    /** 登记患者（E13-T01 接口；dischargeStatus 1 存活 / 3 死亡，A 规格字段 191）。 */
    private long registerPatient(
            String token, String suffix, LocalDate surgery, LocalDate discharge, int status) {
        long wardId =
                runInTenant(
                        "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E14T05-" + suffix);
        body.put("name", "孙示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1950-06-01");
        body.put("wardId", wardId);
        body.put("admissionDate", surgery.minusDays(7).toString());
        body.put("surgeryDate", surgery.toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", status);
        body.put("legacyNo", LEGACY + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    private List<Map<String, Object>> tasksOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForList(
                                "SELECT * FROM fu_task WHERE patient_id = ? ORDER BY plan_date",
                                patientId));
    }

    private String hospitalToken() {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "username",
                                "nurse.demo",
                                "password",
                                PASSWORD));
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
        assertNotNull(value, "日期列不得为空");
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
