package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * E21-T06 端到端（tasks.csv 验收「切换表每行一条用例通过」；C-FU-007 / Q01、Q03）。C 规格第 5 章「计划切换触发事件」表十行逐行用例（§3.3.1
 * SW-1…SW-10，docs/reviews/hospital-c-acceptance-review.md）：
 *
 * <ol>
 *   <li>SW-1 首次植入：按器械类型入普通计划，起算日 = 植入日期（E21-T05 路径回归）；
 *   <li>SW-2 升级为 CRT-D：原（普通）入组结束（SWITCHED）+ 未完成任务终止（原因入终止三列）+ 转 ICD 计划、从升级日期起算；
 *   <li>SW-3 升级为 CRT-P：留在普通计划族、从升级日期重起算（CL-02 假设三）；
 *   <li>SW-4 电池更换（同类型）：计划类型不变、ERI 期循环结束（mode 回 NORMAL / eri_since 清空）、从更换日期重走术后节点（CL-02 假设二）；
 *   <li>SW-5 电极修正：不切换（入组行不变）；
 *   <li>SW-6 编辑更正起搏器类型：按新类型重新入组、起算仍按原植入日期；
 *   <li>SW-7 随访录入 ERI 状态 = 接近ERI：mode → ERI、eri_since = 该次随访日期，常规未完成任务终止；
 *   <li>SW-8 后续随访 ERI 状态更正为正常：mode 回 NORMAL、eri_since 清空（恢复原计划的常规间隔）；
 *   <li>SW-9 取出且未再植入：结束入组（不再创建任务）、未完成任务终止，无新入组；
 *   <li>SW-10 医生指定下次随访时间：doctor_next_date 登记、计划类型不变（窗口围绕展开随 E21-T08）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 IT-E21T06-PACER / -ICD、程控表单
 * IT-E21T06-FU、legacy_no 前缀 IT-E21T06-）；V86 预置映射行在清理时快照、收尾还原（PUT 整体替换会重写本表）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanSwitchIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T06";
    private static final String PACER_CODE = "IT-E21T06-PACER";
    private static final String ICD_CODE = "IT-E21T06-ICD";
    private static final String FORM_CODE = "IT_E21T06_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    /** V86 预置映射行快照（PUT 整体替换前留存，收尾还原）。 */
    private List<Map<String, Object>> mappingSnapshot;

    @BeforeAll
    void snapshotPresetMapping() {
        mappingSnapshot =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT device_type, plan_code, enabled FROM fu_plan_mapping"
                                                + " ORDER BY device_type"));
        assertFalse(mappingSnapshot.isEmpty(), "V86 预置映射行应存在");
    }

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " (target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type LIKE 'PLAN_%'"
                                    + " AND (detail LIKE '%IT-E21T06%'"
                                    + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                    + " WHERE code IN ('"
                                    + PACER_CODE
                                    + "','"
                                    + ICD_CODE
                                    + "'))))"
                                    + " OR target = 'PLAN_MAPPING:device-mapping'");
                    jdbc.update(
                            "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
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
                            "DELETE il FROM pt_implant_lead il"
                                    + " JOIN pt_patient p ON p.id = il.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM pt_lead l JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE i FROM pt_implant i"
                                    + " JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code IN (?, ?)",
                            PACER_CODE,
                            ICD_CODE);
                    jdbc.update("DELETE FROM fu_plan WHERE code IN (?, ?)", PACER_CODE, ICD_CODE);
                    jdbc.update(
                            "DELETE FROM fu_plan_mapping WHERE plan_code IN (?, ?)",
                            PACER_CODE,
                            ICD_CODE);
                    // 还原 V86 预置映射（用例里 PUT 整体替换会清表）
                    jdbc.update("DELETE FROM fu_plan_mapping");
                    for (Map<String, Object> row : mappingSnapshot) {
                        jdbc.update(
                                "INSERT INTO fu_plan_mapping (device_type, plan_code, enabled)"
                                        + " VALUES (?, ?, ?)",
                                row.get("device_type"),
                                row.get("plan_code"),
                                row.get("enabled"));
                    }
                    // IT 期间开启的开关还原为种子默认（关闭），A / B 租户不受影响
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                    // IT 程控表单（JDBC 自建：f323 ERI 状态 / f324 预计电池寿命 / f333 下次随访时间，OQ-130① 键契约）
                    jdbc.update("DELETE FROM fe_form_version_field WHERE form_code = ?", FORM_CODE);
                    jdbc.update("DELETE FROM fe_form_version WHERE form_code = ?", FORM_CODE);
                    jdbc.update("DELETE FROM fe_field WHERE form_code = ?", FORM_CODE);
                    jdbc.update("DELETE FROM fe_option WHERE set_code = 'c_eri_status'");
                    jdbc.update("DELETE FROM fe_option_set WHERE code = 'c_eri_status'");
                    jdbc.update("DELETE FROM fe_form WHERE code = ?", FORM_CODE);
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- SW-1 首次植入

    @Test
    void sw1FirstImplantEnrollsByDeviceTypeFromImplantDate() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        String implantDate = LocalDate.now().minusMonths(5).toString();
        long patientId = registerPatient(admin, "01", "DDD", implantDate);
        Map<String, Object> enrollment = enrollmentOf(patientId);

        assertNotNull(enrollment, "首次植入按器械类型自动入组（SW-1，E21-T05 路径）");
        assertEquals(PACER_CODE, enrollment.get("plan_code"));
        assertEquals("ACTIVE", enrollment.get("status"));
        assertEquals("NORMAL", enrollment.get("mode"));
        assertEquals(
                java.sql.Date.valueOf(implantDate), enrollment.get("start_date"), "术后节点从植入日期起算");
        assertNull(enrollment.get("switch_reason"), "首次植入无切换");
    }

    // ---------------------------------------------------------------- SW-2 升级为 ICD / CRT-D

    @Test
    void sw2UpgradeToIcdEndsOldEnrollmentAndReenrollsFromUpgradeDate() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        String implantDate = LocalDate.now().minusMonths(5).toString();
        String upgradeDate = LocalDate.now().minusMonths(1).toString();
        long patientId = registerPatient(admin, "02", "DDD", implantDate);
        long oldEnrollmentId = enrollmentIdOf(patientId);
        long oldTaskId = pendingTaskOf(oldEnrollmentId);
        assertTrue(oldTaskId > 0, "入组即建当期任务（窗口已开启）");

        replace(admin, patientId, "UPGRADE", upgradeDate, "CRT_D");

        // 原（普通）入组结束：SWITCHED + 切换原因；未完成任务自动终止并记录原因
        Map<String, Object> oldRow = enrollmentRow(oldEnrollmentId);
        assertEquals("SWITCHED", oldRow.get("status"), "原入组结束（C-FU-007）");
        assertTrue(
                String.valueOf(oldRow.get("switch_reason")).contains("升级"),
                "switch_reason 记切换原因：" + oldRow.get("switch_reason"));
        Map<String, Object> task = taskRow(oldTaskId);
        assertEquals("TERMINATED", task.get("status"), "未完成的旧任务自动终止");
        assertTrue(
                String.valueOf(task.get("terminate_reason")).contains("计划切换"),
                "终止原因记录切换：" + task.get("terminate_reason"));

        // 转 ICD/CRT-D 计划，从升级日期重新起算术后节点
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current, "切换后重新入组");
        assertEquals(ICD_CODE, current.get("plan_code"), "升级为 CRT-D 转入 ICD/CRT-D 计划");
        assertEquals("NORMAL", current.get("mode"));
        assertEquals(
                java.sql.Date.valueOf(upgradeDate),
                current.get("start_date"),
                "从升级日期重起算 72 小时至 1 年节点（常规间隔变 3–6 个月，计划本体随 E23-T20）");
        assertEquals(1, activeEnrollmentCount(patientId), "不产生双计划并行任务");
    }

    // ---------------------------------------------------------------- SW-3 升级为 CRT-P / 普通类型

    @Test
    void sw3UpgradeToCrtPStaysInPacerFamilyFromUpgradeDate() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        String implantDate = LocalDate.now().minusMonths(5).toString();
        String upgradeDate = LocalDate.now().minusMonths(1).toString();
        long patientId = registerPatient(admin, "03", "DDD", implantDate);
        long oldEnrollmentId = enrollmentIdOf(patientId);

        replace(admin, patientId, "UPGRADE", upgradeDate, "CRT_P");

        assertEquals("SWITCHED", enrollmentRow(oldEnrollmentId).get("status"), "同族升级同样结束原入组重起算");
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current);
        assertEquals(PACER_CODE, current.get("plan_code"), "升级为 CRT-P 转入 / 留在普通起搏器计划（CL-02 假设三）");
        assertEquals(java.sql.Date.valueOf(upgradeDate), current.get("start_date"), "从升级日期重起算");
    }

    // ---------------------------------------------------------------- SW-4 电池更换（同类型）

    @Test
    void sw4BatteryReplacementEndsEriCycleAndRestartsNodes() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        String implantDate = LocalDate.now().minusMonths(5).toString();
        long patientId = registerPatient(admin, "04", "DDD", implantDate);
        long eriEnrollmentId = enrollmentIdOf(patientId);

        // 先标 ERI（SW-7 路径）：mode → ERI、eri_since = 随访日期
        LocalDate eriVisit = LocalDate.now().minusDays(2);
        submitBackfillRecord(admin, patientId, eriVisit, Map.of("f323", 1));
        Map<String, Object> eriRow = enrollmentRow(eriEnrollmentId);
        assertEquals("ERI", eriRow.get("mode"), "先进入 ERI 期（前置条件）");
        assertEquals(java.sql.Date.valueOf(eriVisit), eriRow.get("eri_since"));

        // 同类型电池更换：计划类型不变、ERI 期循环结束、从更换日期重走术后节点（CL-02 假设二）
        String replaceDate = LocalDate.now().minusMonths(1).toString();
        replace(admin, patientId, "REPLACE", replaceDate, "DDD");

        assertEquals("SWITCHED", enrollmentRow(eriEnrollmentId).get("status"), "ERI 期入组随更换结束");
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current);
        assertEquals(PACER_CODE, current.get("plan_code"), "同类型更换计划类型不变");
        assertEquals("NORMAL", current.get("mode"), "ERI 期循环结束（更换器械后结束，C-FU-006）");
        assertNull(current.get("eri_since"), "eri_since 清空");
        assertEquals(
                java.sql.Date.valueOf(replaceDate),
                current.get("start_date"),
                "按更换日期重新走 72 小时至 1 年节点");
    }

    // ---------------------------------------------------------------- SW-5 电极修正

    @Test
    void sw5LeadRevisionDoesNotSwitchPlan() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        long patientId =
                registerPatient(admin, "05", "DDD", LocalDate.now().minusMonths(5).toString());
        long enrollmentId = enrollmentIdOf(patientId);
        Map<String, Object> before = enrollmentRow(enrollmentId);

        replace(
                admin,
                patientId,
                "LEAD_REVISION",
                LocalDate.now().minusMonths(1).toString(),
                "DDD");

        Map<String, Object> after = enrollmentRow(enrollmentId);
        assertEquals("ACTIVE", after.get("status"), "电极修正不切换（入组行不变）");
        assertEquals(before.get("plan_code"), after.get("plan_code"));
        assertEquals(before.get("start_date"), after.get("start_date"), "不重起算");
        assertEquals(before.get("mode"), after.get("mode"));
        assertEquals(1, activeEnrollmentCount(patientId));
    }

    // ---------------------------------------------------------------- SW-6 器械类型更正（录入纠错）

    @Test
    void sw6DeviceTypeCorrectionReenrollsFromOriginalImplantDate() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        String implantDate = LocalDate.now().minusMonths(5).toString();
        long patientId = registerPatient(admin, "06", "DDD", implantDate);
        long oldEnrollmentId = enrollmentIdOf(patientId);

        Map<?, ?> implant = firstImplant(admin, patientId);
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", ((Number) implant.get("version")).intValue());
        body.put("implant", implantPayload(implantTypeOf(implant), implantDate, "ICD"));
        assertEquals(
                0,
                code(put("/api/hospital/followup/implants/" + implant.get("id"), admin, body)),
                "编辑更正起搏器类型失败");

        // 按新类型重新入组；起算仍按原植入日期（SW-6「否，仍按原植入日期」）；未开始任务随终止 + 重新入组重算
        assertEquals("SWITCHED", enrollmentRow(oldEnrollmentId).get("status"), "纠错切换结束原入组");
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current);
        assertEquals(ICD_CODE, current.get("plan_code"), "按新类型（ICD）重新入组");
        assertEquals(
                java.sql.Date.valueOf(implantDate), current.get("start_date"), "起算仍按原植入日期（不是更正当天）");
        assertTrue(
                String.valueOf(enrollmentRow(oldEnrollmentId).get("switch_reason")).contains("更正"),
                "switch_reason 记更正原因");
    }

    // ---------------------------------------------------------------- SW-7 / SW-8 / SW-10
    // 随访记录触发的三行

    @Test
    void sw7Sw8Sw10EriCycleAndDoctorNextVisitDate() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        long patientId =
                registerPatient(admin, "07", "DDD", LocalDate.now().minusMonths(5).toString());
        long enrollmentId = enrollmentIdOf(patientId);
        long taskId = pendingTaskOf(enrollmentId);
        assertTrue(taskId > 0, "入组即建当期任务");

        // SW-7 随访录入 ERI 状态 = 接近ERI：切换 ERI 期循环，从该次随访日期起算；常规未完成任务终止
        LocalDate eriVisit = LocalDate.now().minusDays(3);
        submitBackfillRecord(admin, patientId, eriVisit, Map.of("f323", 1));
        Map<String, Object> eriRow = enrollmentRow(enrollmentId);
        assertEquals("ERI", eriRow.get("mode"), "标 ERI 后切循环（SW-7）");
        assertEquals(
                java.sql.Date.valueOf(eriVisit), eriRow.get("eri_since"), "从该次随访日期起算（eri_since）");
        assertEquals("ACTIVE", eriRow.get("status"), "ERI 切换不换入组行");
        assertEquals("TERMINATED", taskRow(taskId).get("status"), "常规未完成任务终止（循环接管）");
        assertTrue(
                String.valueOf(taskRow(taskId).get("terminate_reason")).contains("ERI"),
                "终止原因记录 ERI 切换");

        // SW-10 医生指定下次随访时间：不改变计划类型，只登记 doctor_next_date（窗口围绕展开随 E21-T08）
        LocalDate doctorDate = LocalDate.now().plusMonths(3);
        submitBackfillRecord(
                admin,
                patientId,
                LocalDate.now().minusDays(2),
                Map.of("f333", doctorDate.toString()));
        Map<String, Object> doctorRow = enrollmentRow(enrollmentId);
        assertEquals("ERI", doctorRow.get("mode"), "医生指定不改变计划类型 / 模式（SW-10）");
        assertEquals(PACER_CODE, doctorRow.get("plan_code"));
        assertEquals(
                java.sql.Date.valueOf(doctorDate),
                doctorRow.get("doctor_next_date"),
                "登记医生指定的下一次计划日期");

        // SW-8 后续随访 ERI 状态更正为正常：恢复原计划的常规间隔（mode 回 NORMAL、eri_since 清空）
        submitBackfillRecord(admin, patientId, LocalDate.now().minusDays(1), Map.of("f323", 0));
        Map<String, Object> restored = enrollmentRow(enrollmentId);
        assertEquals("NORMAL", restored.get("mode"), "ERI 标记解除恢复常规间隔（SW-8）");
        assertNull(restored.get("eri_since"), "eri_since 清空");
        assertEquals("ACTIVE", restored.get("status"), "仍在原入组（不换行）");
    }

    @Test
    void sw7LowBatteryLifeAlsoTriggersEriCycle() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        long patientId =
                registerPatient(admin, "08", "DDD", LocalDate.now().minusMonths(5).toString());
        long enrollmentId = enrollmentIdOf(patientId);

        // 预计电池寿命 ≤ 1 年（切换表「接近 ERI」行的第二个条件）
        submitBackfillRecord(admin, patientId, LocalDate.now().minusDays(1), Map.of("f324", 0.8));
        assertEquals("ERI", enrollmentRow(enrollmentId).get("mode"), "电池寿命 0.8 年同样切换 ERI 期");
    }

    // ---------------------------------------------------------------- SW-9 取出且未再植入

    @Test
    void sw9ExplantEndsEnrollmentAndTerminatesTasks() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        long patientId =
                registerPatient(admin, "09", "DDD", LocalDate.now().minusMonths(5).toString());
        long enrollmentId = enrollmentIdOf(patientId);
        long taskId = pendingTaskOf(enrollmentId);
        assertTrue(taskId > 0);

        Map<?, ?> implant = firstImplant(admin, patientId);
        List<Map<String, Object>> leadActions = new ArrayList<>();
        for (Object lead : (List<?>) implant.get("leads")) {
            leadActions.add(
                    Map.of(
                            "leadId",
                            String.valueOf(((Map<?, ?>) lead).get("id")),
                            "action",
                            "EXTRACT"));
        }
        String explantDate = LocalDate.now().minusMonths(1).toString();
        Map<?, ?> explanted =
                post(
                        "/api/hospital/followup/implants/" + implant.get("id") + "/explant",
                        admin,
                        Map.of(
                                "baseVersion",
                                ((Number) implant.get("version")).intValue(),
                                "date",
                                explantDate,
                                "reason",
                                "感染取出（示例）",
                                "leadActions",
                                leadActions));
        assertEquals(0, code(explanted), "取出失败：" + explanted);

        // 结束入组、不再创建任务：无在组入组、原入组 SWITCHED、未完成任务终止
        assertEquals(0, activeEnrollmentCount(patientId), "结束入组（SW-9）");
        Map<String, Object> row = enrollmentRow(enrollmentId);
        assertEquals("SWITCHED", row.get("status"));
        assertTrue(
                String.valueOf(row.get("switch_reason")).contains("取出"),
                "switch_reason 记取出：" + row.get("switch_reason"));
        assertEquals("TERMINATED", taskRow(taskId).get("status"), "未完成任务终止");
        // 患者仍在册、随访中（再植入走登记 / 新建时须能重新入组）
        assertEquals(
                "ACTIVE",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                                        String.class,
                                        patientId)),
                "取出不落患者级「已终止」（与 A-FU-008 移出区分）");
    }

    // ---------------------------------------------------------------- 辅助

    private void enableImplant() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
    }

    /** 映射保存体：DDD / CRT_P → 普通计划，CRT_D / ICD → ICD 计划（与 V86 预置划分同构）。 */
    private static Map<String, Object> mappingBody() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String type : new String[] {"SSI", "DDD", "CRT_P", "HBP_LBBP", "LEADLESS"}) {
            items.add(Map.of("deviceType", type, "planCode", PACER_CODE, "enabled", true));
        }
        for (String type : new String[] {"ICD", "CRT_D"}) {
            items.add(Map.of("deviceType", type, "planCode", ICD_CODE, "enabled", true));
        }
        return Map.of("items", items);
    }

    /** 植入起算的已发布计划（一个 6 个月节点、窗口 ±60/90 天，入组即产生当期任务；passed_window_action = SKIP）。 */
    private long publishedPlan(String token, String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("startDateField", "IMPLANT_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", false);
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "术后随访（示例）");
        subplan.put("repeatMode", "FIXED");
        subplan.put("repeatCount", 1);
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "术后 6 个月（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 6);
        node.put("offsetUnit", "MONTH");
        node.put("remindValue", 14);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 60);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 90);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_postop_v1");
        node.put("passedWindowAction", "SKIP");
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

    /** C 登记并返回患者 id（implant 开关开启时同建首条植入记录，ImplantRecordIT 同款请求体）。 */
    private long registerPatient(
            String token, String suffix, String deviceType, String implantDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "端木示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId());
        body.put("legacyNo", LEGACY + "-" + suffix);
        body.put("implant", implantPayload("FIRST", implantDate, deviceType));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    /** 植入记录载荷（RA / RV 两根电极；无导线类型不携带电极）。 */
    private static Map<String, Object> implantPayload(String type, String date, String deviceType) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", type);
        implant.put("implantDate", date);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-" + LEGACY + "-" + deviceType);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        if (!"LEADLESS".equals(deviceType)) {
            implant.put(
                    "leads",
                    List.of(
                            Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                            Map.of("chamber", "RV", "position", "RV_APEX")));
        }
        return implant;
    }

    private static String implantTypeOf(Map<?, ?> implant) {
        return String.valueOf(implant.get("implantType"));
    }

    /** 更换 / 升级 / 电极修正（在用电极逐根 KEEP）。 */
    private void replace(
            String token, long patientId, String type, String date, String deviceType) {
        Map<?, ?> implant = firstImplant(token, patientId);
        List<Map<String, Object>> leadActions = new ArrayList<>();
        for (Object lead : (List<?>) implant.get("leads")) {
            leadActions.add(
                    Map.of(
                            "leadId",
                            String.valueOf(((Map<?, ?>) lead).get("id")),
                            "action",
                            "KEEP"));
        }
        Map<?, ?> replaced =
                post(
                        "/api/hospital/followup/implants/" + implant.get("id") + "/replace",
                        token,
                        Map.of(
                                "baseVersion",
                                ((Number) implant.get("version")).intValue(),
                                "type",
                                type,
                                "implant",
                                implantPayload(type, date, deviceType),
                                "leadActions",
                                leadActions,
                                "newLeads",
                                List.of()));
        assertEquals(0, code(replaced), type + " 失败：" + replaced);
    }

    /**
     * IT 程控表单（f323 ERI 状态〔c_eri_status 0/1/2〕/ f324 预计电池寿命 / f333 下次随访时间，全部选填，OQ-130①
     * 键契约）。幂等（同用例多次补录只建一次）。
     */
    private void createProgramForm() {
        runInTenant(
                "demoa",
                () -> {
                    Integer exists =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM fe_form WHERE code = ?",
                                    Integer.class,
                                    FORM_CODE);
                    if (exists != null && exists > 0) {
                        return;
                    }
                    jdbc.update(
                            "INSERT INTO fe_form (code, name, usage_type, current_version,"
                                    + " categories) VALUES (?, 'IT 程控随访（示例）', 'FOLLOWUP', 1,"
                                    + " JSON_ARRAY(JSON_OBJECT('key','fu_overview','label','随访概况','sortNo',10),"
                                    + " JSON_OBJECT('key','battery','label','电池','sortNo',50)))",
                            FORM_CODE);
                    jdbc.update(
                            "INSERT INTO fe_option_set (code, name) VALUES ('c_eri_status',"
                                    + " 'ERI 状态（示例）')");
                    jdbc.update(
                            "INSERT INTO fe_option (set_code, option_code, label, sort_no) VALUES"
                                    + " ('c_eri_status', 0, '正常', 10), ('c_eri_status', 1, '接近ERI', 20),"
                                    + " ('c_eri_status', 2, '已达ERI(EOI)', 30)");
                    jdbc.update(
                            "INSERT INTO fe_field (form_code, field_key, field_code, label,"
                                    + " category_key, type, option_set_code, required, sort_no) VALUES"
                                    + " (?, 'f323', 'eri_status', 'ERI 状态', 'battery', 'radio',"
                                    + " 'c_eri_status', 0, 10),"
                                    + " (?, 'f324', 'battery_life', '预计电池寿命(年)', 'battery',"
                                    + " 'number', NULL, 0, 20),"
                                    + " (?, 'f333', 'next_visit_date', '下次随访时间', 'fu_overview',"
                                    + " 'date', NULL, 0, 30)",
                            FORM_CODE,
                            FORM_CODE,
                            FORM_CODE);
                    jdbc.update(
                            "INSERT INTO fe_form_version (form_code, version, categories,"
                                    + " field_count) SELECT code, current_version, categories, 3"
                                    + " FROM fe_form WHERE code = ?",
                            FORM_CODE);
                    jdbc.update(
                            "INSERT INTO fe_form_version_field"
                                    + " (version_id, form_code, version, field_key, field_code,"
                                    + " label, category_key, type, unit, decimals, hard_range,"
                                    + " soft_range, option_set_code, required, visible_when,"
                                    + " required_when, derived_def, sensitive_flag, storage_type,"
                                    + " help_text, sort_no)"
                                    + " SELECT v.id, f.form_code, v.version, f.field_key, f.field_code,"
                                    + " f.label, f.category_key, f.type, f.unit, f.decimals,"
                                    + " f.hard_range, f.soft_range, f.option_set_code, f.required,"
                                    + " f.visible_when, f.required_when, f.derived_def,"
                                    + " f.sensitive_flag, f.storage_type, f.help_text, f.sort_no"
                                    + " FROM fe_field f JOIN fe_form_version v"
                                    + " ON v.form_code = f.form_code AND v.version = 1"
                                    + " WHERE f.form_code = ? AND f.status = 'ENABLED'",
                            FORM_CODE);
                });
    }

    /** 补录一条记录并提交（visitDate 须早于今天；data 按 IT 表单稳定键）。 */
    private void submitBackfillRecord(
            String token, long patientId, LocalDate visitDate, Map<String, Object> data) {
        createProgramForm();
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate.toString(),
                                "formCode",
                                FORM_CODE));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        token,
                        Map.of("baseVersion", 0, "data", data));
        assertEquals(0, code(submitted), "补录提交失败：" + submitted);
    }

    private Map<?, ?> firstImplant(String token, long patientId) {
        Map<?, ?> list = get("/api/hospital/followup/patients/" + patientId + "/implants", token);
        assertEquals(0, code(list));
        List<?> items = (List<?>) data(list).get("items");
        return (Map<?, ?>) items.get(0);
    }

    private Map<String, Object> enrollmentOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.query(
                                "SELECT * FROM fu_enrollment WHERE patient_id = ?"
                                        + " AND status IN ('ACTIVE','PAUSED') LIMIT 1",
                                rs -> rs.next() ? resultMap(rs, 0) : null,
                                patientId));
    }

    private long enrollmentIdOf(long patientId) {
        Map<String, Object> row = enrollmentOf(patientId);
        assertNotNull(row, "应有在组入组");
        return ((Number) row.get("id")).longValue();
    }

    private Map<String, Object> enrollmentRow(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT * FROM fu_enrollment WHERE id = ?",
                                PlanSwitchIT::resultMap,
                                enrollmentId));
    }

    private int activeEnrollmentCount(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_enrollment WHERE patient_id = ?"
                                        + " AND status IN ('ACTIVE','PAUSED')",
                                Integer.class,
                                patientId));
    }

    /** 该入组的当期（PENDING）任务 id（入组即建；无则 0）。 */
    private long pendingTaskOf(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COALESCE(MIN(id), 0) FROM fu_task WHERE enrollment_id = ?"
                                        + " AND status = 'PENDING'",
                                Long.class,
                                enrollmentId));
    }

    private Map<String, Object> taskRow(long taskId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT * FROM fu_task WHERE id = ?",
                                PlanSwitchIT::resultMap,
                                taskId));
    }

    private static Map<String, Object> resultMap(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Map<String, Object> row = new HashMap<>();
        int columns = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columns; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private long deptId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'", Long.class));
    }

    private long wardId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
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

    private Map<?, ?> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class)
                .getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong(String.valueOf(data(response).get("id")));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
