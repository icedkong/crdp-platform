package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E16-T10 端到端 / A-ATT-015、C-SCAN-008、C-NFR-006（完整应用 + 真实 MySQL / Redis，HTTP 直连）。
 * 验收原文：二维码过期失效；手机端压缩（压缩在前端 image-compress，本 IT 锁定服务端口径：公开口鉴权、脱敏、预选、提交生成报告单、一次性消费）。
 *
 * <p>覆盖：电脑端签发（预选位置、越范围患者 40400、无权限 40300、模块关闭 40301）；手机端免登录全流程（引导信息 → 页面信息脱敏 + 位置下拉预选 → 上传图片 →
 * 提交生成报告单、创建人 = 签发人）；提交后令牌作废（再次提交 / 读信息 40101）；过期（删 Redis 键模拟 TTL 到点）40101； C 打开 h5_upload_login
 * 后未登录 40100、/context 豁免、同租户登录放行。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileUploadIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E16T10";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "DELETE v FROM rpt_indicator_value v"
                                    + " JOIN rpt_report r ON r.id = v.report_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE f FROM rpt_report_file f"
                                    + " JOIN rpt_report r ON r.id = f.report_id"
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
                    jdbc.update("DELETE FROM sys_file WHERE original_name LIKE 'IT-E16T10-%'");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update("DELETE FROM cfg_feature WHERE feature_code = 'h5_upload_login'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action IN"
                                    + " ('RPT_CREATE','RPT_MOBILE_SUBMIT','RPT_UPLOAD_TOKEN_ISSUE')");
                });
        Set<String> keys = redis.keys("crdp:h5-upload:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：一次性二维码全流程

    /** 电脑端签发（带预选）→ 手机端免登录：引导 / 信息（脱敏 + 预选）/ 上传 / 提交 → 报告单生成、令牌作废、电脑端轮询。 */
    @Test
    void phoneUploadFlowCreatesReportAndConsumesToken() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long patientId = registeredPatient(admin);
        Map<?, ?> categoryValue = firstBaselineCategory(admin, patientId);

        // 越范围患者 40400（doctor.demo 无 ALL 范围时的独立患者；nurse 登记的患者对 doctor 不可见时同一口径）
        // —— 本用例主体用 nurse.demo（TENANT_ADMIN，ALL 范围）签发
        Map<String, Object> preset = new HashMap<>();
        preset.put("linkType", categoryValue.get("linkType"));
        preset.put("anchorType", categoryValue.get("anchorType"));
        preset.put("anchorKey", categoryValue.get("anchorKey"));
        preset.put("anchorTemplateVersion", categoryValue.get("anchorTemplateVersion"));
        Map<?, ?> issued =
                data(
                        post(
                                BASE + "/upload-tokens",
                                admin,
                                Map.of("patientId", patientId, "presetLocation", preset)));
        String token = str(issued.get("token"));
        assertNotNull(token, "签发应返回令牌");
        assertTrue(str(issued.get("qrUrl")).endsWith("/#/u/" + token), "qrUrl 令牌只放 URL 片段");
        assertTrue(
                Long.parseLong(str(issued.get("expiresAtEpochSecond")))
                        > System.currentTimeMillis() / 1000,
                "有效期在未来（15 分钟）");

        // 手机端引导信息（无 Authorization）：登录表单租户预填 + 不要求登录（A 默认关闭）
        Map<?, ?> context = data(get("/api/h5/upload/" + token + "/context", null));
        assertEquals("demoa", str(context.get("tenantCode")));
        assertEquals(Boolean.FALSE, context.get("requireLogin"));

        // 页面信息：患者脱敏 + 位置下拉（含预选 key）
        Map<?, ?> info = data(get("/api/h5/upload/" + token, null));
        // 登记名「周报告（示例）」的密文脱敏值（与全院列表行同口径，ReportSearchIT 同值）
        assertEquals("周*）", str(info.get("patientName")), "姓名脱敏（首尾保留）");
        String presetKey = str(info.get("presetKey"));
        assertNotNull(presetKey, "二维码预选位置应命中下拉项");
        List<?> options = (List<?>) info.get("locationOptions");
        assertTrue(options.size() >= 1, "位置下拉至少含预选位置");
        Map<?, ?> matched =
                (Map<?, ?>)
                        options.stream()
                                .map(o -> (Map<?, ?>) o)
                                .filter(o -> presetKey.equals(str(o.get("key"))))
                                .findFirst()
                                .orElseThrow();
        Map<?, ?> matchedValue = (Map<?, ?>) matched.get("value");
        assertEquals(
                str(categoryValue.get("anchorKey")),
                str(matchedValue.get("anchorKey")),
                "预选项与签发预选同锚点");

        // 上传两张图片（前端已压缩；服务端格式校验同电脑端）
        String file1 = h5Upload(token, "IT-E16T10-第1页（示例）.png");
        String file2 = h5Upload(token, "IT-E16T10-第2页（示例）.png");

        // 提交（手机端只选类型 / 名称 / 报告时间 / 位置；附件取令牌清单）。
        // LAB（检验）绑定名称字典（A 预置 V62），名称必填口径同电脑端
        List<?> labNames = (List<?>) get(BASE + "/report-names?typeCode=LAB", admin).get("data");
        String labNameId = str(((Map<?, ?>) labNames.get(0)).get("id"));
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", matchedValue.get("linkType"));
        location.put("anchorType", matchedValue.get("anchorType"));
        location.put("anchorKey", matchedValue.get("anchorKey"));
        location.put("anchorTemplateVersion", matchedValue.get("anchorTemplateVersion"));
        Map<?, ?> created =
                data(
                        post(
                                "/api/h5/upload/" + token + "/submit",
                                null,
                                Map.of(
                                        "typeCode",
                                        "LAB",
                                        "nameId",
                                        labNameId,
                                        "reportDate",
                                        LocalDate.now().toString(),
                                        "locations",
                                        List.of(location))));
        String reportId = str(created.get("id"));
        assertNotNull(reportId, "提交应直接生成报告单");
        assertEquals(2, ((Number) created.get("fileCount")).intValue(), "两页照片挂同一报告单");

        // 落库口径：报告单 + 位置行 + 附件行、创建人 = 签发人（nurse.demo）
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report WHERE id = " + reportId,
                                    Integer.class));
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = "
                                            + reportId,
                                    Integer.class));
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_file WHERE report_id = "
                                            + reportId,
                                    Integer.class));
                });

        // 手机端不留存：令牌一次性（提交后读信息 / 再提交均 40101，防探测同一句）
        assertEquals(40101, code(get("/api/h5/upload/" + token, null)), "已用令牌读信息 40101");
        assertEquals(
                40101,
                code(post("/api/h5/upload/" + token + "/submit", null, Map.of())),
                "已用令牌再提交 40101");

        // 电脑端轮询：SUBMITTED + reportId（前端随即刷新患者内清单）
        Map<?, ?> status = data(get(BASE + "/upload-tokens/" + token, admin));
        assertEquals("SUBMITTED", str(status.get("status")));
        assertEquals(reportId, str(status.get("reportId")));

        // 患者内清单可见（doctor 只读也可见）
        List<?> reports =
                (List<?>)
                        data(get(BASE + "/patients/" + patientId + "/reports", doctor))
                                .get("items");
        assertTrue(
                reports.stream().anyMatch(r -> reportId.equals(str(((Map<?, ?>) r).get("id")))),
                "手机上传的报告单进入患者内清单");
    }

    /** 验收「二维码过期失效」：TTL 到点（删 Redis 键模拟）后公开口一律 40101。 */
    @Test
    void expiredTokenFailsWithSingleMessage() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin);
        String token =
                str(
                        data(post(BASE + "/upload-tokens", admin, Map.of("patientId", patientId)))
                                .get("token"));
        assertEquals(0, code(get("/api/h5/upload/" + token, null)), "签发后立即可用");

        // 模拟 15 分钟到点：键过期消失
        redis.delete("crdp:h5-upload:" + token);

        Map<?, ?> body = get("/api/h5/upload/" + token, null);
        assertEquals(40101, code(body), "过期读信息 40101");
        assertEquals("二维码已过期，请在电脑上重新生成", str(body.get("message")));
        assertEquals(40101, code(get("/api/h5/upload/" + token + "/context", null)));
        assertEquals(40101, code(h5UploadRaw(token, "IT-E16T10-过期（示例）.png")), "过期上传 40101");
        assertEquals(40101, code(post("/api/h5/upload/" + token + "/submit", null, Map.of())));
        // 电脑端轮询报 EXPIRED（提示重新生成）
        assertEquals(
                "EXPIRED", str(data(get(BASE + "/upload-tokens/" + token, admin)).get("status")));
    }

    /** 电脑端闸门：无 followup:scan:upload 40300、越范围患者 40400、模块关闭 40301（开启后恢复）。 */
    @Test
    void issueGuards() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long patientId = registeredPatient(admin);

        assertEquals(
                40300,
                code(post(BASE + "/upload-tokens", doctor, Map.of("patientId", patientId))),
                "FOLLOWUP_DOCTOR 无 followup:scan:upload → 40300");

        String tenantB = hospitalToken("demob");
        assertEquals(
                40400,
                code(post(BASE + "/upload-tokens", tenantB, Map.of("patientId", patientId))),
                "跨租户患者 40400（独立租户库）");

        try {
            moduleService.close(
                    1L,
                    "followup",
                    new com.crdp.server.platform.TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(post(BASE + "/upload-tokens", admin, Map.of("patientId", patientId))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(
                    1L,
                    "followup",
                    new com.crdp.server.platform.TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(post(BASE + "/upload-tokens", admin, Map.of("patientId", patientId))));
    }

    /** C-NFR-006：打开 h5_upload_login 后手机端须登录本院账号（40100 引导），/context 豁免，同租户会话放行。 */
    @Test
    void loginRequiredTenantRequiresSameHospitalSession() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin);
        String token =
                str(
                        data(post(BASE + "/upload-tokens", admin, Map.of("patientId", patientId)))
                                .get("token"));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO cfg_feature (feature_code, enabled) VALUES"
                                        + " ('h5_upload_login', 1)"));

        // 引导口豁免（登录表单要先拿租户编码）
        assertEquals(0, code(get("/api/h5/upload/" + token + "/context", null)));
        // 未登录：40100 引导登录
        Map<?, ?> rejected = get("/api/h5/upload/" + token, null);
        assertEquals(40100, code(rejected));
        assertTrue(str(rejected.get("message")).contains("请先登录本院账号"));
        // 同租户医院会话（手机端登录后）放行；跨租户会话仍拒
        assertEquals(0, code(get("/api/h5/upload/" + token, admin)));
        String tenantB = hospitalToken("demob");
        assertEquals(40100, code(get("/api/h5/upload/" + token, tenantB)), "跨租户会话同样拒");
    }

    // ---------------------------------------------------------------- 辅助（与 ReportIT 同口径）

    private long registeredPatient(String token) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周报告（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId("demoa"));
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 基线首个分类位置的 value（预选口径与添加报告单对话框一致）。 */
    private Map<?, ?> firstBaselineCategory(String token, long patientId) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baseline =
                (Map<?, ?>)
                        roots.stream()
                                .filter(
                                        node ->
                                                "baseline"
                                                        .equals(str(((Map<?, ?>) node).get("key"))))
                                .findFirst()
                                .orElseThrow();
        return (Map<?, ?>) ((Map<?, ?>) ((List<?>) baseline.get("children")).get(0)).get("value");
    }

    /** H5 上传一张 PNG（无 Authorization），返回 fileId 字符串。 */
    private String h5Upload(String token, String filename) {
        Map<?, ?> response = h5UploadEntity(token, filename);
        assertEquals(0, code(response), "H5 上传失败：" + response);
        return str(data(response).get("fileId"));
    }

    /** H5 上传原始响应（过期断言用）。 */
    private Map<?, ?> h5UploadRaw(String token, String filename) {
        return h5UploadEntity(token, filename);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> h5UploadEntity(String token, String filename) {
        HttpHeaders headers = new HttpHeaders();
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
        return (Map<?, ?>)
                rest.postForEntity(
                                "/api/h5/upload/" + token + "/files",
                                new HttpEntity<>(parts, headers),
                                Map.class)
                        .getBody();
    }

    /** 合法 1×1 PNG（能通过上传白名单与 Tika 解码，与 ReportIT 同一张）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private static long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    @Autowired private com.crdp.server.platform.TenantModuleService moduleService;

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
                rest.exchange(
                                path,
                                method,
                                body == null
                                        ? new HttpEntity<>(headers)
                                        : new HttpEntity<>(body, headers),
                                Map.class)
                        .getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
