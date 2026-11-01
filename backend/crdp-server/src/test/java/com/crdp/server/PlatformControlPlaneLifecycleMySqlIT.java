package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.server.platform.TenantExpiryService;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E05-T12 端到端（验收：租户生命周期用例通过）：一个虚构租户从向导开通走完 试用 → 转正 → 到期宽限 → 宽限期满自动停用 → 续期恢复 的完整生命周期，
 * 并在每个生命周期状态上做模块开通 / 关闭，验证生命周期与模块开通的交叉契约——
 *
 * <ol>
 *   <li>试用 / 宽限期可通过 HTTP 开通模块（既有 IT 只覆盖启用态与向导内开通）；
 *   <li>停用 / 开通中 / 注销各态开通模块一律 40901；停用不拦平台侧关闭（医院侧访问才是被拦的对象）；
 *   <li>向导勾选下架模块 40901（PLT-MOD-004）；未知模块 / 未知租户 40400；
 *   <li>医院端会话贯穿全程：宽限期不失效、宽限期满自动停用立即 40100；
 *   <li>开通记录（PLT-MOD-009）与 TENANT_STATUS / TENANT_EXPIRE 审计完整留痕全程。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清。到期日只允许未来（E05-T02 校验）， 「已到期」初态沿用
 * E05-T03 IT 的做法直改平台库模拟；租户查找缓存 60 秒正负都缓存，停用后的重新登录不做成功断言，以存量会话 40100 断言吊销。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformControlPlaneLifecycleMySqlIT {

    private static final LocalDate TODAY = LocalDate.now();
    private static final String DEMO_PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private TenantExpiryService expiryService;

    private JdbcTemplate root;
    private JdbcTemplate platform;
    private final List<String> createdCodes = new java.util.ArrayList<>();

    // ---------------------------------------------------------------- 基础设施

    private void connect() {
        if (platform != null) {
            return;
        }
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                base + "/" + params, "root", "DevOnly#root2026"));
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        for (String code : createdCodes) {
            root.execute("DROP DATABASE IF EXISTS t_" + code);
            root.execute("DROP USER IF EXISTS `t_" + code + "`@'%'");
            // 模块审计的 target 是 plt_tenant:{code}:{module}，一并按前缀清掉
            platform.update(
                    "DELETE FROM plt_audit_log WHERE target LIKE ?", "plt_tenant:" + code + "%");
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_tenant_expire_notice WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_suspension WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module_config WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module_log WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
    }

    private String loginPlatform() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/platform/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "username",
                                                "platform.admin",
                                                "password",
                                                DEMO_PASSWORD),
                                        headers),
                                Map.class)
                        .getBody();
        return ((Map<?, ?>) login.get("data")).get("token").toString();
    }

    private HttpHeaders platformJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpMethod method, String url, HttpEntity<?> entity) {
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object value = response == null ? null : response.get("code");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    /** 向导建租户（走完整开通编排，管理员 admin / Init@2026#）并登记清理；返回 tenantId。医院名称全平台唯一，逐次递进取名。 */
    private long createWizardTenant(String name, Map<String, Object> extra, String explicitCode) {
        Map<String, Object> request = new HashMap<>(extra);
        request.put("name", name);
        if (explicitCode != null) {
            request.put("code", explicitCode);
        }
        request.putIfAbsent("adminUsername", "admin");
        request.putIfAbsent("adminDisplayName", "生命周期管理员（示例）");
        request.putIfAbsent("adminInitialPassword", "Init@2026#");
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(request, platformJson(loginPlatform())));
        assertEquals(0, code(response), () -> String.valueOf(response));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        createdCodes.add(data.get("code").toString());
        return ((Number) data.get("tenantId")).longValue();
    }

    /** 医院端登录并立即改掉初始密码（解除 40305 强制改密对后续断言的干扰），返回会话令牌。 */
    private String hospitalSession(String tenantCode) {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/login",
                        new HttpEntity<>(
                                Map.of(
                                        "tenantCode", tenantCode,
                                        "username", "admin",
                                        "password", "Init@2026#"),
                                json));
        assertEquals(0, code(login), () -> String.valueOf(login));
        String token = ((Map<?, ?>) login.get("data")).get("token").toString();
        HttpHeaders auth = new HttpHeaders();
        auth.setContentType(MediaType.APPLICATION_JSON);
        auth.setBearerAuth(token);
        Map<?, ?> changed =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/password",
                        new HttpEntity<>(
                                Map.of("oldPassword", "Init@2026#", "newPassword", "Walk@2026#"),
                                auth));
        assertEquals(0, code(changed), () -> String.valueOf(changed));
        return token;
    }

    // ---------------------------------------------------------------- 库内与视图辅助

    private String codeOf(long tenantId) {
        return platform.queryForObject(
                "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private String statusInDb(long tenantId) {
        return platform.queryForObject(
                "SELECT status FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private String moduleStatusInDb(long tenantId, String moduleCode) {
        return platform.queryForObject(
                "SELECT status FROM plt_tenant_module WHERE tenant_id = ? AND module_code = ?",
                String.class,
                tenantId,
                moduleCode);
    }

    @SuppressWarnings("unchecked")
    private List<String> menuTitles(String hospitalToken) {
        Map<?, ?> menus =
                body(
                        HttpMethod.GET,
                        "/api/hospital/menus",
                        new HttpEntity<>(plainBearer(hospitalToken)));
        return ((List<Map<String, Object>>) menus.get("data"))
                .stream().flatMap(node -> flatten(node).stream()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> flatten(Map<String, Object> node) {
        List<String> titles = new java.util.ArrayList<>();
        Object meta = node.get("meta");
        if (meta instanceof Map<?, ?> metaMap && metaMap.get("title") != null) {
            titles.add(String.valueOf(metaMap.get("title")));
        }
        Object childNodes = node.get("children");
        if (childNodes instanceof List<?> list) {
            for (Object child : list) {
                titles.addAll(flatten((Map<String, Object>) child));
            }
        }
        return titles;
    }

    private HttpHeaders plainBearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private int hospitalPing(String hospitalToken) {
        return code(
                body(
                        HttpMethod.GET,
                        "/api/hospital/followup/ping",
                        new HttpEntity<>(plainBearer(hospitalToken))));
    }

    private int hospitalMe(String hospitalToken) {
        return code(
                body(
                        HttpMethod.GET,
                        "/api/hospital/auth/me",
                        new HttpEntity<>(plainBearer(hospitalToken))));
    }

    private Map<String, Object> closeModule(String token, long tenantId, String reason) {
        return body(
                HttpMethod.POST,
                "/api/platform/tenants/" + tenantId + "/modules/followup/close",
                new HttpEntity<>(Map.of("reason", reason), platformJson(token)));
    }

    private Map<String, Object> openModule(String token, long tenantId, String reason) {
        return body(
                HttpMethod.POST,
                "/api/platform/tenants/" + tenantId + "/modules/followup/open",
                new HttpEntity<>(
                        reason == null ? Map.of() : Map.of("reason", reason), platformJson(token)));
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 租户生命周期主用例：向导开通（含 followup 模块、到期设置）→ 试用态模块开 / 关 → 转正 → 到期自动转宽限（会话不失效、仍可开通） →
     * 宽限期满自动停用（会话吊销、平台侧可关不可开）→ 续期恢复（停用时段关闭、模块可再开通）→ 全程留痕。
     */
    @Test
    @Order(1)
    void tenantWalksFullLifecycleWithModuleProvisioningAtEachStage() {
        connect();
        String token = loginPlatform();

        // 开通中 → 试用：向导勾选 followup，带到期设置（到期日明天、宽限 2 天，宽限覆盖 [到期日, 到期日+2)）
        long tenantId =
                createWizardTenant(
                        "虚构示例医院寅",
                        Map.of(
                                "expireType",
                                "FIXED",
                                "expireAt",
                                TODAY.plusDays(1).toString(),
                                "remindDays",
                                List.of(7),
                                "graceDays",
                                2,
                                "graceReadOnly",
                                false,
                                "modules",
                                List.of("followup")),
                        null);
        String code = codeOf(tenantId);
        assertEquals("TRIAL", statusInDb(tenantId));
        assertEquals("OPEN", moduleStatusInDb(tenantId, "base"));
        assertEquals("OPEN", moduleStatusInDb(tenantId, "followup"));

        // 租户详情 · 模块页签（PLT-MOD-004/005）：租户状态与模块状态随生命周期一路可见
        Map<?, ?> modulesView =
                (Map<?, ?>)
                        body(
                                        HttpMethod.GET,
                                        "/api/platform/tenants/" + tenantId + "/modules",
                                        new HttpEntity<>(platformJson(token)))
                                .get("data");
        assertEquals("TRIAL", modulesView.get("tenantStatus"));

        // 租户管理员登录一次，这个会话贯穿整个生命周期
        String hospitalToken = hospitalSession(code);

        // ---- 试用：HTTP 开通 / 关闭立即生效（既有 IT 只在启用态覆盖过 open 端点）
        assertTrue(menuTitles(hospitalToken).contains("随访"), "试用态开通的模块菜单应可见");
        assertEquals(0, hospitalPing(hospitalToken));
        assertEquals(0, code(closeModule(token, tenantId, "试用期间停用随访（示例）")));
        assertEquals("CLOSED", moduleStatusInDb(tenantId, "followup"));
        assertFalse(menuTitles(hospitalToken).contains("随访"), "关闭后菜单立即收回");
        assertEquals(40301, hospitalPing(hospitalToken), "关闭后模块接口立即拒绝");
        Map<?, ?> trialReopen = openModule(token, tenantId, "试用期间恢复随访（示例）");
        assertEquals(0, code(trialReopen), () -> String.valueOf(trialReopen));
        assertEquals("CLOSED", ((Map<?, ?>) trialReopen.get("data")).get("previousStatus"));
        assertEquals("OPEN", moduleStatusInDb(tenantId, "followup"));
        assertTrue(menuTitles(hospitalToken).contains("随访"), "重新开通后菜单恢复");
        assertEquals(0, hospitalPing(hospitalToken));

        // ---- 转正：试用 → 启用（人工流转，写审计）
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "ENABLE", "reason", "正式签约（示例）"),
                                        platformJson(token)))));
        assertEquals("ENABLED", statusInDb(tenantId));

        // ---- 服务到期：启用 → 宽限期（每日作业；已到期初态直改库模拟，同 E05-T03 IT）
        platform.update(
                "UPDATE plt_tenant SET expire_at = ? WHERE id = ?",
                java.sql.Date.valueOf(TODAY),
                tenantId);
        expiryService.runForDate(TODAY);
        assertEquals("GRACE", statusInDb(tenantId));
        assertEquals(0, hospitalMe(hospitalToken), "宽限期存量会话不失效");

        // ---- 宽限期：影响面能看到在线会话；模块仍可关 / 可开
        Map<?, ?> impact =
                (Map<?, ?>)
                        body(
                                        HttpMethod.GET,
                                        "/api/platform/tenants/"
                                                + tenantId
                                                + "/modules/followup/close-impact",
                                        new HttpEntity<>(platformJson(token)))
                                .get("data");
        assertEquals(
                1, ((Number) impact.get("onlineUserCount")).intValue(), "宽限期会话仍在线，影响面应计 1 个在线用户");
        assertEquals(0, code(closeModule(token, tenantId, "宽限期停用随访（示例）")));
        assertEquals(40301, hospitalPing(hospitalToken));
        Map<?, ?> graceReopen = openModule(token, tenantId, "宽限期恢复随访（示例）");
        assertEquals(0, code(graceReopen), () -> "宽限期应允许开通模块：" + graceReopen);
        assertEquals(0, hospitalPing(hospitalToken));

        // ---- 宽限期满：自动停用（吊销全部会话、开停用时段、写审计）
        expiryService.runForDate(TODAY.plusDays(2));
        assertEquals("DISABLED", statusInDb(tenantId));
        assertEquals(40100, hospitalMe(hospitalToken), "停用后在线会话立即失效（PLT-TEN-016）");
        Map<String, Object> suspension =
                platform.queryForMap(
                        "SELECT cause, reason, restored_at, restore_cause FROM"
                                + " plt_tenant_suspension WHERE tenant_id = ?",
                        tenantId);
        assertEquals("AUTO_GRACE_END", suspension.get("cause"));
        assertNull(suspension.get("restored_at"), "停用时段开段，恢复后才关闭");

        // ---- 停用态：平台侧模块管理不受影响（关闭可用），但开通被拒
        assertEquals(0, code(closeModule(token, tenantId, "停用期间整理模块（示例）")));
        Map<?, ?> blockedOpen = openModule(token, tenantId, "停用期间尝试开通（示例）");
        assertEquals(40901, code(blockedOpen), () -> String.valueOf(blockedOpen));
        assertTrue(
                String.valueOf(blockedOpen.get("message")).contains("不能开通模块"),
                blockedOpen.get("message").toString());
        assertEquals("CLOSED", moduleStatusInDb(tenantId, "followup"), "被拒的开通不得改状态");

        // ---- 续期：停用 → 启用（PLT-TEN-013），停用时段关闭、模块可再开通
        Map<?, ?> renewed =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + tenantId + "/expire",
                        new HttpEntity<>(
                                Map.of(
                                        "expireType", "FIXED",
                                        "expireAt", TODAY.plusDays(365).toString(),
                                        "reason", "续费一年（示例）"),
                                platformJson(token)));
        assertEquals(0, code(renewed), () -> String.valueOf(renewed));
        assertEquals("DISABLED", ((Map<?, ?>) renewed.get("data")).get("previousStatus"));
        assertEquals("ENABLED", statusInDb(tenantId));
        Map<String, Object> closedSuspension =
                platform.queryForMap(
                        "SELECT restored_at, restore_cause FROM plt_tenant_suspension"
                                + " WHERE tenant_id = ?",
                        tenantId);
        assertNotNull(closedSuspension.get("restored_at"), "续期后停用时段关闭");
        assertEquals("RENEWAL", closedSuspension.get("restore_cause"));
        assertEquals(0, code(openModule(token, tenantId, "续期后恢复随访（示例）")));

        // ---- 全程留痕：开通记录倒序齐全（followup 开 4 关 3；视图按模块过滤，base 的开通记录不在其中）
        Map<?, ?> logs =
                body(
                        HttpMethod.GET,
                        "/api/platform/modules/followup/open-logs?tenantId=" + tenantId,
                        new HttpEntity<>(platformJson(token)));
        assertEquals(0, code(logs));
        List<?> entries = (List<?>) logs.get("data");
        assertEquals(7, entries.size(), "全程开通记录：" + entries);
        assertEquals(
                4L,
                entries.stream().filter(e -> "OPEN".equals(((Map<?, ?>) e).get("action"))).count());
        assertEquals(
                3L,
                entries.stream()
                        .filter(e -> "CLOSE".equals(((Map<?, ?>) e).get("action")))
                        .count());
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(
                    ((Number) ((Map<?, ?>) entries.get(i)).get("id")).longValue()
                            < ((Number) ((Map<?, ?>) entries.get(i - 1)).get("id")).longValue(),
                    "开通记录倒序（最新在前）");
        }

        // 状态审计链：人工转正、到期自动宽限、宽限满自动停用各一条；续期写 TENANT_EXPIRE
        List<String> statusAudits =
                platform.queryForList(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_STATUS'"
                                + " AND target = ? ORDER BY id",
                        String.class,
                        "plt_tenant:" + code);
        assertEquals(3, statusAudits.size(), String.join(" | ", statusAudits));
        assertTrue(statusAudits.get(0).contains("TRIAL→ENABLED"), statusAudits.get(0));
        assertTrue(statusAudits.get(1).contains("ENABLED→GRACE"), statusAudits.get(1));
        assertTrue(statusAudits.get(1).contains("服务到期自动"), statusAudits.get(1));
        assertTrue(statusAudits.get(2).contains("GRACE→DISABLED"), statusAudits.get(2));
        assertTrue(statusAudits.get(2).contains("宽限期结束自动"), statusAudits.get(2));
        String expireAudit =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_EXPIRE'"
                                + " AND target = ?",
                        String.class,
                        "plt_tenant:" + code);
        assertTrue(expireAudit.contains("DISABLED→ENABLED"), expireAudit);
    }

    /**
     * 模块开通的负向矩阵：向导勾选下架模块 40901（PLT-MOD-004）；开通中 / 注销中 / 已注销租户不能开通模块、开通中不能人工流转状态； 未知模块与未知租户
     * 40400（不暴露存在性口径与资源 40400 一致）。
     */
    @Test
    @Order(2)
    void offShelfModuleAndUnavailableTenantStatesRejectProvisioning() {
        connect();
        String token = loginPlatform();

        // 向导勾选下架模块 → 40901；租户行已建（进度可回收），登记清理
        Map<?, ?> catalog =
                body(
                        HttpMethod.GET,
                        "/api/platform/modules",
                        new HttpEntity<>(platformJson(token)));
        assertEquals(0, code(catalog));
        String followupName =
                ((List<Map<String, Object>>) catalog.get("data"))
                        .stream()
                                .filter(m -> "followup".equals(m.get("code")))
                                .map(m -> String.valueOf(m.get("name")))
                                .findFirst()
                                .orElseThrow();
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/followup",
                                new HttpEntity<>(
                                        Map.of("name", followupName, "status", "OFF"),
                                        platformJson(token)))),
                "先下架 followup");
        String wizardCode = "tcpx" + Long.toString(System.currentTimeMillis(), 36).toLowerCase();
        Map<?, ?> offShelf =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(
                                Map.of(
                                        "name",
                                        "虚构示例医院辰",
                                        "code",
                                        wizardCode,
                                        "modules",
                                        List.of("followup")),
                                platformJson(token)));
        assertEquals(40901, code(offShelf), () -> String.valueOf(offShelf));
        assertTrue(
                String.valueOf(offShelf.get("message")).contains("已下架"),
                offShelf.get("message").toString());
        createdCodes.add(wizardCode);
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/followup",
                                new HttpEntity<>(
                                        Map.of("name", followupName, "status", "ON"),
                                        platformJson(token)))),
                "恢复上架，不影响后续用例");

        // 不可用租户状态矩阵：先把 followup 关掉，保证每次都是「未开通 → 尝试开通」
        long tenantId = createWizardTenant("虚构示例医院巳", Map.of("modules", List.of("followup")), null);
        assertEquals(0, code(closeModule(token, tenantId, "状态矩阵准备（示例）")));
        for (String status : List.of("PROVISIONING", "CANCELLING", "CANCELLED")) {
            platform.update("UPDATE plt_tenant SET status = ? WHERE id = ?", status, tenantId);
            Map<?, ?> rejected = openModule(token, tenantId, null);
            assertEquals(40901, code(rejected), status + "：" + rejected);
            assertTrue(
                    String.valueOf(rejected.get("message")).contains(status),
                    "文案应说明当前状态：" + rejected.get("message"));
            assertTrue(
                    String.valueOf(rejected.get("message")).contains("不能开通模块"),
                    rejected.get("message").toString());
        }
        assertEquals("CLOSED", moduleStatusInDb(tenantId, "followup"), "被拒的开通不得改状态");

        // 开通中的租户不能人工流转状态（HTTP 级；状态图纯函数已单测覆盖）
        platform.update("UPDATE plt_tenant SET status = 'PROVISIONING' WHERE id = ?", tenantId);
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "ENABLE", "reason", "开通中（示例）"),
                                        platformJson(token)))));

        // 未知资源：模块 40400、租户 40400
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/modules/nosuchmod/open",
                                new HttpEntity<>(Map.of(), platformJson(token)))));
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/999999/modules/followup/open",
                                new HttpEntity<>(Map.of(), platformJson(token)))));
    }
}
