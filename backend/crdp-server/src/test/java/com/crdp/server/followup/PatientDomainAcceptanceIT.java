package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
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
 * E13-T10 患者域链路验收（tasks.csv 验收原文：登记、检索、转病区用例通过）。
 *
 * <p>与逐功能的 `PatientRegistrationIT`（E13-T01）/ `PatientSearchIT`（E13-T04）/ `PatientAdvancedSearchIT`
 * （E13-T06）/ `WardAndTransferIT`（E13-T08）不同，本套件把三条功能线接成<b>一条连续链</b>（同 E09-T08 NotifyAcceptanceIT
 * 的链路口径）：后一段用例消费前一段真实产生的状态，而不是各自造数——
 *
 * <ol>
 *   <li>登记链路：登记 → 查重提示（范围内带脱敏摘要，可跳转）→ 重复 40902 阻止 → 姓名 + 出生日期相同仅提示不阻止 → 同一病案号在 demob 可再登记（每院独立库）；密文
 *       + 盲索引落库与 PATIENT_CREATE 审计；
 *   <li>检索链路：同一批患者按 keyword 姓名 / 病案号盲索引精确命中 → 高级检索条件树（字典字段、参数化）命中 → 保存为个人筛选（仅本人可见）→ 应用保存的 payload
 *       结果与即时条件检索一致；
 *   <li>转病区链路：登记 → 三条检索路径（keyword / 条件树 / 保存的筛选）全部可见 → 转入新病区 → 原病区医生在<b>全部四条路径</b>（含
 *       summary）立即不可见（保存的筛选不冻结结果集，数据范围在查询时实时收窄）→ 管理员仍可见且转病区历史 / 审计 / 乐观锁版本留痕。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T10-、病区
 * W-IT-T10A/B、保存的筛选名前缀「IT-E13T10」）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientDomainAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T10";
    private static final String NAME_CHAIN = "张链路（示例）";
    private static final String FILTER_NAME = "IT-E13T10 2026 出院（示例）";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('PATIENT_CREATE','PATIENT_TRANSFER')"
                                        + " AND (target IN (SELECT CONCAT('PATIENT:', id) FROM"
                                        + " pt_patient WHERE legacy_no LIKE 'IT-E13T10-%')"
                                        + " OR detail LIKE '出院登记被阻止：%')");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action LIKE 'SAVED_FILTER_%'"
                                        + " AND module = 'followup'");
                        jdbc.update(
                                "DELETE h FROM pt_ward_history h JOIN pt_patient p ON p.id = h.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E13T10-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T10-%'");
                        jdbc.update(
                                "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                                        + " (SELECT id FROM pt_patient)");
                        jdbc.update("DELETE FROM fu_saved_filter WHERE name LIKE 'IT-E13T10%'");
                        jdbc.update("DELETE FROM ward WHERE code IN ('W-IT-T10A', 'W-IT-T10B')");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ------------------------------------------------------------- 链路一：登记（E13-T01 验收：重复病案号提示并阻止）

    @Test
    void registrationChainChecksDuplicateBlocksRepeatAndStaysTenantUnique() {
        String doctor = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        String mrn = "IT-MRN-T10-A1";

        // 1. 登记成功（最小必填集），返回脱敏姓名
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        doctor,
                        body(mrn, NAME_CHAIN, wardId, "1955-01-01"));
        assertEquals(0, codeOf(created), "登记失败：" + created);
        String patientId = String.valueOf(data(created).get("id"));
        assertEquals("张*）", data(created).get("nameMasked"), "登记结果只回脱敏姓名");

        // 2. 实时查重：范围内同号带已有患者脱敏摘要（前端按 id 跳转）
        Map<?, ?> check =
                post(
                        "/api/hospital/followup/patients/check-duplicate",
                        doctor,
                        Map.of(
                                "medicalRecordNo",
                                mrn,
                                "name",
                                "王查重（示例）",
                                "birthDate",
                                "1960-02-02"));
        assertEquals(0, codeOf(check));
        assertTrue((Boolean) data(check).get("exists"), "同号查重应提示已存在");
        Map<?, ?> summary = (Map<?, ?>) data(check).get("patient");
        assertNotNull(summary, "范围内重复下发已有患者摘要");
        assertEquals(patientId, String.valueOf(summary.get("id")));
        assertEquals("张*）", summary.get("nameMasked"));

        // 3. 重复登记被 40902 阻止（提示并阻止）
        Map<?, ?> blocked =
                post(
                        "/api/hospital/followup/patients",
                        doctor,
                        body(mrn, "王查重（示例）", wardId, "1960-02-02"));
        assertEquals(40902, codeOf(blocked), "重复病案号必须阻止");
        assertEquals(
                patientId, String.valueOf(((Map<?, ?>) dataOf(blocked).get("patient")).get("id")));

        // 4. 姓名 + 出生日期相同 → 可能重复仅提示，不阻止（C-PAT-002）
        Map<?, ?> possible =
                post(
                        "/api/hospital/followup/patients/check-duplicate",
                        doctor,
                        Map.of(
                                "medicalRecordNo",
                                "IT-MRN-T10-A2",
                                "name",
                                NAME_CHAIN,
                                "birthDate",
                                "1955-01-01"));
        assertEquals(0, codeOf(possible));
        assertFalse((Boolean) data(possible).get("exists"), "新病案号未占用");
        List<?> matches = (List<?>) data(possible).get("possibleMatches");
        assertEquals(1, matches.size(), "姓名 + 出生日期相同提示可能重复");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                doctor,
                                body("IT-MRN-T10-A2", NAME_CHAIN, wardId, "1955-01-01"))),
                "可能重复不阻止登记");

        // 5. 同一病案号在 demob（每院独立库）不受 demoa 唯一约束
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        long demobWard =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                demobAdmin,
                                body(mrn, "李隔院（示例）", demobWard, "1955-01-01"))),
                "demob 同号登记应成功（租户隔离）");

        // 6. 落库口径：明文不进库（密文 + 盲索引）、PATIENT_CREATE 审计（成功 + 被阻止各一）
        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT mrn_cipher, mrn_blind, name_cipher, legacy_no, ward_id"
                                            + " FROM pt_patient WHERE id = ?",
                                    Long.parseLong(patientId));
                    assertFalse(String.valueOf(row.get("mrn_cipher")).contains(mrn), "病案号明文不落库");
                    assertFalse(String.valueOf(row.get("name_cipher")).contains("张链路"), "姓名明文不落库");
                    assertTrue(String.valueOf(row.get("mrn_blind")).length() >= 32, "盲索引为摘要");
                    assertEquals(LEGACY + "-" + mrn, row.get("legacy_no"));
                    assertEquals(wardId, ((Number) row.get("ward_id")).longValue());

                    Integer success =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                            + " AND target = ? AND result = 'SUCCESS'",
                                    Integer.class,
                                    "PATIENT:" + patientId);
                    assertEquals(1, success, "登记写 PATIENT_CREATE 审计");
                    Integer denied =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                            + " AND result = 'DENIED' AND error_code = 40902",
                                    Integer.class);
                    assertTrue(denied >= 1, "被阻止的登记写 DENIED 审计");
                });
    }

    // ------------------------------------------------------------- 链路二：检索（E13-T04
    // 验收：按病区隔离、按姓名精确检索）

    @Test
    void searchChainFindsPatientByKeywordConditionAndSavedFilter() {
        String doctor = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        register(doctor, "IT-MRN-T10-B1", "李检索（示例）", wardId, "1948-06-01", "2026-03-08", 1);
        register(doctor, "IT-MRN-T10-B2", "王检索（示例）", wardId, "1953-09-09", "2027-01-15", 3);

        // 1. keyword 姓名精确（盲索引相等，非模糊）
        Map<?, ?> byName =
                post("/api/hospital/followup/patients/search", doctor, searchBody("李检索（示例）"));
        assertEquals(0, codeOf(byName));
        assertEquals(1, ((Number) data(byName).get("total")).intValue(), "按姓名精确检索命中一名");
        assertEquals(LEGACY + "-IT-MRN-T10-B1", legacyOf(firstItem(byName)));
        assertEquals("李*）", firstItem(byName).get("name"), "行内姓名脱敏");

        // 2. keyword 病案号精确
        Map<?, ?> byMrn =
                post("/api/hospital/followup/patients/search", doctor, searchBody("IT-MRN-T10-B2"));
        assertEquals(1, ((Number) data(byMrn).get("total")).intValue(), "按病案号精确命中");
        assertEquals(LEGACY + "-IT-MRN-T10-B2", legacyOf(firstItem(byMrn)));

        // 3. 高级检索条件树（字典字段，数据范围自动加病区条件）
        Map<String, Object> advanced = searchBody(null);
        advanced.put("condition", conditionOf("2026-01-01", "2026-12-31"));
        Map<?, ?> advancedPage = post("/api/hospital/followup/patients/search", doctor, advanced);
        assertEquals(0, codeOf(advancedPage), "条件检索失败：" + advancedPage);
        assertEquals(1, ((Number) data(advancedPage).get("total")).intValue(), "出院日期介于 2026 年命中一名");
        assertEquals(LEGACY + "-IT-MRN-T10-B1", legacyOf(firstItem(advancedPage)));

        // 4. 保存为个人筛选（payload 不含检索词），仅本人可见
        Map<?, ?> saved =
                post(
                        "/api/hospital/followup/saved-filters",
                        doctor,
                        Map.of(
                                "name",
                                FILTER_NAME,
                                "payload",
                                Map.of("condition", conditionOf("2026-01-01", "2026-12-31"))));
        assertEquals(0, codeOf(saved), "保存筛选失败：" + saved);
        long filterId = Long.parseLong(String.valueOf(data(saved).get("id")));

        List<?> mine = itemsOf(get("/api/hospital/followup/saved-filters", doctor));
        assertEquals(
                1,
                mine.stream()
                        .filter(item -> FILTER_NAME.equals(((Map<?, ?>) item).get("name")))
                        .count(),
                "本人清单包含刚保存的筛选");
        Map<?, ?> payload =
                (Map<?, ?>)
                        mine.stream()
                                .map(item -> (Map<?, ?>) item)
                                .filter(item -> FILTER_NAME.equals(item.get("name")))
                                .findFirst()
                                .orElseThrow()
                                .get("payload");
        assertFalse(payload.containsKey("keyword"), "保存的筛选不含检索词");
        List<?> others = itemsOf(get("/api/hospital/followup/saved-filters", adminToken()));
        assertEquals(
                0,
                others.stream()
                        .filter(item -> FILTER_NAME.equals(((Map<?, ?>) item).get("name")))
                        .count(),
                "个人筛选仅本人可见");

        // 5. 应用保存的筛选：与即时条件检索结果一致（同一患者、同一总数）
        Map<String, Object> applied = searchBody(null);
        applied.put("condition", payload.get("condition"));
        Map<?, ?> appliedPage = post("/api/hospital/followup/patients/search", doctor, applied);
        assertEquals(0, codeOf(appliedPage));
        assertEquals(
                ((Number) data(advancedPage).get("total")).intValue(),
                ((Number) data(appliedPage).get("total")).intValue(),
                "应用保存的筛选与即时条件检索同结果");
        assertEquals(legacyOf(firstItem(advancedPage)), legacyOf(firstItem(appliedPage)));
        assertTrue(filterId > 0);
    }

    // --------------------------------------------------- 链路三：转病区（E13-T08 验收：转病区后原病区医生不可见）

    @Test
    void transferChainRevokesVisibilityAcrossAllRetrievalPaths() {
        String doctor = doctorToken();
        String admin = adminToken();
        long wardId = wardIdByCode("W-DEMO-1");
        String mrn = "IT-MRN-T10-C1";

        // 1. 登记并保存个人筛选（三条件：keyword / summary / 条件树全可见）
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                doctor,
                                body(mrn, "赵转区（示例）", wardId, "1951-02-02"))),
                "登记失败");
        long patientId = patientIdByMrn(mrn);
        assertEquals(
                0,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", doctor)),
                "转病区前医生可见患者");
        assertEquals(
                1,
                ((Number)
                                data(post(
                                                "/api/hospital/followup/patients/search",
                                                doctor,
                                                searchBody(mrn)))
                                        .get("total"))
                        .intValue(),
                "转病区前 keyword 可见");
        Map<?, ?> savedFilter =
                post(
                        "/api/hospital/followup/saved-filters",
                        doctor,
                        Map.of(
                                "name",
                                FILTER_NAME,
                                "payload",
                                Map.of("condition", conditionOf("2026-01-01", "2026-12-31"))));
        assertEquals(0, codeOf(savedFilter), "保存筛选失败");

        // 2. 转入新病区（真实入口建病区，走 API）
        Map<?, ?> ward =
                post(
                        "/api/hospital/followup/wards",
                        admin,
                        Map.of(
                                "code", "W-IT-T10A",
                                "name", "示例 IT 链路转入病区（示例）",
                                "wardType", "WARD"));
        assertEquals(0, codeOf(ward), "建病区失败：" + ward);
        long targetWard = Long.parseLong(String.valueOf(data(ward).get("id")));

        Map<?, ?> transferred =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/transfer",
                        doctor,
                        Map.of("wardId", targetWard, "reason", "链路验收转病区（示例）"));
        assertEquals(0, codeOf(transferred), "转病区失败：" + transferred);
        assertEquals("示例 IT 链路转入病区（示例）", data(transferred).get("wardName"));

        // 3. 原病区医生：四条路径立即不可见（summary 40400、keyword / 条件树 / 保存的筛选全部 0）
        assertEquals(
                40400,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", doctor)),
                "原病区医生 summary 40400（不暴露存在）");
        assertEquals(
                0,
                ((Number)
                                data(post(
                                                "/api/hospital/followup/patients/search",
                                                doctor,
                                                searchBody(mrn)))
                                        .get("total"))
                        .intValue(),
                "原病区医生 keyword 检索不到");
        Map<String, Object> advanced = searchBody(null);
        advanced.put("condition", conditionOf("2026-01-01", "2026-12-31"));
        assertEquals(
                0,
                ((Number)
                                data(post(
                                                "/api/hospital/followup/patients/search",
                                                doctor,
                                                advanced))
                                        .get("total"))
                        .intValue(),
                "原病区医生条件树检索不到（条件放行但数据范围收窄）");
        Map<String, Object> applied = searchBody(null);
        applied.put("condition", conditionOf("2026-01-01", "2026-12-31"));
        assertEquals(
                0,
                ((Number)
                                data(post(
                                                "/api/hospital/followup/patients/search",
                                                doctor,
                                                applied))
                                        .get("total"))
                        .intValue(),
                "保存的筛选不冻结结果集：应用后同样检索不到");
        assertEquals(
                1,
                itemsOf(get("/api/hospital/followup/saved-filters", doctor)).stream()
                        .filter(item -> FILTER_NAME.equals(((Map<?, ?>) item).get("name")))
                        .count(),
                "保存的筛选本身仍是医生的个人数据（清单保留，结果随范围实时收窄）");

        // 4. 管理员（ALL 范围）：新病区仍可见，历史 / 审计 / 乐观锁留痕
        assertEquals(
                1,
                ((Number)
                                data(post(
                                                "/api/hospital/followup/patients/search",
                                                admin,
                                                searchBody(mrn)))
                                        .get("total"))
                        .intValue(),
                "管理员 keyword 仍可见（新病区）");
        Map<String, Object> adminAdvanced = searchBody(null);
        adminAdvanced.put("condition", conditionOf("2026-01-01", "2026-12-31"));
        assertEquals(
                1,
                ((Number)
                                data(post(
                                                "/api/hospital/followup/patients/search",
                                                admin,
                                                adminAdvanced))
                                        .get("total"))
                        .intValue(),
                "管理员条件树仍可见");
        Map<?, ?> history =
                get("/api/hospital/followup/patients/" + patientId + "/ward-history", admin);
        assertEquals(0, codeOf(history));
        List<?> entries = (List<?>) data(history).get("items");
        assertEquals(1, entries.size(), "转病区历史一条");
        assertEquals("虚构病区一", ((Map<?, ?>) entries.get(0)).get("fromWardName"));
        assertEquals("示例 IT 链路转入病区（示例）", ((Map<?, ?>) entries.get(0)).get("toWardName"));
        assertEquals("链路验收转病区（示例）", ((Map<?, ?>) entries.get(0)).get("reason"));

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT ward_id, version FROM pt_patient WHERE id = ?",
                                    patientId);
                    assertEquals(targetWard, ((Number) row.get("ward_id")).longValue(), "归属已改");
                    assertTrue(((Number) row.get("version")).longValue() >= 1, "乐观锁版本递增");
                    Integer historyRows =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pt_ward_history WHERE patient_id = ?"
                                            + " AND from_ward_id = ? AND to_ward_id = ?",
                                    Integer.class,
                                    patientId,
                                    wardId,
                                    targetWard);
                    assertEquals(1, historyRows, "pt_ward_history 只追加一行");
                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                            + " 'PATIENT_TRANSFER' AND target = ? AND result ="
                                            + " 'SUCCESS'",
                                    Integer.class,
                                    "PATIENT:" + patientId);
                    assertEquals(1, audits, "转病区写 PATIENT_TRANSFER 审计");
                });
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, Object> body(
            String mrn, String name, long wardId, String birthDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", birthDate);
        body.put("wardId", wardId);
        body.put("surgeryDate", "2026-03-01");
        body.put("admissionDate", "2026-02-25");
        body.put("dischargeDate", "2026-03-08");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        return body;
    }

    private static Map<String, Object> searchBody(String keyword) {
        Map<String, Object> body = new HashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", 20);
        body.put("filters", new HashMap<String, Object>());
        if (keyword != null) {
            body.put("keyword", keyword);
        }
        return body;
    }

    /** 2026 年出院条件树（字典字段 + 参数化，E13-T06 口径）。 */
    private static Map<String, Object> conditionOf(String from, String to) {
        return Map.of(
                "logic",
                "AND",
                "items",
                List.of(
                        Map.of(
                                "field",
                                "discharge_date",
                                "op",
                                "BETWEEN",
                                "value",
                                List.of(from, to))));
    }

    private void register(
            String token,
            String mrn,
            String name,
            long wardId,
            String birthDate,
            String dischargeDate,
            int dischargeStatus) {
        Map<String, Object> body = body(mrn, name, wardId, birthDate);
        body.put("dischargeDate", dischargeDate);
        body.put("dischargeStatus", dischargeStatus);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败：" + created);
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败：" + response);
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
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        Object payload = response.get("data");
        return payload instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> firstItem(Map<?, ?> page) {
        return (Map<?, ?>) ((List<?>) data(page).get("items")).get(0);
    }

    private static List<?> itemsOf(Map<?, ?> response) {
        return (List<?>) data(response).get("items");
    }

    private static String legacyOf(Map<?, ?> item) {
        Object value = item.get("legacyNo");
        return value == null ? null : String.valueOf(value);
    }

    private long wardIdByCode(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long patientIdByMrn(String mrn) {
        // 病案号只存密文 + 盲索引，按配对的 legacy_no 取 id
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM pt_patient WHERE legacy_no = ?",
                                Long.class,
                                LEGACY + "-" + mrn));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
