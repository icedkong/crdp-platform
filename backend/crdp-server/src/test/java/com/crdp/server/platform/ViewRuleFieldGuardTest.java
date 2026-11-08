package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.server.platform.ViewRuleResolver.Rule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E20-T03 / PLT-VIEW-010、011（验收：前端不接收原值、页面显示已设盲）：视角效果落到值—— HIDDEN → null、BLINDED → 「已设盲」、MASKED /
 * PLAIN → 保留传入（已脱敏）值（视角不放宽列表明文）；非视角会话一律原样（医院端行为不变）。
 */
class ViewRuleFieldGuardTest {

    private static SessionPrincipal session(String type) {
        return new SessionPrincipal(
                "token",
                type,
                7L,
                "demoa",
                "虚构示例医院甲",
                1L,
                "platform.admin",
                "平台管理员（示例）",
                List.of("PLATFORM_ADMIN"),
                List.of(),
                List.of(),
                List.of(),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private static ViewRuleFieldGuard guardOf(List<Rule> rules) {
        return new ViewRuleFieldGuard(new TenantViewRuleExecutor(() -> rules, new JdbcTemplate()));
    }

    private static Rule rule(long id, String resource, String field, String visibility) {
        return new Rule(
                id,
                "VR-" + id,
                "规则" + id,
                "FIELD",
                null,
                field == null ? null : "followup",
                resource,
                field,
                Set.of(),
                visibility,
                null,
                "READONLY",
                false,
                "ACTIVE");
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                session("TENANT_VIEW"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void maskedKeepsMaskedValue() {
        ViewRuleFieldGuard guard = guardOf(List.of(rule(1, "patient", "name", "MASKED")));
        assertEquals("张*明", guard.apply("patient", "name", "张*明"), "脱敏规则保留（调用方传入的）脱敏值——前端不接收原值");
        assertFalse(guard.hidden("patient", "name"));
    }

    @Test
    void blindedReplacesWithPlaceholder() {
        ViewRuleFieldGuard guard = guardOf(List.of(rule(2, "patient", "name", "BLINDED")));
        assertEquals(
                TenantViewFieldGuard.BLINDED_TEXT,
                guard.apply("patient", "name", "张*明"),
                "设盲字段显示「已设盲」（PLT-VIEW-011）");
        assertTrue(guard.hidden("patient", "name"), "结构字段（录音清单等）的设盲等同隐藏");
    }

    @Test
    void hiddenRemovesValue() {
        ViewRuleFieldGuard guard = guardOf(List.of(rule(3, "report", null, "HIDDEN")));
        assertNull(guard.apply("report", null, "whatever"), "隐藏字段不返回原值");
        assertTrue(guard.hidden("report", null), "报告单原件 / 附件对视角置空");
    }

    @Test
    void plainDoesNotLoosenListMasking() {
        ViewRuleFieldGuard guard = guardOf(List.of(rule(4, "patient", "name", "PLAIN")));
        assertEquals(
                "张*明",
                guard.apply("patient", "name", "张*明"),
                "PLAIN 不放宽列表明文（AGENTS.md §5.3；明文仅专用接口且视角禁用）");
    }

    @Test
    void hospitalSessionPassesThrough() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                session("HOSPITAL"), null, List.of()));
        ViewRuleFieldGuard guard = guardOf(List.of(rule(5, "patient", "name", "HIDDEN")));
        assertEquals("张*明", guard.apply("patient", "name", "张*明"), "医院会话不经视角执行点");
        assertFalse(guard.hidden("patient", "name"));
    }
}
