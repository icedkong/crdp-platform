package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * E06-T02 / PLT-ORG-001、002 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连）： 组织树、院区与科室的新增 / 编辑 / 移动 /
 * 停用（多级科室、编码不可改、唯一性 40902、有启用下级不可停用 40901）、 影响面、无权限 40300、不存在 40400、跨租户隔离（demoa 看不到 demob
 * 的院区）与操作审计。 CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。全部使用虚构示例数据（编码前缀 IT-）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class OrgManagementIT {

    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    /** 只清理本 IT 建的数据（编码前缀 IT-）与本 IT 的审计事件，不碰种子演示数据。 */
    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-%'");
                        jdbc.update("DELETE FROM sys_org WHERE code LIKE 'IT-ORG-%'");
                        jdbc.update(
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('ORG_CREATE','ORG_UPDATE','ORG_STATUS','DEPT_CREATE','DEPT_UPDATE','DEPT_STATUS')");
                    });
        }
    }

    @Test
    void treeShowsSeededCampusAndDept() {
        Map<?, ?> tree = get("/api/hospital/system/orgs/tree", adminToken());
        assertEquals(0, codeOf(tree));
        Map<?, ?> data = data(tree);
        assertNotNull(data.get("singleOrg"), "单院区口径标志必须下发（PLT-ORG-001 可隐藏院区层级）");
        String body = String.valueOf(data);
        assertTrue(body.contains("虚构院区") && body.contains("虚构心内科"), "种子院区与科室可见：" + body);
        assertTrue(body.contains("ORG-DEMO") && body.contains("DEPT-CARDIO"), "节点带编码");
    }

    @Test
    void campusCreateEditDisableWithRules() {
        String token = adminToken();

        // 校验失败：编码 / 名称必填、编码格式
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/orgs",
                                token,
                                Map.of("code", "", "name", "示例院区"))));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/orgs",
                                token,
                                Map.of("code", "IT-ORG-1", "name", " "))));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/orgs",
                                token,
                                Map.of("code", "非法 编码!", "name", "示例院区"))));

        // 正常新增
        Map<?, ?> created =
                post(
                        "/api/hospital/system/orgs",
                        token,
                        Map.of("code", "IT-ORG-1", "name", "示例北院区"));
        assertEquals(0, codeOf(created));
        long orgId = ((Number) data(created).get("id")).longValue();

        // 唯一性冲突 40902
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/system/orgs",
                                token,
                                Map.of("code", "IT-ORG-1", "name", "重复编码"))));

        // 编辑：名称可改、编码不可改
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/orgs/" + orgId,
                                token,
                                Map.of("code", "IT-ORG-X", "name", "示例北院区"))));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/orgs/" + orgId,
                                token,
                                Map.of("code", "IT-ORG-1", "name", "示例北院区改"))));

        // 多院区后树关闭单院区口径
        Map<?, ?> treeData = data(get("/api/hospital/system/orgs/tree", token));
        String tree = String.valueOf(treeData);
        assertTrue(tree.contains("示例北院区改"), tree);
        assertEquals(Boolean.FALSE, treeData.get("singleOrg"), "两个启用院区时不可隐藏院区层级");

        // 影响面与停用：尚无科室可直接停用；重复停用 40901；不存在 40400
        Map<?, ?> impact = get("/api/hospital/system/orgs/" + orgId + "/impact", token);
        assertEquals(0, codeOf(impact));
        assertEquals(0, ((Number) data(impact).get("deptCount")).intValue());
        assertEquals(
                0,
                codeOf(
                        postStatus(
                                "/api/hospital/system/orgs/" + orgId + "/status",
                                token,
                                "DISABLE")));
        assertEquals(
                40901,
                codeOf(
                        postStatus(
                                "/api/hospital/system/orgs/" + orgId + "/status",
                                token,
                                "DISABLE")));
        assertEquals(
                0,
                codeOf(
                        postStatus(
                                "/api/hospital/system/orgs/" + orgId + "/status",
                                token,
                                "ENABLE")));
        assertEquals(40400, codeOf(get("/api/hospital/system/orgs/999999/impact", token)));
        assertEquals(
                40001,
                codeOf(
                        postStatus(
                                "/api/hospital/system/orgs/" + orgId + "/status",
                                token,
                                "DELETE")));
    }

    @Test
    void deptTreeCreateMoveSortAndDisableRules() {
        String token = adminToken();
        long rootOrgId = orgIdByCode("ORG-DEMO");

        // 校验失败：院区必填、编码必填、负责人必须是启用用户
        assertEquals(
                40001, codeOf(post("/api/hospital/system/depts", token, Map.of("name", "示例科室"))));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/depts",
                                token,
                                Map.of("orgId", rootOrgId, "name", "示例科室"))));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/depts",
                                token,
                                Map.of(
                                        "orgId",
                                        rootOrgId,
                                        "code",
                                        "IT-D-1",
                                        "name",
                                        "示例科室",
                                        "leaderUserId",
                                        999999))));

        // 正常新增：顶层科室 + 两个子科室（排序、类型、负责人、HIS 编码）
        long cardioId =
                createDept(
                        token,
                        rootOrgId,
                        0,
                        "IT-D-100",
                        "示例心内科",
                        "CLINICAL",
                        "LEADER",
                        "HIS-100",
                        20);
        long sonId =
                createDept(
                        token,
                        rootOrgId,
                        cardioId,
                        "IT-D-110",
                        "示例CCU",
                        "CLINICAL",
                        null,
                        null,
                        10);
        long otherId =
                createDept(
                        token, rootOrgId, cardioId, "IT-D-120", "示例超声室", "MEDICAL", null, null, 5);

        // 多级与排序：树里 CCU（10）在超声室（5）之后；负责人姓名联查返回
        String tree = String.valueOf(data(get("/api/hospital/system/orgs/tree", token)));
        assertTrue(tree.contains("示例CCU") && tree.contains("示例超声室"), tree);
        assertTrue(tree.indexOf("示例超声室") < tree.indexOf("示例CCU"), "同级按 sortNo 升序：" + tree);
        assertTrue(tree.contains("演示护士"), "负责人姓名来自 sys_user.display_name");

        // 编码唯一与不可改
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/system/depts",
                                token,
                                Map.of("orgId", rootOrgId, "code", "IT-D-100", "name", "重复编码"))));
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + cardioId,
                                token,
                                Map.of("orgId", rootOrgId, "code", "IT-D-999", "name", "示例心内科"))));

        // 移动：先测防环（son 还在 cardio 子树内），再做合法移动
        assertEquals(
                40901,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + cardioId,
                                token,
                                Map.of(
                                        "orgId",
                                        rootOrgId,
                                        "parentDeptId",
                                        sonId,
                                        "name",
                                        "示例心内科"))),
                "移入本科室下级会成环");
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + sonId,
                                token,
                                Map.of("orgId", rootOrgId, "parentDeptId", 0, "name", "示例CCU"))),
                "移到院区顶层是合法移动");
        long foreignOrgId = createCampus(token, "IT-ORG-F", "示例外院区");
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + sonId,
                                token,
                                Map.of("orgId", foreignOrgId, "parentDeptId", 0, "name", "示例CCU"))),
                "整体移动到另一院区顶层是合法编辑");
        assertEquals(
                40901,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + sonId,
                                token,
                                Map.of(
                                        "orgId",
                                        foreignOrgId,
                                        "parentDeptId",
                                        cardioId,
                                        "name",
                                        "示例CCU"))),
                "上级科室与所填院区不一致");
        assertEquals(
                40901,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + sonId,
                                token,
                                Map.of(
                                        "orgId",
                                        rootOrgId,
                                        "parentDeptId",
                                        999999,
                                        "name",
                                        "示例CCU"))),
                "上级科室不存在");
        // 移回本院区顶层，后续停用用例沿用原结构
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/depts/" + sonId,
                                token,
                                Map.of("orgId", rootOrgId, "parentDeptId", 0, "name", "示例CCU"))));

        // 停用规则：有启用下级先挡（40901），停用下级后可停用；停用不出现在「启用选择器」口径由前端按 status 过滤
        assertEquals(
                40901,
                codeOf(
                        postStatus(
                                "/api/hospital/system/depts/" + cardioId + "/status",
                                token,
                                "DISABLE")));
        Map<?, ?> impact = get("/api/hospital/system/depts/" + cardioId + "/impact", token);
        assertEquals(0, codeOf(impact));
        assertTrue(((Number) data(impact).get("enabledChildCount")).intValue() > 0, "影响面带启用下级数");
        assertEquals(
                0,
                codeOf(
                        postStatus(
                                "/api/hospital/system/depts/" + otherId + "/status",
                                token,
                                "DISABLE")));
        assertEquals(
                0,
                codeOf(
                        postStatus(
                                "/api/hospital/system/depts/" + cardioId + "/status",
                                token,
                                "DISABLE")));
        assertEquals(
                40901,
                codeOf(
                        postStatus(
                                "/api/hospital/system/depts/" + otherId + "/status",
                                token,
                                "DISABLE")));

        // 有启用科室的院区不能停用（40901），停用全部科室后可以
        assertEquals(
                40901,
                codeOf(
                        postStatus(
                                "/api/hospital/system/orgs/" + rootOrgId + "/status",
                                token,
                                "DISABLE")));
        assertEquals(40400, codeOf(get("/api/hospital/system/depts/999999/impact", token)));

        // 审计：停用动作已写入 aud_log（同事务）
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type IN ('DEPT_STATUS','ORG_STATUS')",
                                        Integer.class));
        assertTrue(audits != null && audits >= 2, "状态变更写审计，实际 " + audits);
    }

    @Test
    void permissionDeniedAndCrossTenantIsolation() {
        String admin = adminToken();
        String doctor = hospitalToken("demoa", "doctor.demo");

        // doctor.demo 只有随访角色：tenant:org:view / edit 均无 → 40300
        assertEquals(40300, codeOf(get("/api/hospital/system/orgs/tree", doctor)));
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/system/depts",
                                doctor,
                                Map.of("orgId", 1, "code", "IT-D-401", "name", "示例科室"))));

        // 负责人候选（暂行）已由 PLT-ORG-012 共用用户选择器替换（E06-T09）：接口移除、未登记默认拒绝 40307
        assertEquals(40307, codeOf(get("/api/hospital/system/orgs/user-options", admin)));
        assertEquals(40307, codeOf(get("/api/hospital/system/orgs/user-options", doctor)));

        // 跨租户：demob 建的院区不出现在 demoa 的树里（租户库隔离）
        String demobAdmin = adminTokenOf("demob");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/orgs",
                                demobAdmin,
                                Map.of("code", "IT-ORG-B", "name", "乙院示例院区"))));
        String demoaTree = String.valueOf(data(get("/api/hospital/system/orgs/tree", admin)));
        assertTrue(!demoaTree.contains("乙院示例院区"), "demoa 不得看到 demob 的院区：" + demoaTree);
        assertTrue(
                String.valueOf(data(get("/api/hospital/system/orgs/tree", demobAdmin)))
                        .contains("乙院示例院区"),
                "demob 自己可见");
    }

    // ---- 辅助 ----

    private long createDept(
            String token,
            long orgId,
            long parentDeptId,
            String code,
            String name,
            String deptType,
            String leaderKind,
            String hisCode,
            int sortNo) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("orgId", orgId);
        body.put("parentDeptId", parentDeptId);
        body.put("code", code);
        body.put("name", name);
        body.put("deptType", deptType);
        body.put("hisCode", hisCode);
        body.put("sortNo", sortNo);
        if ("LEADER".equals(leaderKind)) {
            body.put("leaderUserId", userId("nurse.demo"));
        }
        Map<?, ?> response = post("/api/hospital/system/depts", token, body);
        assertEquals(0, codeOf(response), "新增科室失败：" + response);
        return ((Number) data(response).get("id")).longValue();
    }

    private long createCampus(String token, String code, String name) {
        Map<?, ?> response =
                post("/api/hospital/system/orgs", token, Map.of("code", code, "name", name));
        assertEquals(0, codeOf(response));
        return ((Number) data(response).get("id")).longValue();
    }

    private Map<?, ?> postStatus(String path, String token, String action) {
        return post(path, token, Map.of("action", action, "reason", "IT 用例（示例）"));
    }

    private long orgIdByCode(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_org WHERE code = ?", Long.class, code));
    }

    private long userId(String username) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo");
    }

    private String adminTokenOf(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response));
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
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
}
