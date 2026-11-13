package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * E21-T03 端到端（验收原文：表单按当前器械显示字段；C-PAT-007、C-FLD-005、C-FU-009）。场景（demoa 开 cfg_feature.implant，向
 * A_ANNUAL_FU v1 快照注入两个 ctx 条件字段）：
 *
 * <ol>
 *   <li>2024-06-01 DDD（RA / RV）→ 2026-03-10 升级 CRT-D（RA / RV 沿用 + LV 新植）：2025-06-15 补录记录 device 匹配旧
 *       DDD（current=false、hasRaLead、chambers=2），更换当日（2026-03-10）记录匹配新 CRT-D
 *       （current=true、chambers=3）——区间 [implant_date, end_date) 右开、更换当日归新器械；
 *   <li>心房参数字段（ctx device.hasRaLead）只在有 RA 电极时保留：旧 DDD 记录保留，单腔 SSI（仅 RV）与 ICD 字段（ctx device.type in
 *       [ICD, CRT_D]）在 DDD 记录被隐藏并<b>提交清空</b>（tech-plan §6）， CRT-D 记录两者均保留；可见时按条件必填（缺 f9d1
 *       42200），隐藏时<b>不必填</b>（SSI 提交成功）；
 *   <li>随访日期早于首次植入 → 无匹配器械（device 为 null），ctx 断链 → 条件字段全部隐藏清空，提交不受阻。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T03-、注入字段 f9d1 /
 * f9d2）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceContextIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T03";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 注入字段：心房阈值（ctx device.hasRaLead，可见即必填）与 ICD 项目（ctx device.type in [ICD, CRT_D]）。 */
    private static final String ATRIAL_KEY = "f9d1";

    private static final String ICD_KEY = "f9d2";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAndSeed() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "DELETE FROM fe_form_version_field WHERE form_code = 'A_ANNUAL_FU'"
                                    + " AND field_key IN (?, ?)",
                            ATRIAL_KEY,
                            ICD_KEY);
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id AND fh.record_type = 'FU_RECORD'"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
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
                            "DELETE l FROM pt_lead l"
                                    + " JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE i FROM pt_implant i"
                                    + " JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    com.crdp.server.AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE event_type IN"
                                    + " ('RECORD_BACKFILL_CREATE','RECORD_SUBMIT')"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', id) FROM fu_record"
                                    + " WHERE patient_id IN (SELECT id FROM pt_patient WHERE legacy_no"
                                    + " LIKE ?))",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'");
                    injectDeviceConditionFields();
                });
    }

    @AfterAll
    void cleanUp() {
        cleanAndSeed();
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
    }

    // ---------------------------------------------------------------- 验收：按随访日期匹配器械 + 条件显示

    @Test
    void recordMatchesDeviceByVisitDateAndStripsDeviceHiddenFields() {
        String admin = adminToken();
        // 2024-06-01 DDD（RA / RV）→ 2026-03-10 升级 CRT-D（RA / RV 沿用 + LV 新植）
        String patientId = registerPatient(admin, "IT-MRN-E21T03-01", "DDD", "2024-06-01", "RA,RV");
        Map<?, ?> first = currentImplant(admin, patientId);
        String firstId = String.valueOf(first.get("id"));
        Map<?, ?> upgraded =
                post(
                        "/api/hospital/followup/implants/" + firstId + "/replace",
                        admin,
                        replaceBody(
                                version(first),
                                "2026-03-10",
                                "CRT_D",
                                List.of(
                                        action(leadIdOf(first, "RA"), "KEEP"),
                                        action(leadIdOf(first, "RV"), "KEEP")),
                                List.of(Map.of("chamber", "LV", "position", "CS"))));
        assertEquals(0, code(upgraded), "升级失败：" + upgraded);
        String newId = String.valueOf(data(upgraded).get("id"));

        // 2025-06-15 补录：匹配旧 DDD（C 规格验收示例「2025 年记录仍关联旧器械」）
        Map<?, ?> oldRecord = backfillRecord(admin, patientId, "2025-06-15");
        Map<?, ?> oldDevice = deviceOf(oldRecord);
        assertEquals(firstId, oldDevice.get("implantId"));
        assertEquals("DDD", oldDevice.get("type"));
        assertEquals("双腔起搏器(DDD)", oldDevice.get("typeLabel"));
        assertEquals(false, oldDevice.get("current"), "按随访日期匹配的是历史器械");
        assertEquals(true, oldDevice.get("hasRaLead"));
        assertEquals(2, num(oldDevice.get("chambers")));

        // 可见的心房字段缺值 → 条件必填 42200（requiredWhen ctx device.hasRaLead）
        Map<String, Object> missing = baseData("2025-06-15");
        missing.put(ICD_KEY, "AT（示例）");
        assertEquals(42200, code(submit(oldRecord, admin, missing)), "心房字段对有 RA 电极的器械条件必填");

        // 带值提交：f9d1 保留（hasRaLead），f9d2 清空（DDD 不在 [ICD, CRT_D]）
        Map<String, Object> data = baseData("2025-06-15");
        data.put(ATRIAL_KEY, 2.5);
        data.put(ICD_KEY, "AT（示例）");
        Map<?, ?> submitted = submit(oldRecord, admin, data);
        assertEquals(0, code(submitted), "提交失败：" + submitted);
        assertEquals("2.5", persistedValue(oldRecord, ATRIAL_KEY), "心房字段对有 RA 电极的器械保留");
        assertNull(persistedValue(oldRecord, ICD_KEY), "ICD 字段对 DDD 隐藏并清空");

        // 更换当日（2026-03-10）记录：归新 CRT-D（区间右开），心房与 ICD 字段均可见
        Map<?, ?> dayOfReplace = backfillRecord(admin, patientId, "2026-03-10");
        Map<?, ?> newDevice = deviceOf(dayOfReplace);
        assertEquals(newId, newDevice.get("implantId"), "更换当日归新器械");
        assertEquals("CRT_D", newDevice.get("type"));
        assertEquals(true, newDevice.get("current"));
        assertEquals(3, num(newDevice.get("chambers")), "RA / RV 沿用 + LV 新植 = 三腔");
        Map<String, Object> both = baseData("2026-03-10");
        both.put(ATRIAL_KEY, 1.8);
        both.put(ICD_KEY, "AT（示例）");
        assertEquals(0, code(submit(dayOfReplace, admin, both)), "CRT-D 记录提交失败");
        assertEquals("1.8", persistedValue(dayOfReplace, ATRIAL_KEY));
        assertEquals("AT（示例）", persistedValue(dayOfReplace, ICD_KEY), "ICD 字段对 CRT-D 可见");
    }

    @Test
    void singleChamberDeviceHidesAtrialFieldsWithoutBlockingSubmit() {
        String admin = adminToken();
        // 单腔 SSI（仅 RV）：心房与 ICD 字段全部隐藏——有值也清空、缺值不必填（提交不受阻）
        String patientId = registerPatient(admin, "IT-MRN-E21T03-02", "SSI", "2026-01-05", "RV");
        Map<?, ?> record =
                backfillRecord(admin, patientId, LocalDate.now(ZONE).minusDays(1).toString());

        Map<?, ?> device = deviceOf(record);
        assertEquals("SSI", device.get("type"));
        assertEquals(true, device.get("current"), "近期记录回落当前器械");
        assertEquals(false, device.get("hasRaLead"));
        assertEquals(1, num(device.get("chambers")));

        Map<String, Object> data = baseData(LocalDate.now(ZONE).minusDays(1).toString());
        data.put(ATRIAL_KEY, 9.9);
        data.put(ICD_KEY, "不应保留（示例）");
        Map<?, ?> submitted = submit(record, admin, data);
        assertEquals(0, code(submitted), "隐藏字段不必填，提交不应受阻：" + submitted);
        assertNull(persistedValue(record, ATRIAL_KEY), "单腔无 RA 电极，心房字段清空");
        assertNull(persistedValue(record, ICD_KEY), "单腔非 ICD，ICD 字段清空");
    }

    @Test
    void recordWithoutActiveImplantHasNoDeviceContext() {
        String admin = adminToken();
        String patientId = registerPatient(admin, "IT-MRN-E21T03-03", "DDD", "2024-06-01", "RA,RV");

        // E21-T14 / C-FU-013/014：随访日期早于首次植入的补录创建被 40001 拦下
        // （C 规格 §5「不早于植入日期、不晚于今天」）——「无器械上下文」改走取出后的患者：
        Map<?, ?> tooEarly =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        admin,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                "2024-01-01",
                                "formCode",
                                "A_ANNUAL_FU"));
        assertEquals(40001, code(tooEarly), "补录随访日期不能早于植入日期（E21-T14 / C-FU-013/014）");

        // 器械取出（电极逐根显式废弃）后无在用器械：补录取出后的日期 → device null，ctx 断链 → 条件字段隐藏清空
        Map<?, ?> first = currentImplant(admin, patientId);
        Map<?, ?> explanted =
                post(
                        "/api/hospital/followup/implants/" + first.get("id") + "/explant",
                        admin,
                        Map.of(
                                "baseVersion",
                                version(first),
                                "date",
                                "2025-06-01",
                                "reason",
                                "IT 取出（示例）",
                                "leadActions",
                                List.of(
                                        action(leadIdOf(first, "RA"), "ABANDON"),
                                        action(leadIdOf(first, "RV"), "ABANDON"))));
        assertEquals(0, code(explanted), "取出失败：" + explanted);

        Map<?, ?> record = backfillRecord(admin, patientId, "2025-08-01");
        assertNull(deviceOf(record).get("implantId"), "无器械上下文：" + deviceOf(record));
        Map<String, Object> data = baseData("2025-08-01");
        data.put(ATRIAL_KEY, 9.9);
        data.put(ICD_KEY, "不应保留（示例）");
        assertEquals(0, code(submit(record, admin, data)), "无器械时提交不受阻");
        assertNull(persistedValue(record, ATRIAL_KEY), "无器械上下文时条件字段全部隐藏");
        assertNull(persistedValue(record, ICD_KEY), "无器械上下文时条件字段全部隐藏");
    }

    // ---------------------------------------------------------------- 数据准备（全部虚构）

    /** 注入两个 ctx 条件字段到 A_ANNUAL_FU 当前快照（cleanAndSeed 删除；不进 fe_field，只影响记录流）。 */
    private void injectDeviceConditionFields() {
        jdbc.update(
                "INSERT INTO fe_form_version_field"
                        + " (version_id, form_code, version, field_key, field_code, label,"
                        + " category_key, type, required, visible_when, required_when,"
                        + " sensitive_flag, storage_type, help_text, sort_no)"
                        + " SELECT v.id, 'A_ANNUAL_FU', v.version, ?, 'it_atrial_threshold',"
                        + " '心房阈值（IT 示例）', 'fu_contact', 'number', 0,"
                        + " JSON_OBJECT('all', JSON_ARRAY(JSON_OBJECT('ctx','device.hasRaLead','op','eq','value', true))),"
                        + " JSON_OBJECT('all', JSON_ARRAY(JSON_OBJECT('ctx','device.hasRaLead','op','eq','value', true))),"
                        + " 0, 'json', 'E21-T03 器械上下文注入字段（示例）', 9901"
                        + " FROM fe_form_version v WHERE v.form_code = 'A_ANNUAL_FU'",
                ATRIAL_KEY);
        jdbc.update(
                "INSERT INTO fe_form_version_field"
                        + " (version_id, form_code, version, field_key, field_code, label,"
                        + " category_key, type, required, visible_when, sensitive_flag,"
                        + " storage_type, help_text, sort_no)"
                        + " SELECT v.id, 'A_ANNUAL_FU', v.version, ?, 'it_icd_therapy',"
                        + " 'ICD 项目（IT 示例）', 'fu_contact', 'text', 0,"
                        + " JSON_OBJECT('all', JSON_ARRAY(JSON_OBJECT('ctx','device.type','op','in','value',"
                        + " JSON_ARRAY('ICD','CRT_D')))),"
                        + " 0, 'json', 'E21-T03 器械上下文注入字段（示例）', 9902"
                        + " FROM fe_form_version v WHERE v.form_code = 'A_ANNUAL_FU'",
                ICD_KEY);
    }

    /** C 模式登记（implant 开关切必填集，OQ-71①）：指定类型、植入日期与电极腔室。 */
    private String registerPatient(
            String token, String mrn, String deviceType, String implantDate, String chambers) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", "王示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId("W-DEMO-1"));
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "SN-" + mrn);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        List<Map<String, Object>> leads =
                chambers.isEmpty()
                        ? List.of()
                        : java.util.Arrays.stream(chambers.split(","))
                                .<Map<String, Object>>map(
                                        chamber ->
                                                Map.of(
                                                        "chamber",
                                                        chamber,
                                                        "position",
                                                        "RA".equals(chamber)
                                                                ? "RA_APPENDAGE"
                                                                : "LV".equals(chamber)
                                                                        ? "CS"
                                                                        : "RV_APEX"))
                                .toList();
        implant.put("leads", leads);
        body.put("implant", implant);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记失败：" + created);
        return String.valueOf(data(created).get("id"));
    }

    /** 更换 / 升级请求体（E21-T02 端点；电极全沿用 + 可选新植）。 */
    private Map<String, Object> replaceBody(
            int baseVersion,
            String date,
            String deviceType,
            List<Map<String, Object>> leadActions,
            List<Map<String, Object>> newLeads) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", baseVersion);
        body.put("type", "UPGRADE");
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantDate", date);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-02");
        implant.put("serialNo", "SN-UP-" + date);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        body.put("implant", implant);
        body.put("leadActions", leadActions);
        body.put("newLeads", newLeads);
        return body;
    }

    /** 新建补录记录（visitDate 过去日期、显式 formCode）并返回 GET /records/{id} 详情。 */
    private Map<?, ?> backfillRecord(String token, String patientId, String visitDate) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate,
                                "formCode",
                                "A_ANNUAL_FU"));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, token);
        assertEquals(0, code(detail), "详情失败：" + detail);
        return data(detail);
    }

    private Map<?, ?> submit(Map<?, ?> detail, String token, Map<String, Object> data) {
        return post(
                "/api/hospital/followup/records/" + detail.get("id") + "/submit",
                token,
                Map.of("baseVersion", ((Number) detail.get("version")).intValue(), "data", data));
    }

    /** 落库 data（MySQL JSON 列回读会重排版，取值一律 JSON_EXTRACT）：缺失键返回 null。 */
    private String persistedValue(Map<?, ?> detail, String key) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(data, ?)) FROM fu_record WHERE id = ?",
                                String.class,
                                "$.\"" + key + "\"",
                                Long.valueOf(String.valueOf(detail.get("id")))));
    }

    private static Map<?, ?> deviceOf(Map<?, ?> detail) {
        return detail.get("device") == null ? Map.of() : (Map<?, ?>) detail.get("device");
    }

    /** A_ANNUAL_FU 必填字段（f214 随访日期、f215 是否死亡、f213 已加微信，与 BackfillTimelineIT 同口径）。 */
    private static Map<String, Object> baseData(String visitDate) {
        Map<String, Object> data = new HashMap<>();
        data.put("f214", visitDate);
        data.put("f215", 0);
        data.put("f213", 1);
        return data;
    }

    private Map<?, ?> currentImplant(String token, String patientId) {
        Map<?, ?> list = get("/api/hospital/followup/patients/" + patientId + "/implants", token);
        assertEquals(0, code(list), "列表失败：" + list);
        return (Map<?, ?>) ((List<?>) data(list).get("items")).get(0);
    }

    private static String leadIdOf(Map<?, ?> implant, String chamber) {
        return ((List<Map<?, ?>>) implant.get("leads"))
                .stream()
                        .filter(lead -> chamber.equals(lead.get("chamber")))
                        .findFirst()
                        .map(lead -> String.valueOf(lead.get("id")))
                        .orElseThrow();
    }

    private static Map<String, Object> action(String leadId, String action) {
        Map<String, Object> row = new HashMap<>();
        row.put("leadId", leadId);
        row.put("action", action);
        return row;
    }

    private static int version(Map<?, ?> implant) {
        return ((Number) implant.get("version")).intValue();
    }

    private static double num(Object value) {
        return ((Number) value).doubleValue();
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

    private long wardId(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'", Long.class));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
