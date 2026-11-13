package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * E21-T22 端到端（验收原文：功能开关关闭时 A、B 不受影响；C-PAT-010 概览顶部器械卡片）。场景：
 *
 * <ol>
 *   <li>器械卡片（demoa 开 implant / eri_cycle / scan_progress）：概览顶部下发当前器械（类型显示名取业务字典）、 植入时长（植入日 →
 *       今天）、ERI 标记（最近一条含 f323 / f324 的已提交随访记录）与本患者待录入扫描件数 （在册且 PENDING 的报告单数）；
 *   <li>分开关（demob / demoa 关 eri_cycle）：eri_cycle 关闭只隐藏 ERI 段，器械行照常；
 *   <li>验收位：demob 三个开关全关（种子默认），即使库里存在在用植入记录、含 ERI 字段的已提交记录与待录入报告单， 概览 device / pendingScanCount 均为
 *       null——A / B 概览行为与 E21 前完全一致。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T22-）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientDeviceCardIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T22";
    private static final String BASE = "/api/hospital/followup";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE r FROM rpt_report_link r JOIN rpt_report rr ON rr.id ="
                                        + " r.report_id JOIN pt_patient p ON p.id = rr.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE rr FROM rpt_report rr JOIN pt_patient p ON p.id ="
                                        + " rr.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
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
                        jdbc.update(
                                "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                                        + " (SELECT id FROM pt_patient)");
                        jdbc.update(
                                "DELETE FROM pt_status_history WHERE patient_id NOT IN"
                                        + " (SELECT id FROM pt_patient)");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        // IT 期间开启的开关还原为种子默认（关闭），A / B 租户不受影响
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code IN"
                                        + " ('implant', 'eri_cycle', 'scan_progress')");
                        // 与 PatientOverviewIT 同口径：单计划世界（关 A 预置计划自动入组）
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 器械卡片（C-PAT-010 概览顶部）

    @Test
    void deviceCardShowsCurrentDeviceDurationEriAndPendingScans() {
        enableFeatures("demoa", true, true, true);
        String doctor = hospitalToken("demoa", "doctor.demo");
        String nurse = hospitalToken("demoa", "nurse.demo");
        // C 登记：患者 + 首条植入记录（CRT-D，2025-06-10）同事务落库
        long patientId =
                registerWithImplant(nurse, "IT-MRN-E21T22-01", "王器械（示例）", "2025-06-10", "CRT_D");
        // 最近一条含 ERI 字段的已提交随访记录：f323 = 1 接近ERI（直接造数，键契约 OQ-130①）
        insertSubmittedEriRecord("demoa", patientId, "{\"f323\":1,\"f324\":3.5}");
        // 一份在册「待录入」报告单（C-SCAN-011；直接造数——报告内容与概览计数无关）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO rpt_report (patient_id, type_code, type_name,"
                                        + " report_date, findings, conclusion, digitize_status,"
                                        + " status, created_by) VALUES (?, 'LAB', '检验报告（示例）',"
                                        + " CURDATE(), '示例所见（虚构）', '未见明显异常（示例）',"
                                        + " 'PENDING', 'NORMAL', 2)",
                                patientId));

        Map<?, ?> summary = get(BASE + "/patients/" + patientId + "/summary", doctor);

        assertEquals(0, codeOf(summary), "概览失败：" + summary);
        Map<?, ?> data = data(summary);
        Map<?, ?> device = (Map<?, ?>) data.get("device");
        assertNotNull(device, "implant 开启：概览顶部带器械卡片");
        assertEquals("CRT-D", device.get("typeLabel"), "类型显示名取业务字典 pacemaker_type");
        assertEquals(expectedDuration("2025-06-10"), device.get("implantDuration"), "植入时长文案");
        assertEquals("接近ERI", device.get("eri"), "ERI 标记取最近含 ERI 字段的已提交记录");
        assertEquals(1, ((Number) data.get("pendingScanCount")).intValue(), "本患者待录入扫描件数");
    }

    /** eri_cycle 关闭：器械行照常、ERI 段隐藏（components.md §4「eri_cycle 关闭时不显示 ERI」）。 */
    @Test
    void deviceCardKeepsDeviceRowButHidesEriWhenEriCycleOff() {
        enableFeatures("demoa", true, false, true);
        String doctor = hospitalToken("demoa", "doctor.demo");
        long patientId =
                registerWithImplant(doctor, "IT-MRN-E21T22-02", "李器械（示例）", "2024-01-15", "DDD");
        insertSubmittedEriRecord("demoa", patientId, "{\"f323\":2}");

        Map<?, ?> data = data(get(BASE + "/patients/" + patientId + "/summary", doctor));

        Map<?, ?> device = (Map<?, ?>) data.get("device");
        assertNotNull(device, "器械行不随 eri_cycle 隐藏");
        assertEquals("双腔起搏器(DDD)", device.get("typeLabel"), "字典显示名（业务字典 V70）");
        assertNull(device.get("eri"), "eri_cycle 关闭：ERI 段不下发（前端隐藏）");
        assertNotNull(data.get("pendingScanCount"), "scan_progress 开启：待录入扫描件数照常（0 亦下发）");
    }

    // ---------------------------------------------------------------- 验收位：功能开关关闭时 A、B 不受影响

    /** demob 三个开关全关（种子默认）：库里即使有在用器械 / ERI 记录 / 待录入报告单，概览也不带 C 段。 */
    @Test
    void switchesOffKeepOverviewIdenticalToPreE21ForTenantB() {
        String doctor = hospitalToken("demob", "nurse.demo");
        long patientId = registerPlain(doctor, "demob", "IT-MRN-E21T22-03", "赵普通（示例）");
        // 造出「若开关开启就会显示」的数据面：在用植入记录 + 含 ERI 字段的已提交记录 + 待录入报告单
        runInTenant(
                "demob",
                () -> {
                    jdbc.update(
                            "INSERT INTO pt_implant (patient_id, implant_type, implant_date,"
                                    + " device_type, status) VALUES (?, 'FIRST', '2025-06-10',"
                                    + " 'CRT_D', 'ACTIVE')",
                            patientId);
                    insertSubmittedEriRecord("demob", patientId, "{\"f323\":1}");
                    jdbc.update(
                            "INSERT INTO rpt_report (patient_id, type_code, type_name, report_date,"
                                    + " findings, conclusion, digitize_status, status, created_by)"
                                    + " VALUES (?, 'LAB', '检验报告（示例）', CURDATE(),"
                                    + " '示例所见（虚构）', '未见明显异常（示例）', 'PENDING',"
                                    + " 'NORMAL', 2)",
                            patientId);
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pt_implant WHERE patient_id = ? AND"
                                            + " status = 'ACTIVE'",
                                    Long.class,
                                    patientId),
                            "造数：demob 存在在用植入记录");
                });

        Map<?, ?> data = data(get(BASE + "/patients/" + patientId + "/summary", doctor));

        assertNull(data.get("device"), "implant 关闭（B 租户）：概览无器械行，行为与 E21 前一致");
        assertNull(data.get("pendingScanCount"), "scan_progress 关闭（B 租户）：概览不下发待录入扫描件数");
        // 其余概览块照常（基线 / 计划 / 最近随访随各自写入方，双租户隔离口径由 PatientOverviewIT 锁定）
        assertEquals("赵*）", data.get("name"), "姓名照常脱敏");
        assertTrue(((List<?>) data.get("actions")) != null, "actions 照常按权限计算");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ImplantRecordIT /
    // ReportTranscribeIT 同口径）

    private void enableFeatures(String tenant, boolean implant, boolean eriCycle, boolean scan) {
        runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = ? WHERE feature_code = 'implant'",
                            implant ? 1 : 0);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = ? WHERE feature_code = 'eri_cycle'",
                            eriCycle ? 1 : 0);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = ? WHERE feature_code ="
                                    + " 'scan_progress'",
                            scan ? 1 : 0);
                });
    }

    /** C 登记（implant 开关开启时的 C 最小必填 + 首条植入记录）。 */
    private long registerWithImplant(
            String token, String mrn, String name, String implantDate, String deviceType) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId("W-DEMO-1"));
        body.put("deptId", deptId("DEPT-CARDIO"));
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "SN-E21T22-" + mrn);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        body.put("implant", implant);
        Map<?, ?> created = post(BASE + "/patients", token, body);
        assertEquals(0, codeOf(created), "C 登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    /** A 出院登记（demob 对照）。 */
    private long registerPlain(String token, String tenant, String mrn, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId(tenant));
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<?, ?> created = post(BASE + "/patients", token, body);
        assertEquals(0, codeOf(created), "A 登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    /** 已提交随访记录（含 ERI 字段；直接造数，避免依赖 C 表单模板的提交校验）。 */
    private void insertSubmittedEriRecord(String tenant, long patientId, String dataJson) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_record (patient_id, source, visit_date, form_code,"
                                        + " template_version, status, version, data, created_by)"
                                        + " VALUES (?, 'BACKFILL', DATE_SUB(CURDATE(), INTERVAL 10"
                                        + " DAY), 'C_PROGRAM_FU', 1, 'SUBMITTED', 1, ?, 2)",
                                patientId,
                                dataJson));
    }

    /** 植入时长期望文案：与生产同口径（日历月，目标日未到当月减一）按今天算。 */
    private static String expectedDuration(String implantDate) {
        LocalDate from = LocalDate.parse(implantDate);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long months =
                (today.getYear() - from.getYear()) * 12L
                        + (today.getMonthValue() - from.getMonthValue());
        if (today.getDayOfMonth() < from.getDayOfMonth()) {
            months--;
        }
        long years = months / 12;
        long rest = months % 12;
        if (months <= 0) {
            return "不足 1 个月";
        }
        if (years <= 0) {
            return rest + " 个月";
        }
        if (rest <= 0) {
            return years + " 年";
        }
        return years + " 年 " + rest + " 个月";
    }

    private long wardId(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant,
                () -> jdbc.queryForObject("SELECT id FROM ward ORDER BY id LIMIT 1", Long.class));
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response), "登录失败：" + response);
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

    private static int codeOf(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
