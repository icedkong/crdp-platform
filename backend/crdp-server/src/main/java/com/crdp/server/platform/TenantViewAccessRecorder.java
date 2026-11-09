package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 视角会话的访问记录（E20-T03 / E20-T05；PLT-VIEW-016 的写入侧，OQ-58 ⑥）：读放行写 VIEW_QUERY、 被拒写 VIEW_DENIED
 * 平台访问审计（aud_platform_access_log，只进平台库）；台账 plt_view_session 的计数列 同步递增——GET /api/hospital/menus
 * 记页面数（page_count），其余放行读记查询数（query_count），被拒记 denied_count； 代为操作计数（proxy_count）随 E20-T06。
 *
 * <p>E20-T05 起查询明细补「对象与条数」（PLT-VIEW-016 全量口径）：VIEW_QUERY 带 object_type（被查询资源， 取接口登记权限码的资源段）与
 * record_count（本次响应的条数——分页 total / 数组长度 / 单对象 1）， page 填请求路径；条数解析失败时只缺 count，不影响放行（审计仍记对象与路径）。
 */
@Service
public class TenantViewAccessRecorder {

    /** 读放行（VIEW_QUERY 的 page 字段填请求路径，审计中心同口径展示）。 */
    public static final String ACTION_QUERY = "VIEW_QUERY";

    /** 被拒操作（写 / 导出 / 明文等，result=DENIED + 原因）。 */
    public static final String ACTION_DENIED = "VIEW_DENIED";

    private final AuditLogger audit;

    private final JdbcTemplate platform;

    public TenantViewAccessRecorder(AuditLogger audit, JdbcTemplate platform) {
        this.audit = audit;
        this.platform = platform;
    }

    /**
     * 读放行：VIEW_QUERY 审计 + query_count（菜单请求记 page_count）。 objectType = 被查询资源 （权限码资源段，未绑码为
     * null）；recordCount = 响应条数（解析失败为 null）。
     */
    public void recordQuery(
            SessionPrincipal view,
            String method,
            String uri,
            boolean page,
            String objectType,
            Long recordCount) {
        append(view, method, uri, ACTION_QUERY, AuditResult.SUCCESS, null, objectType, recordCount);
        bumpCounter(view, page ? "page_count" : "query_count");
    }

    /** 被拒：VIEW_DENIED 审计（result=DENIED + 原因）+ denied_count。 */
    public void recordDenied(
            SessionPrincipal view, String method, String uri, int errorCode, String reason) {
        append(view, method, uri, ACTION_DENIED, AuditResult.DENIED, reason, null, null);
        bumpCounter(view, "denied_count");
    }

    private void append(
            SessionPrincipal view,
            String method,
            String uri,
            String action,
            AuditResult result,
            String reason,
            String objectType,
            Long recordCount) {
        AuditEntry.Builder builder =
                AuditEntry.platformAccess(view.tenantId(), "base", action)
                        .userId(view.userId())
                        .username(view.username())
                        .displayName(view.displayName())
                        .roleCodes(view.roles())
                        .description(
                                ("VIEW_QUERY".equals(action) ? "视角查询 " : "视角操作被拒 ")
                                        + method
                                        + " "
                                        + uri)
                        .viewSessionId(view.sessionId())
                        .page(uri)
                        .result(result);
        if (objectType != null) {
            builder.objectType(objectType);
        }
        if (recordCount != null) {
            builder.recordCount(recordCount);
        }
        if (reason != null) {
            builder.reason(reason);
        }
        audit.append(builder.build());
    }

    /** 台账计数列递增（会话已结束则 0 行，无副作用）。 */
    private void bumpCounter(SessionPrincipal view, String column) {
        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "UPDATE plt_view_session SET "
                                        + column
                                        + " = "
                                        + column
                                        + " + 1"
                                        + " WHERE session_no = ? AND status = 'ACTIVE'",
                                view.sessionId()));
    }
}
