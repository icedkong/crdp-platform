package com.crdp.system.event;

import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * E06-T03 / PLT-ORG-013：组织与用户变更事件（租户库 sys_outbox）。离职、停用（用户 / 科室 / 院区）、
 * 调岗（主科室变化）与角色变化在同一事务内发布，供业务模块（随访的责任人转交等，E14+）订阅； 订阅方<b>不自动改派</b>，只提示管理员（OQ-10）。
 *
 * <p>事件类型与负载是跨模块契约（docs/api/system.md §9）：信封统一 {@code tenantCode / operatorId / occurredAt}，时间
 * ISO-8601 北京时间。病区停用事件随病区管理任务加入同一张表。
 */
@Component
public class OrgUserEvents {

    public static final String USER_DISABLED = "user.disabled";
    public static final String USER_RESIGNED = "user.resigned";
    public static final String USER_DEPT_CHANGED = "user.dept_changed";
    public static final String USER_ROLES_CHANGED = "user.roles_changed";
    public static final String DEPT_DISABLED = "dept.disabled";
    public static final String ORG_DISABLED = "org.disabled";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Shanghai"));

    private final OutboxPublisher outbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrgUserEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /** 用户停用（会话已下线；待办转交由订阅方提示，OQ-10）。 */
    public void userDisabled(long userId, String username, String displayName, String reason) {
        Map<String, Object> payload = userEnvelope(userId, username, displayName, reason);
        publish(USER_DISABLED, payload);
    }

    /** 用户离职（终态）。 */
    public void userResigned(long userId, String username, String displayName, String reason) {
        Map<String, Object> payload = userEnvelope(userId, username, displayName, reason);
        publish(USER_RESIGNED, payload);
    }

    /** 调岗：主科室变化（old / new 同时为编码与名称，供订阅方直接展示）。 */
    public void userDeptChanged(
            long userId,
            String username,
            String displayName,
            Long oldDeptId,
            String oldDeptName,
            Long newDeptId,
            String newDeptName) {
        Map<String, Object> payload = userEnvelope(userId, username, displayName, null);
        payload.put("previousMainDept", ref(oldDeptId, oldDeptName));
        payload.put("newMainDept", ref(newDeptId, newDeptName));
        publish(USER_DEPT_CHANGED, payload);
    }

    /** 角色变化（增删角色的编码清单）。 */
    public void userRolesChanged(
            long userId,
            String username,
            String displayName,
            List<String> previousRoleCodes,
            List<String> newRoleCodes) {
        Map<String, Object> payload = userEnvelope(userId, username, displayName, null);
        payload.put("previousRoleCodes", previousRoleCodes);
        payload.put("newRoleCodes", newRoleCodes);
        publish(USER_ROLES_CHANGED, payload);
    }

    /** 科室停用（成员与引用仍在，业务选择器不再出现该科室）。 */
    public void deptDisabled(
            long deptId, String code, String name, long orgId, String orgName, String reason) {
        Map<String, Object> payload = envelope(reason);
        payload.put("deptId", deptId);
        payload.put("deptCode", code);
        payload.put("deptName", name);
        payload.put("orgId", orgId);
        payload.put("orgName", orgName);
        publish(DEPT_DISABLED, payload);
    }

    /** 院区停用（要求其下已无启用科室）。 */
    public void orgDisabled(long orgId, String code, String name, String reason) {
        Map<String, Object> payload = envelope(reason);
        payload.put("orgId", orgId);
        payload.put("orgCode", code);
        payload.put("orgName", name);
        publish(ORG_DISABLED, payload);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> userEnvelope(
            long userId, String username, String displayName, String reason) {
        Map<String, Object> payload = envelope(reason);
        payload.put("userId", userId);
        payload.put("username", username);
        payload.put("displayName", displayName);
        return payload;
    }

    private Map<String, Object> envelope(String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        TenantContext.current().ifPresent(state -> payload.put("tenantCode", state.tenantCode()));
        payload.put(
                "operatorId",
                CurrentSession.current().map(principal -> principal.userId()).orElse(null));
        payload.put("occurredAt", TIME.format(java.time.Instant.now()));
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason.trim());
        }
        return payload;
    }

    private static Map<String, Object> ref(Long id, String name) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id);
        ref.put("name", name);
        return ref;
    }

    private void publish(String type, Map<String, Object> payload) {
        try {
            outbox.publish(type, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            // LinkedHashMap 无特殊类型，序列化不应失败；防御性兜底为最小合法 JSON
            outbox.publish(type, "{\"error\":\"payload-serialize-failed\"}");
        }
    }
}
