package com.crdp.server.config;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.tenant.TenantResourceRow;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开通编排（PLT-TEN-003/004/006/008 · E02-T04，验收：中途失败重跑可续，不重复建库）。
 *
 * <p>五个步骤按序执行，每步幂等；失败即中止并把失败原因记入平台库 plt_provision_step，重跑时已完成的 步骤自然跳过，从失败步骤继续：
 *
 * <ol>
 *   <li>tenant-row：平台库登记租户（status=PROVISIONING），编码唯一键防重；
 *   <li>resource-row：生成租户库专属账号口令并登记 plt_tenant_resource——已存在则复用原口令，不重复生成；
 *   <li>database：特权连接 CREATE DATABASE / CREATE USER IF NOT EXISTS + GRANT（不重复建库）；
 *   <li>migrate-register：热注册（E02-T02）——建池、跑租户库迁移、注册进路由，已注册则跳过；
 *   <li>admin：租户库内置 TENANT_ADMIN 角色与首个管理员（已存在跳过，口令 Argon2id）。
 * </ol>
 *
 * <p>全部成功后租户置 TRIAL。对象存储桶与 KMS 主密钥属云资源（由人负责，E01），本编排只覆盖共享 MySQL 实例。平台端向导与进度页是
 * E02-T07/T08（前端）。租户编码经白名单校验后才拼入 DDL 标识符。
 */
@Component
public class TenantOnboardingService {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9]{1,31}$");
    private static final int ERROR_MAX_LENGTH = 1000;
    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    /**
     * 解锁 / 重置按钮（tenant:user:reset）在 plt_menu 的固定 id，平台库迁移 V10 新增（E04-T05 / PLT-AUTH-007）。与种子 V100
     * 直接写死菜单 id 的做法一致（sys_role_menu 无跨库外键）。
     */
    private static final int TENANT_ADMIN_RESET_MENU_ID = 311;

    /**
     * 在线会话按钮（tenant:session:view / kick）在 plt_menu 的固定 id，平台库迁移 V11 新增（E04-T06 /
     * PLT-AUTH-008、009）。老库由租户库 base V8 增量授予、local/dev 全新库由种子 V103 补授，这里覆盖生产（无种子）。
     */
    private static final int[] TENANT_ADMIN_SESSION_MENU_IDS = {312, 313};

    /** 开通请求：编码与医院名称必填；首个管理员在开通时提供，也可启用前经 E06 补设。 */
    public record OnboardingRequest(
            String code,
            String name,
            String adminUsername,
            String adminDisplayName,
            String adminInitialPassword) {}

    public record StepResult(String step, String status, String error) {}

    public record OnboardingResult(long tenantId, String code, List<StepResult> steps) {}

    private final JdbcTemplate platform;
    private final TenantDataSourceRegistry registry;
    private final ProvisioningDatabase provisioning;
    private final ProvisioningProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public TenantOnboardingService(
            JdbcTemplate platform,
            TenantDataSourceRegistry registry,
            ProvisioningDatabase provisioning,
            ProvisioningProperties properties,
            PasswordEncoder passwordEncoder) {
        this.platform = platform;
        this.registry = registry;
        this.provisioning = provisioning;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    public OnboardingResult onboard(OnboardingRequest request) {
        requireValid(request);
        List<StepResult> steps = new ArrayList<>();

        long tenantId = ensureTenantRow(request);
        record(steps, tenantId, "tenant-row", null);

        String dbPassword =
                provisionReturning(
                        steps,
                        tenantId,
                        "resource-row",
                        () -> ensureResourceRow(tenantId, request.code()));
        TenantResourceRow resource = resourceRow(tenantId, request.code(), dbPassword);
        provision(steps, tenantId, "database", () -> ensureDatabase(request.code(), dbPassword));
        provision(steps, tenantId, "migrate-register", () -> registry.register(resource));
        if (adminProvided(request)) {
            provision(steps, tenantId, "admin", () -> ensureAdmin(tenantId, request));
        } else {
            // 向导第 3 步可跳过：启用前经 E06 补设管理员
            record(steps, tenantId, "admin", "SKIPPED", null);
        }

        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "UPDATE plt_tenant SET status = 'TRIAL'"
                                        + " WHERE id = ? AND status = 'PROVISIONING'",
                                tenantId));
        return new OnboardingResult(tenantId, request.code(), steps);
    }

    /** 开通进度视图（PLT-TEN-008）：租户状态与逐步结果。 */
    public ProvisionView provisionOf(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant =
                            platform.queryForMap(
                                    "SELECT code, name, status FROM plt_tenant WHERE id = ?",
                                    tenantId);
                    List<StepResult> steps = loadProvisionSteps(tenantId);
                    return new ProvisionView(
                            tenantId,
                            String.valueOf(tenant.get("code")),
                            String.valueOf(tenant.get("name")),
                            String.valueOf(tenant.get("status")),
                            steps);
                });
    }

    /** 从失败步骤重试：用平台库里登记的名称与编码重跑编排（可重入）；管理员已建成则保持不动。 */
    public OnboardingResult retry(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant =
                            platform.queryForMap(
                                    "SELECT code, name FROM plt_tenant WHERE id = ?", tenantId);
                    return onboard(
                            new OnboardingRequest(
                                    String.valueOf(tenant.get("code")),
                                    String.valueOf(tenant.get("name")),
                                    null,
                                    null,
                                    null));
                });
    }

    /** 放弃回收（PLT-TEN-008）：仅开通中（未到 TRIAL）的租户；注销库与账号、清平台登记。 */
    public void recycle(long tenantId) {
        String code =
                TenantContext.callAsPlatform(
                        () -> {
                            Map<String, Object> tenant =
                                    platform.queryForMap(
                                            "SELECT code, status FROM plt_tenant WHERE id = ?",
                                            tenantId);
                            if (!"PROVISIONING".equals(String.valueOf(tenant.get("status")))) {
                                throw new ApiException(
                                        400, 40001, "仅开通未完成的租户可回收；正式注销走 PLT-TEN-020");
                            }
                            return String.valueOf(tenant.get("code"));
                        });
        registry.unregister(tenantId);
        String database = "t_" + code;
        provisioning.execute("DROP DATABASE IF EXISTS `" + database + "`");
        provisioning.execute("DROP USER IF EXISTS `" + database + "`@'%'");
        TenantContext.runAsPlatform(
                () -> {
                    platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", tenantId);
                    platform.update(
                            "DELETE FROM plt_tenant_resource WHERE tenant_id = ?", tenantId);
                    platform.update("DELETE FROM plt_tenant WHERE id = ?", tenantId);
                });
    }

    private List<StepResult> loadProvisionSteps(long tenantId) {
        return platform.query(
                "SELECT step, status, error_text FROM plt_provision_step"
                        + " WHERE tenant_id = ? ORDER BY id",
                (rs, rowNum) ->
                        new StepResult(
                                rs.getString("step"),
                                rs.getString("status"),
                                rs.getString("error_text")),
                tenantId);
    }

    /** 进度视图（E02-T08）。 */
    public record ProvisionView(
            long tenantId, String code, String name, String status, List<StepResult> steps) {}

    private void requireValid(OnboardingRequest request) {
        if (request.code() == null || !CODE.matcher(request.code()).matches()) {
            throw new ApiException(400, 40001, "租户编码只允许小写字母开头的小写字母与数字");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(400, 40001, "医院名称必填");
        }
        if (adminProvided(request)
                && (request.adminInitialPassword() == null
                        || request.adminInitialPassword().isBlank())) {
            throw new ApiException(400, 40001, "填写首个租户管理员时初始口令必填（也可跳过，启用前补设）");
        }
    }

    private static boolean adminProvided(OnboardingRequest request) {
        return request.adminUsername() != null && !request.adminUsername().isBlank();
    }

    private long ensureTenantRow(OnboardingRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    Long existing =
                            queryId("SELECT id FROM plt_tenant WHERE code = ?", request.code());
                    if (existing != null) {
                        return existing;
                    }
                    try {
                        return insertReturningId(
                                "INSERT INTO plt_tenant (code, name, status)"
                                        + " VALUES (?, ?, 'PROVISIONING')",
                                request.code(),
                                request.name());
                    } catch (DuplicateKeyException ex) {
                        // 并发插入撞编码时重查可得；重查仍为空说明撞的是医院名称唯一键（E05-T01 起 name 唯一）
                        Long raced =
                                queryId("SELECT id FROM plt_tenant WHERE code = ?", request.code());
                        if (raced == null) {
                            throw new ApiException(409, 40901, "医院名称已存在");
                        }
                        return raced;
                    }
                });
    }

    /** 已有资源行时复用原口令（不重复生成），保证重跑可续。返回租户库账号的明文口令。 */
    private String ensureResourceRow(long tenantId, String code) {
        return TenantContext.callAsPlatform(
                () -> {
                    List<String> existing =
                            platform.queryForList(
                                    "SELECT password_cipher FROM plt_tenant_resource"
                                            + " WHERE tenant_id = ?",
                                    String.class,
                                    tenantId);
                    if (!existing.isEmpty()) {
                        return DatasourceSecrets.resolve(existing.get(0), "local");
                    }
                    String dbPassword = randomPassword();
                    platform.update(
                            "INSERT INTO plt_tenant_resource"
                                    + " (tenant_id, db_name, jdbc_url, db_username, password_cipher,"
                                    + " secret_mode) VALUES (?, ?, ?, ?, ?, 'local')",
                            tenantId,
                            "t_" + code,
                            tenantJdbcUrl(code),
                            "t_" + code,
                            "local:" + dbPassword);
                    return dbPassword;
                });
    }

    private void ensureDatabase(String code, String dbPassword) {
        String database = "t_" + code;
        provisioning.execute(
                "CREATE DATABASE IF NOT EXISTS `"
                        + database
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        provisioning.execute(
                "CREATE USER IF NOT EXISTS `"
                        + database
                        + "`@'%' IDENTIFIED BY '"
                        + dbPassword
                        + "'");
        provisioning.execute(
                "ALTER USER `" + database + "`@'%' IDENTIFIED BY '" + dbPassword + "'");
        provisioning.execute(
                "GRANT ALL PRIVILEGES ON `" + database + "`.* TO `" + database + "`@'%'");
    }

    /** 在租户库内保证内置管理员角色与首个管理员存在。 */
    private void ensureAdmin(long tenantId, OnboardingRequest request) {
        TenantContext.runAsTenant(
                tenantId,
                request.code(),
                () -> {
                    Long roleId =
                            queryId("SELECT id FROM sys_role WHERE code = ?", TENANT_ADMIN_ROLE);
                    if (roleId == null) {
                        roleId =
                                insertReturningId(
                                        "INSERT INTO sys_role (code, name, built_in, status)"
                                                + " VALUES (?, ?, 1, 'ENABLED')",
                                        TENANT_ADMIN_ROLE,
                                        "租户管理员");
                    }
                    Long userId =
                            queryId(
                                    "SELECT id FROM sys_user WHERE username = ?",
                                    request.adminUsername());
                    if (userId == null) {
                        userId =
                                insertReturningId(
                                        "INSERT INTO sys_user (username, display_name, password_hash,"
                                                + " status, must_change_password)"
                                                + " VALUES (?, ?, ?, 'ENABLED', 1)",
                                        request.adminUsername(),
                                        request.adminDisplayName() == null
                                                ? request.adminUsername()
                                                : request.adminDisplayName(),
                                        passwordEncoder.encode(request.adminInitialPassword()));
                    }
                    Long bound =
                            queryId(
                                    "SELECT user_id FROM sys_user_role WHERE user_id = ? AND role_id = ?",
                                    userId,
                                    roleId);
                    if (bound == null) {
                        platform.update(
                                "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                userId,
                                roleId);
                    }
                    // E04-T05 / PLT-AUTH-007：生产不开种子目录，内置管理员角色由这里创建，
                    // 解锁 / 重置按钮（plt_menu 311，tenant:user:reset，平台库 V10）也随之授予；
                    // local/dev 的全新租户库另有种子 V102 兜底（种子 V100 建角色晚于 base V7，V7 在新库上插不进去）。
                    Integer granted =
                            platform.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = "
                                            + TENANT_ADMIN_RESET_MENU_ID,
                                    Integer.class,
                                    roleId);
                    if (granted == null || granted == 0) {
                        platform.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, "
                                        + TENANT_ADMIN_RESET_MENU_ID
                                        + ")",
                                roleId);
                    }
                    // E04-T06 / PLT-AUTH-008、009：在线会话按钮（plt_menu 312 / 313）同样授予；
                    // local/dev 全新库由种子 V103 兜底，老库由租户库 base V8 授予。
                    for (int menuId : TENANT_ADMIN_SESSION_MENU_IDS) {
                        Integer sessionGranted =
                                platform.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = "
                                                + menuId,
                                        Integer.class,
                                        roleId);
                        if (sessionGranted == null || sessionGranted == 0) {
                            platform.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, "
                                            + menuId
                                            + ")",
                                    roleId);
                        }
                    }
                });
    }

    private TenantResourceRow resourceRow(long tenantId, String code, String dbPassword) {
        String database = "t_" + code;
        return new TenantResourceRow(
                tenantId,
                code,
                "PROVISIONING",
                database,
                tenantJdbcUrl(code),
                database,
                dbPassword);
    }

    private String tenantJdbcUrl(String code) {
        return properties.getTenantJdbcUrlTemplate().replace("{db}", "t_" + code);
    }

    private void provision(List<StepResult> steps, long tenantId, String step, Runnable action) {
        provisionReturning(
                steps,
                tenantId,
                step,
                () -> {
                    action.run();
                    return null;
                });
    }

    private String provisionReturning(
            List<StepResult> steps, long tenantId, String step, Supplier<String> action) {
        try {
            String value = action.get();
            record(steps, tenantId, step, null);
            return value;
        } catch (RuntimeException ex) {
            record(steps, tenantId, step, ex);
            throw ex;
        }
    }

    private void record(
            List<StepResult> steps, long tenantId, String step, RuntimeException error) {
        record(steps, tenantId, step, error == null ? "SUCCESS" : "FAILED", abbreviate(error));
    }

    private void record(
            List<StepResult> steps, long tenantId, String step, String status, String errorText) {
        steps.add(new StepResult(step, status, errorText));
        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "INSERT INTO plt_provision_step (tenant_id, step, status, error_text)"
                                        + " VALUES (?, ?, ?, ?)"
                                        + " ON DUPLICATE KEY UPDATE status = VALUES(status),"
                                        + " error_text = VALUES(error_text)",
                                tenantId,
                                step,
                                status,
                                errorText));
    }

    private String randomPassword() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder builder = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private Long queryId(String sql, Object... args) {
        List<Long> ids = platform.queryForList(sql, Long.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long insertReturningId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        platform.update(
                (PreparedStatementCreator)
                        connection -> {
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            sql, Statement.RETURN_GENERATED_KEYS);
                            for (int i = 0; i < args.length; i++) {
                                statement.setObject(i + 1, args[i]);
                            }
                            return statement;
                        },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取到自增主键: " + sql);
        }
        return key.longValue();
    }

    private static String abbreviate(RuntimeException ex) {
        if (ex == null) {
            return null;
        }
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() > ERROR_MAX_LENGTH
                ? message.substring(0, ERROR_MAX_LENGTH)
                : message;
    }
}
