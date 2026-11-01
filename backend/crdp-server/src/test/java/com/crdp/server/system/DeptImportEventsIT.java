package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * E06-T03 端到端（完整应用 + 真实 MySQL/Redis；验收原文：Excel 导入科室；变更发事件）。场景：
 *
 * <ol>
 *   <li>Excel 导入科室（PLT-ORG-003）：模板下载（xlsx、表头一致、示例为虚构数据）；上传后逐行校验并落库——
 *       新增（含文件内父子引用，先后顺序无关）、按编码更新、单行失败不影响其他行（未知院区 / 重复编码 / 自引用 / 循环引用 / 跨院区移动 / 非法负责人 /
 *       非整数排序各报原因）；表头不符与无数据行 40001； 写 ORG_IMPORT 审计；权限 tenant:org:edit（随访医生 40300）；租户隔离（demoa 导入不影响
 *       demob）。
 *   <li>变更发事件（PLT-ORG-013）：用户停用 → user.disabled、离职 → user.resigned、调岗 →
 *       user.dept_changed（新旧科室）、角色变化 → user.roles_changed（新旧编码）；科室停用 → dept.disabled、 院区停用 →
 *       org.disabled；事件写进本院 sys_outbox（同一事务），demob 库看不到 demoa 的事件。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（科室 IT-D-IMP、院区 IT-ORG-IMP、 用户 IT-U-IMP、角色
 * IT-R-IMP）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class DeptImportEventsIT {

    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
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
                        jdbc.update(
                                "DELETE d FROM sys_dept d JOIN sys_org o ON o.id = d.org_id"
                                        + " WHERE o.code = 'IT-ORG-IMP'");
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-IMP%'");
                        jdbc.update("DELETE FROM sys_org WHERE code = 'IT-ORG-IMP'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code LIKE 'IT-R-IMP%'");
                        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-R-IMP%'");
                        jdbc.update("DELETE FROM sys_outbox");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target LIKE 'sys_dept:IT-D-IMP%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target LIKE 'sys_user:IT-U-IMP%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target = 'sys_dept:import'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE target LIKE 'sys_org:IT-ORG-IMP%'");
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
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMP%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-IMP%'");
                        jdbc.update(
                                "DELETE d FROM sys_dept d JOIN sys_org o ON o.id = d.org_id"
                                        + " WHERE o.code = 'IT-ORG-IMP'");
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-IMP%'");
                        jdbc.update("DELETE FROM sys_org WHERE code = 'IT-ORG-IMP'");
                        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-R-IMP%'");
                        jdbc.update("DELETE FROM sys_outbox");
                    });
        }
    }

    // ---------------------------------------------------------------- Excel 导入科室（验收）

    @Test
    void templateDownloadsAsXlsxWithExpectedHeaders() {
        String admin = adminToken();
        ResponseEntity<byte[]> response =
                rest.exchange(
                        "/api/hospital/system/orgs/import-template",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(admin)),
                        byte[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders().getContentType().toString());
        assertNotNull(response.getHeaders().getContentDisposition().getFilename(), "附件名");

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(response.getBody()));
        assertEquals(DeptImportHeaders.EXPECTED, grid.get(0));
        assertTrue(grid.size() >= 3, "模板应带示例行：" + grid.size());
        assertTrue(
                grid.get(1).get(3).contains("示例"),
                "示例行数据必须带「示例」标记（AGENTS 第 5 节第 5 条）：" + grid.get(1));
    }

    @Test
    void importCreatesUpdatesAndReportsRowErrors() throws Exception {
        String admin = adminToken();
        // 准备：导入院区（导入不建院区，先手工建）与一个启用用户做负责人
        createOrg("IT-ORG-IMP", "IT 示例院区", admin);
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES ('IT-U-IMP-LEADER', 'IT 示例负责人',"
                                    + " 'x', 'ENABLED', 0)");
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES ('IT-U-IMP-DISABLED', 'IT 停用负责人',"
                                    + " 'x', 'DISABLED', 0)");
                    jdbc.update(
                            "INSERT INTO sys_dept (org_id, parent_id, code, name, sort_no, status)"
                                    + " VALUES (?, 0, 'IT-D-IMP-OLD', 'IT 旧科室', 5, 'ENABLED')",
                            orgIdByCode("demoa", "IT-ORG-IMP"));
                });

        // 子行在前、父行在后：文件内引用与行序无关
        byte[] file =
                SimpleXlsx.write(
                        DeptImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-CHILD",
                                        "IT-D-IMP-PARENT",
                                        "IT 示例子科室",
                                        "临床",
                                        "",
                                        "IT-U-IMP-LEADER",
                                        "20"),
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-PARENT",
                                        "",
                                        "IT 示例父科室",
                                        "临床",
                                        "",
                                        "",
                                        "10"),
                                // 更新已有科室（改名 + 排序）
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-OLD",
                                        "",
                                        "IT 旧科室（改名）",
                                        "",
                                        "HIS-001",
                                        "",
                                        "99"),
                                // 以下各行校验失败（各覆盖一类原因）
                                List.of("ORG-NOT-EXIST", "IT-D-IMP-X1", "", "未知院区", "", "", "", ""),
                                List.of("IT-ORG-IMP", "IT-D-IMP-X2", "", "", "", "", "", ""),
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-X3",
                                        "IT-D-IMP-X3",
                                        "自引用",
                                        "",
                                        "",
                                        "",
                                        ""),
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-X4",
                                        "",
                                        "非法负责人",
                                        "",
                                        "",
                                        "not-a-user",
                                        ""),
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-X5",
                                        "",
                                        "非法排序",
                                        "",
                                        "",
                                        "",
                                        "not-int"),
                                // 跨院区移动：DEPT-CARDIO 属于 ORG-DEMO
                                List.of("IT-ORG-IMP", "DEPT-CARDIO", "", "跨院区移动", "", "", "", "")));

        Map<?, ?> report = importFile(admin, file);
        assertEquals(9, ((Number) report.get("total")).intValue(), "总行数：" + report);
        assertEquals(2, ((Number) report.get("created")).intValue(), "新增两行：" + report);
        assertEquals(1, ((Number) report.get("updated")).intValue(), "更新一行：" + report);
        assertEquals(6, ((Number) report.get("failed")).intValue(), "失败六行：" + report);

        Map<Integer, Map<?, ?>> byRow = new java.util.LinkedHashMap<>();
        for (Object item : (List<?>) report.get("rows")) {
            Map<?, ?> row = (Map<?, ?>) item;
            byRow.put(((Number) row.get("rowNo")).intValue(), row);
        }
        assertEquals("CREATED", byRow.get(2).get("action"), "父行新增：" + byRow.get(2));
        assertEquals("CREATED", byRow.get(3).get("action"));
        assertEquals("UPDATED", byRow.get(4).get("action"), "旧科室按编码更新");
        assertTrue(
                String.valueOf(byRow.get(5).get("message")).contains("院区不存在"),
                "未知院区报错：" + byRow.get(5));
        assertTrue(
                String.valueOf(byRow.get(6).get("message")).contains("科室名称必填"),
                "缺名称报错：" + byRow.get(6));
        assertTrue(
                String.valueOf(byRow.get(7).get("message")).contains("上级科室不能是本科室"),
                "自引用报错：" + byRow.get(7));
        assertTrue(
                String.valueOf(byRow.get(8).get("message")).contains("负责人账号不存在或未启用"),
                "非法负责人报错：" + byRow.get(8));
        assertTrue(
                String.valueOf(byRow.get(9).get("message")).contains("排序号必须是整数"),
                "非法排序报错：" + byRow.get(9));
        assertTrue(
                String.valueOf(byRow.get(10).get("message")).contains("已属于其他院区"),
                "跨院区移动报错：" + byRow.get(10));

        // 库内结果：父子关系、更新后的名称 / HIS / 排序
        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> child =
                            jdbc.queryForMap(
                                    "SELECT id, parent_id, leader_user_id, sort_no FROM sys_dept"
                                            + " WHERE code = 'IT-D-IMP-CHILD'");
                    Map<String, Object> parent =
                            jdbc.queryForMap(
                                    "SELECT id FROM sys_dept WHERE code = 'IT-D-IMP-PARENT'");
                    assertEquals(
                            ((Number) parent.get("id")).longValue(),
                            ((Number) child.get("parent_id")).longValue(),
                            "子科室挂到文件内父科室");
                    assertNotNull(child.get("leader_user_id"), "负责人已解析为用户 id");
                    Map<String, Object> old =
                            jdbc.queryForMap(
                                    "SELECT name, his_code, sort_no FROM sys_dept WHERE code = 'IT-D-IMP-OLD'");
                    assertEquals("IT 旧科室（改名）", old.get("name"));
                    assertEquals("HIS-001", old.get("his_code"));
                    assertEquals(99, ((Number) old.get("sort_no")).intValue());
                });

        // 审计：ORG_IMPORT 作业级摘要
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'ORG_IMPORT'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1, "写 ORG_IMPORT 审计");
    }

    @Test
    void importRejectsBrokenHeaderEmptyRowsAndMissingPermission() throws Exception {
        String admin = adminToken();
        // 表头不符
        byte[] wrongHeader =
                SimpleXlsx.write(
                        List.of("院区", "编码", "上级", "名称", "类型", "HIS", "负责人", "排序"),
                        List.of(List.of("ORG-DEMO", "D", "", "N", "", "", "", "")));
        assertEquals(40001, codeOf(importFileRaw(admin, wrongHeader)));

        // 没有数据行
        byte[] empty = SimpleXlsx.write(DeptImportHeaders.EXPECTED, List.of());
        assertEquals(40001, codeOf(importFileRaw(admin, empty)));

        // 非法文件（不是 zip）
        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new ByteArrayResource("不是xlsx".getBytes()) {
                    @Override
                    public String getFilename() {
                        return "bad.xlsx";
                    }
                });
        Map<?, ?> bad =
                bodyOf(
                        rest.postForEntity(
                                "/api/hospital/system/orgs/import",
                                new HttpEntity<>(parts, headers),
                                Map.class));
        assertEquals(40001, codeOf(bad), "非法文件报参数错误：" + bad);

        // 权限：doctor.demo（随访医生）无 tenant:org:edit → 40300
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        byte[] ok =
                SimpleXlsx.write(
                        DeptImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "ORG-DEMO",
                                        "IT-D-IMP-PERM",
                                        "",
                                        "IT 权限示例科室",
                                        "",
                                        "",
                                        "",
                                        "")));
        assertEquals(40300, codeOf(importFileRaw(doctor, ok)));
    }

    @Test
    void importIsTenantIsolated() throws Exception {
        String admin = adminToken();
        // demoa 建院区并导入；demob 的树不含该科室，demob 也看不到 demoa 的 ORG_IMPORT 审计
        createOrg("IT-ORG-IMP", "IT 示例院区", admin);
        byte[] file =
                SimpleXlsx.write(
                        DeptImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-ISO",
                                        "",
                                        "IT 隔离示例科室",
                                        "",
                                        "",
                                        "",
                                        "")));
        assertEquals(0, codeOf(importFileRaw(admin, file)));
        Integer demobCount =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_dept WHERE code = 'IT-D-IMP-ISO'",
                                        Integer.class));
        assertEquals(0, demobCount, "demob 库不出现 demoa 导入的科室");
        Integer demobAudits =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'ORG_IMPORT'",
                                        Integer.class));
        assertEquals(0, demobAudits, "审计只写本院");
    }

    @Test
    void importRejectsCircularParentChainInsideFile() throws Exception {
        String admin = adminToken();
        createOrg("IT-ORG-IMP", "IT 示例院区", admin);
        byte[] file =
                SimpleXlsx.write(
                        DeptImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-A",
                                        "IT-D-IMP-B",
                                        "示例甲",
                                        "",
                                        "",
                                        "",
                                        ""),
                                List.of(
                                        "IT-ORG-IMP",
                                        "IT-D-IMP-B",
                                        "IT-D-IMP-A",
                                        "示例乙",
                                        "",
                                        "",
                                        "",
                                        "")));
        Map<?, ?> report = importFile(admin, file);
        assertEquals(2, ((Number) report.get("failed")).intValue(), "互相引用全部失败：" + report);
        Integer count =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_dept WHERE code IN ('IT-D-IMP-A','IT-D-IMP-B')",
                                        Integer.class));
        assertEquals(0, count, "循环引用的两行都不落库");
    }

    // ---------------------------------------------------------------- 变更发事件（验收）

    @Test
    void userStatusChangesPublishOutboxEvents() {
        String admin = adminToken();
        long userId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                            + " must_change_password) VALUES ('IT-U-IMP-STAT', 'IT 示例状态用户',"
                                            + " 'x', 'ENABLED', 0)");
                            return jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = 'IT-U-IMP-STAT'",
                                    Long.class);
                        });

        // 停用 → user.disabled
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "DISABLE", "reason", "长期休假（示例）"))));
        Map<String, Object> disabled = latestOutboxEvent("demoa", "user.disabled");
        assertEquals("IT-U-IMP-STAT", disabled.get("username"));
        assertEquals("长期休假（示例）", disabled.get("reason"));
        assertNotNull(disabled.get("occurredAt"), "事件带发生时间");
        assertEquals("demoa", disabled.get("tenantCode"));

        // 重新启用 → 不发事件（规格只要求离职、调岗、停用）
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "ENABLE"))));

        // 离职 → user.resigned
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "RESIGN"))));
        Map<String, Object> resigned = latestOutboxEvent("demoa", "user.resigned");
        assertEquals("IT-U-IMP-STAT", resigned.get("username"));

        // demob 看不到 demoa 的事件（租户库隔离）
        Integer demobEvents =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox", Integer.class));
        assertEquals(0, demobEvents, "事件只写本院 sys_outbox");
    }

    @Test
    void userDeptAndRoleChangesPublishEvents() {
        String admin = adminToken();
        long cardioId = deptIdByCode("demoa", "DEPT-CARDIO");
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                            + " must_change_password) VALUES ('IT-U-IMP-MOVE', 'IT 示例调岗用户',"
                                            + " 'x', 'ENABLED', 0)");
                            long userId =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_user WHERE username = 'IT-U-IMP-MOVE'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                            + " VALUES ('IT-R-IMP-A', 'IT 示例角色甲', 0, 'ENABLED', 'CUSTOM'),"
                                            + " ('IT-R-IMP-B', 'IT 示例角色乙', 0, 'ENABLED', 'CUSTOM')");
                            Long roleA =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-IMP-A'",
                                            Long.class);
                            Long roleB =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-IMP-B'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                    userId,
                                    roleA);
                            // 主科室先落在心内科
                            jdbc.update(
                                    "UPDATE sys_user SET main_dept_id = ? WHERE id = ?",
                                    cardioId,
                                    userId);
                            return roleB;
                        });
        long userId = userIdOf("demoa", "IT-U-IMP-MOVE");

        // 新建一个科室做调岗目标，编辑用户：主科室换 + 角色换成乙
        long targetDeptId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_dept (org_id, parent_id, code, name, sort_no, status)"
                                            + " VALUES (?, 0, 'IT-D-IMP-MOVE', 'IT 调岗目标科室', 1, 'ENABLED')",
                                    orgIdByCode("demoa", "ORG-DEMO"));
                            return jdbc.queryForObject(
                                    "SELECT id FROM sys_dept WHERE code = 'IT-D-IMP-MOVE'",
                                    Long.class);
                        });

        Map<?, ?> updateResponse =
                put(
                        "/api/hospital/system/users/" + userId,
                        admin,
                        Map.of(
                                "displayName",
                                "IT 示例调岗用户",
                                "mainDeptId",
                                targetDeptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, codeOf(updateResponse), "编辑用户失败：" + updateResponse);
        assertEquals(
                targetDeptId,
                ((Number) dataOf(updateResponse).get("mainDeptId")).longValue(),
                "主科室已更新");

        Map<String, Object> deptEvent = latestOutboxEvent("demoa", "user.dept_changed");
        assertEquals("IT-U-IMP-MOVE", deptEvent.get("username"));
        assertEquals(
                cardioId,
                ((Number) ((Map<?, ?>) deptEvent.get("previousMainDept")).get("id")).longValue(),
                "旧主科室");
        assertEquals(
                targetDeptId,
                ((Number) ((Map<?, ?>) deptEvent.get("newMainDept")).get("id")).longValue(),
                "新主科室");

        Map<String, Object> roleEvent = latestOutboxEvent("demoa", "user.roles_changed");
        assertEquals(List.of("IT-R-IMP-A"), roleEvent.get("previousRoleCodes"));
        assertEquals(List.of("IT-R-IMP-B"), roleEvent.get("newRoleCodes"));

        // 没有变化的编辑不重复发事件（角色与主科室都保持）
        put(
                "/api/hospital/system/users/" + userId,
                admin,
                Map.of(
                        "displayName",
                        "IT 示例调岗用户（改名）",
                        "mainDeptId",
                        targetDeptId,
                        "roleIds",
                        List.of(roleId)));
        Integer deptEvents =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox WHERE event_type = 'user.dept_changed'",
                                        Integer.class));
        Integer roleEvents =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox WHERE event_type = 'user.roles_changed'",
                                        Integer.class));
        assertEquals(1, deptEvents, "主科室未再变化不重发");
        assertEquals(1, roleEvents, "角色未再变化不重发");
    }

    @Test
    void deptAndOrgDisablePublishEvents() {
        String admin = adminToken();
        createOrg("IT-ORG-IMP", "IT 示例院区", admin);
        long deptId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_dept (org_id, parent_id, code, name, sort_no, status)"
                                            + " VALUES (?, 0, 'IT-D-IMP-DIS', 'IT 停用示例科室', 1, 'ENABLED')",
                                    orgIdByCode("demoa", "IT-ORG-IMP"));
                            return jdbc.queryForObject(
                                    "SELECT id FROM sys_dept WHERE code = 'IT-D-IMP-DIS'",
                                    Long.class);
                        });
        long orgId = orgIdByCode("demoa", "IT-ORG-IMP");

        // 科室停用 → dept.disabled
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/depts/" + deptId + "/status",
                                admin,
                                Map.of("action", "DISABLE", "reason", "合并到其他科室（示例）"))));
        Map<String, Object> deptEvent = latestOutboxEvent("demoa", "dept.disabled");
        assertEquals("IT-D-IMP-DIS", deptEvent.get("deptCode"));
        assertEquals("IT 示例院区", deptEvent.get("orgName"));

        // 院区停用 → org.disabled（下级已停用，允许）
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/orgs/" + orgId + "/status",
                                admin,
                                Map.of("action", "DISABLE"))));
        Map<String, Object> orgEvent = latestOutboxEvent("demoa", "org.disabled");
        assertEquals("IT-ORG-IMP", orgEvent.get("orgCode"));

        // 事件状态为 PENDING（分发属 E09）
        assertEquals("PENDING", String.valueOf(deptEvent.get("__status")));
    }

    // ---------------------------------------------------------------- 辅助

    /** 与后端模板表头同构（避免测试依赖生产常量的可见性）。 */
    private static final class DeptImportHeaders {
        static final List<String> EXPECTED =
                List.of("院区编码", "科室编码", "上级科室编码", "科室名称", "科室类型", "HIS科室编码", "负责人账号", "排序");
    }

    /** 最近一条该类型事件（payload 解析为 Map；__status 旁挂投递状态）。 */
    private Map<String, Object> latestOutboxEvent(String tenant, String type) {
        Map<String, Object> raw =
                runInTenant(
                        tenant,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT JSON_UNQUOTE(payload) AS payloadText, status"
                                                + " FROM sys_outbox WHERE event_type = ?"
                                                + " ORDER BY id DESC LIMIT 1",
                                        type));
        try {
            Map<String, Object> payload =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(String.valueOf(raw.get("payloadText")), Map.class);
            payload.put("__status", raw.get("status"));
            return payload;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("事件负载不是合法 JSON", ex);
        }
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
                        return "departments.xlsx";
                    }
                });
        return bodyOf(
                rest.postForEntity(
                        "/api/hospital/system/orgs/import",
                        new HttpEntity<>(parts, headers),
                        Map.class));
    }

    private void createOrg(String code, String name, String admin) {
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/orgs",
                                admin,
                                Map.of("code", code, "name", name))));
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

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return bodyOf(rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class));
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return bodyOf(
                rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class));
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

    private long userIdOf(String tenant, String username) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private long deptIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long orgIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_org WHERE code = ?", Long.class, code));
    }
}
