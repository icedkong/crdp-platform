package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E06-T07 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连；验收原文：用户可多选负责病区与科室）。场景：
 *
 * <ol>
 *   <li>新建用户（PLT-ORG-006）：账号 / 手机号本院唯一（40902）、模板角色不可直接分配（40901）、 初始密码只下发一次并强制首登改密；
 *   <li>负责病区 / 负责科室多选（A-WARD-002、C-WARD-003）：新建带两个病区与负责科室、编辑全量覆盖，
 *       变更即递增权限版本号（在线会话下一次请求重算，platform-spec §4「立即生效」）；
 *   <li>状态（PLT-ORG-006）：停用立即下线、启用可再停用 / 离职，离职为终态；最后一名启用的租户管理员不可停用 / 离职 / 降级；
 *   <li>重置密码与解锁（PLT-ORG-008）：临时密码 + 强制改密 + 旧哈希入历史 + 全部会话失效 + 解除锁定；
 *   <li>权限与隔离：doctor.demo 无 tenant:user:view 40300、未知用户 40400、demob 看不到 demoa 的用户；全部写审计。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（账号前缀 IT-U-、病区前缀 W-IT-）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class UserManagementIT {

    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    /** 只清理本 IT 建的数据（IT-U- 账号、W-IT- 病区）与其审计，不碰种子演示数据。 */
    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update(
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-%'");
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-U'");
                        jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target LIKE 'sys_user:IT-U-%'");
                    });
        }
    }

    /** 收尾清理：不把 IT-U- 用户 / W-IT- 病区留给后续 IT（如授权范围的受影响用户统计）。 */
    @org.junit.jupiter.api.AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        for (String tenant : new String[] {"demoa", "demob"}) {
            TenantContext.runAsTenant(
                    "demob".equals(tenant) ? 2L : 1L,
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update(
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-U-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-%'");
                        jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-%'");
                        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'IT-D-U'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE target LIKE 'sys_user:IT-U-%'");
                    });
        }
    }

    // ---------------------------------------------------------------- 新建 + 负责病区 / 负责科室多选（验收标准）

    @Test
    void createUserWithMultipleResponsibleWardsAndDepts() {
        String admin = adminToken();
        long deptId = deptIdByCode("DEPT-CARDIO");
        long subDeptId = createDept(deptId);
        long wardA = wardIdByCode("W-DEMO-1");
        long wardB = createWard("W-IT-B", "示例病区乙");
        long roleId = roleIdByCode("FOLLOWUP_DOCTOR");

        // 病区候选：两个启用病区都可选（A-WARD-002「一个医生可负责多个病区」）
        List<?> options = (List<?>) get("/api/hospital/system/wards/options", admin).get("data");
        assertTrue(options.size() >= 2, "病区候选应包含启用病区：" + options);

        // 校验失败：主科室 / 角色必填、手机号格式、模板角色不可直接分配
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/users",
                                admin,
                                Map.of(
                                        "username",
                                        "IT-U-1",
                                        "displayName",
                                        "示例护士",
                                        "phone",
                                        "13900001001",
                                        "mainDeptId",
                                        deptId,
                                        "roleIds",
                                        List.of()))));
        Map<String, Object> noDept = userBody("IT-U-1", "示例护士", "13900001001", deptId, roleId);
        noDept.remove("mainDeptId");
        assertEquals(40001, codeOf(post("/api/hospital/system/users", admin, noDept)));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/system/users",
                                admin,
                                Map.of(
                                        "username",
                                        "IT-U-1",
                                        "displayName",
                                        "示例护士",
                                        "phone",
                                        "12345",
                                        "mainDeptId",
                                        deptId,
                                        "roleIds",
                                        List.of(roleId)))));

        // 正常新建：两个负责病区 + 两个负责科室（多选，验收标准）
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username",
                                "IT-U-1",
                                "displayName",
                                "示例随访护士",
                                "staffNo",
                                "IT-7001",
                                "phone",
                                "13900001001",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardA, wardB),
                                "deptScopeIds",
                                List.of(deptId, subDeptId)));
        assertEquals(0, codeOf(created), "新建用户失败：" + created);
        Map<?, ?> createdUser = data(created);
        String temporary = String.valueOf(createdUser.get("temporaryPassword"));
        assertFalse(temporary.isBlank(), "初始密码随本次响应下发一次");
        long userId = ((Number) ((Map<?, ?>) createdUser.get("user")).get("id")).longValue();
        assertEquals(
                List.of(wardA, wardB),
                longList(((Map<?, ?>) createdUser.get("user")).get("wardIds")));
        assertEquals(
                List.of(deptId, subDeptId),
                longList(((Map<?, ?>) createdUser.get("user")).get("deptScopeIds")));

        // 落库：多选行数正确；强制首登改密
        assertEquals(
                2,
                countInTenant(
                                "demoa",
                                "SELECT COUNT(*) FROM sys_user_ward WHERE user_id = " + userId)
                        .intValue());
        assertEquals(
                2,
                countInTenant(
                                "demoa",
                                "SELECT COUNT(*) FROM sys_user_dept_scope WHERE user_id = "
                                        + userId)
                        .intValue());
        assertEquals(
                1,
                countInTenant(
                                "demoa",
                                "SELECT must_change_password FROM sys_user WHERE id = " + userId)
                        .intValue());
        assertTrue(
                String.valueOf(((Map<?, ?>) createdUser.get("user")).get("phoneMasked"))
                        .matches("139\\*\\*\\*\\*1001"),
                "手机号只回脱敏值");

        // 唯一性：账号与手机号 40902
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/system/users",
                                admin,
                                userBody("IT-U-1", "重复账号", "13900001999", deptId, roleId))));
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/system/users",
                                admin,
                                userBody("IT-U-2", "重复手机号", "13900001001", deptId, roleId))));

        // 模板角色不可直接分配（E06-T05 下发的 FOLLOWUP_DUTY_DOCTOR 模板）
        Long templateId = roleIdByCodeOrNull("FOLLOWUP_DUTY_DOCTOR");
        if (templateId != null) {
            assertEquals(
                    40901,
                    codeOf(
                            post(
                                    "/api/hospital/system/users",
                                    admin,
                                    userBody(
                                            "IT-U-3",
                                            "模板角色用户",
                                            "13900001303",
                                            deptId,
                                            templateId))));
        }

        // 列表筛选：按病区 / 科室 / 状态 / 关键字都能命中
        assertEquals(
                1,
                totalOf(
                        get(
                                "/api/hospital/system/users?wardId=" + wardB + "&status=ENABLED",
                                admin)));
        assertEquals(1, totalOf(get("/api/hospital/system/users?deptId=" + subDeptId, admin)));
        assertEquals(1, totalOf(get("/api/hospital/system/users?keyword=IT-U-1", admin)));
        assertEquals(0, totalOf(get("/api/hospital/system/users?keyword=IT-U-404", admin)));

        // 审计
        assertTrue(
                countInTenant(
                                        "demoa",
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'USER_CREATE'"
                                                + " AND target = 'sys_user:IT-U-1'")
                                .intValue()
                        >= 1,
                "新建用户写审计");
    }

    @Test
    void editReplacesResponsibleScopesAndTakesEffectImmediately() {
        String admin = adminToken();
        long deptId = deptIdByCode("DEPT-CARDIO");
        long roleId = roleIdByCode("FOLLOWUP_DOCTOR");
        long wardA = wardIdByCode("W-DEMO-1");
        long wardB = createWard("W-IT-B", "示例病区乙");

        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username",
                                "IT-U-1",
                                "displayName",
                                "示例随访护士",
                                "phone",
                                "13900001001",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardA, wardB)));
        assertEquals(0, codeOf(created));
        long userId = ((Number) ((Map<?, ?>) data(created).get("user")).get("id")).longValue();
        String temporary = String.valueOf(data(created).get("temporaryPassword"));

        // 以新用户登录取会话（初始密码可用，受限改密态不影响 /auth/me）
        String userToken = hospitalToken("demoa", "IT-U-1", temporary);
        long versionBefore =
                ((Number) data(get("/api/hospital/auth/me", userToken)).get("permissionVersion"))
                        .longValue();

        // 编辑：负责病区全量覆盖为单个、负责科室清空、改姓名与工号（C-WARD-003 负责科室多选，可清空）
        Map<?, ?> updated =
                put(
                        "/api/hospital/system/users/" + userId,
                        admin,
                        Map.of(
                                "displayName",
                                "示例随访护士改",
                                "staffNo",
                                "IT-7002",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardA),
                                "deptScopeIds",
                                List.of()));
        assertEquals(0, codeOf(updated), "编辑用户失败：" + updated);
        assertEquals(List.of(wardA), longList(data(updated).get("wardIds")));
        assertEquals(List.of(), longList(data(updated).get("deptScopeIds")));
        assertEquals(
                1,
                countInTenant(
                                "demoa",
                                "SELECT COUNT(*) FROM sys_user_ward WHERE user_id = " + userId)
                        .intValue(),
                "负责病区按全量覆盖落库");
        assertEquals(
                0,
                countInTenant(
                                "demoa",
                                "SELECT COUNT(*) FROM sys_user_dept_scope WHERE user_id = "
                                        + userId)
                        .intValue());

        // 立即生效：在线会话的下一次请求按新权限版本重算（platform-spec §4、OQ-40 ①）
        long versionAfter =
                ((Number) data(get("/api/hospital/auth/me", userToken)).get("permissionVersion"))
                        .longValue();
        assertTrue(versionAfter > versionBefore, "权限版本应递增：" + versionBefore + " → " + versionAfter);

        // 手机号不传＝保持不变；改绑已被占用手机号 40902
        Map<?, ?> keep =
                put(
                        "/api/hospital/system/users/" + userId,
                        admin,
                        Map.of(
                                "displayName",
                                "示例随访护士改",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardA),
                                "deptScopeIds",
                                List.of()));
        assertEquals(0, codeOf(keep));
        assertEquals(
                40902,
                codeOf(
                        put(
                                "/api/hospital/system/users/" + userId,
                                admin,
                                Map.of(
                                        "displayName",
                                        "示例随访护士改",
                                        "phone",
                                        DEMO_ADMIN_PHONE,
                                        "mainDeptId",
                                        deptId,
                                        "roleIds",
                                        List.of(roleId),
                                        "wardIds",
                                        List.of(wardA),
                                        "deptScopeIds",
                                        List.of()))),
                "改绑已被本院其他账号占用的手机号 40902");
        assertTrue(
                countInTenant(
                                        "demoa",
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'USER_UPDATE'"
                                                + " AND target = 'sys_user:IT-U-1'")
                                .intValue()
                        >= 2,
                "编辑写审计");
    }

    // ---------------------------------------------------------------- 状态（PLT-ORG-006）

    @Test
    void statusLifecycleDisableResignAndLastAdminGuard() {
        String admin = adminToken();
        long deptId = deptIdByCode("DEPT-CARDIO");
        long roleId = roleIdByCode("FOLLOWUP_DOCTOR");
        long userId = createUser("IT-U-2", "13900001002", deptId, roleId);
        String token = hospitalToken("demoa", "IT-U-2", temporaryOf("IT-U-2"));

        // 停用：立即生效（旧会话 40100、登录被拒），审计
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "DISABLE", "reason", "轮岗（示例）"))));
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", token)), "停用立即下线");
        assertEquals(
                40101,
                codeOf(
                        post(
                                "/api/hospital/auth/login",
                                null,
                                Map.of(
                                        "tenantCode",
                                        "demoa",
                                        "username",
                                        "IT-U-2",
                                        "password",
                                        "wrong-pass-IT"))),
                "停用账号不能登录（按 AuthService 口径拒绝）");

        // 流转：停用态不能直接离职（40901）；再启用后离职成功（终态）
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "RESIGN"))),
                "离职只能从启用态进入");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "ENABLE"))));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "DISABLE"))));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "ENABLE"))));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "RESIGN", "reason", "离职（示例）"))));
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + userId + "/status",
                                admin,
                                Map.of("action", "ENABLE"))),
                "离职为终态，不能再启用");
        assertEquals("RESIGNED", fieldOfUser(userId, "status"));

        // 最后一名启用的租户管理员：不能停用 / 离职 / 降级（demoa 只有 nurse.demo 一个）
        long adminUserId = userIdOf("nurse.demo");
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + adminUserId + "/status",
                                admin,
                                Map.of("action", "DISABLE"))));
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/system/users/" + adminUserId + "/status",
                                admin,
                                Map.of("action", "RESIGN"))));
        long plainRoleId = roleIdByCode("FOLLOWUP_DOCTOR");
        assertEquals(
                40901,
                codeOf(
                        put(
                                "/api/hospital/system/users/" + adminUserId,
                                admin,
                                Map.of(
                                        "displayName",
                                        "演示护士",
                                        "mainDeptId",
                                        deptId,
                                        "roleIds",
                                        List.of(plainRoleId),
                                        "wardIds",
                                        List.of(),
                                        "deptScopeIds",
                                        List.of()))),
                "不能移除最后一名启用租户管理员的租户管理员角色");
        assertEquals("ENABLED", fieldOfUser(adminUserId, "status"), "保护生效：租户管理员仍为启用态");
    }

    // ---------------------------------------------------------------- 重置密码与解锁（PLT-ORG-008）

    @Test
    void resetPasswordRevokesSessionsAndUnlockWritesAudit() {
        String admin = adminToken();
        long deptId = deptIdByCode("DEPT-CARDIO");
        long roleId = roleIdByCode("FOLLOWUP_DOCTOR");
        long userId = createUser("IT-U-3", "13900001003", deptId, roleId);
        String token = hospitalToken("demoa", "IT-U-3", temporaryOf("IT-U-3"));
        assertEquals(0, codeOf(get("/api/hospital/auth/me", token)));

        // 重置：临时密码只下发一次、强制改密、旧哈希入历史、全部会话失效、解除锁定
        Map<?, ?> reset =
                post("/api/hospital/system/users/" + userId + "/reset-password", admin, Map.of());
        assertEquals(0, codeOf(reset));
        String temporary = String.valueOf(data(reset).get("temporaryPassword"));
        assertFalse(temporary.isBlank());
        assertEquals(
                1,
                countInTenant(
                                "demoa",
                                "SELECT must_change_password FROM sys_user WHERE id = " + userId)
                        .intValue());
        assertTrue(
                countInTenant(
                                        "demoa",
                                        "SELECT COUNT(*) FROM sys_password_history WHERE user_id = "
                                                + userId)
                                .intValue()
                        >= 1,
                "重置前哈希进入历史表");
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", token)), "重置后旧会话全部失效");
        // 新临时密码可登录（受限改密态）
        assertEquals(
                0,
                codeOf(get("/api/hospital/auth/me", hospitalToken("demoa", "IT-U-3", temporary))));

        // 解锁（E04-T05 接口在用户管理页继续使用）
        assertEquals(
                0,
                codeOf(post("/api/hospital/system/users/" + userId + "/unlock", admin, Map.of())));
        assertTrue(
                countInTenant(
                                        "demoa",
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'USER_UNLOCK'"
                                                + " AND target = 'sys_user:IT-U-3'")
                                .intValue()
                        >= 1,
                "解锁写审计");
        assertTrue(
                countInTenant(
                                        "demoa",
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'USER_RESET_PASSWORD'"
                                                + " AND target = 'sys_user:IT-U-3'")
                                .intValue()
                        >= 1,
                "重置密码写审计");
    }

    // ---------------------------------------------------------------- 权限与隔离

    @Test
    void permissionDeniedUnknownUserAndCrossTenantIsolation() {
        String admin = adminToken();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);

        // doctor.demo 只有随访角色：tenant:user:view / edit / reset 均 40300
        assertEquals(40300, codeOf(get("/api/hospital/system/users", doctor)));
        assertEquals(40300, codeOf(get("/api/hospital/system/users/1", doctor)));
        assertEquals(
                40300,
                codeOf(post("/api/hospital/system/users/1/reset-password", doctor, Map.of())));

        // 未知用户 40400（不暴露对象存在）
        assertEquals(40400, codeOf(get("/api/hospital/system/users/999999", admin)));

        // 跨租户：demoa 的用户在 demob 不可见（租户库隔离）
        long userId =
                createUser(
                        "IT-U-4",
                        "13900001004",
                        deptIdByCode("DEPT-CARDIO"),
                        roleIdByCode("FOLLOWUP_DOCTOR"));
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        assertEquals(
                40400,
                codeOf(get("/api/hospital/system/users/" + userId, demobAdmin)),
                "demob 不得访问 demoa 的用户");
        String demobList =
                String.valueOf(data(get("/api/hospital/system/users?keyword=IT-U-4", demobAdmin)));
        assertFalse(demobList.contains("IT-U-4"), "demob 列表不含 demoa 用户：" + demobList);
        assertEquals(
                1, totalOf(get("/api/hospital/system/users?keyword=IT-U-4", admin)), "demoa 自己可见");
    }

    // ---------------------------------------------------------------- 辅助

    private Map<String, Object> userBody(
            String username, String displayName, String phone, long deptId, long roleId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("displayName", displayName);
        body.put("phone", phone);
        body.put("mainDeptId", deptId);
        body.put("roleIds", List.of(roleId));
        return body;
    }

    /** 建用户并返回 id（临时密码按创建响应记录，供 temporaryOf 查询）。 */
    private long createUser(String username, String phone, long deptId, long roleId) {
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken(),
                        Map.of(
                                "username", username,
                                "displayName", "示例用户" + username,
                                "phone", phone,
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        Map<?, ?> user = data(created);
        temporaryPasswords.put(username, String.valueOf(user.get("temporaryPassword")));
        return ((Number) ((Map<?, ?>) user.get("user")).get("id")).longValue();
    }

    private final java.util.Map<String, String> temporaryPasswords = new java.util.HashMap<>();

    private String temporaryOf(String username) {
        String password = temporaryPasswords.get(username);
        assertNotNull(password, "初始 / 重置密码已随创建 / 重置响应记录");
        return password;
    }

    private long createDept(long parentDeptId) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_dept (org_id, parent_id, code, name, status)"
                                    + " SELECT org_id, ?, 'IT-D-U', '示例随访科', 'ENABLED' FROM sys_dept WHERE id = ?",
                            parentDeptId,
                            parentDeptId);
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_dept WHERE code = 'IT-D-U'", Long.class);
                });
    }

    private long createWard(String code, String name) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long deptIdByCode(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long wardIdByCode(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long roleIdByCode(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = ?", Long.class, code));
    }

    private Long roleIdByCodeOrNull(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc
                                .queryForList(
                                        "SELECT id FROM sys_role WHERE code = ?", Long.class, code)
                                .stream()
                                .findFirst()
                                .orElse(null));
    }

    private long userIdOf(String username) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    /** 种子演示管理员 nurse.demo 的绑定手机号（V101，本地演示段），做改绑冲突源。 */
    private static final String DEMO_ADMIN_PHONE = "13900000001";

    private String fieldOfUser(long userId, String field) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT " + field + " FROM sys_user WHERE id = ?",
                                String.class,
                                userId));
    }

    private Number countInTenant(String tenant, String sql) {
        return runInTenant(tenant, () -> jdbc.queryForObject(sql, Number.class));
    }

    private int totalOf(Map<?, ?> page) {
        return ((Number) data(page).get("total")).intValue();
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static List<Long> longList(Object raw) {
        return ((List<?>) raw).stream().map(n -> ((Number) n).longValue()).toList();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
