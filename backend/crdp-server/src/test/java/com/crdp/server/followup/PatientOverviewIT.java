package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.patient.app.PatientStatusHistoryService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.TenantModuleService;
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
 * E13-T05 端到端（验收原文：概览显示基线、计划、最近随访）。场景：
 *
 * <ol>
 *   <li>概览（A-PAT-004、C-PAT-010）：doctor.demo（W-DEMO-1 病区范围）查看范围内患者——姓名 / 病案号只含脱敏值，基线（登记实体列 +
 *       完整度列）、计划（下次随访窗口，E14 写入前为 null）、最近随访（关键指标，E15 写入前为 null）三块齐备；已终止患者 terminated=true 且无 stop
 *       操作；患者详情隐藏菜单（plt_menu 215）随 /menus 下发；
 *   <li>时间轴（A-PAT-013）：出院节点与状态变更事件按时间顺序返回（登记初始行不重复展示），状态文案码由前端映射；
 *   <li>权限与隔离：越范围患者 40400；无 followup:patient:view 40300；followup 模块关闭 40301；双租户互不可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T05-、病区 W-IT-T5、角色
 * IT-R-NOVIEW5、用户 IT-U-NV5）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientOverviewIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T05";
    private static final String NAME_A = "张小明（示例）";
    private static final String NAME_OTHER = "李大中（示例）";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PatientStatusHistoryService statusHistory;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T05-%'");
                        jdbc.update(
                                "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                                        + " (SELECT id FROM pt_patient)");
                        jdbc.update(
                                "DELETE FROM pt_status_history WHERE patient_id NOT IN"
                                        + " (SELECT id FROM pt_patient)");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username = 'IT-U-NV5'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username = 'IT-U-NV5'");
                        jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-NV5'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'IT-R-NOVIEW5'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code = 'IT-R-NOVIEW5'");
                        jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-NOVIEW5'");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-T5'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 概览（验收：显示基线、计划、最近随访）

    @Test
    void summaryShowsMaskedIdentityBaselinePlanAndLatestFollowup() {
        String token = doctorToken();
        register(token, "IT-MRN-T5-01", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        long patientId = patientIdByMrn("IT-MRN-T5-01");

        Map<?, ?> summary = get("/api/hospital/followup/patients/" + patientId + "/summary", token);

        assertEquals(0, codeOf(summary), "概览失败：" + summary);
        Map<?, ?> data = data(summary);
        assertEquals(String.valueOf(patientId), data.get("id"));
        assertEquals("张*）", data.get("name"), "姓名只输出脱敏值（明文走专用接口）");
        assertEquals("IT********01", data.get("patientNo"), "病案号脱敏（前 2 后 2）");
        assertEquals(LEGACY + "-IT-MRN-T5-01", data.get("legacyNo"));
        assertEquals("M", data.get("gender"));
        assertEquals(71, ((Number) data.get("age")).intValue(), "年龄 = 手术日期 − 出生日期");
        assertEquals("ACTIVE", data.get("status"), "登记即随访中（OQ-71②）");
        assertFalse((Boolean) data.get("terminated"));
        assertEquals("虚构病区一", data.get("wardName"));
        // 基线：登记实体列即基线最小必填集；完整度列随 E13-T02 写入（当前 null）
        assertEquals("2026-03-08", data.get("dischargeDate"));
        assertNull(data.get("baselineCompleteness"), "基线完整度随 E13-T02 写入");
        // 计划：下次随访窗口随 E14 写入（当前整体 null，前端显示占位）
        assertNull(data.get("nextVisit"), "下次随访窗口随 E14 写入");
        // 最近随访：关键指标随 E15 写入（当前整体 null，前端显示「暂无随访记录」）
        assertNull(data.get("latestFollowup"), "最近随访关键指标随 E15 写入");
        assertNull(data.get("device"), "C 当前器械随 E21 开放");
        // 演示随访医生只有 followup:patient:edit（菜单 214）：可停止、无计划外 / 上传 / 沟通入口
        assertEquals(List.of("stop"), data.get("actions"), "actions 按权限 + 状态计算");
    }

    @Test
    void summaryMarksTerminatedPatientWithoutStopAction() {
        String token = doctorToken();
        register(token, "IT-MRN-T5-02", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        long patientId = patientIdByMrn("IT-MRN-T5-02");
        recordStatus(patientId, "DEAD", "2026-09-01", "心源性死亡（示例）");

        Map<?, ?> summary = get("/api/hospital/followup/patients/" + patientId + "/summary", token);

        assertEquals(0, codeOf(summary));
        Map<?, ?> data = data(summary);
        assertTrue((Boolean) data.get("terminated"), "死亡 / 停止 / 失访视为已终止（pages.md §4.10）");
        assertEquals(List.of(), data.get("actions"), "已终止患者无 stop 操作（upload 演示角色无权限）");
    }

    @Test
    void patientDetailHiddenMenuIsDelivered() {
        Map<?, ?> menus = get("/api/hospital/menus", doctorToken());
        String paths = String.valueOf(menus);
        assertTrue(
                paths.contains("/followup/patient/:id"),
                "患者详情隐藏菜单（plt_menu 215，平台库 V39 + followup V41 / 种子 V114 授权）随 /menus 下发");
    }

    // ---------------------------------------------------------------- 时间轴（A-PAT-013）

    @Test
    void timelineOrdersDischargeAndStatusEventsChronologically() {
        String token = doctorToken();
        register(token, "IT-MRN-T5-03", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        long patientId = patientIdByMrn("IT-MRN-T5-03");
        recordStatus(patientId, "PAUSED", "2026-07-01", "拒绝随访（示例）");
        recordStatus(patientId, "DEAD", "2026-09-01", "心源性死亡（示例）");

        Map<?, ?> timeline =
                get("/api/hospital/followup/patients/" + patientId + "/followup-timeline", token);

        assertEquals(0, codeOf(timeline), "时间轴失败：" + timeline);
        Map<?, ?> data = data(timeline);
        List<?> items = (List<?>) data.get("items");
        assertEquals(3, items.size(), "出院 + 暂停 + 死亡；登记初始行（REGISTRATION）不重复展示");
        Map<?, ?> discharge = (Map<?, ?>) items.get(0);
        assertEquals("DISCHARGE", discharge.get("kind"));
        assertEquals("2026-03-08", discharge.get("date"));
        assertEquals("出院", discharge.get("label"));
        Map<?, ?> paused = (Map<?, ?>) items.get(1);
        assertEquals("STATUS", paused.get("kind"));
        assertEquals("PAUSED", paused.get("status"), "状态文案码由前端 PATIENT_STATUS_TEXT 映射");
        assertEquals("2026-07-01", paused.get("date"));
        assertEquals("拒绝随访（示例）", paused.get("note"));
        Map<?, ?> death = (Map<?, ?>) items.get(2);
        assertEquals("DEAD", death.get("status"), "死亡作为状态事件展示（A-PAT-013）");
        assertEquals("2026-09-01", death.get("date"));
    }

    // ---------------------------------------------------------------- 权限与隔离

    @Test
    void outOfScopePatientReturns40400ForBothEndpoints() {
        long otherWard = createWard("W-IT-T5", "示例 IT 概览隔离病区");
        register(adminToken(), "IT-MRN-T5-04", NAME_OTHER, otherWard, "1948-06-01");
        long patientId = patientIdByMrn("IT-MRN-T5-04");

        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                doctorToken())),
                "越范围患者 40400（不暴露对象存在）");
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/followup-timeline",
                                doctorToken())),
                "时间轴同样 40400");

        // ALL 范围管理员可见（同一行不为 null）
        assertEquals(
                0,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                adminToken())));
    }

    @Test
    void withoutViewPermissionRejectedWith40300() {
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-NOVIEW5', 'IT 无概览权限（示例）', 0, 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-NOVIEW5'",
                                            Long.class);
                            // 只授随访任务（22），不授患者档案（21）
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
                                "username", "IT-U-NV5",
                                "displayName", "IT 无概览权限用户（示例）",
                                "phone", "13900001303",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-NV5", temporary);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");

        register(adminToken(), "IT-MRN-T5-05", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        long patientId = patientIdByMrn("IT-MRN-T5-05");
        assertEquals(
                40300,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", token)),
                "无 followup:patient:view 40300");
        assertEquals(
                40300,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/followup-timeline",
                                token)),
                "时间轴同样需要 view 权限");
    }

    @Test
    void closedFollowupModuleReturns40301() {
        String token = doctorToken();
        register(token, "IT-MRN-T5-06", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        long patientId = patientIdByMrn("IT-MRN-T5-06");
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", token)),
                    "模块关闭 40301");
            assertEquals(
                    40301,
                    codeOf(
                            get(
                                    "/api/hospital/followup/patients/"
                                            + patientId
                                            + "/followup-timeline",
                                    token)),
                    "时间轴同样 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    @Test
    void overviewIsIsolatedAcrossTenants() {
        register(adminToken(), "IT-MRN-T5-07", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        long demoaPatientId = patientIdByMrn("IT-MRN-T5-07");

        // demob 同名患者：独立租户库；demoa 的患者 id 在 demob 不可见
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        long demobWard =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
        registerAsTenant("demob", demobAdmin, "IT-MRN-T5-07", NAME_OTHER, demobWard, "1948-06-01");

        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + demoaPatientId + "/summary",
                                demobAdmin)),
                "双租户互不可见（患者 id 只在本院库有意义）");
    }

    // ---------------------------------------------------------------- 辅助

    private void register(String token, String mrn, String name, long wardId, String birthDate) {
        registerAsTenant("demoa", token, mrn, name, wardId, birthDate);
    }

    private void registerAsTenant(
            String tenant, String token, String mrn, String name, long wardId, String birthDate) {
        Map<String, Object> body =
                new HashMap<>(
                        Map.of(
                                "medicalRecordNo",
                                mrn,
                                "name",
                                name,
                                "gender",
                                "M",
                                "birthDate",
                                birthDate,
                                "wardId",
                                wardId,
                                "surgeryDate",
                                "2026-03-01",
                                "admissionDate",
                                "2026-02-25",
                                "dischargeDate",
                                "2026-03-08",
                                "dischargeStatus",
                                1,
                                "legacyNo",
                                LEGACY + "-" + mrn));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败（" + tenant + "）：" + created);
    }

    /**
     * 追加状态历史并同步 pt_patient.followup_status——与 E14-T06 流转接口的事务语义一致 （历史服务只追加，pt_patient 同步随 E14-T06
     * 落地，这里直接写列模拟）。
     */
    private void recordStatus(long patientId, String status, String effectiveDate, String reason) {
        runInTenant(
                "demoa",
                () -> {
                    statusHistory.record(
                            patientId,
                            status,
                            java.time.LocalDate.parse(effectiveDate),
                            reason,
                            "MANUAL",
                            null);
                    jdbc.update(
                            "UPDATE pt_patient SET followup_status = ? WHERE id = ?",
                            status,
                            patientId);
                });
    }

    private long patientIdByMrn(String mrn) {
        // legacy_no = IT-E13T05-{mrn} 与登记一一对应，避免在测试里解密
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM pt_patient WHERE legacy_no = ?",
                                Long.class,
                                LEGACY + "-" + mrn));
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
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private long wardIdByCode(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptIdByCode(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
