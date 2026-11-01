package com.crdp.server.system.audit;

import java.util.List;
import java.util.Map;

/**
 * E08-T04 审计表登记：日志类型 → 表名、导出列（查询列与 CSV 列一致）。 医院端与平台端的表结构见 V15 / V25（规格列）。 查询接口的 {@code type}
 * 路径段（operation / login / data-access / tenant-view / view-rule / control-plane）由各端服务映射到这里的核心类型 +
 * 表过滤条件。
 */
public final class AuditTables {

    private AuditTables() {}

    /** 核心日志类型（对应库表，区别于接口路径段）。 */
    public enum Kind {
        /** 操作日志：租户 aud_log / 平台 plt_audit_log。 */
        OPERATION,
        /** 登录日志：两端各自库的 aud_login_log。 */
        LOGIN,
        /** 数据访问日志：租户 aud_data_access_log。 */
        DATA_ACCESS,
        /** 平台访问日志（租户视角）：平台 aud_platform_access_log。 */
        PLATFORM_ACCESS
    }

    /** 租户库三张表。 */
    public static final Map<Kind, String> TENANT_TABLES =
            Map.of(
                    Kind.OPERATION, "aud_log",
                    Kind.LOGIN, "aud_login_log",
                    Kind.DATA_ACCESS, "aud_data_access_log");

    /** 平台库三张表。 */
    public static final Map<Kind, String> PLATFORM_TABLES =
            Map.of(
                    Kind.OPERATION, "plt_audit_log",
                    Kind.LOGIN, "aud_login_log",
                    Kind.PLATFORM_ACCESS, "aud_platform_access_log");

    /** 导出列（CSV 列头与 SELECT 列一致；id 与 created_at 恒在最前）。 */
    public static final Map<Kind, List<String>> COLUMNS =
            Map.of(
                    Kind.OPERATION,
                    List.of(
                            "id",
                            "created_at",
                            "user_name",
                            "display_name",
                            "user_type",
                            "role_codes",
                            "module",
                            "event_type",
                            "action",
                            "target",
                            "object_type",
                            "object_id",
                            "detail",
                            "result",
                            "error_code",
                            "reason",
                            "ip",
                            "user_agent",
                            "trace_id",
                            "view_session_id"),
                    Kind.LOGIN,
                    List.of(
                            "id",
                            "created_at",
                            "username",
                            "user_id",
                            "display_name",
                            "user_type",
                            "role_codes",
                            "module",
                            "action",
                            "result",
                            "error_code",
                            "login_method",
                            "tenant_identify_method",
                            "second_factor_result",
                            "fail_reason",
                            "session_id",
                            "ip",
                            "user_agent",
                            "trace_id"),
                    Kind.DATA_ACCESS,
                    List.of(
                            "id",
                            "created_at",
                            "user_name",
                            "display_name",
                            "user_type",
                            "role_codes",
                            "module",
                            "action",
                            "access_method",
                            "object_type",
                            "object_id",
                            "record_count",
                            "query_digest",
                            "file_hash",
                            "result",
                            "error_code",
                            "reason",
                            "description",
                            "ip",
                            "user_agent",
                            "trace_id",
                            "view_session_id"),
                    Kind.PLATFORM_ACCESS,
                    List.of(
                            "id",
                            "created_at",
                            "tenant_id",
                            "user_name",
                            "display_name",
                            "user_type",
                            "role_codes",
                            "module",
                            "action",
                            "description",
                            "object_type",
                            "object_id",
                            "view_session_id",
                            "page",
                            "access_method",
                            "record_count",
                            "query_digest",
                            "file_hash",
                            "result",
                            "error_code",
                            "reason",
                            "ip",
                            "user_agent",
                            "trace_id"));

    /** 接口路径段 → 类型（医院端）。 */
    public static Kind tenantKind(String type) {
        return switch (type == null ? "" : type) {
            case "operation" -> Kind.OPERATION;
            case "login" -> Kind.LOGIN;
            case "data-access" -> Kind.DATA_ACCESS;
            default ->
                    throw new com.crdp.framework.web.ApiException(
                            400, 40001, "日志类型不正确，应为 operation / login / data-access");
        };
    }

    /** 接口路径段 → 类型（平台端）。view-rule / control-plane 复用底层表加过滤，见 PlatformAuditService。 */
    public static Kind platformKind(String type) {
        return switch (type == null ? "" : type) {
            case "operation", "control-plane" -> Kind.OPERATION;
            case "login" -> Kind.LOGIN;
            case "tenant-view", "view-rule" -> Kind.PLATFORM_ACCESS;
            default ->
                    throw new com.crdp.framework.web.ApiException(
                            400,
                            40001,
                            "日志类型不正确，应为 operation / login / tenant-view / view-rule / control-plane");
        };
    }
}
