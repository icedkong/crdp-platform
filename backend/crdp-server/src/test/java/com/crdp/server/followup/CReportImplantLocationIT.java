package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.HashMap;
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

/**
 * E21-T32 端到端 / C-SCAN-010、C-PAT-017（完整应用 + 真实 MySQL，HTTP 直连）。验收原文：C 报告单可选基线资料（患者登记分组、 植入记录整条或器械 /
 * 电极）与随访记录（整条或程控分组）为位置。
 *
 * <p>覆盖：位置树含植入记录根（当前器械置顶，器械信息 / 电极分组）；植入整条 / 分组三种粒度落行（模板版本 = C_IMPLANT
 * 当前发布版）；同一报告单同一位置唯一（40001）；未知分组锚点 / 错误粒度 40001；越患者植入记录 40400；开关关闭（demob）40301； 修改位置 add+remove
 * 植入位置；患者内清单 baseline 侧含仅植入位置的报告单且标签带「植入记录#N 日期 · 器械信息 / 电极」。 全部虚构数据，门控 CRDP_IT_MYSQL_PORT（与
 * E21-T27 留痕套件同链路，C-ACC-12 完整链路的机器面）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CReportImplantLocationIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E21T32";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    void setUpOnce() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                        + " 'implant'"));
    }

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE object_type = 'REPORT' AND object_id IN"
                                    + " (SELECT id FROM rpt_report WHERE patient_id IN"
                                    + "  (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM rpt_indicator_value WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM rpt_report_file WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM rpt_report_link WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM rpt_report WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    // 级联清理植入侧（先电极关联与电极，再植入、任务 / 记录、患者）
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM pt_implant_lead WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM pt_lead WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM pt_implant WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () ->
                        // 开关回关（随包 V100 默认 0，不留库影响 A / B 惰性口径）
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                        + " 'implant'"));
    }

    // ---------------------------------------------------------------- 验收：植入位置三种粒度

    /** 位置树含植入记录根（当前器械置顶 + 器械信息 / 电极分组）；创建整条 / 器械 / 电极三粒度落行 + 唯一性 + 患者内清单标签。 */
    @Test
    void implantWholeAndGroupLocationsThroughout() {
        String admin = adminToken();
        long patientId = registeredPatient(admin, "01");
        Map<?, ?> name = firstNameOf("LAB", admin);
        long currentImplantId = currentImplantId(patientId);
        long replacedImplantId = replacedImplantId(patientId);

        // 位置树：基线资料 + 植入记录 + 随访记录三个根；当前器械置顶（在用在前）
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", admin)
                                .get("data");
        assertEquals(3, roots.size(), "三根：" + roots);
        Map<?, ?> implantRoot =
                roots.stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> "植入记录".equals(item.get("title")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(implantRoot, "位置树含植入记录根");
        assertFalse((Boolean) implantRoot.get("selectable"), "分组根不可选");
        List<?> implantNodes = (List<?>) implantRoot.get("children");
        assertEquals(2, implantNodes.size(), "两条植入记录");
        Map<?, ?> top = (Map<?, ?>) implantNodes.get(0);
        assertEquals(String.valueOf(currentImplantId), valueOf(top).get("linkRecordId"));
        assertTrue(valueOf(top).get("anchorType") == null, "整条植入记录锚点为空");
        List<?> groups = (List<?>) top.get("children");
        assertEquals(2, groups.size(), "器械信息 / 电极两个分组（C_IMPLANT 当前发布版）");
        assertEquals("device", valueOf((Map<?, ?>) groups.get(0)).get("anchorKey"));
        assertEquals("lead", valueOf((Map<?, ?>) groups.get(1)).get("anchorKey"));
        assertEquals(
                String.valueOf(replacedImplantId),
                valueOf((Map<?, ?>) implantNodes.get(1)).get("linkRecordId"),
                "其余按植入日期新在前");

        // 创建：整条 + 器械信息 + 电极三种粒度各落一行（模板版本 = C_IMPLANT 当前发布版）
        long reportId =
                createReport(
                        admin,
                        patientId,
                        str(name.get("id")),
                        List.of(
                                implantLocation(currentImplantId, null),
                                implantLocation(currentImplantId, "device"),
                                implantLocation(currentImplantId, "lead")));
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            3L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?"
                                            + " AND link_type = 'implant_record'",
                                    Long.class,
                                    reportId),
                            "三种粒度各一行");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT template_version FROM rpt_report_link WHERE report_id"
                                            + " = ? AND anchor_type = 'section' AND anchor_key ="
                                            + " 'device'",
                                    Integer.class,
                                    reportId),
                            "模板版本 = C_IMPLANT 当前发布版（V90 预置 1）");
                });

        // 患者内清单（scope=baseline）：仅植入位置的报告单随 baseline 侧下发，标签带记录名与分组名（左栏数据面，C-PAT-017）
        Map<?, ?> reports = get(BASE + "/patients/" + patientId + "/reports?scope=baseline", admin);
        assertEquals(0, code(reports));
        List<?> items = (List<?>) data(reports).get("items");
        assertEquals(1, items.size(), "仅植入位置 → baseline 侧（植入记录在基线资料页签）");
        List<?> locations = (List<?>) ((Map<?, ?>) items.get(0)).get("locations");
        assertEquals(3, locations.size());
        assertTrue(
                locations.stream()
                        .anyMatch(
                                tag -> {
                                    Map<?, ?> item = (Map<?, ?>) tag;
                                    return "implant_record".equals(item.get("linkType"))
                                            && str(item.get("recordLabel")).contains("植入记录#2")
                                            && "器械信息".equals(item.get("anchorLabel"));
                                }),
                "标签 = 植入记录#N 日期 · 器械信息：" + locations);
        Map<?, ?> followupSide =
                get(BASE + "/patients/" + patientId + "/reports?scope=followup", admin);
        assertTrue(((List<?>) data(followupSide).get("items")).isEmpty(), "随访侧不含仅植入位置的报告单");

        // 唯一性：同位置重复添加 40001；未知分组 40001；越患者植入记录 40400
        Map<?, ?> duplicate =
                put(
                        BASE + "/reports/" + reportId + "/locations",
                        admin,
                        Map.of(
                                "add",
                                List.of(implantLocation(currentImplantId, "device")),
                                "remove",
                                List.of()));
        assertEquals(40001, code(duplicate), "同一报告单同一位置唯一：" + duplicate);

        Map<?, ?> unknownAnchor =
                put(
                        BASE + "/reports/" + reportId + "/locations",
                        admin,
                        Map.of(
                                "add",
                                List.of(implantLocation(currentImplantId, "gone")),
                                "remove",
                                List.of()));
        assertEquals(40001, code(unknownAnchor), "未知植入分组锚点：" + unknownAnchor);

        long otherPatientImplant = currentImplantId(registeredPatient(admin, "02"));
        Map<?, ?> foreign =
                put(
                        BASE + "/reports/" + reportId + "/locations",
                        admin,
                        Map.of(
                                "add",
                                List.of(implantLocation(otherPatientImplant, null)),
                                "remove",
                                List.of()));
        assertEquals(40400, code(foreign), "越患者植入记录统一 40400：" + foreign);

        // 修改位置：add 历史（已更换）器械整条 + remove 电极分组，四元组匹配在册行
        Map<?, ?> changed =
                put(
                        BASE + "/reports/" + reportId + "/locations",
                        admin,
                        Map.of(
                                "add",
                                List.of(implantLocation(replacedImplantId, null)),
                                "remove",
                                List.of(implantLocation(currentImplantId, "lead"))));
        assertEquals(0, code(changed), "add+remove 同场生效：" + changed);
        assertEquals(
                3,
                ((List<?>) data(changed).get("locations")).size(),
                "3 = 整条 + 器械信息（在册）+ 历史器械整条（新加）");
    }

    /** 关闭 implant 开关（库内数据动作）：创建植入位置 40301（后端闸门，AGENTS §5.7）。 */
    @Test
    void featureOffRejectsImplantLocationWith40301() {
        String admin = adminToken();
        // 患者与植入记录先在开关开启时造好（C 登记携带 implant 载荷须开关开启）
        long patientId = registeredPatient(admin, "03");
        long implantId = currentImplantId(patientId);
        Map<?, ?> name = firstNameOf("LAB", admin);
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                        + " 'implant'"));
        try {
            Map<?, ?> rejected =
                    post(
                            BASE + "/reports",
                            admin,
                            reportBody(
                                    patientId,
                                    str(name.get("id")),
                                    List.of(implantLocation(implantId, null))));
            assertEquals(40301, code(rejected), "开关关闭 40301：" + rejected);
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                            + " 'implant'"));
        }
    }

    // ---------------------------------------------------------------- 工具

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

    /** C 登记（implant 开关开启：患者 + 首条植入 DDD）+ 第二条 REPLACE（2026-03-02，CRT-D）→ 当前器械 #2。 */
    private long registeredPatient(String token, String suffix) {
        String mrn = LEGACY + "-" + suffix;
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", "2026-01-05");
        implant.put("deviceType", "DDD");
        implant.put("serialNo", "DEMO-SN-E21T32-" + suffix);
        implant.put(
                "leads",
                List.of(
                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                        Map.of("chamber", "RV", "position", "RV_APEX")));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", "王李张（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId("DEPT-CARDIO"));
        body.put("wardId", wardId("W-DEMO-1"));
        body.put("legacyNo", mrn);
        body.put("implant", implant);
        Map<?, ?> first = post(BASE + "/patients", token, body);
        assertEquals(0, code(first), "C 登记失败：" + first);
        long patientId = Long.parseLong(str(data(first).get("id")));

        Map<String, Object> second = new HashMap<>(implant);
        second.put("implantType", "REPLACE");
        second.put("implantDate", "2026-03-02");
        second.put("deviceType", "CRT_D");
        second.put("serialNo", "DEMO-SN-E21T32-R" + suffix);
        second.put(
                "leads",
                List.of(
                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                        Map.of("chamber", "RV", "position", "RV_SEPTUM"),
                        Map.of("chamber", "LV", "position", "CS")));
        Map<?, ?> replaced = post(BASE + "/patients/" + patientId + "/implants", token, second);
        assertEquals(0, code(replaced), "第二次植入失败：" + replaced);
        return patientId;
    }

    private long currentImplantId(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM pt_implant WHERE patient_id = ? AND status ="
                                        + " 'ACTIVE'",
                                Long.class,
                                patientId));
    }

    private long replacedImplantId(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM pt_implant WHERE patient_id = ? AND status ="
                                        + " 'REPLACED'",
                                Long.class,
                                patientId));
    }

    /** LAB 首个名称（血常规，A 预置 V62）；目录接口 data 直接是清单。 */
    private Map<?, ?> firstNameOf(String typeCode, String token) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    private Map<String, Object> reportBody(
            long patientId, String nameId, List<Map<String, Object>> locations) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "LAB");
        body.put("nameId", nameId);
        body.put("reportDate", LocalDate.now().minusDays(35).toString());
        body.put("findings", "植入资料归档演练（示例）");
        body.put("locations", locations);
        return body;
    }

    private long createReport(
            String token, long patientId, String nameId, List<Map<String, Object>> locations) {
        Map<?, ?> created =
                post(BASE + "/reports", token, reportBody(patientId, nameId, locations));
        assertEquals(0, code(created), "创建失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 植入位置（anchorKey null = 整条）。 */
    private static Map<String, Object> implantLocation(long implantId, String anchorKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "implant_record");
        location.put("linkRecordId", String.valueOf(implantId));
        location.put("anchorType", anchorKey == null ? null : "section");
        if (anchorKey != null) {
            location.put("anchorKey", anchorKey);
        }
        return location;
    }

    private static Map<?, ?> valueOf(Map<?, ?> node) {
        return (Map<?, ?>) node.get("value");
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, bearer(token)), Map.class)
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
        return (Integer) response.get("code");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.callAsTenant(
                1L,
                tenant,
                () -> {
                    action.run();
                    return null;
                });
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
