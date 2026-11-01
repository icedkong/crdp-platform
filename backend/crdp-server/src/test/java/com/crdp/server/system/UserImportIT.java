package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.system.org.xlsx.SimpleXlsx;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E06-T08 端到端（完整应用 + 真实 MySQL/Redis；验收原文：Excel 导入用户并返回错误行）。场景：
 *
 * <ol>
 *   <li>模板下载（xlsx、表头一致、示例为虚构数据）；上传后逐行校验并新建——账号 / 手机号本院唯一、 角色与负责病区 /
 *       负责科室按编码写入、首登强制改密、手机号落密文、<b>初始密码不随导入返回</b> （首次短信登录后设置，platform-spec §6 新建用户流程）；
 *   <li>单行失败不影响其他行（账号 / 手机号文件内重复、库内已存在、非法账号、手机号格式、缺角色、 未知科室 / 角色 / 病区、模板角色各报原因），返回错误行清单；
 *   <li>文件级 40001：表头不符、无数据行；权限 tenant:user:import（随访医生 40300、无权限账号看不到模板）； 写 USER_IMPORT
 *       作业级审计（不含手机号明文）；租户隔离（demoa 导入不影响 demob）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（账号前缀 IT-U-IMP、科室 IT-D-IMP-U、 病区
 * W-IT-IMP）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class UserImportIT {

    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    /** 只清理本 IT 建的数据（IT-U-IMP 账号、IT-D-IMP-U 科室、W-IT-IMP 病区）与其审计。 */
    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-IMP%'");
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-IMP-U%'");
                        jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-IMP%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target LIKE 'sys_user:IT-U-IMP%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target = 'sys_user:import'");
                    });
        }
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        for (String tenant : new String[] {"demoa", "demob"}) {
            TenantContext.runAsTenant(
                    "demob".equals(tenant) ? 2L : 1L,
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-IMP%'");
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-IMP-U%'");
                        jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-IMP%'");
                    });
        }
    }

    // ---------------------------------------------------------------- 模板（验收）

    @Test
    void templateDownloadsAsXlsxWithExpectedHeaders() {
        String admin = adminToken();
        ResponseEntity<byte[]> response =
                rest.exchange(
                        "/api/hospital/system/users/import-template",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(admin)),
                        byte[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders().getContentType().toString());
        assertNotNull(response.getHeaders().getContentDisposition().getFilename(), "附件名");

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(response.getBody()));
        assertEquals(UserImportHeaders.EXPECTED, grid.get(0));
        assertTrue(grid.size() >= 3, "模板应带示例行：" + grid.size());
        assertTrue(
                grid.get(1).get(1).contains("示例"),
                "示例行数据必须带「示例」标记（AGENTS 第 5 节第 5 条）：" + grid.get(1));
        // 随访医生无 tenant:user:import → 40300
        assertEquals(
                40300,
                codeOf(
                        bodyOf(
                                rest.exchange(
                                        "/api/hospital/system/users/import-template",
                                        HttpMethod.GET,
                                        new HttpEntity<>(
                                                authHeaders(
                                                        hospitalToken(
                                                                "demoa", "doctor.demo", PASSWORD))),
                                        Map.class))),
                "无导入权限不能下载模板");
    }

    // ---------------------------------------------------------------- 导入：成功行 + 错误行（验收）

    @Test
    void importCreatesUsersAndReturnsFailedRowsWithReasons() throws Exception {
        String admin = adminToken();
        long roleId = roleIdByCode("demoa", "FOLLOWUP_DOCTOR");
        long deptId = deptIdByCode("demoa", "DEPT-CARDIO");

        byte[] file =
                SimpleXlsx.write(
                        UserImportHeaders.EXPECTED,
                        List.of(
                                // 行 2：完整行（角色两个、负责病区、负责科室）
                                List.of(
                                        "IT-U-IMP-A",
                                        "甲护士（示例）",
                                        "S-2001",
                                        "13911110001",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "W-DEMO-1",
                                        "DEPT-CARDIO"),
                                // 行 3：最简行（可选列全空）
                                List.of(
                                        "IT-U-IMP-B",
                                        "乙医生（示例）",
                                        "",
                                        "13911110002",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""),
                                // 行 4 / 行 5：账号在文件内重复（两行都失败）
                                List.of(
                                        "IT-U-IMP-DUP",
                                        "重复甲（示例）",
                                        "",
                                        "13911110003",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-DUP",
                                        "重复乙（示例）",
                                        "",
                                        "13911110004",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""),
                                // 以下各行校验失败（每行一类原因）
                                List.of(
                                        "张三",
                                        "非法账号（示例）",
                                        "",
                                        "13911110005",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-PHONE",
                                        "手机号非法（示例）",
                                        "",
                                        "12345",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-NOROLE",
                                        "缺角色（示例）",
                                        "",
                                        "13911110006",
                                        "DEPT-CARDIO",
                                        "",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-DEPT",
                                        "科室未知（示例）",
                                        "",
                                        "13911110007",
                                        "DEPT-NOT-EXIST",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-ROLE",
                                        "角色未知（示例）",
                                        "",
                                        "13911110008",
                                        "DEPT-CARDIO",
                                        "ROLE-NOT-EXIST",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-TPL",
                                        "模板角色（示例）",
                                        "",
                                        "13911110009",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_NURSE",
                                        "",
                                        ""),
                                List.of(
                                        "IT-U-IMP-WARD",
                                        "病区未知（示例）",
                                        "",
                                        "13911110010",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "W-NOT-EXIST",
                                        ""),
                                // 行 13：账号库内已存在（种子 nurse.demo）
                                List.of(
                                        "nurse.demo",
                                        "既有账号（示例）",
                                        "",
                                        "13911110011",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        "")));
        Map<?, ?> report = importFile(admin, file);

        assertEquals(12, ((Number) report.get("total")).intValue());
        assertEquals(2, ((Number) report.get("created")).intValue(), "只有前两行成功：" + report);
        assertEquals(10, ((Number) report.get("failed")).intValue());
        List<?> rows = (List<?>) report.get("rows");
        Map<Integer, Map<?, ?>> rowByNo = new java.util.LinkedHashMap<>();
        for (Object row : rows) {
            Map<?, ?> item = (Map<?, ?>) row;
            rowByNo.put(((Number) item.get("rowNo")).intValue(), item);
        }
        assertEquals("CREATED", rowByNo.get(2).get("action"));
        assertEquals("CREATED", rowByNo.get(3).get("action"));
        assertEquals("FAILED", rowByNo.get(4).get("action"));
        assertTrue(
                String.valueOf(rowByNo.get(4).get("message")).contains("账号在文件内重复"),
                String.valueOf(rowByNo.get(4).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(5).get("message")).contains("账号在文件内重复"),
                String.valueOf(rowByNo.get(5).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(6).get("message")).contains("账号"),
                String.valueOf(rowByNo.get(6).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(7).get("message")).contains("手机号格式不正确"),
                String.valueOf(rowByNo.get(7).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(8).get("message")).contains("角色编码必填"),
                String.valueOf(rowByNo.get(8).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(9).get("message")).contains("主科室不存在"),
                String.valueOf(rowByNo.get(9).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(10).get("message")).contains("角色不存在"),
                String.valueOf(rowByNo.get(10).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(11).get("message")).contains("模板角色"),
                String.valueOf(rowByNo.get(11).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(12).get("message")).contains("负责病区不存在"),
                String.valueOf(rowByNo.get(12).get("message")));
        assertTrue(
                String.valueOf(rowByNo.get(13).get("message")).startsWith("账号已存在：nurse.demo"),
                String.valueOf(rowByNo.get(13).get("message")));

        // 落库断言：两个成功用户完整写入（角色 / 负责病区 / 负责科室 / 首登强制改密 / 手机号密文 + 盲索引）
        Map<String, Object> userA =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT id, display_name, staff_no, password_hash,"
                                                + " must_change_password, status, phone, phone_idx,"
                                                + " main_dept_id FROM sys_user WHERE username = 'IT-U-IMP-A'"));
        assertEquals("甲护士（示例）", userA.get("display_name"));
        assertEquals("S-2001", userA.get("staff_no"));
        assertEquals(deptId, ((Number) userA.get("main_dept_id")).longValue());
        assertEquals("ENABLED", userA.get("status"));
        assertEquals(1, ((Number) userA.get("must_change_password")).intValue(), "首登强制改密");
        assertNotNull(userA.get("password_hash"), "初始密码只落哈希");
        assertFalse(String.valueOf(userA.get("phone")).contains("13911110001"), "手机号不落明文");
        assertNotNull(userA.get("phone_idx"), "手机号盲索引");
        long userAId = ((Number) userA.get("id")).longValue();
        assertEquals(
                List.of(roleId),
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT role_id FROM sys_user_role WHERE user_id = ?",
                                        Long.class,
                                        userAId)));
        assertEquals(
                List.of(wardIdByCode("demoa", "W-DEMO-1")),
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT ward_id FROM sys_user_ward WHERE user_id = ?",
                                        Long.class,
                                        userAId)));
        assertEquals(
                List.of(deptId),
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT dept_id FROM sys_user_dept_scope WHERE user_id = ?",
                                        Long.class,
                                        userAId)));
        // 失败行不落库（未知角色 / 模板角色行都没有用户行可挂）
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE username LIKE"
                                                + " 'IT-U-IMP-%' AND username NOT IN ('IT-U-IMP-A','IT-U-IMP-B')",
                                        Integer.class)),
                "失败行不落库");

        // 导入报告不含任何明文手机号与临时密码（初始密码不随导入返回）
        assertFalse(String.valueOf(report).contains("temporaryPassword"), "报告不含临时密码");

        // 审计：作业级摘要一条，不含手机号明文
        List<Map<String, Object>> audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT event_type, detail FROM aud_log WHERE target ="
                                                + " 'sys_user:import'"));
        assertEquals(1, audits.size(), "一次导入一条作业级审计");
        assertEquals("USER_IMPORT", audits.get(0).get("event_type"));
        assertTrue(
                String.valueOf(audits.get(0).get("detail")).contains("新增 2"),
                String.valueOf(audits.get(0).get("detail")));
        assertFalse(
                String.valueOf(audits.get(0).get("detail")).contains("13911110001"), "审计不含手机号明文");
    }

    // ---------------------------------------------------------------- 文件级错误、权限与租户隔离

    @Test
    void importRejectsBadHeaderEmptyRowsAndMissingPermission() throws Exception {
        String admin = adminToken();
        // 表头不符
        assertEquals(
                40001,
                codeOf(
                        importFileRaw(
                                admin,
                                SimpleXlsx.write(
                                        List.of("账号", "姓名", "错误表头"),
                                        List.of(List.of("a", "b", "c"))))));
        // 无数据行
        assertEquals(
                40001,
                codeOf(
                        importFileRaw(
                                admin, SimpleXlsx.write(UserImportHeaders.EXPECTED, List.of()))));
        // 随访医生无 tenant:user:import → 40300
        byte[] okFile =
                SimpleXlsx.write(
                        UserImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "IT-U-IMP-NOAUTH",
                                        "无权限（示例）",
                                        "",
                                        "13911110011",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        "")));
        assertEquals(
                40300,
                codeOf(importFileRaw(hospitalToken("demoa", "doctor.demo", PASSWORD), okFile)));
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE username ="
                                                + " 'IT-U-IMP-NOAUTH'",
                                        Integer.class)),
                "40300 不落库");
    }

    @Test
    void importIsTenantIsolated() throws Exception {
        // 同一文件（同账号、同手机号）在两家医院各自导入成功：账号与手机号只要求本院唯一
        // （platform-spec §6「同一手机号可在多家医院分别使用」），数据完全隔离
        byte[] file =
                SimpleXlsx.write(
                        UserImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "IT-U-IMP-ISO",
                                        "隔离（示例）",
                                        "",
                                        "13911110012",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        "")));
        assertEquals(1, ((Number) importFile(adminToken(), file).get("created")).intValue());
        assertEquals(
                1,
                ((Number)
                                importFile(hospitalToken("demob", "nurse.demo", PASSWORD), file)
                                        .get("created"))
                        .intValue(),
                "demob 同样导入成功（各租户独立账号空间）");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE username ="
                                                + " 'IT-U-IMP-ISO'",
                                        Integer.class)),
                "demoa 落库");
        assertEquals(
                1,
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE username ="
                                                + " 'IT-U-IMP-ISO'",
                                        Integer.class)),
                "demob 落库");
        // 两院各自一条 USER_IMPORT 审计（demoa 的导入不写进 demob 库）
        for (String tenant : new String[] {"demoa", "demob"}) {
            assertEquals(
                    1,
                    runInTenant(
                            tenant,
                            () ->
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                    + " 'USER_IMPORT'",
                                            Integer.class)),
                    tenant + " 各自一条作业级审计");
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 与后端模板表头同构（避免测试依赖生产常量的可见性）。 */
    private static final class UserImportHeaders {
        static final List<String> EXPECTED =
                List.of("账号", "姓名", "工号", "手机号", "主科室编码", "角色编码", "负责病区编码", "负责科室编码");
    }

    private Map<?, ?> importFile(String admin, byte[] file) {
        Map<?, ?> response = importFileRaw(admin, file);
        assertEquals(0, codeOf(response), "导入请求失败：" + response);
        return dataOf(response);
    }

    private Map<?, ?> importFileRaw(String admin, byte[] file) {
        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new ByteArrayResource(file) {
                    @Override
                    public String getFilename() {
                        return "users.xlsx";
                    }
                });
        return bodyOf(
                rest.postForEntity(
                        "/api/hospital/system/users/import",
                        new HttpEntity<>(parts, headers),
                        Map.class));
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                bodyOf(
                        rest.postForEntity(
                                "/api/hospital/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "tenantCode",
                                                tenant,
                                                "username",
                                                username,
                                                "password",
                                                password)),
                                Map.class));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) dataOf(response).get("token");
    }

    private static Map<?, ?> bodyOf(ResponseEntity<Map> response) {
        return response.getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }

    private long roleIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = ?", Long.class, code));
    }

    private long deptIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long wardIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }
}
