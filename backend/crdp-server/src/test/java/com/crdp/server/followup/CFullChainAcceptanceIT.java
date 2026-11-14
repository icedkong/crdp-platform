package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E21-T29（测试，C 集成测试 W43；验收「C 全链路用例通过」）：评审用例集 {@code docs/reviews/hospital-c-acceptance-review.md}
 * §3.8 C-ACC-45 的机器面承载——「C 全链路（登记 → 植入 → 入组 → 任务 → 录入标红 → 报告单 → 补录 → 统计）用例通过 + A、B 租户不受影响 + 列表与看板 2
 * 秒内打开（建议值，C-NFR-001）」。
 *
 * <p>与分项 IT 的分工（不重复覆盖）：单步行为由 CSwitchAcceptanceIT（入组 / 切换）、CProgramEntryApiIT（录入自动带入 / 校验）、
 * CDigitizeTranscribeAcceptanceIT（电子化三态 /
 * 转录）、CReportImplantLocationIT（植入位置）、CDashboardCardsIT（三卡口径）、
 * PatientDeviceCardIT（概览器械卡）分别锁定。本套件把八步接成<b>一名患者的连续旅程</b>，只消费链路自产物（登记 / 植入 / 录入 / 报告单全走 HTTP 正口，
 * 入组由登记事务内按 V86 预置映射完成，标红由提交重算落库，不服务层造数、不手工插事件），并补齐分项套件不覆盖的三块：
 *
 * <ol>
 *   <li>旅程（@Order 1–4）：历史患者登记（implant 载荷，过去植入日期，C-PAT-008）→ 按器械类型自动入组 C_PACER_FU_PLAN（C-FU-003）→
 *       入组即建当期「术后6个月」任务 → 从任务进入录入（C-ACC-20 自动带入）提交 ON_TIME → 第二条记录心室阈值 0.7 → 1.2 V 升高 71% 命中
 *       rv_threshold_rise（C-ACC-19 数字算例，V82 预置规则）→ 纸质随访表报告单待录入（pendingScanCount）→ 转录补录后已电子化（CL-05 /
 *       OQ-131②：位置无随访记录 → 转录新建补录记录）→ 首页统计反映链路数据（四张 C 卡 + 指标集 style=C）；
 *   <li>2 秒建议值（C-NFR-001「列表与看板在常规网络下 2 秒内打开」）：患者列表 / 任务列表 / 看板布局与三张卡的服务端往返计时 （本机
 *       MySQL、无真实网络耗时，建议值口径）；
 *   <li>双租户回归与开关（C-ACC-45 前半句）：C 形态只在 demoa，demob（B，开关全关）布局无 C 卡、C 卡 40301、kpi 保持 A 形态； demoa 三开关关闭
 *       → C 入口从布局隐藏、接口 40301、C 登记带植入载荷 40001（C-PAT-001 反向），同一开关态下 A 口径登记照常并 自动入组
 *       A_ANNUAL_FU_PLAN（Q35 不受 C 开关影响，ImplantRecordIT / BRegressionAUnaffectedIT 同口径）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T29-，@BeforeAll
 * / @AfterAll 各清一遍保证可重跑）；预置发布态、三个开关与 C 预置报告类型收尾还原随包形态（V91 草稿 / V100 关 / V94 DISABLED，对 A / B 与其他 IT
 * 惰性，OQ-128② / OQ-142①）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CFullChainAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String LEGACY = "IT-E21T29";
    private static final String PACER_CODE = "C_PACER_FU_PLAN";
    private static final String A_PLAN_CODE = "A_ANNUAL_FU_PLAN";

    /** 随包医院 C 随访程控表单（V90；f303 随访日期 / f304 随访时间点 / f313 心室起搏阈值 / f316 心室阻抗）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    /** C-NFR-001 建议值：列表与看板 2 秒内打开（服务端往返口径，毫秒）。 */
    private static final long SUGGESTED_MS = 2000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    /** 旅程患者（@Order(1) 登记，后续步骤共用）。 */
    private long patientId;

    private String taskRecordId;

    private String redRecordId;

    private long paperReportId;

    // ---------------------------------------------------------------- 激活（runbook §3：开关 + 预置发布）

    /**
     * 医院 C 形态（demoa 模拟）：implant / eri_cycle / scan_progress 开 + C 预置报告类型启用 + 发布
     * C_PACER_FU_PLAN（幂等）。
     */
    @BeforeAll
    void activateCHospitalShape() {
        recycleLeftovers();
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code IN"
                                    + " ('implant', 'eri_cycle', 'scan_progress')");
                    jdbc.update(
                            "UPDATE rpt_type SET status = 'ENABLED' WHERE code IN"
                                    + " ('C_PROGRAM', 'C_PAPER_FORM', 'C_CONSENT')");
                    // A 预置保持上线基线（V61 随包 auto_enroll=1；BRegressionAUnaffectedIT 同款自卫，双保险不改变随包值）
                    jdbc.update(
                            "UPDATE fu_plan SET auto_enroll = 1 WHERE code = ?"
                                    + " AND status = 'PUBLISHED'",
                            A_PLAN_CODE);
                });
        String admin = adminToken();
        long planId = presetPlanId(PACER_CODE);
        if (!"PUBLISHED".equals(presetPlanStatus(planId))) {
            assertEquals(
                    0,
                    code(post(BASE + "/plans/" + planId + "/publish", admin, Map.of())),
                    "发布 C 预置计划失败：" + PACER_CODE);
        }
        assertEquals("PUBLISHED", presetPlanStatus(planId), "发布后状态：" + PACER_CODE);
    }

    @AfterAll
    void cleanUp() {
        recycleLeftovers();
        runInTenant(
                "demoa",
                () -> {
                    // 预置发布态还原 V91 草稿（本类激活是测试动作，不留库影响 A / B 与其他 IT，OQ-128②）
                    jdbc.update(
                            "UPDATE fu_plan SET status = 'UNPUBLISHED', published_at = NULL,"
                                    + " published_by = NULL WHERE code = ?",
                            PACER_CODE);
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE plan_code = ? AND action ="
                                    + " 'PLAN_PUBLISH'",
                            PACER_CODE);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code IN"
                                    + " ('implant', 'eri_cycle', 'scan_progress')");
                    jdbc.update(
                            "UPDATE rpt_type SET status = 'DISABLED' WHERE code IN"
                                    + " ('C_PROGRAM', 'C_PAPER_FORM', 'C_CONSENT')");
                });
    }

    /** 本 IT 自建数据清理（患者按 legacy_no 前缀全链路：记录 / 任务 / 入组 / 植入 / 报告单 / 审计 / 事件；报告单审计按 object_id）。 */
    private void recycleLeftovers() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')"
                                    + " OR target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM"
                                    + " fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')"
                                    + " OR target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')"
                                    + " OR target IN (SELECT CONCAT('ENROLLMENT:', e.id) FROM"
                                    + " fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')"
                                    + " OR (event_type = 'PLAN_PUBLISH' AND target IN"
                                    + " (SELECT CONCAT('PLAN:', id) FROM fu_plan WHERE code = '"
                                    + PACER_CODE
                                    + "'))");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE object_type = 'REPORT' AND object_id IN"
                                    + " (SELECT id FROM rpt_report WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE fh.record_type = 'FU_RECORD' AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE event_type IN"
                                    + " ('TaskOpened', 'TASK_OVERDUE')"
                                    + " AND JSON_EXTRACT(payload, '$.patientId') IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE v FROM rpt_indicator_value v"
                                    + " JOIN rpt_report r ON r.id = v.report_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM rpt_report_link l"
                                    + " JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM rpt_report r"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fr FROM fu_record fr JOIN pt_patient p ON p.id = fr.patient_id"
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
                            "DELETE il FROM pt_implant_lead il JOIN pt_patient p"
                                    + " ON p.id = il.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM pt_lead l JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE i FROM pt_implant i JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE ps FROM pt_patient_summary ps JOIN pt_patient p"
                                    + " ON ps.patient_id = p.id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE sh FROM pt_status_history sh JOIN pt_patient p"
                                    + " ON sh.patient_id = p.id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    // ---------------------------------------------------------------- ①–④ 登记 → 植入 → 入组 → 任务 →
    // 任务录入提交

    /** 历史患者登记（过去植入日期）带首条植入记录：随访编号自动生成、V86 映射自动入组、当期任务、任务录入自动带入并 ON_TIME 完成。 */
    @Test
    @Order(1)
    void step1RegisterImplantEnrollTaskAndCompleteTaskEntryRecord() {
        String nurse = adminToken();
        LocalDate today = LocalDate.now();
        // 植入日 = 今天 − 6 个月 − 3 天：「术后6个月」计划日 = 今天 − 3（窗口 [今天−17, 今天+11] 当期开启 → 入组即建任务），
        // 且早于⑤标红记录的随访日（今天 − 2）——任务记录的对比时点 = 任务计划日期（visit_date 为空，CompareSourceService），
        // 只有计划日早于标红记录，「上一次」才会命中任务记录（C-ACC-19 的 0.7 V 在任务记录里）
        LocalDate implant = today.minusMonths(6).minusDays(3);

        // ① 登记 + ② 植入（同一请求携带首条植入记录，DDD + RA/RV 电极）
        Map<?, ?> created = post(BASE + "/patients", nurse, cRegistrationBody("01", implant));
        assertEquals(0, code(created), "C 登记失败：" + created);
        patientId = Long.parseLong(str(data(created).get("id")));
        assertTrue(
                String.valueOf(data(created).get("visitNo")).matches("105-\\d{4}-\\d{3}"),
                "随访编号按「前缀-建档年份-年内流水」自动生成（C-PAT-003，OQ-131①）：" + data(created).get("visitNo"));

        // 植入记录落库：一条在用 DDD、两根电极（C-ACC-05 口径的链路自证）
        Map<?, ?> implants = get(BASE + "/patients/" + patientId + "/implants", nurse);
        List<?> rows = (List<?>) data(implants).get("items");
        assertEquals(1, rows.size(), "登记携带首条植入记录");
        Map<?, ?> implantRow = (Map<?, ?>) rows.get(0);
        assertEquals("DDD", implantRow.get("deviceType"));
        assertEquals(true, implantRow.get("current"), "同一时间仅一条在用");
        assertEquals(2, ((List<?>) implantRow.get("leads")).size(), "RA / RV 两根电极");

        // ③ 入组：按起搏器类型映射自动入组（C-FU-003），起算 = 植入日期
        Map<String, Object> enrollment = enrollmentOf(patientId);
        assertNotNull(enrollment, "保存植入记录即自动入组");
        assertEquals(PACER_CODE, enrollment.get("plan_code"), "DDD → 普通起搏器计划（V86 预置映射）");
        assertEquals("ACTIVE", enrollment.get("status"));
        assertEquals("NORMAL", enrollment.get("mode"));
        assertEquals(java.sql.Date.valueOf(implant), enrollment.get("start_date"), "起算 = 植入日期");

        // ④ 任务：入组即建当期「术后6个月」（±14 天）任务，恰一条；已过窗口节点 SKIP（Q14）
        List<Map<String, Object>> tasks = tasksOf(patientId);
        assertEquals(1, tasks.size(), "只建当期窗口任务");
        Map<String, Object> task = tasks.get(0);
        assertEquals("术后6个月", task.get("node_name"), "随访时间点来自预置节点（Q03 节点表）");
        assertEquals("PENDING", task.get("status"));
        assertEquals(
                java.sql.Date.valueOf(implant.plusMonths(6).minusDays(14)),
                task.get("window_start"));
        assertEquals(
                java.sql.Date.valueOf(implant.plusMonths(6).plusDays(14)), task.get("window_end"));
        long taskId = ((Number) task.get("id")).longValue();

        // 从任务进入录入（C-ACC-20 自动带入）并提交：窗口内 ON_TIME 完成
        Map<?, ?> me = data(get("/api/hospital/auth/me", nurse));
        Map<?, ?> started = data(post(BASE + "/tasks/" + taskId + "/start", nurse, Map.of()));
        taskRecordId = str(started.get("recordId"));
        assertTrue(Boolean.TRUE.equals(started.get("recordCreated")), "首次开始录入创建草稿");
        Map<?, ?> detail = data(get(BASE + "/records/" + taskRecordId, nurse));
        assertEquals(C_FORM, detail.get("formCode"), "任务录入表单 = 计划节点表单（V91 预置）");
        assertEquals("术后6个月", ((Map<?, ?>) detail.get("context")).get("nodeName"));
        assertTrue(
                String.valueOf(detail.get("visitNo")).matches("105-\\d{4}-\\d{3}"),
                "记录自动带入随访编号：" + detail.get("visitNo"));
        assertEquals(1, detail.get("sequenceNo"), "首条记录随访序号 = 1");
        assertEquals(
                str(me.get("displayName")),
                str(((Map<?, ?>) detail.get("entryDefaults")).get("doctorName")),
                "随访医生默认当前登录用户（C-FU-008）");
        assertEquals("DDD", ((Map<?, ?>) detail.get("device")).get("type"), "自动带入当前器械");

        Map<String, Object> form = baseProgramData(today.minusDays(5), 4);
        form.put("f313", 0.7); // 心室起搏阈值 0.7 V（C-ACC-19 算例的「上次」）
        Map<?, ?> submitted =
                post(BASE + "/records/" + taskRecordId + "/submit", nurse, submitBody(form));
        assertEquals(0, code(submitted), "任务记录提交失败：" + submitted);
        assertEquals("COMPLETED", data(submitted).get("taskStatus"), "提交后任务完成");
        assertEquals("ON_TIME", data(submitted).get("completeKind"), "窗口内完成（按时）");
        assertNull(highlightOf(taskRecordId), "首条记录无「上一次」不比较（C-FU-010），不产生标红");
    }

    // ---------------------------------------------------------------- ⑤ 录入标红（C-ACC-19 数字算例）

    /** 对照 0.7 V 提交 1.2 V（升高 71%）：rv_threshold_rise 命中并持久化（previousValue / 变化率文案），时间轴标红项数 = 1。 */
    @Test
    @Order(2)
    void step2ProgramEntryHighlightsThresholdRiseAgainstLastRecord() {
        String nurse = adminToken();
        LocalDate visit = LocalDate.now().minusDays(2);

        redRecordId = submitProgramRecord(nurse, patientId, visit, Map.of("f313", 1.2), 6);

        Map<String, Object> hit = firstHitOf(redRecordId);
        assertNotNull(hit, "与上次对比升高 > 50% 应标红（V82 预置 rv_threshold_rise）");
        assertEquals("rv_threshold_rise", hit.get("ruleId"));
        assertEquals("f313", hit.get("key"), "标红挂心室起搏阈值字段");
        assertEquals(0.7, ((Number) hit.get("previousValue")).doubleValue(), "上次值 = 任务记录的 0.7 V");
        assertTrue(
                Math.abs(((Number) hit.get("change")).doubleValue() - 71.43) < 0.01,
                "变化率 ≈ 71%：" + hit.get("change"));
        assertTrue(
                String.valueOf(hit.get("changeText")).contains("升高"),
                "变化量文案与前端同形：" + hit.get("changeText"));
        assertTrue(
                String.valueOf(hit.get("message")).contains("心室起搏阈值"),
                "命中规则文案（V82 message）：" + hit.get("message"));

        // 时间轴（C-FU-023）：各次随访带标红项数，含补录
        Map<?, ?> timeline =
                data(get(BASE + "/patients/" + patientId + "/followup-timeline", nurse));
        Map<?, ?> backfill = nodeOf((List<?>) timeline.get("items"), "BACKFILL");
        assertNotNull(backfill, "补录记录在时间轴上");
        assertEquals(str(redRecordId), str(backfill.get("recordId")));
        assertEquals(1, backfill.get("highlightCount"), "标红项数 = 1（C-ACC-18 链路面）");
    }

    // ---------------------------------------------------------------- ⑥–⑦ 报告单 → 补录（转录）

    /** 纸质随访表挂基线位置（待录入，概览 pendingScanCount）→ 转录补录后已电子化、记录自动加入位置、补录进度推进。 */
    @Test
    @Order(3)
    void step3PaperReportGoesPendingThenTranscribeBackfillMarksDone() {
        String nurse = adminToken();
        Map<?, ?> paperName = firstNameOf("C_PAPER_FORM", nurse);

        // ⑥ 报告单：选类型「纸质随访表」、位置挂基线资料（位置中没有随访记录 → 只存附件，CL-05 / OQ-131②）
        paperReportId = createReport(nurse, patientId, "C_PAPER_FORM", str(paperName.get("id")));
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "PENDING",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    paperReportId),
                            "纸质随访表默认待录入（类型默认，C-SCAN-011）");
                });
        Map<?, ?> summary = data(get(BASE + "/patients/" + patientId + "/summary", nurse));
        assertEquals(
                1,
                ((Number) summary.get("pendingScanCount")).intValue(),
                "概览待录入扫描件数 = 1（scan_progress 开关，C-PAT-010）");

        // ⑦ 补录：对照扫描件新建补录记录并转录态提交 → 状态已电子化 + 记录自动加入位置（C-ACC-28）
        long uploaderId = uploaderId();
        Map<?, ?> before =
                data(get(BASE + "/dashboard/cards/scan_progress?uploaderId=" + uploaderId, nurse));
        assertEquals(1, ((Number) before.get("pendingCount")).intValue(), "转录前：待录入 1 份（按上传人隔离）");

        String transcribedId = transcribeBackfill(nurse, LocalDate.now().minusDays(1));

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "DONE",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    paperReportId),
                            "转录提交后状态由待录入变已电子化（验收位）");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'followup_record'"
                                            + " AND anchor_type IS NULL",
                                    Long.class,
                                    paperReportId),
                            "记录自动加入扫描件的位置（整条记录）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_TRANSCRIBE' AND object_type = 'REPORT'"
                                                    + " AND object_id = ? AND result = 'SUCCESS'",
                                            Long.class,
                                            String.valueOf(paperReportId))
                                    >= 1,
                            "写 RPT_TRANSCRIBE 操作日志");
                });
        Map<?, ?> after =
                data(get(BASE + "/dashboard/cards/scan_progress?uploaderId=" + uploaderId, nurse));
        assertEquals(0, ((Number) after.get("pendingCount")).intValue(), "转录后：待录入清零");
        assertEquals(
                ((Number) before.get("doneCount")).longValue() + 1,
                ((Number) after.get("doneCount")).longValue(),
                "已电子化 +1（补录进度 = 已电子化 ÷（已电子化 + 待录入），C-DASH-007）");
        assertEquals(100, ((Number) after.get("donePct")).intValue(), "进度 100%");
        assertNull(highlightOf(transcribedId), "链路收尾核对面：阈值与上次相同（1.2 → 1.2）不标红（变化率 0 不命中）");

        // 概览待录入数随转录回落
        Map<?, ?> summaryAfter = data(get(BASE + "/patients/" + patientId + "/summary", nurse));
        assertEquals(
                0, ((Number) summaryAfter.get("pendingScanCount")).intValue(), "转录后概览待录入扫描件数回落为 0");
    }

    // ---------------------------------------------------------------- ⑧ 统计（首页与卡片反映链路数据）+ 2 秒建议值

    /** 四张 C 卡进布局且数据面反映链路产物（患者 / 标红记录在册、ERI 负例），列表与看板 2 秒内返回（C-NFR-001 建议值）。 */
    @Test
    @Order(4)
    void step4DashboardReflectsChainDataWithinSuggestedLatency() {
        String nurse = adminToken();

        // 看板入口：三张 C 卡 + 补录进度进布局（目录 ∩ 功能开关 ∩ 权限）
        List<String> codes = cardCodes(data(get(BASE + "/dashboard/layout", nurse)));
        assertTrue(codes.contains("device_dist"), "布局含器械类型分布");
        assertTrue(codes.contains("eri_list"), "布局含接近 ERI 器械列表");
        assertTrue(codes.contains("abnormal_records"), "布局含异常标红记录");
        assertTrue(codes.contains("scan_progress"), "布局含补录进度");

        // 器械类型分布：在随访患者数与 DDD 行包含链路患者（品牌拆分在列）
        Map<?, ?> dist = data(get(BASE + "/dashboard/cards/device_dist", nurse));
        assertTrue(((Number) dist.get("followedCount")).longValue() >= 1, "分母含链路患者");
        Map<?, ?> dddRow = rowsByLabel(dist).get("双腔起搏器(DDD)");
        assertNotNull(dddRow, "DDD 行在列（器械显示名随业务字典）");
        assertTrue(((Number) dddRow.get("count")).longValue() >= 1, "链路患者计入 DDD 行");
        assertFalse(((List<?>) dddRow.get("brands")).isEmpty(), "品牌拆分行在列");

        // 指标卡：C 指标集（implant 开 → style=C），在随访 / 近 30 天标红数与列表卡同一条件树
        Map<?, ?> kpi = data(get(BASE + "/dashboard/cards/kpi", nurse));
        assertEquals("C", kpi.get("style"), "C 形态指标集");
        assertTrue(number(metric(kpi, "active")) >= 1, "「在随访患者数」含链路患者");
        assertTrue(number(metric(kpi, "highlightRecent")) >= 1, "「近 30 天含标红参数的记录数」含标红记录");

        // 异常标红记录（30 天）：链路患者的标红记录在列、命中项非空；ERI 列表负例（链路患者未标 ERI 不进列表）
        Map<?, ?> abnormal = data(get(BASE + "/dashboard/cards/abnormal_records", nurse));
        assertEquals(30, ((Number) abnormal.get("days")).intValue(), "窗口缺省 30 天");
        Map<?, ?> hitRow = itemsByPatientId(abnormal).get(str(patientId));
        assertNotNull(hitRow, "标红记录进「异常标红记录」卡（点击可进入记录）");
        assertFalse(((List<?>) hitRow.get("hits")).isEmpty(), "标红项目与变化率在列");
        Map<?, ?> eri = data(get(BASE + "/dashboard/cards/eri_list", nurse));
        assertFalse(
                itemsByPatientId(eri).containsKey(str(patientId)), "未标 ERI 的链路患者不进「接近 ERI 器械列表」");

        // 2 秒建议值（C-NFR-001）：列表（患者 / 任务）与看板（布局 + 三卡 + 指标）服务端往返
        warmUp(nurse);
        assertWithinSuggested(
                "患者列表（分页 20）",
                timed(
                        () ->
                                post(
                                        BASE + "/patients/search",
                                        nurse,
                                        Map.of("pageNo", 1, "pageSize", 20))));
        assertWithinSuggested(
                "任务列表（分页 20）",
                timed(
                        () ->
                                post(
                                        BASE + "/tasks/search",
                                        nurse,
                                        Map.of("scope", "ALL", "pageNo", 1, "pageSize", 20))));
        assertWithinSuggested("看板布局", timed(() -> get(BASE + "/dashboard/layout", nurse)));
        assertWithinSuggested(
                "器械类型分布卡", timed(() -> get(BASE + "/dashboard/cards/device_dist", nurse)));
        assertWithinSuggested(
                "异常标红记录卡", timed(() -> get(BASE + "/dashboard/cards/abnormal_records", nurse)));
        assertWithinSuggested("指标卡", timed(() -> get(BASE + "/dashboard/cards/kpi", nurse)));
    }

    // ---------------------------------------------------------------- ⑨ 双租户回归 + 开关关闭（C-ACC-45 前半句）

    /** demob（B）无 C 卡且 40301、kpi 保持 A 形态；demoa 三开关关闭 → C 入口隐藏、接口 40301、A 口径登记照常并自动入组 A 预置（Q35）。 */
    @Test
    @Order(5)
    void step5DualTenantRegressionAndFeatureOffHidesCEntries() {
        String nurse = adminToken();

        // B 不受影响（demob 开关全关，C 形态只在 demoa）：布局无 C 卡、C 卡 40301、kpi 保持 A 形态（B 是 A 的配置差异版）
        String bAdmin = hospitalToken("demob", "nurse.demo");
        Map<?, ?> bFeatures = data(get("/api/hospital/auth/features", bAdmin));
        assertEquals(Boolean.FALSE, bFeatures.get("implant"), "demob 未开通 implant（租户隔离维度）");
        List<String> bCodes = cardCodes(data(get(BASE + "/dashboard/layout", bAdmin)));
        assertFalse(bCodes.contains("device_dist"), "B 布局无器械类型分布");
        assertFalse(bCodes.contains("eri_list"), "B 布局无接近 ERI 列表");
        assertFalse(bCodes.contains("abnormal_records"), "B 布局无异常标红记录");
        assertFalse(bCodes.contains("scan_progress"), "B 布局无补录进度");
        assertEquals(
                40301, code(get(BASE + "/dashboard/cards/device_dist", bAdmin)), "demob C 卡 40301");
        Map<?, ?> bKpi = data(get(BASE + "/dashboard/cards/kpi", bAdmin));
        assertEquals("A", bKpi.get("style"), "B 指标集保持 A 形态（不受 demoa C 形态影响）");

        // 开关关闭（C-ACC-45 前半句）：C 入口从布局隐藏、接口 40301、C 登记带植入载荷 40001；A 口径登记照常（A 不受影响）
        try {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0 WHERE feature_code IN"
                                            + " ('implant', 'eri_cycle', 'scan_progress')"));
            List<String> offCodes = cardCodes(data(get(BASE + "/dashboard/layout", nurse)));
            assertFalse(offCodes.contains("device_dist"), "开关关闭：器械分布入口隐藏（布局下发）");
            assertFalse(offCodes.contains("eri_list"), "开关关闭：ERI 列表入口隐藏");
            assertFalse(offCodes.contains("abnormal_records"), "开关关闭：异常标红入口隐藏");
            assertFalse(offCodes.contains("scan_progress"), "开关关闭：补录进度入口隐藏");
            assertEquals(
                    40301, code(get(BASE + "/dashboard/cards/device_dist", nurse)), "器械分布 40301");
            assertEquals(
                    40301, code(get(BASE + "/dashboard/cards/eri_list", nurse)), "ERI 列表 40301");
            assertEquals(
                    40301,
                    code(get(BASE + "/dashboard/cards/abnormal_records", nurse)),
                    "异常标红 40301");
            assertEquals(
                    40301, code(get(BASE + "/dashboard/cards/scan_progress", nurse)), "补录进度 40301");
            assertEquals(
                    40301,
                    code(get(BASE + "/patients/" + patientId + "/implants", nurse)),
                    "植入记录接口 40301");
            assertEquals(
                    40001,
                    code(
                            post(
                                    BASE + "/patients",
                                    nurse,
                                    cRegistrationBody("02", LocalDate.now().minusMonths(1)))),
                    "开关关闭 C 登记带植入载荷 40001（ImplantRecordIT 口径）");

            // A 不受影响：开关关闭即回 A 形态，A 口径登记（出院链，不带植入载荷）照常自动入组 A 预置（Q35）
            Map<?, ?> aCreated =
                    post(
                            BASE + "/patients",
                            nurse,
                            aRegistrationBody("A1", LocalDate.now().minusYears(1)));
            assertEquals(0, code(aCreated), "A 口径登记不受 C 开关影响：" + aCreated);
            assertNull(data(aCreated).get("visitNo"), "A 口径不生成随访编号");
            long aPatientId = Long.parseLong(str(data(aCreated).get("id")));
            Map<String, Object> aEnrollment = enrollmentOf(aPatientId);
            assertNotNull(aEnrollment, "A 登记自动入组仍在（C 开关不改变 A 链路）");
            assertEquals(A_PLAN_CODE, aEnrollment.get("plan_code"), "入组 A 年度随访预置（V61）");
            int aFormTaskCount =
                    runInTenant(
                            "demoa",
                            () ->
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?"
                                                    + " AND form_code = 'A_ANNUAL_FU'",
                                            Integer.class,
                                            aPatientId));
            assertTrue(aFormTaskCount > 0, "A 任务用 A_ANNUAL_FU 表单（C 表单不越界）");
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 1 WHERE feature_code IN"
                                            + " ('implant', 'eri_cycle', 'scan_progress')"));
        }
    }

    // ---------------------------------------------------------------- 造数与工具（CSwitchAcceptanceIT /
    // CDigitizeTranscribeAcceptanceIT 同款口径）

    /** 旅程患者转录补录记录（对照扫描件，f313 与上次相同 → 不新增标红）；返回记录 id。 */
    private String transcribeBackfill(String token, LocalDate visitDate) {
        Map<?, ?> created =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate.toString(),
                                "formCode",
                                C_FORM));
        assertEquals(0, code(created), "转录补录创建失败：" + created);
        String recordId = str(data(created).get("recordId"));
        Map<String, Object> form = baseProgramData(visitDate, 6);
        form.put("f313", 1.2);
        Map<String, Object> body = submitBody(form);
        body.put("transcribeReportId", paperReportId);
        Map<?, ?> submitted = post(BASE + "/records/" + recordId + "/submit", token, body);
        assertEquals(0, code(submitted), "转录态提交失败：" + submitted);
        assertEquals(str(paperReportId), str(data(submitted).get("transcribedReportId")));
        return recordId;
    }

    /** C 登记请求体（implant 开关开启：患者 + 首条植入 DDD，RA / RV 电极，过去植入日期 = 历史患者）。 */
    private Map<String, Object> cRegistrationBody(String suffix, LocalDate implantDate) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate.toString());
        implant.put("deviceType", "DDD");
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-" + LEGACY + "-" + suffix);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        implant.put(
                "leads",
                List.of(
                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                        Map.of("chamber", "RV", "position", "RV_APEX")));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "端木全链（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId());
        body.put("legacyNo", LEGACY + "-" + suffix);
        body.put("implant", implant);
        return body;
    }

    /** A 口径登记请求体（出院链，不带植入载荷；ImplantRecordIT / BRegressionAUnaffectedIT 同款）。 */
    private Map<String, Object> aRegistrationBody(String suffix, LocalDate discharge) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "周回归（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1957-11-03");
        body.put("deptId", deptId());
        body.put("wardId", wardId());
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix);
        return body;
    }

    /** C_PROGRAM_FU 最小可提交数据（f303 随访日期必填 + f304 随访时间点必填）。 */
    private static Map<String, Object> baseProgramData(LocalDate visitDate, int timepoint) {
        Map<String, Object> form = new HashMap<>();
        form.put("f303", visitDate.toString());
        form.put("f304", timepoint);
        form.put("f316", 800);
        return form;
    }

    private static Map<String, Object> submitBody(Map<String, Object> form) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", 0);
        body.put("data", form);
        return body;
    }

    /** 补录一条随包 C_PROGRAM_FU 程控记录并提交（visitDate 须早于今天且晚于植入日）；extra 并入 data。返回记录 id。 */
    private String submitProgramRecord(
            String token,
            long patientId,
            LocalDate visitDate,
            Map<String, Object> extra,
            int timepoint) {
        Map<?, ?> created =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate.toString(),
                                "formCode",
                                C_FORM));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = str(data(created).get("recordId"));
        Map<String, Object> form = baseProgramData(visitDate, timepoint);
        form.putAll(extra);
        Map<?, ?> submitted =
                post(BASE + "/records/" + recordId + "/submit", token, submitBody(form));
        assertEquals(0, code(submitted), "补录提交失败：" + submitted);
        return recordId;
    }

    /** 纸质随访表报告单（挂基线资料位置；C 预置名称字典 V94）。 */
    private long createReport(String token, long patientId, String typeCode, String nameId) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", LocalDate.now().minusDays(35).toString());
        body.put("findings", "全链路电子化演练（示例）");
        body.put(
                "locations",
                List.of(
                        Map.of(
                                "linkType", "baseline",
                                "anchorType", "category",
                                "anchorKey", "basic")));
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, code(created), "报告单创建失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 类型下首个名称（GET /report-names，data 直接是清单）。 */
    private Map<?, ?> firstNameOf(String typeCode, String token) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    /** fu_record.highlight（租户库直读；NULL = 无命中）。 */
    private String highlightOf(String recordId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT CAST(highlight AS CHAR) FROM fu_record WHERE id = ?",
                                String.class,
                                Long.parseLong(recordId)));
    }

    /** 第一条命中（JSON 列输出带空格，按字段解析；无命中 null）。 */
    private Map<String, Object> firstHitOf(String recordId) {
        String stored = highlightOf(recordId);
        if (stored == null) {
            return null;
        }
        try {
            return JSON.readValue(stored, new TypeReference<List<Map<String, Object>>>() {}).get(0);
        } catch (Exception broken) {
            throw new IllegalStateException("highlight 解析失败：" + stored, broken);
        }
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

    private List<Map<String, Object>> tasksOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.query(
                                "SELECT t.*, n.name AS node_name FROM fu_task t"
                                        + " JOIN fu_plan_node n ON n.id = t.node_id"
                                        + " WHERE t.patient_id = ? ORDER BY t.id",
                                this::resultMap,
                                patientId));
    }

    private Map<String, Object> resultMap(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Map<String, Object> row = new HashMap<>();
        int columns = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columns; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private static Map<?, ?> nodeOf(List<?> items, String kind) {
        return nodesOf(items, kind).stream().findFirst().orElse(null);
    }

    private static List<Map<?, ?>> nodesOf(List<?> items, String kind) {
        List<Map<?, ?>> matched = new ArrayList<>();
        for (Object item : items) {
            if (kind.equals(((Map<?, ?>) item).get("kind"))) {
                matched.add((Map<?, ?>) item);
            }
        }
        return matched;
    }

    private static List<String> cardCodes(Map<?, ?> layout) {
        List<String> codes = new ArrayList<>();
        for (Object item : (List<?>) layout.get("cards")) {
            codes.add(str(((Map<?, ?>) item).get("code")));
        }
        return codes;
    }

    private static Map<?, ?> metric(Map<?, ?> kpi, String code) {
        for (Object item : (List<?>) kpi.get("metrics")) {
            if (code.equals(str(((Map<?, ?>) item).get("code")))) {
                return (Map<?, ?>) item;
            }
        }
        throw new AssertionError("指标缺失：" + code);
    }

    private static long number(Object metricOrValue) {
        Object value = metricOrValue instanceof Map<?, ?> map ? map.get("value") : metricOrValue;
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Map<String, Map<?, ?>> rowsByLabel(Map<?, ?> dist) {
        Map<String, Map<?, ?>> rows = new HashMap<>();
        for (Object item : (List<?>) dist.get("rows")) {
            rows.put(str(((Map<?, ?>) item).get("label")), (Map<?, ?>) item);
        }
        return rows;
    }

    private static Map<String, Map<?, ?>> itemsByPatientId(Map<?, ?> view) {
        Map<String, Map<?, ?>> items = new HashMap<>();
        for (Object item : (List<?>) view.get("items")) {
            items.put(str(((Map<?, ?>) item).get("patientId")), (Map<?, ?>) item);
        }
        return items;
    }

    private long uploaderId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = 'nurse.demo'",
                                Long.class));
    }

    // ---------------------------------------------------------------- 计时（C-NFR-001 建议值，服务端往返口径）

    /** 预热一次（首次调用含连接池 / JIT 开销，不计入判据——建议值针对稳态打开耗时）。 */
    private void warmUp(String token) {
        assertEquals(
                0,
                code(post(BASE + "/patients/search", token, Map.of("pageNo", 1, "pageSize", 20))));
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/tasks/search",
                                token,
                                Map.of("scope", "ALL", "pageNo", 1, "pageSize", 20))));
        assertEquals(0, code(get(BASE + "/dashboard/layout", token)));
        assertEquals(0, code(get(BASE + "/dashboard/cards/device_dist", token)));
        assertEquals(0, code(get(BASE + "/dashboard/cards/abnormal_records", token)));
        assertEquals(0, code(get(BASE + "/dashboard/cards/kpi", token)));
    }

    private interface TimedCall {
        Map<?, ?> call();
    }

    private long timed(TimedCall call) {
        long start = System.nanoTime();
        Map<?, ?> response = call.call();
        assertEquals(0, code(response), "计时内的接口调用必须成功：" + response);
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void assertWithinSuggested(String surface, long elapsedMs) {
        assertTrue(
                elapsedMs < SUGGESTED_MS,
                surface + " 服务端往返 " + elapsedMs + " ms ≥ 建议值 " + SUGGESTED_MS + " ms（C-NFR-001）");
    }

    // ---------------------------------------------------------------- 基础工具

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
        return hospitalToken("demoa", "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
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

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
