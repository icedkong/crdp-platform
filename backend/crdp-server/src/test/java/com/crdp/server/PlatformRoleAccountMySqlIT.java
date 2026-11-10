package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E06-T04 端到端（验收原文：四个平台预置角色可用）。场景按序：
 *
 * <ol>
 *   <li>四个平台预置角色（PLT-ROLE-002）：种子 V104 预置平台管理员、实施人员、运维人员、客服支持， built_in 且默认视角权限与规格 §8
 *       一致（平台管理员后续可调整）；
 *   <li>每个预置角色「可用」（验收口径）：为实施 / 运维 / 客服各建一个平台账号（PLT-ROLE-001： 强制手机号、初始密码、首次登录强制改密），登录后按角色下发菜单与权限码；
 *   <li>预置角色权限可调整且即时生效（platform-spec §1）：给运维补授「授权范围模板」后，
 *       运维账号同一会话下一次请求即拿到新权限码（PLT-PERM-010），收回即失去；角色停用即失效；
 *   <li>平台自定义角色（PLT-ROLE-003）：新建（编码服务端生成）、维护、「指定租户」暂行拒绝（OQ-48）；
 *   <li>账号管理约束：手机号必填、密码策略、角色必填、停用账号不能授予、账号名唯一； 平台管理员至少保留 2 个启用账号（40901）；重置密码一次性临时密码 + 旧会话全失效；解锁；
 *   <li>权限边界：无权限 40300、不存在 40400。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（删除本 IT 建的账号、
 * 自定义角色、审计事件，恢复运维角色的菜单授权）。数据全部虚构（手机号夹具为 139-0000 虚构测试号段）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformRoleAccountMySqlIT {

    private static final String ADMIN_USERNAME = "platform.admin";
    private static final String ADMIN_PASSWORD = "Demo@2026";
    private static final String NEW_PASSWORD = "Role#2026x";

    /** 本 IT 创建的账号 → 清理用。 */
    private static final List<String> createdUsernames = new ArrayList<>();

    /** 本 IT 创建的自定义角色编码前缀 → 清理用。 */
    private static final String CUSTOM_PREFIX = "IT_ROLE_";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        // 会话与登录失败计数：避免重复跑本 IT 时旧会话 / 锁定计数互相影响
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---------------------------------------------------------------- 1. 预置角色与默认权限

    @Test
    @Order(1)
    void fourPresetRolesExistWithConfirmedDefaults() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        List<Map<String, Object>> roles = itemsOf(get("/api/platform/roles", token));

        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> role : roles) {
            byCode.put((String) role.get("code"), role);
        }
        for (String code : List.of("PLATFORM_ADMIN", "IMPLEMENTATION", "OPERATIONS", "SUPPORT")) {
            Map<String, Object> role = byCode.get(code);
            assertNotNull(role, "预置角色存在：" + code + "，实际 " + byCode.keySet());
            assertEquals(Boolean.TRUE, role.get("builtIn"), code + " 为内置角色");
            assertEquals("ENABLED", role.get("status"), code + " 启用");
        }
        // 默认视角权限（platform-spec §8 预置平台角色的默认视角权限表，已确认）
        assertView(
                byCode.get("PLATFORM_ADMIN"), true, "CONFIG_AND_BUSINESS", "ALL", true, true, true);
        assertView(
                byCode.get("IMPLEMENTATION"),
                true,
                "CONFIG_AND_BUSINESS",
                "ALL",
                true,
                false,
                false);
        assertView(byCode.get("OPERATIONS"), true, "CONFIG_ONLY", "ALL", false, false, false);
        assertView(
                byCode.get("SUPPORT"),
                true,
                "CONFIG_AND_BUSINESS",
                "RESPONSIBLE",
                false,
                true,
                false);
    }

    // ---------------------------------------------------------------- 2. 三个预置角色各建号登录（验收：可用）

    @Test
    @Order(2)
    void implementationAccountSeesProvisioningMenus() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long roleId = roleIdByCode("IMPLEMENTATION");
        long accountId = createAccount(token, "it.impl.demo", "实施演示（示例）", roleId);
        createdUsernames.add("it.impl.demo");

        String implToken = loginWithForcedChange("it.impl.demo");
        List<String> codes = itemsOf(get("/api/platform/auth/codes", implToken));
        assertTrue(codes.contains("platform:tenant:view"), "实施人员可看租户管理");
        assertTrue(codes.contains("platform:module:view"), "实施人员可看模块目录");
        assertTrue(codes.contains("platform:scope:template:edit"), "实施人员可维护授权范围模板");
        assertFalse(codes.contains("platform:account:view"), "实施人员默认不可管理平台账号");
        assertFalse(codes.contains("platform:role:edit"), "实施人员不可维护平台角色");
        List<Map<String, Object>> menus = itemsOf(get("/api/platform/menus", implToken));
        assertTrue(containsPath(menus, "/platform/tenant"), "实施人员菜单含租户管理（目录内）");
        assertFalse(containsPath(menus, "/platform/account"), "实施人员菜单不含平台账号");

        // 账号视图：角色绑定与手机号脱敏（AGENTS 第 5 节：不回明文）
        Map<String, Object> view = dataOf(get("/api/platform/accounts/" + accountId, token));
        assertEquals("it.impl.demo", view.get("username"));
        assertEquals(List.of("实施人员"), view.get("roleNames"));
        assertTrue(
                String.valueOf(view.get("phoneMasked")).contains("****"),
                "手机号只回脱敏值：" + view.get("phoneMasked"));
    }

    @Test
    @Order(3)
    void operationsAccountIsConfigOnlyAndViewOnly() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        createAccount(token, "it.ops.demo", "运维演示（示例）", roleIdByCode("OPERATIONS"));
        createdUsernames.add("it.ops.demo");

        String opsToken = loginWithForcedChange("it.ops.demo");
        List<String> codes = itemsOf(get("/api/platform/auth/codes", opsToken));
        assertTrue(codes.contains("platform:tenant:view"));
        assertTrue(codes.contains("platform:menu:view"), "运维可看菜单资源");
        assertFalse(codes.contains("platform:scope:template:edit"), "运维只读，无模板维护");
        assertFalse(codes.contains("platform:tenant:status"), "运维无任何配置变更权限");

        // 无权限矩阵（AGENTS 第 6 节）：运维调维护接口 40300
        assertEquals(
                40300,
                codeOf(
                        post(
                                Map.of("name", "越权角色（示例）", "menuIds", List.of()),
                                "/api/platform/roles",
                                opsToken)),
                "运维无 platform:role:edit");
        assertEquals(
                40300,
                codeOf(get("/api/platform/accounts", opsToken)),
                "运维无 platform:account:view");
    }

    @Test
    @Order(4)
    void supportAccountIsResponsibleTenantScopedViewer() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        createAccount(token, "it.support.demo", "客服演示（示例）", roleIdByCode("SUPPORT"));
        createdUsernames.add("it.support.demo");

        String supportToken = loginWithForcedChange("it.support.demo");
        List<String> codes = itemsOf(get("/api/platform/auth/codes", supportToken));
        assertTrue(codes.contains("platform:tenant:view"), "客服可看租户（进入负责租户视角的入口）");
        assertTrue(codes.contains("platform:view:enter"), "客服默认可进入租户视角（种子 V107，PLT-VIEW-001）");
        assertEquals(
                2,
                codes.stream().filter(code -> code.startsWith("platform:")).count(),
                "客服默认只有租户查看与进入视角两个平台权限码，实际 " + codes);
    }

    // ---------------------------------------------------------------- 3. 预置角色权限可调整且即时生效

    @Test
    @Order(5)
    void presetRolePermissionChangeAppliesToOnlineSessionImmediately() {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long operationsRole = roleIdByCode("OPERATIONS");
        long accountId = accountIdByUsername("it.ops.demo");

        // 补授「授权范围模板」（菜单 124）→ 持有者权限版本递增（PLT-PERM-010）
        Map<String, Object> opsRole =
                dataOf(get("/api/platform/roles/" + operationsRole, adminToken));
        @SuppressWarnings("unchecked")
        List<Number> menuIds = new ArrayList<>((List<Number>) opsRole.get("menuIds"));
        assertFalse(menuIds.isEmpty(), "运维角色有默认菜单授权");
        menuIds.add(124L);
        Map<String, Object> saveRequest = roleSaveFrom(opsRole, menuIds);
        Map<?, ?> updated = put("/api/platform/roles/" + operationsRole, saveRequest, adminToken);
        assertEquals(0, codeOf(updated), "预置角色权限可调整：" + String.valueOf(updated.get("message")));

        // 同一会话下一次请求即拿到新权限码（在线会话重算，OQ-40 ①）
        String opsToken = login("it.ops.demo", NEW_PASSWORD + "B");
        assertTrue(
                itemsOf(get("/api/platform/auth/codes", opsToken))
                        .contains("platform:scope:template:edit"),
                "补授后在线会话即时生效");

        // 收回 → 立即失去
        saveRequest =
                roleSaveFrom(
                        opsRole, menuIds.stream().filter(id -> id.longValue() != 124L).toList());
        assertEquals(
                0, codeOf(put("/api/platform/roles/" + operationsRole, saveRequest, adminToken)));
        assertFalse(
                itemsOf(get("/api/platform/auth/codes", opsToken))
                        .contains("platform:scope:template:edit"),
                "收回后在线会话立即失去权限码");
    }

    // ---------------------------------------------------------------- 4. 自定义角色（PLT-ROLE-003）

    @Test
    @Order(6)
    void customRoleLifecycle() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // 新建：授权平台端菜单 + 配置租户视角权限（第 8 章六字段）；编码服务端生成
        Map<?, ?> created =
                post(
                        Map.of(
                                "name", "巡检角色（示例）",
                                "description", "本 IT 创建的自定义角色",
                                "menuIds", List.of(100L, 111L),
                                "viewCanEnter", true,
                                "viewVisibleScope", "CONFIG_ONLY",
                                "viewTenantScope", "ALL"),
                        "/api/platform/roles",
                        token);
        assertEquals(0, codeOf(created), "新建自定义角色：" + String.valueOf(created.get("message")));
        Map<?, ?> view = dataOf(created);
        String code = (String) view.get("code");
        assertTrue(code.startsWith("CUSTOM_"), "自定义角色编码服务端生成：" + code);
        assertTrue(code.length() <= 64);
        assertEquals(Boolean.FALSE, view.get("builtIn"));

        // 「指定租户」（PLT-VIEW-005，E20-T01 起可选）：缺清单 40001；带存在的租户清单可建（OQ-48 收敛）
        Map<?, ?> noList =
                post(
                        Map.of(
                                "name", "指定租户缺清单（示例）",
                                "menuIds", List.of(),
                                "viewCanEnter", true,
                                "viewVisibleScope", "CONFIG_ONLY",
                                "viewTenantScope", "SPECIFIED"),
                        "/api/platform/roles",
                        token);
        assertEquals(40001, codeOf(noList));
        assertTrue(String.valueOf(noList.get("message")).contains("至少一个租户"));

        long demoTenantId =
                platformDirect()
                        .queryForObject(
                                "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");
        Map<?, ?> withList =
                post(
                        Map.of(
                                "name",
                                "指定租户角色（示例）",
                                "menuIds",
                                List.of(),
                                "viewCanEnter",
                                true,
                                "viewVisibleScope",
                                "CONFIG_ONLY",
                                "viewTenantScope",
                                "SPECIFIED",
                                "viewSpecifiedTenantIds",
                                List.of(demoTenantId)),
                        "/api/platform/roles",
                        token);
        assertEquals(0, codeOf(withList), String.valueOf(withList.get("message")));
        // JSON 反序列化为 Integer，按数值比较（避免 Long/Integer equals 失真）
        Object echoed =
                dataOf(get("/api/platform/roles/" + dataOf(withList).get("id"), token))
                        .get("specifiedTenantIds");
        assertTrue(
                echoed instanceof List<?> echoedList
                        && echoedList.size() == 1
                        && ((Number) echoedList.get(0)).longValue() == demoTenantId,
                "详情回显指定租户清单，实际 " + echoed);

        // 越界菜单不能授权
        Map<?, ?> badMenu =
                post(
                        Map.of("name", "坏菜单角色（示例）", "menuIds", List.of(21L)),
                        "/api/platform/roles",
                        token);
        assertEquals(40001, codeOf(badMenu), "医院端菜单不能授权给平台角色");

        // 维护：绑定持有者 → 停用（不提供删除；预置角色同样只能维护）→ 持有者立即失去
        long roleId = ((Number) view.get("id")).longValue();
        createAccount(token, "it.custom.demo", "自定义演示（示例）", roleId);
        createdUsernames.add("it.custom.demo");
        String holderToken = loginWithForcedChange("it.custom.demo");
        List<String> holderCodes = itemsOf(get("/api/platform/auth/codes", holderToken));
        assertTrue(
                holderCodes.contains("platform:tenant:view"),
                "启用中的自定义角色授权进入会话，实际 codes=" + holderCodes);
        String customCode = (String) view.get("code");
        Map<?, ?> me = dataOf(get("/api/platform/auth/me", holderToken));
        assertTrue(((List<?>) me.get("roles")).contains(customCode), "角色清单含新角色：" + me.get("roles"));

        Map<?, ?> updated =
                put(
                        "/api/platform/roles/" + roleId,
                        Map.of(
                                "name", "巡检角色改名（示例）",
                                "description", "",
                                "status", "DISABLED",
                                "menuIds", List.of(100L),
                                "viewCanEnter", false,
                                "viewVisibleScope", "CONFIG_ONLY",
                                "viewTenantScope", "RESPONSIBLE"),
                        token);
        assertEquals(0, codeOf(updated), String.valueOf(updated.get("message")));
        assertEquals("DISABLED", dataOf(updated).get("status"));
        assertEquals(Boolean.FALSE, dataOf(updated).get("builtIn"));

        // 角色停用后持有者立即失去对应权限（platform-spec §1 业务规则 + PLT-PERM-010）
        List<String> codes = itemsOf(get("/api/platform/auth/codes", holderToken));
        assertFalse(codes.contains("platform:tenant:view"), "停用角色的授权立即退出会话");
        me = dataOf(get("/api/platform/auth/me", holderToken));
        assertFalse(
                ((List<?>) me.get("roles")).contains(customCode), "停用角色从角色清单消失：" + me.get("roles"));
    }

    // ---------------------------------------------------------------- 5. 账号管理约束

    @Test
    @Order(7)
    void accountConstraintsAndAdminQuorum() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long implRole = roleIdByCode("IMPLEMENTATION");

        // 手机号必填（PLT-ROLE-001：强制绑定，双因素用）
        Map<?, ?> noPhone =
                post(
                        Map.of(
                                "username",
                                "it.nophone.demo",
                                "displayName",
                                "无手机（示例）",
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(implRole)),
                        "/api/platform/accounts",
                        token);
        assertEquals(40001, codeOf(noPhone));
        assertTrue(String.valueOf(noPhone.get("message")).contains("手机号"));

        // 初始密码策略（42200）
        Map<?, ?> weakPassword =
                post(
                        Map.of(
                                "username", "it.weak.demo",
                                "displayName", "弱密码（示例）",
                                "phone", "13900007701",
                                "password", "weak",
                                "roleIds", List.of(implRole)),
                        "/api/platform/accounts",
                        token);
        assertEquals(42200, codeOf(weakPassword));

        // 角色必填 / 停用角色不能授予
        Map<?, ?> noRoles =
                post(
                        Map.of(
                                "username", "it.norole.demo",
                                "displayName", "无角色（示例）",
                                "phone", "13900007702",
                                "password", NEW_PASSWORD + "A",
                                "roleIds", List.of()),
                        "/api/platform/accounts",
                        token);
        assertEquals(40001, codeOf(noRoles));

        // 账号名唯一（40901）
        Map<?, ?> duplicate =
                post(
                        Map.of(
                                "username",
                                ADMIN_USERNAME,
                                "displayName",
                                "重复账号（示例）",
                                "phone",
                                "13900007703",
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(implRole)),
                        "/api/platform/accounts",
                        token);
        assertEquals(40901, codeOf(duplicate));

        // 平台管理员至少保留 2 个启用账号（platform-spec §1，已确认）：
        // 建第二个平台管理员 → 停用它后只剩 1 个 → 40901；补足第三个后再停用 → 放行
        long adminRole = roleIdByCode("PLATFORM_ADMIN");
        Map<?, ?> secondAdmin =
                post(
                        Map.of(
                                "username", "it.admin2.demo",
                                "displayName", "第二管理员（示例）",
                                "phone", "13900007704",
                                "password", NEW_PASSWORD + "A",
                                "roleIds", List.of(adminRole)),
                        "/api/platform/accounts",
                        token);
        assertEquals(0, codeOf(secondAdmin), String.valueOf(secondAdmin.get("message")));
        createdUsernames.add("it.admin2.demo");
        long secondAdminId = ((Number) dataOf(secondAdmin).get("id")).longValue();

        Map<?, ?> rejectedDisable =
                put(
                        "/api/platform/accounts/" + secondAdminId,
                        Map.of(
                                "displayName", "第二管理员（示例）",
                                "phone", "13900007704",
                                "roleIds", List.of(adminRole),
                                "status", "DISABLED"),
                        token);
        assertEquals(40901, codeOf(rejectedDisable), "停用后平台管理员只剩 1 个，应拒绝：" + rejectedDisable);

        Map<?, ?> thirdAdmin =
                post(
                        Map.of(
                                "username", "it.admin3.demo",
                                "displayName", "第三管理员（示例）",
                                "phone", "13900007705",
                                "password", NEW_PASSWORD + "A",
                                "roleIds", List.of(adminRole)),
                        "/api/platform/accounts",
                        token);
        assertEquals(0, codeOf(thirdAdmin), String.valueOf(thirdAdmin.get("message")));
        createdUsernames.add("it.admin3.demo");

        Map<?, ?> allowedDisable =
                put(
                        "/api/platform/accounts/" + secondAdminId,
                        Map.of(
                                "displayName", "第二管理员（示例）",
                                "phone", "13900007704",
                                "roleIds", List.of(adminRole),
                                "status", "DISABLED"),
                        token);
        assertEquals(
                0,
                codeOf(allowedDisable),
                "补足后可停用：" + String.valueOf(allowedDisable.get("message")));
        assertEquals("DISABLED", dataOf(allowedDisable).get("status"));
    }

    @Test
    @Order(8)
    void resetPasswordIssuesOneTimeTemporaryAndKillsSessions() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long opsAccount = accountIdByUsername("it.ops.demo");
        String opsToken = login("it.ops.demo", NEW_PASSWORD + "B");

        // 重置：一次性临时密码；旧会话全失效；锁定一并解除。
        // 敏感操作需二次认证（E04-T08 / PLT-AUTH-012）：X-Reauth-Token 由 /auth/reauth 换取
        Map<?, ?> reset =
                postReauth(
                        Map.of(),
                        "/api/platform/accounts/" + opsAccount + "/reset-password",
                        token);
        assertEquals(0, codeOf(reset), String.valueOf(reset.get("message")));
        String temporary = (String) dataOf(reset).get("temporaryPassword");
        assertNotNull(temporary);
        assertTrue(temporary.length() >= 12, "临时密码长度充足");

        // 旧令牌下一次请求 40100（会话已失效）
        assertEquals(40100, codeOf(get("/api/platform/auth/me", opsToken)));

        // 临时密码可登录，且必须改密（mustChangePassword=true，codes 40305）
        Map<?, ?> relogin =
                post(
                        Map.of("username", "it.ops.demo", "password", temporary),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(relogin), String.valueOf(relogin.get("message")));
        String freshToken = (String) dataOf(relogin).get("token");
        assertEquals(
                Boolean.TRUE,
                dataOf(get("/api/platform/auth/me", freshToken)).get("mustChangePassword"));
        assertEquals(40305, codeOf(get("/api/platform/auth/codes", freshToken)));

        // 解锁（platform:account:reset 同权限点）：200 并写审计
        Map<?, ?> unlocked =
                post(Map.of(), "/api/platform/accounts/" + opsAccount + "/unlock", token);
        assertEquals(0, codeOf(unlocked), String.valueOf(unlocked.get("message")));
        assertEquals(Boolean.TRUE, dataOf(unlocked).get("unlocked"));

        // 40400：不存在的账号 / 角色（不暴露对象存在）
        assertEquals(40400, codeOf(get("/api/platform/accounts/999999", token)));
        assertEquals(40400, codeOf(get("/api/platform/roles/999999", token)));
    }

    // ---------------------------------------------------------------- 辅助

    /** 菜单树递归找 path（目录的子菜单嵌套在 children 里）。 */
    @SuppressWarnings("unchecked")
    private static boolean containsPath(List<Map<String, Object>> nodes, String path) {
        for (Map<String, Object> node : nodes) {
            if (path.equals(node.get("path"))) {
                return true;
            }
            Object children = node.get("children");
            if (children instanceof List<?>
                    && containsPath((List<Map<String, Object>>) children, path)) {
                return true;
            }
        }
        return false;
    }

    private void assertView(
            Map<String, Object> role,
            boolean canEnter,
            String visibleScope,
            String tenantScope,
            boolean canConfigure,
            boolean canReset,
            boolean canEdit) {
        Map<?, ?> view = (Map<?, ?>) role.get("view");
        assertEquals(canEnter, view.get("canEnter"), role.get("code") + ".canEnter");
        assertEquals(visibleScope, view.get("visibleScope"), role.get("code") + ".visibleScope");
        assertEquals(tenantScope, view.get("tenantScope"), role.get("code") + ".tenantScope");
        assertEquals(canConfigure, view.get("canConfigure"), role.get("code") + ".canConfigure");
        assertEquals(
                canReset, view.get("canResetPassword"), role.get("code") + ".canResetPassword");
        assertEquals(canEdit, view.get("canEditBusiness"), role.get("code") + ".canEditBusiness");
    }

    private static Map<String, Object> roleSaveFrom(Map<?, ?> role, List<Number> menuIds) {
        Map<?, ?> view = (Map<?, ?>) role.get("view");
        Map<String, Object> request = new HashMap<>();
        request.put("name", role.get("name"));
        request.put("description", role.get("description"));
        request.put("status", role.get("status"));
        request.put("menuIds", menuIds);
        request.put("viewCanEnter", view.get("canEnter"));
        request.put("viewVisibleScope", view.get("visibleScope"));
        request.put("viewTenantScope", view.get("tenantScope"));
        request.put("viewCanConfigure", view.get("canConfigure"));
        request.put("viewCanResetPassword", view.get("canResetPassword"));
        request.put("viewCanEditBusiness", view.get("canEditBusiness"));
        return request;
    }

    private String login(String username, String password) {
        Map<?, ?> response =
                post(
                        Map.of("username", username, "password", password),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(response), "登录成功：" + String.valueOf(response.get("message")));
        return (String) dataOf(response).get("token");
    }

    /** 新建账号的首次登录：改密前 codes/menus 40305，改密后本会话恢复可用（E04-T05 口径）。 */
    private String loginWithForcedChange(String username) {
        Map<?, ?> first =
                post(
                        Map.of("username", username, "password", NEW_PASSWORD + "A"),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(first), String.valueOf(first.get("message")));
        String token = (String) dataOf(first).get("token");
        assertEquals(
                Boolean.TRUE,
                dataOf(get("/api/platform/auth/me", token)).get("mustChangePassword"));
        Map<?, ?> changed =
                post(
                        Map.of(
                                "oldPassword",
                                NEW_PASSWORD + "A",
                                "newPassword",
                                NEW_PASSWORD + "B"),
                        "/api/platform/auth/password",
                        token);
        assertEquals(0, codeOf(changed), "首次改密成功：" + String.valueOf(changed.get("message")));
        return token;
    }

    private long createAccount(
            String adminToken, String username, String displayName, long roleId) {
        Map<?, ?> response =
                post(
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                displayName,
                                "phone",
                                "139000077" + (10 + createdUsernames.size() % 80),
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(roleId)),
                        "/api/platform/accounts",
                        adminToken);
        assertEquals(0, codeOf(response), "建号成功：" + String.valueOf(response.get("message")));
        return ((Number) dataOf(response).get("id")).longValue();
    }

    /** 平台库直连（不经租户上下文——测试进程没有租户会话，接口内已由服务端处理）。 */
    private JdbcTemplate platformDirect() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    private long roleIdByCode(String code) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_role WHERE code = ?", Long.class, code);
    }

    private long accountIdByUsername(String username) {
        return platformDirect()
                .queryForObject(
                        "SELECT id FROM plt_account WHERE username = ?", Long.class, username);
    }

    private Map<?, ?> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    /** 敏感操作（E04-T08 / PLT-AUTH-012）：先二次认证换 reauthToken，再携带 X-Reauth-Token 调用。 */
    private Map<?, ?> postReauth(Map<String, Object> body, String path, String token) {
        Map<?, ?> reauth =
                post(Map.of("password", ADMIN_PASSWORD), "/api/platform/auth/reauth", token);
        assertEquals(0, codeOf(reauth), "二次认证失败：" + String.valueOf(reauth.get("message")));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("X-Reauth-Token", (String) dataOf(reauth).get("reauthToken"));
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> itemsOf(Map<?, ?> response) {
        return (List<T>) response.get("data");
    }

    /** 自建自清：删除本 IT 的账号、角色绑定、自定义角色与审计事件，恢复运维角色默认授权。 */
    @AfterAll
    static void cleanUp() {
        // Spring 上下文可能已随 JUnit 关闭，清理走独立连接（与 PlatformTenantScopeMySqlIT 同款做法）
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        // 账号与绑定（含密码历史）
        platform.update(
                "DELETE FROM plt_password_history WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.%')");
        platform.update(
                "DELETE FROM plt_account_role WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.%')");
        platform.update("DELETE FROM plt_account WHERE username LIKE 'it.%'");
        // 自定义角色与授权
        platform.update(
                "DELETE FROM plt_role_view_tenant WHERE role_id IN"
                        + " (SELECT id FROM plt_role WHERE code LIKE 'CUSTOM\\_%')");
        platform.update(
                "DELETE FROM plt_role_menu WHERE role_id IN"
                        + " (SELECT id FROM plt_role WHERE code LIKE 'CUSTOM\\_%')");
        platform.update(
                "DELETE FROM plt_account_role WHERE role_id IN"
                        + " (SELECT id FROM plt_role WHERE code LIKE 'CUSTOM\\_%')");
        platform.update("DELETE FROM plt_role WHERE code LIKE 'CUSTOM\\_%'");
        // 运维角色默认授权回到种子快照（V104 的 100/111/112/113 + V107 的视角进入按钮 143；
        // E20-T04 起租户视角 IT 依赖该授予，只清本 IT 补授的 124）
        platform.update(
                "DELETE FROM plt_role_menu WHERE role_id ="
                        + " (SELECT id FROM plt_role WHERE code = 'OPERATIONS')"
                        + " AND menu_id NOT IN (100, 111, 112, 113, 143)");
        // 审计痕迹（Redis 里的会话与登录计数按前缀在首测 @BeforeEach 清理）
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('ROLE_CREATE', 'ROLE_UPDATE', 'ACCOUNT_CREATE', 'ACCOUNT_UPDATE',"
                        + " 'ACCOUNT_RESET', 'ACCOUNT_UNLOCK')");
    }
}
