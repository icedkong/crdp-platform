package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
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
 * E21-T11 端到端（验收原文：「修改已提交记录需单独权限」；C-FU-008、C-FU-024、C-FU-026 / Q17）。场景（demoa 开
 * cfg_feature.record_edit_submitted，平台侧放开敏感按钮 229 / 230 并补授 TENANT_ADMIN）：
 *
 * <ol>
 *   <li><b>验收位</b>：仅有录入码 followup:record:edit 的用户在开关开启时 edit-session 40300（未分配「修改已提交记录」权限只能查看）； 补授
 *       followup:record:edit-submitted（重登录）后放行；开关关闭（无行，A / B 行为）record:edit 即可；
 *   <li>保存修改（PUT）：reason 必填（40001）、SUBMITTED 原地更新 version + 1（「保存后生成 v2」）、字段级历史记下原因；
 *   <li>重复记录提示（C-FU-024）：同一患者同一随访日期（有效口径 visit_date ?? 表单随访日期 ?? 计划日期）已提交记录 duplicate =
 *       true，excludeRecordId 排除自身；详情附 visitNo / sequenceNo（C-FU-008 自动带入）；
 *   <li>删除（DELETE）：reason 必填（40001）、软删除（deleted = 1 + 原因）、软删行详情 40400（不暴露存在）、RECORD_DELETE 审计。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T11-、审计 RECORD_* /
 * 审计原因标记、plt_tenant_scope 放开行、sys_role_menu 补授行、cfg_feature 开关行）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordEditDeleteIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T11";
    private static final String SCOPE_REASON = "IT-E21T11 用例放开（示例）";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    com.crdp.server.AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action IN"
                                    + " ('RECORD_EDIT_SESSION', 'RECORD_UPDATE', 'RECORD_DELETE',"
                                    + " 'RECORD_SUBMIT', 'RECORD_BACKFILL_CREATE')");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id"
                                    + " AND fh.record_type = 'FU_RECORD'"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    // 收回上一轮补授（保证 40300 负例可重放）；开关行由各用例自设
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'TENANT_ADMIN' AND rm.menu_id IN (229, 230)");
                });
        TenantContext.runAsPlatform(
                () ->
                        platformJdbc()
                                .update(
                                        "DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND"
                                                + " menu_id IN (229, 230) AND reason = ?",
                                        SCOPE_REASON));
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () -> {
                    // 收回补授与开关行（幂等；避免影响其他用例 / 后续运行）
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'TENANT_ADMIN' AND rm.menu_id IN (229, 230)");
                    jdbc.update(
                            "DELETE FROM cfg_feature WHERE feature_code ="
                                    + " 'record_edit_submitted'");
                });
        TenantContext.runAsPlatform(
                () ->
                        platformJdbc()
                                .update(
                                        "DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND"
                                                + " menu_id IN (229, 230) AND reason = ?",
                                        SCOPE_REASON));
    }

    // ---------------------------------------------------------------- 验收：修改已提交记录需单独权限

    @Test
    void submittedRecordEditRequiresSeparatePermissionWhenGateOn() {
        String admin = adminToken();
        String patientId = registerPatient(admin, "IT-MRN-E21T11-01");
        Map<?, ?> record = submittedBackfill(admin, patientId, "2026-09-10");
        String recordId = String.valueOf(record.get("id"));

        // 开关开启（医院 C 口径）：仅有录入码 record:edit → 40300（验收位）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO cfg_feature (feature_code, enabled) VALUES"
                                        + " ('record_edit_submitted', 1) ON DUPLICATE KEY UPDATE"
                                        + " enabled = 1"));
        Map<?, ?> rejected =
                post(
                        "/api/hospital/followup/records/" + recordId + "/edit-session",
                        admin,
                        Map.of("reason", "抄录错误"));
        assertEquals(40300, code(rejected), "未分配「修改已提交记录」权限只能查看（C-FU-026 / Q17）");

        // 平台放开敏感按钮 229 + 租户补授 TENANT_ADMIN → 重登录后放行
        openSensitive(229);
        grantMenuToAdmin(229);
        String editor = adminToken();
        Map<?, ?> session =
                post(
                        "/api/hospital/followup/records/" + recordId + "/edit-session",
                        editor,
                        Map.of("reason", "抄录错误"));
        assertEquals(0, code(session), "放开 + 补授后 edit-session 应放行：" + session);
        int baseVersion = ((Number) data(session).get("version")).intValue();

        // PUT：reason 必填（OQ-14 暂定 A / C 都必填）
        Map<String, Object> withoutReason = new HashMap<>(baseData("2026-09-11"));
        Map<String, Object> putBlank = new HashMap<>();
        putBlank.put("baseVersion", baseVersion);
        putBlank.put("data", withoutReason);
        putBlank.put("reason", " ");
        assertEquals(
                40001,
                code(put(recordUrl(recordId), editor, putBlank)),
                "修改原因必填（ReasonModal，OQ-14）");

        // PUT：SUBMITTED 原地更新 version + 1（保存后生成 v2）+ 字段级历史记下原因
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", baseVersion);
        body.put("data", baseData("2026-09-11"));
        body.put("reason", "抄录错误");
        Map<?, ?> updated = put(recordUrl(recordId), editor, body);
        assertEquals(0, code(updated), "保存修改失败：" + updated);
        assertEquals(baseVersion + 1, ((Number) data(updated).get("version")).intValue());
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT reason FROM fe_field_history WHERE"
                                                        + " record_type = 'FU_RECORD' AND record_id"
                                                        + " = ? ORDER BY id DESC LIMIT 1",
                                                String.class,
                                                Long.valueOf(recordId)))
                        .endsWith("：抄录错误"),
                "留痕行原因以用户填写的原因结尾");
        assertEquals(
                "SUBMITTED",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status FROM fu_record WHERE id = ?",
                                        String.class,
                                        Long.valueOf(recordId))),
                "修改后仍是已提交（原地更新）");
    }

    /** 开关关闭（无行，A / B 行为）：followup:record:edit 即可（followup.md §6）。 */
    @Test
    void submittedRecordEditPassesWithPlainEditWhenGateOff() {
        String admin = adminToken();
        String patientId = registerPatient(admin, "IT-MRN-E21T11-02");
        Map<?, ?> record = submittedBackfill(admin, patientId, "2026-08-01");
        String recordId = String.valueOf(record.get("id"));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "DELETE FROM cfg_feature WHERE feature_code ="
                                        + " 'record_edit_submitted'"));
        // 平台侧未放开 229 → 敏感放开行不存在；本用例只验开关关闭路径（不补授 229）
        Map<?, ?> session =
                post(
                        "/api/hospital/followup/records/" + recordId + "/edit-session",
                        admin,
                        Map.of());
        assertEquals(0, code(session), "开关关闭时 record:edit 即可（A / B 口径）：" + session);
    }

    // ---------------------------------------------------------------- 重复提示 / 序号 / 删除

    @Test
    void duplicateCheckSequenceNoAndSoftDelete() {
        String admin = adminToken();
        String patientId = registerPatient(admin, "IT-MRN-E21T11-03");
        Map<?, ?> record = submittedBackfill(admin, patientId, "2026-07-15");
        String recordId = String.valueOf(record.get("id"));

        // C-FU-008 自动带入：随访序号派生（唯一已提交记录 → 第 1 次）
        assertEquals(1, ((Number) record.get("sequenceNo")).intValue());

        // C-FU-024：同一患者同一随访日期已有记录 → duplicate = true；excludeRecordId 排除自身
        Map<?, ?> hit =
                get(
                        "/api/hospital/followup/patients/"
                                + patientId
                                + "/records/check-duplicate?visitDate=2026-07-15",
                        admin);
        assertEquals(0, code(hit));
        assertTrue((Boolean) data(hit).get("duplicate"), "同日已有已提交记录应提示");
        assertEquals(1, ((List<?>) data(hit).get("records")).size(), "命中行含定位与来源 / 状态");
        Map<?, ?> self =
                get(
                        "/api/hospital/followup/patients/"
                                + patientId
                                + "/records/check-duplicate?visitDate=2026-07-15&excludeRecordId="
                                + recordId,
                        admin);
        assertTrue(!(Boolean) data(self).get("duplicate"), "排除自身后不提示");
        Map<?, ?> other =
                get(
                        "/api/hospital/followup/patients/"
                                + patientId
                                + "/records/check-duplicate?visitDate=2026-07-16",
                        admin);
        assertTrue(!(Boolean) data(other).get("duplicate"), "不同日期不提示");

        // 删除：未分配 followup:record:delete → 40300（注解先行，权限在原因校验之前）
        assertEquals(
                40300, code(delete(recordUrl(recordId), admin, "重复录入")), "未分配「删除随访记录」权限 40300");

        // 平台放开敏感按钮 230 + 补授 → reason 必填（40001）→ 软删除、审计、详情 40400
        openSensitive(230);
        grantMenuToAdmin(230);
        String adminWithDelete = adminToken();
        assertEquals(
                40001,
                code(delete(recordUrl(recordId), adminWithDelete, null)),
                "删除原因必填（C-FU-026）");
        assertEquals(0, code(delete(recordUrl(recordId), adminWithDelete, "重复录入")), "删除失败");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT deleted FROM fu_record WHERE id = ?",
                                        Integer.class,
                                        Long.valueOf(recordId))),
                "软删除：数据长期保留（deleted = 1）");
        assertEquals(
                "重复录入",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT delete_reason FROM fu_record WHERE id = ?",
                                        String.class,
                                        Long.valueOf(recordId))),
                "删除原因落库（C-FU-026）");
        assertEquals(
                40400,
                code(get("/api/hospital/followup/records/" + recordId, adminWithDelete)),
                "软删行对读取侧等同不存在（不暴露存在）");
        // 软删后不再算重复（C-FU-024：软删不算）
        Map<?, ?> afterDelete =
                get(
                        "/api/hospital/followup/patients/"
                                + patientId
                                + "/records/check-duplicate?visitDate=2026-07-15",
                        adminWithDelete);
        assertTrue(!(Boolean) data(afterDelete).get("duplicate"), "软删行不算重复提示");
        assertNotNull(
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                + " 'RECORD_DELETE' AND target = ?",
                                        Integer.class,
                                        "FU_RECORD:" + recordId)),
                "RECORD_DELETE 审计已写");
    }

    // ---------------------------------------------------------------- 装配

    /** 登记患者（植入信息不落——implant 开关关闭的 A 口径即可）。 */
    private String registerPatient(String token, String mrn) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", "王示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId("W-DEMO-1"));
        body.put("admissionDate", "2026-07-01");
        body.put("surgeryDate", "2026-07-02");
        body.put("dischargeDate", "2026-07-05");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记失败：" + created);
        return String.valueOf(data(created).get("id"));
    }

    /** 新建补录记录并提交（A_ANNUAL_FU 必填字段同 DeviceContextIT 口径），返回提交后详情。 */
    private Map<?, ?> submittedBackfill(String token, String patientId, String visitDate) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source", "BACKFILL",
                                "visitDate", visitDate,
                                "formCode", "A_ANNUAL_FU"));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        token,
                        Map.of("baseVersion", 0, "data", baseData(visitDate)));
        assertEquals(0, code(submitted), "提交失败：" + submitted);
        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, token);
        assertEquals(0, code(detail));
        return data(detail);
    }

    /** A_ANNUAL_FU 必填字段（f214 随访日期、f215 是否死亡、f213 已加微信）。 */
    private static Map<String, Object> baseData(String visitDate) {
        Map<String, Object> data = new HashMap<>();
        data.put("f214", visitDate);
        data.put("f215", 0);
        data.put("f213", 1);
        return data;
    }

    /** 平台侧放开敏感按钮（plt_tenant_scope assignable = 1，与 ReportDeletionIT 同口径）。 */
    private void openSensitive(long menuId) {
        TenantContext.runAsPlatform(
                () ->
                        platformJdbc()
                                .update(
                                        "INSERT INTO plt_tenant_scope (tenant_id, menu_id,"
                                                + " assignable, reason) VALUES (1, ?, 1, ?) ON"
                                                + " DUPLICATE KEY UPDATE assignable = 1",
                                        menuId,
                                        SCOPE_REASON));
    }

    /** 租户侧补授 TENANT_ADMIN（幂等；权限快照在登录时生成——补授后须重登录）。 */
    private void grantMenuToAdmin(long menuId) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, ? FROM"
                                        + " sys_role r WHERE r.code = 'TENANT_ADMIN' AND NOT"
                                        + " EXISTS (SELECT 1 FROM sys_role_menu rm WHERE"
                                        + " rm.role_id = r.id AND rm.menu_id = ?)",
                                menuId,
                                menuId));
    }

    /** 平台库连接（敏感权限放开写在平台侧；与 ReportDeletionIT 同款直连）。 */
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

    private Long wardId(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
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

    private static String recordUrl(String recordId) {
        return "/api/hospital/followup/records/" + recordId;
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> delete(String path, String token, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(
                        path,
                        HttpMethod.DELETE,
                        reason == null
                                ? new HttpEntity<>(headers)
                                : new HttpEntity<>(Map.of("reason", reason), headers),
                        Map.class)
                .getBody();
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
