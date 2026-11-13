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
 * E21-T26（测试，C 下次随访时间与补录用例；tasks.csv 验收「窗口算例通过」，关联需求 C-FU-005 / C-FU-014）的执行方套件： 把评审用例集 {@code
 * docs/reviews/hospital-c-acceptance-review.md} §3 指派给本任务的 C-ACC-16（窗口算例）与 C-ACC-26 /
 * 27（补录算例）放到**随包产品配置链**上端到端执行—— runbook `docs/ops/hospital-c-plan-preset.md` §3 激活路径：发布 followup
 * V91 两套预置（C_PACER_FU_PLAN / C_ICD_FU_PLAN，随包 UNPUBLISHED 草稿，OQ-128②）→ 保存植入记录按 V86 预置映射自动入组（不 PUT
 * 改映射）→ 预置节点（Q03 节点表：术后 72 小时〔前 3 后 4〕/ 1 / 3 / 6 个月 / 1 年、常规 12 个月 ±30、ERI 期 1 个月
 * ±7）真实参与排期与建议。与行为面用例的分工：{@link DoctorNextDateIT}（E21-T08，自建滚动计划）与 {@link
 * BackfillTimepointIT}（E21-T14，自建计划 + 逐边界）锁**行为规则**，本类锁**产品配置可用性**——预置常规随访节点（±30
 * 天）的窗口算例、七档建议与补录链路在预置配置上逐档执行。 C-ACC-25（序号重排）机器面由 {@link SequenceNumberIT} 承载（留痕见
 * `docs/testing/hospital-c-next-visit-backfill-acceptance.md`）。
 *
 * <p>用例执行（方法名前缀对齐）：
 *
 * <ol>
 *   <li>C-ACC-16 窗口算例（验收原文时钟相对化，与 {@link DoctorNextDateIT} 同法：规格例 2027-01-15 ±30 天 → 窗口 2026-12-16
 *       至 2027-02-14、窗口开始日建任务；此处医生日期 = 今天 + 2 个月，算例同构）：普通起搏器患者（预置计划、术后节点全过 → SKIP）补录本次记录并填写下次随访时间 →
 *       登记 doctor_next_date；窗口开始日前一天跑作业不建任务（缓存保持计划默认）；窗口开始日当天建「常规随访」任务：计划日期 = 医生指定日期（不是计划默认的上次随访 +
 *       12 个月）、窗口按预置节点宽度 ±30 天围绕展开、开启日 = 窗口开始日；消费后清理登记（只影响下一次）、滚动从医生日期起继续（下次 = 医生日 + 12 个月）；
 *   <li>C-ACC-16 补录条款：补录历史记录填写**过去**的下次随访时间 → 只保存在记录里（不登记 doctor_next_date、不生成任务）——后端语义；「早于今天 /
 *       本次随访日期不能保存」的前端闸门由 next-visit.spec / logic.spec 承载（留痕文档映射）；
 *   <li>C-ACC-26 七档逐档（0–7 术后72小时/出院前、25–45 术后1个月、75–110 术后3个月、150–215 术后6个月、330–400
 *       术后1年、其后每6-12个月；边界日由 {@link com.crdp.followup.record.app.TimepointSuggestionServiceTest} 与
 *       {@link BackfillTimepointIT} 锁定）：每档取代表日在预置链上经建议接口核对选项编码与名称；距植入 31 天 → 「术后1个月」（规格验收原文）；ERI
 *       档走真实路径（提交 ERI 状态记录翻入组模式）优先于天数；建议只是种进草稿的缺省，**可改选**（提交改选值落库）；
 *   <li>C-ACC-27 补录链路：不需任务、不受窗口限制（空档日期直接新建过去日期记录，source =
 *       BACKFILL、无任务关联）；「继续补录下一次」同一创建接口——保留患者、器械按新随访日期匹配、 测量值清空；日期边界（不早于植入日期 / 不晚于今天 →
 *       40001）。「补录日期落在未完成任务窗口内 → 自动完成该任务」条款与现行实现（补录提交无任务不联动完成，followup.md §3） 冲突，未在本类断言，缺口与暂行口径见
 *       OQ-146 与留痕文档。
 * </ol>
 *
 * <p>程控记录直接走随包 C_PROGRAM_FU 表单（V90，demoa 常驻，只读引用不删改种子）。门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose
 * 注入端口）。 自建自清（legacy_no 前缀 IT-E21T26-）；预置发布态收尾还原为 V91 草稿态（对 A / B 与其他 IT 惰性，OQ-128②）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CNextVisitBackfillAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T26";
    private static final String PACER_CODE = "C_PACER_FU_PLAN";
    private static final String ICD_CODE = "C_ICD_FU_PLAN";

    /** 随包医院 C 随访程控表单（V90；f303 随访日期 / f304 随访时间点 / f323 ERI 状态 / f333 下次随访时间）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.crdp.followup.task.app.FollowupTaskCreationService creationService;

    // ---------------------------------------------------------------- 激活（runbook §3 步骤 2/3/4）

    /** 发布两套预置（幂等）+ 开 implant 开关：V86 预置映射随后对保存的植入记录自动入组生效。 */
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
                () -> jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'"));
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
        // 预置节点 ownerType = DOCTOR：建任务写 TaskOpened 事件（payload 带患者 id），PENDING 事件残留会被后续
        // IT 的通知派发消费成 ntf 行、污染通知套件计数（CSwitchAcceptanceIT 同口径），随患者一并清理
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

    // ---------------------------------------------------------------- C-ACC-16 窗口算例（预置链，验收核心）

    @Test
    void cAcc16DoctorNextDateBecomesPlanDateWithPresetRoutineWindowAroundIt() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(14); // 术后节点（72 小时…1 年）窗口全过 → 入组即建 SKIP（Q14）
        LocalDate visit = today.minusDays(5); // 本次随访（补录过去日期）
        LocalDate doctor = today.plusMonths(2); // 医生指定下次随访时间（规格例 2027-01-15 的时钟相对化同构）
        long patientId = registerPatient(admin, "01", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);

        // 预置链前提：V86 映射自动入组普通计划、植入日期起算；历史患者无当期任务（Q14 只为今天之后开始的窗口创建）
        Map<String, Object> premise = enrollmentRow(enrollmentId);
        assertEquals(PACER_CODE, premise.get("plan_code"), "DDD → 普通起搏器计划（V91 预置）");
        assertEquals(java.sql.Date.valueOf(implant), premise.get("start_date"), "起算日 = 植入日期");
        assertEquals("NORMAL", premise.get("mode"));
        assertEquals(0, tasksOf(patientId).size(), "术后窗口全过 SKIP、常规首次抑制（OQ-139②）→ 无任务");

        // 补录本次记录并填写下次随访时间（C-FU-005 / SW-10 登记路径）：doctor_next_date = 医生日期
        submitProgramRecord(admin, patientId, visit, Map.of("f333", doctor.toString()), 6);
        Map<String, Object> registered = enrollmentRow(enrollmentId);
        assertEquals(
                java.sql.Date.valueOf(doctor),
                registered.get("doctor_next_date"),
                "填写下次随访时间 → 登记为下一次任务的计划日期");
        assertEquals(java.sql.Date.valueOf(visit), registered.get("last_visit_date"), "滚动基准快照推进");

        // 窗口开始日（医生日期 − 预置节点窗口前 30 天）前一天：不建任务、登记未消费、缓存保持计划默认
        LocalDate defaultPlan = visit.plusMonths(12); // 计划默认：上次随访 + 12 个月（预置常规随访节点）
        creationService.runForDate(doctor.minusDays(31));
        assertEquals(0, tasksOf(patientId).size(), "医生窗口未开启不建任务（不按计划默认日期提前建）");
        Map<String, Object> beforeOpen = enrollmentRow(enrollmentId);
        assertEquals(java.sql.Date.valueOf(doctor), beforeOpen.get("doctor_next_date"), "未消费不清理");
        assertEquals(
                java.sql.Date.valueOf(defaultPlan),
                beforeOpen.get("next_plan_date"),
                "缓存保持计划默认（读取侧 COALESCE 医生日期优先，OQ-141）");

        // 窗口开始日当天（规格例 2026-12-16 同构）：创建「常规随访」任务——计划日期 = 医生指定日期、窗口 ±30 天围绕展开、开启日 = 窗口开始日
        creationService.runForDate(doctor.minusDays(30));
        List<Map<String, Object>> created = tasksOf(patientId);
        assertEquals(1, created.size(), "窗口开始日当天创建（而不是按计划默认日期）");
        Map<String, Object> task = created.get(0);
        assertEquals("常规随访", nodeName(task), "预置常规随访节点（Q03 节点表：LAST_VISIT + 12 个月 ±30）");
        assertEquals(java.sql.Date.valueOf(doctor), task.get("plan_date"), "计划日期 = 医生指定日期");
        assertTrue(
                !java.sql.Date.valueOf(defaultPlan).equals(task.get("plan_date")),
                "不是计划默认的 " + defaultPlan);
        assertEquals(
                java.sql.Date.valueOf(doctor.minusDays(30)),
                task.get("window_start"),
                "窗口前 30 天（规格例 2026-12-16）");
        assertEquals(
                java.sql.Date.valueOf(doctor.plusDays(30)),
                task.get("window_end"),
                "窗口后 30 天（规格例 2027-02-14）");
        assertEquals(
                java.sql.Date.valueOf(doctor.minusDays(30)), task.get("open_date"), "开启日 = 窗口开始日");
        assertEquals("PENDING", task.get("status"));
        assertEquals("NORMAL", task.get("create_type"));
        assertEquals(1, ((Number) task.get("occurrence")).intValue());
        assertNull(enrollmentRow(enrollmentId).get("doctor_next_date"), "消费后清理已用值（只影响下一次，OQ-138③）");

        // 重放幂等；滚动循环从医生指定日期起继续（下次 = 医生日 + 12 个月、开启 − 30 天）
        creationService.runForDate(doctor.minusDays(29));
        assertEquals(1, tasksOf(patientId).size(), "重复执行不重复创建（uk 幂等）");
        Map<String, Object> cache = enrollmentRow(enrollmentId);
        assertEquals(
                java.sql.Date.valueOf(doctor.plusMonths(12)),
                cache.get("next_plan_date"),
                "滚动从医生指定日期起继续");
        assertEquals(
                java.sql.Date.valueOf(doctor.plusMonths(12).minusDays(30)),
                cache.get("next_open_date"));
    }

    // ---------------------------------------------------------------- C-ACC-16 补录条款（过去日期只保存）

    @Test
    void cAcc16PastDoctorDateOnBackfillOnlySavedWithoutTask() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(14);
        LocalDate visit = today.minusDays(10);
        LocalDate pastNext = today.minusDays(3); // 早于今天的下次随访时间（补录历史记录场景）
        long patientId = registerPatient(admin, "02", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);

        String recordId =
                submitProgramRecord(
                        admin, patientId, visit, Map.of("f333", pastNext.toString()), 6);

        // 只保存：记录里保留下次随访时间（已过去），不登记 doctor_next_date、无从生成任务
        assertEquals(
                pastNext.toString(),
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT JSON_UNQUOTE(JSON_EXTRACT(data, '$.f333'))"
                                                + " FROM fu_record WHERE id = ?",
                                        String.class,
                                        Long.parseLong(recordId))),
                "补录历史记录中的下次随访时间只保存在记录里");
        assertNull(enrollmentRow(enrollmentId).get("doctor_next_date"), "过去日期不登记（不再生成任务，C 规格 §5）");
        assertEquals(0, tasksOf(patientId).size(), "无登记即无医生指定任务");
    }

    // ---------------------------------------------------------------- C-ACC-26 七档逐档（预置链）

    @Test
    void cAcc26SuggestsEveryTierOnPresetChain() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusYears(2);
        long patientId = registerPatient(admin, "03", "DDD", implant);
        assertEquals(PACER_CODE, enrollmentRow(enrollmentIdOf(patientId)).get("plan_code"));

        // 七档规则逐档核对（评审 C-ACC-26；边界日 0/7/8/45/46/110/215/216/330/400/401 由
        // TimepointSuggestionServiceTest + BackfillTimepointIT 锁定，此处每档取代表日）
        assertTier(admin, patientId, implant, 3, 1, "术后72小时/出院前");
        assertTier(admin, patientId, implant, 31, 2, "术后1个月"); // 规格验收原文：距植入 31 天
        assertTier(admin, patientId, implant, 90, 3, "术后3个月");
        assertTier(admin, patientId, implant, 180, 4, "术后6个月");
        assertTier(admin, patientId, implant, 365, 5, "术后1年");
        assertTier(admin, patientId, implant, 500, 6, "每6-12个月");
    }

    @Test
    void cAcc26EriTierSuggestedViaRealEriMarkingPath() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusYears(2);
        long patientId = registerPatient(admin, "04", "DDD", implant);
        long enrollmentId = enrollmentIdOf(patientId);

        // 真实路径翻 ERI（SW-7：提交 ERI 状态 = 接近ERI → mode = ERI、eri_since = 该次随访日期）
        LocalDate eriVisit = LocalDate.now().minusDays(40);
        submitProgramRecord(admin, patientId, eriVisit, Map.of("f323", 1), 7);
        assertEquals("ERI", enrollmentRow(enrollmentId).get("mode"), "先进入 ERI 期（前置条件）");

        // 器械接近 ERI → 优先建议「ERI期随访」（天数 700+ 本应落「每6-12个月」，状态口径优先）
        Map<?, ?> eri = suggest(admin, patientId, LocalDate.now().minusDays(30));
        assertEquals(true, eri.get("eriFollowup"));
        assertEquals(7, ((Number) eri.get("suggestedTimepoint")).intValue(), "第七档：ERI期随访");
        assertEquals("ERI期随访", eri.get("suggestedLabel"));
    }

    @Test
    void cAcc26SuggestionIsDefaultAndChangeableOnSubmit() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusYears(2);
        long patientId = registerPatient(admin, "05", "DDD", implant);

        // 创建即种入建议（31 天 → 术后1个月，选项 2）
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                implant.plusDays(31).toString(),
                                "formCode",
                                C_FORM));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        assertEquals(
                2, ((Number) recordData(recordId).get("f304")).intValue(), "建议时间点种进草稿（C-FU-014）");

        // 可改选：提交改选「术后1年」（5）→ 落库为改选值
        Map<String, Object> dataBody = new HashMap<>();
        dataBody.put("f303", implant.plusDays(31).toString());
        dataBody.put("f304", 5);
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        admin,
                        Map.of("baseVersion", 0, "data", dataBody));
        assertEquals(0, code(submitted), "改选后提交应成功（建议值可改选）：" + submitted);
        assertEquals(5, ((Number) recordData(recordId).get("f304")).intValue(), "提交落库为改选值（建议只是缺省）");
    }

    // ---------------------------------------------------------------- C-ACC-27 补录链路（预置链）

    @Test
    void cAcc27BackfillWithoutTaskContinuousEntryAndBounds() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(4);
        long patientId = registerPatient(admin, "06", "DDD", implant);
        long implantId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM pt_implant WHERE patient_id = ?",
                                        Long.class,
                                        patientId));
        assertEquals(0, tasksOf(patientId).size(), "历史患者无当期任务（Q14）——补录不需任务的前提");

        // 不需任务、不受窗口限制：空档日期（植入后 50 天，不落任何节点窗口）直接新建过去日期记录
        Map<?, ?> first =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                implant.plusDays(50).toString(),
                                "formCode",
                                C_FORM));
        assertEquals(0, code(first), "空档日期补录创建失败：" + first);
        Map<String, Object> firstRow = recordRow(String.valueOf(data(first).get("recordId")));
        assertEquals("BACKFILL", String.valueOf(firstRow.get("source")), "记录来源标为补录（C-FU-013）");
        assertNull(firstRow.get("task_id"), "不关联任务");
        assertEquals(
                implantId,
                ((Number) firstRow.get("implant_id")).longValue(),
                "自动关联当时器械（创建即解析器械维度）");

        // 「继续补录下一次」（C-FU-015 后端口径 = 同一创建接口）：保留患者与器械、测量值清空（新草稿只种建议）
        Map<?, ?> second =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                implant.plusDays(100).toString(),
                                "formCode",
                                C_FORM));
        assertEquals(0, code(second), "继续补录下一次失败：" + second);
        Map<String, Object> secondRow = recordRow(String.valueOf(data(second).get("recordId")));
        assertEquals(patientId, ((Number) secondRow.get("patient_id")).longValue(), "保留患者");
        assertEquals(
                implantId,
                ((Number) secondRow.get("implant_id")).longValue(),
                "器械按新随访日期重新匹配（仍在用 → 同一器械）");
        Map<String, Object> secondData = readDataJson(secondRow);
        assertEquals(3, ((Number) secondData.get("f304")).intValue(), "100 天 → 建议术后3个月种入新草稿");
        assertNull(secondData.get("f311"), "测量值清空（新记录不带上一次的值）");

        // 日期边界：「不早于（最早）植入日期、不晚于今天」（C 规格 §5 补录流程）
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/records",
                                admin,
                                Map.of(
                                        "source",
                                        "BACKFILL",
                                        "visitDate",
                                        implant.minusDays(1).toString(),
                                        "formCode",
                                        C_FORM))),
                "补录随访日期不能早于植入日期");
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/records",
                                admin,
                                Map.of(
                                        "source",
                                        "BACKFILL",
                                        "visitDate",
                                        LocalDate.now().toString(),
                                        "formCode",
                                        C_FORM))),
                "补录随访日期必须是过去的日期（不晚于今天）");
    }

    // ---------------------------------------------------------------- 辅助（CSwitchAcceptanceIT 同款口径）

    /** 七档代表日断言：选项编码 + 名称 + 距植入天数。 */
    private void assertTier(
            String token, long patientId, LocalDate implant, int days, int code, String label) {
        Map<?, ?> view = suggest(token, patientId, implant.plusDays(days));
        assertEquals(
                days, ((Number) view.get("daysSinceImplant")).intValue(), "距植入 " + days + " 天");
        assertEquals(
                code, ((Number) view.get("suggestedTimepoint")).intValue(), days + " 天 → " + label);
        assertEquals(label, view.get("suggestedLabel"));
    }

    /** C 登记并返回患者 id（implant 开关开启时同建首条植入记录，V86 映射自动入组）。 */
    private long registerPatient(
            String token, String suffix, String deviceType, LocalDate implantDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "尉迟示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1958-02-11");
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
        implant.put("model", "示例型号 S-02");
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

    /**
     * 补录一条随包 C_PROGRAM_FU 程控记录并提交（visitDate 须早于今天且晚于植入日）； extra 为 f323 / f333，timepoint 为
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

    private Map<?, ?> suggest(String token, long patientId, LocalDate visitDate) {
        Map<?, ?> response =
                rest.exchange(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/records/suggest-timepoint?visitDate="
                                        + visitDate,
                                HttpMethod.GET,
                                new HttpEntity<>(bearer(token)),
                                Map.class)
                        .getBody();
        assertEquals(0, code(response), "建议接口失败：" + response);
        return data(response);
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
        assertNotNull(row, "保存植入记录按 V86 预置映射自动入组（runbook §3 激活路径）");
        return ((Number) row.get("id")).longValue();
    }

    private Map<String, Object> enrollmentRow(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT * FROM fu_enrollment WHERE id = ?",
                                CNextVisitBackfillAcceptanceIT::resultMap,
                                enrollmentId));
    }

    private List<Map<String, Object>> tasksOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.query(
                                "SELECT t.*, n.name AS node_name FROM fu_task t"
                                        + " JOIN fu_plan_node n ON n.id = t.node_id"
                                        + " WHERE t.patient_id = ? ORDER BY t.id",
                                CNextVisitBackfillAcceptanceIT::resultMap,
                                patientId));
    }

    private static String nodeName(Map<String, Object> task) {
        return String.valueOf(task.get("node_name"));
    }

    private Map<String, Object> recordRow(String recordId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT * FROM fu_record WHERE id = ?",
                                CNextVisitBackfillAcceptanceIT::resultMap,
                                Long.parseLong(recordId)));
    }

    private Map<String, Object> recordData(String recordId) {
        return readDataJson(recordRow(recordId));
    }

    /** fu_record.data（JSON 列，JdbcTemplate 取回 String）解析为 Map。 */
    private Map<String, Object> readDataJson(Map<String, Object> row) {
        Object raw = row.get("data");
        try {
            return JSON.readValue(
                    raw instanceof String text ? text : String.valueOf(raw),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("fu_record.data 解析失败：" + raw, invalid);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
