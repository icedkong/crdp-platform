package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.datascope.DataScopeGuard;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.system.role.RoleAdminService;
import com.crdp.server.system.role.RoleAdminService.FieldPolicyItem;
import com.crdp.server.system.role.RoleAdminService.RoleFieldPoliciesView;
import com.crdp.server.system.role.RoleAdminService.ScopeSave;
import com.crdp.server.system.user.UserPermissionPreviewService;
import com.crdp.server.system.user.UserPermissionPreviewService.UserEffectivePermissionsView;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
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
 * E07-T08 / PLT-PERM-007（tasks.csv 验收：七种范围类型用例通过）：权限、数据范围与脱敏自动化用例。
 *
 * <p>七种范围类型（ALL / WARD / DEPT / RESPONSIBLE_DEPT / CUSTOM_DEPT / SELF / RESPONSIBLE_PATIENT，
 * platform-spec §4 PLT-PERM-007「关键字字段·数据范围类型」）每种一条端到端用例，四处同口径断言：
 *
 * <ol>
 *   <li>配置面：角色范围经真实入口 {@link RoleAdminService#updateScope} 落库（类型校验、CUSTOM_DEPT 科室清单）；
 *   <li>登录面：{@link HospitalDirectory} 装配会话快照（与 AuthService / 会话重算同一条取数路径）；
 *   <li>查询面：{@link DataScopeGuard#enforce} 把快照改写成病区 / 科室 / 本人 OR 条件（tech-plan §5）；
 *   <li>排查面：{@link UserPermissionPreviewService} 预览「所见 = 会话所得」（PLT-ORG-009）。
 * </ol>
 *
 * <p>另含权限链用例（无 tenant:role:view / edit 40300、授权变化递增权限版本 PLT-PERM-010）与脱敏配置面用例（患者身份 五位字段默认
 * MASKED、角色覆盖 VISIBLE / HIDDEN、ROLE_FIELD_POLICY 审计；AGENTS.md §5.3 默认脱敏）。 脱敏<b>序列化</b>与明文查看接口属
 * E07-T05，本套件只锁定配置面与默认值不回归。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（ITR- 前缀角色 / 科室 / 病区，itr.
 * 前缀用户）。全部虚构数据，患者表尚未建（M2），改写断言与既有用例同为 SQL 口径。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PermissionScopeMaskingIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    private static final String PATIENT_SQL = "SELECT id FROM pt_patient WHERE deleted = 0";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HospitalDirectory hospitalDirectory;
    @Autowired private RoleAdminService roleAdmin;
    @Autowired private UserPermissionPreviewService previewService;
    @Autowired private PermissionVersionStore permissionVersions;

    @BeforeEach
    void cleanState() {
        runInTenant(() -> deleteItrRows(jdbc));
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, () -> deleteItrRows(jdbc));
    }

    /** 只清理本套件建的数据（ITR- 前缀角色 / 科室 / 病区、itr. 前缀用户）与相关审计。 */
    private static void deleteItrRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE ur FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE r.code LIKE 'ITR-%'");
        jdbc.update(
                "DELETE rsd FROM sys_role_scope_dept rsd JOIN sys_role r ON r.id = rsd.role_id"
                        + " WHERE r.code LIKE 'ITR-%'");
        jdbc.update(
                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                        + " WHERE r.code LIKE 'ITR-%'");
        jdbc.update(
                "DELETE fp FROM sys_role_field_policy fp JOIN sys_role r ON r.id = fp.role_id"
                        + " WHERE r.code LIKE 'ITR-%'");
        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'ITR-%'");
        jdbc.update(
                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                        + " WHERE u.username LIKE 'itr.%'");
        jdbc.update(
                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                        + " WHERE u.username LIKE 'itr.%'");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.%'");
        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'ITR-%'");
        jdbc.update("DELETE FROM ward WHERE code LIKE 'ITR-%'");
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_log WHERE event_type IN ('ROLE_SCOPE', 'ROLE_FIELD_POLICY')");
    }

    // ================================================================ 七种范围类型

    /** 类型一 ALL：任一启用角色为 ALL 即全院，不再改写 SQL（A-WARD-007）；预览不列病区 / 科室清单。 */
    @Test
    void allScopeSeesWholeTenantAndDominatesRestrictedRoles() {
        long role = roleWithScope("ITR-PSM-ALL", "IT 全院范围角色（示例）", List.of("ALL"), List.of());
        long user = userWithRoles("itr.psm.all.demo", "IT 全院用户（示例）", List.of(role));

        ScopeSnapshot scope = scopeOf(user);
        assertTrue(scope.all(), "ALL 类型 → 全院快照");
        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(PATIENT_SQL, rewritten, "全院不再追加范围条件");

        UserEffectivePermissionsView preview = previewOf(user);
        assertTrue(preview.scopeAll(), "预览同口径显示全院");
        assertTrue(typesOf(preview).contains("ALL"));
        assertTrue(preview.wards().isEmpty() && preview.depts().isEmpty(), "全院不列病区 / 科室清单");

        // 叠加一个受限角色（WARD）仍全院：多角色并集任一 ALL 即不过滤
        long wardRole =
                roleWithScope("ITR-PSM-ALL-W", "IT 全院叠加病区角色（示例）", List.of("WARD"), List.of());
        runInTenant(
                () -> {
                    setWards(user, wardId("ITR-PSM-WARD-1"));
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            user,
                            wardRole);
                });
        assertTrue(scopeOf(user).all(), "任一角色 ALL 即全院（并集不收窄）");
    }

    /**
     * 类型二 WARD（本人负责病区，A-WARD-003 / C-WARD-004）：清单取 sys_user_ward； 未分配病区 → 空范围 → 恒假条件（看不到患者而不是报错）。
     */
    @Test
    void wardScopeTakesResponsibleWardsAndEmptyWithoutAssignment() {
        long ward = wardId("ITR-PSM-WARD-1");
        long role = roleWithScope("ITR-PSM-WARD", "IT 病区范围角色（示例）", List.of("WARD"), List.of());
        long user = userWithRoles("itr.psm.ward.demo", "IT 病区用户（示例）", List.of(role));
        runInTenant(() -> setWards(user, ward));

        ScopeSnapshot scope = scopeOf(user);
        assertFalse(scope.all());
        assertTrue(scope.deptIds().isEmpty() && !scope.self(), "只有病区分支");
        assertEquals(List.of(ward), scope.wardIds(), "WARD 类型取本人负责病区清单");

        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(
                PATIENT_SQL + " AND pt_patient.ward_id IN (" + ward + ")", rewritten, "患者查询按病区过滤");

        UserEffectivePermissionsView preview = previewOf(user);
        assertFalse(preview.scopeAll());
        assertTrue(typesOf(preview).contains("WARD"));
        assertEquals(
                1, preview.wards().stream().filter(w -> w.id() == ward).count(), "预览列出可见病区（含来源角色）");

        // 边界：WARD 类型但未分配任何病区 → 空范围，追加恒假条件
        long loner = userWithRoles("itr.psm.wardless.demo", "IT 未分配病区用户（示例）", List.of(role));
        ScopeSnapshot empty = scopeOf(loner);
        assertTrue(empty.empty(), "未分配病区 → 空范围");
        assertEquals(
                PATIENT_SQL + " AND 1 = 0",
                DataScopeGuard.enforce(PATIENT_SQL, empty, loner, false),
                "空范围返回零行而不是报错");
    }

    /** 类型三 DEPT（本科室）：清单取 sys_user.main_dept_id；未配置主科室 → 空范围。 */
    @Test
    void deptScopeTakesMainDeptAndEmptyWithoutMainDept() {
        long dept = deptId("ITR-PSM-DEPT-MAIN");
        long role = roleWithScope("ITR-PSM-DEPT", "IT 本科室范围角色（示例）", List.of("DEPT"), List.of());
        long user = userWithRoles("itr.psm.dept.demo", "IT 本科室用户（示例）", List.of(role));
        runInTenant(() -> setMainDept(user, dept));

        ScopeSnapshot scope = scopeOf(user);
        assertFalse(scope.all());
        assertTrue(scope.wardIds().isEmpty() && !scope.self());
        assertEquals(List.of(dept), scope.deptIds(), "DEPT 类型取主科室");

        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(
                PATIENT_SQL + " AND pt_patient.dept_id IN (" + dept + ")", rewritten, "患者查询按科室过滤");

        UserEffectivePermissionsView preview = previewOf(user);
        assertTrue(typesOf(preview).contains("DEPT"));
        assertTrue(
                preview.depts().isEmpty(),
                "预览的科室清单只列负责科室行（sys_user_dept_scope，A-WARD-009 口径），" + "主科室作为 DEPT 类型的取值来源不在该清单");

        // 边界：DEPT 类型但未配置主科室 → 空范围
        long noMain = userWithRoles("itr.psm.nodept.demo", "IT 无主科室用户（示例）", List.of(role));
        assertTrue(scopeOf(noMain).empty(), "未配置主科室 → 空范围");
        assertEquals(
                PATIENT_SQL + " AND 1 = 0",
                DataScopeGuard.enforce(PATIENT_SQL, scopeOf(noMain), noMain, false));
    }

    /**
     * 类型四 RESPONSIBLE_DEPT（本人负责科室，C-WARD-004）：清单取 sys_user_dept_scope（负责科室单独分配，
     * 不等同于主科室与兼职科室）；用户的主科室行不生效（未触发类型不取值）。
     */
    @Test
    void responsibleDeptScopeTakesUserDeptScopeRowsOnly() {
        long dept = deptId("ITR-PSM-DEPT-RESP");
        long main = deptId("ITR-PSM-DEPT-MAIN");
        long role =
                roleWithScope(
                        "ITR-PSM-RDEPT", "IT 负责科室范围角色（示例）", List.of("RESPONSIBLE_DEPT"), List.of());
        long user = userWithRoles("itr.psm.rdept.demo", "IT 负责科室用户（示例）", List.of(role));
        runInTenant(
                () -> {
                    setDeptScope(user, dept);
                    setMainDept(user, main); // 防御：主科室不因脏数据混入
                });

        ScopeSnapshot scope = scopeOf(user);
        assertEquals(List.of(dept), scope.deptIds(), "RESPONSIBLE_DEPT 只取负责科室清单");
        assertTrue(scope.wardIds().isEmpty() && !scope.self());

        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(PATIENT_SQL + " AND pt_patient.dept_id IN (" + dept + ")", rewritten);

        UserEffectivePermissionsView preview = previewOf(user);
        assertTrue(typesOf(preview).contains("RESPONSIBLE_DEPT"));
        assertEquals(1, preview.depts().stream().filter(d -> d.id() == dept).count(), "预览列出负责科室");
    }

    /** 类型五 CUSTOM_DEPT（自定义科室）：清单取角色的 sys_role_scope_dept； 用户的负责科室与主科室行不生效（未触发类型不取值）。 */
    @Test
    void customDeptScopeTakesRoleDeptListOnly() {
        long custom = deptId("ITR-PSM-DEPT-CUSTOM");
        long resp = deptId("ITR-PSM-DEPT-RESP");
        long main = deptId("ITR-PSM-DEPT-MAIN");
        long role =
                roleWithScope(
                        "ITR-PSM-CDEPT",
                        "IT 自定义科室范围角色（示例）",
                        List.of("CUSTOM_DEPT"),
                        List.of(custom));
        long user = userWithRoles("itr.psm.cdept.demo", "IT 自定义科室用户（示例）", List.of(role));
        runInTenant(
                () -> {
                    setDeptScope(user, resp); // 防御：负责科室行不混入
                    setMainDept(user, main); // 防御：主科室不混入
                });

        ScopeSnapshot scope = scopeOf(user);
        assertEquals(List.of(custom), scope.deptIds(), "CUSTOM_DEPT 只取角色勾选的科室清单");
        assertTrue(scope.wardIds().isEmpty() && !scope.self());

        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(PATIENT_SQL + " AND pt_patient.dept_id IN (" + custom + ")", rewritten);

        UserEffectivePermissionsView preview = previewOf(user);
        assertTrue(typesOf(preview).contains("CUSTOM_DEPT"));
    }

    /** 类型六 SELF（仅本人）：患者侧等值条件 responsible_user_id = 本人，无病区 / 科室分支。 */
    @Test
    void selfScopeFiltersPatientByResponsibleUser() {
        long role = roleWithScope("ITR-PSM-SELF", "IT 仅本人范围角色（示例）", List.of("SELF"), List.of());
        long user = userWithRoles("itr.psm.self.demo", "IT 仅本人用户（示例）", List.of(role));

        ScopeSnapshot scope = scopeOf(user);
        assertFalse(scope.all());
        assertFalse(scope.empty(), "SELF 是非空受限范围");
        assertTrue(scope.wardIds().isEmpty() && scope.deptIds().isEmpty());
        assertTrue(scope.self(), "SELF 类型开启本人分支");

        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(
                PATIENT_SQL + " AND pt_patient.responsible_user_id = " + user,
                rewritten,
                "仅本人：患者责任医生 = 本人");

        UserEffectivePermissionsView preview = previewOf(user);
        assertTrue(typesOf(preview).contains("SELF"));
    }

    /**
     * 类型七 RESPONSIBLE_PATIENT（仅本人负责的患者，A-WARD-003「责任医生或任务负责人为本人的患者及其全部子数据」）： 任务表另含负责人分支 assignee =
     * 本人；无负责人语义的子表（导入行）只带患者侧条件。
     */
    @Test
    void responsiblePatientScopeCoversTaskAssignee() {
        long role =
                roleWithScope(
                        "ITR-PSM-RPAT",
                        "IT 负责患者范围角色（示例）",
                        List.of("RESPONSIBLE_PATIENT"),
                        List.of());
        long user = userWithRoles("itr.psm.rpat.demo", "IT 负责患者用户（示例）", List.of(role));

        ScopeSnapshot scope = scopeOf(user);
        assertTrue(scope.self(), "RESPONSIBLE_PATIENT 开启本人分支");

        String tasks =
                DataScopeGuard.enforce(
                        "SELECT t.id FROM fu_task t WHERE t.status = 'OVERDUE'",
                        scope,
                        user,
                        false);
        assertEquals(
                "SELECT t.id FROM fu_task t WHERE t.status = 'OVERDUE'"
                        + " AND EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id = t.patient_id"
                        + " AND crdp_p.deleted = 0"
                        + " AND (crdp_p.responsible_user_id = "
                        + user
                        + " OR t.assignee_id = "
                        + user
                        + "))",
                tasks,
                "任务表：责任医生或任务负责人为本人的患者（tech-plan §5 示例同构）");

        String rows = DataScopeGuard.enforce("SELECT r.row_no FROM imp_row r", scope, user, false);
        assertEquals(
                "SELECT r.row_no FROM imp_row r"
                        + " WHERE EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id = r.patient_id"
                        + " AND crdp_p.deleted = 0 AND crdp_p.responsible_user_id = "
                        + user
                        + ")",
                rows,
                "无负责人语义的子表只带患者侧条件");

        UserEffectivePermissionsView preview = previewOf(user);
        assertTrue(typesOf(preview).contains("RESPONSIBLE_PATIENT"));
    }

    /**
     * 六种受限类型并集（E07-T02 验收「多角色取并集，病区与科室 OR」）：三个角色分别携带 病区 / 科室三类 / 本人两类，快照合并成三分支 OR；科室三分支（主科室 ∪ 负责科室
     * ∪ 自定义科室）去重后同在 dept_id IN 清单。
     */
    @Test
    void restrictedTypesUnionMergesWardDeptAndSelfBranchesWithOr() {
        long ward = wardId("ITR-PSM-WARD-1");
        long main = deptId("ITR-PSM-DEPT-MAIN");
        long resp = deptId("ITR-PSM-DEPT-RESP");
        long custom = deptId("ITR-PSM-DEPT-CUSTOM");
        long roleWardDept =
                roleWithScope("ITR-PSM-U-A", "IT 并集角色甲（示例）", List.of("WARD", "DEPT"), List.of());
        long roleDepts =
                roleWithScope(
                        "ITR-PSM-U-B",
                        "IT 并集角色乙（示例）",
                        List.of("RESPONSIBLE_DEPT", "CUSTOM_DEPT"),
                        List.of(custom));
        long roleSelf =
                roleWithScope(
                        "ITR-PSM-U-C",
                        "IT 并集角色丙（示例）",
                        List.of("SELF", "RESPONSIBLE_PATIENT"),
                        List.of());
        long user =
                userWithRoles(
                        "itr.psm.union.demo",
                        "IT 并集用户（示例）",
                        List.of(roleWardDept, roleDepts, roleSelf));
        runInTenant(
                () -> {
                    setWards(user, ward);
                    setMainDept(user, main);
                    setDeptScope(user, resp);
                });

        ScopeSnapshot scope = scopeOf(user);
        assertFalse(scope.all(), "没有 ALL 角色，仍是受限范围");
        assertEquals(List.of(ward), scope.wardIds());
        assertEquals(
                List.of(main, resp, custom), scope.deptIds(), "科室分支 = 主科室 ∪ 负责科室 ∪ 角色自定义科室（去重保序）");
        assertTrue(scope.self(), "SELF / RESPONSIBLE_PATIENT 并入本人分支");

        String rewritten = DataScopeGuard.enforce(PATIENT_SQL, scope, user, false);
        assertEquals(
                PATIENT_SQL
                        + " AND (pt_patient.ward_id IN ("
                        + ward
                        + ") OR pt_patient.dept_id IN ("
                        + main
                        + ", "
                        + resp
                        + ", "
                        + custom
                        + ") OR pt_patient.responsible_user_id = "
                        + user
                        + ")",
                rewritten,
                "病区与科室无上下级关系，三组分支恒 OR（C-WARD-004）");

        UserEffectivePermissionsView preview = previewOf(user);
        assertFalse(preview.scopeAll());
        for (String type :
                List.of(
                        "WARD",
                        "DEPT",
                        "RESPONSIBLE_DEPT",
                        "CUSTOM_DEPT",
                        "SELF",
                        "RESPONSIBLE_PATIENT")) {
            assertTrue(typesOf(preview).contains(type), "预览应含类型 " + type);
        }
    }

    // ================================================================ 权限链

    /**
     * 权限链（AGENTS.md §5.9、§6）：范围与字段策略配置接口要求 tenant:role:view / edit——无权限 40300（前端 隐藏不能代替后端校验）；
     * 管理员保存后成员权限版本递增（PLT-PERM-010，旧会话下一次请求即按新范围重算），并写 ROLE_SCOPE 审计。
     */
    @Test
    void scopeAndFieldPolicyConfigRequirePermissionAndBumpMemberVersion() {
        long role = roleWithScope("ITR-PSM-PERM", "IT 权限链角色（示例）", List.of("WARD"), List.of());
        long member = userWithRoles("itr.psm.member.demo", "IT 权限链成员（示例）", List.of(role));
        String doctor = hospitalToken("doctor.demo");
        String admin = hospitalToken("nurse.demo");

        // 医生（无 tenant:role:view / edit）：查看与保存一律 40300
        assertEquals(40300, code(get("/api/hospital/system/roles", doctor)));
        assertEquals(40300, code(get("/api/hospital/system/roles/" + role + "/scope", doctor)));
        assertEquals(
                40300,
                code(
                        put(
                                "/api/hospital/system/roles/" + role + "/scope",
                                doctor,
                                Map.of(
                                        "scopeTypes",
                                        List.of("SELF"),
                                        "customDeptIds",
                                        List.of()))));
        assertEquals(
                40300, code(get("/api/hospital/system/roles/" + role + "/field-policies", doctor)));
        assertEquals(
                40300,
                code(
                        put(
                                "/api/hospital/system/roles/" + role + "/field-policies",
                                doctor,
                                Map.of("policies", List.of()))));

        // 管理员可查看并保存；保存后成员权限版本递增（PLT-PERM-010）
        assertEquals(0, code(get("/api/hospital/system/roles/" + role + "/scope", admin)));
        long before = permissionVersions.current("HOSPITAL", TENANT_ID, member);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + role + "/scope",
                                admin,
                                Map.of(
                                        "scopeTypes",
                                        List.of("SELF"),
                                        "customDeptIds",
                                        List.of()))));
        long after = permissionVersions.current("HOSPITAL", TENANT_ID, member);
        assertTrue(after > before, "范围变化应递增成员权限版本（" + before + " → " + after + "）");

        Integer audits =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'ROLE_SCOPE'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1, "范围保存应写 ROLE_SCOPE 审计，实际 " + audits);
    }

    // ================================================================ 脱敏配置面

    /**
     * 脱敏（AGENTS.md §5.3、PLT-PERM-009 配置面）：患者身份五位字段（姓名 / 证件号 / 手机号 / 病案号 / 住址） 平台默认一律 MASKED；角色可覆盖为
     * VISIBLE / HIDDEN（未覆盖字段继续默认），非法策略与目录外字段 40001；保存写 ROLE_FIELD_POLICY 审计。 序列化脱敏与明文查看接口随
     * E07-T05，此处锁定默认值与配置面。
     */
    @Test
    void patientIdentityFieldsDefaultMaskedAndRoleOverridesPersist() {
        long roleId = insertRole("ITR-PSM-FIELD", "IT 字段策略角色（示例）");

        RoleFieldPoliciesView view = runInTenant(() -> roleAdmin.fieldPoliciesView(roleId));
        assertEquals(5, view.fields().size(), "敏感字段目录应含患者身份五位字段");
        for (var field : view.fields()) {
            assertEquals("MASKED", field.defaultPolicy(), field.field() + " 平台默认应为脱敏");
            assertEquals("MASKED", field.policy(), "未配置的角色回落平台默认（脱敏）");
            assertFalse(field.overridden());
        }
        assertEquals(
                List.of("name", "idCard", "phone", "medicalRecordNo", "address"),
                view.fields().stream().map(f -> f.field()).toList(),
                "目录字段与顺序（docs/api/patient.md 口径）");

        // 覆盖：姓名可见、住址隐藏；其余继续跟随默认
        runInTenant(
                () ->
                        roleAdmin.updateFieldPolicies(
                                roleId,
                                List.of(
                                        new FieldPolicyItem("patient", "name", "VISIBLE"),
                                        new FieldPolicyItem("patient", "address", "HIDDEN"))));
        RoleFieldPoliciesView overridden = runInTenant(() -> roleAdmin.fieldPoliciesView(roleId));
        assertTrue(
                overridden.fields().stream()
                        .anyMatch(
                                f ->
                                        "name".equals(f.field())
                                                && "VISIBLE".equals(f.policy())
                                                && f.overridden()),
                "姓名覆盖为可见");
        assertTrue(
                overridden.fields().stream()
                        .anyMatch(
                                f ->
                                        "address".equals(f.field())
                                                && "HIDDEN".equals(f.policy())
                                                && f.overridden()),
                "住址覆盖为隐藏");
        assertTrue(
                overridden.fields().stream()
                        .anyMatch(
                                f ->
                                        "phone".equals(f.field())
                                                && "MASKED".equals(f.policy())
                                                && !f.overridden()),
                "未覆盖字段继续默认脱敏");

        // 校验失败：非法策略值 / 目录外字段（40001）
        ApiException badPolicy =
                assertThrows(
                        ApiException.class,
                        () ->
                                runInTenant(
                                        () ->
                                                roleAdmin.updateFieldPolicies(
                                                        roleId,
                                                        List.of(
                                                                new FieldPolicyItem(
                                                                        "patient", "name",
                                                                        "PLAIN")))));
        assertEquals(40001, badPolicy.code());
        ApiException badField =
                assertThrows(
                        ApiException.class,
                        () ->
                                runInTenant(
                                        () ->
                                                roleAdmin.updateFieldPolicies(
                                                        roleId,
                                                        List.of(
                                                                new FieldPolicyItem(
                                                                        "patient",
                                                                        "nickname",
                                                                        "VISIBLE")))));
        assertEquals(40001, badField.code());

        // 存储与审计留痕
        Integer rows =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_field_policy WHERE role_id"
                                                + " = ?",
                                        Integer.class,
                                        roleId));
        assertEquals(2, rows, "显式覆盖应落两行");
        Integer audits =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'ROLE_FIELD_POLICY'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1, "字段策略保存应写审计，实际 " + audits);
    }

    // ---------------------------------------------------------------- 基础设施

    /** 与登录同一取数路径（HospitalDirectory.findById → assemble），不经 HTTP 即可断言快照。 */
    private ScopeSnapshot scopeOf(long userId) {
        return runInTenant(
                () -> hospitalDirectory.findById(userId).map(HospitalAccount::scope).orElseThrow());
    }

    private UserEffectivePermissionsView previewOf(long userId) {
        return runInTenant(() -> previewService.preview(userId));
    }

    private static List<String> typesOf(UserEffectivePermissionsView preview) {
        return preview.scopeTypes().stream().map(type -> type.type()).toList();
    }

    /** 建自定义角色并经真实配置入口 {@link RoleAdminService#updateScope} 写入范围（类型校验 / 审计 / 版本递增同生产）。 */
    private long roleWithScope(
            String code, String name, List<String> types, List<Long> customDepts) {
        long roleId = insertRole(code, name);
        runInTenant(() -> roleAdmin.updateScope(roleId, new ScopeSave(types, customDepts)));
        return roleId;
    }

    private long insertRole(String code, String name) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                    + " VALUES (?, ?, 0, 'ENABLED', 'CUSTOM')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_role WHERE code = ?", Long.class, code);
                });
    }

    private long userWithRoles(String username, String displayName, List<Long> roles) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES (?, ?, 'IT-PLACEHOLDER', 'ENABLED', 0)",
                            username,
                            displayName);
                    long userId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = ?",
                                    Long.class,
                                    username);
                    for (long roleId : roles) {
                        jdbc.update(
                                "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                userId,
                                roleId);
                    }
                    return userId;
                });
    }

    private void setWards(long userId, long wardId) {
        jdbc.update("INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)", userId, wardId);
    }

    private void setDeptScope(long userId, long deptId) {
        jdbc.update(
                "INSERT INTO sys_user_dept_scope (user_id, dept_id) VALUES (?, ?)", userId, deptId);
    }

    private void setMainDept(long userId, Long deptId) {
        jdbc.update("UPDATE sys_user SET main_dept_id = ? WHERE id = ?", deptId, userId);
    }

    private long wardId(String code) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            "IT 病区（示例）");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long deptId(String code) {
        return runInTenant(
                () -> {
                    List<Long> existing =
                            jdbc.queryForList(
                                    "SELECT id FROM sys_dept WHERE code = ?", Long.class, code);
                    if (!existing.isEmpty()) {
                        return existing.get(0);
                    }
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
                            "IT 科室（示例）");
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_dept WHERE code = ?", Long.class, code);
                });
    }

    // ---------------------------------------------------------------- HTTP（权限链用例）

    private String hospitalToken(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD));
        assertEquals(0, code(response), username + " 应能登录");
        return (String) data(response).get("token");
    }

    private Map<?, ?> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class)
                .getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return rest.exchange(
                        path, HttpMethod.PUT, new HttpEntity<>(body, headers(token)), Map.class)
                .getBody();
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return rest.postForEntity(path, new HttpEntity<>(body, headers(token)), Map.class)
                .getBody();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    // ---------------------------------------------------------------- 租户上下文

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
