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

    /**
     * IT 自建 ERI 状态选项集（E23-T20 起 V90 随包预置共享的 c_eri_status，编码契约相同 0/1/2）： 本 IT
     * 用自有编码建集与清理，不再删改共享预置（配置模板导出 / 双院配置比对依赖 demoa 的 V90 种子完整）。
     */
    private static final String ERI_SET = "it_e21t06_eri";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.crdp.followup.task.app.FollowupTaskCreationService creationService;

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
                    jdbc.update("DELETE FROM fe_option WHERE set_code = ?", ERI_SET);
                    jdbc.update("DELETE FROM fe_option_set WHERE code = ?", ERI_SET);
                    jdbc.update("DELETE FROM fe_form WHERE code = ?", FORM_CODE);
                    // E21-T07 起本 IT 会跑任务创建作业（历史逻辑日）：清执行记录，避免补跑区间影响其他 IT
                    jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
                });
        runInTenant(
                "demob",
                () -> jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'"));
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
        body.put("reason", "类型更正（示例，SW-6）");
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

    // ---------------------------------------------------------------- E21-T07 / C-FU-006 接近 ERI
    // 加密随访循环

    @Test
    void eriLoopSchedulesMonthlyFromVisitDateAndEndsOnReplacement() {
        LocalDate today = LocalDate.now();
        enableImplant();
        String admin = adminToken();
        publishedEriLoopPlan(admin, PACER_CODE);
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        // 植入 T−6 月 −40 天（历史患者，C-PAT-008）：固定「术后 6 个月」窗口在入组前已结束（SKIP，Q14）、
        // 常规随访首次回落与固定节点重合被抑制（OQ-139②）；把入组 created_at 回拨到植入日，复现「早已建档」的时间线
        LocalDate implant = today.minusMonths(6).minusDays(40);
        long patientId = registerPatient(admin, "10", "DDD", implant.toString());
        long enrollmentId = enrollmentIdOf(patientId);
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?",
                                        Integer.class,
                                        enrollmentId)),
                "入组当天无任务（固定节点已过窗口、常规首次抑制）");
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                                java.sql.Timestamp.valueOf(implant.atStartOfDay()),
                                enrollmentId));

        // 标 ERI（SW-7 路径）：mode → ERI、eri_since = 该次随访日期，last_visit_date 快照同值（V93）
        LocalDate eriVisit = today.minusMonths(2);
        submitBackfillRecord(admin, patientId, eriVisit, Map.of("f323", 1));
        Map<String, Object> eriRow = enrollmentRow(enrollmentId);
        assertEquals("ERI", eriRow.get("mode"), "标 ERI 后切循环（C-FU-006）");
        assertEquals(java.sql.Date.valueOf(eriVisit), eriRow.get("eri_since"));
        assertEquals(
                java.sql.Date.valueOf(eriVisit),
                eriRow.get("last_visit_date"),
                "滚动基准快照 = 该次随访日期（E21-T07）");

        // ERI 循环第 1 次 = 随访日期 + 1 个月、窗口前后 7 天；开启日（计划 −7 天）跑作业即创建
        LocalDate firstPlan = eriVisit.plusMonths(1);
        creationService.runForDate(firstPlan.minusDays(7));
        Map<String, Object> firstEriTask = eriLoopTask(enrollmentId, 1, "ERI 期循环第 1 次任务应创建");
        assertEquals(java.sql.Date.valueOf(firstPlan), firstEriTask.get("plan_date"));
        assertEquals(
                java.sql.Date.valueOf(firstPlan.minusDays(7)), firstEriTask.get("window_start"));
        assertEquals(java.sql.Date.valueOf(firstPlan.plusDays(7)), firstEriTask.get("window_end"));
        assertEquals("PENDING", firstEriTask.get("status"));
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?",
                                        Integer.class,
                                        enrollmentId)),
                "ERI 模式只排 ERI 循环节点（固定 / 常规不再创建，OQ-138⑦）");

        // 第 2 次随访（窗口内、晚于第 1 次计划日）后循环从该次随访滚动：第 2 次 = 该次 + 1 个月（不是第 1 次计划日 + 周期）
        LocalDate secondVisit = today.minusDays(25);
        submitBackfillRecord(admin, patientId, secondVisit, Map.of("f323", 1));
        assertEquals(
                java.sql.Date.valueOf(secondVisit),
                enrollmentRow(enrollmentId).get("last_visit_date"),
                "快照随每次随访单调推进");
        LocalDate secondPlan = secondVisit.plusMonths(1);
        creationService.runForDate(secondPlan.minusDays(7));
        Map<String, Object> secondEriTask =
                eriLoopTask(enrollmentId, 2, "ERI 期循环第 2 次任务应按上次随访滚动创建");
        assertEquals(java.sql.Date.valueOf(secondPlan), secondEriTask.get("plan_date"));
        long pacerPlanId = ((Number) enrollmentRow(enrollmentId).get("plan_id")).longValue();
        assertEquals(
                2,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?"
                                                + " AND node_id = (SELECT id FROM fu_plan_node"
                                                + " WHERE plan_id = ? AND node_type = 'NODE'"
                                                + " AND name = 'ERI期随访（示例）')",
                                        Integer.class,
                                        enrollmentId,
                                        pacerPlanId)),
                "ERI 循环共 2 次");

        // 更换器械（同类型电池更换，SW-4）：ERI 期循环结束——原入组 SWITCHED、未完成任务终止，新入组常规模式不再排 ERI 循环
        String replaceDate = today.minusDays(1).toString();
        replace(admin, patientId, "REPLACE", replaceDate, "DDD");
        assertEquals("SWITCHED", enrollmentRow(enrollmentId).get("status"), "更换器械后 ERI 循环入组结束");
        long terminated =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?"
                                                + " AND status = 'TERMINATED'",
                                        Integer.class,
                                        enrollmentId));
        assertEquals(2, terminated, "两次 ERI 循环任务随切换终止并记录原因（C-FU-007）");
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current, "同类型更换重新入组（计划类型不变）");
        assertEquals("NORMAL", current.get("mode"), "新入组回到常规模式（ERI 循环结束）");
        long currentEnrollmentId = ((Number) current.get("id")).longValue();

        creationService.runForDate(today);
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?"
                                                + " AND node_id = (SELECT id FROM fu_plan_node"
                                                + " WHERE plan_id = ? AND node_type = 'NODE'"
                                                + " AND name = 'ERI期随访（示例）')",
                                        Integer.class,
                                        currentEnrollmentId,
                                        current.get("plan_id"))),
                "更换器械后不再创建 ERI 循环任务（验收「更换器械后结束」）");
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?",
                                        Integer.class,
                                        currentEnrollmentId)),
                "新入组术后节点与常规首次均未到开启日 / 被抑制，无任务");
    }

    @Test
    void eriClearedRoutineLoopRollsFromLastVisit() {
        // SW-8 解除 + E21-T07 常规滚动：ERI 更正为正常后回常规模式，常规随访从上次实际随访日期滚动（+6 个月）
        LocalDate today = LocalDate.now();
        enableImplant();
        String admin = adminToken();
        publishedEriLoopPlan(admin, PACER_CODE);
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        LocalDate implant = today.minusMonths(8).minusDays(10);
        long patientId = registerPatient(admin, "11", "DDD", implant.toString());
        long enrollmentId = enrollmentIdOf(patientId);

        // 先标 ERI 再更正为正常（f323 = 0 且电池寿命不低，OQ-137）：模式回 NORMAL，快照停在解除那次随访
        LocalDate eriVisit = today.minusMonths(6).minusDays(10);
        submitBackfillRecord(admin, patientId, eriVisit, Map.of("f323", 1));
        assertEquals("ERI", enrollmentRow(enrollmentId).get("mode"));
        LocalDate clearedVisit = today.minusMonths(6).plusDays(13);
        submitBackfillRecord(admin, patientId, clearedVisit, Map.of("f323", 0));
        Map<String, Object> restored = enrollmentRow(enrollmentId);
        assertEquals("NORMAL", restored.get("mode"), "ERI 标记解除恢复常规间隔（SW-8）");
        assertEquals(java.sql.Date.valueOf(clearedVisit), restored.get("last_visit_date"));

        // 常规随访第 1 次 = 解除那次随访 + 6 个月（可设 3–6 / 6–12 个月由租户在计划版本调整，A-FU-018）；开启日（−14 天）创建
        LocalDate routinePlan = clearedVisit.plusMonths(6);
        creationService.runForDate(routinePlan.minusDays(14));
        Map<String, Object> routineTask =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT t.* FROM fu_task t JOIN fu_plan_node n"
                                                + " ON n.id = t.node_id"
                                                + " WHERE t.enrollment_id = ? AND n.name ="
                                                + " '常规随访（示例）'",
                                        PlanSwitchIT::resultMap,
                                        enrollmentId));
        assertNotNull(routineTask, "常规随访按上次随访滚动创建（E21-T07）");
        assertEquals(java.sql.Date.valueOf(routinePlan), routineTask.get("plan_date"));
        assertEquals(1, ((Number) routineTask.get("occurrence")).intValue());
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM fu_task t JOIN fu_plan_node n"
                                                + " ON n.id = t.node_id"
                                                + " WHERE t.enrollment_id = ? AND n.name ="
                                                + " 'ERI期随访（示例）'",
                                        Integer.class,
                                        enrollmentId)),
                "常规模式不排 ERI 循环节点");
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

    /**
     * E21-T07 用 C 预置形态计划（V91 同构）：FIXED×1「术后6个月」（PLAN_START +6 月、±30 天）+「常规随访」滚动（LAST_VISIT +6 月、±14
     * 天、enroll_mode NORMAL）+「ERI期随访」滚动（LAST_VISIT +1 月、±7 天、enroll_mode ERI）。 节点名被用例按名定位，勿改。
     */
    private long publishedEriLoopPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 起搏器术后随访（含 ERI 循环）");
        body.put("startDateField", "IMPLANT_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", false);
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> s1 = new HashMap<>();
        s1.put("key", "s1");
        s1.put("name", "术后随访（示例）");
        s1.put("repeatMode", "FIXED");
        s1.put("repeatCount", 1);
        s1.put("sortNo", 10);
        nodes.add(s1);
        nodes.add(eriLoopNode("n1", "s1", "术后6个月（示例）", "PLAN_START", 6, 14, 30, 30, null));
        Map<String, Object> s2 = new HashMap<>();
        s2.put("key", "s2");
        s2.put("name", "常规随访（示例）");
        s2.put("repeatMode", "CYCLE");
        s2.put("cycleValue", 6);
        s2.put("cycleUnit", "MONTH");
        s2.put("sortNo", 20);
        nodes.add(s2);
        nodes.add(eriLoopNode("n2", "s2", "常规随访（示例）", "LAST_VISIT", 6, 14, 14, 14, "NORMAL"));
        Map<String, Object> s3 = new HashMap<>();
        s3.put("key", "s3");
        s3.put("name", "ERI期随访（示例）");
        s3.put("repeatMode", "CYCLE");
        s3.put("cycleValue", 1);
        s3.put("cycleUnit", "MONTH");
        s3.put("sortNo", 30);
        nodes.add(s3);
        nodes.add(eriLoopNode("n3", "s3", "ERI期随访（示例）", "LAST_VISIT", 1, 7, 7, 7, "ERI"));
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", nodes))),
                "保存 ERI 循环计划节点失败");
        assertEquals(
                0,
                code(post("/api/hospital/followup/plans/" + planId + "/publish", token, Map.of())),
                "发布失败");
        return planId;
    }

    /** 该入组 ERI 循环节点第 occurrence 次任务行（无则断言失败并返回 null）。 */
    private Map<String, Object> eriLoopTask(long enrollmentId, int occurrence, String message) {
        Map<String, Object> row =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.query(
                                        "SELECT t.* FROM fu_task t JOIN fu_plan_node n"
                                                + " ON n.id = t.node_id"
                                                + " WHERE t.enrollment_id = ? AND n.name ="
                                                + " 'ERI期随访（示例）' AND t.occurrence = ?",
                                        rs -> rs.next() ? resultMap(rs, 0) : null,
                                        enrollmentId,
                                        occurrence));
        assertNotNull(row, message);
        return row;
    }

    /** ERI 循环计划的节点行（单位恒为 MONTH / DAY，enrollMode 可空）。 */
    private static Map<String, Object> eriLoopNode(
            String key,
            String parentKey,
            String name,
            String basis,
            int offsetMonths,
            int remindDays,
            int windowBefore,
            int windowAfter,
            String enrollMode) {
        Map<String, Object> node = new HashMap<>();
        node.put("key", key);
        node.put("parentKey", parentKey);
        node.put("name", name);
        node.put("basis", basis);
        node.put("offsetValue", offsetMonths);
        node.put("offsetUnit", "MONTH");
        node.put("remindValue", remindDays);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", windowBefore);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", windowAfter);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_postop_v1");
        node.put("passedWindowAction", "SKIP");
        if (enrollMode != null) {
            node.put("enrollMode", enrollMode);
        }
        node.put("sortNo", 10);
        return node;
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
     * IT 程控表单（f323 ERI 状态〔ERI_SET 0/1/2，与 V90 契约 c_eri_status 同码〕/ f324 预计电池寿命 / f333
     * 下次随访时间，全部选填，OQ-130① 键契约）。幂等（同用例多次补录只建一次）。
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
                            "INSERT INTO fe_option_set (code, name) VALUES (?, 'ERI 状态（示例）')",
                            ERI_SET);
                    jdbc.update(
                            "INSERT INTO fe_option (set_code, option_code, label, sort_no) VALUES"
                                    + " (?, 0, '正常', 10), (?, 1, '接近ERI', 20), (?, 2, '已达ERI(EOI)', 30)",
                            ERI_SET,
                            ERI_SET,
                            ERI_SET);
                    jdbc.update(
                            "INSERT INTO fe_field (form_code, field_key, field_code, label,"
                                    + " category_key, type, option_set_code, required, sort_no) VALUES"
                                    + " (?, 'f323', 'eri_status', 'ERI 状态', 'battery', 'radio',"
                                    + " ?, 0, 10),"
                                    + " (?, 'f324', 'battery_life', '预计电池寿命(年)', 'battery',"
                                    + " 'number', NULL, 0, 20),"
                                    + " (?, 'f333', 'next_visit_date', '下次随访时间', 'fu_overview',"
                                    + " 'date', NULL, 0, 30)",
                            FORM_CODE,
                            ERI_SET,
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
