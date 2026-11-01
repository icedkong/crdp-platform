package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.apires.ApiResourceRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 接口资源查询与标记（E05-T06 / PLT-PERM-004，平台端页面「接口资源」）：展示自动登记的接口，按模块与权限码
 * 筛选，未绑定权限码的接口高亮（unbound）；平台端可标记公共接口、审计级别、敏感级别、动作类型，并可给未加注解的 接口补绑权限码。标记写 API_UPDATE
 * 审计并即时刷新登记册（默认拒绝闸门立刻按新标记放行 / 拒绝）。
 */
@Service
public class ApiResourceService {

    private static final Pattern PERM_CODE =
            Pattern.compile("[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*){2}");

    private static final List<String> AUDIT_LEVELS = List.of("NONE", "OPERATION", "DATA_ACCESS");
    private static final List<String> SENSITIVE_LEVELS = List.of("NORMAL", "SENSITIVE");
    private static final List<String> ACTION_TYPES =
            List.of("QUERY", "WRITE", "EXPORT", "DOWNLOAD");

    private final PlatformMapper platformMapper;
    private final ApiResourceScanner scanner;
    private final AuditLogger audit;

    public ApiResourceService(
            PlatformMapper platformMapper, ApiResourceScanner scanner, AuditLogger audit) {
        this.platformMapper = platformMapper;
        this.scanner = scanner;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 接口资源视图（未绑定权限码的非公共接口由前端高亮为「未绑定」）。 */
    public record ApiView(
            long id,
            String httpMethod,
            String pathPattern,
            String permCode,
            String moduleCode,
            boolean publicAccess,
            String auditLevel,
            String sensitiveLevel,
            String actionType,
            String source) {

        static ApiView of(ApiResourceRow row) {
            return new ApiView(
                    row.getId(),
                    row.getHttpMethod(),
                    row.getPathPattern(),
                    row.getPermCode(),
                    row.getModuleCode(),
                    Boolean.TRUE.equals(row.getPublicAccess()),
                    row.getAuditLevel(),
                    row.getSensitiveLevel(),
                    row.getActionType(),
                    row.getSource());
        }
    }

    /** 标记请求：全部可省略（沿用现值）；permCode 可绑定到未加注解的接口（加注解的以代码为准，忽略覆盖）。 */
    public record MarkRequest(
            String permCode,
            Boolean publicAccess,
            String auditLevel,
            String sensitiveLevel,
            String actionType) {}

    // ---------------------------------------------------------------- 查询

    public List<ApiView> list(String moduleCode, String permCode, boolean unbound) {
        return TenantContext.callAsPlatform(
                () ->
                        platformMapper.listApiResources().stream()
                                .filter(
                                        row ->
                                                moduleCode == null
                                                        || moduleCode.isBlank()
                                                        || moduleCode.equals(row.getModuleCode()))
                                .filter(
                                        row ->
                                                permCode == null
                                                        || permCode.isBlank()
                                                        || permCode.equals(row.getPermCode()))
                                .filter(
                                        row ->
                                                !unbound
                                                        || (row.getPermCode() == null
                                                                || row.getPermCode().isBlank()))
                                .map(ApiView::of)
                                .toList());
    }

    // ---------------------------------------------------------------- 标记

    public ApiView mark(long id, MarkRequest request) {
        ApiResourceRow row =
                TenantContext.callAsPlatform(
                        () -> {
                            ApiResourceRow existing = platformMapper.findApiResourceById(id);
                            if (existing == null) {
                                throw new ApiException(404, 40400, "接口资源不存在");
                            }
                            return existing;
                        });
        String permCode =
                request.permCode() == null ? row.getPermCode() : request.permCode().trim();
        if (permCode != null && !permCode.isBlank() && !PERM_CODE.matcher(permCode).matches()) {
            throw new ApiException(
                    400, 40001, "permCode 权限码格式 {模块}:{资源}:{动作}（如 followup:task:view）");
        }
        if (permCode != null && permCode.isBlank()) {
            permCode = null;
        }
        boolean publicAccess =
                request.publicAccess() == null
                        ? Boolean.TRUE.equals(row.getPublicAccess())
                        : request.publicAccess();
        String auditLevel =
                request.auditLevel() == null ? row.getAuditLevel() : request.auditLevel();
        if (!AUDIT_LEVELS.contains(auditLevel)) {
            throw new ApiException(400, 40001, "auditLevel 审计级别只允许 NONE / OPERATION / DATA_ACCESS");
        }
        String sensitiveLevel =
                request.sensitiveLevel() == null
                        ? row.getSensitiveLevel()
                        : request.sensitiveLevel();
        if (!SENSITIVE_LEVELS.contains(sensitiveLevel)) {
            throw new ApiException(400, 40001, "sensitiveLevel 敏感级别只允许 NORMAL / SENSITIVE");
        }
        String actionType =
                request.actionType() == null ? row.getActionType() : request.actionType();
        if (!ACTION_TYPES.contains(actionType)) {
            throw new ApiException(
                    400, 40001, "actionType 动作类型只允许 QUERY / WRITE / EXPORT / DOWNLOAD");
        }
        if (permCode == null && !publicAccess) {
            throw new ApiException(
                    400, 40001, "未绑定权限码的接口必须标记为公共接口（platform-spec §4：非公共接口未绑定权限码一律不可访问）");
        }
        ApiResourceRow marked = new ApiResourceRow();
        marked.setId(row.getId());
        marked.setHttpMethod(row.getHttpMethod());
        marked.setPathPattern(row.getPathPattern());
        marked.setPermCode(permCode);
        marked.setModuleCode(row.getModuleCode());
        marked.setPublicAccess(publicAccess);
        marked.setAuditLevel(auditLevel);
        marked.setSensitiveLevel(sensitiveLevel);
        marked.setActionType(actionType);
        marked.setSource(row.getSource());
        TenantContext.callAsPlatform(() -> platformMapper.updateApiResourceMarks(marked));
        scanner.reloadRegistry();
        audit.record(
                new AuditEvent(
                        "API_UPDATE",
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        "MARK",
                        "plt_api_resource:" + row.getHttpMethod() + " " + row.getPathPattern(),
                        "标记接口属性：权限码 "
                                + (permCode == null ? "（无）" : permCode)
                                + "，公共 "
                                + (publicAccess ? "是" : "否")
                                + "，审计 "
                                + auditLevel
                                + "，敏感 "
                                + sensitiveLevel
                                + "，动作 "
                                + actionType));
        return TenantContext.callAsPlatform(
                () -> ApiView.of(platformMapper.findApiResourceById(id)));
    }
}
