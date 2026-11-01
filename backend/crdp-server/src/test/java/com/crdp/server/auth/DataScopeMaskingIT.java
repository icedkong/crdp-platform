package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.crdp.framework.datascope.DataScopeContext;
import com.crdp.framework.datascope.DataScopeInnerInterceptor;
import com.crdp.framework.fieldpolicy.MaskedByPolicy;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.system.role.RoleAdminService;
import com.crdp.server.system.role.RoleAdminService.FieldPolicyItem;
import com.crdp.server.system.role.RoleAdminService.ScopeSave;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E07-T07（tasks.csv 验收：各范围类型用例全部通过）：数据范围 × 脱敏自动化用例——每条用例都把「行可见性」与「字段输出」 两条生产链路串起来跑：
 *
 * <ol>
 *   <li>配置面：角色范围经真实入口 {@link RoleAdminService#updateScope}、字段策略经 {@link
 *       RoleAdminService#updateFieldPolicies} 落库；
 *   <li>登录面：真实 HTTP 登录 → {@link SessionStore} 取回生产会话（快照与字段策略在登录时冻结）；
 *   <li>查询面：生产 {@link MybatisPlusInterceptor} 里注册的 {@link DataScopeInnerInterceptor}（ {@link
 *       com.crdp.framework.datascope.MybatisPlusConfig} 同一实例）改写患者 / 任务 SQL——按该用户的范围类型；
 *   <li>序列化面：Boot 主 {@link ObjectMapper}（已并入 FieldPolicyJacksonModule）对 {@link MaskedByPolicy} DTO
 *       按该用户字段策略输出脱敏 / 明文 / 隐藏。
 * </ol>
 *
 * <p>与 E07-T08 {@code PermissionScopeMaskingIT}（快照装配 / 预览 / 配置面）和 E07-T05 {@code
 * PatientPlaintextIT}（明文专用接口）互补；本套件锁定两条正交不变量：<b>范围放得再宽（ALL）也不放宽脱敏</b>，
 * <b>字段策略放成明文（VISIBLE）也不放宽行范围</b>。运行链路（切面 → ThreadLocal 上下文 → 拦截器）单测见 framework {@code
 * DataScopeRuntimeChainTest}。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（ITR-SM 前缀角色 / 科室 / 病区， itr.sm.
 * 前缀用户）。全部虚构数据；患者表随 E13-T01 建表，改写断言与既有套件同为 SQL 口径。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class DataScopeMaskingIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    private static final String PATIENT_SQL = "SELECT id FROM pt_patient WHERE deleted = 0";
    private static final String TASK_SQL = "SELECT t.id FROM fu_task t WHERE t.status = 'OVERDUE'";

    /** 与 E13 起的患者 DTO 同构的最小样例（字段值全部虚构）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SamplePatientRow(
            @MaskedByPolicy(resource = "patient", field = "name") String name,
            @MaskedByPolicy(resource = "patient", field = "idCard") String idCard,
            @MaskedByPolicy(resource = "patient", field = "phone") String phone,
            @MaskedByPolicy(resource = "patient", field = "medicalRecordNo") String medicalRecordNo,
            @MaskedByPolicy(resource = "patient", field = "address") String address,
            String gender) {}

    private static final SamplePatientRow SAMPLE =
            new SamplePatientRow(
                    "张小明", "11010219900101567X", "13800005678", "MR1000001", "北京市东城区某某街道某小区", "男");

    /** 五个敏感字段全部按目录默认 MASKED 的期望输出（SensitiveValueMasker 口径）。 */
    private static final String FULLY_MASKED_JSON =
            "{\"name\":\"张*明\",\"idCard\":\"110102********567X\",\"phone\":\"138****5678\","
                    + "\"medicalRecordNo\":\"MR*****01\",\"address\":\"北京市东城区****\",\"gender\":\"男\"}";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoleAdminService roleAdmin;
    @Autowired private SessionStore sessionStore;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MybatisPlusInterceptor mybatisPlusInterceptor;

    private DataScopeInnerInterceptor scopeInterceptor;

    @BeforeEach
    void cleanState() {
        scopeInterceptor =
                mybatisPlusInterceptor.getInterceptors().stream()
                        .filter(DataScopeInnerInterceptor.class::isInstance)
                        .map(DataScopeInnerInterceptor.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("生产拦截器链未注册数据范围拦截器"));
        runInTenant(() -> deleteItrRows(jdbc));
    }

    @AfterEach
    void clearThreadLocals() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, () -> deleteItrRows(jdbc));
    }

    /** 只清理本套件建的数据（ITR-SM 前缀角色 / 科室 / 病区、itr.sm. 前缀用户）与其登录、配置审计。 */
    private static void deleteItrRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE ur FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE r.code LIKE 'ITR-SM-%'");
        jdbc.update(
                "DELETE rsd FROM sys_role_scope_dept rsd JOIN sys_role r ON r.id = rsd.role_id"
                        + " WHERE r.code LIKE 'ITR-SM-%'");
        jdbc.update(
                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                        + " WHERE r.code LIKE 'ITR-SM-%'");
        jdbc.update(
                "DELETE fp FROM sys_role_field_policy fp JOIN sys_role r ON r.id = fp.role_id"
                        + " WHERE r.code LIKE 'ITR-SM-%'");
        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'ITR-SM-%'");
        jdbc.update(
                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                        + " WHERE u.username LIKE 'itr.sm.%'");
        jdbc.update(
                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                        + " WHERE u.username LIKE 'itr.sm.%'");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.sm.%'");
        jdbc.update("DELETE FROM sys_dept WHERE code LIKE 'ITR-SM-%'");
        jdbc.update("DELETE FROM ward WHERE code LIKE 'ITR-SM-%'");
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_log WHERE event_type IN ('ROLE_SCOPE', 'ROLE_FIELD_POLICY')");
        AuditTableMaintenance.delete(
                jdbc, "DELETE FROM aud_login_log WHERE username LIKE 'itr.sm.%'");
    }

    // ================================================================ 各范围类型 × 脱敏

    /** 类型 ALL：全院不改写 SQL；但没有显式放宽字段策略时输出仍然全部脱敏——范围最宽不放宽脱敏。 */
    @Test
    void allScopeRewritesNothingAndStillMasksByDefault() throws Exception {
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-ALL", "itr.sm.all.demo", List.of("ALL"), List.of(), List.of());

        assertTrue(principal.scope().all(), "任一角色 ALL 即全院");
        assertEquals(PATIENT_SQL, executedPatientSql(principal), "全院不再追加范围条件");
        assertEquals(FULLY_MASKED_JSON, serialized(principal), "ALL 用户的敏感字段默认仍全部脱敏");
    }

    /** 类型 WARD（本人负责病区）：患者 SQL 按病区 IN 过滤；输出默认脱敏。 */
    @Test
    void wardScopeFiltersByResponsibleWardsAndMasks() throws Exception {
        long ward = wardId("ITR-SM-WARD");
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-WARD", "itr.sm.ward.demo", List.of("WARD"), List.of(), List.of());
        setWards(principal.userId(), ward);

        principal = loginPrincipal("itr.sm.ward.demo");
        assertEquals(List.of(ward), principal.scope().wardIds(), "WARD 取本人负责病区清单");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.ward_id IN (" + ward + ")",
                executedPatientSql(principal),
                "患者查询按病区过滤");
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    /** 类型 DEPT（本科室）：患者 SQL 按主科室过滤；输出默认脱敏。 */
    @Test
    void deptScopeFiltersByMainDeptAndMasks() throws Exception {
        long dept = deptId("ITR-SM-DEPT-MAIN");
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-DEPT", "itr.sm.dept.demo", List.of("DEPT"), List.of(), List.of());
        jdbcUpdate("UPDATE sys_user SET main_dept_id = ? WHERE id = ?", dept, principal.userId());

        principal = loginPrincipal("itr.sm.dept.demo");
        assertEquals(List.of(dept), principal.scope().deptIds(), "DEPT 取主科室");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.dept_id IN (" + dept + ")",
                executedPatientSql(principal));
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    /** 类型 RESPONSIBLE_DEPT（本人负责科室）：患者 SQL 按负责科室清单过滤；输出默认脱敏。 */
    @Test
    void responsibleDeptScopeFiltersByUserDeptScopeRowsAndMasks() throws Exception {
        long dept = deptId("ITR-SM-DEPT-RESP");
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-RDEPT",
                        "itr.sm.rdept.demo",
                        List.of("RESPONSIBLE_DEPT"),
                        List.of(),
                        List.of());
        jdbcUpdate(
                "INSERT INTO sys_user_dept_scope (user_id, dept_id) VALUES (?, ?)",
                principal.userId(),
                dept);

        principal = loginPrincipal("itr.sm.rdept.demo");
        assertEquals(List.of(dept), principal.scope().deptIds(), "RESPONSIBLE_DEPT 取负责科室行");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.dept_id IN (" + dept + ")",
                executedPatientSql(principal));
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    /** 类型 CUSTOM_DEPT（自定义科室）：患者 SQL 按角色勾选科室过滤；输出默认脱敏。 */
    @Test
    void customDeptScopeFiltersByRoleDeptListAndMasks() throws Exception {
        long custom = deptId("ITR-SM-DEPT-CUSTOM");
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-CDEPT",
                        "itr.sm.cdept.demo",
                        List.of("CUSTOM_DEPT"),
                        List.of(custom),
                        List.of());

        assertEquals(List.of(custom), principal.scope().deptIds(), "CUSTOM_DEPT 取角色勾选科室");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.dept_id IN (" + custom + ")",
                executedPatientSql(principal));
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    /** 类型 SELF（仅本人）：患者 SQL 等值责任医生；输出默认脱敏。 */
    @Test
    void selfScopeFiltersByResponsibleUserAndMasks() throws Exception {
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-SELF", "itr.sm.self.demo", List.of("SELF"), List.of(), List.of());

        assertTrue(principal.scope().self(), "SELF 开启本人分支");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.responsible_user_id = " + principal.userId(),
                executedPatientSql(principal),
                "仅本人：责任医生 = 本人");
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    /**
     * 类型 RESPONSIBLE_PATIENT（仅本人负责的患者）：患者侧等值责任医生，任务表另含负责人分支（ A-WARD-003「责任医生或任务负责人为本人的患者」）；输出默认脱敏。
     */
    @Test
    void responsiblePatientScopeCoversTaskAssigneeAndMasks() throws Exception {
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-RPAT",
                        "itr.sm.rpat.demo",
                        List.of("RESPONSIBLE_PATIENT"),
                        List.of(),
                        List.of());
        long userId = principal.userId();

        assertEquals(
                PATIENT_SQL + " AND pt_patient.responsible_user_id = " + userId,
                executedPatientSql(principal));
        assertEquals(
                TASK_SQL
                        + " AND EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id ="
                        + " t.patient_id AND crdp_p.deleted = 0"
                        + " AND (crdp_p.responsible_user_id = "
                        + userId
                        + " OR t.assignee_id = "
                        + userId
                        + "))",
                executedTaskSql(principal),
                "任务表：责任医生或任务负责人为本人的患者");
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    /**
     * 类型 RESEARCH_PROJECT（研究项目扩展，PLT-PERM-008）：rct 模块未落地、扩展点空实现 → 空范围恒假条件（ 默认拒绝，不放宽任何数据）；输出默认脱敏。
     */
    @Test
    void researchProjectScopeStaysEmptyAndMasks() throws Exception {
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-RCT",
                        "itr.sm.rct.demo",
                        List.of("RESEARCH_PROJECT"),
                        List.of(),
                        List.of());

        assertFalse(principal.scope().all());
        assertTrue(principal.scope().empty(), "非项目成员 → 空范围");
        assertEquals(
                PATIENT_SQL + " AND 1 = 0",
                executedPatientSql(principal),
                "研究项目分支不放宽任何数据（患者可见性随 rct 模块规则落地）");
        assertEquals(FULLY_MASKED_JSON, serialized(principal));
    }

    // ================================================================ 两条正交不变量

    /**
     * 不变量一（AGENTS.md §5.3 默认脱敏）：范围最宽（ALL）也不放宽脱敏——见 {@link
     * #allScopeRewritesNothingAndStillMasksByDefault()}。 不变量二：字段策略放成明文（VISIBLE）也不放宽行范围——同一用户的 SQL
     * 仍按病区过滤，明文只作用于序列化输出。
     */
    @Test
    void visibleFieldPolicyDoesNotWidenScope() throws Exception {
        long ward = wardId("ITR-SM-WARD");
        SessionPrincipal principal =
                loginScopedUser(
                        "ITR-SM-VISIBLE",
                        "itr.sm.visible.demo",
                        List.of("WARD"),
                        List.of(),
                        List.of(
                                new FieldPolicyItem("patient", "name", "VISIBLE"),
                                new FieldPolicyItem("patient", "address", "HIDDEN")));
        setWards(principal.userId(), ward);

        principal = loginPrincipal("itr.sm.visible.demo");
        assertEquals("VISIBLE", principal.fieldPolicies().decide("patient", "name"), "角色覆盖进会话");
        assertEquals(
                "{\"name\":\"张小明\",\"idCard\":\"110102********567X\",\"phone\":\"138****5678\","
                        + "\"medicalRecordNo\":\"MR*****01\",\"gender\":\"男\"}",
                serialized(principal),
                "姓名随策略输出明文、住址整字段隐藏，其余默认脱敏");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.ward_id IN (" + ward + ")",
                executedPatientSql(principal),
                "明文输出不改变行范围：仍只见本人负责病区的患者");
    }

    // ---------------------------------------------------------------- 基础设施

    /** 建「自定义角色 + 范围类型 + 可选自定义科室 + 可选字段策略覆盖」的用户并登录，返回生产会话。 用户口令哈希复用种子医生（Argon2id），密码同 Demo@2026。 */
    private SessionPrincipal loginScopedUser(
            String roleCode,
            String username,
            List<String> scopeTypes,
            List<Long> customDeptIds,
            List<FieldPolicyItem> fieldPolicies) {
        long roleId =
                runInTenant(
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                            + " VALUES (?, 'IT 范围脱敏角色（示例）', 0, 'ENABLED',"
                                            + " 'CUSTOM')",
                                    roleCode);
                            return jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
                        });
        runInTenant(
                () -> {
                    roleAdmin.updateScope(roleId, new ScopeSave(scopeTypes, customDeptIds));
                    if (!fieldPolicies.isEmpty()) {
                        roleAdmin.updateFieldPolicies(roleId, fieldPolicies);
                    }
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) SELECT ?, 'IT 范围脱敏用户（示例）',"
                                    + " password_hash, 'ENABLED', 0 FROM sys_user WHERE username ="
                                    + " 'doctor.demo'",
                            username);
                    long userId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = ?",
                                    Long.class,
                                    username);
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            userId,
                            roleId);
                });
        return loginPrincipal(username);
    }

    /** 真实 HTTP 登录后从 {@link SessionStore} 取回生产会话（快照与字段策略登录时冻结）。 */
    private SessionPrincipal loginPrincipal(String username) {
        Map<?, ?> response =
                rest.exchange(
                                "/api/hospital/auth/login",
                                HttpMethod.POST,
                                new HttpEntity<>(
                                        Map.of(
                                                "tenantCode",
                                                TENANT,
                                                "username",
                                                username,
                                                "password",
                                                PASSWORD),
                                        jsonHeaders()),
                                Map.class)
                        .getBody();
        assertEquals(0, ((Number) response.get("code")).intValue(), username + " 应能登录");
        String token = (String) ((Map<?, ?>) response.get("data")).get("token");
        return sessionStore.find(token).orElseThrow();
    }

    /**
     * 查询面：以该会话快照走生产数据范围拦截器（切面同款 ThreadLocal 写法），返回最终交给 MySQL 的患者 SQL。 患者表随 E13-T01 建，断言与既有套件同为 SQL
     * 口径。
     */
    private String executedPatientSql(SessionPrincipal principal) throws SQLException {
        return executedSql(principal, PATIENT_SQL);
    }

    private String executedTaskSql(SessionPrincipal principal) throws SQLException {
        return executedSql(principal, TASK_SQL);
    }

    private String executedSql(SessionPrincipal principal, String sql) throws SQLException {
        DataScopeContext.set(principal.scope(), principal.userId());
        try {
            BoundSql boundSql = new BoundSql(new Configuration(), sql, List.of(), null);
            scopeInterceptor.beforeQuery(null, null, null, null, null, boundSql);
            return boundSql.getSql();
        } finally {
            DataScopeContext.clear();
        }
    }

    /**
     * 序列化面：像 TokenAuthenticationFilter 一样把会话放进 SecurityContext，用 Boot 主 ObjectMapper（ 已并入
     * FieldPolicyJacksonModule）序列化样例 DTO——与真实接口响应同一套序列化链。
     */
    private String serialized(SessionPrincipal principal) throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try {
            return objectMapper.writeValueAsString(SAMPLE);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void setWards(long userId, long wardId) {
        jdbcUpdate("INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)", userId, wardId);
    }

    private void jdbcUpdate(String sql, Object... args) {
        runInTenant(() -> jdbc.update(sql, args));
    }

    private long wardId(String code) {
        return runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, 'IT 病区（示例）',"
                                    + " 'ENABLED')",
                            code);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long deptId(String code) {
        return runInTenant(
                () -> {
                    Long orgId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_org WHERE code = ?",
                                    Long.class,
                                    "ORG-DEMO");
                    jdbc.update(
                            "INSERT INTO sys_dept (org_id, parent_id, code, name, status)"
                                    + " VALUES (?, 0, ?, 'IT 科室（示例）', 'ENABLED')",
                            orgId,
                            code);
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_dept WHERE code = ?", Long.class, code);
                });
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
