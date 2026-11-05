package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.record.app.FollowupRecordService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E25-T01 端到端 / NFR-SAVE-001、NFR-SAVE-003（完整应用 + 真实 MySQL，HTTP 直连 + 服务层造数）。验收原文：自动保存只写草稿、
 * 任务变进行中；版本不一致返回 409 与差异字段。
 *
 * <p>覆盖：自动保存只合并变更字段并 version+1（data_meta 不动、不写字段历史、不逐次写审计）；草稿不做必填 / 跨字段校验（「草稿不触发 提交逻辑」）；baseVersion
 * 过期 409（40900，data 附当前版本 / 更新人显示名 / 更新时间 / 两边不同字段，patient.md §4.1）；「保留我的 并覆盖」（currentVersion 作
 * baseVersion + overwrite）成功并写 RECORD_DRAFT_OVERWRITE 审计（只记字段键）；已提交 40901；记录不存在 40400、未知键 / 类型错 / 缺
 * baseVersion / 空 changes 40001；无 followup:record:edit 40300；followup 模块关闭 40301； demoa / demob
 * 双租户记录隔离（每院独立库）。
 *
 * <p>「任务变进行中」的写库联动：fu_task 建表与状态流转随 E14-T04 / E15-T01（晚于本任务），接入点在 RecordDraftService.saveDraft
 * 成功之后（OQ-77）。门控 CRDP_IT_MYSQL_PORT。全部虚构数据（记录经 data_meta 标记 E25T01 自建自清）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordDraftIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String MARKER = "E25T01";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private FollowupRecordService recordService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        SecurityContextHolder.clearContext();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action = 'RECORD_DRAFT_OVERWRITE'"
                                        + " AND object_id IN (SELECT CAST(id AS CHAR) FROM fu_record"
                                        + " WHERE data_meta LIKE '%\""
                                        + MARKER
                                        + "\"%')");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE data_meta LIKE '%\""
                                        + MARKER
                                        + "\"%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E25T01-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username = 'IT-U-NOR'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username = 'IT-U-NOR'");
                        jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-NOR'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'IT-R-NORECORD'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code = 'IT-R-NORECORD'");
                        jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-NORECORD'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 自动保存只写草稿

    /** 草稿合并变更字段、version+1；不做必填校验（f214 必填仍缺）；不写字段历史、不逐次写审计。 */
    @Test
    void autosaveMergesChangesWithoutSubmitSideEffects() {
        long recordId = createRecord(Map.of("f213", 1));
        String token = doctorToken();

        // f214（随访日期，必填）仍缺：草稿不做必填 / 跨字段校验（tech-plan §6 草稿语义）
        Map<?, ?> first =
                put(
                        draftPath(recordId),
                        token,
                        Map.of("baseVersion", 0, "changes", Map.of("f218", "心源性死亡（示例）")));
        assertEquals(0, code(first), "缺必填字段的草稿照常保存：" + first);
        assertEquals(1, ((Number) data(first).get("version")).intValue(), "version + 1");
        assertNotNull(data(first).get("savedAt"), "返回保存时间（表单顶部「已自动保存 HH:mm」）");

        Map<?, ?> second =
                put(
                        draftPath(recordId),
                        token,
                        Map.of("baseVersion", 1, "changes", Map.of("f214", "2026-03-10")));
        assertEquals(0, code(second), "第二次自动保存用新版本号");
        assertEquals(2, ((Number) data(second).get("version")).intValue());

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT data, data_meta AS dataMeta, version, updated_by"
                                            + " FROM fu_record WHERE id = ?",
                                    recordId);
                    assertEquals(2, ((Number) row.get("version")).intValue(), "落库版本号 = 2");
                    // MySQL JSON 显示带空格（{"f213": 1}），比较前去掉空白
                    String dataJson = String.valueOf(row.get("data")).replaceAll("\\s", "");
                    assertTrue(
                            dataJson.contains("\"f213\":1")
                                    && dataJson.contains("\"f214\":\"2026-03-10\"")
                                    && dataJson.contains("心源性死亡（示例）"),
                            "变更字段合并、未提及字段保留：" + dataJson);
                    assertEquals(
                            "{\"it\":\"" + MARKER + "\"}",
                            String.valueOf(row.get("dataMeta")).replaceAll("\\s", ""),
                            "草稿不更新 data_meta");
                    assertEquals(
                            userIdOf("doctor.demo"),
                            ((Number) row.get("updated_by")).longValue(),
                            "更新人 = 当前登录医生");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_field_history"
                                                    + " WHERE record_type = 'FU_RECORD' AND record_id = ?",
                                            Long.class,
                                            recordId)
                                    .longValue(),
                            "草稿不写字段历史（提交 / 保存修改时才写）");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RECORD_DRAFT_OVERWRITE' AND object_id = ?",
                                            Long.class,
                                            String.valueOf(recordId))
                                    .longValue(),
                            "普通自动保存不逐次写审计");
                });
    }

    // ---------------------------------------------------------------- 乐观锁：409 与差异字段

    /** baseVersion 过期 → 40900，附当前版本 / 更新人显示名 / 更新时间 / 两边不同字段（值相同的键不列）。 */
    @Test
    void staleBaseVersionReturns409WithDiffFields() {
        long recordId = createRecord(Map.of("f213", 1, "f214", "2026-01-05"));
        // 他人（演示护士）抢先保存：版本前进、值被改
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE fu_record SET data = ?, version = version + 1, updated_by = ?"
                                    + " WHERE id = ?",
                            "{\"f213\":1,\"f214\":\"2026-02-14\",\"f215\":1}",
                            userIdOf("nurse.demo"),
                            recordId);
                });

        Map<?, ?> response =
                put(
                        draftPath(recordId),
                        doctorToken(),
                        Map.of(
                                "baseVersion",
                                0,
                                "changes",
                                Map.of("f214", "2026-04-01", "f213", 1)));
        assertEquals(40900, code(response), "版本不一致返回 409");
        Map<?, ?> detail = data(response);
        assertEquals(1, ((Number) detail.get("currentVersion")).intValue(), "附服务器当前版本号");
        assertEquals("演示护士", detail.get("updatedBy"), "附更新人显示名");
        assertNotNull(detail.get("updatedAt"), "附更新时间");
        List<Map<?, ?>> diffFields = (List<Map<?, ?>>) detail.get("diffFields");
        assertEquals(1, diffFields.size(), "值相同的键（f213）不列：" + diffFields);
        Map<?, ?> diff = diffFields.get(0);
        assertEquals("f214", diff.get("key"));
        assertEquals("随访日期", diff.get("label"), "label 取模板版本快照显示名");
        assertEquals("2026-04-01", diff.get("mine"));
        assertEquals("2026-02-14", diff.get("server"));
    }

    /** 「保留我的并覆盖」：以 currentVersion 作 baseVersion 重发并带 overwrite → 保存成功且写审计（只记字段键）。 */
    @Test
    void keepMineOverwriteSavesAndWritesAudit() {
        long recordId = createRecord(Map.of("f213", 1, "f214", "2026-01-05"));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_record SET data = ?, version = version + 1,"
                                        + " updated_by = ? WHERE id = ?",
                                "{\"f213\":1,\"f214\":\"2026-02-14\"}",
                                userIdOf("nurse.demo"),
                                recordId));

        Map<?, ?> overwritten =
                put(
                        draftPath(recordId),
                        doctorToken(),
                        Map.of(
                                "baseVersion",
                                1,
                                "changes",
                                Map.of("f214", "2026-04-01"),
                                "overwrite",
                                true));
        assertEquals(0, code(overwritten), "覆盖保存成功：" + overwritten);
        assertEquals(2, ((Number) data(overwritten).get("version")).intValue());

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> auditRow =
                            jdbc.queryForMap(
                                    "SELECT detail, after_json FROM aud_log WHERE action ="
                                            + " 'RECORD_DRAFT_OVERWRITE' AND object_id = ?",
                                    String.valueOf(recordId));
                    assertTrue(
                            String.valueOf(auditRow.get("detail")).contains("覆盖"),
                            "描述点明覆盖他人版本：" + auditRow.get("detail"));
                    String afterJson = String.valueOf(auditRow.get("after_json"));
                    assertTrue(afterJson.contains("\"f214\""), "审计记变更字段键：" + afterJson);
                    assertFalse(afterJson.contains("2026-04-01"), "审计不落字段值（敏感值只存摘要）：" + afterJson);
                });
    }

    // ---------------------------------------------------------------- 状态与入参校验

    @Test
    void submittedRecordRejectsAutosaveWith40901() {
        long recordId = createRecord(Map.of("f213", 1));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_record SET status = 'SUBMITTED' WHERE id = ?",
                                recordId));

        assertEquals(
                40901,
                code(
                        put(
                                draftPath(recordId),
                                doctorToken(),
                                Map.of("baseVersion", 0, "changes", Map.of("f214", "2026-03-10")))),
                "已提交记录不自动保存（patient.md §4.1）");
    }

    @Test
    void rejectsMissingRecordAndBadRequests() {
        String token = doctorToken();
        assertEquals(
                40400,
                code(
                        put(
                                draftPath(999999999L),
                                token,
                                Map.of("baseVersion", 0, "changes", Map.of("f214", "2026-03-10")))),
                "记录不存在");
        long recordId = createRecord(Map.of("f213", 1));
        assertEquals(
                40001,
                code(
                        put(
                                draftPath(recordId),
                                token,
                                Map.of("baseVersion", 0, "changes", Map.of("fXYZ", 1)))),
                "未知键不能写入");
        assertEquals(
                40001,
                code(
                        put(
                                draftPath(recordId),
                                token,
                                Map.of("baseVersion", 0, "changes", Map.of("f213", "是")))),
                "单选存选项编码（整数），类型错拒绝");
        Map<String, Object> noBase =
                new java.util.LinkedHashMap<>(Map.of("changes", Map.of("f213", 0)));
        assertEquals(40001, code(put(draftPath(recordId), token, noBase)), "baseVersion 必填");
        assertEquals(
                40001,
                code(
                        put(
                                draftPath(recordId),
                                token,
                                Map.of("baseVersion", 0, "changes", Map.of()))),
                "只在有新改动时发送（空 changes 是客户端缺陷）");
    }

    // ---------------------------------------------------------------- 权限与模块开关

    @Test
    void withoutRecordEditPermissionRejectedWith40300() {
        long roleId =
                callInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-NORECORD', 'IT 无录入权限（示例）', 0, 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-NORECORD'",
                                            Long.class);
                            // 只授任务列表（22），不授录入随访记录（222）
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 22)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId = deptIdByCode("DEPT-CARDIO");
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken(),
                        Map.of(
                                "username", "IT-U-NOR",
                                "displayName", "IT 无录入权限用户（示例）",
                                "phone", "13900001310",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));

        // 强制改密期间其余接口 40305（PLT-AUTH-010），先改密再测 40300
        String token = hospitalToken("demoa", "IT-U-NOR", temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        long recordId = createRecord(Map.of("f213", 1));
        assertEquals(
                40300,
                code(
                        put(
                                draftPath(recordId),
                                token,
                                Map.of("baseVersion", 0, "changes", Map.of("f214", "2026-03-10")))),
                "无 followup:record:edit 40300");
    }

    @Test
    void closedFollowupModuleReturns40301() {
        long recordId = createRecord(Map.of("f213", 1));
        String token = doctorToken();
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(
                            put(
                                    draftPath(recordId),
                                    token,
                                    Map.of(
                                            "baseVersion",
                                            0,
                                            "changes",
                                            Map.of("f214", "2026-03-10")))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 双租户隔离

    @Test
    void recordInvisibleAcrossTenants() {
        long recordId = createRecord(Map.of("f213", 1));
        String demobToken = hospitalToken("demob", "doctor.demo");
        assertEquals(
                40400,
                code(
                        put(
                                draftPath(recordId),
                                demobToken,
                                Map.of("baseVersion", 0, "changes", Map.of("f214", "2026-03-10")))),
                "demoa 的记录在 demob 不可见（每院独立库）");
        assertEquals(
                0L,
                callInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM fu_record WHERE id = ?",
                                                Long.class,
                                                recordId)
                                        .longValue()),
                "demob 库无该记录行");
    }

    // ---------------------------------------------------------------- 造数与工具

    /** 服务层造数（E15-T07 起载体 fu_record：登记一名虚构患者后建 BACKFILL 草稿；data_meta 打 E25T01 标记自清）。 */
    private long createRecord(Map<String, Object> data) {
        long patientId = registerItPatient();
        loginServicePrincipal();
        Map<String, Object> dataMeta = Map.of("it", MARKER);
        FollowupRecordService.RecordView created =
                callInTenant(
                        "demoa",
                        () ->
                                recordService.createDraft(
                                        new FollowupRecordService.CreateCommand(
                                                patientId,
                                                null,
                                                FollowupRecordService.SOURCE_BACKFILL,
                                                java.time.LocalDate.now().minusDays(10),
                                                FORM,
                                                data,
                                                dataMeta)));
        return created.id();
    }

    /** 登记 IT 虚构患者（HTTP，默认病区；legacy_no 打标记自清）。 */
    private long registerItPatient() {
        String legacy = "IT-E25T01-" + (System.nanoTime() % 100000);
        Map<String, Object> body =
                new java.util.LinkedHashMap<>(
                        Map.ofEntries(
                                Map.entry("medicalRecordNo", legacy),
                                Map.entry("name", "周草稿（示例）"),
                                Map.entry("gender", "F"),
                                Map.entry("birthDate", "1950-01-01"),
                                Map.entry("admissionDate", "2026-01-01"),
                                Map.entry("surgeryDate", "2026-01-02"),
                                Map.entry("dischargeDate", "2026-01-05"),
                                Map.entry("dischargeStatus", 1),
                                Map.entry("legacyNo", legacy)));
        body.put(
                "wardId",
                callInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                                "SELECT id FROM ward ORDER BY id LIMIT 1",
                                                Long.class)
                                        .longValue()));
        Map<?, ?> created = post("/api/hospital/followup/patients", adminToken(), body);
        assertEquals(0, code(created), "登记 IT 患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    private static String draftPath(long recordId) {
        return "/api/hospital/followup/records/" + recordId + "/draft";
    }

    private long userIdOf(String username) {
        return callInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                        "SELECT id FROM sys_user WHERE username = ?",
                                        Long.class,
                                        username)
                                .longValue());
    }

    private void loginServicePrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                servicePrincipal(), null, List.of()));
    }

    /** 服务层直调的医院会话（IT 线程无 HTTP 过滤器链，手动装配；记录创建经 FormService，OQ-63①）。 */
    private static SessionPrincipal servicePrincipal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                7L,
                "nurse.demo",
                "张医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:record:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                null);
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
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
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T callInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long deptIdByCode(String code) {
        return callInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = ?", Long.class, code)
                                .longValue());
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
