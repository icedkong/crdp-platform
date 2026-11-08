package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.crdp.server.platform.ViewRuleResolver.Rule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E20-T03 / PLT-VIEW-010、011：执行引擎与效果预览共用 {@link ViewRuleResolver}（E20-T02 验收「预览与实际执行 一致」）——同一批
 * ACTIVE 规则喂进 {@code fieldEffect} 的效果即预览值；权限码切分（模块 / 资源 / 动作）供执行过滤器分类。
 */
class TenantViewRuleExecutorTest {

    /** 视角会话（TENANT_VIEW）：解析目标 = 平台角色 + 被查看租户。 */
    private static SessionPrincipal viewSession(List<String> roles) {
        return new SessionPrincipal(
                "view-token",
                "TENANT_VIEW",
                7L,
                "demoa",
                "虚构示例医院甲",
                1L,
                "platform.admin",
                "平台管理员（示例）",
                roles,
                List.of(),
                List.of(),
                List.of(),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private static Rule rule(
            long id,
            String layer,
            Long tenantId,
            String module,
            String resource,
            String field,
            Set<String> roleCodes,
            String visibility,
            String maskRule) {
        return new Rule(
                id,
                "VR-" + id,
                "规则" + id,
                layer,
                tenantId,
                module,
                resource,
                field,
                roleCodes,
                visibility,
                maskRule,
                "READONLY",
                false,
                "ACTIVE");
    }

    private TenantViewRuleExecutor executorOf(List<Rule> rules) {
        return new TenantViewRuleExecutor(() -> rules, new JdbcTemplate());
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                viewSession(List.of("PLATFORM_ADMIN")), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void maskedFieldKeepsMaskedValue() {
        // V109 默认：患者姓名脱敏（FIELD 层，maskRule=name）
        TenantViewRuleExecutor executor =
                executorOf(
                        List.of(
                                rule(
                                        1,
                                        "FIELD",
                                        null,
                                        "followup",
                                        "patient",
                                        "name",
                                        Set.of(),
                                        "MASKED",
                                        "name")));
        Effect effect = executor.fieldEffect("patient", "name");
        assertEquals("MASKED", effect.visibility());
        assertEquals("name", effect.maskRule(), "脱敏码与预览同值（执行即预览）");
    }

    @Test
    void blindedFieldIsBlindForView() {
        // 自定义规则：患者姓名设盲（PLT-VIEW-011 占位口径由 ViewRuleFieldGuard 落值）
        TenantViewRuleExecutor executor =
                executorOf(
                        List.of(
                                rule(
                                        2,
                                        "FIELD",
                                        null,
                                        "followup",
                                        "patient",
                                        "name",
                                        Set.of(),
                                        "BLINDED",
                                        null)));
        assertEquals("BLINDED", executor.fieldEffect("patient", "name").visibility());
    }

    @Test
    void hiddenResourceOriginal() {
        // V109 默认：报告单与电子化资料原件整体隐藏（field 空命中资源任意字段）
        TenantViewRuleExecutor executor =
                executorOf(
                        List.of(
                                rule(
                                        3,
                                        "FIELD",
                                        null,
                                        "followup",
                                        "report",
                                        null,
                                        Set.of(),
                                        "HIDDEN",
                                        null)));
        assertEquals("HIDDEN", executor.fieldEffect("report", null).visibility());
    }

    @Test
    void noRuleFallsBackToInitialDefault() {
        TenantViewRuleExecutor executor = executorOf(List.of());
        Effect effect = executor.fieldEffect("patient", "name");
        assertEquals(ViewRuleCatalog.VISIBILITY_PLAIN, effect.visibility());
        assertEquals(ViewRuleCatalog.OPERATION_READONLY, effect.operation());
        assertFalse(effect.allowExport(), "无命中回落初始默认：可见、只读、禁止导出");
    }

    @Test
    void nonViewSessionGetsNoEffect() {
        SecurityContextHolder.clearContext();
        assertNull(executorOf(List.of()).fieldEffect("patient", "name"), "无视角会话不经过视角执行点（医院端口径自理）");
    }

    @Test
    void moduleScopedRuleOnlyHitsItsModule() {
        // RCT 字段模块留空 = 全部模块（E20-T02 目录先行，rct 上线即命中）
        TenantViewRuleExecutor executor =
                executorOf(
                        List.of(
                                rule(
                                        4,
                                        "FIELD",
                                        null,
                                        null,
                                        "rct",
                                        "grouping",
                                        Set.of(),
                                        "BLINDED",
                                        null)));
        assertEquals("BLINDED", executor.fieldEffect("rct", "grouping").visibility());
    }

    // ---------------------------------------------------------------- 权限码切分

    @Test
    void splitsPermissionCodeSegments() {
        assertEquals("view", TenantViewRuleExecutor.actionOf("followup:patient:view"));
        assertEquals("followup", TenantViewRuleExecutor.moduleOf("followup:patient:view"));
        assertEquals("patient", TenantViewRuleExecutor.resourceOf("followup:patient:view"));
        assertEquals("", TenantViewRuleExecutor.actionOf(null));
        assertNull(TenantViewRuleExecutor.moduleOf(null));
        assertTrue(executorOf(List.of()).moduleOpen(7L, null), "无模块选择器不拦");
    }

    @Test
    void operationEffectResolvesReadonlyByDefault() {
        TenantViewRuleExecutor executor = executorOf(List.of());
        Effect effect = executor.operationEffect(7L, "followup", "patient");
        assertEquals(ViewRuleCatalog.OPERATION_READONLY, effect.operation());
        assertFalse(effect.allowExport());
    }
}
