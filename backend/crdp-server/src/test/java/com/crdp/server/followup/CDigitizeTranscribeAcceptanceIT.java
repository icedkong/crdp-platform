package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
 * E21-T27 执行方套件 / C-ACC-28、29、34（评审 §3.5–3.6；验收「状态流转用例通过」）。在随包产品配置链上端到端执行： runbook §3 激活路径（启用 C
 * 预置类型）+ {@code scan_progress} 开关 = 医院 C 形态（demoa 模拟）。
 *
 * <ol>
 *   <li>C-ACC-28：纸质随访表（C_PAPER_FORM，随包预置）分屏转录——新建补录程控记录并对照录入，保存 → 记录自动加入扫描件位置、
 *       状态「待录入」→「已电子化」、可从扫描件跳到该记录（患者内清单位置标签即数据源，OQ-131②）；
 *   <li>C-ACC-29：电子化状态三态与默认值（类型默认：纸质随访表待录入、知情同意书无需录入）；手工改「无需录入」须填原因； 补录进度 = 已电子化 ÷（已电子化 +
 *       待录入），「无需录入」不计入；移除随访位置 → 回「待录入」、结构化记录不受影响（CL-10）；
 *   <li>C-ACC-34：程控报告单（C_PROGRAM + V94 预置映射 C_PROGRAM_TO_FU）位置选随访记录的程控分组（battery）—— 映射确认把电池电压 /
 *       心室起搏阈值写入该记录（报告单不另存参数）、转录态提交后报告单转「已电子化」； 移除其映射目标随访位置 → 已写入参数不清除、状态回「待录入」（CL-05 / OQ-131②）；
 *   <li>位置守卫（含植入 / 程控分组）：implant 开关关闭时植入位置 40301（E21-T32 闸门口径）；程控分组锚点须在该记录固化版本分组清单内。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（病案号 IT-E21T27- 前缀；C 预置类型随包
 * DISABLED，激活为测试动作、收尾还原）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CDigitizeTranscribeAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E21T27";

    /** 随包医院 C 随访程控表单（V90，demoa 常态存在，只读引用不删改种子；f303 随访日期 / f304 随访时间点）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    // 开关回关（A 租户形态；种子 V100 默认 0）
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'scan_progress'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE object_type = 'REPORT' AND object_id IN"
                                    + " (SELECT id FROM rpt_report WHERE patient_id IN"
                                    + "  (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id"
                                    + " WHERE fh.record_type = 'FU_RECORD' AND r.patient_id IN"
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
                            "DELETE FROM fu_record WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () ->
                        // C 预置类型还原随包 DISABLED（激活是测试动作，不留库影响 A / B 惰性口径，OQ-142①）
                        jdbc.update(
                                "UPDATE rpt_type SET status = 'DISABLED' WHERE code IN"
                                        + " ('C_PROGRAM', 'C_PAPER_FORM', 'C_CONSENT')"));
    }

    /** 医院 C 形态：scan_progress 开 + runbook §3 的 C 预置类型启用（库内数据动作，同手册口径）。 */
    private void activateCHospitalShape() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                    + " 'scan_progress'");
                    jdbc.update(
                            "UPDATE rpt_type SET status = 'ENABLED' WHERE code IN"
                                    + " ('C_PROGRAM', 'C_PAPER_FORM', 'C_CONSENT')");
                });
    }

    // ---------------------------------------------------------------- C-ACC-28 转录（C 预置链）

    /** 纸质随访表只挂基线位置（位置中没有随访记录）→ 转录态提交：状态置 DONE、记录自动加入位置（整条）、 患者内清单位置标签出现（「从扫描件跳转到该随访记录」的数据源）。 */
    @Test
    void cAcc28PaperFormTranscribeMarksDoneAndJoinsRecord() {
        activateCHospitalShape();
        String nurse = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(nurse, "01");
        Map<?, ?> paperName = firstNameOf("C_PAPER_FORM", nurse);
        long reportId =
                createReport(
                        nurse,
                        patientId,
                        "C_PAPER_FORM",
                        str(paperName.get("id")),
                        List.of(baselineLocation("basic")),
                        List.of());

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "PENDING",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "纸质随访表默认待录入（类型默认，C-SCAN-011）");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'followup_record'",
                                    Long.class,
                                    reportId),
                            "造数：位置中没有随访记录（只挂基线）");
                });

        // 转录流程：新建补录程控记录并对照录入（OQ-131②：位置中无随访记录 → 转录新建补录记录）
        long recordId = createBackfillRecord(nurse, patientId, LocalDate.now().minusDays(30));
        Map<String, Object> submit = submitBody(LocalDate.now().minusDays(30), 6, null);
        submit.put("transcribeReportId", reportId);
        Map<?, ?> submitted = post(BASE + "/records/" + recordId + "/submit", nurse, submit);
        assertEquals(0, code(submitted), "转录态提交失败：" + submitted);
        assertEquals(
                String.valueOf(reportId),
                str(data(submitted).get("transcribedReportId")),
                "结果回带 transcribedReportId");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "DONE",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "保存后状态由待录入变已电子化（验收位）");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'followup_record'"
                                            + " AND link_record_id = ? AND anchor_type IS NULL",
                                    Long.class,
                                    reportId,
                                    recordId),
                            "记录自动加入扫描件的位置（整条记录）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_TRANSCRIBE' AND object_type = 'REPORT'"
                                                    + " AND object_id = ? AND result = 'SUCCESS'",
                                            Long.class,
                                            String.valueOf(reportId))
                                    >= 1,
                            "写 RPT_TRANSCRIBE 操作日志");
                });

        // 患者内清单：位置标签出现（前端「从扫描件跳转到该随访记录」的数据源，C-FU-017）
        Map<?, ?> reports = get(BASE + "/patients/" + patientId + "/reports?scope=all", nurse);
        assertEquals(0, code(reports));
        List<?> items = (List<?>) data(reports).get("items");
        assertEquals(1, items.size());
        List<?> locations = (List<?>) ((Map<?, ?>) items.get(0)).get("locations");
        assertTrue(
                locations.stream()
                        .anyMatch(tag -> str(((Map<?, ?>) tag).get("linkRecordId")) != null),
                "清单位置标签含该随访记录：" + locations);
    }

    // ---------------------------------------------------------------- C-ACC-29 三态、进度与回退（状态流转核心）

    /**
     * 三态与默认值：类型默认（纸质随访表 PENDING / 知情同意书 NO_ENTRY 不计分母）；手工改无需录入须填原因； 补录进度随转录推进；移除随访位置 →
     * 回待录入、结构化记录不受影响（CL-10）。
     */
    @Test
    void cAcc29ThreeStatesDefaultsProgressAndRevertOnLocationRemoval() {
        activateCHospitalShape();
        String nurse = hospitalToken("demoa", "nurse.demo");
        long uploaderId = uploaderId(nurse);
        long patientId = registeredPatient(nurse, "02");
        Map<?, ?> paperName = firstNameOf("C_PAPER_FORM", nurse);
        Map<?, ?> consentName = firstNameOf("C_CONSENT", nurse);

        // 知情同意书默认无需录入（类型默认；「无需录入」不计入补录进度分母）
        long consentId =
                createReport(
                        nurse,
                        patientId,
                        "C_CONSENT",
                        str(consentName.get("id")),
                        List.of(baselineLocation("basic")),
                        List.of());
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                "NO_ENTRY",
                                jdbc.queryForObject(
                                        "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                        String.class,
                                        consentId),
                                "知情同意书默认无需录入（规格 §6 验收示例）"));

        // 手工改「无需录入」须填原因（已有 NO_ENTRY → 用纸质随访表验证：先改成 NO_ENTRY 无原因 40001）
        long paperId =
                createReport(
                        nurse,
                        patientId,
                        "C_PAPER_FORM",
                        str(paperName.get("id")),
                        List.of(baselineLocation("basic")),
                        List.of());
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/reports/" + paperId + "/digitize-status",
                                nurse,
                                Map.of("status", "NO_ENTRY"))),
                "改无需录入不填原因 40001");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports/" + paperId + "/digitize-status",
                                nurse,
                                Map.of("status", "NO_ENTRY", "reason", "签名页无需转录（示例）"))),
                "填原因后可改");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports/" + paperId + "/digitize-status",
                                nurse,
                                Map.of("status", "PENDING"))),
                "手工改回待录入（不须原因）");

        // 补录进度（C-DASH-007）：转录前 pending=1 / done=0；转录后 pending=0 / done=1、比例推进（按上传人过滤隔离本例）
        Map<?, ?> before =
                data(get(BASE + "/dashboard/cards/scan_progress?uploaderId=" + uploaderId, nurse));
        long pendingBefore = ((Number) before.get("pendingCount")).longValue();
        long doneBefore = ((Number) before.get("doneCount")).longValue();
        assertEquals(1L, pendingBefore, "转录前：纸质随访表 1 份待录入（知情同意书不在分母）");
        assertEquals(0L, doneBefore, "转录前：无已电子化");
        assertEquals(0, ((Number) before.get("donePct")).intValue(), "进度 0%");

        long recordId = createBackfillRecord(nurse, patientId, LocalDate.now().minusDays(45));
        Map<String, Object> submit = submitBody(LocalDate.now().minusDays(45), 6, null);
        submit.put("transcribeReportId", paperId);
        assertEquals(
                0, code(post(BASE + "/records/" + recordId + "/submit", nurse, submit)), "转录态提交");

        Map<?, ?> after =
                data(get(BASE + "/dashboard/cards/scan_progress?uploaderId=" + uploaderId, nurse));
        assertEquals(0L, ((Number) after.get("pendingCount")).longValue(), "转录后：待录入清零");
        assertEquals(
                doneBefore + 1,
                ((Number) after.get("doneCount")).longValue(),
                "转录后：已电子化 +1（进度 = 已电子化 ÷（已电子化 + 待录入））");
        assertEquals(100, ((Number) after.get("donePct")).intValue(), "进度 100%（无需录入不计入分母）");

        // 移除随访位置 → 自动回「待录入」、结构化记录不受影响（CL-10；仍保留基线位置 ≥1）
        Map<String, Object> removal = new HashMap<>();
        removal.put("add", List.of());
        removal.put("remove", List.of(wholeRecordLocation(recordId)));
        assertEquals(
                0,
                code(put(BASE + "/reports/" + paperId + "/locations", nurse, removal)),
                "移除随访位置失败");
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "PENDING",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    paperId),
                            "移除随访记录位置 → 已电子化自动回待录入（C-SCAN-020 / §7）");
                    assertEquals(
                            "SUBMITTED",
                            jdbc.queryForObject(
                                    "SELECT status FROM fu_record WHERE id = ?",
                                    String.class,
                                    recordId),
                            "结构化记录不受影响");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_DIGITIZE_CHANGE' AND object_type ="
                                                    + " 'REPORT' AND object_id = ?"
                                                    + " AND result = 'SUCCESS'",
                                            Long.class,
                                            String.valueOf(paperId))
                                    >= 1,
                            "状态自动回退写 RPT_DIGITIZE_CHANGE 操作日志");
                });
        Map<?, ?> reverted =
                data(get(BASE + "/dashboard/cards/scan_progress?uploaderId=" + uploaderId, nurse));
        assertEquals(
                1L, ((Number) reverted.get("pendingCount")).longValue(), "回退后进度口径随之回落（待录入 +1）");
        assertEquals(0, ((Number) reverted.get("donePct")).intValue(), "回到 0%");
    }

    // ---------------------------------------------------------------- C-ACC-34 程控报告单映射 + 程控分组位置

    /**
     * 位置选随访记录的程控分组（battery）：映射确认把电池电压 / 心室起搏阈值写入该记录（报告单不另存参数）、 转录态提交后报告单转已电子化；移除其映射目标随访位置 →
     * 已写入参数不清除、状态回待录入（CL-05）。
     */
    @Test
    void cAcc34ProgramReportMapsIntoProgramGroupThenReverts() {
        activateCHospitalShape();
        String nurse = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(nurse, "03");
        long recordId = createBackfillRecord(nurse, patientId, LocalDate.now().minusDays(60));
        Map<?, ?> programName = firstNameOf("C_PROGRAM", nurse);

        // 位置 = 该随访记录的程控分组（battery，V90 分类键）+ 基线（移除时保底 ≥1）
        long reportId =
                createReport(
                        nurse,
                        patientId,
                        "C_PROGRAM",
                        str(programName.get("id")),
                        List.of(sectionLocation(recordId, "battery"), baselineLocation("basic")),
                        List.of(
                                indicator("battery_voltage", "2.85"),
                                indicator("rv_threshold", "0.9")));
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'followup_record' AND link_record_id = ?"
                                            + " AND anchor_type = 'section' AND anchor_key = 'battery'",
                                    Long.class,
                                    reportId,
                                    recordId),
                            "造数：程控分组位置（battery）在册");
                    assertEquals(
                            "PENDING",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "程控报告单默认待录入");
                });

        // 映射预览：候选 = 位置中的随访记录；确认写入电池电压 / 心室起搏阈值（报告单不另存参数）
        Map<?, ?> preview =
                data(post(BASE + "/reports/" + reportId + "/mapping/preview", nurse, Map.of()));
        Map<?, ?> mapping = mappingOf(preview, "FOLLOWUP_RECORD");
        assertEquals(
                "C_PROGRAM_FU", str(mapping.get("targetFormCode")), "预置映射 C_PROGRAM_TO_FU（V94）");
        Map<?, ?> candidate = (Map<?, ?>) ((List<?>) mapping.get("candidates")).get(0);
        assertEquals(str(recordId), str(candidate.get("linkRecordId")), "候选 = 位置中的随访记录");
        List<String> fieldKeys = new ArrayList<>();
        for (Object write : (List<?>) candidate.get("writes")) {
            Map<?, ?> row = (Map<?, ?>) write;
            if (row.get("error") == null) {
                fieldKeys.add(str(row.get("fieldKey")));
            }
        }
        assertTrue(fieldKeys.containsAll(List.of("f322", "f313")), "预览含电池电压 / 心室起搏阈值写入行");
        Map<String, Object> confirm = new HashMap<>();
        confirm.put("mappingId", str(mapping.get("mappingId")));
        confirm.put("linkRecordId", str(recordId));
        confirm.put("targetVersion", ((Number) candidate.get("targetVersion")).intValue());
        confirm.put("fieldKeys", fieldKeys);
        Map<?, ?> confirmResponse =
                post(BASE + "/reports/" + reportId + "/mapping/confirm", nurse, confirm);
        assertEquals(0, code(confirmResponse), "映射确认失败：" + confirmResponse);
        int versionAfterMapping = ((Number) data(confirmResponse).get("targetVersion")).intValue();
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            2.85,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f322') FROM fu_record WHERE id = ?",
                                    Double.class,
                                    recordId),
                            "电池电压写入映射目标随访记录");
                    assertEquals(
                            0.9,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f313') FROM fu_record WHERE id = ?",
                                    Double.class,
                                    recordId),
                            "心室起搏阈值写入映射目标随访记录");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report WHERE id = ?"
                                            + " AND (JSON_EXTRACT(field_values, '$.f322') IS NOT"
                                            + " NULL OR JSON_EXTRACT(field_values, '$.f313') IS"
                                            + " NOT NULL)",
                                    Long.class,
                                    reportId),
                            "报告单不另存一份参数（参数只存映射目标记录，A-ATT-013 / C-SCAN-005）");
                });

        // 转录态提交（OQ-131② / OQ-142②：随访日期由转录 / 补录流程落）→ 报告单转已电子化；
        // baseVersion 用映射写入后的记录版本（映射确认已 +1，AGENTS §5.10 乐观锁）
        Map<String, Object> submit =
                submitBody(LocalDate.now().minusDays(60), 6, Map.of("f322", 2.85, "f313", 0.9));
        submit.put("baseVersion", versionAfterMapping);
        submit.put("transcribeReportId", reportId);
        assertEquals(
                0, code(post(BASE + "/records/" + recordId + "/submit", nurse, submit)), "提交失败");
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                "DONE",
                                jdbc.queryForObject(
                                        "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                        String.class,
                                        reportId),
                                "转录态提交后报告单转已电子化"));

        // 移除映射目标随访位置（程控分组行；基线保底）→ 参数不清除、状态回待录入
        Map<String, Object> removal = new HashMap<>();
        removal.put("add", List.of());
        removal.put(
                "remove",
                List.of(
                        Map.of(
                                "linkType",
                                "followup_record",
                                "linkRecordId",
                                String.valueOf(recordId),
                                "anchorType",
                                "section",
                                "anchorKey",
                                "battery")));
        assertEquals(
                0, code(put(BASE + "/reports/" + reportId + "/locations", nurse, removal)), "移除失败");
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "PENDING",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "移除映射目标随访位置 → 状态回待录入（C-SCAN-020）");
                    assertEquals(
                            2.85,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f322') FROM fu_record WHERE id = ?",
                                    Double.class,
                                    recordId),
                            "已写入参数不清除");
                    assertEquals(
                            "SUBMITTED",
                            jdbc.queryForObject(
                                    "SELECT status FROM fu_record WHERE id = ?",
                                    String.class,
                                    recordId),
                            "结构化记录不受影响");
                });
    }

    // ---------------------------------------------------------------- 位置守卫（含植入 / 程控分组）

    /**
     * 植入记录位置（E21-T32 已开放）：本套 implant 开关保持关闭 → 创建侧 40301；修改位置 remove 侧按四元组匹配在册行（不在册 40001）；
     * 程控分组锚点不在该记录固化版本分组清单 → 40001。开放形态的完整链路（位置树 / 落行 / 左栏数据面）见 CReportImplantLocationIT。
     */
    @Test
    void positionGuardsRejectImplantAnchorWhileFeatureOff() {
        activateCHospitalShape();
        String nurse = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(nurse, "04");
        Map<?, ?> paperName = firstNameOf("C_PAPER_FORM", nurse);
        long recordId = createBackfillRecord(nurse, patientId, LocalDate.now().minusDays(20));

        // 创建：implant_record 位置 40301（本套 implant 开关关闭，E21-T32 起的闸门口径）
        Map<?, ?> implantRejected =
                post(
                        BASE + "/reports",
                        nurse,
                        reportBody(
                                patientId,
                                "C_PAPER_FORM",
                                str(paperName.get("id")),
                                List.of(implantLocation(1L)),
                                List.of()));
        assertEquals(40301, code(implantRejected), "implant 开关关闭植入位置 40301：" + implantRejected);

        // 创建：程控分组锚点不在该记录固化版本 → 40001
        Map<?, ?> badSection =
                post(
                        BASE + "/reports",
                        nurse,
                        reportBody(
                                patientId,
                                "C_PAPER_FORM",
                                str(paperName.get("id")),
                                List.of(sectionLocation(recordId, "not_a_section")),
                                List.of()));
        assertEquals(40001, code(badSection), "未知程控分组锚点拒绝：" + badSection);
        assertTrue(
                str(badSection.get("message")).contains("随访分组不存在"),
                "错误文案指向分组锚点：" + badSection.get("message"));

        // 修改位置：remove implant_record 同口径拒绝
        long reportId =
                createReport(
                        nurse,
                        patientId,
                        "C_PAPER_FORM",
                        str(paperName.get("id")),
                        List.of(baselineLocation("basic")),
                        List.of());
        Map<String, Object> removal = new HashMap<>();
        removal.put("add", List.of());
        removal.put("remove", List.of(implantLocation(1L)));
        Map<?, ?> removeRejected =
                put(BASE + "/reports/" + reportId + "/locations", nurse, removal);
        assertEquals(40001, code(removeRejected), "修改位置侧不在册植入位置 40001（四元组匹配）");

        // 对照：合法程控分组位置可创建（battery，V90）——同一记录同一分组在 C-ACC-34 已走全流程，此处锁定 200 形态
        long sectionReport =
                createReport(
                        nurse,
                        patientId,
                        "C_PAPER_FORM",
                        str(paperName.get("id")),
                        List.of(sectionLocation(recordId, "battery")),
                        List.of());
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                                + " AND anchor_type = 'section' AND anchor_key ="
                                                + " 'battery'",
                                        Long.class,
                                        sectionReport),
                                "合法程控分组位置落行"));
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportTranscribeIT /
    // ReportMappingIT 同口径）

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private long registeredPatient(String token, String suffix) {
        String legacyNo = LEGACY + "-" + suffix;
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "欧阳转档（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1959-03-12");
        body.put("wardId", defaultWardId());
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    private long defaultWardId() {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward ORDER BY id LIMIT 1", Long.class));
    }

    private long uploaderId(String token) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = 'nurse.demo'",
                                Long.class));
    }

    /** 补录创建一条随包 C_PROGRAM_FU 程控记录（草稿，不提交——提交由各用例按场景带 / 不带 transcribeReportId）。 */
    private long createBackfillRecord(String token, long patientId, LocalDate visitDate) {
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
        return Long.parseLong(str(data(created).get("recordId")));
    }

    /** C_PROGRAM_FU 提交必填（f303 随访日期、f304 随访时间点）；extra 并入 data（映射参数回填等）。 */
    private Map<String, Object> submitBody(
            LocalDate visitDate, int timepoint, Map<String, Object> extra) {
        Map<String, Object> data = new HashMap<>();
        data.put("f303", visitDate.toString());
        data.put("f304", timepoint);
        if (extra != null) {
            data.putAll(extra);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", 0);
        body.put("data", data);
        return body;
    }

    /** 类型下首个名称（C 预置名称字典 V94：纸质随访表「随访程控记录单」、程控「常规律控」、同意书「植入知情同意书」）。 */
    private Map<?, ?> firstNameOf(String typeCode, String token) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    private Map<String, Object> reportBody(
            long patientId,
            String typeCode,
            String nameId,
            List<Map<String, Object>> locations,
            List<Map<String, Object>> indicators) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", LocalDate.now().minusDays(35).toString());
        body.put("findings", "随访资料电子化演练（示例）");
        body.put("locations", locations);
        body.put("indicators", indicators);
        return body;
    }

    private long createReport(
            String token,
            long patientId,
            String typeCode,
            String nameId,
            List<Map<String, Object>> locations,
            List<Map<String, Object>> indicators) {
        Map<?, ?> created =
                post(
                        BASE + "/reports",
                        token,
                        reportBody(patientId, typeCode, nameId, locations, indicators));
        assertEquals(0, code(created), "创建报告单失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    private static Map<String, Object> baselineLocation(String categoryKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "baseline");
        location.put("anchorType", "category");
        location.put("anchorKey", categoryKey);
        return location;
    }

    /** 随访记录的程控分组位置（section + 分组键，V90 C_PROGRAM_FU 分类）。 */
    private static Map<String, Object> sectionLocation(long recordId, String sectionKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "followup_record");
        location.put("linkRecordId", String.valueOf(recordId));
        location.put("anchorType", "section");
        location.put("anchorKey", sectionKey);
        return location;
    }

    /** 随访记录整条位置（anchorType 为 null，HashMap 承载——Map.of 不收 null 值）。 */
    private static Map<String, Object> wholeRecordLocation(long recordId) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "followup_record");
        location.put("linkRecordId", String.valueOf(recordId));
        location.put("anchorType", null);
        return location;
    }

    /** 植入记录整条位置（anchorType 为 null；E21-T32 起开放、开关关闭 40301）。 */
    private static Map<String, Object> implantLocation(long implantId) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "implant_record");
        location.put("linkRecordId", String.valueOf(implantId));
        location.put("anchorType", null);
        return location;
    }

    private static Map<String, Object> indicator(String itemCode, String result) {
        Map<String, Object> item = new HashMap<>();
        item.put("itemCode", itemCode);
        item.put("result", result);
        return item;
    }

    private static Map<?, ?> mappingOf(Map<?, ?> preview, String targetType) {
        return (Map<?, ?>)
                ((List<?>) preview.get("mappings"))
                        .stream()
                                .filter(
                                        item ->
                                                targetType.equals(
                                                        ((Map<?, ?>) item).get("targetType")))
                                .findFirst()
                                .orElseThrow(
                                        () -> new AssertionError("预览缺少 " + targetType + " 映射"));
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
