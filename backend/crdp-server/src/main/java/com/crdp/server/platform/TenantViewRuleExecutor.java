package com.crdp.server.platform;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.crdp.server.platform.ViewRuleResolver.Rule;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 视角规则的执行引擎（E20-T03 / PLT-VIEW-010、011、012）：效果预览与执行点共用 {@link ViewRuleResolver} 一个实现（E20-T02
 * 验收「预览与实际执行一致」）——本类只负责装载 ACTIVE 规则、以视角会话的 平台角色 + 被查看租户为目标解析效果，任何一侧不自行匹配。
 *
 * <p>规则变更即时生效（platform-spec §8）：每次调用现查 ACTIVE 规则（量级小，平台库单表），
 * 进行中的视角会话下一次请求即按新规则执行；不结束会话（版本戳供审计对账，V50 口径）。
 *
 * <p>模块开通判定（{@link #moduleOpen}）：视角请求按<b>该院实时</b>开通状态判 40301（菜单同口径，
 * E20-T04「页面、模块与租户个性化与该院一致」），不走医院会话快照。
 */
@Service
public class TenantViewRuleExecutor {

    private final Supplier<List<Rule>> rules;

    private final JdbcTemplate platform;

    /** 生产构造：规则装载走 ViewRuleService（ACTIVE 集，平台上下文自设）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TenantViewRuleExecutor(ViewRuleService viewRules, JdbcTemplate platform) {
        this(
                () ->
                        viewRules.activeRules().stream()
                                .map(ViewRuleService.RuleRow::toResolverRule)
                                .toList(),
                platform);
    }

    /** 测试构造：直接喂固定规则集（解析逻辑与生产完全一致）。 */
    TenantViewRuleExecutor(Supplier<List<Rule>> rules, JdbcTemplate platform) {
        this.rules = rules;
        this.platform = platform;
    }

    // ---------------------------------------------------------------- 效果解析（执行点入口）

    /**
     * 字段可见性效果（PLT-VIEW-010、011）：resolve 目标为视角字段目录条目（资源 + 字段， {@code field} 空 =
     * 资源整体规则，如报告单与电子化资料原件）。无视角会话时返回 null（调用方按医院端口径处理，不经过视角执行点）。
     */
    public Effect fieldEffect(String resource, String field) {
        SessionPrincipal view = CurrentSession.current().orElse(null);
        if (view == null || !view.tenantView()) {
            return null;
        }
        String module =
                ViewRuleCatalog.find(resource, field)
                        .map(ViewRuleCatalog.Entry::moduleCode)
                        .orElse(null);
        return ViewRuleResolver.resolve(
                rules.get(), Set.copyOf(view.roles()), view.tenantId(), module, resource, field);
    }

    /** 资源操作与导出效果（PLT-VIEW-012）：目标模块 + 资源在该院的读写 / 导出口径。 */
    public Effect operationEffect(long tenantId, String moduleCode, String resource) {
        SessionPrincipal view = CurrentSession.current().orElse(null);
        Set<String> roles = view == null ? Set.of() : Set.copyOf(view.roles());
        return ViewRuleResolver.resolve(rules.get(), roles, tenantId, moduleCode, resource, null);
    }

    /** 该院模块是否开通（视角请求的 40301 判定，实时查平台库）。 */
    public boolean moduleOpen(long tenantId, String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) {
            return true;
        }
        return TenantContext.callAsPlatform(
                () ->
                        platform.queryForObject(
                                        "SELECT COUNT(*) FROM plt_tenant_module"
                                                + " WHERE tenant_id = ? AND module_code = ?"
                                                + " AND status = 'OPEN'",
                                        Integer.class,
                                        tenantId,
                                        moduleCode)
                                > 0);
    }

    // ---------------------------------------------------------------- 权限码切分（分类辅助，过滤器与单测共用）

    /** 医院端权限码的第三段（动作；{模块}:{资源}:{动作}）。 */
    public static String actionOf(String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return "";
        }
        int at = permCode.lastIndexOf(':');
        return at < 0 ? permCode : permCode.substring(at + 1);
    }

    /** 权限码的模块段（第一段；视角规则的模块选择器同码）。 */
    public static String moduleOf(String permCode) {
        if (permCode == null) {
            return null;
        }
        int at = permCode.indexOf(':');
        return at <= 0 ? null : permCode.substring(0, at);
    }

    /** 权限码的资源段（第二段；视角字段目录的 resource 同码，如 patient / report）。 */
    public static String resourceOf(String permCode) {
        if (permCode == null) {
            return null;
        }
        int first = permCode.indexOf(':');
        int last = permCode.lastIndexOf(':');
        if (first <= 0 || last <= first) {
            return null;
        }
        return permCode.substring(first + 1, last);
    }
}
