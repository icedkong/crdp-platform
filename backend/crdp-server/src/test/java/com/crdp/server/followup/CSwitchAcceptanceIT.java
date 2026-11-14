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
 * E21-T25（测试，C 植入、计划切换与 ERI 用例；验收「切换表全部用例通过」= §3.3.1 十行 SW-1…SW-10）的执行方套件： 在 E21-T06/T07
 * 行为用例（{@link PlanSwitchIT}，自建计划与映射夹具）之上，把同一张切换表放到**随包产品配置链**上端到端执行—— runbook
 * `docs/ops/hospital-c-plan-preset.md` §3 的激活路径：发布 followup V91 两套预置（C_PACER_FU_PLAN /
 * C_ICD_FU_PLAN，随包 UNPUBLISHED 草稿，OQ-128②）→ 保存植入记录按 followup V86 预置映射自动入组（不 PUT 改映射）→ 十行触发事件逐行断言（C
 * 规格 §5 「计划切换触发事件」表、Q03；行定义见 `docs/reviews/hospital-c-acceptance-review.md` §3.3.1）。与 PlanSwitchIT
 * 的分工：那边锁**行为规则**（合成计划可控参数），这边锁**产品配置可用性**——预置节点（Q03 节点表：术后 72 小时〔前 3 后 4〕/ 1 / 3 / 6 个月 / 1 年、常规随访
 * 12 / 6 个月 ±30 / ±14、ERI 期 1 个月 ±7）真实参与排期，SW-7 / SW-8 / SW-10 的循环日期与窗口宽度全部按预置节点参数落库核对。
 *
 * <p>十行执行（每行至少一条用例，方法名前缀对齐）：
 *
 * <ol>
 *   <li>SW-1 首次植入：V86 映射入 C_PACER_FU_PLAN、起算 = 植入日期；入组即建当期「术后6个月」任务、已过窗口节点 SKIP（Q14）；
 *   <li>SW-2 升级为 CRT-D：原入组 SWITCHED + 未完成任务终止（原因入终止三列）+ 转 C_ICD_FU_PLAN、从升级日期重起算（新计划「术后1个月」±7
 *       开窗即建）；
 *   <li>SW-3 升级为 CRT-P：留在 C_PACER_FU_PLAN、从升级日期重起算（CL-02 假设三）；
 *   <li>SW-4 电池更换（同类型）：计划类型不变、ERI 期循环结束（mode 回 NORMAL / eri_since 清空）、从更换日期重走术后节点（CL-02 假设二）；
 *   <li>SW-5 电极修正：不切换（入组行与当期任务原样保留）；
 *   <li>SW-6 编辑更正起搏器类型：按新类型重新入组（C_ICD_FU_PLAN）、起算仍按**原植入日期**、当期任务按新计划重算；
 *   <li>SW-7 随访录入 ERI 状态 = 接近ERI：mode → ERI、eri_since = 该次随访日期、常规未完成任务终止；ERI 循环 = 随访 + 1 个月、窗口
 *       ±7、逐次随访滚动；
 *   <li>SW-8 ERI 状态更正为正常：恢复常规间隔（普通计划 12 个月 ±30，从解除那次随访滚动）；
 *   <li>SW-9 取出且未再植入：结束入组（无新入组）、未完成任务终止、患者仍在册随访中；
 *   <li>SW-10 医生指定下次随访时间：doctor_next_date 登记、计划类型 / 模式不变；优先级「医生指定 → ERI 期循环 → 计划默认」—— ERI 模式下医生日期覆盖
 *       ERI 循环的下一次。
 * </ol>
 *
 * <p>另附 C-ACC-18 机器面一条：计划切换（升级）后患者时间轴连续——切换前补录记录仍在轴上、下次计划日期来自新入组且不重复（E21-T23 交付列展示适配，此处锁接口行为不回退）。
 *
 * <p>程控记录直接走随包 C_PROGRAM_FU 表单（V90，demoa 常驻，只读引用不删改种子）；ERI 判定键 f323 / f324 / f333（OQ-130①）。 门控
 * CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T25-）；发布态在收尾还原为 V91 草稿态（对 A
 * / B 与其他 IT 惰性，OQ-128②）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CSwitchAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T25";
    private static final String PACER_CODE = "C_PACER_FU_PLAN";
    private static final String ICD_CODE = "C_ICD_FU_PLAN";

    /** 随包医院 C 随访程控表单（V90；f303 随访日期 / f304 随访时间点 / f323 ERI 状态 / f333 下次随访时间）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.crdp.followup.task.app.FollowupTaskCreationService creationService;

    // ---------------------------------------------------------------- 激活（runbook §3 步骤 2/3/4）

    /** 发布两套预置（runbook §3.2：UNPUBLISHED → PUBLISHED 后 V86 映射自动入组生效）——幂等（CI 单次执行）。 */
    @BeforeAll
    void activatePresetPlans() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'");
                    Integer mapping =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM fu_plan_mapping WHERE enabled = 1"
                                            + " AND device_type = 'DDD' AND plan_code = ?",
                                    Integer.class,
                                    PACER_CODE);
                    assertTrue(mapping != null && mapping > 0, "V86 预置映射行应在（DDD → 普通计划）");
                });
        String admin = adminToken();
        for (String code : new String[] {PACER_CODE, ICD_CODE}) {
            long planId = presetPlanId(code);
            String status = presetPlanStatus(planId);
            if (!"PUBLISHED".equals(status)) {
                assertEquals("UNPUBLISHED", status, "预置随包状态应为未发布草稿（OQ-128②）：" + code);
                assertEquals(
                        0,
                        code(
                                post(
                                        "/api/hospital/followup/plans/" + planId + "/publish",
                                        admin,
                                        Map.of())),
                        "发布预置失败：" + code);
            }
            assertEquals("PUBLISHED", presetPlanStatus(planId), "发布后状态：" + code);
        }
    }

    @BeforeEach
    void cleanState() {
        runInTenant("demoa", this::deleteOwnData);
        runInTenant(
                "demoa",
                () -> {
                    // 本 IT 会跑任务创建作业（历史逻辑日）：清执行记录，避免补跑区间影响其他 IT（PlanSwitchIT 同口径）
                    jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
                });
        runInTenant(
                "demob",
                () -> jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'"));
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () -> {
                    // 预置发布态还原为 V91 草稿（本 IT 的激活是测试动作，不留在库里影响其他 IT 与 A / B 惰性口径）
                    jdbc.update(
                            "UPDATE fu_plan SET status = 'UNPUBLISHED', published_at = NULL,"
                                    + " published_by = NULL WHERE code IN (?, ?)",
                            PACER_CODE,
                            ICD_CODE);
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE plan_code IN (?, ?) AND action ="
                                    + " 'PLAN_PUBLISH'",
                            PACER_CODE,
                            ICD_CODE);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                });
    }

    /** 本 IT 自建数据清理（患者按 legacy_no 前缀全链路 + 预置发布审计行；审计删除走维护开关）。 */
    private void deleteOwnData() {
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_log WHERE"
                        + " target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                        + " WHERE legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM fu_record r"
                        + " JOIN pt_patient p ON p.id = r.patient_id WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                        + " JOIN pt_patient p ON p.id = t.patient_id WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR target IN (SELECT CONCAT('ENROLLMENT:', e.id) FROM fu_enrollment e"
                        + " JOIN pt_patient p ON p.id = e.patient_id WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR (event_type = 'PLAN_PUBLISH' AND target IN (SELECT CONCAT('PLAN:', id)"
                        + " FROM fu_plan WHERE code IN ('"
                        + PACER_CODE
                        + "','"
                        + ICD_CODE
                        + "')))");
        jdbc.update(
                "DELETE fh FROM fe_field_history fh JOIN fu_record r ON r.id = fh.record_id"
                        + " JOIN pt_patient p ON p.id = r.patient_id WHERE fh.record_type ="
                        + " 'FU_RECORD' AND p.legacy_no LIKE ?",
                LEGACY + "-%");
        // 预置节点 ownerType = DOCTOR：建任务写 TaskOpened / 逾期写 TASK_OVERDUE 事件（payload 带患者 id）。
        // PENDING 事件残留会被后续 IT 的通知派发消费成 ntf 行、污染通知套件计数（CI 实测 NotifyAcceptanceIT），随患者一并清理。
        jdbc.update(
                "DELETE FROM sys_outbox WHERE event_type IN ('TaskOpened', 'TASK_OVERDUE')"
                        + " AND JSON_EXTRACT(payload, '$.patientId') IN"
                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                LEGACY + "-%");
        jdbc.update(
                "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE il FROM pt_implant_lead il JOIN pt_patient p ON p.id = il.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE l FROM pt_lead l JOIN pt_patient p ON p.id = l.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE i FROM pt_implant i JOIN pt_patient p ON p.id = i.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
    }

    // ---------------------------------------------------------------- SW-1 首次植入（含 runbook §3.4
    // 试入组）

    @Test
    void sw1FirstImplantEnrollsViaPresetMappingFromImplantDate() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        long patientId = registerPatient(admin, "01", "DDD", implant);

        Map<String, Object> enrollment = enrollmentOf(patientId);
        assertNotNull(enrollment, "保存植入记录按 V86 预置映射自动入组（C-FU-003）");
        assertEquals(PACER_CODE, enrollment.get("plan_code"), "DDD 入普通起搏器计划（V91 预置）");
        assertEquals("ACTIVE", enrollment.get("status"));
        assertEquals("NORMAL", enrollment.get("mode"));
        assertEquals(java.sql.Date.valueOf(implant), enrollment.get("start_date"), "起算日 = 植入日期");
        assertNull(enrollment.get("switch_reason"), "首次植入无切换");

        // 入组即建当期任务：术后6个月窗口（±14 天）当期开启 → 恰一条；72 小时 / 1 / 3 个月窗口已过 → SKIP 不补建（Q14）
        List<Map<String, Object>> tasks = tasksOf(patientId);
        assertEquals(1, tasks.size(), "只建当期窗口任务，已过窗口节点 SKIP");
        Map<String, Object> sixMonth = tasks.get(0);
        assertEquals("术后6个月", nodeName(sixMonth), "预置节点名（Q03 节点表）");
        assertEquals(java.sql.Date.valueOf(implant.plusMonths(6)), sixMonth.get("plan_date"));
        assertEquals(
                java.sql.Date.valueOf(implant.plusMonths(6).minusDays(14)),
                sixMonth.get("window_start"),
                "窗口前 14 天");
        assertEquals(
                java.sql.Date.valueOf(implant.plusMonths(6).plusDays(14)),
                sixMonth.get("window_end"),
                "窗口后 14 天");
        assertEquals("PENDING", sixMonth.get("status"));
        assertEquals(1, activeEnrollmentCount(patientId));
    }

    // ---------------------------------------------------------------- SW-2 升级为 ICD / CRT-D

    @Test
    void sw2UpgradeToCrtDSwitchesToIcdPresetPlanFromUpgradeDate() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        LocalDate upgrade = LocalDate.now().minusMonths(1);
        long patientId = registerPatient(admin, "02", "DDD", implant);
        long oldEnrollmentId = enrollmentIdOf(patientId);
        long oldTaskId = pendingTaskOf(oldEnrollmentId);
        assertTrue(oldTaskId > 0, "入组即建当期任务（前置）");

        replace(admin, patientId, "UPGRADE", upgrade, "CRT_D");

        assertEquals("SWITCHED", enrollmentRow(oldEnrollmentId).get("status"), "原（普通）入组结束");
        assertTrue(
                String.valueOf(enrollmentRow(oldEnrollmentId).get("switch_reason")).contains("升级"),
                "switch_reason 记升级原因");
        Map<String, Object> oldTask = taskRow(oldTaskId);
        assertEquals("TERMINATED", oldTask.get("status"), "未完成的旧任务自动终止");
        assertTrue(
                String.valueOf(oldTask.get("terminate_reason")).contains("计划切换"),
                "终止原因记录计划切换：" + oldTask.get("terminate_reason"));

        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current);
        assertEquals(ICD_CODE, current.get("plan_code"), "升级为 CRT-D 转 ICD/CRT-D 计划（V91 预置）");
        assertEquals("NORMAL", current.get("mode"));
        assertEquals(
                java.sql.Date.valueOf(upgrade), current.get("start_date"), "从升级日期重起算 72 小时至 1 年节点");
        assertEquals(1, activeEnrollmentCount(patientId), "不产生双计划并行任务");

        // 新计划「术后1个月」（±7 天）当期开启 → 入组即建；72 小时窗口（升级日 +7 天）已过 → SKIP
        List<Map<String, Object>> tasks = tasksOf(patientId);
        assertEquals(1, tasksByStatus(tasks, "PENDING").size(), "新入组只建当期任务");
        Map<String, Object> oneMonth = tasksByStatus(tasks, "PENDING").get(0);
        assertEquals("术后1个月", nodeName(oneMonth), "ICD 计划预置节点（Q03 节点表）");
        assertEquals(java.sql.Date.valueOf(upgrade.plusMonths(1)), oneMonth.get("plan_date"));
        assertEquals(
                java.sql.Date.valueOf(upgrade.plusMonths(1).minusDays(7)),
                oneMonth.get("window_start"),
                "窗口前 7 天");
        assertEquals(
                java.sql.Date.valueOf(upgrade.plusMonths(1).plusDays(7)),
                oneMonth.get("window_end"),
                "窗口后 7 天");
    }

    // ---------------------------------------------------------------- SW-3 升级为 CRT-P / 普通类型

    @Test
    void sw3UpgradeToCrtPStaysInPacerPresetPlanFromUpgradeDate() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        LocalDate upgrade = LocalDate.now().minusMonths(1);
        long patientId = registerPatient(admin, "03", "DDD", implant);
        long oldEnrollmentId = enrollmentIdOf(patientId);

        replace(admin, patientId, "UPGRADE", upgrade, "CRT_P");

        assertEquals("SWITCHED", enrollmentRow(oldEnrollmentId).get("status"), "同族升级同样结束原入组重起算");
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current);
        assertEquals(PACER_CODE, current.get("plan_code"), "升级为 CRT-P 转入 / 留在普通起搏器计划（CL-02 假设三）");
        assertEquals(java.sql.Date.valueOf(upgrade), current.get("start_date"), "从升级日期重起算");
        assertEquals(1, activeEnrollmentCount(patientId));
    }

    // ---------------------------------------------------------------- SW-4 电池更换（同类型）

    @Test
    void sw4BatteryReplacementEndsEriCycleAndRestartsPostopNodes() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        long patientId = registerPatient(admin, "04", "DDD", implant);
        long eriEnrollmentId = enrollmentIdOf(patientId);

        // 先标 ERI（SW-7 路径）：mode → ERI
        LocalDate eriVisit = LocalDate.now().minusMonths(2);
        submitProgramRecord(admin, patientId, eriVisit, Map.of("f323", 1), 7);
        assertEquals("ERI", enrollmentRow(eriEnrollmentId).get("mode"), "先进入 ERI 期（前置条件）");

        LocalDate replaceDate = LocalDate.now().minusDays(10);
        replace(admin, patientId, "REPLACE", replaceDate, "DDD");

        assertEquals("SWITCHED", enrollmentRow(eriEnrollmentId).get("status"), "ERI 期入组随更换结束");
        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current, "同类型更换重新入组");
        assertEquals(PACER_CODE, current.get("plan_code"), "计划类型不变（同族）");
        assertEquals("NORMAL", current.get("mode"), "ERI 期循环结束（更换器械后结束，C-FU-006）");
        assertNull(current.get("eri_since"), "eri_since 清空");
        assertEquals(
                java.sql.Date.valueOf(replaceDate),
                current.get("start_date"),
                "按更换日期重新走 72 小时至 1 年节点（CL-02 假设二）");

        // 更换日 +10 天：72 小时窗口（更换日至 +7 天）已过 → SKIP、1 个月窗口（±7）未开启 → 无任务；ERI 循环不再创建
        long currentEnrollmentId = ((Number) current.get("id")).longValue();
        creationService.runForDate(LocalDate.now().plusDays(10));
        assertEquals(
                0,
                taskCountByEnrollment(currentEnrollmentId),
                "新入组无任务（已过窗口 SKIP、未来节点不预生成）且不再创建 ERI 循环任务");
    }

    // ---------------------------------------------------------------- SW-5 电极修正

    @Test
    void sw5LeadRevisionKeepsPresetEnrollmentUntouched() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        long patientId = registerPatient(admin, "05", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);
        long taskId = pendingTaskOf(enrollmentId);
        assertTrue(taskId > 0, "入组即建当期任务（前置）");
        Map<String, Object> before = enrollmentRow(enrollmentId);

        replace(admin, patientId, "LEAD_REVISION", LocalDate.now().minusMonths(1), "DDD");

        Map<String, Object> after = enrollmentRow(enrollmentId);
        assertEquals("ACTIVE", after.get("status"), "电极修正不切换（入组行不变）");
        assertEquals(before.get("plan_code"), after.get("plan_code"));
        assertEquals(before.get("start_date"), after.get("start_date"), "不重起算");
        assertEquals(before.get("mode"), after.get("mode"));
        assertEquals("PENDING", taskRow(taskId).get("status"), "当期任务不终止");
        assertEquals(1, activeEnrollmentCount(patientId));
    }

    // ---------------------------------------------------------------- SW-6 器械类型更正（录入纠错）

    @Test
    void sw6DeviceTypeCorrectionReenrollsFromOriginalImplantDate() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        long patientId = registerPatient(admin, "06", "DDD", implant);
        long oldEnrollmentId = enrollmentIdOf(patientId);
        long oldTaskId = pendingTaskOf(oldEnrollmentId);
        assertTrue(oldTaskId > 0, "入组即建当期任务（前置）");

        Map<?, ?> implantRow = firstImplant(admin, patientId);
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", ((Number) implantRow.get("version")).intValue());
        body.put("implant", implantPayload("FIRST", implant, "ICD"));
        body.put("reason", "类型更正（示例，SW-6）");
        assertEquals(
                0,
                code(put("/api/hospital/followup/implants/" + implantRow.get("id"), admin, body)),
                "编辑更正起搏器类型失败");

        assertEquals("SWITCHED", enrollmentRow(oldEnrollmentId).get("status"), "纠错切换结束原入组");
        assertTrue(
                String.valueOf(enrollmentRow(oldEnrollmentId).get("switch_reason")).contains("更正"),
                "switch_reason 记更正原因");
        assertEquals("TERMINATED", taskRow(oldTaskId).get("status"), "未开始任务随切换终止（按新计划重算）");

        Map<String, Object> current = enrollmentOf(patientId);
        assertNotNull(current);
        assertEquals(ICD_CODE, current.get("plan_code"), "按新类型（ICD）重新入组（V86 映射）");
        assertEquals(
                java.sql.Date.valueOf(implant),
                current.get("start_date"),
                "起算仍按原植入日期（SW-6「否，仍按原植入日期」）");

        // 重算后新入组当期任务：ICD 计划「术后6个月」（原植入 +6 个月 ±14 天，当期开启）
        List<Map<String, Object>> pending = tasksByStatus(tasksOf(patientId), "PENDING");
        assertEquals(1, pending.size(), "未开始任务按新计划重算（恰一条当期）");
        assertEquals("术后6个月", nodeName(pending.get(0)));
        assertEquals(java.sql.Date.valueOf(implant.plusMonths(6)), pending.get(0).get("plan_date"));
    }

    // ---------------------------------------------------------------- SW-7 接近 ERI（ERI
    // 期循环，C-FU-006）

    @Test
    void sw7EriMarkingSwitchesToMonthlyLoopOnPresetEriNode() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(6);
        long patientId = registerPatient(admin, "07", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);
        long routineTaskId = pendingTaskOf(enrollmentId);
        assertTrue(routineTaskId > 0, "入组即建当期任务（前置）");
        // 历史患者时间线（C-PAT-008）：入组 created_at 回拨到植入日，本用例用过去逻辑日复跑每日作业（PlanSwitchIT 同口径）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                                java.sql.Timestamp.valueOf(implant.atStartOfDay()),
                                enrollmentId));

        // 随访录入 ERI 状态 = 接近ERI：mode → ERI、eri_since = 该次随访日期、常规未完成任务终止
        LocalDate eriVisit = today.minusMonths(2);
        submitProgramRecord(admin, patientId, eriVisit, Map.of("f323", 1), 7);
        Map<String, Object> eriRow = enrollmentRow(enrollmentId);
        assertEquals("ERI", eriRow.get("mode"), "标 ERI 后切循环（SW-7）");
        assertEquals(java.sql.Date.valueOf(eriVisit), eriRow.get("eri_since"), "从该次随访日期起算");
        assertEquals(
                java.sql.Date.valueOf(eriVisit),
                eriRow.get("last_visit_date"),
                "滚动基准快照 = 该次随访（E21-T07）");
        assertEquals("ACTIVE", eriRow.get("status"), "ERI 切换不换入组行");
        assertEquals("TERMINATED", taskRow(routineTaskId).get("status"), "常规未完成任务终止（循环接管）");
        assertTrue(
                String.valueOf(taskRow(routineTaskId).get("terminate_reason")).contains("ERI"),
                "终止原因记录 ERI 切换");

        // ERI 循环第 1 次 = 随访 + 1 个月、窗口 ±7（预置 ERI期随访节点）：开启日（−7 天）跑作业即创建
        LocalDate firstPlan = eriVisit.plusMonths(1);
        creationService.runForDate(firstPlan.minusDays(7));
        Map<String, Object> firstTask = eriLoopTask(enrollmentId, 1, "ERI 循环第 1 次任务应创建");
        assertEquals("ERI期随访", nodeName(firstTask), "预置 ERI 期循环节点（Q03 节点表：1 个月 ±7）");
        assertEquals(java.sql.Date.valueOf(firstPlan), firstTask.get("plan_date"));
        assertEquals(java.sql.Date.valueOf(firstPlan.minusDays(7)), firstTask.get("window_start"));
        assertEquals(java.sql.Date.valueOf(firstPlan.plusDays(7)), firstTask.get("window_end"));
        assertEquals("PENDING", firstTask.get("status"));
        assertEquals(
                1,
                pendingTaskCountByPatient(patientId),
                "ERI 模式只排 ERI 循环节点（固定 / 常规不再创建，OQ-138⑦；终止的常规任务不计）");

        // 第 2 次随访（晚于第 1 次计划日，窗口内）后循环从该次随访滚动：第 2 次 = 该次 + 1 个月（不是第 1 次计划日 + 周期）
        LocalDate secondVisit = today.minusDays(25);
        submitProgramRecord(admin, patientId, secondVisit, Map.of("f323", 1), 7);
        assertEquals(
                java.sql.Date.valueOf(secondVisit),
                enrollmentRow(enrollmentId).get("last_visit_date"),
                "快照随每次随访单调推进");
        LocalDate secondPlan = secondVisit.plusMonths(1);
        creationService.runForDate(secondPlan.minusDays(7));
        Map<String, Object> secondTask = eriLoopTask(enrollmentId, 2, "ERI 循环第 2 次应按上次随访滚动创建");
        assertEquals(java.sql.Date.valueOf(secondPlan), secondTask.get("plan_date"));
        assertEquals("PENDING", secondTask.get("status"));
        assertEquals(2, nodeTaskCount(enrollmentId, "ERI期随访"), "ERI 循环共 2 次");
    }

    // ---------------------------------------------------------------- SW-8 ERI 标记解除

    @Test
    void sw8EriClearedRestoresPacerRoutineCadence() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(8);
        long patientId = registerPatient(admin, "08", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);

        LocalDate eriVisit = today.minusMonths(6);
        submitProgramRecord(admin, patientId, eriVisit, Map.of("f323", 1), 7);
        assertEquals("ERI", enrollmentRow(enrollmentId).get("mode"), "先进入 ERI 期（前置）");

        // 后续随访 ERI 状态更正为正常：恢复原计划的常规间隔（普通计划 12 个月 ±30）
        LocalDate clearedVisit = eriVisit.plusDays(20);
        submitProgramRecord(admin, patientId, clearedVisit, Map.of("f323", 0), 6);
        Map<String, Object> restored = enrollmentRow(enrollmentId);
        assertEquals("NORMAL", restored.get("mode"), "ERI 标记解除恢复常规间隔（SW-8）");
        assertNull(restored.get("eri_since"), "eri_since 清空");
        assertEquals("ACTIVE", restored.get("status"), "仍在原入组（不换行）");
        assertEquals(java.sql.Date.valueOf(clearedVisit), restored.get("last_visit_date"));

        // 常规随访第 1 次 = 解除那次随访 + 12 个月、窗口 ±30（预置常规随访节点，普通计划）：开启日（−30 天）创建
        LocalDate routinePlan = clearedVisit.plusMonths(12);
        creationService.runForDate(routinePlan.minusDays(30));
        Map<String, Object> routineTask = nodeTask(enrollmentId, "常规随访");
        assertNotNull(routineTask, "常规随访按上次随访滚动创建（预置节点）");
        assertEquals(java.sql.Date.valueOf(routinePlan), routineTask.get("plan_date"));
        assertEquals(
                java.sql.Date.valueOf(routinePlan.minusDays(30)),
                routineTask.get("window_start"),
                "窗口前 30 天");
        assertEquals(
                java.sql.Date.valueOf(routinePlan.plusDays(30)),
                routineTask.get("window_end"),
                "窗口后 30 天");
        assertEquals(1, ((Number) routineTask.get("occurrence")).intValue());
        assertEquals(0, nodeTaskCount(enrollmentId, "ERI期随访"), "常规模式不排 ERI 循环节点");
    }

    // ---------------------------------------------------------------- SW-9 取出且未再植入

    @Test
    void sw9ExplantEndsEnrollmentWithoutReenrollment() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        long patientId = registerPatient(admin, "09", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);
        long taskId = pendingTaskOf(enrollmentId);
        assertTrue(taskId > 0, "入组即建当期任务（前置）");

        Map<?, ?> implantRow = firstImplant(admin, patientId);
        List<Map<String, Object>> leadActions = new ArrayList<>();
        for (Object lead : (List<?>) implantRow.get("leads")) {
            leadActions.add(
                    Map.of(
                            "leadId",
                            String.valueOf(((Map<?, ?>) lead).get("id")),
                            "action",
                            "EXTRACT"));
        }
        Map<?, ?> explanted =
                post(
                        "/api/hospital/followup/implants/" + implantRow.get("id") + "/explant",
                        admin,
                        Map.of(
                                "baseVersion",
                                ((Number) implantRow.get("version")).intValue(),
                                "date",
                                LocalDate.now().minusMonths(1).toString(),
                                "reason",
                                "感染取出（示例）",
                                "leadActions",
                                leadActions));
        assertEquals(0, code(explanted), "取出失败：" + explanted);

        assertEquals(0, activeEnrollmentCount(patientId), "结束入组、不再创建任务（SW-9）");
        Map<String, Object> row = enrollmentRow(enrollmentId);
        assertEquals("SWITCHED", row.get("status"));
        assertTrue(
                String.valueOf(row.get("switch_reason")).contains("取出"),
                "switch_reason 记取出：" + row.get("switch_reason"));
        assertEquals("TERMINATED", taskRow(taskId).get("status"), "未完成任务终止");
        creationService.runForDate(LocalDate.now().plusDays(5));
        assertEquals(0, pendingTaskCountByPatient(patientId), "取出后不再创建任何任务");
        assertEquals(
                "ACTIVE",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                                        String.class,
                                        patientId)),
                "取出不落患者级「已终止」（再植入须能重新入组）");
    }

    // ---------------------------------------------------------------- SW-10 医生指定下次随访时间（优先级）

    @Test
    void sw10DoctorNextDateRegistersAndWinsOverEriLoop() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(6);
        long patientId = registerPatient(admin, "10", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);

        // 先标 ERI：循环第 1 次 = 随访 + 1 个月（today+20d，开启日 today+13d）
        LocalDate eriVisit = today.minusDays(10);
        submitProgramRecord(admin, patientId, eriVisit, Map.of("f323", 1), 7);
        assertEquals("ERI", enrollmentRow(enrollmentId).get("mode"), "先进入 ERI 期（前置）");

        // 医生指定下次随访时间：doctor_next_date 登记、不改变计划类型 / 模式（SW-10）
        LocalDate doctorDate = today.plusDays(25);
        submitProgramRecord(
                admin, patientId, today.minusDays(3), Map.of("f333", doctorDate.toString()), 6);
        Map<String, Object> doctorRow = enrollmentRow(enrollmentId);
        assertEquals("ERI", doctorRow.get("mode"), "医生指定不改变计划类型 / 模式");
        assertEquals(PACER_CODE, doctorRow.get("plan_code"));
        assertEquals(
                java.sql.Date.valueOf(doctorDate),
                doctorRow.get("doctor_next_date"),
                "登记医生指定的下一次计划日期");

        // 优先级「医生指定 → ERI 期循环 → 计划默认」：医生窗口开启日（doctorDate − 节点窗口前 7 天）跑作业，
        // ERI 循环的下一次被医生日期覆盖（计划日 = 医生日而不是循环默认的 today+20d）
        LocalDate eriDefault = eriVisit.plusMonths(1);
        creationService.runForDate(doctorDate.minusDays(7));
        Map<String, Object> task = eriLoopTask(enrollmentId, 1, "医生日期应覆盖 ERI 循环的下一次");
        assertEquals(
                java.sql.Date.valueOf(doctorDate),
                task.get("plan_date"),
                "医生指定优先于 ERI 期循环（C 规格 §5 优先级）");
        assertEquals(
                java.sql.Date.valueOf(doctorDate.minusDays(7)),
                task.get("window_start"),
                "窗口按目标节点宽度围绕医生日期");
        assertEquals(java.sql.Date.valueOf(doctorDate.plusDays(7)), task.get("window_end"));
        assertTrue(
                !java.sql.Date.valueOf(eriDefault).equals(task.get("plan_date")),
                "不是 ERI 循环默认计划日 " + eriDefault);
    }

    // ---------------------------------------------------------------- C-ACC-18 机器面：切换后时间轴连续

    /** E21-T23（C-FU-020 / C-FU-023）：时间轴器械事件菱形 + 标红项数、任务列表 C 三列（随包产品配置链端到端）。 */
    @Test
    void timelineAndTaskListCarryCColumnsAcrossPlanSwitch() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(6);
        long patientId = registerPatient(admin, "23", "DDD", implant);

        // 一条已提交补录记录；直接注入 E17-T04 语义的命中数组（提交重算由 CompareHighlightIT 锁定，此处锁 E21-T23 读取侧）
        String recordId = submitProgramRecord(admin, patientId, today.minusDays(40), Map.of(), 6);
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_record SET highlight = ? WHERE id = ?",
                                "[{\"ruleId\":9001,\"key\":\"f310\",\"message\":\"示例标红（E21-T23 用例）\"}]",
                                Long.parseLong(recordId)));

        LocalDate upgrade = today.minusDays(5);
        replace(admin, patientId, "UPGRADE", upgrade, "CRT_D");

        Map<?, ?> timeline =
                data(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/followup-timeline",
                                admin));
        List<Map<?, ?>> items = (List<Map<?, ?>>) timeline.get("items");

        // 器械事件（菱形）：首次植入（入组语境）与升级（转入语境，C-FU-007）都插在时间线上
        List<Map<?, ?>> events = nodesOf(items, "IMPLANT");
        assertEquals(2, events.size(), "每条植入记录一个器械事件节点");
        assertEquals("首次植入 · 双腔起搏器(DDD)", events.get(0).get("label"));
        assertEquals("入组“普通起搏器术后随访”", events.get(0).get("note"));
        assertEquals("升级 · CRT-D", events.get(1).get("label"));
        assertTrue(
                String.valueOf(events.get(1).get("note")).contains("ICD/CRT-D 术后随访"),
                "升级 note 带转入语境：" + events.get(1).get("note"));

        // 补录节点带标红项数（C-FU-023「各次随访……每次的标红项数」，含补录）
        Map<?, ?> backfill = nodeOf(items, "BACKFILL");
        assertNotNull(backfill, "补录记录在轴上");
        assertEquals(1, backfill.get("highlightCount"), "标红项数 = 注入命中数");

        // 任务列表 C 三列（C-FU-020）：当前器械 / 随访时间点 / 上次随访标红项数
        Map<?, ?> searched =
                data(
                        post(
                                "/api/hospital/followup/tasks/search",
                                admin,
                                Map.of("scope", "ALL", "pageNo", 1, "pageSize", 100)));
        List<Map<?, ?>> rows =
                ((List<Map<?, ?>>) searched.get("items"))
                        .stream()
                                .filter(
                                        row ->
                                                String.valueOf(row.get("patientId"))
                                                        .equals(String.valueOf(patientId)))
                                .collect(java.util.stream.Collectors.toList());
        assertFalse(rows.isEmpty(), "该患者任务在列表中");
        for (Map<?, ?> row : rows) {
            assertEquals("CRT-D", row.get("deviceType"), "当前器械类型 = 升级后在用 CRT-D 字典显示名");
            assertNotNull(row.get("timepoint"), "随访时间点 = 任务节点名（如术后1个月 / ERI期随访）");
            assertEquals(1, row.get("lastHighlightCount"), "上次随访标红项数 = 注入命中数");
        }
    }

    @Test
    void timelineStaysContinuousAcrossPlanSwitch() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(6);
        long patientId = registerPatient(admin, "11", "DDD", implant);

        // 切换前一条补录记录（历史）
        LocalDate backfillVisit = today.minusDays(40);
        String recordId = submitProgramRecord(admin, patientId, backfillVisit, Map.of(), 6);

        LocalDate upgrade = today.minusDays(5);
        replace(admin, patientId, "UPGRADE", upgrade, "CRT_D");

        Map<?, ?> timeline =
                data(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/followup-timeline",
                                admin));
        List<Map<?, ?>> items = (List<Map<?, ?>>) timeline.get("items");

        Map<?, ?> backfill = nodeOf(items, "BACKFILL");
        assertNotNull(backfill, "切换前的补录记录仍在时间轴上（连续性，C-ACC-18）");
        assertEquals(recordId, String.valueOf(backfill.get("recordId")));
        assertEquals(backfillVisit.toString(), String.valueOf(backfill.get("date")));

        // 新入组的当期任务（72 小时窗口 [upgrade, upgrade+7d] 当期开启）在轴上；被切换终止的旧任务同样在轴上（历史保留）
        List<Map<?, ?>> followups = nodesOf(items, "FOLLOWUP");
        assertEquals(2, followups.size(), "旧（终止）+ 新（待随访）任务节点都在轴上");
        assertTrue(
                followups.stream().anyMatch(item -> "PENDING".equals(item.get("status"))),
                "新入组当期任务待随访");
        assertTrue(
                followups.stream().anyMatch(item -> "TERMINATED".equals(item.get("status"))),
                "切换终止的旧任务保留为历史节点");

        // 下次计划日期来自新入组（ICD 计划「术后1个月」），且只此一条（旧入组 SWITCHED 不再贡献）
        List<Map<?, ?>> planned = nodesOf(items, "PLANNED");
        assertEquals(1, planned.size(), "恰一条下次计划日期（无旧入组残留）");
        assertEquals(
                upgrade.plusMonths(1).toString(),
                String.valueOf(planned.get(0).get("planDate")),
                "下次计划日期 = 新计划术后 1 个月（时间轴连续到新计划）");
    }

    // ---------------------------------------------------------------- 辅助（PlanSwitchIT 同款口径）

    /** C 登记并返回患者 id（implant 开关开启时同建首条植入记录）。 */
    private long registerPatient(
            String token, String suffix, String deviceType, LocalDate implantDate) {
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

    /** 植入记录载荷（RA / RV 两根电极）。 */
    private static Map<String, Object> implantPayload(
            String type, LocalDate date, String deviceType) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", type);
        implant.put("implantDate", date.toString());
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-" + LEGACY + "-" + deviceType + "-" + date.hashCode());
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

    /** 更换 / 升级 / 电极修正（在用电极逐根 KEEP；电极修正不换脉冲发生器——沿用原记录序列号 / 品牌 / 型号）。 */
    private void replace(
            String token, long patientId, String type, LocalDate date, String deviceType) {
        Map<?, ?> implantRow = firstImplant(token, patientId);
        List<Map<String, Object>> leadActions = new ArrayList<>();
        for (Object lead : (List<?>) implantRow.get("leads")) {
            leadActions.add(
                    Map.of(
                            "leadId",
                            String.valueOf(((Map<?, ?>) lead).get("id")),
                            "action",
                            "KEEP"));
        }
        Map<String, Object> payload = implantPayload(type, date, deviceType);
        if ("LEAD_REVISION".equals(type)) {
            payload.put("serialNo", String.valueOf(implantRow.get("serialNo")));
            payload.put("brand", String.valueOf(implantRow.get("brand")));
            payload.put("model", String.valueOf(implantRow.get("model")));
        }
        Map<?, ?> replaced =
                post(
                        "/api/hospital/followup/implants/" + implantRow.get("id") + "/replace",
                        token,
                        Map.of(
                                "baseVersion",
                                ((Number) implantRow.get("version")).intValue(),
                                "type",
                                type,
                                "implant",
                                payload,
                                "leadActions",
                                leadActions,
                                "newLeads",
                                List.of()));
        assertEquals(0, code(replaced), type + " 失败：" + replaced);
    }

    /**
     * 补录一条随包 C_PROGRAM_FU 程控记录并提交（visitDate 须早于今天且晚于植入日）； extra 为 f323 / f324 / f333，timepoint 为
     * c_visit_timepoint 选项编码（整数：6 常规、7 ERI 期）。返回记录 id。
     */
    private String submitProgramRecord(
            String token,
            long patientId,
            LocalDate visitDate,
            Map<String, Object> extra,
            int timepoint) {
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
                                C_FORM));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<String, Object> dataBody = new HashMap<>();
        dataBody.put("f303", visitDate.toString());
        dataBody.put("f304", timepoint);
        dataBody.putAll(extra);
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        token,
                        Map.of("baseVersion", 0, "data", dataBody));
        assertEquals(0, code(submitted), "补录提交失败：" + submitted);
        return recordId;
    }

    private Map<?, ?> firstImplant(String token, long patientId) {
        Map<?, ?> list = get("/api/hospital/followup/patients/" + patientId + "/implants", token);
        assertEquals(0, code(list));
        List<?> items = (List<?>) data(list).get("items");
        return (Map<?, ?>) items.get(0);
    }

    private long presetPlanId(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_plan WHERE code = ?", Long.class, code));
    }

    private String presetPlanStatus(long planId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM fu_plan WHERE id = ?", String.class, planId));
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
                                CSwitchAcceptanceIT::resultMap,
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

    private List<Map<String, Object>> tasksOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.query(
                                "SELECT t.*, n.name AS node_name FROM fu_task t"
                                        + " JOIN fu_plan_node n ON n.id = t.node_id"
                                        + " WHERE t.patient_id = ? ORDER BY t.id",
                                CSwitchAcceptanceIT::resultMap,
                                patientId));
    }

    private static List<Map<String, Object>> tasksByStatus(
            List<Map<String, Object>> tasks, String status) {
        return tasks.stream()
                .filter(task -> status.equals(task.get("status")))
                .collect(java.util.stream.Collectors.toList());
    }

    private static String nodeName(Map<String, Object> task) {
        return String.valueOf(task.get("node_name"));
    }

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
                                CSwitchAcceptanceIT::resultMap,
                                taskId));
    }

    private int taskCountByEnrollment(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?",
                                Integer.class,
                                enrollmentId));
    }

    private int pendingTaskCountByPatient(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?"
                                        + " AND status = 'PENDING'",
                                Integer.class,
                                patientId));
    }

    /** 该入组指定节点名的最新一条任务行（无则 null）。 */
    private Map<String, Object> nodeTask(long enrollmentId, String nodeName) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.query(
                                "SELECT t.* FROM fu_task t JOIN fu_plan_node n ON n.id = t.node_id"
                                        + " WHERE t.enrollment_id = ? AND n.name = ?"
                                        + " ORDER BY t.id DESC LIMIT 1",
                                rs -> rs.next() ? resultMap(rs, 0) : null,
                                enrollmentId,
                                nodeName));
    }

    private int nodeTaskCount(long enrollmentId, String nodeName) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task t JOIN fu_plan_node n"
                                        + " ON n.id = t.node_id WHERE t.enrollment_id = ?"
                                        + " AND n.name = ?",
                                Integer.class,
                                enrollmentId,
                                nodeName));
    }

    /** 该入组「ERI期随访」节点第 occurrence 次任务行（无则断言失败）。 */
    private Map<String, Object> eriLoopTask(long enrollmentId, int occurrence, String message) {
        Map<String, Object> row =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.query(
                                        "SELECT t.*, n.name AS node_name FROM fu_task t"
                                                + " JOIN fu_plan_node n ON n.id = t.node_id"
                                                + " WHERE t.enrollment_id = ? AND n.name ="
                                                + " 'ERI期随访' AND t.occurrence = ?",
                                        rs -> rs.next() ? resultMap(rs, 0) : null,
                                        enrollmentId,
                                        occurrence));
        assertNotNull(row, message);
        return row;
    }

    private static Map<?, ?> nodeOf(List<Map<?, ?>> items, String kind) {
        return nodesOf(items, kind).stream().findFirst().orElse(null);
    }

    private static List<Map<?, ?>> nodesOf(List<Map<?, ?>> items, String kind) {
        return items.stream()
                .filter(item -> kind.equals(item.get("kind")))
                .collect(java.util.stream.Collectors.toList());
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
