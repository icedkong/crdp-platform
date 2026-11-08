package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E19-T06 端到端（tasks.csv 验收「按角色配置后即生效」，A-DASH-013 / A-DASH-014）。全部走 HTTP + 真库（角色 / 布局 / 偏好行落 t_demoa
 * / t_demob，权限码 followup:dashboard:config 由平台库 V60 建菜单、 种子 V130 授 TENANT_ADMIN）：
 *
 * <ul>
 *   <li>验收（A 规格 §9）：租户管理员为「随访员」角色（A_FOLLOWUP_NURSE，V123 预置）只勾选任务区与指标卡、 任务区排第一 → 随访员登录 GET
 *       /dashboard/layout 只见 [task_board, kpi]，任务区在上；科主任 （A_DIRECTOR）未配置不受影响（默认布局，不含节点随访率）；重新配置后下一次
 *       GET 即变化（无缓存）；
 *   <li>目录 ∩ 开关：node_rate 配置可见且 node_follow_rate 开 → 出现；开关关闭 → 即使已配置也不出现；
 *   <li>用户隐藏 / 显示（P1）：随访员隐藏趋势图（角色允许）后 visible=false、恢复默认回到角色配置的顺序； 隐藏角色未允许的卡片 40001；
 *   <li>权限与校验：无 followup:dashboard:config 的随访员 PUT role-layouts 40300；未知卡片 / 顺序重复 40001、角色不存在
 *       40400；配置变更写 DASHBOARD_ROLE_LAYOUT 审计；
 *   <li>双租户：demoa 的角色配置不影响 demob（同编码角色独立行）。
 * </ul>
 *
 * <p>「把节点随访率配置给只负责 A 病区的医生后，卡片只统计 A 病区患者」的数据隔离由卡片取数的 @DataScope 出行（DashboardRateCardsIT
 * 已锁定病区收窄与范围外空行集），本测只覆盖目录 / 配置 / 偏好层。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（dash_role_layout / dash_user_pref 按 V123
 * 预置角色编码删行；预置行本身只读不动）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardLayoutIT {

    private static final String PASSWORD = "Demo@2026";

    /** 本用例涉及的 V123 预置角色（cleanState 按编码定位，两侧租户同清）。 */
    private static final String[] ROLE_CODES = {"A_FOLLOWUP_NURSE", "A_DIRECTOR", "A_WARD_DOCTOR"};

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action IN"
                                        + " ('DASHBOARD_ROLE_LAYOUT','DASHBOARD_USER_PREF')");
                        jdbc.update(
                                "DELETE FROM dash_user_pref WHERE user_id IN"
                                        + " (SELECT u.id FROM sys_user u WHERE u.username IN"
                                        + " ('a.cvs1.nur01','a.cvs1.doc01'))");
                        jdbc.update(
                                "DELETE r FROM dash_role_layout r JOIN sys_role ro ON ro.id ="
                                        + " r.role_id WHERE ro.code IN ('A_FOLLOWUP_NURSE',"
                                        + " 'A_DIRECTOR', 'A_WARD_DOCTOR')");
                        // 开关恢复默认（种子 V100：node_follow_rate 开）
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1"
                                        + " WHERE feature_code = 'node_follow_rate'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：按角色配置后即生效

    @Test
    void roleConfigTakesEffectImmediatelyForMembersOnly() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long nurseRole = roleId("demoa", "A_FOLLOWUP_NURSE");

        // 随访员只勾选任务区（看板）与指标卡，任务区排在第一（A 规格 §9 验收原例；未配置的卡片不显示）
        saveRoleLayout(
                admin,
                nurseRole,
                List.of(
                        card("task_board", true, 1),
                        card("kpi", true, 2),
                        card("trend", false, 3)));

        Map<?, ?> nurse = layoutOf("demoa", "a.cvs1.nur01", PASSWORD);
        assertEquals("ROLE_CONFIG", nurse.get("source"));
        assertEquals(List.of("task_board", "kpi"), visibleCodes(nurse), "随访员首页只显示这两张卡片，任务区在上");
        // 重新配置（趋势图放开到最前）→ 下一次 GET 即生效（无缓存，验收「按角色配置后即生效」）
        saveRoleLayout(
                admin,
                nurseRole,
                List.of(card("trend", true, 1), card("task_board", true, 2), card("kpi", true, 3)));
        assertEquals(
                List.of("trend", "task_board", "kpi"),
                visibleCodes(layoutOf("demoa", "a.cvs1.nur01", PASSWORD)),
                "重新配置后立即生效");

        // 科主任（A_DIRECTOR）未配置 → 默认布局不受影响（A 默认不含节点随访率）
        Map<?, ?> director = layoutOf("demoa", "a.cvs1.dir", PASSWORD);
        assertEquals("DEFAULT", director.get("source"));
        assertEquals(
                List.of(
                        "kpi",
                        "trend",
                        "task_board",
                        "task_table",
                        "yearly_rate",
                        "baseline_completeness",
                        "followup_completeness"),
                visibleCodes(director),
                "未配置角色使用默认布局（医院 A 默认不显示节点随访率）");

        // 配置变更写审计（AGENTS §5.4 配置变更）
        assertEquals(
                2,
                countAudit("demoa", "DASHBOARD_ROLE_LAYOUT"),
                "两次保存各写一条 DASHBOARD_ROLE_LAYOUT 审计");
    }

    // ---------------------------------------------------------------- 目录 ∩ 功能开关（不放宽）

    @Test
    void featureSwitchStillGatesCardEvenWhenConfiguredVisible() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long doctorRole = roleId("demoa", "A_WARD_DOCTOR");

        // 把节点随访率配置给（只负责 A 病区心外一的）病区医生：开关开 → 卡片出现（数据隔离由 @DataScope 出行）
        saveRoleLayout(
                admin,
                doctorRole,
                List.of(
                        card("kpi", true, 1),
                        card("node_rate", true, 2),
                        card("yearly_rate", true, 3)));
        List<String> withNode = visibleCodes(layoutOf("demoa", "a.cvs1.doc01", PASSWORD));
        assertTrue(withNode.contains("node_rate"), "配置可见且开关开 → 出现");

        // 开关关闭 → 即使已配置也不出现（AGENTS §5.7 配置不放宽开关）；恢复
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0"
                                        + " WHERE feature_code = 'node_follow_rate'"));
        try {
            List<String> withoutNode = visibleCodes(layoutOf("demoa", "a.cvs1.doc01", PASSWORD));
            assertFalse(withoutNode.contains("node_rate"), "开关关闭 → 卡片不可用");
            // 配置页视图仍列出该卡并标记 featureOn=false（可预置、生效等开关）
            Map<?, ?> view =
                    data(
                            get(
                                    "/api/hospital/followup/dashboard/role-layouts?roleId="
                                            + doctorRole,
                                    admin));
            Map<?, ?> nodeCard =
                    viewCards(view).stream()
                            .filter(card -> "node_rate".equals(card.get("code")))
                            .findFirst()
                            .orElseThrow();
            assertEquals(Boolean.FALSE, nodeCard.get("featureOn"), "配置页标记开关关闭");
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 1"
                                            + " WHERE feature_code = 'node_follow_rate'"));
        }
    }

    // ---------------------------------------------------------------- 用户隐藏 / 显示（A-DASH-014，P1）

    @Test
    void userHidesCardWithinRoleRangeAndRestoreReturnsRoleLayout() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long nurseRole = roleId("demoa", "A_FOLLOWUP_NURSE");
        saveRoleLayout(
                admin,
                nurseRole,
                List.of(card("task_board", true, 1), card("kpi", true, 2), card("trend", true, 3)));

        String token = hospitalToken("demoa", "a.cvs1.nur01", PASSWORD);

        // 隐藏趋势图（角色允许且 allowUserHide）→ visible=false，行保留供「卡片设置」重新显示
        Map<?, ?> hidden = putUserPref(token, List.of("trend"));
        Map<?, ?> trend =
                cardsOf(hidden).stream()
                        .filter(card -> "trend".equals(card.get("code")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(Boolean.FALSE, trend.get("visible"), "用户隐藏后不显示");
        assertEquals(Boolean.TRUE, trend.get("userHidden"));
        assertEquals(List.of("task_board", "kpi"), visibleCodes(hidden));
        assertEquals(1, countAudit("demoa", "DASHBOARD_USER_PREF"), "偏好变更写 DASHBOARD_USER_PREF 审计");

        // 恢复默认（空清单）→ 回到角色配置的卡片与顺序（A 规格 §9 验收「恢复默认后回到角色配置」）
        Map<?, ?> restored = putUserPref(token, List.of());
        assertEquals(List.of("task_board", "kpi", "trend"), visibleCodes(restored));

        // 角色未允许（配置隐藏）的卡片不能隐藏：40001
        saveRoleLayout(
                admin,
                nurseRole,
                List.of(
                        card("task_board", true, 1),
                        card("kpi", true, 2),
                        card("trend", false, 3)));
        assertEquals(
                40001,
                codeOf(
                        putRaw(
                                "/api/hospital/followup/dashboard/user-pref",
                                token,
                                Map.of("hidden", List.of("trend")))),
                "不能隐藏角色未允许的卡片（A-DASH-014）");
    }

    // ---------------------------------------------------------------- 权限 / 校验 / 双租户

    @Test
    void configEndpointsRequirePermissionAndValidatePayload() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long nurseRole = roleId("demoa", "A_FOLLOWUP_NURSE");

        // 随访员（V123 只授业务菜单，无 followup:dashboard:config）→ 40300
        String nurse = hospitalToken("demoa", "a.cvs1.nur01", PASSWORD);
        assertEquals(
                40300,
                codeOf(
                        putRaw(
                                "/api/hospital/followup/dashboard/role-layouts/" + nurseRole,
                                nurse,
                                roleBody(List.of(card("kpi", true, 1))))),
                "无 followup:dashboard:config → 40300");

        // 校验：未知卡片 40001、顺序重复 40001、角色不存在 40400
        assertEquals(
                40001,
                codeOf(
                        putRaw(
                                "/api/hospital/followup/dashboard/role-layouts/" + nurseRole,
                                admin,
                                roleBody(List.of(card("no_such_card", true, 1))))));
        assertEquals(
                40001,
                codeOf(
                        putRaw(
                                "/api/hospital/followup/dashboard/role-layouts/" + nurseRole,
                                admin,
                                roleBody(List.of(card("kpi", true, 1), card("trend", true, 1))))));
        assertEquals(
                40400,
                codeOf(
                        putRaw(
                                "/api/hospital/followup/dashboard/role-layouts/999999",
                                admin,
                                roleBody(List.of(card("kpi", true, 1))))));

        // 恢复默认（DELETE）→ configured=false；角色候选随响应下发（配置页不依赖 tenant:role:view）
        assertEquals(
                0,
                codeOf(
                        delete(
                                "/api/hospital/followup/dashboard/role-layouts/" + nurseRole,
                                admin)));
        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/followup/dashboard/role-layouts?roleId=" + nurseRole,
                                admin));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) view.get("current")).get("configured"));
        assertTrue(((List<?>) view.get("roles")).size() >= 4, "角色候选（TENANT_ADMIN 与 V123 预置角色）");
    }

    @Test
    void roleLayoutIsolatedPerTenant() {
        // demoa 配置随访员角色；demob 同编码角色不受影响（租户库隔离，AGENTS §5.1）
        String adminA = hospitalToken("demoa", "nurse.demo", PASSWORD);
        saveRoleLayout(
                adminA,
                roleId("demoa", "A_FOLLOWUP_NURSE"),
                List.of(card("task_board", true, 1), card("kpi", true, 2)));

        Map<?, ?> nurseB = layoutOf("demob", "a.cvs1.nur01", PASSWORD);
        assertEquals("DEFAULT", nurseB.get("source"), "demob 未配置 → 默认布局");
        assertTrue(visibleCodes(nurseB).contains("trend"), "demob 随访员仍见默认全部卡片");
    }

    // ---------------------------------------------------------------- HTTP 装配

    private Map<?, ?> layoutOf(String tenant, String username, String password) {
        String token = hospitalToken(tenant, username, password);
        return data(get("/api/hospital/followup/dashboard/layout", token));
    }

    private void saveRoleLayout(String admin, long roleId, List<Map<String, Object>> cards) {
        int code =
                codeOf(
                        putRaw(
                                "/api/hospital/followup/dashboard/role-layouts/" + roleId,
                                admin,
                                roleBody(cards)));
        assertEquals(0, code, "保存角色布局失败");
    }

    private static Map<String, Object> roleBody(List<Map<String, Object>> cards) {
        return Map.of("cards", cards);
    }

    private static Map<String, Object> card(String code, boolean visible, int order) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("code", code);
        card.put("visible", visible);
        card.put("order", order);
        return card;
    }

    private Map<?, ?> putUserPref(String token, List<String> hidden) {
        return put("/api/hospital/followup/dashboard/user-pref", token, Map.of("hidden", hidden));
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

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return data(putRawResponse(path, token, body));
    }

    private Map<?, ?> putRaw(String path, String token, Map<String, Object> body) {
        return putRawResponse(path, token, body);
    }

    private Map<?, ?> putRawResponse(String path, String token, Map<String, Object> body) {
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

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static <T> T data(ResponseEntity<Map> response) {
        return (T) data(response.getBody());
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static List<Map<?, ?>> cardsOf(Map<?, ?> layout) {
        return (List<Map<?, ?>>) layout.get("cards");
    }

    private static List<Map<?, ?>> viewCards(Map<?, ?> view) {
        return (List<Map<?, ?>>) ((Map<?, ?>) view.get("current")).get("cards");
    }

    private static List<String> visibleCodes(Map<?, ?> layout) {
        List<String> codes = new ArrayList<>();
        for (Map<?, ?> card : cardsOf(layout)) {
            if (Boolean.TRUE.equals(card.get("visible"))) {
                codes.add(String.valueOf(card.get("code")));
            }
        }
        return codes;
    }

    private long roleId(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = ?", Long.class, code));
    }

    private int countAudit(String tenant, String action) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM aud_log WHERE action = ?",
                                Integer.class,
                                action));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
