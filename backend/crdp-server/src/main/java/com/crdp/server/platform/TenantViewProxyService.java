package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.crdp.server.system.user.UserAccountAdminService;
import com.crdp.server.system.user.UserAccountAdminService.ResetDraft;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 租户视角的代为操作（E20-T06 / PLT-VIEW-013、017、018，验收「默认关闭，开启需授权并记审计」）。
 *
 * <p>三类代为操作全部默认关闭，开启 = 平台管理员在平台角色上配置对应字段（{@code view_can_configure} / {@code
 * view_can_reset_password} / {@code view_can_edit_business}，多角色取并集），每次执行都写平台访问审计 （{@code
 * VIEW_PROXY_CONFIG} / {@code VIEW_PROXY_RESET} / {@code VIEW_PROXY_EDIT}，含原因与前后值）并递增会话台账 {@code
 * proxy_count}——平台侧可见，医院端不可见（PLT-VIEW-016）。
 *
 * <ul>
 *   <li><b>代为配置（PLT-VIEW-013）</b>：医院端配置类资源的写接口按「角色可代为配置 ∧ 资源在范围内（未配置范围 = 全部配置类资源，已确认）」放行；请求头 {@code
 *       X-View-Reason} 携带二次确认填写的操作原因（必填）；
 *   <li><b>编辑业务数据（PLT-VIEW-018）</b>：followup 模块业务资源的写接口按「角色可编辑业务数据 ∧ 命中操作为
 *       可编辑的视角规则」放行（任一不满足即只读，platform-spec §8）；前后值完整记录只存平台侧；
 *   <li><b>代重置医院用户密码（PLT-VIEW-017）</b>：专用代理口（医院侧重置口对视角令牌仍拒绝），新密码只经短信 发给用户本人（发送成功才落库），平台账号全程看不到新密码。
 * </ul>
 *
 * <p>配置类与业务数据的资源分类（{@link #classify}，纯函数）：{@code base} 模块（tenant:*）全部为配置类； {@code followup} 模块内
 * action=config（随访异常 / 通知 / 工作台卡片 / 报告单配置）与资源 field（表单字段字典）为配置类，
 * 其余（患者、计划、任务、记录、报告单与电子化资料、联系记录等）为业务数据；后续新模块（如 rct）按业务数据对待 （platform-spec §8「后续模块数据按同一规则」，默认只读规则下
 * fail closed）。动作 plain（明文查看）与 reset （医院侧重置口）<b>永远不放行</b>——明文是平台铁律（AGENTS.md §5.3），重置只走
 * PLT-VIEW-017 的专用代理口。
 */
@Service
public class TenantViewProxyService {

    private static final Logger log = LoggerFactory.getLogger(TenantViewProxyService.class);

    /**
     * 代为操作原因请求头（前端二次确认弹窗填写，后端校验必填）。HTTP 头不承载非 ASCII—— 取值按 encodeURIComponent 的百分号编码（前端 {@code
     * encodeURIComponent(reason)}，后端 {@link #requireHeaderReason} 解码），中文原因无损传输。
     */
    public static final String REASON_HEADER = "X-View-Reason";

    /** 原因长度口径：≥2 字（不允许单字符敷衍）、≤500（与进入说明同口径）。 */
    static final int REASON_MIN = 2;

    static final int REASON_MAX = 500;

    /** 平台访问审计动作：代为配置（PLT-VIEW-013）。 */
    public static final String ACTION_PROXY_CONFIG = "VIEW_PROXY_CONFIG";

    /** 平台访问审计动作：编辑业务数据（PLT-VIEW-018）。 */
    public static final String ACTION_PROXY_EDIT = "VIEW_PROXY_EDIT";

    /** 平台访问审计动作：代重置医院用户密码（PLT-VIEW-017）。 */
    public static final String ACTION_PROXY_RESET = "VIEW_PROXY_RESET";

    /** 前后值快照的入库上限（超出截断，防大对象撑爆审计行）。 */
    static final int SNAPSHOT_MAX = 32_000;

    private final JdbcTemplate platform;

    private final TenantViewRuleExecutor executor;

    private final TenantViewAccessRecorder recorder;

    private final UserAccountAdminService userAdmin;

    private final SmsCodeSender sms;

    private final AuditLogger audit;

    public TenantViewProxyService(
            JdbcTemplate platform,
            TenantViewRuleExecutor executor,
            TenantViewAccessRecorder recorder,
            UserAccountAdminService userAdmin,
            SmsCodeSender sms,
            AuditLogger audit) {
        this.platform = platform;
        this.executor = executor;
        this.recorder = recorder;
        this.userAdmin = userAdmin;
        this.sms = sms;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 能力（PLT-VIEW-005 字段的运行时并集）

    /**
     * 视角会话的代为操作能力：账号全部<b>启用</b>角色的并集（与进入授权同口径，实时查平台库—— 负责关系或角色字段变化时进行中的会话已被结束，这里读到的是有效集）。 {@code
     * configResources=null} 表示全部配置类资源（任一可代为配置的角色未配置范围即全部，PLT-VIEW-013 已确认）； {@code
     * businessEditable} 为业务数据「可编辑」规则在该院 followup 模块的命中结果（供前端按钮显隐， 执行点仍逐资源判定）。
     */
    public ViewCapabilities capabilitiesOf(long accountId, long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    List<Map<String, Object>> rows =
                            platform.queryForList(
                                    "SELECT r.view_can_configure, r.view_config_resources,"
                                            + " r.view_can_reset_password, r.view_can_edit_business"
                                            + " FROM plt_role r JOIN plt_account_role ar ON ar.role_id"
                                            + " = r.id"
                                            + " WHERE ar.account_id = ? AND r.status = 'ENABLED'",
                                    accountId);
                    boolean configure = false;
                    boolean reset = false;
                    boolean edit = false;
                    boolean scopeAll = false;
                    Set<String> scope = new LinkedHashSet<>();
                    for (Map<String, Object> row : rows) {
                        boolean canConfigure =
                                ((Number) row.get("view_can_configure")).intValue() == 1;
                        configure |= canConfigure;
                        reset |= ((Number) row.get("view_can_reset_password")).intValue() == 1;
                        edit |= ((Number) row.get("view_can_edit_business")).intValue() == 1;
                        if (canConfigure) {
                            List<String> listed =
                                    PlatformRoleService.parseConfigResources(
                                            row.get("view_config_resources") == null
                                                    ? null
                                                    : String.valueOf(
                                                            row.get("view_config_resources")));
                            if (listed == null) {
                                scopeAll = true;
                            } else {
                                scope.addAll(listed);
                            }
                        }
                    }
                    boolean businessEditable = false;
                    if (edit) {
                        Effect effect = executor.operationEffect(tenantId, "followup", null);
                        businessEditable =
                                effect != null
                                        && ViewRuleCatalog.OPERATION_EDITABLE.equals(
                                                effect.operation());
                    }
                    return new ViewCapabilities(
                            configure,
                            scopeAll || scope.isEmpty() ? null : List.copyOf(scope),
                            reset,
                            edit,
                            businessEditable);
                });
    }

    /** 代为操作能力（多角色并集结果；configResources=null = 全部配置类资源）。 */
    public record ViewCapabilities(
            boolean canConfigure,
            List<String> configResources,
            boolean canResetPassword,
            boolean canEditBusiness,
            boolean businessEditable) {

        /** 该配置类资源是否在代为配置范围内（纯函数，供单测）。 */
        public boolean configAllows(String resource) {
            return canConfigure && (configResources == null || configResources.contains(resource));
        }
    }

    // ---------------------------------------------------------------- 资源分类（纯函数，供过滤器与单测共用）

    /** 写接口的分类：CONFIG 代为配置（PLT-VIEW-013）/ BUSINESS 编辑业务数据（PLT-VIEW-018）/ NEVER 不放行。 */
    public enum WriteClass {
        CONFIG,
        BUSINESS,
        NEVER
    }

    /** 对视角令牌永远不放行的动作段：明文查看（平台铁律）与医院侧重置口（只走 PLT-VIEW-017 代理口）。 */
    static final Set<String> NEVER_ACTIONS = Set.of("plain", "reset");

    /**
     * 按登记信息分类写接口：module 为登记模块（plt_api_resource.module_code，tenant:* 落 base）、 code 为权限码
     * （可能逗号拼接多候选，取首个）。纯函数。
     */
    public static WriteClass classify(String moduleCode, String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return WriteClass.NEVER;
        }
        String first = permCode.split(",")[0].trim();
        String action = TenantViewRuleExecutor.actionOf(first);
        if (NEVER_ACTIONS.contains(action)) {
            return WriteClass.NEVER;
        }
        String resource = TenantViewRuleExecutor.resourceOf(first);
        if ("followup".equals(moduleCode)) {
            // followup 模块内的配置页：action=config（异常规则 / 通知 / 工作台卡片 / 报告单配置）与字段字典
            return "config".equals(action) || "field".equals(resource)
                    ? WriteClass.CONFIG
                    : WriteClass.BUSINESS;
        }
        // base（tenant:* 配置类）与后续模块：base 全部配置类；其余模块按业务数据（默认只读规则 fail closed）
        return "base".equals(moduleCode) ? WriteClass.CONFIG : WriteClass.BUSINESS;
    }

    // ---------------------------------------------------------------- 原因校验（纯函数）

    /** 请求头原因解码 + 校验（百分号编码 UTF-8 → 明文；坏编码按原因缺失处理）。 */
    public static String requireHeaderReason(String raw) {
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(raw == null ? "" : raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badEncoding) {
            decoded = "";
        }
        return requireReason(decoded);
    }

    /** 代为操作原因校验：必填 2–500 字（前端二次确认弹窗收集，PLT-VIEW-013「二次确认并填写原因」）。 */
    public static String requireReason(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < REASON_MIN) {
            throw new ApiException(
                    400, 40001, "代为操作必须填写原因（" + REASON_MIN + "–" + REASON_MAX + " 字，二次确认后随请求提交）");
        }
        if (value.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "代为操作原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return value;
    }

    // ---------------------------------------------------------------- 受控放行的审计（PLT-VIEW-016）

    /**
     * 记一次已放行的代为操作（成功路径）：VIEW_PROXY_* 平台访问审计（对象、原因、前后值）+ 台账 proxy_count 递增。 beforeJson / afterJson
     * 为写前同路径 GET 快照与写请求载荷（超限截断；失败为 null 不阻塞）。
     */
    public void recordProxy(
            SessionPrincipal view,
            String method,
            String uri,
            String action,
            String objectType,
            String objectId,
            String reason,
            String beforeJson,
            String afterJson) {
        audit.append(
                AuditEntry.platformAccess(view.tenantId(), "base", action)
                        .userId(view.userId())
                        .username(view.username())
                        .displayName(view.displayName())
                        .roleCodes(view.roles())
                        .description(
                                ("VIEW_PROXY_RESET".equals(action) ? "代重置密码 " : "代为操作 ")
                                        + method
                                        + " "
                                        + uri
                                        + "，原因："
                                        + reason)
                        .objectType(objectType)
                        .objectId(objectId)
                        .reason(reason)
                        .beforeJson(truncate(beforeJson))
                        .afterJson(truncate(afterJson))
                        .viewSessionId(view.sessionId())
                        .page(uri)
                        .success()
                        .build());
        recorder.bumpProxyCount(view);
        log.info(
                "tenant view proxy account={} tenant={} action={} uri={} session={}",
                view.username(),
                view.tenantCode(),
                action,
                uri,
                view.sessionId());
    }

    /** 快照截断（纯函数）：超限保留前缀并标记，绝不因截断抛错。 */
    public static String truncate(String json) {
        if (json == null || json.length() <= SNAPSHOT_MAX) {
            return json;
        }
        return json.substring(0, SNAPSHOT_MAX) + "…(截断)";
    }

    // ---------------------------------------------------------------- 代重置医院用户密码（PLT-VIEW-017）

    /** 代重置结果：只回账号与脱敏手机号（证明短信去向），<b>绝不回新密码</b>。 */
    public record ProxyResetResult(
            long userId, String username, String displayName, String sentTo) {}

    /**
     * 代重置医院用户密码（视角令牌专用代理口）：能力校验（角色可代重置，否则 40303 并写 VIEW_DENIED）→ 生成临时密码 →
     * <b>先发短信</b>（发送失败重置不生效，短信通道异常如实报错）→ 落库（强制改密、旧哈希入历史、会话失效、解锁）→ 写 VIEW_PROXY_RESET
     * 平台访问审计。新密码只经短信发给用户本人，任何响应、日志与审计都不含新密码（已确认）。
     */
    public ProxyResetResult resetUserPassword(long userId, String rawReason) {
        SessionPrincipal view = CurrentSession.requireTenantView();
        String reason = requireReason(rawReason);
        ViewCapabilities capabilities = capabilitiesOf(view.userId(), view.tenantId());
        if (!capabilities.canResetPassword()) {
            recorder.recordDenied(
                    view,
                    "POST",
                    "/api/hospital/view/users/" + userId + "/reset-password",
                    40303,
                    "平台角色未授权代重置医院用户密码");
            throw new ApiException(403, 40303, "平台角色未授权代重置医院用户密码，请联系平台管理员");
        }
        ResetDraft draft = userAdmin.draftPasswordReset(userId);
        try {
            sms.sendTemporaryPassword(draft.phone(), draft.password());
        } catch (RuntimeException sendFailure) {
            log.warn(
                    "代重置密码短信下发失败，重置未生效 user={} tenant={} session={}",
                    draft.username(),
                    view.tenantCode(),
                    view.sessionId());
            throw new ApiException(500, 50000, "新密码短信下发失败，本次代重置未生效（用户密码未变化），请稍后重试或联系平台运维");
        }
        userAdmin.commitPasswordReset(draft);
        recordProxy(
                view,
                "POST",
                "/api/hospital/view/users/" + userId + "/reset-password",
                ACTION_PROXY_RESET,
                "user",
                String.valueOf(userId),
                reason,
                null,
                "新密码已经短信发送给用户本人（" + draft.username() + "，平台侧不留存新密码）");
        return new ProxyResetResult(
                draft.userId(), draft.username(), draft.displayName(), draft.phoneMasked());
    }

    // ---------------------------------------------------------------- 供过滤器使用的受控放行判定

    /**
     * 写请求是否按代为操作放行（{@code TenantViewGuardFilter} 调用）：CONFIG = 可代为配置 ∧ 资源在范围； BUSINESS = 可编辑业务数据 ∧
     * 该资源命中操作为可编辑的视角规则（PLT-VIEW-018「任一不满足即只读」）。 返回 null 表示不放行。
     */
    public String releaseAction(
            SessionPrincipal view, WriteClass writeClass, String moduleCode, String resource) {
        ViewCapabilities capabilities = capabilitiesOf(view.userId(), view.tenantId());
        switch (writeClass) {
            case CONFIG -> {
                return capabilities.configAllows(resource) ? ACTION_PROXY_CONFIG : null;
            }
            case BUSINESS -> {
                if (!capabilities.canEditBusiness()) {
                    return null;
                }
                Effect effect = executor.operationEffect(view.tenantId(), moduleCode, resource);
                return effect != null
                                && ViewRuleCatalog.OPERATION_EDITABLE.equals(effect.operation())
                        ? ACTION_PROXY_EDIT
                        : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** 供单测构造（并集口径的纯逻辑核对）。 */
    static List<String> unionScope(List<List<String>> roleScopes) {
        boolean all = false;
        Set<String> union = new LinkedHashSet<>();
        for (List<String> scope : roleScopes) {
            if (scope == null) {
                all = true;
            } else {
                union.addAll(scope);
            }
        }
        if (all || union.isEmpty()) {
            return null;
        }
        return new ArrayList<>(union);
    }
}
