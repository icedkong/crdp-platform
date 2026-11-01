package com.crdp.server.system.user;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * E06-T07 / PLT-ORG-006、008（platform-spec §6「组织架构、角色与用户」）+ A-WARD-002、C-WARD-003： 医院端用户管理。
 *
 * <p>覆盖：分页列表（关键字 / 科室 / 病区 / 角色 / 状态筛选，手机号脱敏）、新建（账号与手机号本院唯一，
 * 初始密码由系统生成、只随本次响应下发一次并强制首次登录改密）、编辑（主科室、角色、<b>负责病区 与负责科室多选</b>全量覆盖——「本人负责病区 / 本人负责科室」数据范围的取值来源，
 * 变更即递增权限版本号）、停用 / 启用 / 离职（离职不可逆；停用与离职立即吊销全部会话）、 重置密码（临时密码 + 强制改密 + 旧哈希入历史 + 会话全部失效 + 解除登录锁定）。
 *
 * <p>保护规则（platform-spec §6 业务规则）：不能停用 / 离职最后一名启用中的租户管理员，
 * 也不能在编辑里移除其租户管理员角色；模板角色不能直接分配给用户（先复制，PLT-ORG-005）； 用户不做物理删除。兼职科室、岗位 /
 * 职称、账号有效期与离职转办事件分别随字典（E06-T10）、 E07-T02、E06-T03 落地；解锁接口已由 E04-T05 提供（{@code UserAdminService}）。
 */
@Service
public class UserAccountAdminService {

    public static final String STATUS_ENABLED = "ENABLED";

    public static final String STATUS_DISABLED = "DISABLED";

    /** 离职（终态）：立即下线、保留行以供审计追溯，不可再启用或编辑回启用。 */
    public static final String STATUS_RESIGNED = "RESIGNED";

    private static final String TENANT_ADMIN_CODE = "TENANT_ADMIN";
    private static final String PHONE_PURPOSE = "sys-user-phone";
    private static final int USERNAME_MAX = 64;
    private static final int NAME_MAX = 64;
    private static final int STAFF_NO_MAX = 64;
    private static final int REASON_MAX = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 临时密码基准长度：四类字符齐备，必然满足平台下限（租户加严时按生效下限取大）。 */
    private static final int TEMP_PASSWORD_BASE_LENGTH = 16;

    /** 列表与详情的时间展示口径：ISO-8601 带时区（北京时间，与个人中心一致）。 */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;
    private final PasswordEncoder passwordEncoder;
    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final SecurityProperties securityProperties;
    private final AuditLogger audit;
    private final PermissionVersionStore permissionVersions;
    private final RandomGenerator random = new SecureRandom();

    public UserAccountAdminService(
            JdbcTemplate jdbc,
            CryptoService cryptoService,
            PasswordEncoder passwordEncoder,
            SessionStore sessionStore,
            LoginRateLimiter rateLimiter,
            SecurityProperties securityProperties,
            AuditLogger audit,
            PermissionVersionStore permissionVersions) {
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.securityProperties = securityProperties;
        this.audit = audit;
        this.permissionVersions = permissionVersions;
    }

    // ---------------------------------------------------------------- 视图与请求

    /** 用户列表行：手机号只回脱敏值（AGENTS 第 5 节，列表不提供明文）。 */
    public record UserView(
            long id,
            String username,
            String displayName,
            String staffNo,
            String phoneMasked,
            String mainDeptName,
            List<String> roleNames,
            String status,
            String createdAt) {}

    public record UserPage(List<UserView> items, long total, int pageNo, int pageSize) {}

    /** 编辑视图：负责病区（wardIds，A-WARD-002）与负责科室（deptScopeIds，C-WARD-003）为多选引用。 */
    public record UserDetail(
            long id,
            String username,
            String displayName,
            String staffNo,
            String phoneMasked,
            Long mainDeptId,
            String mainDeptName,
            List<Long> roleIds,
            List<Long> wardIds,
            List<Long> deptScopeIds,
            String status,
            String createdAt) {}

    /** 新建请求：账号与手机号本院唯一；wardIds / deptScopeIds 可空（不分配负责范围）。 */
    public record UserCreateRequest(
            String username,
            String displayName,
            String staffNo,
            String phone,
            Long mainDeptId,
            List<Long> roleIds,
            List<Long> wardIds,
            List<Long> deptScopeIds) {}

    /** 编辑请求（全量覆盖）。username 不在编辑接口改；phone 可空＝保持不变， 传值＝换绑（本院唯一校验同新建）。 */
    public record UserUpdateRequest(
            String displayName,
            String staffNo,
            String phone,
            Long mainDeptId,
            List<Long> roleIds,
            List<Long> wardIds,
            List<Long> deptScopeIds) {}

    /** 新建结果：初始密码只随本次响应下发一次（不落日志与审计），首次登录强制改密。 */
    public record CreatedUser(UserDetail user, String temporaryPassword) {}

    /** 状态变更：action=ENABLE|DISABLE|RESIGN；reason 可选（≤200，写审计）。 */
    public record StatusChange(String action, String reason) {}

    /** 重置密码结果：临时密码只在本次响应返回一次。 */
    public record ResetPasswordResult(long id, String username, String temporaryPassword) {}

    /** 病区候选（用户编辑页「负责病区」多选；病区字典维护属病区管理任务，这里只读启用项）。 */
    public record WardOption(long id, String code, String name) {}

    // ---------------------------------------------------------------- 查询

    /** 分页列表（PLT-ORG-006「按科室与状态筛选」；病区 / 角色筛选供病区页与角色页跳入）。 */
    public UserPage list(
            String keyword,
            Long deptId,
            Long wardId,
            Long roleId,
            String status,
            Integer pageNo,
            Integer pageSize) {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size =
                pageSize == null || pageSize < 1
                        ? DEFAULT_PAGE_SIZE
                        : Math.min(pageSize, MAX_PAGE_SIZE);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (u.username LIKE ? OR u.display_name LIKE ? OR u.staff_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (deptId != null && deptId > 0) {
            // 主科室或负责科室命中都算（负责科室决定「本人负责科室」范围，platform-spec §4）
            where.append(
                    " AND (u.main_dept_id = ? OR EXISTS (SELECT 1 FROM sys_user_dept_scope s"
                            + " WHERE s.user_id = u.id AND s.dept_id = ?))");
            args.add(deptId);
            args.add(deptId);
        }
        if (wardId != null && wardId > 0) {
            where.append(
                    " AND EXISTS (SELECT 1 FROM sys_user_ward w WHERE w.user_id = u.id AND w.ward_id = ?)");
            args.add(wardId);
        }
        if (roleId != null && roleId > 0) {
            where.append(
                    " AND EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = ?)");
            args.add(roleId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND u.status = ?");
            args.add(status.trim());
        }
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user u" + where, Long.class, args.toArray());
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT u.id, u.username, u.display_name, u.staff_no,"
                                + " u.phone, d.name AS main_dept_name, u.status, u.created_at"
                                + " FROM sys_user u LEFT JOIN sys_dept d ON d.id = u.main_dept_id"
                                + where
                                + " ORDER BY u.id DESC LIMIT "
                                + size
                                + " OFFSET "
                                + (page - 1) * size,
                        args.toArray());
        Map<Long, List<String>> roleNames =
                roleNamesByUser(rows.stream().map(row -> num(row.get("id"))).toList());
        List<UserView> items =
                rows.stream()
                        .map(
                                row ->
                                        new UserView(
                                                num(row.get("id")),
                                                str(row.get("username")),
                                                str(row.get("display_name")),
                                                strOrNull(row.get("staff_no")),
                                                maskedPhone(
                                                        decryptOrNull(strOrNull(row.get("phone")))),
                                                strOrNull(row.get("main_dept_name")),
                                                roleNames.getOrDefault(
                                                        num(row.get("id")), List.of()),
                                                str(row.get("status")),
                                                formatTime(row.get("created_at"))))
                        .toList();
        return new UserPage(items, total == null ? 0 : total, page, size);
    }

    /** 用户详情（编辑页回显）。不存在 40400（不暴露其他租户的对象）。 */
    public UserDetail detail(long userId) {
        Map<String, Object> row = userRow(userId);
        return toDetail(row);
    }

    /** 病区候选（启用中的病区；停用病区不出现在选择器，platform-spec §6 业务规则）。 */
    public List<WardOption> wardOptions() {
        return jdbc
                .queryForList(
                        "SELECT id, code, name FROM ward WHERE status = 'ENABLED' ORDER BY code",
                        (Object[]) new Object[0])
                .stream()
                .map(
                        row ->
                                new WardOption(
                                        num(row.get("id")),
                                        str(row.get("code")),
                                        str(row.get("name"))))
                .toList();
    }

    /**
     * 用户选择器候选（E06-T09 / PLT-ORG-012，登录即可调用）：本院<b>启用</b>用户， 只返回 id、账号、显示名与主科室名（业务模块选责任人 / 研究成员共用，
     * 原组织接口 /orgs/user-options 为暂行实现、本任务起替换）。 keyword 命中账号 / 姓名 / 工号；deptId 命中主科室或负责科室；最多 50 条。
     */
    public List<UserOptionView> userOptions(String keyword, Long deptId) {
        StringBuilder where = new StringBuilder(" WHERE u.status = 'ENABLED'");
        List<Object> args = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            where.append(" AND (u.username LIKE ? OR u.display_name LIKE ? OR u.staff_no LIKE ?)");
            String like = "%" + escapeLike(kw) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (deptId != null && deptId > 0) {
            // 主科室或负责科室命中（与用户列表 deptId 筛选同口径）
            where.append(
                    " AND (u.main_dept_id = ? OR EXISTS (SELECT 1 FROM sys_user_dept_scope s"
                            + " WHERE s.user_id = u.id AND s.dept_id = ?))");
            args.add(deptId);
            args.add(deptId);
        }
        return jdbc
                .queryForList(
                        "SELECT u.id, u.username, u.display_name, d.name AS dept_name"
                                + " FROM sys_user u LEFT JOIN sys_dept d ON d.id = u.main_dept_id"
                                + where
                                + " ORDER BY u.display_name, u.id LIMIT 50",
                        args.toArray())
                .stream()
                .map(
                        row ->
                                new UserOptionView(
                                        num(row.get("id")),
                                        str(row.get("username")),
                                        str(row.get("display_name")),
                                        strOrNull(row.get("dept_name"))))
                .toList();
    }

    /** 选择器候选（PLT-ORG-012）：不含手机号等敏感字段，科室名用于同名人区分。 */
    public record UserOptionView(long id, String username, String displayName, String deptName) {}

    /** LIKE 关键字转义（% _ \），与组织候选暂行接口同做法。 */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---------------------------------------------------------------- 新建（PLT-ORG-006 新建用户流程）

    public CreatedUser create(UserCreateRequest request) {
        String username = normalizeUsername(request == null ? null : request.username());
        String displayName = requireDisplayName(request == null ? null : request.displayName());
        String staffNo = optionalStaffNo(request == null ? null : request.staffNo());
        String phone = requirePhone(request == null ? null : request.phone());
        Long mainDeptId = requireMainDeptId(request == null ? null : request.mainDeptId());
        LinkedHashSet<Long> roleIds =
                normalizeIds(request == null ? null : request.roleIds(), "roleIds");
        if (roleIds.isEmpty()) {
            throw bad("至少分配一个角色");
        }
        LinkedHashSet<Long> wardIds = normalizeNullableIds(request.wardIds(), "wardIds");
        LinkedHashSet<Long> deptScopeIds =
                normalizeNullableIds(request.deptScopeIds(), "deptScopeIds");
        validateRefs(mainDeptId, roleIds, wardIds, deptScopeIds);
        if (!jdbc.queryForList("SELECT id FROM sys_user WHERE username = ?", Long.class, username)
                .isEmpty()) {
            throw duplicate("账号已存在：" + username);
        }
        String phoneIdx = cryptoService.blindIndex(PHONE_PURPOSE, phone);
        List<Long> holder =
                jdbc.queryForList(
                        "SELECT id FROM sys_user WHERE phone_idx = ?", Long.class, phoneIdx);
        if (!holder.isEmpty()) {
            throw duplicate("该手机号已绑定本院其他账号：" + maskedPhone(phone));
        }
        String temporary =
                generateTemporaryPassword(username, effectiveMinPasswordLength(), random);
        try {
            jdbc.update(
                    "INSERT INTO sys_user (username, display_name, password_hash, status,"
                            + " must_change_password, staff_no, main_dept_id, phone, phone_idx)"
                            + " VALUES (?, ?, ?, 'ENABLED', 1, ?, ?, ?, ?)",
                    username,
                    displayName,
                    passwordEncoder.encode(temporary),
                    staffNo,
                    mainDeptId,
                    cryptoService.encrypt(PHONE_PURPOSE, phone),
                    phoneIdx);
        } catch (DuplicateKeyException ex) {
            throw duplicate("账号或手机号已绑定本院其他用户");
        }
        long userId = num(userRowByUsername(username).get("id"));
        replaceRoles(userId, roleIds);
        replaceWards(userId, wardIds);
        replaceDeptScopes(userId, deptScopeIds);
        audit(
                "USER_CREATE",
                "CREATE",
                "sys_user:" + username,
                "新建用户「"
                        + displayName
                        + "」（角色 "
                        + roleIds.size()
                        + " 个，负责病区 "
                        + wardIds.size()
                        + " 个，负责科室 "
                        + deptScopeIds.size()
                        + " 个）；初始密码只下发一次，首次登录强制改密");
        return new CreatedUser(detail(userId), temporary);
    }

    // ---------------------------------------------------------------- 编辑（含负责病区 / 负责科室）

    public UserDetail update(long userId, UserUpdateRequest request) {
        Map<String, Object> row = userRow(userId);
        String displayName = requireDisplayName(request == null ? null : request.displayName());
        String staffNo = optionalStaffNo(request == null ? null : request.staffNo());
        Long mainDeptId = requireMainDeptId(request == null ? null : request.mainDeptId());
        LinkedHashSet<Long> roleIds =
                normalizeIds(request == null ? null : request.roleIds(), "roleIds");
        if (roleIds.isEmpty()) {
            throw bad("至少分配一个角色");
        }
        LinkedHashSet<Long> wardIds = normalizeNullableIds(request.wardIds(), "wardIds");
        LinkedHashSet<Long> deptScopeIds =
                normalizeNullableIds(request.deptScopeIds(), "deptScopeIds");
        validateRefs(mainDeptId, roleIds, wardIds, deptScopeIds);
        String phoneCipher = null;
        String phoneIdx = null;
        if (request != null && request.phone() != null && !request.phone().isBlank()) {
            String phone = requirePhone(request.phone());
            phoneIdx = cryptoService.blindIndex(PHONE_PURPOSE, phone);
            List<Long> holder =
                    jdbc.queryForList(
                            "SELECT id FROM sys_user WHERE phone_idx = ?", Long.class, phoneIdx);
            if (!holder.isEmpty() && holder.get(0) != userId) {
                throw duplicate("该手机号已绑定本院其他账号：" + maskedPhone(phone));
            }
            phoneCipher = cryptoService.encrypt(PHONE_PURPOSE, phone);
        }
        // 不能通过编辑移除最后一名启用租户管理员的租户管理员角色（platform-spec §6 业务规则）
        if (isBuiltInTenantAdminMember(userId) && !wouldKeepTenantAdmin(roleIds)) {
            guardLastEnabledTenantAdmin(userId, "移除其租户管理员角色");
        }
        jdbc.update(
                "UPDATE sys_user SET display_name = ?, staff_no = ?, main_dept_id = ? WHERE id = ?",
                displayName,
                staffNo,
                mainDeptId,
                userId);
        if (phoneCipher != null) {
            try {
                jdbc.update(
                        "UPDATE sys_user SET phone = ?, phone_idx = ? WHERE id = ?",
                        phoneCipher,
                        phoneIdx,
                        userId);
            } catch (DuplicateKeyException ex) {
                throw duplicate("该手机号已绑定本院其他账号");
            }
        }
        replaceRoles(userId, roleIds);
        replaceWards(userId, wardIds);
        replaceDeptScopes(userId, deptScopeIds);
        // 负责病区 / 负责科室 / 角色变化立即生效：递增权限版本（platform-spec §4、OQ-40 ①）
        bump(userId);
        audit(
                "USER_UPDATE",
                "UPDATE",
                "sys_user:" + str(row.get("username")),
                "编辑用户「"
                        + displayName
                        + "」（角色 "
                        + roleIds.size()
                        + " 个，负责病区 "
                        + wardIds.size()
                        + " 个，负责科室 "
                        + deptScopeIds.size()
                        + " 个）");
        return detail(userId);
    }

    // ---------------------------------------------------------------- 状态（PLT-ORG-006：停用 / 启用 / 离职）

    public UserDetail changeStatus(long userId, StatusChange request) {
        Map<String, Object> row = userRow(userId);
        String target = targetStatus(request);
        String current = str(row.get("status"));
        ensureTransition(current, target);
        if (STATUS_ENABLED.equals(current) && isBuiltInTenantAdminMember(userId)) {
            guardLastEnabledTenantAdmin(userId, STATUS_RESIGNED.equals(target) ? "离职" : "停用");
        }
        jdbc.update("UPDATE sys_user SET status = ? WHERE id = ?", target, userId);
        if (STATUS_DISABLED.equals(target) || STATUS_RESIGNED.equals(target)) {
            // 停用 / 离职立即下线（platform-spec §6 业务规则）
            sessionStore.revokeAllForUser(sessionProxy(row), null);
        }
        bump(userId);
        audit(
                "USER_STATUS",
                "STATUS",
                "sys_user:" + str(row.get("username")),
                statusText(current)
                        + " → "
                        + statusText(target)
                        + "「"
                        + str(row.get("display_name"))
                        + "」"
                        + reasonSuffix(request)
                        + (STATUS_DISABLED.equals(target) || STATUS_RESIGNED.equals(target)
                                ? "；该账号全部会话已下线"
                                : ""));
        return detail(userId);
    }

    // ---------------------------------------------------------------- 重置密码（PLT-ORG-008）

    /** 生成一次性临时密码（响应只返回一次）：置「首次登录强制改密」，旧哈希入历史表， 全部会话失效，并解除登录锁定。 */
    public ResetPasswordResult resetPassword(long userId) {
        Map<String, Object> row = userRow(userId);
        String username = str(row.get("username"));
        String temporary =
                generateTemporaryPassword(username, effectiveMinPasswordLength(), random);
        String currentHash = strOrNull(row.get("password_hash"));
        jdbc.update(
                "UPDATE sys_user SET password_hash = ?, must_change_password = 1,"
                        + " password_updated_at = ? WHERE id = ?",
                passwordEncoder.encode(temporary),
                LocalDateTime.now(),
                userId);
        if (currentHash != null && !currentHash.isBlank()) {
            jdbc.update(
                    "INSERT INTO sys_password_history (user_id, password_hash) VALUES (?, ?)",
                    userId,
                    currentHash);
        }
        sessionStore.revokeAllForUser(sessionProxy(row), null);
        rateLimiter.unlock("H:" + currentTenantCode() + ":" + username.toLowerCase(Locale.ROOT));
        audit(
                "USER_RESET_PASSWORD",
                "UPDATE",
                "sys_user:" + username,
                "重置用户密码（临时密码只下发一次），该账号全部会话已失效并解除登录锁定");
        return new ResetPasswordResult(userId, username, temporary);
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /** 账号：必填、≤64、字母数字下划线短横线与点（与演示账号 nurse.demo 同字符集），租户内唯一、创建后不可改。 */
    static String normalizeUsername(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("账号必填");
        }
        String username = raw.trim();
        if (username.length() > USERNAME_MAX) {
            throw bad("账号过长（最多 " + USERNAME_MAX + " 个字符）");
        }
        if (!username.matches("[A-Za-z0-9._-]+")) {
            throw bad("账号只允许字母、数字、下划线、短横线与点");
        }
        return username;
    }

    static String requireDisplayName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("姓名必填");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw bad("姓名过长（最多 " + NAME_MAX + " 个字符）");
        }
        return name;
    }

    /** 工号：可空（≤64；预留用于后续单点登录映射）。 */
    static String optionalStaffNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String staffNo = raw.trim();
        if (staffNo.length() > STAFF_NO_MAX) {
            throw bad("工号过长（最多 " + STAFF_NO_MAX + " 个字符）");
        }
        return staffNo;
    }

    /** 大陆手机号口径（与登录短信、个人中心换绑一致，E04-T04 / E06-T11）。 */
    static String requirePhone(String raw) {
        if (raw == null || !raw.trim().matches("1\\d{10}")) {
            throw bad("手机号格式不正确（11 位，1 开头）");
        }
        return raw.trim();
    }

    /** 手机号脱敏：保留前 3 位与后 4 位（列表与审计不落明文，AGENTS 第 5 节）。 */
    static String maskedPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 解析状态动作（顺带校验 reason 长度）。 */
    static String targetStatus(StatusChange request) {
        String action = request == null || request.action() == null ? "" : request.action().trim();
        String target;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "ENABLE" -> target = STATUS_ENABLED;
            case "DISABLE" -> target = STATUS_DISABLED;
            case "RESIGN" -> target = STATUS_RESIGNED;
            default -> throw bad("action 只允许 ENABLE / DISABLE / RESIGN");
        }
        if (reasonSuffix(request).length() > REASON_MAX) {
            throw bad("变更原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return target;
    }

    /** 状态机：启用 ⇄ 停用，启用 → 离职（终态）；其余流转 40901。 */
    static void ensureTransition(String current, String target) {
        if (STATUS_ENABLED.equals(current) && STATUS_DISABLED.equals(target)) {
            return;
        }
        if (STATUS_ENABLED.equals(current) && STATUS_RESIGNED.equals(target)) {
            return;
        }
        if (STATUS_DISABLED.equals(current) && STATUS_ENABLED.equals(target)) {
            return;
        }
        if (STATUS_RESIGNED.equals(current)) {
            throw conflict("离职为终态，不能再启用或变更（审计与稽查轨迹保留）");
        }
        throw conflict("当前状态「" + statusText(current) + "」不能变更为「" + statusText(target) + "」");
    }

    static String reasonSuffix(StatusChange request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            return "";
        }
        if (reason.length() > REASON_MAX) {
            throw bad("变更原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return "，原因：" + reason;
    }

    /** 必填编号清单（角色）：null 或空 → 40001 由调用方给业务文案；非法项 40001。 */
    static LinkedHashSet<Long> normalizeIds(List<Long> raw, String field) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (raw == null) {
            throw bad(field + " 不能为空");
        }
        for (Long id : raw) {
            if (id == null || id <= 0) {
                throw bad(field + " 含无效编号");
            }
            ids.add(id);
        }
        return ids;
    }

    /** 可空清单（负责病区 / 负责科室）：null = 清空。 */
    static LinkedHashSet<Long> normalizeNullableIds(List<Long> raw, String field) {
        if (raw == null) {
            return new LinkedHashSet<>();
        }
        return normalizeIds(raw, field);
    }

    static Long requireMainDeptId(Long raw) {
        if (raw == null || raw <= 0) {
            throw bad("主科室必填");
        }
        return raw;
    }

    /**
     * 临时密码生成（纯函数注入 Random 便于测试）：四类字符各至少 1 个、长度取 max(16, 生效下限)， 不含账号（platform-spec §7
     * 密码策略；与平台账号重置同口径，PLT-ROLE-001）。
     */
    static String generateTemporaryPassword(
            String username, int minLength, RandomGenerator random) {
        int length = Math.max(TEMP_PASSWORD_BASE_LENGTH, minLength);
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;
        StringBuilder password = new StringBuilder();
        password.append(upper.charAt(random.nextInt(upper.length())));
        password.append(lower.charAt(random.nextInt(lower.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));
        password.append(special.charAt(random.nextInt(special.length())));
        while (password.length() < length) {
            password.append(all.charAt(random.nextInt(all.length())));
        }
        // Fisher–Yates 洗牌，避免前四位固定类别
        for (int i = password.length() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = password.charAt(i);
            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j, tmp);
        }
        // 双保险：命中「不得包含账号」时替换为随机段
        if (username != null
                && !username.isBlank()
                && password.toString()
                        .toLowerCase(Locale.ROOT)
                        .contains(username.toLowerCase(Locale.ROOT))) {
            password.setLength(0);
            while (password.length() < length) {
                password.append(all.charAt(random.nextInt(all.length())));
            }
        }
        return password.toString();
    }

    static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case STATUS_ENABLED -> "启用";
            case STATUS_DISABLED -> "停用";
            case STATUS_RESIGNED -> "离职";
            default -> status;
        };
    }

    // ---------------------------------------------------------------- 内部

    private UserDetail toDetail(Map<String, Object> row) {
        long userId = num(row.get("id"));
        return new UserDetail(
                userId,
                str(row.get("username")),
                str(row.get("display_name")),
                strOrNull(row.get("staff_no")),
                maskedPhone(decryptOrNull(strOrNull(row.get("phone")))),
                nullableNum(row.get("main_dept_id")),
                strOrNull(row.get("main_dept_name")),
                jdbc.queryForList(
                        "SELECT role_id FROM sys_user_role WHERE user_id = ? ORDER BY role_id",
                        Long.class,
                        userId),
                jdbc.queryForList(
                        "SELECT ward_id FROM sys_user_ward WHERE user_id = ? ORDER BY ward_id",
                        Long.class,
                        userId),
                jdbc.queryForList(
                        "SELECT dept_id FROM sys_user_dept_scope WHERE user_id = ? ORDER BY dept_id",
                        Long.class,
                        userId),
                str(row.get("status")),
                formatTime(row.get("created_at")));
    }

    private Map<String, Object> userRow(long userId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT u.id, u.username, u.display_name, u.staff_no,"
                                + " u.phone, u.main_dept_id AS mainDeptId, d.name AS main_dept_name,"
                                + " u.status, u.password_hash, u.created_at"
                                + " FROM sys_user u LEFT JOIN sys_dept d ON d.id = u.main_dept_id"
                                + " WHERE u.id = ?",
                        userId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "用户不存在或不在本院");
        }
        return rows.get(0);
    }

    private Map<String, Object> userRowByUsername(String username) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT u.id, u.username, u.display_name, u.staff_no,"
                                + " u.phone, u.main_dept_id AS mainDeptId, d.name AS main_dept_name,"
                                + " u.status, u.password_hash, u.created_at"
                                + " FROM sys_user u LEFT JOIN sys_dept d ON d.id = u.main_dept_id"
                                + " WHERE u.username = ?",
                        username);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "用户不存在或不在本院");
        }
        return rows.get(0);
    }

    /** 主科室、角色（不能是模板）、负责病区、负责科室的存在性校验；未知引用 40400。 */
    private void validateRefs(
            Long mainDeptId,
            LinkedHashSet<Long> roleIds,
            LinkedHashSet<Long> wardIds,
            LinkedHashSet<Long> deptScopeIds) {
        LinkedHashSet<Long> deptIds = new LinkedHashSet<>();
        deptIds.add(mainDeptId);
        deptIds.addAll(deptScopeIds);
        List<Map<String, Object>> depts = rowsById("sys_dept", "id, name", deptIds);
        if (depts.size() != deptIds.size()) {
            throw new ApiException(404, 40400, "科室不存在：" + unknownIds(deptIds, depts));
        }
        if (!wardIds.isEmpty()) {
            List<Map<String, Object>> wards = rowsById("ward", "id, code, name", wardIds);
            if (wards.size() != wardIds.size()) {
                throw new ApiException(404, 40400, "病区不存在：" + unknownIds(wardIds, wards));
            }
        }
        List<Map<String, Object>> roles =
                rowsById("sys_role", "id, code, name, role_type", roleIds);
        if (roles.size() != roleIds.size()) {
            throw new ApiException(404, 40400, "角色不存在：" + unknownIds(roleIds, roles));
        }
        for (Map<String, Object> role : roles) {
            if ("TEMPLATE".equals(str(role.get("role_type")))) {
                throw conflict(
                        "模板角色「" + str(role.get("name")) + "」不能直接分配给用户，请先复制成自定义角色（PLT-ORG-005）");
            }
        }
    }

    private List<Map<String, Object>> rowsById(
            String table, String columns, LinkedHashSet<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        StringBuilder sql =
                new StringBuilder("SELECT " + columns + " FROM " + table + " WHERE id IN (");
        sql.append("?, ".repeat(ids.size() - 1)).append("?)");
        return jdbc.queryForList(sql.toString(), ids.toArray());
    }

    private static List<Long> unknownIds(
            LinkedHashSet<Long> wanted, List<Map<String, Object>> found) {
        LinkedHashSet<Long> known = new LinkedHashSet<>();
        for (Map<String, Object> row : found) {
            known.add(num(row.get("id")));
        }
        return wanted.stream().filter(id -> !known.contains(id)).toList();
    }

    private void replaceRoles(long userId, LinkedHashSet<Long> roleIds) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (Long roleId : roleIds) {
            jdbc.update(
                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        }
    }

    private void replaceWards(long userId, LinkedHashSet<Long> wardIds) {
        jdbc.update("DELETE FROM sys_user_ward WHERE user_id = ?", userId);
        for (Long wardId : wardIds) {
            jdbc.update(
                    "INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)", userId, wardId);
        }
    }

    private void replaceDeptScopes(long userId, LinkedHashSet<Long> deptIds) {
        jdbc.update("DELETE FROM sys_user_dept_scope WHERE user_id = ?", userId);
        for (Long deptId : deptIds) {
            jdbc.update(
                    "INSERT INTO sys_user_dept_scope (user_id, dept_id) VALUES (?, ?)",
                    userId,
                    deptId);
        }
    }

    private Map<Long, List<String>> roleNamesByUser(List<Long> userIds) {
        Map<Long, List<String>> byUser = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return byUser;
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT ur.user_id, r.name FROM sys_user_role ur"
                                + " JOIN sys_role r ON r.id = ur.role_id"
                                + " WHERE r.status = 'ENABLED' AND ur.user_id IN (");
        sql.append("?, ".repeat(userIds.size() - 1)).append("?) ORDER BY ur.user_id, r.id");
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), userIds.toArray())) {
            byUser.computeIfAbsent(num(row.get("user_id")), key -> new ArrayList<>())
                    .add(str(row.get("name")));
        }
        return byUser;
    }

    /** 目标用户是否挂内置租户管理员角色。 */
    private boolean isBuiltInTenantAdminMember(long userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                                + " WHERE ur.user_id = ? AND r.code = 'TENANT_ADMIN' AND r.built_in = 1",
                        Integer.class,
                        userId);
        return count != null && count > 0;
    }

    /** 候选角色里是否保留租户管理员（编辑保护用）。 */
    private boolean wouldKeepTenantAdmin(LinkedHashSet<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return false;
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT COUNT(*) FROM sys_role WHERE built_in = 1 AND code = 'TENANT_ADMIN'"
                                + " AND id IN (");
        sql.append("?, ".repeat(roleIds.size() - 1)).append("?)");
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, roleIds.toArray());
        return count != null && count > 0;
    }

    /** 不能停用 / 离职 / 降级最后一名启用中的租户管理员（platform-spec §6 业务规则）。 */
    private void guardLastEnabledTenantAdmin(long userId, String action) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.id"
                                + " JOIN sys_role r ON r.id = ur.role_id"
                                + " WHERE r.code = 'TENANT_ADMIN' AND r.built_in = 1"
                                + " AND u.status = 'ENABLED'",
                        Integer.class);
        if (count != null && count <= 1) {
            throw conflict("不能" + action + "：该用户是最后一名启用中的租户管理员");
        }
    }

    /** 生效的密码最小长度：平台下限与租户加严取大（PLT-AUTH-006；租户项 cfg_param security.password.min-length）。 */
    private int effectiveMinPasswordLength() {
        List<String> values =
                jdbc.queryForList(
                        "SELECT param_value FROM cfg_param WHERE param_key = 'security.password.min-length'",
                        String.class);
        int override = 0;
        if (!values.isEmpty() && values.get(0) != null && !values.get(0).isBlank()) {
            try {
                override = Integer.parseInt(values.get(0).trim());
            } catch (NumberFormatException ex) {
                override = 0;
            }
        }
        return Math.max(securityProperties.getPasswordMinLength(), override);
    }

    /** 会话吊销用代理（只承载端别 + 租户 + 用户定位字段，与平台账号重置同做法）。 */
    private SessionPrincipal sessionProxy(Map<String, Object> row) {
        TenantContext.State state = TenantContext.require();
        return new SessionPrincipal(
                null,
                "HOSPITAL",
                state.tenantId(),
                state.tenantCode(),
                null,
                num(row.get("id")),
                str(row.get("username")),
                str(row.get("display_name")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private void bump(long userId) {
        TenantContext.State state = TenantContext.require();
        permissionVersions.bump("HOSPITAL", state.tenantId(), userId);
    }

    private void audit(String eventType, String action, String target, String detail) {
        audit.record(
                new AuditEvent(
                        eventType,
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        action,
                        target,
                        detail));
    }

    private static String currentTenantCode() {
        return TenantContext.current()
                .map(state -> state.tenantCode() == null ? "" : state.tenantCode())
                .orElse("");
    }

    private String decryptOrNull(String cipher) {
        return cipher == null ? null : cryptoService.decrypt(cipher);
    }

    private static String formatTime(Object value) {
        if (value == null) {
            return null;
        }
        LocalDateTime time =
                value instanceof java.sql.Timestamp ts
                        ? ts.toLocalDateTime()
                        : (LocalDateTime) value;
        return TIME.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static long num(Object value) {
        return ((Number) Objects.requireNonNull(value)).longValue();
    }

    private static Long nullableNum(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static ApiException duplicate(String message) {
        return new ApiException(409, 40902, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, 40901, message);
    }
}
