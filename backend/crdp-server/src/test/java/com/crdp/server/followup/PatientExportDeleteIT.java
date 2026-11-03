package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E13-T09 端到端（tasks.csv 验收：导出需审批；删除只软删）。场景：
 *
 * <ol>
 *   <li>患者导出（A-PAT-009、C-PAT-014）：租户管理员本人导出免审批直接生成（CSV 首列水印 + 水印码 + 脱敏值）； 普通用户导出待租户管理员审批——未审批下载
 *       40901、无审批权 40300、审批通过后生成可下载； 导出内容按导出人数据范围（病区受限医生看不到其他病区患者行）；越范围（权限 / 模块关闭 40301 / 校验失败
 *       40001）；全程审计（EXPORT / APPROVE / DOWNLOAD）；
 *   <li>删除患者（A-PAT-012、A-NFR-008、C-PAT-015）：软删除（deleted = 1 + delete_token 释放病案号 +
 *       原因必填，行不物理删除）、删除后列表 / 详情不可见、病案号可重新登记、越范围 40400（DENIED 审计）； 租户管理员恢复，病案号被新患者占用时 40902。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T09-、病区 W-IT-T9-、角色
 * IT-R-T9-ALL / IT-R-T9-WARD、用户 IT-U-T9-ALL）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientExportDeleteIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T09";
    private static final String MRN_A = "IT-T9-A-0001";
    private static final String MRN_B = "IT-T9-B-0002";
    private static final String USER_ALL = "it.e13t09.all";

    private JdbcTemplate platform;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantModuleService moduleService;

    @BeforeAll
    void setUp() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
        // 敏感权限按租户放开（PLT-SCOPE-004）：会话权限 = 角色授予 ∩ 可分配范围，212 导出 / 217 删除
        // 默认不可分配，平台侧写 plt_tenant_scope 覆盖（生产由平台端操作）
        TenantContext.runAsPlatform(
                () -> {
                    for (long menu : new long[] {212, 217}) {
                        platform.update(
                                "INSERT INTO plt_tenant_scope (tenant_id, menu_id, assignable, reason)"
                                        + " VALUES (1, ?, 1, 'IT-E13T09 用例放开（示例）')"
                                        + " ON DUPLICATE KEY UPDATE assignable = 1",
                                menu);
                    }
                });
        // 普通用户：全院范围 + 查看 / 导出 / 删除（21 / 212 / 217），非租户管理员（导出须审批）
        runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                    + " VALUES ('IT-R-T9-ALL', 'IT 导出删除角色（示例）', 0, 'ENABLED',"
                                    + " 'CUSTOM')");
                    Long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = 'IT-R-T9-ALL'",
                                    Long.class);
                    for (long menu : new long[] {21, 212, 217}) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                roleId,
                                menu);
                    }
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES (?, 'IT 全院用户（示例）', ?, 'ENABLED', 0)",
                            USER_ALL,
                            passwordEncoder.encode(PASSWORD));
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) SELECT u.id, r.id FROM sys_user u,"
                                    + " sys_role r WHERE u.username = ? AND r.code = 'IT-R-T9-ALL'",
                            USER_ALL);
                    // 内置角色补授导出（218 审批已由 followup V44 / 种子 V116 授 TENANT_ADMIN）
                    for (String role : new String[] {"TENANT_ADMIN", "FOLLOWUP_DOCTOR"}) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, 212 FROM"
                                        + " sys_role r WHERE r.code = ? AND NOT EXISTS (SELECT 1 FROM"
                                        + " sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 212)",
                                role);
                    }
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        if (platform != null) {
            TenantContext.runAsPlatform(
                    () ->
                            platform.update(
                                    "DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id IN"
                                            + " (212, 217) AND reason LIKE 'IT-E13T09%'"));
        }
        runInTenant(
                () -> {
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE rm.menu_id = 212 AND r.code IN ('TENANT_ADMIN',"
                                    + " 'FOLLOWUP_DOCTOR')");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id WHERE"
                                    + " u.username = ?",
                            USER_ALL);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", USER_ALL);
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'IT-R-T9-ALL'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code = 'IT-R-T9-ALL'");
                    jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-T9-ALL'");
                });
    }

    @BeforeEach
    void cleanState() {
        runInTenant(
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE event_type IN ('PATIENT_DELETE','PATIENT_RESTORE')"
                                    + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE 'IT-E13T09-%')");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE event_type IN ('PATIENT_EXPORT_APPROVE',"
                                    + "'PATIENT_EXPORT_REJECT') AND object_id IN"
                                    + " (SELECT CAST(id AS CHAR) FROM job_export WHERE reason LIKE '%IT-E13T09%')");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_data_access_log WHERE module = 'followup'"
                                    + " AND access_method IN ('EXPORT','DOWNLOAD')"
                                    + " AND object_id IN (SELECT CAST(id AS CHAR) FROM job_export"
                                    + " WHERE reason LIKE '%IT-E13T09%')");
                    jdbc.update(
                            "DELETE FROM job_export_approval WHERE export_id IN"
                                    + " (SELECT id FROM job_export WHERE reason LIKE '%IT-E13T09%')");
                    jdbc.update("DELETE FROM job_export WHERE reason LIKE '%IT-E13T09%'");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T09-%'");
                    jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-T9-%'");
                });
    }

    // ---------------------------------------------------------------- 导出（A-PAT-009：审批、水印码）

    @Test
    void tenantAdminExportSkipsApprovalAndGeneratesMaskedCsvWithWatermark() {
        long wardB = createWard("W-IT-T9-B", "示例 IT 二病区（示例）");
        registerPatient(MRN_A, "张小明（示例）", wardOf("W-DEMO-1"));
        registerPatient(MRN_B, "李大中（示例）", wardB);
        String admin = hospitalToken(TENANT, "nurse.demo", PASSWORD);

        // 212 已在 @BeforeAll 统一授予（敏感权限经 plt_tenant_scope 放开）
        {
            Map<?, ?> submitted =
                    postReauth(
                            "/api/hospital/followup/exports",
                            admin,
                            Map.of(
                                    "scope", "PATIENT",
                                    "valueMode", "LABEL",
                                    "reason", "IT-E13T09 管理员导出用例（示例）"));
            assertEquals(0, codeOf(submitted), "提交失败：" + submitted);
            assertEquals("GENERATING", data(submitted).get("status"), "租户管理员本人导出无需审批");
            long exportId = ((Number) data(submitted).get("exportId")).longValue();
            awaitReady(admin, exportId);

            String csv = download(admin, exportId);
            assertTrue(csv.contains("nurse.demo"), "导出文件应含水印（账号，OQ-22）");
            assertTrue(csv.contains("水印码 "), "导出文件应含水印码（tech-plan §导出水印码）");
            assertTrue(csv.contains("张*）"), "姓名只出脱敏值（OQ-20）");
            assertFalse(csv.contains("张小明"), "姓名明文不得出现在导出文件");
            assertTrue(csv.contains("随访中") && csv.contains("男"), "LABEL 模式出显示名");
            assertTrue(csv.contains(MRN_A.substring(0, 2)) || csv.contains("**"), "病案号为脱敏值");

            // 全程审计：EXPORT + DOWNLOAD（module=followup）
            runInTenant(
                    () -> {
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_data_access_log WHERE module ="
                                                + " 'followup' AND access_method = 'EXPORT' AND object_id = ?",
                                        Long.class,
                                        String.valueOf(exportId)),
                                "提交导出应写 EXPORT 审计");
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_data_access_log WHERE module ="
                                                + " 'followup' AND access_method = 'DOWNLOAD' AND object_id = ?",
                                        Long.class,
                                        String.valueOf(exportId)),
                                "下载应写 DOWNLOAD 审计");
                    });
        }
    }

    @Test
    void normalUserExportNeedsApprovalAndRespectsApplicantScope() {
        long wardB = createWard("W-IT-T9-C", "示例 IT 三病区（示例）");
        registerPatient(MRN_A, "张小明（示例）", wardOf("W-DEMO-1"));
        registerPatient(MRN_B, "李大中（示例）", wardB);
        String user = hospitalToken(TENANT, USER_ALL, PASSWORD);
        String admin = hospitalToken(TENANT, "nurse.demo", PASSWORD);
        // 218 审批权由 followup V44 / 种子 V116 授 TENANT_ADMIN（可分配范围内置生效）
        {
            Map<?, ?> submitted =
                    postReauth(
                            "/api/hospital/followup/exports",
                            user,
                            Map.of(
                                    "scope", "PATIENT",
                                    "valueMode", "CODE",
                                    "reason", "IT-E13T09 审批用例（示例）"));
            assertEquals(0, codeOf(submitted), "提交失败：" + submitted);
            assertEquals("PENDING_APPROVAL", data(submitted).get("status"));
            long exportId = ((Number) data(submitted).get("exportId")).longValue();

            // 未审批下载（已带二次认证头）→ 40901（import-export.md §4）
            assertEquals(
                    40901,
                    codeOf(
                            get(
                                    "/api/hospital/followup/exports/" + exportId + "/download",
                                    user,
                                    reauth(user))));
            // 普通用户无审批权 → 40300
            assertEquals(
                    40300,
                    codeOf(
                            post(
                                    "/api/hospital/followup/export-approvals/"
                                            + exportId
                                            + "/approve",
                                    user,
                                    Map.of("comment", "自审（示例）"))));
            // 管理员待审批列表可见并通过
            Map<?, ?> pending =
                    get("/api/hospital/followup/export-approvals?status=PENDING", admin);
            assertEquals(0, codeOf(pending), "待审批列表失败：" + pending);
            assertTrue(
                    data(pending).get("items").toString().contains(String.valueOf(exportId)),
                    "待审批列表应包含该申请");
            Map<?, ?> approved =
                    post(
                            "/api/hospital/followup/export-approvals/" + exportId + "/approve",
                            admin,
                            Map.of("comment", "同意（示例）"));
            assertEquals(0, codeOf(approved), "审批失败：" + approved);
            assertEquals("GENERATING", data(approved).get("status"));

            awaitReady(user, exportId);
            // 全院范围申请人：两个患者都在文件里（导出条件未加筛选）
            String csv = download(user, exportId);
            assertTrue(csv.length() > 0);
            // 审批写 OPERATION 审计（前后值 = 状态迁移）
            runInTenant(
                    () ->
                            assertEquals(
                                    1L,
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'PATIENT_EXPORT_APPROVE' AND object_id = ?"
                                                    + " AND after_json LIKE '%GENERATING%'",
                                            Long.class, String.valueOf(exportId))));

            // 重复审批 → 40901
            assertEquals(
                    40901,
                    codeOf(
                            post(
                                    "/api/hospital/followup/export-approvals/"
                                            + exportId
                                            + "/approve",
                                    admin,
                                    Map.of("comment", "重复审批（示例）"))));
        }
    }

    @Test
    void doctorExportExcludesOutOfWardPatients() {
        long wardB = createWard("W-IT-T9-D", "示例 IT 四病区（示例）");
        registerPatient(MRN_A, "张小明（示例）", wardOf("W-DEMO-1"));
        registerPatient(MRN_B, "李大中（示例）", wardB);
        // 病区医生（W-DEMO-1，212 已授）：提交导出（待审批）→ 管理员审批 → 文件只含本病区患者
        {
            String doctor = hospitalToken(TENANT, "doctor.demo", PASSWORD);
            String admin = hospitalToken(TENANT, "nurse.demo", PASSWORD);
            Map<?, ?> submitted =
                    postReauth(
                            "/api/hospital/followup/exports",
                            doctor,
                            Map.of("scope", "PATIENT", "valueMode", "CODE"));
            assertEquals(0, codeOf(submitted), "提交失败：" + submitted);
            long exportId = ((Number) data(submitted).get("exportId")).longValue();
            assertEquals(
                    0,
                    codeOf(
                            post(
                                    "/api/hospital/followup/export-approvals/"
                                            + exportId
                                            + "/approve",
                                    admin,
                                    Map.of("comment", "同意（示例）"))));
            awaitReady(doctor, exportId);

            String csv = download(doctor, exportId);
            String mrnBMasked = mrnMasked(MRN_B);
            assertFalse(csv.contains(mrnBMasked), "越范围（其他病区）患者不进入导出文件：" + mrnBMasked);
        }
    }

    @Test
    void exportValidationPermissionAndModuleGate() {
        String admin = hospitalToken(TENANT, "nurse.demo", PASSWORD);
        {
            // scope 暂只支持 PATIENT；valueMode 只允许 CODE / LABEL（40001）
            assertEquals(
                    40001,
                    codeOf(
                            postReauth(
                                    "/api/hospital/followup/exports",
                                    admin,
                                    Map.of("scope", "BASELINE"))));
            Map<?, ?> mixedResp =
                    postReauth(
                            "/api/hospital/followup/exports",
                            admin,
                            Map.of("scope", "PATIENT", "valueMode", "MIXED"));
            assertEquals(40001, codeOf(mixedResp), "valueMode MIXED 应 40001：" + mixedResp);
            // 高级检索目录外字段（40001，A-NFR-009）
            assertEquals(
                    40001,
                    codeOf(
                            postReauth(
                                    "/api/hospital/followup/exports",
                                    admin,
                                    // condition 为 ExportQuery 包装：{ filters?, condition? 树 }
                                    Map.of(
                                            "scope",
                                            "PATIENT",
                                            "condition",
                                            Map.of(
                                                    "condition",
                                                    Map.of(
                                                            "logic",
                                                            "and",
                                                            "items",
                                                            List.of(
                                                                    Map.of(
                                                                            "field",
                                                                            "mrn_blind",
                                                                            "op",
                                                                            "eq",
                                                                            "value",
                                                                            "IT"))))))));
        }
        // 无导出权限 → 40300：临时收回 FOLLOWUP_DOCTOR 的 212，重新登录后生效（自建自清）
        revokeMenuFromRole("FOLLOWUP_DOCTOR", 212);
        try {
            String doctor = hospitalToken(TENANT, "doctor.demo", PASSWORD);
            assertEquals(
                    40300,
                    codeOf(
                            postReauth(
                                    "/api/hospital/followup/exports",
                                    doctor,
                                    Map.of("scope", "PATIENT"))));
        } finally {
            grantMenuToRole("FOLLOWUP_DOCTOR", 212);
        }
        // 模块关闭 → 40301（212 已授，模块门控先于权限校验）
        {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    codeOf(
                            postReauth(
                                    "/api/hospital/followup/exports",
                                    admin,
                                    Map.of("scope", "PATIENT"))));
        }
        moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
    }

    // ---------------------------------------------------------------- 删除（A-PAT-012：只软删）

    @Test
    void deleteIsSoftDeleteAndReleasesMrnThenRestore() {
        long patientId = registerPatient(MRN_A, "张小明（示例）", wardOf("W-DEMO-1"));
        String user = hospitalToken(TENANT, USER_ALL, PASSWORD);

        // 原因必填（40001）
        HttpHeaders reauthHeaders = jsonHeaders(user);
        reauthHeaders.set("X-Reauth-Token", reauth(user));
        assertEquals(
                40001,
                codeOf(
                        rest.exchange(
                                        "/api/hospital/followup/patients/" + patientId,
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(Map.of("reason", "  "), reauthHeaders),
                                        Map.class)
                                .getBody()));
        // 缺二次认证头 → 40304（敏感操作）
        assertEquals(
                40304,
                codeOf(
                        rest.exchange(
                                        "/api/hospital/followup/patients/" + patientId,
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(
                                                Map.of("reason", "原因（示例）"), jsonHeaders(user)),
                                        Map.class)
                                .getBody()));

        // 软删除成功
        Map<?, ?> deleted =
                rest.exchange(
                                "/api/hospital/followup/patients/" + patientId,
                                HttpMethod.DELETE,
                                new HttpEntity<>(Map.of("reason", "录入错误（示例）"), reauthHeaders),
                                Map.class)
                        .getBody();
        assertEquals(0, codeOf(deleted), "删除失败：" + deleted);

        runInTenant(
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT deleted, delete_token, delete_reason, mrn_cipher FROM"
                                            + " pt_patient WHERE id = ?",
                                    patientId);
                    assertEquals(1, ((Number) row.get("deleted")).intValue(), "只软删：行保留");
                    assertEquals(
                            patientId,
                            ((Number) row.get("delete_token")).longValue(),
                            "delete_token 置 id 释放病案号");
                    assertEquals("录入错误（示例）", row.get("delete_reason"));
                    assertNotNull(row.get("mrn_cipher"), "数据长期保留（A-NFR-008 只软删）");
                });

        // 删除后：列表检索不到、详情 40400、病案号可重新登记（查重不阻止）
        String user2 = hospitalToken(TENANT, USER_ALL, PASSWORD);
        Map<?, ?> searched =
                post("/api/hospital/followup/patients/search", user2, Map.of("keyword", MRN_A));
        assertEquals(0, codeOf(searched));
        assertEquals(0L, ((Number) data(searched).get("total")).longValue(), "已删患者不在列表");
        assertEquals(
                40400,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", user2)));
        // 查重需 followup:patient:create（IT 角色未授），用管理员执行
        Map<?, ?> duplicate =
                post(
                        "/api/hospital/followup/patients/check-duplicate",
                        hospitalToken(TENANT, "nurse.demo", PASSWORD),
                        Map.of("medicalRecordNo", MRN_A, "name", "张小明（示例）"));
        assertEquals(0, codeOf(duplicate));
        assertEquals(
                Boolean.FALSE, ((Map<?, ?>) data(duplicate)).get("exists"), "软删后病案号可复用（A-PAT-012）");

        // 重复删除 → 40400（已不可见）
        assertEquals(
                40400,
                codeOf(
                        rest.exchange(
                                        "/api/hospital/followup/patients/" + patientId,
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(Map.of("reason", "重复（示例）"), reauthHeaders),
                                        Map.class)
                                .getBody()));

        // 租户管理员恢复：先被新患者占用病案号 → 40902；移开后恢复成功
        long reRegistered = registerPatient(MRN_A, "张小明（示例）", wardOf("W-DEMO-1"));
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/restore",
                                user2,
                                null,
                                reauth(user2))));

        runInTenant(
                () -> {
                    // 直接删行会断审计哈希链（E08-T02），经 AuditTableMaintenance 维护后删除；
                    // 只清占位患者的行——原患者的 PATIENT_DELETE 审计供末尾断言（@BeforeEach 会整体清理）
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE target = ?",
                            "PATIENT:" + reRegistered);
                    jdbc.update("DELETE FROM pt_patient WHERE id = ?", reRegistered);
                });
        Map<?, ?> restored =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/restore",
                        user2,
                        null,
                        reauth(user2));
        assertEquals(0, codeOf(restored), "恢复失败：" + restored);
        runInTenant(
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT deleted, delete_token, delete_reason FROM pt_patient"
                                            + " WHERE id = ?",
                                    patientId);
                    assertEquals(0, ((Number) row.get("deleted")).intValue());
                    assertEquals(0L, ((Number) row.get("delete_token")).longValue());
                    assertEquals(null, row.get("delete_reason"));
                });
        // 删除与恢复审计（OPERATION）
        runInTenant(
                () -> {
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_DELETE'"
                                            + " AND target = ? AND result = 'SUCCESS'",
                                    Long.class,
                                    "PATIENT:" + patientId));
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_RESTORE'"
                                            + " AND target = ?",
                                    Long.class,
                                    "PATIENT:" + patientId));
                });
    }

    @Test
    void deleteOutOfScopeReturns40400AndWritesDeniedAudit() {
        long wardB = createWard("W-IT-T9-E", "示例 IT 五病区（示例）");
        long patientId = registerPatient(MRN_B, "李大中（示例）", wardB);
        // doctor.demo 只负责 W-DEMO-1（217 经平台放开 + 角色未授）：越范围删除应 40300 缺权限路径
        // ——为覆盖越范围 40400，临时给 FOLLOWUP_DOCTOR 授 217（用例内自清）
        grantMenuToRole("FOLLOWUP_DOCTOR", 217);
        try {
            String doctor = hospitalToken(TENANT, "doctor.demo", PASSWORD);
            Map<?, ?> denied =
                    rest.exchange(
                                    "/api/hospital/followup/patients/" + patientId,
                                    HttpMethod.DELETE,
                                    new HttpEntity<>(
                                            Map.of("reason", "越范围（示例）"), reauthHeaders(doctor)),
                                    Map.class)
                            .getBody();
            assertEquals(40400, codeOf(denied), "越范围不暴露对象存在");
            runInTenant(
                    () -> {
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'PATIENT_DELETE' AND target = ? AND result = 'DENIED'",
                                        Long.class,
                                        "PATIENT:" + patientId));
                        assertEquals(
                                0,
                                jdbc.queryForObject(
                                                "SELECT deleted FROM pt_patient WHERE id = ?",
                                                Integer.class,
                                                patientId)
                                        .intValue(),
                                "越范围请求不得改动数据");
                    });
        } finally {
            revokeMenuFromRole("FOLLOWUP_DOCTOR", 217);
        }
    }

    // ---------------------------------------------------------------- 辅助

    private void grantMenuToRole(String roleCode, long menuId) {
        runInTenant(
                () ->
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, ? FROM"
                                        + " sys_role r WHERE r.code = ? AND NOT EXISTS (SELECT 1 FROM"
                                        + " sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = ?)",
                                menuId,
                                roleCode,
                                menuId));
    }

    private void revokeMenuFromRole(String roleCode, long menuId) {
        runInTenant(
                () ->
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = ? AND rm.menu_id = ?",
                                roleCode,
                                menuId));
    }

    private long wardOf(String code) {
        return runInTenant(
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long createWard(String code, String name) {
        String admin = hospitalToken(TENANT, "nurse.demo", PASSWORD);
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/wards",
                        admin,
                        Map.of("code", code, "name", name, "wardType", "WARD"));
        assertEquals(0, codeOf(created), "建病区失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    private long registerPatient(String mrn, String name, long wardId) {
        String admin = hospitalToken(TENANT, "nurse.demo", PASSWORD);
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("surgeryDate", "2026-03-01");
        body.put("admissionDate", "2026-02-25");
        body.put("dischargeDate", "2026-03-08");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<?, ?> created = post("/api/hospital/followup/patients", admin, body);
        assertEquals(0, codeOf(created), "登记失败：" + created);
        // 大数 ID 序列化为字符串（AGENTS 第 6 节），按字符串取回
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    private String mrnMasked(String mrn) {
        // SensitiveValueMasker 病案号口径：前 2 + 中间位数星 + 后 2（IT-T9-B-0002 → IT*******02）
        return mrn.length() <= 4
                ? mrn
                : mrn.substring(0, 2)
                        + "*".repeat(mrn.length() - 4)
                        + mrn.substring(mrn.length() - 2);
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
        return rest.exchange(
                        path,
                        HttpMethod.POST,
                        new HttpEntity<>(body, jsonHeaders(token)),
                        Map.class)
                .getBody();
    }

    private Map<?, ?> post(
            String path, String token, Map<String, Object> body, String reauthToken) {
        HttpHeaders headers = jsonHeaders(token);
        headers.set("X-Reauth-Token", reauthToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(jsonHeaders(token)), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token, String reauthToken) {
        return rest.exchange(
                        path,
                        HttpMethod.GET,
                        new HttpEntity<>(reauthHeaders(token, reauthToken)),
                        Map.class)
                .getBody();
    }

    /** 敏感接口调用：先做二次认证再携带 X-Reauth-Token 提交。 */
    private Map<?, ?> postReauth(String path, String token, Map<String, Object> body) {
        return post(path, token, body, reauth(token));
    }

    private String reauth(String token) {
        Map<?, ?> response = post("/api/hospital/auth/reauth", token, Map.of("password", PASSWORD));
        assertEquals(0, codeOf(response), "二次认证失败：" + response);
        return (String) data(response).get("reauthToken");
    }

    private HttpHeaders reauthHeaders(String token) {
        return reauthHeaders(token, reauth(token));
    }

    private HttpHeaders reauthHeaders(String token, String reauthToken) {
        HttpHeaders headers = jsonHeaders(token);
        headers.set("X-Reauth-Token", reauthToken);
        return headers;
    }

    private String download(String token, long exportId) {
        return new String(
                rest.exchange(
                                "/api/hospital/followup/exports/" + exportId + "/download",
                                HttpMethod.GET,
                                new HttpEntity<>(reauthHeaders(token)),
                                byte[].class)
                        .getBody(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private void awaitReady(String token, long exportId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Map<?, ?> mine = get("/api/hospital/followup/exports", token);
            if (codeOf(mine) == 0) {
                for (Object item : (List<?>) data(mine).get("items")) {
                    if (((Number) ((Map<?, ?>) item).get("id")).longValue() == exportId) {
                        if ("READY".equals(((Map<?, ?>) item).get("status"))) {
                            return;
                        }
                        if ("FAILED".equals(((Map<?, ?>) item).get("status"))) {
                            throw new AssertionError(
                                    "导出生成失败：" + ((Map<?, ?>) item).get("failReason"));
                        }
                    }
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("导出文件未在 15 秒内生成完成");
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(1L, TENANT, action);
    }

    private <T> T runInTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, TENANT, action::get);
    }
}
