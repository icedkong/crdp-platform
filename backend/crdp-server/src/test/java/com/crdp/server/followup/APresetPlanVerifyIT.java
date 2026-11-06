package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E23-T02 核对（端到端 / 真实 MySQL）：迁移 V61 落库后的医院 A 预置计划「按 A 规格配出计划与提醒」（tasks.csv 验收原文）。
 *
 * <p>基准同 {@link APresetPlanSqlTest}（A-FU-017 / A-FU-001、Q35、A-NTF-001），这里是库侧行为核对：
 *
 * <ol>
 *   <li>预置行：fu_plan（已发布、长期不设结束年度、auto_enroll 开）+ 子计划持续循环每年一次 + 节点 +1 年 / 提前 7 天 / 窗口 ±7 天 / 表单
 *       A_ANNUAL_FU / 责任医生 / 已过窗口补建；fu_plan_log 留新建与发布两条（A-FU-021）； ntf_rule 十个事件默认规则与 A 规格 §7
 *       表一致（V22 种子，逐项核对由 SqlTest 承担，这里核行数与关键行）；
 *   <li>出院登记自动加入预置计划（Q35）：入组 ACTIVE、起算日期 = 出院日期；
 *   <li>入组前第 1 年窗口已结束 → 补建补录任务（BACKFILL，Q44）：计划日期 = 出院 +1 年、窗口前后各 7 天、 不写 TaskOpened（补录不进通知，A 规格第 5
 *       章）；未来年度不预生成，缓存指向第 2 年（A-FU-001）；
 *   <li>第 1 年窗口已开启未结束 → 当天正常创建（NORMAL）并写 TaskOpened 事件（提醒开始 = 窗口开始 = 计划日期前 7 天，
 *       A-FU-001；窗口开启同天合并由事件产生方口径保证，A-NTF-001）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（病案号 / legacy_no IT-E23T02- 前缀）；
 * 预置计划行本身只读不动（其他 IT 按「单计划世界」断言时会把 auto_enroll 关掉，本类 @BeforeAll 恢复为随包状态）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class APresetPlanVerifyIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String LEGACY = "IT-E23T02";

    private static final String PLAN_CODE = "A_ANNUAL_FU_PLAN";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    private long planId;

    @BeforeAll
    void prepare() {
        recycleLeftoverFromEarlierRun();
        // 其他 IT 可能按「单计划世界」临时关掉预置自动入组：恢复随包状态（V61）再核对
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 1 WHERE code = ?"
                                        + " AND status = 'PUBLISHED'",
                                PLAN_CODE));
        planId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM fu_plan WHERE code = ? AND version = 1",
                                        Long.class,
                                        PLAN_CODE));
    }

    @AfterAll
    void cleanUp() {
        recycleLeftoverFromEarlierRun();
    }

    /** 预置行落库与规格一致（A-FU-017；行级逐项核对在 {@link APresetPlanSqlTest}，这里核库内落点）。 */
    @Test
    @Order(1)
    void presetRowsMatchSpecInDatabase() {
        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> plan =
                            jdbc.queryForMap(
                                    "SELECT code, name, version, status, start_date_field,"
                                            + " cycle_type, cycle_years, auto_enroll"
                                            + " FROM fu_plan WHERE id = ?",
                                    planId);
                    assertEquals("冠心病术后年度随访", plan.get("name"));
                    assertEquals(1, ((Number) plan.get("version")).intValue());
                    assertEquals("PUBLISHED", plan.get("status"), "预置即已发布（租户上线即可用）");
                    assertEquals("DISCHARGE_DATE", plan.get("start_date_field"));
                    assertEquals("ONGOING", plan.get("cycle_type"));
                    assertNull(plan.get("cycle_years"), "不设结束年度");
                    assertEquals(Boolean.TRUE, plan.get("auto_enroll"), "Q35 自动入组");

                    Map<String, Object> subplan =
                            jdbc.queryForMap(
                                    "SELECT name, repeat_mode, cycle_value, cycle_unit"
                                            + " FROM fu_plan_node WHERE plan_id = ?"
                                            + " AND node_type = 'SUBPLAN'",
                                    planId);
                    assertEquals("CYCLE", subplan.get("repeat_mode"));
                    assertEquals(1, ((Number) subplan.get("cycle_value")).intValue());
                    assertEquals("YEAR", subplan.get("cycle_unit"));

                    Map<String, Object> node =
                            jdbc.queryForMap(
                                    "SELECT n.name, n.basis, n.offset_value, n.offset_unit,"
                                            + " n.remind_value, n.window_before_value,"
                                            + " n.window_after_value, n.task_type, n.owner_type,"
                                            + " n.form_code, n.passed_window_action"
                                            + " FROM fu_plan_node n JOIN fu_plan_node s"
                                            + " ON n.parent_id = s.id"
                                            + " WHERE n.plan_id = ? AND n.node_type = 'NODE'",
                                    planId);
                    assertEquals("电话随访", node.get("name"));
                    assertEquals("PLAN_START", node.get("basis"));
                    assertEquals(1, ((Number) node.get("offset_value")).intValue());
                    assertEquals("YEAR", node.get("offset_unit"));
                    assertEquals(7, ((Number) node.get("remind_value")).intValue());
                    assertEquals(
                            7, ((Number) node.get("window_before_value")).intValue(), "窗口前 7 天");
                    assertEquals(
                            7, ((Number) node.get("window_after_value")).intValue(), "窗口后 7 天");
                    assertEquals("FORM", node.get("task_type"));
                    assertEquals("DOCTOR", node.get("owner_type"), "责任人 = 责任医生");
                    assertEquals("A_ANNUAL_FU", node.get("form_code"));
                    assertEquals("BACKFILL", node.get("passed_window_action"));

                    // 表单随包（E23-T01 V38）；变更记录两条（A-FU-021）
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_form WHERE code = 'A_ANNUAL_FU'"
                                                    + " AND usage_type = 'FOLLOWUP'",
                                            Integer.class)
                                    .intValue(),
                            "随访记录表单 A_ANNUAL_FU 随包");
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_log WHERE plan_id = ?"
                                                    + " AND action IN ('PLAN_CREATE', 'PLAN_PUBLISH')",
                                            Integer.class,
                                            planId)
                                    .intValue(),
                            "预置留新建 + 发布两条变更记录");
                    // 通知规则（A-NTF-001，V22 种子）：十个事件全部启用，关键默认值在库
                    assertEquals(
                            10,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM ntf_rule WHERE enabled = 1",
                                            Integer.class)
                                    .intValue(),
                            "A 规格 §7 十个事件默认全启用");
                    assertEquals(
                            "7",
                            jdbc.queryForObject(
                                    "SELECT CAST(advance_days AS CHAR) FROM ntf_rule"
                                            + " WHERE event_type = 'WINDOW_PREVIEW'",
                                    String.class));
                    assertEquals(
                            "INBOX,SMS",
                            jdbc.queryForObject(
                                    "SELECT channels FROM ntf_rule"
                                            + " WHERE event_type = 'TASK_OVERDUE'",
                                    String.class));
                });
    }

    /** 出院登记自动加入预置计划（Q35）；入组前第 1 年窗口已结束 → 补建补录（BACKFILL，Q44）， 未来年度不预生成、缓存指向第 2 年（A-FU-001）。 */
    @Test
    @Order(2)
    void registrationAutoEnrollsAndBackfillsPassedYear() {
        LocalDate today = LocalDate.now();
        LocalDate discharge = today.minusYears(1).minusDays(30); // 第 1 年窗口已结束 23 天
        String nurse = hospitalToken("demoa", "nurse.demo");
        long patientId = registerPatient(nurse, "B1", discharge);

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> enrollment =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_enrollment WHERE patient_id = ?"
                                            + " AND plan_id = ?",
                                    patientId,
                                    planId);
                    assertEquals("ACTIVE", enrollment.get("status"), "出院登记自动入组（Q35）");
                    assertEquals(discharge, date(enrollment.get("start_date")), "起算日期 = 出院日期");

                    // 第 1 年：窗口 [出院+1年-7, +7] 已结束 → 补建补录一条，不写 TaskOpened
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?",
                                            Integer.class,
                                            patientId)
                                    .intValue(),
                            "只补建已过窗口的第 1 年，未来年度不预生成");
                    Map<String, Object> task =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_task WHERE patient_id = ?", patientId);
                    assertEquals("BACKFILL", task.get("create_type"), "入组前已过窗口补建补录");
                    assertEquals("PENDING", task.get("status"));
                    assertEquals(1, ((Number) task.get("occurrence")).intValue(), "第 1 次");
                    assertEquals("FORM", task.get("task_type"));
                    assertEquals("A_ANNUAL_FU", task.get("form_code"), "完整随访记录表单");
                    assertEquals(discharge.plusYears(1), date(task.get("plan_date")), "出院 +1 年");
                    assertEquals(
                            discharge.plusYears(1).minusDays(7),
                            date(task.get("window_start")),
                            "窗口前 7 天");
                    assertEquals(
                            discharge.plusYears(1).plusDays(7),
                            date(task.get("window_end")),
                            "窗口后 7 天");
                    assertEquals(
                            0,
                            outboxCount(patientId, "TaskOpened"),
                            "补录任务不写 TaskOpened（不进通知，A 规格第 5 章）");

                    // 缓存：下次 = 第 2 年（计划日期 / 开启日 = 计划日期前 7 天）
                    assertEquals(
                            discharge.plusYears(2),
                            date(enrollment.get("next_plan_date")),
                            "下次计划日期 = 出院 +2 年（长期不设结束年度）");
                    assertEquals(
                            discharge.plusYears(2).minusDays(7),
                            date(enrollment.get("next_open_date")),
                            "下次开启日 = 提醒开始 = 窗口开始（A-FU-001）");
                });
    }

    /** 第 1 年窗口已开启未结束 → 登记当天正常创建（NORMAL）并写 TaskOpened 事件（提醒开始 = 窗口开始 = 计划日期前 7 天）。 */
    @Test
    @Order(3)
    void openWindowYearCreatesNormalTaskAndOpenedEvent() {
        LocalDate today = LocalDate.now();
        LocalDate discharge = today.minusYears(1); // 第 1 年计划日期 = 今天，窗口 [今天-7, 今天+7] 已开启未结束
        String nurse = hospitalToken("demoa", "nurse.demo");
        long patientId = registerPatient(nurse, "N1", discharge);

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> task =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_task WHERE patient_id = ?", patientId);
                    assertEquals("NORMAL", task.get("create_type"), "窗口已开启未结束当天正常创建");
                    assertEquals("PENDING", task.get("status"));
                    assertEquals(today, date(task.get("plan_date")), "计划日期 = 出院 +1 年 = 今天");
                    assertEquals(today.minusDays(7), date(task.get("remind_start")), "提前 7 天提醒");
                    assertEquals(today.minusDays(7), date(task.get("window_start")), "窗口开始 = 提醒开始");
                    assertEquals(today.plusDays(7), date(task.get("window_end")));
                    assertEquals(
                            today.minusDays(7),
                            date(task.get("open_date")),
                            "开启日 = 提醒开始日与窗口开始日较早一天（两者同天，A-FU-001）");
                    assertEquals(1, outboxCount(patientId, "TaskOpened"), "正常创建写 TaskOpened");
                });
    }

    // ---------------------------------------------------------------- 自建自清

    /** 清本类残留（患者级数据 + 事件 + 审计）；预置计划行（fu_plan / fu_plan_node / fu_plan_log）只读不动。 */
    private void recycleLeftoverFromEarlierRun() {
        runInTenant(
                "demoa",
                () -> {
                    List<Long> patientIds =
                            jdbc.queryForList(
                                    "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                    Long.class,
                                    LEGACY + "-%");
                    for (Long patientId : patientIds) {
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE JSON_EXTRACT(payload, '$.patientId')"
                                        + " = ?",
                                patientId);
                    }
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type = 'PATIENT_CREATE'"
                                    + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (action = 'TASK_CREATE'"
                                    + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    private int outboxCount(long patientId, String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = ?"
                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                Integer.class,
                eventType,
                patientId);
    }

    private long registerPatient(String token, String suffix, LocalDate discharge) {
        long wardId =
                runInTenant(
                        "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E23T02-" + suffix);
        body.put("name", "赵示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1951-02-01");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix + "-demoa");
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
