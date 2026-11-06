package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.junit.jupiter.api.AfterEach;
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
 * E16-T07 端到端 / A-ATT-013（验收原文「映射写入留痕，冲突提示」；C-SCAN-005 同一机制）： 心脏超声报告单 → 结构化字段映射写入的预览、确认、留痕与冲突（完整应用
 * + 真实 MySQL，HTTP 直连）。
 *
 * <p>覆盖：预览（候选目标 = 位置中的随访记录 / 基线超声检查分类、值转换、附带写入、conflict 提示、 时相边界与基线锁定 blockedReason）；确认（fieldKeys
 * 逐项确认覆盖、未确认冲突字段跳过、版本冲突 40900、 基线锁定 40901、写 fe_field_history（source=REPORT）与 RPT_MAPPING_WRITE 审计）；
 * 无映射类型空清单；无上传权限 40300、跨租户 40400。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportMappingIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E16T07";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE h FROM fe_field_history h"
                                        + " LEFT JOIN fu_record r ON r.id = h.record_id AND h.record_type = 'FU_RECORD'"
                                        + " LEFT JOIN pt_baseline_section s ON s.id = h.record_id"
                                        + " AND h.record_type = 'PT_BASELINE_SECTION'"
                                        + " WHERE (h.record_type = 'FU_RECORD' AND r.patient_id IN"
                                        + "   (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))"
                                        + "    OR (h.record_type = 'PT_BASELINE_SECTION' AND s.patient_id IN"
                                        + "   (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))",
                                LEGACY + "-%",
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
                                "DELETE FROM pt_baseline_section WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM sys_file WHERE original_name LIKE 'IT-E16T07-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action IN ('RPT_CREATE','RPT_MAPPING_WRITE','BASELINE_LOCK','BASELINE_UNLOCK')");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 随访记录映射（A-ATT-013 第 N 年随访列）

    /** 预览 → 确认写入 fu_record：复查超声置为是、超声检查时间 = 报告时间、指标转类型；留字段历史与审计。 */
    @Test
    void echoReportMapsIntoFollowupRecordWithHistoryAndAudit() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        Map<?, ?> name = firstNameOf("ECHO", admin);
        long reportId =
                createEchoReport(
                        admin,
                        patientId,
                        str(name.get("id")),
                        "2026-06-08",
                        followupLocation(recordId, null),
                        List.of(indicator("EF", "55"), indicator("MR_DEGREE", "少量")));

        Map<?, ?> preview =
                data(post(BASE + "/reports/" + reportId + "/mapping/preview", admin, Map.of()));
        List<?> mappings = (List<?>) preview.get("mappings");
        assertEquals(1, mappings.size(), "位置只有随访记录 → 只有 ECHO_FOLLOWUP 映射");
        Map<?, ?> mapping = (Map<?, ?>) mappings.get(0);
        assertEquals("FOLLOWUP_RECORD", mapping.get("targetType"));
        assertNullSafe(mapping.get("blockedReason"));
        List<?> candidates = (List<?>) mapping.get("candidates");
        assertEquals(1, candidates.size(), "位置里 1 条记录 → 1 个候选");
        Map<?, ?> candidate = (Map<?, ?>) candidates.get(0);
        assertEquals(str(recordId), str(candidate.get("linkRecordId")));
        int targetVersion = ((Number) candidate.get("targetVersion")).intValue();
        List<?> writes = (List<?>) candidate.get("writes");
        assertTrue(writes.size() >= 5, "报告时间 + 指标 + 附带写入（复查超声 / 有无二尖瓣返流）：" + writes);
        assertTrue(
                writes.stream()
                        .anyMatch(
                                write ->
                                        "f236".equals(((Map<?, ?>) write).get("fieldKey"))
                                                && Integer.valueOf(1)
                                                        .equals(
                                                                ((Map<?, ?>) write)
                                                                        .get("newValue"))),
                "映射级附带写入：复查超声 = 是");
        assertTrue(
                writes.stream()
                        .anyMatch(
                                write ->
                                        "f239".equals(((Map<?, ?>) write).get("fieldKey"))
                                                && Integer.valueOf(2)
                                                        .equals(
                                                                ((Map<?, ?>) write)
                                                                        .get("newValue"))),
                "「少量」→ 编码 2");

        List<String> fieldKeys = new ArrayList<>();
        for (Object write : writes) {
            Map<?, ?> row = (Map<?, ?>) write;
            if (row.get("error") == null) {
                fieldKeys.add(str(row.get("fieldKey")));
            }
        }
        Map<?, ?> confirmResponse =
                post(
                        BASE + "/reports/" + reportId + "/mapping/confirm",
                        admin,
                        confirmBody(mapping, candidate, fieldKeys));
        assertEquals(0, code(confirmResponse), "确认写入失败：" + confirmResponse);
        assertEquals(
                targetVersion + 1,
                ((Number) data(confirmResponse).get("targetVersion")).intValue(),
                "写入后 version + 1");

        runInTenant(
                "demoa",
                () -> {
                    // fu_record.data 是 JSON 列（MySQL 归一化格式），按 JSON_EXTRACT 断言值
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f236') FROM fu_record WHERE id = ?",
                                    Integer.class,
                                    recordId),
                            "复查超声置为是（映射级附带写入）");
                    assertEquals(
                            55,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f248') FROM fu_record WHERE id = ?",
                                    Integer.class,
                                    recordId),
                            "EF 写入（字符串结果转数值）");
                    assertEquals(
                            "2026-06-08",
                            jdbc.queryForObject(
                                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(data, '$.f237'))"
                                            + " FROM fu_record WHERE id = ?",
                                    String.class,
                                    recordId),
                            "超声检查时间 = 报告时间（REPORT_DATE 行）");
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f239') FROM fu_record WHERE id = ?",
                                    Integer.class,
                                    recordId),
                            "二尖瓣返流程度 = 少量(2)（选项名转编码）");
                    assertEquals(
                            targetVersion + 1,
                            jdbc.queryForObject(
                                    "SELECT version FROM fu_record WHERE id = ?",
                                    Integer.class,
                                    recordId),
                            "写入后 version + 1");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_field_history"
                                                    + " WHERE record_type = 'FU_RECORD' AND record_id = ?"
                                                    + " AND source = 'REPORT'",
                                            Long.class,
                                            recordId)
                                    >= 4,
                            "映射写入留痕（来源 = 报告单）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action = 'RPT_MAPPING_WRITE'",
                                            Long.class)
                                    >= 1,
                            "写 RPT_MAPPING_WRITE 审计");
                });

        // 冲突提示：第二份报告 EF=60（同日期，只 EF 一处冲突），预览 conflict=true；确认不勾 EF → 跳过并保留原值
        long second =
                createEchoReport(
                        admin,
                        patientId,
                        str(name.get("id")),
                        "2026-06-08",
                        followupLocation(recordId, null),
                        List.of(indicator("EF", "60")));
        Map<?, ?> secondPreview =
                data(post(BASE + "/reports/" + second + "/mapping/preview", admin, Map.of()));
        Map<?, ?> secondCandidate = firstCandidateOf(secondPreview, "FOLLOWUP_RECORD");
        Map<?, ?> efWrite =
                (Map<?, ?>)
                        ((List<?>) secondCandidate.get("writes"))
                                .stream()
                                        .filter(
                                                write ->
                                                        "f248"
                                                                .equals(
                                                                        ((Map<?, ?>) write)
                                                                                .get("fieldKey")))
                                        .findFirst()
                                        .orElseThrow();
        assertTrue((Boolean) efWrite.get("conflict"), "EF 60 vs 原值 55 → conflict 提示");
        assertEquals(55, ((Number) efWrite.get("oldValue")).intValue());

        List<String> withoutEf = new ArrayList<>();
        for (Object write : (List<?>) secondCandidate.get("writes")) {
            Map<?, ?> row = (Map<?, ?>) write;
            if (row.get("error") == null && !Boolean.TRUE.equals(row.get("conflict"))) {
                withoutEf.add(str(row.get("fieldKey")));
            }
        }
        Map<?, ?> skipResponse =
                post(
                        BASE + "/reports/" + second + "/mapping/confirm",
                        admin,
                        confirmBody(secondMappingOf(secondPreview), secondCandidate, withoutEf));
        assertEquals(0, code(skipResponse), "跳过冲突字段的确认应成功：" + skipResponse);
        assertEquals(
                List.of("f248"),
                (List<?>) data(skipResponse).get("skippedConflicts"),
                "未确认的冲突字段跳过并回告");
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                55,
                                jdbc.queryForObject(
                                        "SELECT JSON_EXTRACT(data, '$.f248') FROM fu_record WHERE id = ?",
                                        Integer.class,
                                        recordId),
                                "未确认覆盖 → 保留原值 55"));

        // 版本过期 40900（乐观锁）：targetVersion 已被前一次确认 +1，重用预览旧值
        Map<String, Object> staleBody = new HashMap<>();
        staleBody.put("mappingId", str(secondMappingOf(secondPreview).get("mappingId")));
        staleBody.put("linkRecordId", str(secondCandidate.get("linkRecordId")));
        staleBody.put("targetVersion", targetVersion);
        staleBody.put("fieldKeys", List.of("f248"));
        assertEquals(
                40900,
                code(post(BASE + "/reports/" + second + "/mapping/confirm", admin, staleBody)),
                "预览版本过期后确认 → 40900（冲突提示走重新预览）");

        // 确认覆盖后写入 60 并再留一条 REPORT 历史（旧值 55 → 新值 60）
        Map<?, ?> thirdPreview =
                data(post(BASE + "/reports/" + second + "/mapping/preview", admin, Map.of()));
        Map<?, ?> thirdCandidate = firstCandidateOf(thirdPreview, "FOLLOWUP_RECORD");
        Map<String, Object> overwrite = new HashMap<>();
        overwrite.put("mappingId", str(secondMappingOf(thirdPreview).get("mappingId")));
        overwrite.put("linkRecordId", str(thirdCandidate.get("linkRecordId")));
        overwrite.put("targetVersion", ((Number) thirdCandidate.get("targetVersion")).intValue());
        overwrite.put("fieldKeys", List.of("f248"));
        assertEquals(
                0,
                code(post(BASE + "/reports/" + second + "/mapping/confirm", admin, overwrite)),
                "逐项确认覆盖冲突字段");
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                55.0,
                                ((Number)
                                                jdbc.queryForObject(
                                                        "SELECT JSON_EXTRACT(old_value, '$')"
                                                                + " FROM fe_field_history"
                                                                + " WHERE record_type = 'FU_RECORD'"
                                                                + " AND record_id = ? AND field_key = 'f248'"
                                                                + " AND source = 'REPORT'"
                                                                + " ORDER BY id DESC LIMIT 1",
                                                        Number.class,
                                                        recordId))
                                        .doubleValue(),
                                "字段历史留旧值 55"));
    }

    // ---------------------------------------------------------------- 基线映射（术前 / 出院前 + 边界与锁定）

    /** 基线映射：预览两条（术前 / 出院前）、确认建「超声检查」分节并留痕；报告时间晚于出院日期 blockedReason + 确认 40001；锁定 40901。 */
    @Test
    void echoReportMapsIntoBaselineSectionWithGuards() {
        String admin = hospitalToken("demoa");
        LocalDate surgery = LocalDate.now().minusDays(40);
        LocalDate discharge = LocalDate.now().minusDays(35);
        long patientId = registeredPatient(admin, surgery, discharge);
        String echoCategory = baselineEchoCategoryKey(admin, patientId);
        Map<?, ?> name = firstNameOf("ECHO", admin);
        long reportId =
                createEchoReport(
                        admin,
                        patientId,
                        str(name.get("id")),
                        surgery.minusDays(3).toString(),
                        baselineLocation(echoCategory),
                        List.of(indicator("EF", "52"), indicator("MR_DEGREE", "中量")));

        Map<?, ?> preview =
                data(post(BASE + "/reports/" + reportId + "/mapping/preview", admin, Map.of()));
        List<?> mappings = (List<?>) preview.get("mappings");
        assertEquals(2, mappings.size(), "位置含基线超声检查分类 → 术前 / 出院前两条映射（用户选时相）");
        Map<?, ?> preop =
                (Map<?, ?>)
                        mappings.stream()
                                .filter(
                                        item ->
                                                "PREOP_LAST"
                                                        .equals(((Map<?, ?>) item).get("phase")))
                                .findFirst()
                                .orElseThrow();
        assertNullSafe(preop.get("blockedReason"));
        Map<?, ?> candidate =
                ((List<?>) preop.get("candidates")).isEmpty()
                        ? null
                        : (Map<?, ?>) ((List<?>) preop.get("candidates")).get(0);
        assertNotNull(candidate, "基线候选（分节可不存在，targetVersion 0）");
        assertEquals(0, ((Number) candidate.get("targetVersion")).intValue(), "分节未建 → 0");
        List<String> fieldKeys = new ArrayList<>();
        for (Object write : (List<?>) candidate.get("writes")) {
            Map<?, ?> row = (Map<?, ?>) write;
            if (row.get("error") == null) {
                fieldKeys.add(str(row.get("fieldKey")));
            }
        }
        Map<String, Object> confirm = new HashMap<>();
        confirm.put("mappingId", str(preop.get("mappingId")));
        confirm.put("targetVersion", 0);
        confirm.put("fieldKeys", fieldKeys);
        assertEquals(
                0,
                code(post(BASE + "/reports/" + reportId + "/mapping/confirm", admin, confirm)),
                "确认写入基线（术前最后一次）");

        runInTenant(
                "demoa",
                () -> {
                    // data 为 JSON 列（MySQL 归一化格式），按 JSON_EXTRACT 断言值
                    assertEquals(
                            52,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f083') FROM pt_baseline_section"
                                            + " WHERE patient_id = ? AND category_key = ?",
                                    Integer.class,
                                    patientId,
                                    echoCategory),
                            "术前 EF");
                    assertEquals(
                            surgery.minusDays(3).toString(),
                            jdbc.queryForObject(
                                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(data, '$.f079'))"
                                            + " FROM pt_baseline_section"
                                            + " WHERE patient_id = ? AND category_key = ?",
                                    String.class,
                                    patientId,
                                    echoCategory),
                            "术前最后一次超声时间 = 报告时间（REPORT_DATE 行）");
                    assertEquals(
                            3,
                            jdbc.queryForObject(
                                    "SELECT JSON_EXTRACT(data, '$.f097') FROM pt_baseline_section"
                                            + " WHERE patient_id = ? AND category_key = ?",
                                    Integer.class,
                                    patientId,
                                    echoCategory),
                            "二尖瓣反流 = 中量(3)（选项名转编码）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_field_history"
                                                    + " WHERE record_type = 'PT_BASELINE_SECTION'"
                                                    + " AND field_key = 'f083' AND source = 'REPORT'",
                                            Long.class)
                                    >= 1,
                            "基线映射同样留字段历史（来源 = 报告单）");
                });

        // 报告时间晚于出院日期：预览 blockedReason + 确认 40001（V64 remark：写入侧校验）
        long late =
                createEchoReport(
                        admin,
                        patientId,
                        str(name.get("id")),
                        discharge.plusDays(2).toString(),
                        baselineLocation(echoCategory),
                        List.of(indicator("EF", "58")));
        Map<?, ?> latePreview =
                data(post(BASE + "/reports/" + late + "/mapping/preview", admin, Map.of()));
        for (Object item : (List<?>) latePreview.get("mappings")) {
            Map<?, ?> row = (Map<?, ?>) item;
            assertTrue(
                    str(row.get("blockedReason")).contains("手术日期")
                            || str(row.get("blockedReason")).contains("出院日期"),
                    "时相边界拦截提示：" + row.get("blockedReason"));
            Map<String, Object> rejected = new HashMap<>();
            rejected.put("mappingId", str(row.get("mappingId")));
            rejected.put("targetVersion", 0);
            rejected.put("fieldKeys", List.of("f083"));
            assertEquals(
                    40001,
                    code(post(BASE + "/reports/" + late + "/mapping/confirm", admin, rejected)),
                    "确认被时相边界拦截");
        }

        // 基线锁定：预览 blockedReason + 确认 40901（A-PAT-006 锁定后只读）
        Map<?, ?> locked =
                data(
                        post(
                                BASE + "/patients/" + patientId + "/lock",
                                admin,
                                Map.of("locked", true, "reason", "IT 归档（示例）")));
        assertTrue((Boolean) locked.get("locked"), "锁定成功");
        long onTime =
                createEchoReport(
                        admin,
                        patientId,
                        str(name.get("id")),
                        surgery.minusDays(1).toString(),
                        baselineLocation(echoCategory),
                        List.of(indicator("EF", "53")));
        Map<?, ?> lockedPreview =
                data(post(BASE + "/reports/" + onTime + "/mapping/preview", admin, Map.of()));
        for (Object item : (List<?>) lockedPreview.get("mappings")) {
            assertTrue(
                    str(((Map<?, ?>) item).get("blockedReason")).contains("基线已锁定"),
                    "锁定拦截提示：" + ((Map<?, ?>) item).get("blockedReason"));
            Map<String, Object> rejected = new HashMap<>();
            rejected.put("mappingId", str(((Map<?, ?>) item).get("mappingId")));
            rejected.put("targetVersion", 0);
            rejected.put("fieldKeys", List.of("f083"));
            assertEquals(
                    40901,
                    code(post(BASE + "/reports/" + onTime + "/mapping/confirm", admin, rejected)),
                    "基线锁定确认 40901");
        }
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 无映射类型

    /** 无上传权限 40300；跨租户报告单 40400；无映射类型空清单（前端隐藏映射步骤）。 */
    @Test
    void guardsAndTypeWithoutMapping() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long patientId = registeredPatient("demoa", admin);
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        Map<?, ?> echoName = firstNameOf("ECHO", admin);
        long reportId =
                createEchoReport(
                        admin,
                        patientId,
                        str(echoName.get("id")),
                        "2026-06-08",
                        followupLocation(recordId, null),
                        List.of(indicator("EF", "55")));

        assertEquals(
                40300,
                code(post(BASE + "/reports/" + reportId + "/mapping/preview", doctor, Map.of())),
                "FOLLOWUP_DOCTOR 无 followup:scan:upload → 40300");
        assertEquals(
                40300,
                code(
                        post(
                                BASE + "/reports/" + reportId + "/mapping/confirm",
                                doctor,
                                Map.of("mappingId", "1", "targetVersion", 0))),
                "确认同样 40300");

        String tenantB = hospitalToken("demob");
        assertEquals(
                40400,
                code(post(BASE + "/reports/" + reportId + "/mapping/preview", tenantB, Map.of())),
                "跨租户报告单 40400（独立租户库）");

        // 无映射类型（检验）：空清单而不是报错
        Map<?, ?> labName = firstNameOf("LAB", admin);
        long labReport =
                createReport(
                        admin,
                        patientId,
                        "LAB",
                        str(labName.get("id")),
                        "2026-06-08",
                        followupLocation(recordId, null),
                        List.of(indicator("WBC", "6.1")));
        Map<?, ?> labPreview =
                data(post(BASE + "/reports/" + labReport + "/mapping/preview", admin, Map.of()));
        assertTrue(((List<?>) labPreview.get("mappings")).isEmpty(), "检验类型无映射配置");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportIT 同口径）

    private long registeredPatient(String tenant, String token) {
        return registeredPatient(
                token,
                LocalDate.now().minusDays(23),
                LocalDate.now().minusDays(22),
                LocalDate.now().minusDays(20));
    }

    private long registeredPatient(String token, LocalDate surgery, LocalDate discharge) {
        return registeredPatient(token, surgery.minusDays(1), surgery, discharge);
    }

    private long registeredPatient(
            String token, LocalDate admission, LocalDate surgery, LocalDate discharge) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周映射（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId());
        body.put("admissionDate", admission.toString());
        body.put("surgeryDate", surgery.toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    private long backfillRecord(String token, long patientId, String visitDate) {
        Map<?, ?> created =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate,
                                "formCode",
                                "A_ANNUAL_FU"));
        assertEquals(0, code(created), "历史补录失败：" + created);
        return Long.parseLong(str(data(created).get("recordId")));
    }

    /** ECHO 首个名称（经胸超声心动图，V64 绑定 18 项指标模板）。 */
    private Map<?, ?> firstNameOf(String typeCode, String token) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    private long createEchoReport(
            String token,
            long patientId,
            String nameId,
            String reportDate,
            Map<String, Object> location,
            List<Map<String, Object>> indicators) {
        return createReport(token, patientId, "ECHO", nameId, reportDate, location, indicators);
    }

    private long createReport(
            String token,
            long patientId,
            String typeCode,
            String nameId,
            String reportDate,
            Map<String, Object> location,
            List<Map<String, Object>> indicators) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", reportDate);
        body.put("findings", "各腔室内径正常（示例）");
        body.put("locations", List.of(location));
        body.put("indicators", indicators);
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, code(created), "创建报告单失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    private static Map<String, Object> followupLocation(long recordId, String sectionKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "followup_record");
        location.put("linkRecordId", String.valueOf(recordId));
        location.put("anchorType", sectionKey == null ? null : "section");
        location.put("anchorKey", sectionKey);
        return location;
    }

    private static Map<String, Object> baselineLocation(String categoryKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "baseline");
        location.put("anchorType", "category");
        location.put("anchorKey", categoryKey);
        return location;
    }

    private static Map<String, Object> indicator(String itemCode, String result) {
        Map<String, Object> item = new HashMap<>();
        item.put("itemCode", itemCode);
        item.put("result", result);
        return item;
    }

    private static Map<String, Object> confirmBody(
            Map<?, ?> mapping, Map<?, ?> candidate, List<String> fieldKeys) {
        Map<String, Object> body = new HashMap<>();
        body.put("mappingId", str(mapping.get("mappingId")));
        body.put(
                "linkRecordId",
                candidate.get("linkRecordId") == null ? null : str(candidate.get("linkRecordId")));
        body.put("targetVersion", ((Number) candidate.get("targetVersion")).intValue());
        body.put("fieldKeys", fieldKeys);
        return body;
    }

    private static Map<?, ?> firstCandidateOf(Map<?, ?> preview, String targetType) {
        return (Map<?, ?>) ((List<?>) mappingOf(preview, targetType).get("candidates")).get(0);
    }

    private static Map<?, ?> secondMappingOf(Map<?, ?> preview) {
        return mappingOf(preview, "FOLLOWUP_RECORD");
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
                                .orElseThrow();
    }

    /** 基线「超声检查」分类锚点（位置树按显示名定位，V38 分类 4）。 */
    private String baselineEchoCategoryKey(String token, long patientId) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baseline =
                (Map<?, ?>)
                        roots.stream()
                                .filter(node -> "baseline".equals(((Map<?, ?>) node).get("key")))
                                .findFirst()
                                .orElseThrow();
        return (String)
                ((List<?>) baseline.get("children"))
                        .stream()
                                .filter(node -> str(((Map<?, ?>) node).get("title")).contains("超声"))
                                .map(
                                        node ->
                                                ((Map<?, ?>) ((Map<?, ?>) node).get("value"))
                                                        .get("anchorKey"))
                                .findFirst()
                                .orElseThrow();
    }

    private static void assertNullSafe(Object value) {
        assertTrue(value == null, "应为空：" + value);
    }

    private long defaultWardId() {
        return runInTenant(
                "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
