package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.server.platform.TenantViewService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * E20-T01 端到端（验收原文：只能进入负责租户，超时自动退出）。场景按序：
 *
 * <ol>
 *   <li>进入与台账（PLT-VIEW-001）：管理员选目的 + 说明进入 demoa 视角，plt_view_session 落 ACTIVE 行、 进入写平台访问审计
 *       VIEW_ENTER（aud_platform_access_log）；参数校验（目的枚举 / 说明必填 / 未知租户 40400）；
 *       同一账号同时只能有一个视角会话（40901），退出后可重新进入。
 *   <li>一键退出（PLT-VIEW-003 会话侧）：DELETE 后台账 ENDED / 主动退出，写 VIEW_EXIT 审计。
 *   <li>负责租户（PLT-VIEW-006，验收：只能进入负责租户）：客服支持（RESPONSIBLE）未分配时全部不可进入； 分配 demoa 后租户列表 viewEnterable 仅
 *       demoa 为真、demob 40300；取消负责关系后进行中的会话按「权限变更」立即结束。
 *   <li>指定租户（PLT-VIEW-005）：自定义角色 SPECIFIED + 清单，只能进入清单内租户；缺清单 40001（角色接口侧， 已由
 *       PlatformRoleAccountMySqlIT 覆盖）。
 *   <li>超时收尾（PLT-VIEW-004，验收：超时自动退出）：视角会话冻结 2 小时 / 空闲 15 分钟（单测覆盖机制）； 调度收尾把超过 2 小时的 ACTIVE
 *       台账按「到时」关账并写 VIEW_EXIT 审计。
 *   <li>权限边界：无 platform:view:enter 的角色 40300 + 被拒尝试也写平台访问审计；退出他人会话 40400。
 *   <li>医院端视角外壳（E20-T04 / PLT-VIEW-002、003，验收：视角中页面顶部常驻标识）：进入凭据一次性（60 秒）→ 医院端凭 ticket 换视角令牌（只出现一次）→
 *       me/codes/menus 装壳（TENANT_VIEW、菜单与该院一致、无业务权限码）→ 横幅会话信息 → 一键退出（ENDED / 主动退出 + VIEW_EXIT）；业务接口
 *       40300（fail closed，E20-T03 放开）。
 *   <li>仅配置类（E20-T04 / PLT-VIEW-002）：运维角色菜单裁掉随访业务子树、保留系统管理与配置页；平台 / 视角令牌端别互斥。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（删除本 IT 建的账号、自定义角色、 视角会话行与审计事件）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTenantViewMySqlIT {

    private static final String ADMIN_USERNAME = "platform.admin";
    private static final String ADMIN_PASSWORD = "Demo@2026";
    private static final String NEW_PASSWORD = "View#2026x";

    /** 本 IT 创建的账号 → 清理用。 */
    private static final List<String> createdUsernames = new ArrayList<>();

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TenantViewService viewService;

    private long demoaId;
    private long demobId;

    @BeforeEach
    void cleanState() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
        demoaId =
                platformDirect()
                        .queryForObject(
                                "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");
        demobId =
                platformDirect()
                        .queryForObject(
                                "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demob");
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---------------------------------------------------------------- 1. 进入与台账（PLT-VIEW-001）

    @Test
    @Order(1)
    void adminEntersViewWithPurposeAndNote() {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // 参数校验：目的枚举、说明必填、未知租户 40400（不暴露存在）
        assertEquals(40001, codeOf(enter(demoaId, "AUDIT", "看一眼", token)), "目的只允许四个枚举值");
        assertEquals(40001, codeOf(enter(demoaId, "TROUBLESHOOT", "  ", token)), "说明必填");
        assertEquals(40400, codeOf(enter(999999L, "TROUBLESHOOT", "排障", token)), "未知租户");

        // 进入成功：会话号、2 小时到期、医院端入口
        Map<?, ?> result = dataOf(enter(demoaId, "TROUBLESHOOT", "排查登录失败（示例）", token));
        long sessionId = ((Number) result.get("sessionId")).longValue();
        String sessionNo = (String) result.get("sessionNo");
        assertFalse(sessionNo.isBlank(), "会话号贯穿审计与水印");
        assertEquals(demoaId, ((Number) result.get("tenantId")).longValue());
        assertNotNull(result.get("expiresAt"), "返回最晚结束时间（开始 + 2 小时）");
        assertTrue(
                ((Number) result.get("expiresInSeconds")).longValue() > 0
                        && ((Number) result.get("expiresInSeconds")).longValue() <= 7200,
                "剩余有效期按 2 小时上限：" + result.get("expiresInSeconds"));

        // 台账 ACTIVE + 进入审计（平台访问日志，action=VIEW_ENTER，仅平台库）
        JdbcTemplate platform = platformDirect();
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT status, purpose, note, account_name FROM plt_view_session WHERE"
                                + " session_no = ?",
                        sessionNo);
        assertEquals("ACTIVE", row.get("status"));
        assertEquals("TROUBLESHOOT", row.get("purpose"));
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action = 'VIEW_ENTER'"
                                + " AND view_session_id = ? AND result = 'SUCCESS'",
                        Integer.class,
                        sessionNo),
                "进入写平台访问审计 VIEW_ENTER");

        // 本人进行中的会话（倒计时口径）
        Map<?, ?> current = dataOf(get("/api/platform/tenant-view/sessions/current", token));
        assertEquals(sessionNo, current.get("sessionNo"));
        assertTrue(((Number) current.get("remainingSeconds")).longValue() > 0);

        // 同一账号同时只能有一个视角会话；切换租户（或重复进入）须先退出
        assertEquals(40901, codeOf(enter(demoaId, "OTHER", "再来一次（示例）", token)));
        assertEquals(40901, codeOf(enter(demobId, "OTHER", "换一家（示例）", token)));

        // 一键退出：台账 ENDED / 主动退出，写 VIEW_EXIT 审计；到时可重新进入
        Map<?, ?> exit = delete("/api/platform/tenant-view/sessions/" + sessionId, token);
        assertEquals(0, codeOf(exit), String.valueOf(exit.get("message")));
        row =
                platform.queryForMap(
                        "SELECT status, end_method FROM plt_view_session WHERE session_no = ?",
                        sessionNo);
        assertEquals("ENDED", row.get("status"));
        assertEquals("LOGOUT", row.get("end_method"));
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action = 'VIEW_EXIT'"
                                + " AND view_session_id = ? AND result = 'SUCCESS'",
                        Integer.class,
                        sessionNo),
                "退出写平台访问审计 VIEW_EXIT");
        assertNull(
                dataOf(get("/api/platform/tenant-view/sessions/current", token)).get("sessionNo"),
                "退出后无进行中会话");

        Map<?, ?> reentered = dataOf(enter(demoaId, "CONFIG_CHECK", "核对配置（示例）", token));
        assertEquals(
                0,
                codeOf(
                        delete(
                                "/api/platform/tenant-view/sessions/"
                                        + ((Number) reentered.get("sessionId")).longValue(),
                                token)));
    }

    // ---------------------------------------------------------------- 2. 负责租户（PLT-VIEW-006，验收）

    @Test
    @Order(2)
    void responsibleScopeOnlyEntersAssignedTenants() {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long supportRole = roleIdByCode("SUPPORT");
        createAccount(adminToken, "it.view.support", "视角客服（示例）", supportRole);
        createdUsernames.add("it.view.support");
        long supportAccountId = accountIdByUsername("it.view.support");
        String supportToken = loginWithForcedChange("it.view.support");

        // 未分配负责租户：列表全部不可进入、进入 40300、被拒尝试写平台访问审计
        assertEnterable(supportToken, demoaId, false);
        assertEquals(
                40300,
                codeOf(enter(demoaId, "TROUBLESHOOT", "未分配先进入（示例）", supportToken)),
                "未分配负责租户不可进入");
        JdbcTemplate platform = platformDirect();
        assertTrue(
                platform.queryForObject(
                                "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                        + " 'VIEW_ENTER' AND result = 'DENIED' AND user_id = ?",
                                Integer.class,
                                supportAccountId)
                        > 0,
                "被拒的进入尝试也写平台访问审计（PLT-VIEW-016 口径随本任务落进入/退出）");

        // 分配负责租户 demoa（PLT-VIEW-006）：整体替换接口 + 反查负责人
        Map<?, ?> saved =
                put(
                        "/api/platform/accounts/" + supportAccountId + "/responsible-tenants",
                        Map.of("tenantIds", List.of(demoaId)),
                        adminToken);
        assertEquals(0, codeOf(saved), String.valueOf(saved.get("message")));
        List<Map<String, Object>> owners =
                itemsOf(
                        get(
                                "/api/platform/tenants/" + demoaId + "/responsible-owners",
                                adminToken));
        assertTrue(
                owners.stream().anyMatch(owner -> "it.view.support".equals(owner.get("username"))),
                "按租户反查负责人：" + owners);

        // 只能进入负责租户（验收）：demoa 可进、demob 40300（列表 viewEnterable 同口径）
        assertEnterable(supportToken, demoaId, true);
        assertEnterable(supportToken, demobId, false);
        Map<?, ?> entered = dataOf(enter(demoaId, "TROUBLESHOOT", "负责租户排障（示例）", supportToken));
        assertEquals(
                40300,
                codeOf(enter(demobId, "TROUBLESHOOT", "越负责范围（示例）", supportToken)),
                "demob 不在负责租户内");

        // 负责关系变更即时生效：进行中的会话按「权限变更」立即结束（platform-spec §8）
        saved =
                put(
                        "/api/platform/accounts/" + supportAccountId + "/responsible-tenants",
                        Map.of("tenantIds", List.of()),
                        adminToken);
        assertEquals(0, codeOf(saved));
        Map<String, Object> ended =
                platform.queryForMap(
                        "SELECT status, end_method FROM plt_view_session WHERE session_no = ?",
                        entered.get("sessionNo"));
        assertEquals("ENDED", ended.get("status"), "负责关系变更后视角会话立即结束");
        assertEquals("PERM_CHANGE", ended.get("end_method"));
        assertNull(
                dataOf(get("/api/platform/tenant-view/sessions/current", supportToken))
                        .get("sessionNo"),
                "会话查询不再返回进行中视角");

        // 未知租户不能设为负责租户
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/platform/accounts/"
                                        + supportAccountId
                                        + "/responsible-tenants",
                                Map.of("tenantIds", List.of(999999L)),
                                adminToken)));
    }

    // ---------------------------------------------------------------- 3. 指定租户（PLT-VIEW-005）

    @Test
    @Order(3)
    void specifiedScopeOnlyEntersListedTenants() {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<?, ?> created =
                post(
                        Map.of(
                                "name",
                                "视角指定租户角色（示例）",
                                "menuIds",
                                List.of(100L, 111L, 143L),
                                "viewCanEnter",
                                true,
                                "viewVisibleScope",
                                "CONFIG_ONLY",
                                "viewTenantScope",
                                "SPECIFIED",
                                "viewSpecifiedTenantIds",
                                List.of(demobId)),
                        "/api/platform/roles",
                        adminToken);
        assertEquals(0, codeOf(created), String.valueOf(created.get("message")));
        long roleId = ((Number) dataOf(created).get("id")).longValue();

        createAccount(adminToken, "it.view.spec", "指定租户账号（示例）", roleId);
        createdUsernames.add("it.view.spec");
        String specToken = loginWithForcedChange("it.view.spec");

        // 只能进入清单内租户：demob 可进、demoa 40300
        assertEnterable(specToken, demobId, true);
        assertEnterable(specToken, demoaId, false);
        Map<?, ?> entered = dataOf(enter(demobId, "CONFIG_CHECK", "核对配置（示例）", specToken));
        assertEquals(demobId, ((Number) entered.get("tenantId")).longValue());
        assertEquals(40300, codeOf(enter(demoaId, "CONFIG_CHECK", "清单外（示例）", specToken)));
        assertEquals(
                0,
                codeOf(
                        delete(
                                "/api/platform/tenant-view/sessions/"
                                        + ((Number) entered.get("sessionId")).longValue(),
                                specToken)));

        // 角色视角授权变更（清单去掉 demob）→ 持有者进行中的会话立即结束：先再进一次再改
        entered = dataOf(enter(demobId, "OTHER", "等会再退（示例）", specToken));
        Map<?, ?> updated =
                put(
                        "/api/platform/roles/" + roleId,
                        Map.of(
                                "name",
                                "视角指定租户角色（示例）",
                                "menuIds",
                                List.of(100L, 111L, 143L),
                                "viewCanEnter",
                                true,
                                "viewVisibleScope",
                                "CONFIG_ONLY",
                                "viewTenantScope",
                                "SPECIFIED",
                                "viewSpecifiedTenantIds",
                                List.of(demoaId)),
                        adminToken);
        assertEquals(0, codeOf(updated), String.valueOf(updated.get("message")));
        Map<String, Object> ended =
                platformDirect()
                        .queryForMap(
                                "SELECT status, end_method FROM plt_view_session WHERE session_no"
                                        + " = ?",
                                entered.get("sessionNo"));
        assertEquals("ENDED", ended.get("status"), "视角授权变更后进行中的会话立即结束");
        assertEquals("PERM_CHANGE", ended.get("end_method"));
    }

    // ---------------------------------------------------------------- 4. 超时收尾（PLT-VIEW-004，验收）

    @Test
    @Order(4)
    void timeoutFinalizationClosesStaleSessions() {
        // 直接造一条 3 小时前开始、1 小时前到时的 ACTIVE 台账（令牌早已被 Redis TTL 逐出）
        JdbcTemplate platform = platformDirect();
        long adminId = accountIdByUsername(ADMIN_USERNAME);
        // started_at_epoch 按库内 UNIX_TIMESTAMP 构造（OQ-56 ⑥：CI 容器钟面为 UTC，epoch 判定不受影响）
        platform.update(
                "INSERT INTO plt_view_session (session_no, account_id, account_name, display_name,"
                        + " role_codes, tenant_id, purpose, note, started_at_epoch, status,"
                        + " started_at, ends_at)"
                        + " VALUES ('itteststalesession', ?, 'platform.admin', '平台管理员（示例）',"
                        + " 'PLATFORM_ADMIN', ?, 'TROUBLESHOOT', '超时收尾用例（示例）',"
                        + " UNIX_TIMESTAMP() - 10800, 'ACTIVE',"
                        + " DATE_SUB(NOW(3), INTERVAL 3 HOUR), DATE_SUB(NOW(3), INTERVAL 1 HOUR))",
                adminId,
                demoaId);

        viewService.finalizeExpiredSessions();

        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT status, end_method, end_at, ends_at FROM plt_view_session WHERE"
                                + " session_no = 'itteststalesession'");
        assertEquals("ENDED", row.get("status"), "2 小时到时的会话由调度收尾（超时自动退出的台账侧）");
        assertEquals("TIMEOUT", row.get("end_method"));
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action = 'VIEW_EXIT'"
                                + " AND view_session_id = 'itteststalesession'",
                        Integer.class),
                "收尾写 VIEW_EXIT 审计");
    }

    // ---------------------------------------------------------------- 5. 权限边界

    @Test
    @Order(5)
    void permissionBoundaries() {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        // 运维人员（V104 后 OPERATIONS 也有 platform:view:enter）→ 用一个无视角权限的自定义角色验证 40300
        Map<?, ?> created =
                post(
                        Map.of(
                                "name",
                                "无视角角色（示例）",
                                "menuIds",
                                List.of(100L, 111L, 143L),
                                "viewCanEnter",
                                false),
                        "/api/platform/roles",
                        adminToken);
        assertEquals(0, codeOf(created), String.valueOf(created.get("message")));
        long roleId = ((Number) dataOf(created).get("id")).longValue();
        createAccount(adminToken, "it.view.noentry", "无视角账号（示例）", roleId);
        createdUsernames.add("it.view.noentry");
        String noEntryToken = loginWithForcedChange("it.view.noentry");

        assertEquals(
                40300,
                codeOf(enter(demoaId, "TROUBLESHOOT", "无视角权限（示例）", noEntryToken)),
                "view_can_enter=0 的角色不可进入");
        assertEnterable(noEntryToken, demoaId, false);

        // 退出他人 / 不存在的会话 40400（不暴露存在）
        assertEquals(
                40400, codeOf(delete("/api/platform/tenant-view/sessions/999999", adminToken)));
        // 未登录 40100
        assertEquals(
                40100,
                codeOf(
                        rest.exchange(
                                        "/api/platform/tenant-view/sessions/current",
                                        HttpMethod.GET,
                                        new HttpEntity<>(new HttpHeaders()),
                                        Map.class)
                                .getBody()));
    }

    // ---------------------------------------------------------------- 6. 医院端视角外壳（E20-T04 /
    // PLT-VIEW-002、003，验收：视角中页面顶部常驻标识）

    @Test
    @Order(6)
    void hospitalViewShellWithConsistentMenusAndExit() {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        dataOf(enter(demoaId, "TROUBLESHOOT", "排查菜单一致性（示例）", adminToken));

        // 进入凭据（60 秒一次性）：平台端「打开医院端界面」时签发，响应带医院端入口
        Map<?, ?> ticket =
                dataOf(
                        post(
                                Map.of(),
                                "/api/platform/tenant-view/sessions/current/entry-ticket",
                                adminToken));
        assertTrue(
                ((Number) ticket.get("expiresInSeconds")).longValue() > 0
                        && ((Number) ticket.get("expiresInSeconds")).longValue() <= 60,
                "进入凭据 60 秒一次性");
        assertEquals("http://127.0.0.1:14173", ticket.get("hospitalUrl"));

        // 凭 ticket 换视角令牌（公开口）：令牌只在本次响应出现一次；响应带横幅口径
        // （会话号 / 租户 / 剩余时长 / 平台账号，PLT-VIEW-003 横幅与水印同源）
        Map<?, ?> exchanged =
                dataOf(
                        post(
                                Map.of("ticket", ticket.get("ticket")),
                                "/api/hospital/view/enter",
                                null));
        String viewToken = (String) exchanged.get("token");
        assertFalse(viewToken.isBlank(), "视角令牌只在换取响应出现一次");
        assertEquals("Bearer", exchanged.get("tokenType"));
        Map<?, ?> shell = (Map<?, ?>) exchanged.get("session");
        Map<?, ?> current = dataOf(get("/api/platform/tenant-view/sessions/current", adminToken));
        assertEquals(current.get("sessionNo"), shell.get("sessionNo"), "与平台侧同一会话");
        assertEquals("demoa", shell.get("tenantCode"));
        assertTrue(((Number) shell.get("remainingSeconds")).longValue() > 0, "剩余时长（横幅倒计时）");
        assertEquals(ADMIN_USERNAME, shell.get("accountName"), "水印含平台账号（PLT-VIEW-003）");

        // ticket 一次性 + 伪造凭据：统一 40001（不暴露差异，防探测）
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of("ticket", ticket.get("ticket")),
                                "/api/hospital/view/enter",
                                null)),
                "进入凭据取出即作废");
        assertEquals(
                40001,
                codeOf(post(Map.of("ticket", "forged-ticket"), "/api/hospital/view/enter", null)),
                "伪造凭据同样 40001");

        // 装壳接口接受视角令牌（PLT-VIEW-002）：me 会话类型 TENANT_VIEW、租户为被查看医院、平台账号自身身份
        Map<?, ?> me = dataOf(get("/api/hospital/auth/me", viewToken));
        assertEquals("TENANT_VIEW", me.get("sessionType"));
        assertEquals("demoa", me.get("tenantCode"));
        assertEquals(ADMIN_USERNAME, me.get("username"), "不借用任何医院用户身份");
        assertTrue(
                itemsOf(get("/api/hospital/auth/codes", viewToken)).isEmpty(),
                "视角令牌不带业务权限码（fail closed，E20-T03 执行点放开）");

        // 菜单与该院一致（PLT-VIEW-002）：管理员可见范围=配置类+业务数据 → 随访目录与系统管理都在
        Set<String> paths = menuPaths(menuTree(viewToken), new HashSet<>());
        assertTrue(paths.contains("/followup") && paths.contains("/followup/patient"), "业务菜单可见");
        assertTrue(paths.contains("/system") && paths.contains("/system/user"), "配置菜单可见");
        assertTrue(paths.contains("/dashboard"), "与该院一致的工作台");

        // 横幅会话信息（PLT-VIEW-003）：会话号 / 租户 / 剩余时长；页面水印同口径
        Map<?, ?> session = dataOf(get("/api/hospital/view/session", viewToken));
        assertEquals(shell.get("sessionNo"), session.get("sessionNo"));
        assertEquals("虚构示例医院甲", session.get("tenantName"));
        assertEquals("TROUBLESHOOT", session.get("purpose"));

        // 业务接口保持默认拒绝（视角令牌无医院会话端别权限，E20-T03 执行点放开前 fail closed）
        assertEquals(
                40300,
                codeOf(get("/api/hospital/system/users?pageNo=1&pageSize=20", viewToken)),
                "视角令牌不能调医院端业务接口");

        // 一键退出（医院端横幅按钮）：台账 ENDED / 主动退出 + VIEW_EXIT 审计；令牌随即失效
        Map<?, ?> exit = delete("/api/hospital/view/session", viewToken);
        assertEquals(0, codeOf(exit), String.valueOf(exit.get("message")));
        Map<String, Object> ended =
                platformDirect()
                        .queryForMap(
                                "SELECT status, end_method FROM plt_view_session WHERE session_no = ?",
                                shell.get("sessionNo"));
        assertEquals("ENDED", ended.get("status"));
        assertEquals("LOGOUT", ended.get("end_method"));
        assertEquals(40100, codeOf(get("/api/hospital/view/session", viewToken)), "退出后视角令牌失效");
    }

    // ---------------------------------------------------------------- 7. 仅配置类裁剪与令牌边界（E20-T04 /
    // PLT-VIEW-002）

    @Test
    @Order(7)
    void configOnlyRoleHidesBusinessMenus() {
        // 仅配置类可见范围：自建角色（CONFIG_ONLY、租户范围全部）——不依赖预置角色的
        // 授权快照（PlatformRoleAccountMySqlIT 会调整运维角色的菜单授予），菜单带平台
        // 工作台与视角进入按钮 143（种子 V107 口径）
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Map<?, ?> created =
                post(
                        Map.of(
                                "name",
                                "视角仅配置类角色（示例）",
                                "menuIds",
                                List.of(100L, 143L),
                                "viewCanEnter",
                                true,
                                "viewVisibleScope",
                                "CONFIG_ONLY",
                                "viewTenantScope",
                                "ALL"),
                        "/api/platform/roles",
                        adminToken);
        assertEquals(0, codeOf(created), String.valueOf(created.get("message")));
        long roleId = ((Number) dataOf(created).get("id")).longValue();
        createAccount(adminToken, "it.view.ops", "运维视角账号（示例）", roleId);
        createdUsernames.add("it.view.ops");
        String opsToken = loginWithForcedChange("it.view.ops");

        Map<?, ?> entered = dataOf(enter(demoaId, "CONFIG_CHECK", "核对租户配置（示例）", opsToken));
        assertEquals(demoaId, ((Number) entered.get("tenantId")).longValue(), "进入成功");
        Map<?, ?> ticket =
                dataOf(
                        post(
                                Map.of(),
                                "/api/platform/tenant-view/sessions/current/entry-ticket",
                                opsToken));
        Map<?, ?> exchanged =
                dataOf(
                        post(
                                Map.of("ticket", ticket.get("ticket")),
                                "/api/hospital/view/enter",
                                null));
        String viewToken = (String) exchanged.get("token");

        // 仅配置类：随访业务子树（目录与页面）整体隐藏，系统管理与配置页（字段字典）保留
        Set<String> paths = menuPaths(menuTree(viewToken), new HashSet<>());
        assertFalse(paths.contains("/followup") || paths.contains("/followup/patient"), "业务目录隐藏");
        assertTrue(paths.contains("/system") && paths.contains("/system/user"), "配置类保留");
        assertTrue(paths.contains("/system/field"), "module_code=followup 的配置页（字段字典）保留");
        assertTrue(paths.contains("/dashboard"), "工作台属装壳页，两种可见范围都在");

        // 端别权限互斥：平台令牌不可调医院端视角接口；视角令牌不可调平台端接口
        assertEquals(40300, codeOf(get("/api/hospital/view/session", opsToken)), "平台令牌不可调医院端视角接口");
        assertEquals(
                40300,
                codeOf(get("/api/platform/tenant-view/sessions/current", viewToken)),
                "视角令牌不可调平台端接口");
        assertEquals(0, codeOf(delete("/api/hospital/view/session", viewToken)), "收尾退出");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> menuTree(String viewToken) {
        return itemsOf(get("/api/hospital/menus", viewToken));
    }

    private static Set<String> menuPaths(List<Map<String, Object>> nodes, Set<String> collected) {
        for (Map<String, Object> node : nodes) {
            collected.add((String) node.get("path"));
            Object children = node.get("children");
            if (children instanceof List<?> list && !list.isEmpty()) {
                menuPaths((List<Map<String, Object>>) list, collected);
            }
        }
        return collected;
    }

    // ---------------------------------------------------------------- 辅助

    /** 断言租户列表 viewEnterable 与进入接口同口径（PLT-VIEW-001）。 */
    private void assertEnterable(String token, long tenantId, boolean expected) {
        Map<?, ?> page = dataOf(get("/api/platform/tenants?pageSize=100", token));
        assertNotNull(page.get("items"), "租户列表分页结构");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        Map<?, ?> row =
                items.stream()
                        .filter(item -> ((Number) item.get("id")).longValue() == tenantId)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("租户 " + tenantId + " 不在列表"));
        assertEquals(expected, row.get("viewEnterable"), "租户 " + tenantId + " 的 viewEnterable");
    }

    private Map<?, ?> enter(long tenantId, String purpose, String note, String token) {
        return post(
                Map.of("tenantId", tenantId, "purpose", purpose, "note", note),
                "/api/platform/tenant-view/sessions",
                token);
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

    /** 新建账号的首次登录：改密后本会话恢复可用（E04-T05 口径）。 */
    private String loginWithForcedChange(String username) {
        Map<?, ?> first =
                post(
                        Map.of("username", username, "password", NEW_PASSWORD + "A"),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(first), String.valueOf(first.get("message")));
        String token = (String) dataOf(first).get("token");
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
                                "139000088" + (10 + createdUsernames.size() % 80),
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(roleId)),
                        "/api/platform/accounts",
                        adminToken);
        assertEquals(0, codeOf(response), "建号成功：" + String.valueOf(response.get("message")));
        return ((Number) dataOf(response).get("id")).longValue();
    }

    /** 平台库直连（与 PlatformRoleAccountMySqlIT 同款）。 */
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

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<?, ?> response) {
        return response.get("data") == null
                ? new HashMap<>()
                : (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> itemsOf(Map<?, ?> response) {
        return (List<T>) response.get("data");
    }

    /** 自建自清：删除本 IT 的账号、自定义角色、视角会话行、负责关系与审计事件。 */
    @AfterAll
    static void cleanUp() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        platform.update(
                "DELETE FROM plt_view_session WHERE account_name = 'platform.admin'"
                        + " OR account_id IN (SELECT id FROM plt_account WHERE username LIKE"
                        + " 'it.view.%')");
        platform.update(
                "DELETE FROM plt_account_tenant WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.view.%')");
        platform.update(
                "DELETE FROM plt_password_history WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.view.%')");
        platform.update(
                "DELETE FROM plt_account_role WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.view.%')");
        platform.update("DELETE FROM plt_account WHERE username LIKE 'it.view.%'");
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
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE action IN ('VIEW_ENTER', 'VIEW_EXIT')");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('ACCOUNT_CREATE', 'ACCOUNT_UPDATE', 'ACCOUNT_TENANT_UPDATE',"
                        + " 'ROLE_CREATE', 'ROLE_UPDATE')");
    }
}
