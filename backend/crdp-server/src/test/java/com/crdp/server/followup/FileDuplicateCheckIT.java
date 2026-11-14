package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.nio.charset.StandardCharsets;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E21-T18 端到端 / C-SCAN-017（完整应用 + 真实 MySQL，HTTP 直连）。验收原文：同一文件再次上传给出提示—— 按文件 SHA-256
 * 提示已上传过及其所属患者，仅在可见范围内提示。
 *
 * <p>覆盖：挂报告单后同哈希再次上传命中（duplicate = true、患者脱敏、两名患者各一行、再次上传仍提示）； 刚上传未挂记录的 PENDING
 * 文件不命中（自查不命中自己）；附件软删后不再命中； 病区范围用户只见本病区患者（范围外不泄露）；无 followup:scan:upload 40300、模块关闭 40301、sha256 缺失
 * / 非法 40001。 全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileDuplicateCheckIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String UPLOAD = "/api/hospital/files";
    private static final String CHECK = "/api/hospital/files/check-duplicate";
    private static final String BASE = "/api/hospital/followup";
    private static final String LEGACY = "IT-E21T18";
    private static final String FILE_PREFIX = "IT-E21T18-";
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
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
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE FROM sys_file WHERE original_name LIKE ?", FILE_PREFIX + "%");
                    jdbc.update("DELETE FROM ward WHERE code IN ('W-IT-T18A', 'W-IT-T18B')");
                    jdbc.update(
                            "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                    + " WHERE u.username LIKE 'IT-E18T-%'");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username LIKE 'IT-E18T-%'");
                    jdbc.update(
                            "DELETE ph FROM sys_password_history ph"
                                    + " JOIN sys_user u ON u.id = ph.user_id"
                                    + " WHERE u.username LIKE 'IT-E18T-%'");
                    jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-E18T-%'");
                    // IT 专用角色（WARD 范围 + 上传按钮）整体撤销，不动内置角色
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'IT_E18T_SCOPE'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code = 'IT_E18T_SCOPE'");
                    jdbc.update("DELETE FROM sys_role WHERE code = 'IT_E18T_SCOPE'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action IN ('RPT_CREATE', 'FILE_UPLOAD')");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 任务验收：同一文件再次上传给出提示

    /** 挂报告单后：同哈希命中 duplicate = true、患者脱敏；再次上传同一文件仍提示；两名患者各一行。 */
    @Test
    void reuploadedFilePromptsWithMaskedPatients() {
        String admin = hospitalToken("demoa");
        long patientA = registeredPatient(admin, defaultWardId("demoa"), "周重复（示例）", LEGACY + "-A");
        long patientB = registeredPatient(admin, defaultWardId("demoa"), "吴重复（示例）", LEGACY + "-B");
        byte[] body = pdf("seed-e21t18-main");

        // 患者A 先上传并挂报告单（文件从此有患者归属）
        Map<?, ?> first = upload(admin, FILE_PREFIX + "报告（示例）.pdf", body);
        String sha256 = str(first.get("sha256"));
        createReport(admin, patientA, Long.parseLong(str(first.get("fileId"))));
        Map<?, ?> view = get(CHECK + "?sha256=" + sha256, admin);
        assertEquals(0, code(view), "查重失败：" + view);
        Map<?, ?> data = data(view);
        assertTrue((Boolean) data.get("duplicate"), "已挂报告单的文件应命中");
        List<?> patients = (List<?>) data.get("patients");
        assertEquals(1, patients.size(), "只列出患者A");
        Map<?, ?> row = (Map<?, ?>) patients.get(0);
        assertEquals(String.valueOf(patientA), str(row.get("patientId")));
        assertEquals("周*）", str(row.get("nameMasked")), "姓名脱敏（首尾 + 单星）");
        assertNotNull(row.get("mrnMasked"), "病案号脱敏值");
        assertFalse(String.valueOf(row.get("nameMasked")).contains("周重复"), "不泄露明文姓名");

        // 患者B 再次上传同一文件（任务验收场景）并挂自己的报告单 → 两名患者各一行
        Map<?, ?> second = upload(admin, FILE_PREFIX + "报告（示例）.pdf", body);
        assertEquals(sha256, str(second.get("sha256")), "同一内容同哈希");
        createReport(admin, patientB, Long.parseLong(str(second.get("fileId"))));
        view = get(CHECK + "?sha256=" + sha256, admin);
        data = data(view);
        assertTrue((Boolean) data.get("duplicate"), "再次上传同一文件给出提示");
        assertEquals(2, ((List<?>) data.get("patients")).size(), "两名患者各一行");
    }

    /** 刚上传、未挂任何记录的 PENDING 文件不命中（自查不能命中自己）；附件软删后不再命中。 */
    @Test
    void pendingAndDeletedFilesDoNotMatch() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"), "郑待挂（示例）", LEGACY + "-C");

        // 刚上传（PENDING、未挂报告单）：没有患者归属，不命中
        Map<?, ?> upload = upload(admin, FILE_PREFIX + "待挂（示例）.pdf", pdf("seed-e21t18-pending"));
        Map<?, ?> view = get(CHECK + "?sha256=" + str(upload.get("sha256")), admin);
        assertEquals(0, code(view));
        assertFalse((Boolean) data(view).get("duplicate"), "未挂记录的上传不命中（自查不算重复）");
        assertTrue(((List<?>) data(view).get("patients")).isEmpty());

        // 挂报告单后命中；附件软删（E16-T09 口径）后不再命中
        createReport(admin, patientId, Long.parseLong(str(upload.get("fileId"))));
        assertTrue(
                (Boolean)
                        data(get(CHECK + "?sha256=" + str(upload.get("sha256")), admin))
                                .get("duplicate"),
                "挂报告单后命中");
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE sys_file SET status = 'DELETED' WHERE id = ?",
                                Long.parseLong(str(upload.get("fileId")))));
        assertFalse(
                (Boolean)
                        data(get(CHECK + "?sha256=" + str(upload.get("sha256")), admin))
                                .get("duplicate"),
                "已删除附件不再命中");
    }

    /** 仅在可见范围内提示：病区医生只见本病区患者，范围外患者不泄露；管理员（ALL 范围）两病区都见。 */
    @Test
    void outOfScopePatientNotLeaked() {
        String admin = hospitalToken("demoa");
        long wardA = createWard("W-IT-T18A", "重复提示病区A（示例）");
        long wardB = createWard("W-IT-T18B", "重复提示病区B（示例）");
        long patientInA = registeredPatient(admin, wardA, "甲病区（示例）", LEGACY + "-D");
        long patientInB = registeredPatient(admin, wardB, "乙病区（示例）", LEGACY + "-E");
        byte[] body = pdf("seed-e21t18-scope");
        Map<?, ?> uploadA = upload(admin, FILE_PREFIX + "范围（示例）.pdf", body);
        createReport(admin, patientInA, Long.parseLong(str(uploadA.get("fileId"))));
        Map<?, ?> uploadB = upload(admin, FILE_PREFIX + "范围（示例）.pdf", body);
        createReport(admin, patientInB, Long.parseLong(str(uploadB.get("fileId"))));
        String sha256 = str(uploadA.get("sha256"));

        assertEquals(
                2,
                ((List<?>) data(get(CHECK + "?sha256=" + sha256, admin)).get("patients")).size(),
                "管理员两病区都见");

        String wardDoctor = wardDoctorOfWardAToken(wardA);
        Map<?, ?> scoped = data(get(CHECK + "?sha256=" + sha256, wardDoctor));
        assertTrue((Boolean) scoped.get("duplicate"), "本病区患者命中");
        List<?> rows = (List<?>) scoped.get("patients");
        assertEquals(1, rows.size(), "只见本病区患者");
        assertEquals(
                String.valueOf(patientInA),
                str(((Map<?, ?>) rows.get(0)).get("patientId")),
                "范围外患者不泄露（AGENTS §5.2 重复提示按当前用户范围计算）");
    }

    /** 无 followup:scan:upload 40300；模块关闭 40301；sha256 缺失 / 非法 40001。 */
    @Test
    void guardsAndValidation() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");

        assertEquals(
                40300, code(get(CHECK, doctor)), "FOLLOWUP_DOCTOR 无 followup:scan:upload → 40300");
        assertEquals(40001, code(get(CHECK, admin)), "缺 sha256 → 40001");
        assertEquals(40001, code(get(CHECK + "?sha256=not-a-hash", admin)), "非 64 位十六进制 → 40001");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301, code(get(CHECK + "?sha256=" + "a".repeat(64), admin)), "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(get(CHECK + "?sha256=" + "a".repeat(64), admin)), "恢复后正常");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportSearchIT 同口径）

    private long registeredPatient(String token, long wardId, String name, String legacyNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", name);
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", "2026-10-01");
        body.put("surgeryDate", "2026-10-02");
        body.put("dischargeDate", "2026-10-05");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 建一份挂附件的 LAB 报告单（位置 = 基线首个分类，锚点取位置树）。 */
    private void createReport(String token, long patientId, long fileId) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baselineRoot =
                (Map<?, ?>)
                        roots.stream()
                                .filter(node -> "baseline".equals(((Map<?, ?>) node).get("key")))
                                .findFirst()
                                .orElseThrow();
        String anchorKey =
                str(
                        ((Map<?, ?>)
                                        ((Map<?, ?>)
                                                        ((List<?>) baselineRoot.get("children"))
                                                                .get(0))
                                                .get("value"))
                                .get("anchorKey"));
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        String nameId = str(((Map<?, ?>) names.get(0)).get("id"));
        Map<?, ?> created =
                post(
                        BASE + "/reports",
                        token,
                        Map.of(
                                "patientId",
                                patientId,
                                "typeCode",
                                "LAB",
                                "nameId",
                                nameId,
                                "reportDate",
                                "2026-10-28",
                                "locations",
                                List.of(
                                        Map.of(
                                                "linkType",
                                                "baseline",
                                                "anchorType",
                                                "category",
                                                "anchorKey",
                                                anchorKey)),
                                "fileIds",
                                List.of(String.valueOf(fileId))));
        assertEquals(0, code(created), "创建报告单失败：" + created);
    }

    /** 上传一个 PDF（非图片，不走缩略图解码），返回 data（fileId 与 sha256）。 */
    private Map<?, ?> upload(String token, String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(body) {
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
        return data(response);
    }

    /** 最小 PDF（Tika 认 %PDF- 魔数；非图片不进缩略图解码）；注释行带种子保证不同内容不同哈希。 */
    private static byte[] pdf(String seed) {
        return ("%PDF-1.7\n%"
                        + "\u00e2\u00e3\u00cf\u00d3\n% "
                        + seed
                        + "\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                        + "trailer\n<< /Root 1 0 R >>\n%%EOF")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 病区A范围医生：IT 专用角色（WARD 范围 + 上传按钮 231「followup:scan:upload」），不动内置角色。 */
    private String wardDoctorOfWardAToken(long wardId) {
        String admin = hospitalToken("demoa");
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status, role_type,"
                                    + " module_code, description) VALUES"
                                    + " ('IT_E18T_SCOPE', '重复提示病区范围（示例）', 0, 'ENABLED',"
                                    + " 'CUSTOM', 'followup', 'IT 临时角色（E21-T18）')");
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id)"
                                    + " SELECT r.id, 231 FROM sys_role r WHERE r.code = 'IT_E18T_SCOPE'"
                                    + " AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm"
                                    + " WHERE rm.role_id = r.id AND rm.menu_id = 231)");
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type)"
                                    + " SELECT r.id, 'WARD' FROM sys_role r WHERE r.code = 'IT_E18T_SCOPE'"
                                    + " AND NOT EXISTS (SELECT 1 FROM sys_role_scope rs"
                                    + " WHERE rs.role_id = r.id AND rs.scope_type = 'WARD')");
                });
        long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        long roleId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_role WHERE code = 'IT_E18T_SCOPE'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username",
                                "IT-E18T-WD",
                                "displayName",
                                "病区医生（示例）",
                                "phone",
                                "13900001518",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardId)));
        assertEquals(0, code(created), "建病区范围用户失败：" + created);
        String temporary = str(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-E18T-WD", temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", "It#E21t18-Pass"))),
                "首登改密失败");
        return hospitalToken("demoa", "IT-E18T-WD", "It#E21t18-Pass");
    }

    private long createWard(String code, String name) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        return hospitalToken(tenant, username, PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
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
        return 1L;
    }
}
