package com.crdp.server.system.audit;

import java.util.List;

/**
 * E08-T04 / PLT-AUD-005、006、007 的查询与导出视图（医院端与平台端共用）。 字段口径对齐 platform-spec §9 「关键字段 · 通用日志字段 +
 * 扩展字段」；时间为 ISO-8601 带时区（北京时间），超过 2^53 的 id 由全局序列化配置转字符串。
 */
public final class AuditViews {

    private AuditViews() {}

    /** 查询条件（PLT-AUD-005：时间、用户、类型、模块、对象、结果、IP、traceId 组合；平台端多一个租户）。 */
    public record SearchRequest(
            String startTime,
            String endTime,
            Long userId,
            String userName,
            String module,
            String action,
            String objectType,
            String objectId,
            String result,
            String ip,
            String traceId,
            Long tenantId,
            Integer pageNo,
            Integer pageSize) {}

    /** 列表行：各日志类型的公共展示字段，类型特有的列在详情里展开。 */
    public record AuditRowView(
            long id,
            String logType,
            String createdAt,
            String userName,
            String displayName,
            String userType,
            String module,
            String action,
            String description,
            String objectType,
            String objectId,
            String result,
            Integer errorCode,
            String reason,
            String ip,
            String traceId,
            Long tenantId,
            String loginMethod,
            String sessionId,
            String accessMethod,
            Long recordCount) {}

    public record AuditPage(List<AuditRowView> items, long total, int pageNo, int pageSize) {}

    /** 同一 traceId 的其他日志（详情页「关联链路」链接，audit.md §3）。 */
    public record AuditTraceLink(
            String logType, long id, String action, String description, String createdAt) {}

    /** 详情（PLT-AUD-006）：前后值对比（敏感值在写入侧已是摘要）+ 哈希链值与关联链路。 */
    public record AuditDetailView(
            AuditRowView row,
            String detailText,
            String beforeJson,
            String afterJson,
            String userAgent,
            String viewSessionId,
            String roleCodes,
            String prevHash,
            String hash,
            List<AuditTraceLink> traceLinks) {}

    /** 导出申请提交请求（PLT-AUD-007）。reason 为申请理由（审批人参考，≤200）。 */
    public record ExportRequest(String type, SearchRequest condition, String reason) {}

    /** 导出作业行（我的导出 / 待审批列表共用）。 */
    public record ExportJobView(
            long id,
            String bizType,
            String exportType,
            String conditionSummary,
            String reason,
            long applicantId,
            String applicantName,
            String applicantDisplayName,
            String status,
            String approverName,
            String approveComment,
            Long rowCount,
            String fileName,
            String fileHash,
            String failReason,
            String expiresAt,
            String createdAt) {}

    public record ExportJobPage(List<ExportJobView> items, long total, int pageNo, int pageSize) {}

    /** 提交结果：status 直接给出生成路径（GENERATING＝免审批或已过审并开始异步生成）。 */
    public record ExportSubmitted(long exportId, String status) {}

    /** 审批动作（approve / reject 共用 body）。 */
    public record ApprovalAction(String comment) {}

    /** 最近一次验链结果（GET /api/platform/audit/integrity，PLT-AUD-009）。 */
    public record ChainCheckView(
            String tableName,
            String status,
            long hashedRows,
            int breakCount,
            Long firstBreakId,
            String firstBreakKind,
            String detail,
            String checkedAt) {}
}
