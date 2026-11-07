package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * E18-T08 集成锁定（全部虚构数据，A-ATT-002 位置必填变更）：历史报告单位置迁移。
 * 验收原文：迁移脚本可重入；按年关联的落到该年随访记录整条，原关联基线的进入未指定位置，迁移报告列出数量。
 *
 * <p>真库链路（门控 CRDP_IT_MYSQL_PORT）：登记患者 → 历史补录一条记录并按 E18-T05 年度锚点口径标 followup_year（模拟历史导入生成的年度记录）→
 * JDBC 装载三份旧关联报告单（followup V75 legacy 列，模拟旧系统 bulk 装载）→ 调迁移端点核对：按年关联落该年记录整条（模板版本 =
 * 记录固化版本）、基线关联零位置（未指定位置组可查）、 该年无记录的保持待迁移、重复调用零新增（可重入）、迁移报告列出数量；无导入权限 40300、模块关闭 40301。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportLegacyLocationMigrationIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    /** 造数与清理标记（全部虚构数据，AGENTS.md 规则 5）。 */
    private static final String LEGACY = "IT-E18T08";

    private static final String TENANT = "demoa";

    private static final long TENANT_ID = 1L;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        runInTenant(
                () -> {
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
                            jdbc,
                            "DELETE FROM aud_log WHERE action = 'RPT_LEGACY_LOCATION_MIGRATE'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：迁移口径与可重入

    /** 三份旧关联报告单一次迁移 + 重入零新增：按年落整条、基线进未指定、该年无记录保持待迁移、报告列数量。 */
    @Test
    void migratesLegacyAssociationsAndIsReentrant() {
        String nurse = hospitalToken(TENANT, "nurse.demo");
        long patientId = registeredPatient(nurse);
        long recordId = backfillRecord(nurse, patientId, "2026-03-10");
        // 模拟 E18-T05 历史导入年度记录：排期锚点 followup_year = 3（V73 列，任务录入记录不携带）
        runInTenant(
                () ->
                        jdbc.update(
                                "UPDATE fu_record SET followup_year = 3, source = 'HIST_IMPORT',"
                                        + " status = 'SUBMITTED' WHERE id = ?",
                                recordId));
        int recordVersion =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT template_version FROM fu_record WHERE id = ?",
                                        Integer.class,
                                        recordId));

        long yearReport = legacyReport(patientId, "YEAR", 3, "第3年心脏超声（示例）");
        long baselineReport = legacyReport(patientId, "BASELINE", null, "基线出院小结（示例）");
        long missingYearReport = legacyReport(patientId, "YEAR", 9, "第9年胸片（示例）");

        Map<?, ?> first = post(BASE + "/reports/legacy-location-migration", nurse, Map.of());
        assertEquals(0, code(first), "迁移失败：" + first);
        Map<?, ?> report = data(first);
        assertEquals(1, intOf(report.get("yearLinked")), "按年关联落到该年随访记录：" + report);
        assertEquals(1, intOf(report.get("baselineUnassigned")), "原关联基线进未指定位置：" + report);
        assertEquals(1, intOf(report.get("yearWithoutRecord")), "该年无记录保持待迁移：" + report);
        assertEquals(2, intOf(report.get("alreadyMigrated")), "迁移报告列出已迁移数量：" + report);

        // 按年关联 → 该年随访记录整条（锚点空、模板版本 = 记录固化版本），报告单版本推进
        Map<String, Object> link =
                runInTenant(
                        () ->
                                jdbc.queryForMap(
                                        "SELECT link_type, link_record_id, anchor_type, anchor_key,"
                                                + " template_version FROM rpt_report_link WHERE"
                                                + " report_id = ?",
                                        yearReport));
        assertEquals("followup_record", link.get("link_type"));
        assertEquals(recordId, ((Number) link.get("link_record_id")).longValue());
        assertNull(link.get("anchor_type"), "整条记录：锚点为空");
        assertNull(link.get("anchor_key"));
        assertEquals(recordVersion, ((Number) link.get("template_version")).intValue());
        runInTenant(
                () -> {
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT version FROM rpt_report WHERE id = ?",
                                    Integer.class,
                                    yearReport),
                            "落位置行的报告单版本 +1（与 E16-T13 同口径，装载时 version=0）");
                    assertNotNull(
                            jdbc.queryForObject(
                                    "SELECT legacy_migrated_at FROM rpt_report WHERE id = ?",
                                    java.sql.Timestamp.class,
                                    yearReport),
                            "已标记迁移（重入判据）");
                });

        // 原关联基线 → 零位置行（未指定位置组），不推进版本
        runInTenant(
                () -> {
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?",
                                    Long.class,
                                    baselineReport),
                            "基线关联不落位置行（未指定位置）");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT version FROM rpt_report WHERE id = ?",
                                    Integer.class,
                                    baselineReport),
                            "无位置行变更不推进版本");
                    assertNotNull(
                            jdbc.queryForObject(
                                    "SELECT legacy_migrated_at FROM rpt_report WHERE id = ?",
                                    java.sql.Timestamp.class,
                                    baselineReport));
                });

        // 该年无随访记录 → 保持待迁移（不标记），零位置行
        runInTenant(
                () -> {
                    assertNull(
                            jdbc.queryForObject(
                                    "SELECT legacy_migrated_at FROM rpt_report WHERE id = ?",
                                    java.sql.Timestamp.class,
                                    missingYearReport),
                            "该年无记录：不标记，下次执行可补");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?",
                                    Long.class,
                                    missingYearReport));
                });

        // 操作日志：两份完成迁移的报告单各一条（before / after 四元组快照）
        assertEquals(
                2L,
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                + " 'RPT_LEGACY_LOCATION_MIGRATE'",
                                        Long.class)),
                "按年关联与基线关联各写一条迁移日志");

        // 未指定位置组可查（A-ATT-020）：基线报告与该年无记录报告在列，按年关联的不在
        Set<Long> unassigned = unassignedIds(nurse);
        assertTrue(unassigned.contains(baselineReport), "基线关联报告单在未指定位置组");
        assertTrue(unassigned.contains(missingYearReport), "该年无记录报告单在未指定位置组");
        assertTrue(!unassigned.contains(yearReport), "按年关联报告单已退出未指定位置组");

        // 重入：再调一次零新增（可重入），只剩待迁移行的计数
        Map<?, ?> second = post(BASE + "/reports/legacy-location-migration", nurse, Map.of());
        assertEquals(0, code(second));
        Map<?, ?> report2 = data(second);
        assertEquals(0, intOf(report2.get("yearLinked")), "重入零新增：" + report2);
        assertEquals(0, intOf(report2.get("baselineUnassigned")));
        assertEquals(1, intOf(report2.get("yearWithoutRecord")), "待迁移行照实列出");
        assertEquals(2, intOf(report2.get("alreadyMigrated")));
        assertEquals(
                1L,
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?",
                                        Long.class,
                                        yearReport)),
                "位置行不重复（V63 uk 兜底 + 重入判据）");

        // 该年记录随后生成（补导入第 9 年）→ 再执行可补落
        runInTenant(
                () -> jdbc.update("UPDATE fu_record SET followup_year = 9 WHERE id = ?", recordId));
        Map<?, ?> third = post(BASE + "/reports/legacy-location-migration", nurse, Map.of());
        assertEquals(0, code(third));
        assertEquals(1, intOf(data(third).get("yearLinked")), "补落该年记录整条");
        assertEquals(
                1L,
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rpt_report_link l"
                                                + " JOIN fu_record r ON r.id = l.link_record_id"
                                                + " WHERE l.report_id = ? AND r.followup_year = 9",
                                        Long.class,
                                        missingYearReport)),
                "补落位置行指向该年第 9 年记录");
    }

    // ---------------------------------------------------------------- 守卫：权限与模块开关

    /** 无 followup:patient:import（撤菜单 353 后登录的医生）→ 40300；模块关闭 → 40301。 */
    @Test
    void permissionAndModuleGuards() {
        String nurse = hospitalToken(TENANT, "nurse.demo");
        // 撤掉 FOLLOWUP_DOCTOR 的导入向导菜单后登录（权限快照在登录时装配）
        runInTenant(
                () ->
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id ="
                                        + " rm.role_id WHERE r.code = 'FOLLOWUP_DOCTOR' AND rm.menu_id"
                                        + " = 353"));
        String doctor;
        try {
            doctor = hospitalToken(TENANT, "doctor.demo");
            assertEquals(
                    40300,
                    code(post(BASE + "/reports/legacy-location-migration", doctor, Map.of())),
                    "无导入权限 40300");
        } finally {
            runInTenant(
                    () ->
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id)"
                                            + " SELECT r.id, 353 FROM sys_role r WHERE r.code ="
                                            + " 'FOLLOWUP_DOCTOR' AND NOT EXISTS (SELECT 1 FROM"
                                            + " sys_role_menu rm WHERE rm.role_id = r.id AND"
                                            + " rm.menu_id = 353)"));
        }

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(post(BASE + "/reports/legacy-location-migration", nurse, Map.of())),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(post(BASE + "/reports/legacy-location-migration", nurse, Map.of())));
    }

    // ---------------------------------------------------------------- 造数与工具

    /** 装载一份旧关联报告单（模拟旧系统 bulk 装载：followup V75 legacy 列直接落库，不经上传接口）。 */
    private long legacyReport(long patientId, String linkType, Integer year, String name) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO rpt_report (patient_id, type_code, type_name,"
                                    + " name_snapshot, report_date, status, version, legacy_link_type,"
                                    + " legacy_link_year) VALUES (?, 'OTHER', '其他（示例）', ?,"
                                    + " '2026-05-20', 'NORMAL', 0, ?, ?)",
                            patientId,
                            name,
                            linkType,
                            year);
                    return jdbc.queryForObject(
                            "SELECT MAX(id) FROM rpt_report WHERE patient_id = ? AND name_snapshot = ?",
                            Long.class,
                            patientId,
                            name);
                });
    }

    /** 全院检索「未指定位置」组里的报告单 id（A-ATT-020 / E16-T05 locationScope=UNASSIGNED）。 */
    private Set<Long> unassignedIds(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("locationScope", "UNASSIGNED");
        body.put("pageSize", 100);
        Map<?, ?> page = post(BASE + "/reports/search", token, body);
        assertEquals(0, code(page), "检索失败：" + page);
        Set<Long> ids = new HashSet<>();
        for (Map<?, ?> item : itemsOf(page)) {
            ids.add(Long.parseLong(str(item.get("id"))));
        }
        return ids;
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

    private long registeredPatient(String token) {
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "吴迁移（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId());
        body.put("admissionDate", "2026-04-01");
        body.put("surgeryDate", "2026-04-02");
        body.put("dischargeDate", "2026-04-05");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 历史补录建一条 fu_record（E15-T07 接口；未入组患者须显式指定 formCode），返回 recordId。 */
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

    private long defaultWardId() {
        return runInTenant(() -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return (Map<?, ?>)
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class)
                        .getBody();
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

    private static int intOf(Object value) {
        return value == null ? -1 : Integer.parseInt(value.toString());
    }
}
