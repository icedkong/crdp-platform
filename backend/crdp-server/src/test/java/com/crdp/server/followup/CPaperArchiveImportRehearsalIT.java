package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E23-T27 端到端演练（tasks.csv 验收「历史患者建档与扫描件关联核对通过」；评审用例 C-ACC-30 / C-PAT-008）。 测试环境形态 = 随包产品配置链 激活后的医院
 * C（runbook §3 同口径，与 {@link CSwitchAcceptanceIT} / {@link CDigitizeTranscribeAcceptanceIT} 同法）： 开
 * {@code implant} + {@code scan_progress} 开关 → 启用 V94 预置报告单类型（纸质随访表 / 植入手术记录 / 知情同意书）→ 发布 V91
 * 两套预置计划（V86 预置映射随即生效，按器械类型自动入组）。
 *
 * <p>演练三阶段（对应 C-ACC-30 步骤 → 预期）：
 *
 * <ol>
 *   <li><b>建档批次</b>（过去植入日期、手填纸质原编号）：12 名历史患者（2015–2024 纸质档案 + 2 名近期植入）逐名登记； 核对 = 建档数（库内计数）+ 纸质原编号落库
 *       + 按器械类型入组（起算快照 = 植入日期）+ 任务口径（历史患者不建任务、 全库 OVERDUE 不增；近期植入只为开启窗口建 1
 *       条任务、已过窗口〔术后72小时〕不建、未来窗口不预生成，Q14 / C-PAT-008）； 重复纸质编号 40902 + DENIED 审计（防重复建档）；
 *   <li><b>扫描件批次</b>（上传并关联）：按「纸质档案台账」逐名上传虚构扫描件（PNG）并关联——纸质随访表 / 知情同意书 ONE_PER_FILE
 *       批量挂基线「基本信息」、植入手术记录挂植入记录整条 + 器械信息分组（E21-T32 锚点）、多页随访表 MERGE 合并一份、出院小结走名称级「无需录入」覆盖；
 *   <li><b>核对</b>（建档数与关联数核对通过）：三口径一致——台账（源）= 库内（rpt_report / rpt_report_link / rpt_report_file /
 *       sys_file ATTACHED + 电子化状态默认值）= 患者内清单 API（份数 / fileCount / 位置标签）+ 概览待录入扫描件数（C-PAT-010）+
 *       按纸质编号全院检索只命中本人（C-SCAN-014 关联核对入口）；越患者植入位置 40400 （关联防错，不暴露存在）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no / 文件名前缀 IT-E23T27-；
 * 预置发布态与开关在收尾还原随包默认，A / B 惰性口径不受影响）。全部虚构数据（纸质编号 纸C-IT27-*、姓名带「示例」）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CPaperArchiveImportRehearsalIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String UPLOAD = "/api/hospital/files";

    private static final String LEGACY = "IT-E23T27";

    /** 纸质原编号前缀（虚构台账号段，审计与排查按它定位）。 */
    private static final String PAPER_NO_PREFIX = "纸C-IT27-";

    private static final String FILE_PREFIX = "IT-E23T27-";

    private static final String PACER_CODE = "C_PACER_FU_PLAN";

    private static final String ICD_CODE = "C_ICD_FU_PLAN";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ObjectStorage storage;

    /** 一名历史患者的纸质档案台账行（扫描件计划决定阶段 2 的上传与关联，份数 / 待录入数由此推导）。 */
    private record PaperArchive(
            int seq,
            String visitNo,
            String deviceType,
            LocalDate implantDate,
            int paperForms,
            int consents,
            boolean implantScan,
            int mergePages,
            boolean dischargeSummary) {

        int reportCount() {
            return paperForms
                    + consents
                    + (implantScan ? 1 : 0)
                    + (mergePages > 0 ? 1 : 0)
                    + (dischargeSummary ? 1 : 0);
        }

        int fileCount() {
            return paperForms
                    + consents
                    + (implantScan ? 1 : 0)
                    + mergePages
                    + (dischargeSummary ? 1 : 0);
        }

        /** 位置行数：基线类资料各 1 行（纸质表 / 同意书 / 合并件 / 出院小结），植入手术记录 = 整条 + 器械信息 2 行。 */
        int linkCount() {
            return paperForms
                    + consents
                    + (mergePages > 0 ? 1 : 0)
                    + (dischargeSummary ? 1 : 0)
                    + (implantScan ? 2 : 0);
        }

        /** 待录入（PENDING）份数：纸质随访表 + 植入手术记录「手术记录」+ 合并件；同意书与出院小结默认无需录入不计。 */
        int pendingCount() {
            return paperForms + (implantScan ? 1 : 0) + (mergePages > 0 ? 1 : 0);
        }

        String planFamily() {
            return "ICD".equals(deviceType) || "CRT_D".equals(deviceType) ? ICD_CODE : PACER_CODE;
        }
    }

    /** 建档结果（患者 id + 台账行），阶段 2 / 3 按它对号核对。 */
    private record Registered(PaperArchive archive, long patientId, long implantId) {}

    // ---------------------------------------------------------------- 环境：随包产品配置链激活（runbook §3）

    /** 医院 C 测试环境形态：开关 + 预置类型启用 + 两套预置计划发布（幂等，CSwitchAcceptanceIT 同法）。 */
    @BeforeAll
    void activateHospitalCShape() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code IN"
                                    + " ('implant', 'scan_progress')");
                    jdbc.update(
                            "UPDATE rpt_type SET status = 'ENABLED' WHERE code IN"
                                    + " ('C_PAPER_FORM', 'C_IMPLANT_RECORD', 'C_CONSENT')");
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
        }
    }

    @BeforeEach
    @AfterAll
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    // 先删桶对象（对象键带 t/demoa/ 前缀），再清台账，最后清业务行与审计（维护开关口径同其他 IT）
                    List<Map<String, Object>> files =
                            jdbc.queryForList(
                                    "SELECT object_key, thumbnail_key FROM sys_file"
                                            + " WHERE original_name LIKE ?",
                                    FILE_PREFIX + "%");
                    for (Map<String, Object> row : files) {
                        deleteObject((String) row.get("object_key"));
                        deleteObject((String) row.get("thumbnail_key"));
                    }
                    jdbc.update(
                            "DELETE FROM sys_file WHERE original_name LIKE ?", FILE_PREFIX + "%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE event_type = 'FILE_UPLOAD' AND detail LIKE ?",
                            "上传文件：" + FILE_PREFIX + "%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (object_type = 'REPORT' AND object_id IN"
                                    + " (SELECT id FROM rpt_report WHERE patient_id IN"
                                    + "  (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)))"
                                    + " OR (event_type = 'PATIENT_CREATE' AND"
                                    + " (target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE ?) OR detail LIKE ?))",
                            LEGACY + "-%",
                            LEGACY + "-%",
                            "%" + PAPER_NO_PREFIX + "%");
                    jdbc.update(
                            "DELETE FROM rpt_report_file WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE FROM rpt_report_link WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE FROM rpt_report WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
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
                                    + " JOIN pt_implant i ON i.id = il.implant_id"
                                    + " JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE i FROM pt_implant i"
                                    + " JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    @AfterAll
    void restorePresetState() {
        runInTenant(
                "demoa",
                () -> {
                    // 预置发布态与开关还原随包默认（激活是测试动作，不影响其他 IT 与 A / B 惰性口径）
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
                            "UPDATE rpt_type SET status = 'DISABLED' WHERE code IN"
                                    + " ('C_PAPER_FORM', 'C_IMPLANT_RECORD', 'C_CONSENT')");
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code IN"
                                    + " ('implant', 'scan_progress')");
                });
    }

    // ---------------------------------------------------------------- 演练（三阶段一个流程）

    @Test
    void paperArchiveDrillRegistersCohortAndVerifiesScanAssociation() {
        LocalDate today = LocalDate.now(PlanDateCalculator.BUSINESS_ZONE);
        String admin = adminToken();
        List<PaperArchive> ledger = ledger(today);

        // ===== 阶段 1：建档批次（过去植入日期 + 手填纸质原编号，C-PAT-008 / C-PAT-003） =====
        Long overdueBefore = countFu("SELECT COUNT(*) FROM fu_task WHERE status = 'OVERDUE'");
        Long deniedBefore =
                countFu(
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                + " AND result = 'DENIED' AND reason = 'DUPLICATE_VISIT_NO'");

        List<Registered> cohort = new ArrayList<>();
        for (PaperArchive row : ledger) {
            Map<?, ?> created = post(BASE + "/patients", admin, registrationBody(row));
            assertEquals(0, code(created), "历史患者建档失败（" + row.visitNo() + "）：" + created);
            assertEquals(row.visitNo(), str(data(created).get("visitNo")), "纸质原编号原样回带");
            long patientId = Long.parseLong(str(data(created).get("id")));
            cohort.add(new Registered(row, patientId, currentImplantId(patientId)));
        }

        // 建档数核对：台账 12 名 = 库内本批计数；纸质原编号逐名落库
        assertEquals(
                ledger.size(),
                countFu("SELECT COUNT(*) FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%"),
                "建档数与台账一致（C-ACC-30「建档数核对」）");
        for (Registered each : cohort) {
            assertEquals(
                    each.archive().visitNo(),
                    queryFu(
                            "SELECT visit_no FROM pt_patient WHERE id = ?",
                            String.class,
                            each.patientId()),
                    "纸质原编号落库（" + each.archive().visitNo() + "）");
        }

        // 入组核对：按器械类型进对应计划族，起算快照 = 植入日期（C-FU-003 / E21-T05）
        for (Registered each : cohort) {
            Map<String, Object> enrollment =
                    queryFuMap(
                            "SELECT plan_code, status, start_date FROM fu_enrollment"
                                    + " WHERE patient_id = ? AND status = 'ACTIVE'",
                            each.patientId());
            assertNotNull(enrollment, "自动入组（" + each.archive().visitNo() + "）");
            assertEquals(each.archive().planFamily(), enrollment.get("plan_code"));
            assertEquals(
                    java.sql.Date.valueOf(each.archive().implantDate()),
                    enrollment.get("start_date"),
                    "起算快照 = 植入日期");
        }

        // 任务核对（Q14 / C-PAT-008）：历史患者（2015–2024）不建任何任务；近期植入只为已开启窗口建 1 条
        for (Registered each : cohort) {
            List<Map<String, Object>> tasks =
                    listFu(
                            "SELECT t.*, n.name AS node_name FROM fu_task t"
                                    + " JOIN fu_plan_node n ON n.id = t.node_id"
                                    + " WHERE t.patient_id = ? ORDER BY t.plan_date",
                            each.patientId());
            if (each.archive().seq() <= 10) {
                assertTrue(
                        tasks.isEmpty(),
                        "入组前窗口已全部结束 → 不建任务、不算逾期（" + each.archive().visitNo() + "）：" + tasks);
                continue;
            }
            assertEquals(1, tasks.size(), "近期植入恰建一条当期任务：" + tasks);
            Map<String, Object> task = tasks.get(0);
            assertEquals("NORMAL", task.get("create_type"), "当期窗口正常创建（非补录）");
            assertEquals("PENDING", task.get("status"), "新任务未逾期");
            LocalDate implant = each.archive().implantDate();
            if (each.archive().seq() == 11) { // 植入 35 天前：术后72小时已过（SKIP）、术后1个月窗口开启
                assertEquals("术后1个月", task.get("node_name"));
                LocalDate plan = implant.plusMonths(1);
                assertEquals(java.sql.Date.valueOf(plan), task.get("plan_date"));
                assertEquals(java.sql.Date.valueOf(plan.minusDays(7)), task.get("window_start"));
                assertEquals(java.sql.Date.valueOf(plan.plusDays(7)), task.get("window_end"));
            } else { // 植入 100 天前（ICD）：72 小时 / 1 个月已过（SKIP）、术后3个月窗口开启
                assertEquals("术后3个月", task.get("node_name"));
                LocalDate plan = implant.plusMonths(3);
                assertEquals(java.sql.Date.valueOf(plan), task.get("plan_date"));
                assertEquals(java.sql.Date.valueOf(plan.minusDays(14)), task.get("window_start"));
                assertEquals(java.sql.Date.valueOf(plan.plusDays(14)), task.get("window_end"));
            }
        }
        assertEquals(
                overdueBefore,
                countFu("SELECT COUNT(*) FROM fu_task WHERE status = 'OVERDUE'"),
                "全库 OVERDUE 不增（首页逾期数不因历史建档上涨）");

        // 防重复建档：同一纸质编号再登记 40902 + DENIED 审计（演练数据质量闸门）
        Map<?, ?> duplicate =
                post(
                        BASE + "/patients",
                        admin,
                        registrationBody(
                                new PaperArchive(
                                        99,
                                        ledger.get(0).visitNo(),
                                        "DDD",
                                        LocalDate.of(2025, 1, 1),
                                        0,
                                        0,
                                        false,
                                        0,
                                        false)));
        assertEquals(40902, code(duplicate), "重复纸质编号被拒：" + duplicate);
        assertTrue(
                countFu(
                                "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                        + " AND result = 'DENIED' AND reason = 'DUPLICATE_VISIT_NO'")
                        > deniedBefore,
                "被拒登记写 DENIED 审计");

        // ===== 阶段 2：扫描件批次（上传 + 关联，C-SCAN-006 / 007 / 010） =====
        String paperName = nameIdOf("C_PAPER_FORM", "随访程控记录单", admin);
        String surgeryName = nameIdOf("C_IMPLANT_RECORD", "手术记录", admin);
        String summaryName = nameIdOf("C_IMPLANT_RECORD", "出院小结", admin);
        Map<Registered, List<Long>> uploadedFiles = new LinkedHashMap<>();
        Map<Registered, Long> implantScanReport = new HashMap<>();
        for (Registered each : cohort) {
            PaperArchive row = each.archive();
            List<Long> files = new ArrayList<>();
            LocalDate implant = row.implantDate();

            // 纸质随访表：ONE_PER_FILE 批量挂基线「基本信息」
            if (row.paperForms() > 0) {
                List<Long> ids = new ArrayList<>();
                for (int i = 1; i <= row.paperForms(); i++) {
                    ids.add(
                            uploadScan(
                                    admin, FILE_PREFIX + row.seq() + "-纸质随访表-" + i + "（示例）.png"));
                }
                files.addAll(ids);
                batchCreate(
                        admin,
                        each.patientId(),
                        "ONE_PER_FILE",
                        "C_PAPER_FORM",
                        paperName,
                        implant.plusDays(30),
                        ids,
                        null);
            }
            // 知情同意书：批量挂基线（默认无需录入）
            if (row.consents() > 0) {
                List<Long> ids = new ArrayList<>();
                for (int i = 1; i <= row.consents(); i++) {
                    ids.add(
                            uploadScan(
                                    admin, FILE_PREFIX + row.seq() + "-知情同意书-" + i + "（示例）.png"));
                }
                files.addAll(ids);
                batchCreate(
                        admin,
                        each.patientId(),
                        "ONE_PER_FILE",
                        "C_CONSENT",
                        null,
                        implant,
                        ids,
                        null);
            }
            // 多页随访表：MERGE 合并一份（C-SCAN-006）
            if (row.mergePages() > 0) {
                List<Long> ids = new ArrayList<>();
                for (int i = 1; i <= row.mergePages(); i++) {
                    ids.add(
                            uploadScan(
                                    admin, FILE_PREFIX + row.seq() + "-合并随访表-" + i + "（示例）.png"));
                }
                files.addAll(ids);
                batchCreate(
                        admin,
                        each.patientId(),
                        "MERGE",
                        "C_PAPER_FORM",
                        paperName,
                        implant.plusDays(400),
                        ids,
                        null);
            }
            // 植入手术记录：挂植入记录整条 + 器械信息分组（E21-T32 锚点，C-SCAN-010）
            if (row.implantScan()) {
                long fileId = uploadScan(admin, FILE_PREFIX + row.seq() + "-植入手术记录（示例）.png");
                files.add(fileId);
                long reportId =
                        createReport(
                                admin,
                                each.patientId(),
                                "C_IMPLANT_RECORD",
                                surgeryName,
                                implant,
                                List.of(
                                        implantWhole(each.implantId()),
                                        implantSection(each.implantId(), "device")),
                                List.of(fileId));
                implantScanReport.put(each, reportId);
            }
            // 出院小结：名称级「无需录入」覆盖（V94 预置名称 digitize_status_override）
            if (row.dischargeSummary()) {
                long fileId = uploadScan(admin, FILE_PREFIX + row.seq() + "-出院小结（示例）.png");
                files.add(fileId);
                createReport(
                        admin,
                        each.patientId(),
                        "C_IMPLANT_RECORD",
                        summaryName,
                        implant.plusDays(5),
                        List.of(baselineBasic()),
                        List.of(fileId));
            }
            uploadedFiles.put(each, files);
        }

        // ===== 阶段 3：核对（台账 = 库内 = API，三口径一致） =====
        // 3.1 库内总量对台账（建档数 / 关联数，C-ACC-30 验收位）
        int totalReports = 0;
        int totalLinks = 0;
        int totalFiles = 0;
        int totalPending = 0;
        for (Registered each : cohort) {
            PaperArchive row = each.archive();
            totalReports += row.reportCount();
            totalLinks += row.linkCount();
            totalFiles += row.fileCount();
            totalPending += row.pendingCount();
            long p = each.patientId();
            assertEquals(
                    row.reportCount(),
                    (long)
                            queryFu(
                                    "SELECT COUNT(*) FROM rpt_report WHERE patient_id = ?",
                                    Long.class,
                                    p),
                    "报告单份数（" + row.visitNo() + "）");
            assertEquals(
                    row.linkCount(),
                    (long)
                            queryFu(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE patient_id = ?",
                                    Long.class,
                                    p),
                    "位置关联行数（" + row.visitNo() + "）");
            assertEquals(
                    row.fileCount(),
                    (long)
                            queryFu(
                                    "SELECT COUNT(*) FROM rpt_report_file WHERE patient_id = ?",
                                    Long.class,
                                    p),
                    "扫描件挂接数（" + row.visitNo() + "）");
            assertEquals(
                    row.pendingCount(),
                    (long)
                            queryFu(
                                    "SELECT COUNT(*) FROM rpt_report WHERE patient_id = ?"
                                            + " AND digitize_status = 'PENDING'",
                                    Long.class,
                                    p),
                    "待录入份数（类型默认：纸质随访表 / 手术记录待录入，同意书 / 出院小结无需录入）");
        }
        assertEquals(
                totalReports,
                (long)
                        countFu(
                                "SELECT COUNT(*) FROM rpt_report WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%"),
                "本批报告单总数与台账合计一致");
        assertEquals(
                totalPending,
                (long)
                        countFu(
                                "SELECT COUNT(*) FROM rpt_report WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)"
                                        + " AND digitize_status = 'PENDING'",
                                LEGACY + "-%"),
                "本批待录入总数与台账合计一致");
        for (List<Long> files : uploadedFiles.values()) {
            for (Long fileId : files) {
                assertEquals(
                        "ATTACHED",
                        queryFu("SELECT status FROM sys_file WHERE id = ?", String.class, fileId),
                        "台账置 ATTACHED（挂接后未游离）");
            }
        }

        // 3.2 植入手术记录关联粒度：整条 + 器械信息两行，锚到本人植入记录（防错关联的关键行）
        for (Map.Entry<Registered, Long> entry : implantScanReport.entrySet()) {
            Registered each = entry.getKey();
            assertEquals(
                    1L,
                    (long)
                            queryFu(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'implant_record'"
                                            + " AND link_record_id = ? AND anchor_type IS NULL",
                                    Long.class,
                                    entry.getValue(),
                                    each.implantId()),
                    "植入记录整条位置");
            assertEquals(
                    1L,
                    (long)
                            queryFu(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'implant_record'"
                                            + " AND link_record_id = ? AND anchor_type = 'section'"
                                            + " AND anchor_key = 'device'",
                                    Long.class,
                                    entry.getValue(),
                                    each.implantId()),
                    "器械信息分组位置（C-SCAN-010 不细到字段）");
        }

        // 3.3 患者内清单与概览（C-PAT-017 数据面 + C-PAT-010 待录入扫描件数）
        for (Registered each : cohort) {
            PaperArchive row = each.archive();
            Map<?, ?> reports =
                    get(BASE + "/patients/" + each.patientId() + "/reports?scope=all", admin);
            assertEquals(0, code(reports));
            List<?> items = (List<?>) data(reports).get("items");
            assertEquals(row.reportCount(), items.size(), "清单份数（" + row.visitNo() + "）");
            int apiPending = 0;
            int apiFiles = 0;
            for (Object item : items) {
                Map<?, ?> report = (Map<?, ?>) item;
                if ("C_PAPER_FORM".equals(report.get("typeCode"))
                        || ("C_IMPLANT_RECORD".equals(report.get("typeCode"))
                                && "手术记录".equals(report.get("name")))) {
                    assertEquals("PENDING", report.get("digitizeStatus"), "扫描件默认待录入");
                    apiPending++;
                } else {
                    assertEquals(
                            "NO_ENTRY",
                            report.get("digitizeStatus"),
                            "同意书 / 出院小结默认无需录入：" + report.get("name"));
                }
                apiFiles += ((Number) report.get("fileCount")).intValue();
            }
            assertEquals(row.pendingCount(), apiPending, "清单待录入份数");
            assertEquals(row.fileCount(), apiFiles, "清单附件数");
            if (row.mergePages() > 0) {
                assertTrue(
                        items.stream()
                                .anyMatch(
                                        item ->
                                                ((Number) ((Map<?, ?>) item).get("fileCount"))
                                                                .intValue()
                                                        == row.mergePages()),
                        "合并件 fileCount = 页数（C-SCAN-006 多页一份）");
            }
            if (row.implantScan()) {
                assertTrue(
                        items.stream()
                                .anyMatch(
                                        item ->
                                                ((List<?>) ((Map<?, ?>) item).get("locations"))
                                                        .stream()
                                                                .anyMatch(
                                                                        tag ->
                                                                                "implant_record"
                                                                                        .equals(
                                                                                                ((Map<
                                                                                                                        ?,
                                                                                                                        ?>)
                                                                                                                tag)
                                                                                                        .get(
                                                                                                                "linkType")))),
                        "清单位置标签含植入记录（左栏跳转数据源）");
            }
            Map<?, ?> summary =
                    data(get(BASE + "/patients/" + each.patientId() + "/summary", admin));
            assertEquals(
                    row.pendingCount(),
                    ((Number) summary.get("pendingScanCount")).intValue(),
                    "概览待录入扫描件数（C-PAT-010）：" + row.visitNo());
        }

        // 3.4 按纸质编号检索：只命中本人扫描件（C-SCAN-014，人工核对的入口口径）
        Registered probe = cohort.get(0);
        Map<?, ?> search =
                data(
                        post(
                                BASE + "/reports/search",
                                admin,
                                Map.of("keyword", probe.archive().visitNo(), "pageSize", 100)));
        assertEquals(
                probe.archive().reportCount(),
                ((Number) search.get("total")).intValue(),
                "纸质编号等值命中本批扫描件");
        for (Object item : (List<?>) search.get("items")) {
            assertEquals(
                    String.valueOf(probe.patientId()),
                    str(((Map<?, ?>) item).get("patientId")),
                    "不串到其他患者");
        }

        // 3.5 关联防错：把扫描件挂到他人植入记录 → 40400（不暴露存在，AGENTS §5.2）
        Registered other = cohort.get(4);
        long strangerFileId =
                uploadScan(admin, FILE_PREFIX + probe.archive().seq() + "-错挂尝试（示例）.png");
        Map<?, ?> misfiled =
                post(
                        BASE + "/reports",
                        admin,
                        reportBody(
                                probe.patientId(),
                                "C_IMPLANT_RECORD",
                                surgeryName,
                                LocalDate.of(2024, 1, 1),
                                List.of(implantWhole(other.implantId())),
                                List.of(strangerFileId)));
        assertEquals(40400, code(misfiled), "越患者植入位置统一 40400：" + misfiled);
    }

    // ---------------------------------------------------------------- 纸质档案台账（全部虚构）

    /** 12 名历史患者：01–10 为 2015–2024 纸质档案（窗口已全过），11 / 12 为近期植入（当期窗口开启）。 */
    private static List<PaperArchive> ledger(LocalDate today) {
        List<PaperArchive> rows = new ArrayList<>();
        rows.add(
                new PaperArchive(
                        1,
                        PAPER_NO_PREFIX + "2015-001",
                        "DDD",
                        LocalDate.of(2015, 4, 12),
                        2,
                        1,
                        true,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        2,
                        PAPER_NO_PREFIX + "2016-002",
                        "SSI",
                        LocalDate.of(2016, 8, 1),
                        1,
                        1,
                        false,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        3,
                        PAPER_NO_PREFIX + "2017-003",
                        "CRT_P",
                        LocalDate.of(2017, 11, 20),
                        1,
                        0,
                        false,
                        2,
                        false));
        rows.add(
                new PaperArchive(
                        4,
                        PAPER_NO_PREFIX + "2018-004",
                        "ICD",
                        LocalDate.of(2018, 5, 6),
                        2,
                        1,
                        false,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        5,
                        PAPER_NO_PREFIX + "2019-005",
                        "CRT_D",
                        LocalDate.of(2019, 2, 14),
                        1,
                        0,
                        true,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        6,
                        PAPER_NO_PREFIX + "2020-006",
                        "DDD",
                        LocalDate.of(2020, 7, 7),
                        0,
                        1,
                        false,
                        3,
                        true));
        rows.add(
                new PaperArchive(
                        7,
                        PAPER_NO_PREFIX + "2021-007",
                        "HBP_LBBP",
                        LocalDate.of(2021, 9, 30),
                        1,
                        0,
                        false,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        8,
                        PAPER_NO_PREFIX + "2022-008",
                        "LEADLESS",
                        LocalDate.of(2022, 3, 18),
                        1,
                        1,
                        true,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        9,
                        PAPER_NO_PREFIX + "2023-009",
                        "DDD",
                        LocalDate.of(2023, 10, 25),
                        2,
                        0,
                        false,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        10,
                        PAPER_NO_PREFIX + "2024-010",
                        "ICD",
                        LocalDate.of(2024, 1, 8),
                        1,
                        1,
                        false,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        11,
                        PAPER_NO_PREFIX + today.getYear() + "-011",
                        "DDD",
                        today.minusDays(35),
                        1,
                        1,
                        false,
                        0,
                        false));
        rows.add(
                new PaperArchive(
                        12,
                        PAPER_NO_PREFIX + today.getYear() + "-012",
                        "ICD",
                        today.minusDays(100),
                        1,
                        0,
                        false,
                        0,
                        false));
        return rows;
    }

    // ---------------------------------------------------------------- 造数与工具（口径同
    // VisitNoRegistrationIT
    // / ReportIT / CSwitchAcceptanceIT）

    /** C 登记请求体：手填纸质原编号 + 过去植入日期 + 首条植入记录（C-PAT-001 / C-PAT-008）。 */
    private Map<String, Object> registrationBody(PaperArchive row) {
        Map<String, Object> body = new HashMap<>();
        String mrn = LEGACY + "-" + String.format("%02d", row.seq());
        body.put("medicalRecordNo", mrn);
        body.put("name", "王李张（示例）");
        body.put("gender", row.seq() % 2 == 0 ? "F" : "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId());
        body.put("legacyNo", mrn);
        body.put("visitNo", row.visitNo());
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", row.implantDate().toString());
        implant.put("deviceType", row.deviceType());
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-E23T27-" + String.format("%02d", row.seq()));
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        if (!"LEADLESS".equals(row.deviceType())) { // 无导线起搏器不录电极（C-PAT-005）
            implant.put(
                    "leads",
                    List.of(
                            Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                            Map.of("chamber", "RV", "position", "RV_APEX")));
        }
        body.put("implant", implant);
        return body;
    }

    private long currentImplantId(long patientId) {
        return queryFu(
                "SELECT id FROM pt_implant WHERE patient_id = ? AND status = 'ACTIVE'",
                Long.class,
                patientId);
    }

    /** 类型下按名称取名称 id（V94 预置名称字典：随访程控记录单 / 手术记录 / 出院小结…）。 */
    private String nameIdOf(String typeCode, String name, String token) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        return names.stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> name.equals(item.get("name")))
                .findFirst()
                .map(item -> str(item.get("id")))
                .orElseThrow(() -> new AssertionError("预置名称缺失：" + typeCode + " / " + name));
    }

    /** 批量上传（E16-T03）：ONE_PER_FILE / MERGE，统一挂基线「基本信息」位置。 */
    private void batchCreate(
            String token,
            long patientId,
            String mode,
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<Long> fileIds,
            List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("mode", mode);
        body.put("typeCode", typeCode);
        if (nameId != null) {
            body.put("nameId", nameId);
        }
        body.put("reportDate", reportDate.toString());
        body.put("locations", List.of(baselineBasic()));
        List<String> ids = fileIds.stream().map(String::valueOf).toList();
        body.put("fileIds", ids);
        if (items != null) {
            body.put("items", items);
        }
        Map<?, ?> created = post(BASE + "/reports/batch-create", token, body);
        assertEquals(0, code(created), "批量上传失败（" + typeCode + "）：" + created);
    }

    private Map<String, Object> reportBody(
            long patientId,
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<Map<String, Object>> locations,
            List<Long> fileIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", reportDate.toString());
        body.put("findings", "历史纸质资料导入演练（示例）");
        body.put("locations", locations);
        body.put("fileIds", fileIds.stream().map(String::valueOf).toList());
        return body;
    }

    private long createReport(
            String token,
            long patientId,
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<Map<String, Object>> locations,
            List<Long> fileIds) {
        Map<?, ?> created =
                post(
                        BASE + "/reports",
                        token,
                        reportBody(patientId, typeCode, nameId, reportDate, locations, fileIds));
        assertEquals(0, code(created), "创建报告单失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** multipart 上传一张 1×1 PNG（Tika 白名单与真实解码均可过），返回 fileId。 */
    private long uploadScan(String token, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(png()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(UPLOAD, new HttpEntity<>(parts, headers), Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        assertEquals(0, code(response), "上传失败：" + response);
        return Long.parseLong(str(data(response).get("fileId")));
    }

    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    private static Map<String, Object> baselineBasic() {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "baseline");
        location.put("anchorType", "category");
        location.put("anchorKey", "basic");
        return location;
    }

    /** 植入记录整条位置（anchorType 为 null，HashMap 承载——Map.of 不收 null 值）。 */
    private static Map<String, Object> implantWhole(long implantId) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "implant_record");
        location.put("linkRecordId", String.valueOf(implantId));
        location.put("anchorType", null);
        return location;
    }

    /** 植入记录分组位置（器械信息 device / 电极 lead，E21-T32）。 */
    private static Map<String, Object> implantSection(long implantId, String anchorKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "implant_record");
        location.put("linkRecordId", String.valueOf(implantId));
        location.put("anchorType", "section");
        location.put("anchorKey", anchorKey);
        return location;
    }

    private long presetPlanId(String code) {
        return queryFu("SELECT id FROM fu_plan WHERE code = ?", Long.class, code);
    }

    private String presetPlanStatus(long planId) {
        return queryFu("SELECT status FROM fu_plan WHERE id = ?", String.class, planId);
    }

    private long deptId() {
        return queryFu("SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'", Long.class);
    }

    private long wardId() {
        return queryFu("SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class);
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
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long countFu(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, Long.class, args));
    }

    private <T> T queryFu(String sql, Class<T> type, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, type, args));
    }

    private Map<String, Object> queryFuMap(String sql, Object... args) {
        return runInTenant(
                "demoa", () -> jdbc.query(sql, rs -> rs.next() ? resultMap(rs) : null, args));
    }

    private List<Map<String, Object>> listFu(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForList(sql, args));
    }

    private static Map<String, Object> resultMap(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private void deleteObject(String objectKey) {
        if (objectKey == null) {
            return;
        }
        String prefix = "t/demoa/";
        String relative =
                objectKey.startsWith(prefix) ? objectKey.substring(prefix.length()) : objectKey;
        storage.delete(relative);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
