package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.PasswordPolicy;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 平台账号管理（E06-T04 / PLT-ROLE-001，docs/api/platform.md §5「平台端 · 平台账号管理」）。
 *
 * <p>账号类型模型（PLT-ROLE-001）：平台账号存平台库 plt_account、租户账号存各自租户库 sys_user，
 * 类型由存放位置区分、决定登录入口（/api/platform/auth/login 与 /api/hospital/auth/login 分立）与
 * 可授予的角色范围——本服务的角色绑定只接受平台角色，租户角色在租户库内、这里不可见。
 *
 * <p>关键约束：新建平台账号强制绑定手机号（双因素认证用，密文 purpose=plt-account-phone + 盲索引 phone_idx
 * 平台库内唯一）；初始密码按平台密码策略校验并置「首次登录强制改密」；支持停用、重置密码 （生成一次性临时密码 + 强制改密 + 全部会话失效 + 解除锁定）、解锁（PLT-AUTH-007）。
 *
 * <p>平台管理员至少保留 2 个启用账号（platform-spec §1「平台管理员至少 2 人（已确认）」，双人复核 依赖）：停用或摘除角色导致启用平台管理员不足 2
 * 个时拒绝（40901）。
 *
 * <p>负责租户多选（PLT-VIEW-006，E20-T01）：plt_account_tenant 整体替换；租户范围为「负责的租户」的角色只能进入
 * 这些租户；负责关系变更即时生效——该账号进行中的视角会话按「权限变更」立即结束（platform-spec §8 业务规则）。 账号角色 / 状态变化与密码重置同样结束视角会话。
 */
@Service
public class PlatformAccountService {

    /** 平台账号名：小写字母开头，小写字母 / 数字 / 点 / 下划线 / 连字符，2–64 位（与种子 platform.admin 同风格）。 */
    static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9._-]{1,63}");

    /** 手机号：国内手机号 11 位（双因素短信）。 */
    static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** 平台管理员至少保留的启用账号数（platform-spec §1，已确认）。 */
    static final int MIN_ENABLED_ADMINS = 2;

    static final String ADMIN_ROLE_CODE = "PLATFORM_ADMIN";

    /** 临时密码长度：四类字符齐备，策略校验必然通过（只在响应里出现一次，不落日志与审计）。 */
    static final int TEMP_PASSWORD_LENGTH = 16;

    private static final String PHONE_PURPOSE = "plt-account-phone";

    private static final int NAME_MAX = 64;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate platform;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;
    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final SecurityProperties securityProperties;
    private final PermissionVersionStore permissionVersions;
    private final AuditLogger audit;
    private final TenantViewService tenantView;

    public PlatformAccountService(
            JdbcTemplate platform,
            PasswordEncoder passwordEncoder,
            CryptoService cryptoService,
            SessionStore sessionStore,
            LoginRateLimiter rateLimiter,
            SecurityProperties securityProperties,
            PermissionVersionStore permissionVersions,
            AuditLogger audit,
            TenantViewService tenantView) {
        this.platform = platform;
        this.passwordEncoder = passwordEncoder;
        this.cryptoService = cryptoService;
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.securityProperties = securityProperties;
        this.permissionVersions = permissionVersions;
        this.audit = audit;
        this.tenantView = tenantView;
    }

    // ---------------------------------------------------------------- DTO

    /** 平台账号视图：手机号只回脱敏值（AGENTS 第 5 节，列表不提供明文）。 */
    public record AccountView(
            long id,
            String username,
            String displayName,
            String phoneMasked,
            String status,
            List<Long> roleIds,
            List<String> roleNames,
            OffsetDateTime createdAt) {}

    /**
     * 新建 / 维护请求。新建必填：username、displayName、phone、password（初始密码）、roleIds；
     * 维护可改：displayName、phone、roleIds、status（username 与密码不在维护接口改——密码走改密 / 重置）。
     */
    public record AccountSaveRequest(
            String username,
            String displayName,
            String phone,
            String password,
            List<Long> roleIds,
            String status) {}

    /** 重置密码结果：临时密码只在本次响应返回一次。 */
    public record ResetPasswordResult(long id, String username, String temporaryPassword) {}

    /** 负责租户视图（PLT-VIEW-006）：账号多选的租户（id + 名称供回显）。 */
    public record ResponsibleTenant(long tenantId, String tenantCode, String tenantName) {}

    /** 按租户反查负责人（platform-spec §8 主要页面「平台账号管理 · 负责租户」）。 */
    public record ResponsibleOwner(
            long accountId, String username, String displayName, String status) {}

    /** 负责租户维护请求：整体替换（空清单 = 清空负责关系）。 */
    public record ResponsibleTenantsRequest(List<Long> tenantIds) {}

    /** 计算模型（校验与保存共用）：请求 + 库内现状快照。 */
    record AccountModel(
            long accountId,
            String username,
            String currentStatus,
            Set<Long> currentRoleIds,
            Set<String> currentRoleCodes,
            Set<Long> desiredRoleIds,
            Set<String> desiredRoleCodes,
            String desiredStatus) {

        boolean touchingAdminRole() {
            return currentRoleCodes.contains(ADMIN_ROLE_CODE)
                    || desiredRoleCodes.contains(ADMIN_ROLE_CODE);
        }

        boolean disabling() {
            return !"DISABLED".equals(currentStatus) && "DISABLED".equals(desiredStatus);
        }

        boolean grantsChanged() {
            return !currentRoleIds.equals(desiredRoleIds);
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 平台账号列表（含停用；平台账号量级小，不分页）。 */
    public List<AccountView> list() {
        return TenantContext.callAsPlatform(this::listInternal);
    }

    private List<AccountView> listInternal() {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, username, display_name, phone, status, created_at"
                                + " FROM plt_account ORDER BY id ASC");
        Map<Long, List<Long>> roleIds = roleIdsByAccount();
        Map<Long, List<String>> roleNames = roleNamesByAccount();
        List<AccountView> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            result.add(
                    new AccountView(
                            id,
                            str(row.get("username")),
                            str(row.get("display_name")),
                            maskedPhone(strOrNull(row.get("phone"))),
                            str(row.get("status")),
                            roleIds.getOrDefault(id, List.of()),
                            roleNames.getOrDefault(id, List.of()),
                            offsetDateTime(row.get("created_at"))));
        }
        return result;
    }

    /** 账号详情。 */
    public AccountView get(long accountId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireAccount(accountId);
                    long id = ((Number) row.get("id")).longValue();
                    return new AccountView(
                            id,
                            str(row.get("username")),
                            str(row.get("display_name")),
                            maskedPhone(strOrNull(row.get("phone"))),
                            str(row.get("status")),
                            roleIdsOf(id),
                            roleNamesOf(id),
                            offsetDateTime(row.get("created_at")));
                });
    }

    // ---------------------------------------------------------------- 新建 / 维护

    /** 新建平台账号（PLT-ROLE-001）：手机号必填，初始密码按策略校验并强制首次改密。 */
    public AccountView create(AccountSaveRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    requireUniqueUsername(request);
                    String phone = requirePhone(request.phone(), null);
                    String username = request.username().trim();
                    validateDisplayName(request.displayName());
                    validateInitialPassword(request.password(), username);
                    AccountModel model =
                            modelFor(0L, username, null, Set.of(), request.roleIds(), "ENABLED");
                    validateRoles(model);
                    String hash = passwordEncoder.encode(request.password());
                    String phoneCipher = cryptoService.encrypt(PHONE_PURPOSE, phone);
                    String phoneIdx = cryptoService.blindIndex(PHONE_PURPOSE, phone);
                    requirePhoneFree(phoneIdx, null);
                    long accountId =
                            insertAccount(
                                    username,
                                    request.displayName().trim(),
                                    hash,
                                    phoneCipher,
                                    phoneIdx);
                    saveRoles(accountId, model.desiredRoleIds());
                    audit.record(
                            new AuditEvent(
                                    "ACCOUNT_CREATE",
                                    operatorId(),
                                    "CREATE",
                                    "plt_account:" + username,
                                    "新建平台账号，绑定平台角色 "
                                            + String.join("、", model.desiredRoleCodes())
                                            + "，已绑定双因素手机号"));
                    return get(accountId);
                });
    }

    /**
     * 维护平台账号：展示名、手机号、角色、状态（停用 / 启用）。角色或状态变化递增该账号权限版本 （PLT-PERM-010 / OQ-40
     * ①，在线会话下一次请求重算；停用账号在重算时被剔除）。
     */
    public AccountView update(long accountId, AccountSaveRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> before = requireAccount(accountId);
                    long id = ((Number) before.get("id")).longValue();
                    String username = str(before.get("username"));
                    String currentStatus = str(before.get("status"));
                    validateDisplayName(request.displayName());
                    String phone =
                            request.phone() == null ? null : requirePhone(request.phone(), id);
                    Set<Long> currentRoleIds = new LinkedHashSet<>(roleIdsOf(id));
                    Set<String> currentRoleCodes =
                            new LinkedHashSet<>(roleCodes(id, currentRoleIds));
                    List<Long> desiredRoleIdsRaw =
                            request.roleIds() == null
                                    ? List.copyOf(currentRoleIds)
                                    : request.roleIds();
                    AccountModel model =
                            modelFor(
                                    id,
                                    username,
                                    currentStatus,
                                    currentRoleIds,
                                    desiredRoleIdsRaw,
                                    request.status());
                    validateRoles(model);
                    String desiredStatus = model.desiredStatus();
                    if (model.touchingAdminRole() || currentRoleCodes.contains(ADMIN_ROLE_CODE)) {
                        ensureEnoughEnabledAdmins(model, username);
                    }
                    if (phone != null) {
                        String phoneCipher = cryptoService.encrypt(PHONE_PURPOSE, phone);
                        String phoneIdx = cryptoService.blindIndex(PHONE_PURPOSE, phone);
                        requirePhoneFree(phoneIdx, id);
                        platform.update(
                                "UPDATE plt_account SET display_name = ?, phone = ?, phone_idx = ?"
                                        + " WHERE id = ?",
                                request.displayName().trim(),
                                phoneCipher,
                                phoneIdx,
                                id);
                    } else {
                        platform.update(
                                "UPDATE plt_account SET display_name = ? WHERE id = ?",
                                request.displayName().trim(),
                                id);
                    }
                    if (!currentRoleIds.equals(model.desiredRoleIds())) {
                        saveRoles(id, model.desiredRoleIds());
                    }
                    if (!currentStatus.equals(desiredStatus)) {
                        platform.update(
                                "UPDATE plt_account SET status = ? WHERE id = ?",
                                desiredStatus,
                                id);
                    }
                    if (model.grantsChanged() || !currentStatus.equals(desiredStatus)) {
                        refreshSessions(id);
                        // 角色 / 状态变化即时生效：进行中的视角会话按「权限变更」立即结束（platform-spec §8）
                        tenantView.endForAccount(id);
                    }
                    String detail =
                            "维护平台账号，平台角色 "
                                    + String.join("、", model.desiredRoleCodes())
                                    + "，状态 "
                                    + currentStatus
                                    + " → "
                                    + desiredStatus;
                    audit.record(
                            new AuditEvent(
                                    "ACCOUNT_UPDATE",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_account:" + username,
                                    detail));
                    return get(id);
                });
    }

    /** 重置密码（platform:account:reset）：生成一次性临时密码（响应只返回一次），置「首次登录强制改密」， 旧哈希入历史表，该账号全部会话失效，同时解除登录锁定。 */
    public ResetPasswordResult resetPassword(long accountId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> account = requireAccount(accountId);
                    long id = ((Number) account.get("id")).longValue();
                    String username = str(account.get("username"));
                    String temporary = generateTemporaryPassword(username);
                    String currentHash = strOrNull(account.get("password_hash"));
                    platform.update(
                            "UPDATE plt_account SET password_hash = ?, must_change_password = 1,"
                                    + " password_updated_at = ? WHERE id = ?",
                            passwordEncoder.encode(temporary),
                            LocalDateTime.now(),
                            id);
                    if (currentHash != null && !currentHash.isBlank()) {
                        platform.update(
                                "INSERT INTO plt_password_history (account_id, password_hash)"
                                        + " VALUES (?, ?)",
                                id,
                                currentHash);
                    }
                    sessionStore.revokeAllForUser(targetPrincipal(id, username, account), null);
                    // 重置密码后该账号全部会话失效（含进行中的租户视角会话，按权限变更收尾）
                    tenantView.endForAccount(id);
                    rateLimiter.unlock("P:" + username.toLowerCase(Locale.ROOT));
                    audit.record(
                            new AuditEvent(
                                    "ACCOUNT_RESET",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_account:" + username,
                                    "重置平台账号密码（临时密码只下发一次），该账号全部会话已失效并解除登录锁定"));
                    return new ResetPasswordResult(id, username, temporary);
                });
    }

    /** 解锁登录锁定（连续失败 5 次锁 30 分钟，管理员可提前解锁）。 */
    public Map<String, Object> unlock(long accountId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> account = requireAccount(accountId);
                    long id = ((Number) account.get("id")).longValue();
                    String username = str(account.get("username"));
                    rateLimiter.unlock("P:" + username.toLowerCase(Locale.ROOT));
                    audit.record(
                            new AuditEvent(
                                    "ACCOUNT_UNLOCK",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_account:" + username,
                                    "解锁平台账号登录锁定"));
                    // PLT-AUD-002「解锁」：平台账号的解锁落平台库登录日志
                    audit.append(
                            AuditEntry.login(username)
                                    .action("UNLOCK")
                                    .description("平台管理员解锁登录锁定")
                                    .success()
                                    .userType("PLATFORM")
                                    .build());
                    return Map.of("userId", id, "username", username, "unlocked", true);
                });
    }

    // ---------------------------------------------------------------- 负责租户（PLT-VIEW-006，E20-T01）

    /** 账号的负责租户（含编码与名称供回显）。 */
    public List<ResponsibleTenant> responsibleTenants(long accountId) {
        return TenantContext.callAsPlatform(
                () -> {
                    requireAccount(accountId);
                    return platform.query(
                            "SELECT t.id, t.code, t.name FROM plt_account_tenant at"
                                    + " JOIN plt_tenant t ON t.id = at.tenant_id"
                                    + " WHERE at.account_id = ? ORDER BY t.id",
                            (rs, rowNum) ->
                                    new ResponsibleTenant(
                                            rs.getLong("id"),
                                            rs.getString("code"),
                                            rs.getString("name")),
                            accountId);
                });
    }

    /** 维护账号负责租户（整体替换）。负责关系变更即时生效：该账号进行中的视角会话按「权限变更」立即结束 （platform-spec §8 业务规则，PLT-VIEW-006）。 */
    public List<ResponsibleTenant> saveResponsibleTenants(
            long accountId, ResponsibleTenantsRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> account = requireAccount(accountId);
                    String username = str(account.get("username"));
                    Set<Long> desired =
                            request == null || request.tenantIds() == null
                                    ? Set.of()
                                    : new LinkedHashSet<>(request.tenantIds());
                    for (Long tenantId : desired) {
                        Integer exists =
                                platform.queryForObject(
                                        "SELECT COUNT(*) FROM plt_tenant WHERE id = ?",
                                        Integer.class,
                                        tenantId);
                        if (exists == null || exists == 0) {
                            throw bad("租户 " + tenantId + " 不存在，不能设为负责租户");
                        }
                    }
                    Set<Long> before =
                            new LinkedHashSet<>(
                                    platform.queryForList(
                                            "SELECT tenant_id FROM plt_account_tenant WHERE"
                                                    + " account_id = ?",
                                            Long.class,
                                            accountId));
                    if (!before.equals(desired)) {
                        platform.update(
                                "DELETE FROM plt_account_tenant WHERE account_id = ?", accountId);
                        for (Long tenantId : desired) {
                            platform.update(
                                    "INSERT INTO plt_account_tenant (account_id, tenant_id,"
                                            + " set_by) VALUES (?, ?, ?)",
                                    accountId,
                                    tenantId,
                                    operatorId());
                        }
                        audit.record(
                                new AuditEvent(
                                        "ACCOUNT_TENANT_UPDATE",
                                        operatorId(),
                                        "UPDATE",
                                        "plt_account:" + username,
                                        "维护负责租户："
                                                + before.size()
                                                + " 个 → "
                                                + desired.size()
                                                + " 个"));
                        tenantView.endForAccount(accountId);
                    }
                    return responsibleTenants(accountId);
                });
    }

    /** 按租户反查负责人（platform-spec §8 主要页面「平台账号管理 · 负责租户」）。 */
    public List<ResponsibleOwner> responsibleOwners(long tenantId) {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT a.id, a.username, a.display_name, a.status"
                                        + " FROM plt_account_tenant at"
                                        + " JOIN plt_account a ON a.id = at.account_id"
                                        + " WHERE at.tenant_id = ? ORDER BY a.id",
                                (rs, rowNum) ->
                                        new ResponsibleOwner(
                                                rs.getLong("id"),
                                                rs.getString("username"),
                                                rs.getString("display_name"),
                                                rs.getString("status")),
                                tenantId));
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /** 展示名必填 ≤64。 */
    static void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw bad("姓名必填");
        }
        if (displayName.trim().length() > NAME_MAX) {
            throw bad("姓名过长（最多 " + NAME_MAX + " 个字符）");
        }
    }

    /** 手机号强制绑定（双因素认证）：必填、格式校验。返回去除首尾空白后的号码。 */
    static String requirePhone(String phone, Long excludeAccountIdIgnored) {
        if (phone == null || phone.isBlank()) {
            throw bad("手机号必填（平台账号强制绑定，用于双因素认证）");
        }
        String trimmed = phone.trim();
        if (!PHONE.matcher(trimmed).matches()) {
            throw bad("手机号格式不正确（11 位国内手机号）");
        }
        return trimmed;
    }

    /** 初始密码按平台密码策略校验（E04-T05 同口径；历史密码比对由改密 / 重置用例负责）。 */
    void validateInitialPassword(String password, String username) {
        if (password == null || password.isBlank()) {
            throw bad("初始密码必填");
        }
        List<String> violations =
                PasswordPolicy.violations(
                        password,
                        username,
                        securityProperties.getPasswordMinLength(),
                        securityProperties.getPasswordMinCategories());
        if (!violations.isEmpty()) {
            throw new ApiException(
                    422,
                    42200,
                    "初始密码不满足策略：" + String.join("；", violations),
                    Map.of(
                            "fieldErrors",
                            List.of(
                                    Map.of(
                                            "field",
                                            "password",
                                            "message",
                                            String.join("；", violations)))));
        }
    }

    /** 角色绑定校验（PLT-ROLE-001「平台账号只能选平台角色」）：必填、全部存在且启用。 */
    void validateRoles(AccountModel model) {
        if (model.desiredRoleIds().isEmpty()) {
            throw bad("至少选择一个平台角色");
        }
        for (Long roleId : model.desiredRoleIds()) {
            Map<String, Object> role = findRole(roleId);
            if (role == null) {
                throw bad("平台角色 " + roleId + " 不存在");
            }
            if (!"ENABLED".equals(str(role.get("status")))) {
                throw bad("平台角色“" + str(role.get("name")) + "”已停用，不能授予");
            }
        }
    }

    /**
     * 平台管理员至少保留 2 个启用账号（platform-spec §1，已确认）：按「本次保存后的最终状态」模拟——
     * 该账号之外启用且持有平台管理员的账号数，加上保存后本账号仍启用且持有平台管理员的 1，不足 2 即拒绝。
     */
    void ensureEnoughEnabledAdmins(AccountModel model, String username) {
        int otherEnabledAdmins = countOtherEnabledAdmins(model.accountId());
        boolean keepsAdminAfterSave =
                model.desiredStatus() != null
                        && "ENABLED".equals(model.desiredStatus())
                        && model.desiredRoleCodes().contains(ADMIN_ROLE_CODE);
        int total = otherEnabledAdmins + (keepsAdminAfterSave ? 1 : 0);
        if (total < MIN_ENABLED_ADMINS) {
            throw new ApiException(
                    409,
                    40901,
                    "平台管理员至少保留 "
                            + MIN_ENABLED_ADMINS
                            + " 个启用账号（"
                            + username
                            + " 保存后只剩余 "
                            + total
                            + " 个），请先补足再变更");
        }
    }

    /** 临时密码生成（纯函数注入 Random 便于测试）：四类字符各至少 1 个，其余随机补齐后洗牌。 */
    static String generateTemporaryPassword(
            String username, java.util.random.RandomGenerator random) {
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
        while (password.length() < TEMP_PASSWORD_LENGTH) {
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
            while (password.length() < TEMP_PASSWORD_LENGTH) {
                password.append(all.charAt(random.nextInt(all.length())));
            }
        }
        return password.toString();
    }

    String generateTemporaryPassword(String username) {
        return generateTemporaryPassword(username, new SecureRandom());
    }

    // ---------------------------------------------------------------- 内部

    private AccountModel modelFor(
            long accountId,
            String username,
            String currentStatus,
            Set<Long> currentRoleIds,
            List<Long> desiredRoleIdsRaw,
            String desiredStatusRaw) {
        Set<Long> desiredRoleIds = new LinkedHashSet<>();
        if (desiredRoleIdsRaw != null) {
            desiredRoleIds.addAll(desiredRoleIdsRaw);
        }
        Set<String> desiredRoleCodes = new LinkedHashSet<>(roleCodes(accountId, desiredRoleIds));
        String desiredStatus =
                desiredStatusRaw == null || desiredStatusRaw.isBlank()
                        ? (currentStatus == null ? "ENABLED" : currentStatus)
                        : desiredStatusRaw;
        if (!"ENABLED".equals(desiredStatus) && !"DISABLED".equals(desiredStatus)) {
            throw bad("账号状态只允许 ENABLED / DISABLED");
        }
        Set<String> currentRoleCodes = new LinkedHashSet<>();
        if (currentStatus != null) {
            currentRoleCodes.addAll(roleCodes(accountId, currentRoleIds));
        }
        return new AccountModel(
                accountId,
                username,
                currentStatus == null ? "ENABLED" : currentStatus,
                currentRoleIds,
                currentRoleCodes,
                desiredRoleIds,
                desiredRoleCodes,
                desiredStatus);
    }

    private void requireUniqueUsername(AccountSaveRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw bad("账号名必填");
        }
        String username = request.username().trim();
        if (!USERNAME.matcher(username).matches()) {
            throw bad("账号名须以小写字母开头，只含小写字母、数字、点、下划线、连字符，长度 2–64");
        }
        Integer exists =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_account WHERE username = ?",
                        Integer.class,
                        username);
        if (exists != null && exists > 0) {
            throw new ApiException(409, 40901, "账号名已存在：" + username);
        }
    }

    /** 手机号盲索引唯一（双因素按手机号取号，重复则语义不明）。 */
    private void requirePhoneFree(String phoneIdx, Long excludeAccountId) {
        if (phoneIdx == null) {
            return;
        }
        Integer exists =
                excludeAccountId == null
                        ? platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_account WHERE phone_idx = ?",
                                Integer.class,
                                phoneIdx)
                        : platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_account WHERE phone_idx = ? AND id <> ?",
                                Integer.class,
                                phoneIdx,
                                excludeAccountId);
        if (exists != null && exists > 0) {
            throw new ApiException(409, 40901, "该手机号已绑定其他平台账号");
        }
    }

    private long insertAccount(
            String username, String displayName, String hash, String phoneCipher, String phoneIdx) {
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status,"
                        + " phone, phone_idx, must_change_password, password_updated_at)"
                        + " VALUES (?, ?, ?, 'ENABLED', ?, ?, 1, ?)",
                username,
                displayName,
                hash,
                phoneCipher,
                phoneIdx,
                LocalDateTime.now());
        return platform.queryForObject(
                "SELECT id FROM plt_account WHERE username = ?", Long.class, username);
    }

    private void saveRoles(long accountId, Set<Long> roleIds) {
        platform.update("DELETE FROM plt_account_role WHERE account_id = ?", accountId);
        for (Long roleId : roleIds) {
            platform.update(
                    "INSERT INTO plt_account_role (account_id, role_id) VALUES (?, ?)",
                    accountId,
                    roleId);
        }
    }

    /** 角色或状态变化后递增该账号权限版本号（PLT-PERM-010 / OQ-40 ①），在线会话下一次请求即重算。 */
    private void refreshSessions(long accountId) {
        permissionVersions.bump("PLATFORM", null, accountId);
    }

    private int countOtherEnabledAdmins(long excludeAccountId) {
        Integer count =
                platform.queryForObject(
                        "SELECT COUNT(DISTINCT ar.account_id) FROM plt_account_role ar"
                                + " JOIN plt_account a ON a.id = ar.account_id"
                                + " JOIN plt_role r ON r.id = ar.role_id AND r.code = ?"
                                + " WHERE a.status = 'ENABLED' AND a.id <> ?",
                        Integer.class,
                        ADMIN_ROLE_CODE,
                        excludeAccountId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> requireAccount(long accountId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, username, display_name, password_hash, phone, status, created_at"
                                + " FROM plt_account WHERE id = ?",
                        accountId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "平台账号不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> findRole(long roleId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, status FROM plt_role WHERE id = ?", roleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<Long, List<Long>> roleIdsByAccount() {
        Map<Long, List<Long>> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT account_id, role_id FROM plt_account_role ORDER BY role_id")) {
            result.computeIfAbsent(
                            ((Number) row.get("account_id")).longValue(), key -> new ArrayList<>())
                    .add(((Number) row.get("role_id")).longValue());
        }
        return result;
    }

    private Map<Long, List<String>> roleNamesByAccount() {
        Map<Long, List<String>> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT ar.account_id AS account_id, r.name AS name"
                                + " FROM plt_account_role ar JOIN plt_role r ON r.id = ar.role_id"
                                + " ORDER BY r.id")) {
            result.computeIfAbsent(
                            ((Number) row.get("account_id")).longValue(), key -> new ArrayList<>())
                    .add(str(row.get("name")));
        }
        return result;
    }

    private List<String> roleNamesOf(long accountId) {
        return platform.queryForList(
                "SELECT r.name FROM plt_account_role ar JOIN plt_role r ON r.id = ar.role_id"
                        + " WHERE ar.account_id = ? ORDER BY r.id",
                String.class,
                accountId);
    }

    private List<Long> roleIdsOf(long accountId) {
        return platform.queryForList(
                "SELECT role_id FROM plt_account_role WHERE account_id = ? ORDER BY role_id",
                Long.class,
                accountId);
    }

    private Set<String> roleCodes(long accountId, Set<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < roleIds.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        try {
            return new LinkedHashSet<>(
                    platform.queryForList(
                            "SELECT code FROM plt_role WHERE id IN (" + placeholders + ")",
                            String.class,
                            roleIds.toArray()));
        } catch (DataAccessException ex) {
            throw new ApiException(500, 50000, "读取平台角色失败");
        }
    }

    /** 目标账号的会话键代理（吊销全部会话用；只承载端别 + 租户 + 用户三个定位字段）。 */
    private SessionPrincipal targetPrincipal(
            long accountId, String username, Map<String, Object> account) {
        return new SessionPrincipal(
                null,
                "PLATFORM",
                null,
                null,
                null,
                accountId,
                username,
                str(account.get("display_name")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    /** 审计与展示口径的手机号脱敏（与 AuthService.maskedPhone 同规则）。 */
    static String maskedPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null || phone.isEmpty() ? null : "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static Long operatorId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static OffsetDateTime offsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        LocalDateTime local =
                value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
        return local.atZone(ZONE).toOffsetDateTime();
    }
}
