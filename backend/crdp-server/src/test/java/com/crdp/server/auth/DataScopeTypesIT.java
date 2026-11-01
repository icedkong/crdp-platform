package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.datascope.DataScopeGuard;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E07-T02 端到端（tasks.csv 验收：多角色取并集，病区与科室 OR；未分配病区看不到患者）。
 *
 * <p>登录侧快照（{@link HospitalDirectory}，与 AuthService / 会话重算同一条取数路径）+ 改写侧 {@link DataScopeGuard}（受控表
 * SQL 由快照生成病区 / 科室 / 本人 OR 条件）串联验证：
 *
 * <ol>
 *   <li>种子医生（WARD + RESPONSIBLE_PATIENT，负责虚构病区一）的基线快照；
 *   <li>叠加 DEPT / RESPONSIBLE_DEPT / CUSTOM_DEPT 角色：主科室、负责科室、角色自定义科室并集进快照， 病区与科室分支在改写 SQL 中以 OR
 *       组合（PLT-PERM-007、C-WARD-004）；
 *   <li>任一角色 ALL 即全院（A-WARD-007，快照不再过滤）；
 *   <li>只有 WARD 且未分配病区的用户 → 空范围，患者查询追加 {@code AND 1 = 0}（A-WARD-003 边界情况）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（ITR- 前缀角色 / 科室 / 用户）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class DataScopeTypesIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HospitalDirectory hospitalDirectory;

    @BeforeEach
    void cleanState() {
        runInTenant(() -> deleteItrRows(jdbc));
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, () -> deleteItrRows(jdbc));
    }

    /** 只清理本 IT 建的数据（ITR- 前缀角色 / 科室与 itr. 前缀用户），并还原种子医生的主科室与负责科室行。 */
    private static void deleteItrRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE ur FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE r.code LIKE 'ITR-%'");
        jdbc.update(
                "DELETE rsd FROM sys_role_scope_dept rsd JOIN sys_role r ON r.id = rsd.role_id"
                        + " WHERE r.code LIKE 'ITR-%'");
        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'ITR-%'");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.%'");
        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'ITR-DEPT-%'");
        jdbc.update(
                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                        + " WHERE u.username = 'doctor.demo'");
        jdbc.update("UPDATE sys_user SET main_dept_id = NULL WHERE username = 'doctor.demo'");
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void seedDoctorSnapshotIsWardPlusResponsiblePatient() {
        long doctorId = userId("doctor.demo");
        long wardId = wardId("W-DEMO-1");
        ScopeSnapshot scope = scopeOf(doctorId);
        assertFalse(scope.all());
        assertFalse(scope.empty());
        assertEquals(List.of(wardId), scope.wardIds(), "WARD 类型取本人负责病区（种子：虚构病区一）");
        assertTrue(scope.deptIds().isEmpty());
        assertTrue(scope.self(), "RESPONSIBLE_PATIENT 类型开启本人分支");
    }

    @Test
    void multiRoleUnionMergesDeptListsAndRewritesWardOrDept() {
        long doctorId = userId("doctor.demo");
        long wardId = wardId("W-DEMO-1");

        // 叠加两个角色：角色 A = DEPT + CUSTOM_DEPT（自定义科室 ITR-DEPT-CUSTOM），角色 B = RESPONSIBLE_DEPT
        long roleA = insertRole("ITR-SCOPE-A", "IT 范围角色甲（示例）", "DEPT", "CUSTOM_DEPT");
        long roleB = insertRole("ITR-SCOPE-B", "IT 范围角色乙（示例）", "RESPONSIBLE_DEPT");
        long mainDept = deptId("DEPT-CARDIO");
        long customDept = insertDept("ITR-DEPT-CUSTOM", "IT 自定义科室（示例）");
        runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role_scope_dept (role_id, dept_id) VALUES (?, ?)",
                            roleA,
                            customDept);
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            doctorId,
                            roleA);
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            doctorId,
                            roleB);
                    // 主科室落到用户上（DEPT 类型取值来源），负责科室行给同一个值验证并集去重
                    jdbc.update(
                            "UPDATE sys_user SET main_dept_id = ? WHERE id = ?",
                            mainDept,
                            doctorId);
                    jdbc.update(
                            "INSERT INTO sys_user_dept_scope (user_id, dept_id) VALUES (?, ?)",
                            doctorId,
                            customDept);
                });

        ScopeSnapshot scope = scopeOf(doctorId);
        assertFalse(scope.all(), "没有任何角色 ALL，仍是受限范围");
        assertEquals(List.of(wardId), scope.wardIds(), "病区分支保持");
        assertTrue(
                scope.deptIds().containsAll(List.of(mainDept, customDept))
                        && scope.deptIds().size() == 2,
                "科室分支 = 主科室 ∪ 自定义科室（负责科室行重复值去重），实际：" + scope.deptIds());
        assertTrue(scope.self(), "原有 RESPONSIBLE_PATIENT 分支保留（多角色并集）");

        // 改写侧：病区与科室 OR（C-WARD-004 两者无上下级关系），本人分支同组
        String rewritten =
                DataScopeGuard.enforce(
                        "SELECT id FROM pt_patient WHERE deleted = 0", scope, doctorId, false);
        assertTrue(
                rewritten.contains("ward_id IN (" + wardId + ") OR")
                        && rewritten.contains("dept_id IN ("),
                "病区与科室以 OR 组合：" + rewritten);
        assertTrue(
                rewritten.contains("responsible_user_id = " + doctorId),
                "仅本人负责的患者分支与上两者同组 OR：" + rewritten);
    }

    @Test
    void anyRoleAllRemovesFiltering() {
        long doctorId = userId("doctor.demo");
        long roleAll = insertRole("ITR-SCOPE-ALL", "IT 全院角色（示例）", "ALL");
        runInTenant(
                () ->
                        jdbc.update(
                                "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                doctorId,
                                roleAll));

        ScopeSnapshot scope = scopeOf(doctorId);
        assertTrue(scope.all(), "任一启用角色为 ALL 即全院（A-WARD-007）");
        String sql = "SELECT id FROM pt_patient WHERE deleted = 0";
        assertEquals(sql, DataScopeGuard.enforce(sql, scope, doctorId, false), "全院不再改写");
    }

    /** A-WARD-003 边界：范围为「本人负责病区」但未分配任何病区的用户看不到患者。 */
    @Test
    void wardScopeWithoutAnyWardSeesNothing() {
        long roleWard = insertRole("ITR-SCOPE-WARD", "IT 病区角色（示例）", "WARD");
        long lonerId = insertUser("itr.wardless.demo", "IT 未分配病区用户（示例）", roleWard);

        ScopeSnapshot scope = scopeOf(lonerId);
        assertFalse(scope.all());
        assertTrue(scope.empty(), "只有 WARD 且未分配病区 → 空范围");

        String rewritten =
                DataScopeGuard.enforce(
                        "SELECT id FROM pt_patient WHERE deleted = 0", scope, lonerId, false);
        assertEquals(
                "SELECT id FROM pt_patient WHERE deleted = 0 AND 1 = 0",
                rewritten,
                "空范围追加恒假条件：看得到零行而不是报错");
    }

    /**
     * E07-T03 / PLT-PERM-008：RESEARCH_PROJECT 类型可分配并进登录装配；无 rct 模块时扩展点空实现 不返回成员项目 →
     * 该用户为空范围，患者查询追加恒假条件（与登录同一条取数路径，端到端验证扩展点可用且 fail closed）。
     */
    @Test
    void researchProjectScopeStaysEmptyUntilRctModuleLands() {
        long roleRct = insertRole("ITR-SCOPE-RCT", "IT 研究项目角色（示例）", "RESEARCH_PROJECT");
        long researcherId = insertUser("itr.researcher.demo", "IT 研究者用户（示例）", roleRct);

        ScopeSnapshot scope = scopeOf(researcherId);
        assertFalse(scope.all());
        assertTrue(scope.projectIds().isEmpty(), "空实现没有成员项目");
        assertTrue(scope.empty(), "只有研究项目类型且非任何项目成员 → 空范围");

        String rewritten =
                DataScopeGuard.enforce(
                        "SELECT id FROM pt_patient WHERE deleted = 0", scope, researcherId, false);
        assertEquals(
                "SELECT id FROM pt_patient WHERE deleted = 0 AND 1 = 0",
                rewritten,
                "研究项目分支不放宽任何数据（患者可见性随 rct 模块规则落地）");
    }

    // ---------------------------------------------------------------- 基础设施

    /** 与登录同一取数路径（HospitalDirectory.findById → assemble），不经过 HTTP 即可断言快照。 */
    private ScopeSnapshot scopeOf(long userId) {
        return runInTenant(
                () -> hospitalDirectory.findById(userId).map(HospitalAccount::scope).orElseThrow());
    }

    private long insertRole(String code, String name, String... scopeTypes) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                    + " VALUES (?, ?, 0, 'ENABLED', 'CUSTOM')",
                            code,
                            name);
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = ?", Long.class, code);
                    for (String type : scopeTypes) {
                        jdbc.update(
                                "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)",
                                roleId,
                                type);
                    }
                    return roleId;
                });
    }

    private long insertDept(String code, String name) {
        return runInTenant(
                () -> {
                    Long orgId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_org WHERE code = ?",
                                    Long.class,
                                    "ORG-DEMO");
                    jdbc.update(
                            "INSERT INTO sys_dept (org_id, parent_id, code, name, status)"
                                    + " VALUES (?, 0, ?, ?, 'ENABLED')",
                            orgId,
                            code,
                            name);
                    return deptId(code);
                });
    }

    private long insertUser(String username, String displayName, long roleId) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES (?, ?, 'IT-PLACEHOLDER', 'ENABLED', 0)",
                            username,
                            displayName);
                    long userId = userId(username);
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            userId,
                            roleId);
                    return userId;
                });
    }

    private long userId(String username) {
        return runInTenant(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private long wardId(String code) {
        return runInTenant(
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId(String code) {
        return runInTenant(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
