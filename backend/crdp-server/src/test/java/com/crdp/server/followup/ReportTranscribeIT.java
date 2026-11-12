package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
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
 * E21-T17 端到端 / C-SCAN-012、C-FU-017（完整应用 + 真实 MySQL，HTTP 直连）。验收原文： 转录后电子化状态自动更新。
 *
 * <p>场景（demoa 租户 + 临时打开 {@code scan_progress} 开关，模拟医院 C 形态）：
 *
 * <ol>
 *   <li>转录态提交（{@code POST /records/{id}/submit} 带 {@code transcribeReportId}）：报告单 digitize_status 置
 *       DONE、version + 1，写 RPT_TRANSCRIBE / RPT_LOCATION_CHANGE 操作日志， 响应回带 transcribedReportId；
 *   <li>位置中没有该随访记录（报告单只挂基线位置）→ 提交后自动插入「整条记录」位置行（C-FU-017 「保存后该记录自动加入扫描件的位置」），患者内清单位置标签随之出现；
 *   <li>普通提交（不带 transcribeReportId）不改电子化状态；
 *   <li>开关 {@code scan_progress} 关闭（医院 A / B 形态）→ 转录态提交 40301，报告单状态不动。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（病案号 IT-E21T17- 前缀、 A_ANNUAL_FU
 * 表单）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportTranscribeIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E21T17";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    // 开关回关（A 租户形态；种子 V100 默认 0）
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'scan_progress'");
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
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE action IN ('RPT_TRANSCRIBE')");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action = 'RPT_LOCATION_CHANGE'"
                                    + " AND detail LIKE '%转录%'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    /** 转录态提交：状态自动置 DONE、version + 1、写操作日志、响应回带 transcribedReportId。 */
    @Test
    void transcribeSubmitMarksReportDigitized() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'scan_progress'"));
        String doctor = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(doctor, "周转录（示例）");
        Map<?, ?> name = labName(doctor);
        long recordId = backfillRecord(doctor, patientId, LocalDate.now().minusDays(30));
        long reportId = createReport(doctor, patientId, str(name.get("id")), recordId, null);
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                                + " AND link_type = 'followup_record' AND link_record_id = ?",
                                        Long.class,
                                        reportId,
                                        recordId),
                                "造数：报告单位置已挂该记录（整条）"));

        Map<String, Object> body = submitBody(LocalDate.now().minusDays(30), reportId);
        Map<?, ?> submitted = post(BASE + "/records/" + recordId + "/submit", doctor, body);
        assertEquals(0, code(submitted), "转录态提交失败：" + submitted);
        assertEquals(
                String.valueOf(reportId),
                str(data(submitted).get("transcribedReportId")),
                "结果回带 transcribedReportId（前端提示口径）");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "DONE",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "转录后电子化状态自动更新（验收位）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT version FROM rpt_report WHERE id = ?",
                                            Integer.class,
                                            reportId)
                                    >= 1,
                            "状态落列推进 version");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_TRANSCRIBE' AND object_type ="
                                                    + " 'REPORT' AND object_id = ?"
                                                    + " AND result = 'SUCCESS'",
                                            Long.class,
                                            String.valueOf(reportId))
                                    >= 1,
                            "写 RPT_TRANSCRIBE 操作日志");
                });
    }

    /** 位置中没有随访记录（只挂基线位置）：提交后自动插入「整条记录」位置行， 患者内清单位置标签出现（C-FU-017）。 */
    @Test
    void transcribeSubmitAddsRecordLocationWhenMissing() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'scan_progress'"));
        String doctor = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(doctor, "吴转录（示例）");
        Map<?, ?> name = labName(doctor);
        long recordId = backfillRecord(doctor, patientId, LocalDate.now().minusDays(40));
        // 只挂基线位置（模拟「位置中没有随访记录」的纸质随访表：新建补录记录并加入位置）
        long reportId = createReport(doctor, patientId, str(name.get("id")), null, "basic");
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                0L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                                + " AND link_type = 'followup_record'",
                                        Long.class,
                                        reportId),
                                "造数：报告单位置中没有随访记录"));

        Map<String, Object> body = submitBody(LocalDate.now().minusDays(40), reportId);
        assertEquals(
                0, code(post(BASE + "/records/" + recordId + "/submit", doctor, body)), "转录态提交失败");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'followup_record'"
                                            + " AND link_record_id = ? AND anchor_type IS NULL",
                                    Long.class,
                                    reportId,
                                    recordId),
                            "记录自动加入报告单位置（整条记录行）");
                    assertEquals(
                            "DONE",
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "同时置已电子化");
                });
        // 患者内清单：位置标签出现（前端「从扫描件跳转到该随访记录」的数据源）
        Map<?, ?> reports = get(BASE + "/patients/" + patientId + "/reports?scope=all", doctor);
        assertEquals(0, code(reports));
        List<?> items = (List<?>) data(reports).get("items");
        assertEquals(1, items.size());
        List<?> locations = (List<?>) ((Map<?, ?>) items.get(0)).get("locations");
        assertTrue(
                locations.stream()
                        .anyMatch(
                                tag ->
                                        "followup_record"
                                                        .equals(
                                                                str(
                                                                        ((Map<?, ?>) tag)
                                                                                .get("linkType")))
                                                && String.valueOf(recordId)
                                                        .equals(
                                                                str(
                                                                        ((Map<?, ?>) tag)
                                                                                .get(
                                                                                        "linkRecordId")))),
                "清单位置标签含该随访记录：" + locations);
    }

    /** 普通提交（不带 transcribeReportId）不改电子化状态（普通对照录入 ≠ 转录）。 */
    @Test
    void plainSubmitDoesNotTouchDigitizeStatus() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'scan_progress'"));
        String doctor = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(doctor, "郑对照（示例）");
        Map<?, ?> name = labName(doctor);
        long recordId = backfillRecord(doctor, patientId, LocalDate.now().minusDays(15));
        long reportId = createReport(doctor, patientId, str(name.get("id")), recordId, null);

        Map<?, ?> submitted =
                post(
                        BASE + "/records/" + recordId + "/submit",
                        doctor,
                        submitBody(LocalDate.now().minusDays(15), null));
        assertEquals(0, code(submitted));
        assertNull(data(submitted).get("transcribedReportId"), "普通提交不回带转录报告单");
        runInTenant(
                "demoa",
                () ->
                        assertNull(
                                jdbc.queryForObject(
                                        "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                        String.class,
                                        reportId),
                                "普通对照录入不改电子化状态"));
    }

    /** 开关 scan_progress 关闭（医院 A / B 形态）→ 转录态提交 40301、报告单状态不动。 */
    @Test
    void transcribeSubmitRejectedWhenFeatureOff() {
        String doctor = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient(doctor, "冯开关（示例）");
        Map<?, ?> name = labName(doctor);
        long recordId = backfillRecord(doctor, patientId, LocalDate.now().minusDays(10));
        long reportId = createReport(doctor, patientId, str(name.get("id")), recordId, null);

        Map<String, Object> body = submitBody(LocalDate.now().minusDays(10), reportId);
        Map<?, ?> submitted = post(BASE + "/records/" + recordId + "/submit", doctor, body);
        assertEquals(40301, code(submitted), "开关关闭 40301：" + submitted);
        runInTenant(
                "demoa",
                () -> {
                    assertNull(
                            jdbc.queryForObject(
                                    "SELECT digitize_status FROM rpt_report WHERE id = ?",
                                    String.class,
                                    reportId),
                            "状态不动");
                    assertEquals(
                            "DRAFT",
                            jdbc.queryForObject(
                                    "SELECT status FROM fu_record WHERE id = ?",
                                    String.class,
                                    recordId),
                            "事务回滚：记录仍是 DRAFT");
                });
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportSearchIT 同口径）

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private long registeredPatient(String token, String name) {
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", name);
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
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

    private long backfillRecord(String token, long patientId, LocalDate visitDate) {
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
                                "A_ANNUAL_FU"));
        assertEquals(0, code(created), "历史补录失败：" + created);
        return Long.parseLong(str(data(created).get("recordId")));
    }

    /** LAB 首个绑定模板的名称（血常规，A 预置 V62）。 */
    private Map<?, ?> labName(String token) {
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    /** 建报告单：recordId 非空挂「整条记录」位置；categoryKey 非空挂基线位置（二者可同时为 null 的另一组合不测）。 */
    private long createReport(
            String token, long patientId, String nameId, Long recordId, String categoryKey) {
        java.util.ArrayList<Map<String, Object>> locations = new java.util.ArrayList<>();
        if (categoryKey != null) {
            locations.add(
                    Map.of(
                            "linkType",
                            "baseline",
                            "anchorType",
                            "category",
                            "anchorKey",
                            categoryKey));
        }
        if (recordId != null) {
            Map<String, Object> whole = new HashMap<>();
            whole.put("linkType", "followup_record");
            whole.put("linkRecordId", String.valueOf(recordId));
            whole.put("anchorType", null);
            locations.add(whole);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "LAB");
        body.put("nameId", nameId);
        body.put("reportDate", LocalDate.now().minusDays(35).toString());
        body.put("findings", "各腔室内径正常（示例）");
        body.put("conclusion", "未见明显异常（示例）");
        body.put("locations", locations);
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, code(created), "创建报告单失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** A_ANNUAL_FU 提交必填（f214 随访日期、f215 是否死亡、f213 已加微信）；transcribeReportId 非空即转录态。 */
    private Map<String, Object> submitBody(LocalDate visitDate, Long transcribeReportId) {
        Map<String, Object> data = new HashMap<>();
        data.put("f214", visitDate.toString());
        data.put("f215", 0);
        data.put("f213", 1);
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", 0);
        body.put("data", data);
        if (transcribeReportId != null) {
            body.put("transcribeReportId", transcribeReportId);
        }
        return body;
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
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
