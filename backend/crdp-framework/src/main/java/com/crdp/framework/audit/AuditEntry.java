package com.crdp.framework.audit;

import java.util.List;

/**
 * 规格字段的一条审计记录（platform-spec §9「关键字段 · 通用日志字段 + 扩展字段」）。
 *
 * <p>通用字段：操作者（userId / username / displayName / userType / roleCodes）、模块、操作类型、 对象（objectType /
 * objectId，不记患者姓名等明文）、描述、变更前后值（敏感值由调用方先摘要）、结果与失败原因、 IP 与终端信息、traceId、视角会话号。 哈希链字段（本条哈希、前序哈希）由存储层在
 * E08-T02 补齐，本对象不携带。
 *
 * <p>扩展字段按类型分组：登录（loginMethod / tenantIdentifyMethod / secondFactorResult / sessionId）、
 * 数据访问（accessMethod / recordCount / queryDigest / fileHash）、平台访问（page）。未用的分组字段保持 null，不落库。
 *
 * <p>调用方只填业务语义字段；操作者与请求上下文（IP、终端、traceId）由写入服务自动补全。
 */
public record AuditEntry(
        AuditType type,
        Long tenantId,
        Long userId,
        String username,
        String displayName,
        String userType,
        List<String> roleCodes,
        String module,
        String action,
        String eventType,
        String description,
        String objectType,
        String objectId,
        AuditResult result,
        Integer errorCode,
        String reason,
        String beforeJson,
        String afterJson,
        String ip,
        String userAgent,
        String traceId,
        String viewSessionId,
        String loginMethod,
        String tenantIdentifyMethod,
        String secondFactorResult,
        String sessionId,
        String accessMethod,
        Long recordCount,
        String queryDigest,
        String fileHash,
        String page) {

    /** 操作日志（PLT-AUD-001）。 */
    public static Builder operation(String module, String action) {
        return new Builder(AuditType.OPERATION).module(module).action(action);
    }

    /** 登录日志（PLT-AUD-002）：username 必填（手机号登录时为脱敏手机号）。 */
    public static Builder login(String username) {
        return new Builder(AuditType.LOGIN).username(username).action("LOGIN");
    }

    /** 数据访问日志（PLT-AUD-003）：accessMethod ∈ VIEW_PLAIN | EXPORT | PRINT | DOWNLOAD | BATCH_QUERY。 */
    public static Builder dataAccess(String module, String accessMethod) {
        return new Builder(AuditType.DATA_ACCESS)
                .module(module)
                .accessMethod(accessMethod)
                .action(accessMethod);
    }

    /** 平台访问日志（PLT-AUD-004）：tenantId 是被查看的租户，只落平台库。 */
    public static Builder platformAccess(Long tenantId, String module, String action) {
        return new Builder(AuditType.PLATFORM_ACCESS)
                .tenantId(tenantId)
                .module(module)
                .action(action);
    }

    /** 构建器：fluent 填字段后 build()。 */
    public static final class Builder {

        private final AuditType type;
        private Long tenantId;
        private Long userId;
        private String username;
        private String displayName;
        private String userType;
        private List<String> roleCodes;
        private String module;
        private String action;
        private String eventType;
        private String description;
        private String objectType;
        private String objectId;
        private AuditResult result;
        private Integer errorCode;
        private String reason;
        private String beforeJson;
        private String afterJson;
        private String ip;
        private String userAgent;
        private String traceId;
        private String viewSessionId;
        private String loginMethod;
        private String tenantIdentifyMethod;
        private String secondFactorResult;
        private String sessionId;
        private String accessMethod;
        private Long recordCount;
        private String queryDigest;
        private String fileHash;
        private String page;

        /** 一般用静态工厂（operation/login/dataAccess/platformAccess）；补全场景需要全量重建时才直接用。 */
        public Builder(AuditType type) {
            this.type = type;
        }

        public Builder tenantId(Long tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder userType(String userType) {
            this.userType = userType;
            return this;
        }

        public Builder roleCodes(List<String> roleCodes) {
            this.roleCodes = roleCodes;
            return this;
        }

        public Builder module(String module) {
            this.module = module;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        /**
         * M0 事件名（如 ROLE_CREATE），落操作表的 event_type 列，供既有查询与 E08-T04 复用； 缺省取 {@link
         * #action(String)}。新代码通常 action 即事件名，不必单独填。
         */
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder objectType(String objectType) {
            this.objectType = objectType;
            return this;
        }

        public Builder objectId(String objectId) {
            this.objectId = objectId;
            return this;
        }

        public Builder result(AuditResult result) {
            this.result = result;
            return this;
        }

        /** 登录成功（等价 result=SUCCESS）。 */
        public Builder success() {
            this.result = AuditResult.SUCCESS;
            return this;
        }

        /** 登录失败并带失败原因（等价 result=FAIL + reason）。 */
        public Builder fail(String failReason) {
            this.result = AuditResult.FAIL;
            this.reason = failReason;
            return this;
        }

        public Builder errorCode(Integer errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /** 变更前值（JSON）。敏感值只放摘要或密文，绝不放明文。 */
        public Builder beforeJson(String beforeJson) {
            this.beforeJson = beforeJson;
            return this;
        }

        /** 变更后值（JSON）。敏感值只放摘要或密文，绝不放明文。 */
        public Builder afterJson(String afterJson) {
            this.afterJson = afterJson;
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder viewSessionId(String viewSessionId) {
            this.viewSessionId = viewSessionId;
            return this;
        }

        /** 登录方式：PASSWORD | SMS。 */
        public Builder loginMethod(String loginMethod) {
            this.loginMethod = loginMethod;
            return this;
        }

        /** 租户识别方式：SUBDOMAIN | INPUT。 */
        public Builder tenantIdentifyMethod(String tenantIdentifyMethod) {
            this.tenantIdentifyMethod = tenantIdentifyMethod;
            return this;
        }

        /** 第二因素结果：PASS | FAIL | SKIP。 */
        public Builder secondFactorResult(String secondFactorResult) {
            this.secondFactorResult = secondFactorResult;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** 访问方式：VIEW_PLAIN | EXPORT | PRINT | DOWNLOAD | BATCH_QUERY。 */
        public Builder accessMethod(String accessMethod) {
            this.accessMethod = accessMethod;
            return this;
        }

        /** 涉及记录数。 */
        public Builder recordCount(Long recordCount) {
            this.recordCount = recordCount;
            return this;
        }

        /** 查询条件摘要（不含敏感明文）。 */
        public Builder queryDigest(String queryDigest) {
            this.queryDigest = queryDigest;
            return this;
        }

        /** 导出文件哈希。 */
        public Builder fileHash(String fileHash) {
            this.fileHash = fileHash;
            return this;
        }

        /** 平台访问：页面路径（租户视角内访问的医院端页面）。 */
        public Builder page(String page) {
            this.page = page;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(
                    type,
                    tenantId,
                    userId,
                    username,
                    displayName,
                    userType,
                    roleCodes,
                    module,
                    action,
                    eventType,
                    description,
                    objectType,
                    objectId,
                    result,
                    errorCode,
                    reason,
                    beforeJson,
                    afterJson,
                    ip,
                    userAgent,
                    traceId,
                    viewSessionId,
                    loginMethod,
                    tenantIdentifyMethod,
                    secondFactorResult,
                    sessionId,
                    accessMethod,
                    recordCount,
                    queryDigest,
                    fileHash,
                    page);
        }
    }
}
