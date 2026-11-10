package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
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
 * E23-T09 UAT 自查缺陷 UAT-SELF-04 核对（端到端 / 真实 MySQL）：种子 V134 落库后租户管理员能打开通知设置。
 *
 * <p>缺陷（G-3 / A-NTF-001，P1）：全新租户库**没有任何角色**持有「通知设置」菜单（plt_menu 36，
 * `followup:notice:config`）——followup V22 把它授予 TENANT_ADMIN，但全新库的种子 V100（创建该角色）按版本号 排序在 V22
 * 之后执行，授予命中 0 行（与 base V7 / V10 落空、种子 V102 / V105 兜底同类）。自查实测：任何角色 （含租户管理员 nurse.demo）`GET
 * /api/hospital/notify/rules` 40300，逐事件规则配置页完全不可用。
 *
 * <p>核对（迁移后库侧行为，预置行只读不动）：
 *
 * <ol>
 *   <li>内置 TENANT_ADMIN 持有菜单 36（sys_role_menu 行在位；demob 同口径，双租户各自行）；
 *   <li>租户管理员调用规则清单接口 200 且目录全集 10 项（A 规格 §7 事件目录；C 首期无窗口预告由停用实现，不分支）；
 *   <li>G-3 的默认口径锚点：窗口预告提前 7 天、即将逾期提前 2 天、已逾期站内 + 短信可抄送病区负责人 （`docs/testing/uat-hospital-a.md` §5
 *       G-3 预期「十个事件默认规则与 A 规格 §7 一致」）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。全部虚构数据，无新增业务行。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoticeMenuGrantVerifyIT {

    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Test
    void tenantAdminHoldsNoticeMenuAfterSeedV134() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            long tenantId = "demob".equals(tenant) ? 2L : 1L;
            TenantContext.runAsTenant(
                    tenantId,
                    tenant,
                    () -> {
                        Integer granted =
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r"
                                                + " ON r.id = rm.role_id"
                                                + " WHERE r.code = 'TENANT_ADMIN' AND r.built_in = 1"
                                                + " AND rm.menu_id = 36",
                                        Integer.class);
                        assertTrue(
                                granted != null && granted >= 1,
                                tenant + "：内置租户管理员应持有「通知设置」菜单（V134，UAT-SELF-04）");
                    });
        }
    }

    @Test
    void tenantAdminCanReadNoticeRulesWithSpecDefaults() {
        String token = hospitalToken("demoa", "nurse.demo", PASSWORD);

        Map<?, ?> rules = get("/api/hospital/notify/rules", token);
        assertEquals(0, rules.get("code"), "修复前任何角色（含租户管理员）都被 40300 拒绝（UAT-SELF-04）：" + rules);
        List<Map<?, ?>> items = (List<Map<?, ?>>) rules.get("data");
        assertEquals(10, items.size(), "事件目录全集 10 项（A 规格 §7，G-3 预期）");

        Map<?, ?> preview = itemOf(items, "WINDOW_PREVIEW");
        assertEquals(7, ((Number) preview.get("advanceDays")).intValue(), "窗口预告默认提前 7 天");
        Map<?, ?> dueSoon = itemOf(items, "DUE_SOON");
        assertEquals(2, ((Number) dueSoon.get("advanceDays")).intValue(), "即将逾期默认提前 2 天");
        Map<?, ?> overdue = itemOf(items, "TASK_OVERDUE");
        assertTrue(
                ((List<?>) overdue.get("channels")).containsAll(List.of("INBOX", "SMS")),
                "已逾期默认站内 + 短信");
        assertTrue(
                ((List<?>) overdue.get("recipients")).contains("CC_WARD_LEADER"), "已逾期默认可抄送病区负责人");
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<?, ?> itemOf(List<Map<?, ?>> items, String eventType) {
        return items.stream()
                .filter(item -> eventType.equals(item.get("eventType")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("事件目录缺少 " + eventType));
    }

    private String hospitalToken(String tenant, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> response =
                rest.exchange(
                                "/api/hospital/auth/login",
                                HttpMethod.POST,
                                new HttpEntity<>(
                                        Map.of(
                                                "tenantCode",
                                                tenant,
                                                "username",
                                                username,
                                                "password",
                                                password),
                                        headers),
                                Map.class)
                        .getBody();
        return String.valueOf(((Map<?, ?>) response.get("data")).get("token"));
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }
}
