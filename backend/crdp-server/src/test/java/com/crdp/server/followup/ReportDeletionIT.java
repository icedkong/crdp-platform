package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E16-T09 端到端 / A-ATT-006、A-ATT-007；C-SCAN-016（完整应用 + 真实 MySQL，HTTP 直连）。 验收原文：删除需原因，全部记审计。
 *
 * <p>覆盖：删除整份报告单（软删除 status=DELETED + 原因 / 删除人 / 删除时间落列，附件台账 sys_file 一并软删， 预览 / 下载随即 40400，写
 * RPT_DELETE 审计带原因）；删除单个文件（报告单保留、fileCount 递减、写 RPT_FILE_DELETE）；
 * 下载审计带「哪份报告单的哪个文件」（A-ATT-006，OQ-62① 的报告单部分——下载 / 预览按患者数据范围收紧）； 原因缺失 40001、无删除权限 40300、越病区 40400（写
 * DENIED 审计）、跨租户 40400、模块关闭 40301。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportDeletionIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String UPLOAD = "/api/hospital/files";
    private static final String FILES = "/api/hospital/files";
    private static final String LEGACY = "IT-E16T09";

    /** 越病区用例的病区医生（A_WARD_DOCTOR + 指定病区 + 本用例补授删除按钮 233）。 */
    private static final String WARD_USER = "IT-T9-WD";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeAll
    void setUpOnce() {
        // 用例自足的权限补授（与 PatientExportDeleteIT 同口径，AfterAll 收回）：
        // 233 删除按钮给 A_WARD_DOCTOR——越病区用例要的是「有权限但越范围 → 40400」。
        // （TENANT_ADMIN 的 231 上传 / 233 删除由种子 V125 / V126 授予，无需用例再补。）
        grantMenuToRole("A_WARD_DOCTOR", 233);
    }

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
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
                        jdbc.update(
                                "DELETE FROM sys_file WHERE original_name LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-T9'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action IN ('RPT_DELETE', 'RPT_FILE_DELETE')");
                        // 下载是数据访问审计（PLT-AUD-003，独立表），按本用例文件名前缀清理
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE action = 'DOWNLOAD' AND"
                                        + " description LIKE '下载文件：IT-E16T09-%'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () -> {
                    // 收回本用例补授的按钮，避免影响其他用例 / 后续运行
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'A_WARD_DOCTOR' AND rm.menu_id = 233");
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'TENANT_ADMIN' AND rm.menu_id = 232");
                    jdbc.update(
                            "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", WARD_USER);
                });
        // 平台侧收回下载敏感权限的放开（本用例 grantDownloadToAdmin 写入 plt_tenant_scope）：
        // 不清理会污染平台授权范围用例的「当前可分配全集」口径（与租户侧补授同款收回）
        TenantContext.runAsPlatform(
                () ->
                        platformJdbc()
                                .update(
                                        "DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND"
                                                + " menu_id = 232 AND reason ="
                                                + " 'IT-E16T09 用例放开（示例）'"));
    }

    /** 平台库连接（敏感权限放开写在平台侧；与 PatientExportDeleteIT 同款直连）。 */
    private JdbcTemplate platformJdbc() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:"
                                + port
                                + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                        "crdp_app",
                        "DevOnly#app2026"));
    }

    /** 角色补授按钮（幂等；与 PatientExportDeleteIT.grantMenuToRole 同口径）。 */
    private void grantMenuToRole(String roleCode, long menuId) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, ? FROM"
                                        + " sys_role r WHERE r.code = ? AND NOT EXISTS (SELECT 1 FROM"
                                        + " sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = ?)",
                                menuId,
                                roleCode,
                                menuId));
    }

    // ---------------------------------------------------------------- 验收：删除需原因，全部记审计

    /** 删除整份报告单：软删除落列、附件台账一并软删、预览 / 下载随即 40400、审计带原因；重复删除 40400。 */
    @Test
    void deleteWholeReportSoftDeletesAndAudits() {
        String admin = hospitalToken("demoa");
        Created created = createReportWithFiles(admin, 2);
        long reportId = created.reportId();

        Map<?, ?> deleted =
                delete(BASE + "/reports/" + reportId, admin, Map.of("reason", "重复上传，删除误传份"));
        assertEquals(0, code(deleted), "删除失败：" + deleted);
        assertEquals("DELETED", str(data(deleted).get("status")));

        // 患者内清单不再出现（软删除对读取生效）
        Map<?, ?> page = get(BASE + "/patients/" + created.patientId() + "/reports", admin);
        assertTrue(
                itemsOf(page).stream().noneMatch(item -> str(item.get("id")).equals(str(reportId))),
                "已删报告单不再出现在患者内清单");

        // 落库断言：status=DELETED + 原因 / 删除人 / 删除时间（V65 三列）
        Map<String, Object> row =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT status, delete_reason, deleted_by, deleted_at FROM"
                                                + " rpt_report WHERE id = ?",
                                        reportId));
        assertEquals("DELETED", row.get("status"));
        assertEquals("重复上传，删除误传份", row.get("delete_reason"));
        assertNotNull(row.get("deleted_by"), "删除人落列");
        assertNotNull(row.get("deleted_at"), "删除时间落列");

        // 附件台账一并软删：预览 / 下载一律 40400（A-ATT-007 软删除 + 台账状态优先）
        for (long fileId : created.fileIds()) {
            assertEquals(
                    40400, code(get(FILES + "/" + fileId + "/preview-url", admin)), "已删文件预览 40400");
        }
        assertEquals(
                2L,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_file WHERE id IN ("
                                                + created.fileIds().get(0)
                                                + ", "
                                                + created.fileIds().get(1)
                                                + ") AND status = 'DELETED'",
                                        Long.class)),
                "两个附件台账均已软删");

        // 审计：RPT_DELETE 一条（OPERATION，SUCCESS，描述带原因）
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_DELETE' AND object_id = ? AND"
                                                        + " detail LIKE '%重复上传，删除误传份%'",
                                                Long.class, String.valueOf(reportId)))
                        >= 1,
                "删除写 RPT_DELETE 审计且描述带原因");

        // 重复删除：已不在册（status != NORMAL）→ 40400 不暴露存在
        assertEquals(
                40400,
                code(delete(BASE + "/reports/" + reportId, admin, Map.of("reason", "再删一次"))),
                "重复删除 40400");
    }

    /** 删除单个文件：报告单保留、fileCount 递减、该附件预览 40400、另一附件不受影响；写 RPT_FILE_DELETE 审计。 */
    @Test
    void deleteSingleFileKeepsReportAndAudits() {
        String admin = hospitalToken("demoa");
        Created created = createReportWithFiles(admin, 2);
        long reportId = created.reportId();
        long removed = created.fileIds().get(0);
        long kept = created.fileIds().get(1);

        Map<?, ?> deleted =
                delete(
                        BASE + "/reports/" + reportId + "/files/" + removed,
                        admin,
                        Map.of("reason", "附件模糊不可辨识"));
        assertEquals(0, code(deleted), "删除附件失败：" + deleted);

        // 报告单仍在，fileCount 2 → 1（计数排除已软删附件）
        Map<?, ?> page = get(BASE + "/patients/" + created.patientId() + "/reports", admin);
        Map<?, ?> row =
                itemsOf(page).stream()
                        .filter(item -> str(item.get("id")).equals(str(reportId)))
                        .findFirst()
                        .orElseThrow();
        assertEquals(1L, ((Number) row.get("fileCount")).longValue(), "删除后附件计数递减");

        // 该附件预览 40400、另一附件仍可预览（报告单未删）
        assertEquals(40400, code(get(FILES + "/" + removed + "/preview-url", admin)));
        assertEquals(0, code(get(FILES + "/" + kept + "/preview-url", admin)));

        // 审计：RPT_FILE_DELETE 一条，描述带原因与文件名
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_FILE_DELETE' AND object_id = ? AND"
                                                        + " detail LIKE '%模糊不可辨识%'",
                                                Long.class, String.valueOf(reportId)))
                        >= 1,
                "删除附件写 RPT_FILE_DELETE 审计且描述带原因");

        // 文件不在该报告单 → 40400
        assertEquals(
                40400,
                code(
                        delete(
                                BASE + "/reports/" + reportId + "/files/" + removed,
                                admin,
                                Map.of("reason", "再删一次"))),
                "已删附件再删 40400");
    }

    /** 下载审计带「哪份报告单的哪个文件」（A-ATT-006：谁、何时、哪份报告单的哪个文件）。 */
    @Test
    void downloadAuditCarriesReportContext() {
        String admin = hospitalToken("demoa");
        Created created = createReportWithFiles(admin, 1);
        grantDownloadToAdmin();

        Map<?, ?> granted =
                post(FILES + "/" + created.fileIds().get(0) + "/download-url", admin, Map.of());
        assertEquals(0, code(granted), "签发下载链接失败：" + granted);

        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_data_access_log WHERE"
                                                        + " action = 'DOWNLOAD' AND description"
                                                        + " LIKE '%报告单：%（#"
                                                        + created.reportId()
                                                        + "）'",
                                                Long.class))
                        >= 1,
                "下载审计描述带报告单上下文（哪份报告单的哪个文件）");
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 模块

    /** 原因缺失 40001；无删除权限（FOLLOWUP_DOCTOR）40300；模块关闭 40301。 */
    @Test
    void permissionReasonAndModuleGuards() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        Created created = createReportWithFiles(admin, 1);

        assertEquals(
                40001,
                code(delete(BASE + "/reports/" + created.reportId(), admin, Map.of())),
                "删除整份报告单原因缺失 40001");
        assertEquals(
                40001,
                code(
                        delete(
                                BASE + "/reports/" + created.reportId(),
                                admin,
                                Map.of("reason", "  "))),
                "空白原因 40001");
        assertEquals(
                40001,
                code(
                        delete(
                                BASE
                                        + "/reports/"
                                        + created.reportId()
                                        + "/files/"
                                        + created.fileIds().get(0),
                                admin,
                                Map.of())),
                "删除单个文件原因缺失 40001");
        // 原因缺失不落任何删除（报告单仍在清单中）
        Map<?, ?> page = get(BASE + "/patients/" + created.patientId() + "/reports", admin);
        assertTrue(
                itemsOf(page).stream()
                        .anyMatch(item -> str(item.get("id")).equals(str(created.reportId()))),
                "原因缺失的删除不生效");

        assertEquals(
                40300,
                code(
                        delete(
                                BASE + "/reports/" + created.reportId(),
                                doctor,
                                Map.of("reason", "医生删除"))),
                "FOLLOWUP_DOCTOR 无 followup:scan:delete → 40300");
        assertEquals(
                40300,
                code(
                        delete(
                                BASE
                                        + "/reports/"
                                        + created.reportId()
                                        + "/files/"
                                        + created.fileIds().get(0),
                                doctor,
                                Map.of("reason", "医生删除附件"))),
                "单文件删除同样 40300");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(
                            delete(
                                    BASE + "/reports/" + created.reportId(),
                                    admin,
                                    Map.of("reason", "模块关闭期删除"))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 恢复（示例）"));
        }
    }

    /** 越病区删除 40400（有权限但数据范围外，写 DENIED 审计）；越病区的报告单附件预览 / 下载同样 40400（OQ-62①）。 */
    @Test
    void outOfWardReportFileAndDeleteRejected() {
        String admin = hospitalToken("demoa");
        long ownWard = defaultWardId("demoa");
        long otherWard = createWard("W-IT-T9", "示例 IT 删除隔离病区（E16-T09）");
        // 患者落在「其他病区」，病区医生（本病区范围）看得见接口但看不见数据
        Created created = createReportWithFiles(admin, 1, otherWard);
        String wardUser = wardDoctorToken(ownWard);

        assertEquals(
                40400,
                code(
                        delete(
                                BASE + "/reports/" + created.reportId(),
                                wardUser,
                                Map.of("reason", "越病区删除"))),
                "越病区删除 40400（不暴露存在）");
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_DELETE' AND result = 'DENIED' AND"
                                                        + " object_id = ?",
                                                Long.class,
                                                String.valueOf(created.reportId())))
                        >= 1,
                "越范围删除写 DENIED 审计");

        // OQ-62①（报告单部分）：挂在越范围患者报告单上的文件，预览 / 下载一律 40400
        assertEquals(
                40400,
                code(get(FILES + "/" + created.fileIds().get(0) + "/preview-url", wardUser)),
                "越病区报告单附件预览 40400");
        grantDownloadToAdmin();
        // 下载口需敏感权限：先给病区医生放开（角色授予 + 平台可分配），再验范围拒绝
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, 232 FROM"
                                        + " sys_role r WHERE r.code = 'A_WARD_DOCTOR' AND NOT EXISTS"
                                        + " (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id"
                                        + " AND rm.menu_id = 232)"));
        try {
            String wardUserWithDownload = hospitalToken("demoa", WARD_USER, "It#E16t9-Pass");
            assertEquals(
                    40400,
                    code(
                            post(
                                    FILES + "/" + created.fileIds().get(0) + "/download-url",
                                    wardUserWithDownload,
                                    Map.of())),
                    "越病区报告单附件下载 40400");
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id ="
                                            + " rm.role_id WHERE r.code = 'A_WARD_DOCTOR' AND"
                                            + " rm.menu_id = 232"));
        }

        // 跨租户删除：独立租户库，40400
        String tenantB = hospitalToken("demob");
        assertEquals(
                40400,
                code(
                        delete(
                                BASE + "/reports/" + created.reportId(),
                                tenantB,
                                Map.of("reason", "跨租户删除"))),
                "跨租户删除 40400");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportIT 同口径）

    private record Created(long patientId, long reportId, List<Long> fileIds) {}

    /** 登记患者（默认病区或指定病区）→ 上传 n 个 PNG → 建报告单挂全部附件（基线首个分类）。 */
    private Created createReportWithFiles(String token, int fileCount) {
        return createReportWithFiles(token, fileCount, defaultWardId("demoa"));
    }

    private Created createReportWithFiles(String token, int fileCount, long wardId) {
        long patientId = registeredPatient(token, wardId);
        java.util.ArrayList<Long> fileIds = new java.util.ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            fileIds.add(uploadPng(token, LEGACY + "-报告" + i + ".png"));
        }
        // A 预置 10 类名称全部必填（OQ-95③）——取 LAB 首个名称（血常规，V62 预置）
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        String nameId = str(((Map<?, ?>) names.get(0)).get("id"));
        String categoryKey = baselineCategoryKey(token, patientId);
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "LAB");
        body.put("nameId", nameId);
        body.put("reportDate", "2026-10-28");
        body.put(
                "locations",
                List.of(
                        Map.of(
                                "linkType", "baseline",
                                "anchorType", "category",
                                "anchorKey", categoryKey)));
        body.put("fileIds", fileIds.stream().map(String::valueOf).toList());
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, code(created), "创建报告单失败：" + created);
        return new Created(patientId, Long.parseLong(str(data(created).get("id"))), fileIds);
    }

    /** 基线首个分类 key（位置树 baseline 根的第一个子节点）。 */
    private String baselineCategoryKey(String token, long patientId) {
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
        return str(
                ((Map<?, ?>) ((Map<?, ?>) ((List<?>) baseline.get("children")).get(0)).get("value"))
                        .get("anchorKey"));
    }

    private long registeredPatient(String token, long wardId) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "何删除（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 下载是敏感权限（V18 plt_sensitive_perm）：平台侧放开 + TENANT_ADMIN 角色授予（用例内放开，AfterAll 收回）。 */
    private void grantDownloadToAdmin() {
        TenantContext.runAsPlatform(
                () ->
                        platformJdbc()
                                .update(
                                        "INSERT INTO plt_tenant_scope (tenant_id, menu_id,"
                                                + " assignable, reason) VALUES (1, 232, 1,"
                                                + " 'IT-E16T09 用例放开（示例）')"
                                                + " ON DUPLICATE KEY UPDATE assignable = 1"));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, 232 FROM"
                                        + " sys_role r WHERE r.code = 'TENANT_ADMIN' AND NOT EXISTS"
                                        + " (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id"
                                        + " AND rm.menu_id = 232)"));
    }

    /** 上传一个 PNG（nurse.demo = TENANT_ADMIN，有 followup:scan:upload），返回 fileId。 */
    private long uploadPng(String token, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
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
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(UPLOAD, new HttpEntity<>(parts, headers), Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        assertEquals(0, code(response), "上传失败：" + response);
        return Long.parseLong(str(data(response).get("fileId")));
    }

    /** 合法 1×1 PNG（能通过上传白名单与 Tika 解码，与 ReportIT 同一张）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    /** 建病区医生（A_WARD_DOCTOR + 指定病区，已补授删除按钮 233），返回登录令牌。 */
    private String wardDoctorToken(long wardId) {
        String admin = hospitalToken("demoa");
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
                                        "SELECT id FROM sys_role WHERE code = 'A_WARD_DOCTOR'",
                                        Long.class));
        jdbcUpdateDeleteUser();
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username",
                                WARD_USER,
                                "displayName",
                                "病区医生（示例）",
                                "phone",
                                "13900001609",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardId)));
        assertEquals(0, code(created), "建病区范围用户失败：" + created);
        String temporary = str(data(created).get("temporaryPassword"));
        String newPassword = "It#E16t9-Pass";
        String token = hospitalToken("demoa", WARD_USER, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", newPassword))),
                "首登改密失败");
        return token;
    }

    /** 用户可能已在上次运行残留（AfterAll 删除；BeforeEach 兜底清一次）。 */
    private void jdbcUpdateDeleteUser() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", WARD_USER);
                });
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
                        Map.of(
                                "tenantCode", tenant,
                                "username", username,
                                "password", password));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> delete(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.DELETE, path, token, body);
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

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> itemsOf(Map<?, ?> response) {
        return (List<Map<?, ?>>) data(response).get("items");
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
