package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
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
 * E21-T24（后端，C 后端接口自动化用例补充；验收「C 核心接口用例纳入 CI」）的补充套件：评审用例集
 * `docs/reviews/hospital-c-acceptance-review.md` §3 中标注「机器面 E21-T24」、且既有套件尚未在**随包 C_PROGRAM_FU
 * 模板**（followup V90，demoa 常驻）上锁定的接口行为。
 *
 * <p>与既有套件的分工（不重复覆盖）：
 *
 * <ul>
 *   <li>C-ACC-01 器械上下文条件显示：{@link DeviceContextIT}（E21-T03）在 A_ANNUAL_FU 快照**注入**两个 ctx
 *       字段锁引擎行为；本类在真实 C 模板的 f308–f312（device.hasRaLead）/ f321（device.chambers ≥ 2）/
 *       f329–f341（device.type in [ICD, CRT_D]）键位上锁产品配置链（V90 的 visible_when DSL 随包可用）；
 *   <li>C-ACC-02 校验：互斥 / 「其他」说明的前端交互由 form-engine spec 锁定，本类锁后端契约位——schema 下发 exclusive /
 *       needsDetail 标记、多选编码白名单（40001）、软范围黄提示（提交成功 + warnings + data_meta 确认人透传，CL-01）与<b>上限频率 ≤
 *       下限频率阻止保存</b>（fe_rule 硬校验 42200）。后者随 V98 修正：V90 预置行写了引擎不支持的 op "gt"（FormRuleEngine 契约仅 lt /
 *       lte，shared-cases form-validation-cases.json），规则被静默跳过、硬校验从未生效——本类先红后绿锁住该缺陷不回归；
 *   <li>C-ACC-20 从任务进入录入自动带入（C-FU-008）：visitNo（105 前缀，OQ-131①）/ sequenceNo / entryDefaults（随访医生 =
 *       当前用户）/ device（当前器械）/ context.nodeName（随访时间点来自任务子计划）； 重复提示 C-ACC-21 的「确认后可保存」一并在 C
 *       表单上锁（提示位本身由 {@link RecordEditDeleteIT} 覆盖）；
 *   <li>其余「机器面 E21-T24」用例已有承载：C-ACC-06 → {@link ImplantReplaceIT}、C-ACC-08 / 09 → {@link
 *       VisitNoRegistrationIT}、C-ACC-10 → {@link PatientRegistrationIT}（C-PAT-002 同链路）、 C-ACC-22 →
 *       {@link RecordEditDeleteIT}、C-ACC-38 → {@link WardAndTransferIT}（E21-T21 科室病区 OR）、 C-ACC-39
 *       / 41 脱敏明文与审计断言 → DataScopeMaskingIT / PatientPlaintextIT / FileServiceAcceptanceIT
 *       链路；C-ACC-24（远程监测）/ C-ACC-32（重复文件哈希）随 E21-T12 / E21-T18 落地后补充。
 * </ul>
 *
 * <p>程控记录直接走随包 C_PROGRAM_FU 表单（V90，只读引用不删改种子）；任务用例沿 CSwitchAcceptanceIT 的激活路径发布 followup V91
 * 两套预置（C_PACER_FU_PLAN / C_ICD_FU_PLAN）后按 V86 预置映射自动入组。 门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose
 * 注入端口）。自建自清（legacy_no 前缀 IT-E21T24-）；预置 发布态与 impl 开关收尾还原（对 A / B 与其他 IT 惰性，OQ-128②）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CProgramEntryApiIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T24";
    private static final String PACER_CODE = "C_PACER_FU_PLAN";
    private static final String ICD_CODE = "C_ICD_FU_PLAN";

    /** 随包医院 C 随访程控表单（V90；f303 随访日期 / f304 随访时间点 / f307 症状 / f316 心室阻抗 / f319 / f320 频率）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- 激活（与 CSwitchAcceptanceIT
    // 同口径）

    /** 开 impl 开关 + 发布两套预置（幂等：已发布则跳过；本类任务用例需要 V86 预置映射入组建当期任务）。 */
    @BeforeAll
    void activatePresetPlans() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'");
                    // E21-T12 / C-FU-025：远程监测记录开关（本类用例开启模拟医院 C 配置，@AfterAll 还原）
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                    + " 'remote_monitor_record'");
                });
        String admin = adminToken();
        for (String code : new String[] {PACER_CODE, ICD_CODE}) {
            long planId = presetPlanId(code);
            if (!"PUBLISHED".equals(presetPlanStatus(planId))) {
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
                () -> jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'"));
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () -> {
                    // 预置发布态还原为 V91 草稿、impl 开关关闭（本类的激活是测试动作，不留在库里影响其他 IT）
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
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                    + " 'remote_monitor_record'");
                });
    }

    /** 本 IT 自建数据清理（患者按 legacy_no 前缀全链路；TaskOpened / TASK_OVERDUE outbox 事件随患者清，防污染通知 IT）。 */
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
                "DELETE i FROM pt_implant i JOIN pt_patient p ON p.id = i.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
    }

    // ---------------------------------------------------------------- C-ACC-01 真实模板：器械上下文条件显示

    @Test
    void realTemplateHidesAndClearsAtrialAvAndIcdFieldsForSingleChamberPatient() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "01", "SSI", implant, List.of("RV"));

        String recordId = createBackfillRecord(admin, patientId, visit);
        Map<?, ?> detail = data(get("/api/hospital/followup/records/" + recordId, admin));
        Map<?, ?> device = (Map<?, ?>) detail.get("device");
        assertNotNull(device, "implant 开关开启且有在用器械：device 随记录下发（E21-T03）");
        assertEquals("SSI", device.get("type"), "单腔起搏器（无 RA 电极）");
        assertEquals(false, device.get("hasRaLead"));
        assertEquals(1, device.get("chambers"), "单腔 = 1 个腔室");

        // 心房参数 f308 / AV 间期 f321 / ICD 项目 f329 / f340 / f341 全部隐藏：有值也清空、隐藏不必填（提交不受阻）
        Map<String, Object> form = baseProgramData(visit, 2);
        form.put("f308", 1.5);
        form.put("f321", 120);
        form.put("f329", List.of(1));
        form.put("f340", 1.5);
        form.put("f341", 50);
        Map<?, ?> submitted = submit(admin, recordId, form, null);
        assertEquals(0, code(submitted), "隐藏字段不必填，SSI 患者提交成功：" + submitted);

        Map<?, ?> after = data(get("/api/hospital/followup/records/" + recordId, admin));
        Map<?, ?> values = (Map<?, ?>) after.get("data");
        assertNull(values.get("f308"), "心房参数（ctx device.hasRaLead）在单腔患者被清空（tech-plan §6）");
        assertNull(values.get("f321"), "AV 间期（ctx device.chambers ≥ 2）在单腔患者被清空");
        assertNull(values.get("f329"), "ICD 项目（ctx device.type in [ICD, CRT_D]）在普通起搏器被清空");
        assertNull(values.get("f340"), "充电时间（ICD 专属）被清空");
        assertNull(values.get("f341"), "高压阻抗（ICD 专属）被清空");
        assertEquals(800, ((Number) values.get("f316")).intValue(), "无条件字段（心室阻抗）正常保存");
    }

    @Test
    void realTemplateKeepsAtrialAvAndIcdFieldsForCrtDPatient() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "02", "CRT_D", implant, List.of("RA", "RV", "LV"));

        String recordId = createBackfillRecord(admin, patientId, visit);
        Map<String, Object> form = baseProgramData(visit, 2);
        form.put("f308", 1.5);
        form.put("f321", 120);
        form.put("f340", 2.5);
        form.put("f341", 45);
        Map<?, ?> submitted = submit(admin, recordId, form, null);
        assertEquals(0, code(submitted), "CRT-D 患者全字段可见可保存：" + submitted);

        Map<?, ?> detail = data(get("/api/hospital/followup/records/" + recordId, admin));
        Map<?, ?> device = (Map<?, ?>) detail.get("device");
        assertEquals("CRT_D", device.get("type"));
        assertEquals(true, device.get("hasRaLead"), "CRT-D 三根电极含 RA");
        assertEquals(3, device.get("chambers"), "三腔 = 3");
        Map<?, ?> values = (Map<?, ?>) detail.get("data");
        assertEquals(1.5, ((Number) values.get("f308")).doubleValue(), "心房参数保留（有 RA 电极）");
        assertEquals(120, ((Number) values.get("f321")).intValue(), "AV 间期保留（chambers ≥ 2）");
        assertEquals(2.5, ((Number) values.get("f340")).doubleValue(), "充电时间保留（CRT-D）");
        assertEquals(45, ((Number) values.get("f341")).intValue(), "高压阻抗保留（CRT-D）");
    }

    // ---------------------------------------------------------------- C-ACC-02 校验（V98 修正后锁死）

    @Test
    void upperRateNotAboveLowerRateBlocksSubmit() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "03", "DDD", implant, List.of("RA", "RV"));

        // 上限 80 < 下限 90 → 硬校验拦截（C-FLD-007「上限频率 ≤ 下限频率 → 阻止保存」）
        String recordId = createBackfillRecord(admin, patientId, visit);
        Map<String, Object> lowerHigher = baseProgramData(visit, 6);
        lowerHigher.put("f319", 90);
        lowerHigher.put("f320", 80);
        assertRateBlocked(admin, recordId, lowerHigher);

        // 相等同样拦截（「须大于」不含等于）
        String recordId2 = createBackfillRecord(admin, patientId, visit);
        Map<String, Object> equal = baseProgramData(visit, 6);
        equal.put("f319", 80);
        equal.put("f320", 80);
        assertRateBlocked(admin, recordId2, equal);

        // 对照组：上限 > 下限放行
        String recordId3 = createBackfillRecord(admin, patientId, visit);
        Map<String, Object> valid = baseProgramData(visit, 6);
        valid.put("f319", 60);
        valid.put("f320", 110);
        assertEquals(0, code(submit(admin, recordId3, valid, null)), "上限 > 下限应放行：" + recordId3);
    }

    /** 42200 + fieldErrors 命中 upper_rate_gt_lower（挂在 f320，文案与 fe_rule.message 同源）。 */
    private void assertRateBlocked(String admin, String recordId, Map<String, Object> form) {
        Map<?, ?> blocked = submit(admin, recordId, form, null);
        assertEquals(42200, code(blocked), "上限频率 ≤ 下限频率必须阻止保存：" + blocked);
        List<Map<?, ?>> fieldErrors =
                (List<Map<?, ?>>) ((Map<?, ?>) blocked.get("data")).get("fieldErrors");
        assertTrue(
                fieldErrors.stream()
                        .anyMatch(issue -> "upper_rate_gt_lower".equals(issue.get("rule"))),
                "命中规则 upper_rate_gt_lower：" + fieldErrors);
        assertTrue(
                fieldErrors.stream()
                        .anyMatch(
                                issue ->
                                        "f320".equals(issue.get("key"))
                                                && String.valueOf(issue.get("message"))
                                                        .contains("上限频率须大于下限频率")),
                "错误挂在上限频率 f320、文案与 fe_rule 同源：" + fieldErrors);
    }

    @Test
    void outOfSoftRangeImpedanceWarnsButSavesAndKeepsConfirmerInDataMeta() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "04", "DDD", implant, List.of("RA", "RV"));

        String recordId = createBackfillRecord(admin, patientId, visit);
        Map<String, Object> form = baseProgramData(visit, 6);
        form.put("f316", 5000); // 软范围 100–3000（CL-01：超范围黄提示，确认后可保存并记确认人）
        Map<String, Object> dataMeta =
                Map.of(
                        "f316",
                        Map.of("softRangeConfirmedBy", "演示护士", "softRangeConfirmedAt", "示例"));
        Map<?, ?> submitted = submit(admin, recordId, form, dataMeta);
        assertEquals(0, code(submitted), "软范围不拦截提交（A-FLD-003 提示但可保存）：" + submitted);
        List<Map<?, ?>> warnings = (List<Map<?, ?>>) data(submitted).get("warnings");
        assertTrue(
                warnings.stream()
                        .anyMatch(
                                issue ->
                                        "f316".equals(issue.get("key"))
                                                && String.valueOf(issue.get("message"))
                                                        .contains("100–3000")),
                "提交结果带 f316 软范围警告（前后端同文案「取值应在 100–3000 之间」）：" + warnings);

        Map<?, ?> detail = data(get("/api/hospital/followup/records/" + recordId, admin));
        assertEquals(
                5000,
                ((Number) ((Map<?, ?>) detail.get("data")).get("f316")).intValue(),
                "确认后 5000 实际落库");
        Map<?, ?> meta = (Map<?, ?>) detail.get("dataMeta");
        assertNotNull(meta, "data_meta 随提交落库");
        assertEquals(
                "演示护士",
                ((Map<?, ?>) meta.get("f316")).get("softRangeConfirmedBy"),
                "确认人按 tech-plan §6 记 data_meta（form.md §2 确认流）");
    }

    @Test
    void symptomSchemaExposesExclusiveAndNeedsDetailFlagsAndRejectsUnknownOptionCodes() {
        String admin = adminToken();

        // 契约位：schema 下发 c_symptom 的「无」exclusive、「其他」needsDetail（前端互斥 / 补充说明交互的数据源，Q04）
        Map<?, ?> schema = data(get("/api/hospital/form/forms/" + C_FORM, admin));
        Map<?, ?> symptom = fieldOf(schema, "f307");
        Map<?, ?> noneOption = optionOf(symptom, 0);
        assertEquals(true, noneOption.get("exclusive"), "症状「无」互斥（C-FLD-004 / Q04）");
        Map<?, ?> otherOption = optionOf(symptom, 8);
        assertEquals(true, otherOption.get("needsDetail"), "症状「其他」须填说明（Q04）");
        Map<?, ?> icdInappropriate = fieldOf(schema, "f330");
        assertEquals(true, optionOf(icdInappropriate, 0).get("exclusive"), "ICD 不恰当治疗「无」互斥（Q04）");
        assertEquals(true, optionOf(icdInappropriate, 6).get("needsDetail"), "「其他」须填说明（Q04）");

        // 白名单：多选编码不在选项集内 → 写入闸门 40001（tech-plan §6 值编码）
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "05", "DDD", implant, List.of("RA", "RV"));
        String recordId = createBackfillRecord(admin, patientId, visit);
        Map<String, Object> form = baseProgramData(visit, 6);
        form.put("f307", List.of(0, 99));
        Map<?, ?> rejected = submit(admin, recordId, form, null);
        assertEquals(40001, code(rejected), "未知选项编码必须被写入闸门拒绝：" + rejected);
    }

    // ---------------------------------------------------------------- C-ACC-20 /
    // C-ACC-21（任务录入与重复提示）

    @Test
    void taskEntryAutoFillsVisitNoSequenceDeviceTimepointAndDoctor() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        long patientId = registerPatient(admin, "06", "DDD", implant, List.of("RA", "RV"));

        // V86 预置映射入组 → 当期恰一条「术后6个月」PENDING 任务（与 CSwitchAcceptanceIT SW-1 同链路）
        Map<String, Object> task = solePendingTaskOf(patientId);
        assertEquals("术后6个月", task.get("node_name"), "随访时间点来自任务子计划节点");
        long taskId = ((Number) task.get("id")).longValue();

        Map<?, ?> me = data(get("/api/hospital/auth/me", admin));
        Map<?, ?> started =
                data(post("/api/hospital/followup/tasks/" + taskId + "/start", admin, Map.of()));
        String recordId = String.valueOf(started.get("recordId"));
        assertTrue(Boolean.TRUE.equals(started.get("recordCreated")), "首次开始录入创建草稿");

        Map<?, ?> detail = detailOf(admin, recordId);
        assertEquals(C_FORM, detail.get("formCode"), "任务录入表单 = 计划节点表单（V91 预置 C_PROGRAM_FU）");
        Map<?, ?> context = (Map<?, ?>) detail.get("context");
        assertNotNull(context, "任务关联记录带任务上下文");
        assertEquals("术后6个月", context.get("nodeName"), "随访时间点（任务节点）自动带入（C-FU-008）");
        assertEquals(patientId, Long.parseLong(String.valueOf(context.get("patientId"))));

        // C-FU-008 自动带入：随访医生默认当前用户（entryDefaults）
        Map<?, ?> entryDefaults = (Map<?, ?>) detail.get("entryDefaults");
        assertNotNull(entryDefaults, "entryDefaults 随详情下发（E21-T11）");
        assertEquals(
                String.valueOf(me.get("displayName")),
                String.valueOf(entryDefaults.get("doctorName")),
                "随访医生默认当前登录用户");
        assertEquals(
                String.valueOf(me.get("userId")),
                String.valueOf(entryDefaults.get("doctorId")),
                "随访医生缺省 = 当前用户 id");

        // 随访编号（C-PAT-003 前缀 105，OQ-131①）与随访序号（C-FU-018 派生预演 = 1）
        assertTrue(
                String.valueOf(detail.get("visitNo")).matches("105-\\d{4}-\\d{3}"),
                "随访编号按「前缀-建档年份-年内 3 位流水号」生成：" + detail.get("visitNo"));
        assertEquals(1, detail.get("sequenceNo"), "首条记录随访序号 = 1");

        // 当前器械（C-PAT-007）：任务录入记录 visit_date 为空 → 回落在用器械
        Map<?, ?> device = (Map<?, ?>) detail.get("device");
        assertNotNull(device, "任务录入带当前器械");
        assertEquals("DDD", device.get("type"));
        assertEquals(true, device.get("current"), "任务录入（无随访日期）回落当前器械");

        // 提交闭环：任务完成（一次随访一条记录，f304 由任务节点带入口径选「术后6个月」= 4）
        Map<String, Object> form = baseProgramData(LocalDate.now(), 4);
        Map<?, ?> submitted = submit(admin, recordId, form, null);
        assertEquals(0, code(submitted), "任务记录提交失败：" + submitted);
        assertEquals("COMPLETED", data(submitted).get("taskStatus"), "提交后任务完成");
    }

    @Test
    void sameVisitDateDuplicateIsPromptedAndConfirmedSaveProceeds() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "07", "DDD", implant, List.of("RA", "RV"));

        // 第一条：同日已提交记录
        String first = createBackfillRecord(admin, patientId, visit);
        assertEquals(0, code(submit(admin, first, baseProgramData(visit, 2), null)), "首条提交");

        // 重复提示（C-FU-024）：同日再建 → check-duplicate 报 duplicate 并给出冲突行
        Map<?, ?> check =
                data(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/records/check-duplicate?visitDate="
                                        + visit,
                                admin));
        assertEquals(true, check.get("duplicate"), "同一患者同一随访日期已有记录 → duplicate = true");
        List<Map<?, ?>> conflicts = (List<Map<?, ?>>) check.get("records");
        assertEquals(1, conflicts.size(), "冲突行含已提交那条");
        assertEquals(first, String.valueOf(conflicts.get(0).get("recordId")));

        // 「确认后可保存」：第二条照常创建并提交成功（提示不阻止）
        String second = createBackfillRecord(admin, patientId, visit);
        Map<?, ?> submitted = submit(admin, second, baseProgramData(visit, 2), null);
        assertEquals(0, code(submitted), "确认后同日第二条可保存（C-ACC-21）：" + submitted);

        // excludeRecordId 排除自身（修改 / 重新提交场景）：排除第二条后仍命中第一条
        Map<?, ?> checkExcluded =
                data(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/records/check-duplicate?visitDate="
                                        + visit
                                        + "&excludeRecordId="
                                        + second,
                                admin));
        assertEquals(true, checkExcluded.get("duplicate"), "排除自身后另一条同日记录仍在提示范围内");
    }

    // ---------------------------------------------------------------- C-ACC-24 / C-FU-025
    // 远程监测记录（E21-T12）

    /** 远程监测记录（E21-T12 / C-FU-025）：同一表单人工录入、source=REMOTE、随访方式缺省「远程监测」、时间轴单独成节点与程控记录区分。 */
    @Test
    void remoteRecordUsesSameFormAndSplitsFromProgrammingRecords() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        LocalDate visit = implant.plusDays(40);
        long patientId = registerPatient(admin, "08", "DDD", implant, List.of("RA", "RV"));
        // 同日先落一条补录（门诊程控来源的纸质记录），用于时间轴区分与重复提示
        String backfill = createBackfillRecord(admin, patientId, visit);

        // 开关开启（@BeforeAll）：REMOTE 当天 / 过去日期可建，同一入口同一表单
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of(
                                "source",
                                "REMOTE",
                                "visitDate",
                                visit.toString(),
                                "formCode",
                                C_FORM));
        assertEquals(0, code(created), "远程监测记录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));

        Map<?, ?> detail = data(get("/api/hospital/followup/records/" + recordId, admin));
        assertEquals("REMOTE", detail.get("source"), "source = REMOTE，与门诊程控记录区分（C-FU-025）");
        assertEquals(visit.toString(), detail.get("visitDate"));
        assertNull(detail.get("context"), "不创建任务、无任务上下文");
        Map<?, ?> defaults = (Map<?, ?>) detail.get("entryDefaults");
        assertNotNull(defaults, "录入缺省随详情下发");
        assertEquals(
                1,
                ((Number) defaults.get("visitMethod")).intValue(),
                "REMOTE 记录随访方式缺省「远程监测」（c_visit_method 选项码 1）");

        // 同一表单提交（f303 随访日期 / f304 随访时间点 / f316 心室阻抗，与补录同口径）
        Map<?, ?> submitted = submit(admin, recordId, baseProgramData(visit, 2), null);
        assertEquals(0, code(submitted), "同一表单人工录入并提交成功：" + submitted);

        // 时间轴：REMOTE 节点「远程监测」与 BACKFILL 节点分开（远程监测与程控记录区分，C-ACC-24）
        Map<?, ?> timeline =
                data(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/followup-timeline",
                                admin));
        List<Map<?, ?>> items = (List<Map<?, ?>>) timeline.get("items");
        Map<?, ?> remoteNode =
                items.stream()
                        .filter(item -> "REMOTE".equals(item.get("kind")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(remoteNode, "时间轴含远程监测节点");
        assertEquals("远程监测", remoteNode.get("label"), "节点名「远程监测」");
        assertEquals(recordId, String.valueOf(remoteNode.get("recordId")));
        assertTrue(
                items.stream()
                        .anyMatch(
                                item ->
                                        "BACKFILL".equals(item.get("kind"))
                                                && String.valueOf(item.get("recordId"))
                                                        .equals(backfill)),
                "同日补录（程控来源）仍是 BACKFILL 节点，两者区分展示");
    }

    // ---------------------------------------------------------------- 辅助（CSwitchAcceptanceIT 同款口径）

    /** C 登记并返回患者 id（implant 开关开启时同建首条植入记录；leads 为腔室列表 RA / RV / LV）。 */
    private long registerPatient(
            String token,
            String suffix,
            String deviceType,
            LocalDate implantDate,
            List<String> chambers) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "端木示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId());
        body.put("legacyNo", LEGACY + "-" + suffix);
        body.put("implant", implantPayload("FIRST", implantDate, deviceType, chambers));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    /** 植入记录载荷（按腔室列表生成电极：RA_APPENDAGE / RV_APEX / CS）。 */
    private static Map<String, Object> implantPayload(
            String type, LocalDate date, String deviceType, List<String> chambers) {
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
        java.util.ArrayList<Map<String, Object>> leads = new java.util.ArrayList<>();
        for (String chamber : chambers) {
            leads.add(
                    Map.of(
                            "chamber",
                            chamber,
                            "position",
                            "LV".equals(chamber)
                                    ? "CS"
                                    : ("RA".equals(chamber) ? "RA_APPENDAGE" : "RV_APEX")));
        }
        implant.put("leads", leads);
        return implant;
    }

    /** 补录创建一条 C_PROGRAM_FU 草稿（visitDate 须早于今天且晚于植入日）。 */
    private String createBackfillRecord(String token, long patientId, LocalDate visitDate) {
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
        return String.valueOf(data(created).get("recordId"));
    }

    /** 最小可提交数据（f303 随访日期必填 + f304 随访时间点必填 + 在范围内的 f316 心室阻抗）。 */
    private static Map<String, Object> baseProgramData(LocalDate visitDate, int timepoint) {
        Map<String, Object> form = new HashMap<>();
        form.put("f303", visitDate.toString());
        form.put("f304", timepoint);
        form.put("f316", 800);
        return form;
    }

    private Map<?, ?> submit(
            String token, String recordId, Map<String, Object> data, Map<String, Object> dataMeta) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", 0);
        body.put("data", data);
        if (dataMeta != null) {
            body.put("dataMeta", dataMeta);
        }
        return post("/api/hospital/followup/records/" + recordId + "/submit", token, body);
    }

    private Map<?, ?> detailOf(String token, String recordId) {
        return data(get("/api/hospital/followup/records/" + recordId, token));
    }

    private static Map<?, ?> fieldOf(Map<?, ?> schema, String key) {
        for (Object category : (List<?>) schema.get("categories")) {
            for (Object field : (List<?>) ((Map<?, ?>) category).get("fields")) {
                if (key.equals(((Map<?, ?>) field).get("key"))) {
                    return (Map<?, ?>) field;
                }
            }
        }
        throw new IllegalStateException("schema 中找不到字段：" + key);
    }

    private static Map<?, ?> optionOf(Map<?, ?> field, int optionCode) {
        for (Object option : (List<?>) field.get("optionSet")) {
            if (Integer.valueOf(optionCode)
                    .equals(((Number) ((Map<?, ?>) option).get("code")).intValue())) {
                return (Map<?, ?>) option;
            }
        }
        throw new IllegalStateException("字段选项中找不到编码：" + optionCode);
    }

    /** 该患者唯一一条 PENDING 任务行（fu_task × fu_plan_node，断言恰一条）。 */
    private Map<String, Object> solePendingTaskOf(long patientId) {
        List<Map<String, Object>> tasks =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.query(
                                        "SELECT t.*, n.name AS node_name FROM fu_task t"
                                                + " JOIN fu_plan_node n ON n.id = t.node_id"
                                                + " WHERE t.patient_id = ? AND t.status = 'PENDING'",
                                        CProgramEntryApiIT::resultMap,
                                        patientId));
        assertEquals(1, tasks.size(), "入组即建当期任务恰一条：" + tasks);
        return tasks.get(0);
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
