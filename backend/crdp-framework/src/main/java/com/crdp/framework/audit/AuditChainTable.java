package com.crdp.framework.audit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计哈希链的表登记与规范化内容（E08-T02 / PLT-AUD-008）。写入与验链两侧共用同一份列顺序与取值归一规则， 保证「写入时算出的 hash」与「验链时按行重算的
 * hash」逐字节一致。
 *
 * <p>规范化串（canonical form）：UTF-8 文本，按行拼接，值做 URL 编码（换行、竖线、等号等边界字符不会破坏结构）：
 *
 * <pre>
 * v1
 * &lt;表名&gt;
 * &lt;列1&gt;=&lt;URL 编码值1&gt;
 * &lt;列2&gt;=&lt;URL 编码值2&gt;
 * ...
 * </pre>
 *
 * <p>哈希输入 = 前序哈希 + 换行 + 规范化串；hash = SHA-256(哈希输入)（经 {@code
 * com.crdp.framework.crypto.DigestService}，NFR-CRY-006）。 id 与 created_at 不参与哈希：id 在 INSERT
 * 前不可知，created_at 由数据库时钟填充；链的位置完整性由「prev_hash 与前一条 hash 衔接 + 主键序」保证。
 *
 * <p>链的定义：每张表独立一条链，按主键升序、只计已封存（hash 非空）的行； 首条封存行的 prev_hash = {@link #GENESIS}（64 个 0）。E08-T01
 * 遗留的未封存行是链前缀，验链跳过。
 */
public final class AuditChainTable {

    /** 链锚：首条封存行的前序哈希。 */
    public static final String GENESIS = "0".repeat(64);

    /** 规范化串版本号（未来调整格式时递增，便于识别重算口径）。 */
    private static final String CANONICAL_VERSION = "v1";

    // ---------------------------------------------------------------- 六张审计表的登记
    // 列顺序与 JdbcAuditLogger 的 INSERT 列一一对应（不含 prev_hash / hash 本身）。

    /** 租户库 · 操作日志（PLT-AUD-001）。 */
    public static final AuditChainTable TENANT_OPERATION =
            new AuditChainTable(
                    "aud_log",
                    List.of(
                            "event_type",
                            "actor_id",
                            "action",
                            "target",
                            "detail",
                            "trace_id",
                            "user_name",
                            "display_name",
                            "user_type",
                            "role_codes",
                            "module",
                            "object_type",
                            "object_id",
                            "result",
                            "error_code",
                            "reason",
                            "before_json",
                            "after_json",
                            "ip",
                            "user_agent",
                            "view_session_id"));

    /** 租户库 · 登录日志（PLT-AUD-002）。 */
    public static final AuditChainTable TENANT_LOGIN =
            new AuditChainTable(
                    "aud_login_log",
                    List.of(
                            "username",
                            "success",
                            "message",
                            "trace_id",
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
                            "user_agent"));

    /** 租户库 · 数据访问日志（PLT-AUD-003）。 */
    public static final AuditChainTable TENANT_DATA_ACCESS =
            new AuditChainTable(
                    "aud_data_access_log",
                    List.of(
                            "user_id",
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
                            "view_session_id"));

    /** 平台库 · 平台操作 / 租户控制面日志（PLT-TEN-022）。 */
    public static final AuditChainTable PLATFORM_OPERATION =
            new AuditChainTable(
                    "plt_audit_log",
                    List.of(
                            "event_type",
                            "actor_id",
                            "tenant_id",
                            "action",
                            "target",
                            "detail",
                            "trace_id",
                            "user_name",
                            "display_name",
                            "user_type",
                            "role_codes",
                            "module",
                            "object_type",
                            "object_id",
                            "result",
                            "error_code",
                            "reason",
                            "before_json",
                            "after_json",
                            "ip",
                            "user_agent",
                            "view_session_id"));

    /** 平台库 · 登录日志（平台账号 + 租户未识别的医院登录失败）。与租户库同名表列不同，注意登记的是平台侧。 */
    public static final AuditChainTable PLATFORM_LOGIN =
            new AuditChainTable(
                    "aud_login_log",
                    List.of(
                            "tenant_id",
                            "username",
                            "success",
                            "message",
                            "trace_id",
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
                            "user_agent"));

    /** 平台库 · 平台访问日志（PLT-AUD-004，医院端不可见）。 */
    public static final AuditChainTable PLATFORM_ACCESS =
            new AuditChainTable(
                    "aud_platform_access_log",
                    List.of(
                            "tenant_id",
                            "user_id",
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
                            "before_json",
                            "after_json",
                            "result",
                            "error_code",
                            "reason",
                            "ip",
                            "user_agent",
                            "trace_id"));

    private static final List<AuditChainTable> TENANT_TABLES =
            List.of(TENANT_OPERATION, TENANT_LOGIN, TENANT_DATA_ACCESS);

    private static final List<AuditChainTable> PLATFORM_TABLES =
            List.of(PLATFORM_OPERATION, PLATFORM_LOGIN, PLATFORM_ACCESS);

    private final String tableName;
    private final List<String> hashColumns;

    /** 公开构造：测试与后续新审计表登记用；生产六张表以静态常量为准。 */
    public AuditChainTable(String tableName, String... columns) {
        this(tableName, List.of(columns));
    }

    private AuditChainTable(String tableName, List<String> hashColumns) {
        this.tableName = tableName;
        this.hashColumns = List.copyOf(hashColumns);
    }

    public String tableName() {
        return tableName;
    }

    /** 参与哈希的列（顺序固定，写入参数按此顺序对齐）。 */
    public List<String> hashColumns() {
        return hashColumns;
    }

    /** 租户库三张审计表。 */
    public static List<AuditChainTable> tenantTables() {
        return TENANT_TABLES;
    }

    /** 平台库三张审计表。 */
    public static List<AuditChainTable> platformTables() {
        return PLATFORM_TABLES;
    }

    /** 全部登记表（登记完整性自检用）。 */
    public static List<AuditChainTable> all() {
        List<AuditChainTable> all = new ArrayList<>(TENANT_TABLES);
        all.addAll(PLATFORM_TABLES);
        return List.copyOf(all);
    }

    /** 规范化内容串；values 与 {@link #hashColumns()} 等长对齐。 */
    public String canonicalForm(List<Object> values) {
        if (values.size() != hashColumns.size()) {
            throw new IllegalArgumentException(
                    tableName
                            + " 参与哈希的列数 "
                            + hashColumns.size()
                            + " 与参数数 "
                            + values.size()
                            + " 不一致");
        }
        StringBuilder canonical = new StringBuilder(CANONICAL_VERSION);
        canonical.append('\n').append(tableName);
        for (int i = 0; i < hashColumns.size(); i++) {
            canonical
                    .append('\n')
                    .append(hashColumns.get(i))
                    .append('=')
                    .append(
                            URLEncoder.encode(
                                    canonicalValue(values.get(i)), StandardCharsets.UTF_8));
        }
        return canonical.toString();
    }

    /** 哈希输入 = 前序哈希 + 换行 + 规范化内容（platform-spec §9：hash = SHA-256(prevHash + 规范化内容)）。 */
    public String hashInput(String prevHash, List<Object> values) {
        return prevHash + "\n" + canonicalForm(values);
    }

    /**
     * 取值归一：写入侧（Java 参数）与读回侧（JDBC 读回）得到同一字符串。 null → 空串；Boolean → 1/0（MySQL TINYINT 读回可能是
     * Boolean）；数值 → 长整型十进制（本域列全部整数）。
     */
    static String canonicalValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (value instanceof Number n) {
            return String.valueOf(n.longValue());
        }
        return String.valueOf(value);
    }
}
