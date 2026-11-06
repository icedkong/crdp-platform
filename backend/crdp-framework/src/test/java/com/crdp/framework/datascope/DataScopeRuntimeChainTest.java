package com.crdp.framework.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E07-T07（tasks.csv 验收：各范围类型用例全部通过）：数据范围<b>运行链路</b>自动化用例。
 *
 * <p>既有用例（{@code DataScopeGuardTest}、E07-T02 / T08 的 IT）都在静态调用 {@link
 * DataScopeGuard#enforce}；本套件补上「请求线程真正走的这一段」：{@link DataScopeAspect} 从会话快照 开 {@link
 * DataScopeContext}（ThreadLocal）→ 生产注册的 {@link DataScopeInnerInterceptor}（{@link MybatisPlusConfig}
 * 同一实例口径）在 MyBatis 查询前改写 {@link BoundSql}。各范围类型（ALL / 病区 / 科室 / 本人 / 组合并 / 空范围 / 研究项目 fail
 * closed）逐例通过；上下文缺失 → 40302 默认拒绝；{@code @IgnoreScope} 旁路 后清理，不泄漏到下一个调用。
 *
 * <p>数据库侧（角色配置 → 登录快照 → 脱敏序列化）的组合用例见 crdp-server {@code DataScopeMaskingIT}。
 */
class DataScopeRuntimeChainTest {

    private static final long USER_ID = 9L;
    private static final String PATIENT_SQL = "SELECT id FROM pt_patient WHERE deleted = 0";

    /** 与 {@link MybatisPlusConfig} 注册到 MybatisPlusInterceptor 的同一拦截器实现。 */
    private final DataScopeInnerInterceptor interceptor = new DataScopeInnerInterceptor();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        DataScopeContext.clear();
    }

    // ---------------------------------------------------------------- 切面开 / 关上下文

    /**
     * @DataScope 方法执行期间上下文携带会话快照与用户 id，返回后清理（不泄漏到同线程后续查询）。
     */
    @Test
    void aspectOpensScopeContextFromSessionAndClearsItAfterwards() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(7L), List.of(3L), true);
        loginAs(scope);
        ScopedQueryService target = new ScopedQueryService();
        ScopedQueryService service = proxied(target);

        service.queryPatients();

        assertEquals(scope, target.seenSnapshot, "切面应把会话快照放进范围上下文");
        assertEquals(USER_ID, target.seenUserId, "「仅本人负责」等值分支需要用户 id");
        assertFalse(target.seenIgnored);
        assertNull(DataScopeContext.get(), "方法返回后上下文必须清理");
    }

    /** 业务异常也不能把 ThreadLocal 范围上下文留在请求线程上（finally 清理）。 */
    @Test
    void aspectClearsScopeContextEvenWhenScopedMethodThrows() {
        loginAs(ScopeSnapshot.restricted(List.of(7L), List.of(), false));
        ScopedQueryService service = proxied(new ScopedQueryService());

        assertThrows(IllegalStateException.class, service::boom);
        assertNull(DataScopeContext.get(), "异常路径同样清理上下文");
    }

    /** 无会话调用 @DataScope 入口：40100（CurrentSession.require），且不放任何范围上下文。 */
    @Test
    void scopedEntryWithoutSessionIsRejectedWith40100() {
        SecurityContextHolder.clearContext();
        ScopedQueryService service = proxied(new ScopedQueryService());

        ApiException rejected = assertThrows(ApiException.class, service::queryPatients);
        assertEquals(40100, rejected.code());
        assertNull(DataScopeContext.get());
    }

    /**
     * @IgnoreScope 旁路（调度入口写明原因后使用）：上下文标记忽略，拦截器不再改写；结束后同样清理。
     */
    @Test
    void ignoreScopeMarksContextIgnoredAndCleansUp() {
        loginAs(ScopeSnapshot.restricted(List.of(7L), List.of(), false));
        ScopedQueryService target = new ScopedQueryService();
        ScopedQueryService service = proxied(target);

        service.exportRaw();

        assertTrue(target.seenIgnored, "@IgnoreScope 方法内 ignored=true");
        assertNull(target.seenSnapshot, "旁路不放置范围快照");
        assertFalse(DataScopeContext.ignored(), "方法返回后忽略标记清理");
        assertNull(DataScopeContext.get());
    }

    // ---------------------------------------------------------------- 拦截器按范围类型改写 BoundSql

    /** 各范围类型经生产拦截器改写 BoundSql（患者主表）：病区 / 科室 / 本人分支按快照拼装，单分支不加括号。 */
    @Test
    void interceptorRewritesBoundSqlPerScopeSnapshot() throws SQLException {
        assertEquals(
                PATIENT_SQL + " AND pt_patient.ward_id IN (7)",
                scoped(ScopeSnapshot.restricted(List.of(7L), List.of(), false)),
                "WARD：本人负责病区 IN");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.dept_id IN (3)",
                scoped(ScopeSnapshot.restricted(List.of(), List.of(3L), false)),
                "DEPT / RESPONSIBLE_DEPT / CUSTOM_DEPT：科室清单 IN");
        assertEquals(
                PATIENT_SQL + " AND pt_patient.responsible_user_id = " + USER_ID,
                scoped(ScopeSnapshot.restricted(List.of(), List.of(), true)),
                "SELF / RESPONSIBLE_PATIENT：责任医生等值分支");
        assertEquals(
                PATIENT_SQL
                        + " AND (pt_patient.ward_id IN (7)"
                        + " OR pt_patient.dept_id IN (3, 4)"
                        + " OR pt_patient.responsible_user_id = "
                        + USER_ID
                        + ")",
                scoped(ScopeSnapshot.restricted(List.of(7L), List.of(3L, 4L), true)),
                "多角色并集：病区与科室恒 OR（C-WARD-004），本人分支同组");
        assertEquals(PATIENT_SQL, scoped(ScopeSnapshot.allowAll()), "ALL：全院不改写");
        assertEquals(
                PATIENT_SQL + " AND 1 = 0",
                scoped(ScopeSnapshot.restricted(List.of(), List.of(), false)),
                "空范围：恒假条件，返回零行而不是报错（A-WARD-003 边界）");
    }

    /** 子表（任务）经拦截器改写：EXISTS 关联患者 + 任务负责人分支（A-WARD-003）。 */
    @Test
    void interceptorRewritesChildTableWithExistsAndOwnerBranch() throws SQLException {
        String sql = "SELECT t.id FROM fu_task t WHERE t.status = 'OVERDUE'";

        String rewritten = scoped(ScopeSnapshot.restricted(List.of(), List.of(), true), sql);

        assertEquals(
                sql
                        + " AND EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id = t.patient_id"
                        + " AND crdp_p.deleted = 0"
                        + " AND (crdp_p.responsible_user_id = "
                        + USER_ID
                        + " OR t.assignee_id = "
                        + USER_ID
                        + "))",
                rewritten,
                "任务表：责任医生或任务负责人为本人的患者");
    }

    /** 默认拒绝：受控表查询但没有范围上下文 → 40302，SQL 不放行给数据库（E07-T01 口径）。 */
    @Test
    void interceptorDeniesControlledQueryWithoutScopeContext() {
        DataScopeContext.clear();

        DataScopeDeniedException denied =
                assertThrows(DataScopeDeniedException.class, () -> scoped(null));

        assertEquals(40302, denied.code(), "后端缺陷口径：未声明范围 40302");
    }

    /** 非受控表（sys_user 等）不拦截；@IgnoreScope 旁路的受控 SQL 原样放行。 */
    @Test
    void interceptorLeavesUncontrolledAndIgnoredSqlAlone() throws SQLException {
        DataScopeContext.clear();
        assertEquals(
                "SELECT id FROM sys_user WHERE status = 'ENABLED'",
                rewritten("SELECT id FROM sys_user WHERE status = 'ENABLED'"),
                "非受控表不改写");

        DataScopeContext.ignore();
        assertEquals(PATIENT_SQL, rewritten(PATIENT_SQL), "ignored=true 时受控表原样放行");
    }

    /** 研究项目分支（PLT-PERM-008）：快照带项目清单时改写器 fail closed 拒绝执行（规则随 rct 模块落地）， 不静默只放行病区 / 科室分支。 */
    @Test
    void interceptorFailsClosedForResearchProjectSnapshots() {
        ScopeSnapshot withProjects =
                ScopeSnapshot.restricted(List.of(7L), List.of(), false, List.of(5L));

        assertThrows(IllegalStateException.class, () -> scoped(withProjects));
    }

    // ---------------------------------------------------------------- 基础设施

    /** 放入快照（切面同款 ThreadLocal 写法）后走生产 {@link DataScopeInnerInterceptor#beforeQuery} 改写患者主表 SQL。 */
    private String scoped(ScopeSnapshot snapshot) throws SQLException {
        return scoped(snapshot, PATIENT_SQL);
    }

    private String scoped(ScopeSnapshot snapshot, String sql) throws SQLException {
        DataScopeContext.clear();
        DataScopeContext.set(snapshot, USER_ID);
        return rewritten(sql);
    }

    /** 走生产拦截器改写并返回 BoundSql 里最终要执行的 SQL；上下文由调用方预先设置（或清空）。 */
    private String rewritten(String sql) throws SQLException {
        BoundSql boundSql = new BoundSql(new Configuration(), sql, List.of(), null);
        interceptor.beforeQuery(null, null, null, null, null, boundSql);
        return boundSql.getSql();
    }

    private static ScopedQueryService proxied(ScopedQueryService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new DataScopeAspect());
        return factory.getProxy();
    }

    private void loginAs(ScopeSnapshot scope) {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token",
                        "HOSPITAL",
                        1L,
                        "demoa",
                        "虚构示例医院",
                        USER_ID,
                        "itr.chain.demo",
                        "IT 链路用户（示例）",
                        List.of("ITR-ROLE"),
                        List.of(),
                        List.of(),
                        List.of("followup"),
                        1L,
                        scope,
                        false,
                        null,
                        null,
                        0L,
                        0L,
                        0L,
                        0L,
                        FieldPolicySnapshot.defaults());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** 受控查询入口样例：观测切面放进上下文的内容；异常方法验证清理路径。 */
    static class ScopedQueryService {

        ScopeSnapshot seenSnapshot;
        long seenUserId;
        boolean seenIgnored;

        @DataScope(resource = "patient")
        void queryPatients() {
            seenSnapshot = DataScopeContext.get();
            seenUserId = DataScopeContext.userId();
            seenIgnored = DataScopeContext.ignored();
        }

        @DataScope(resource = "patient")
        void boom() {
            throw new IllegalStateException("示例业务异常");
        }

        @IgnoreScope(reason = "IT 链路用例：调度样例入口（示例）")
        void exportRaw() {
            seenIgnored = DataScopeContext.ignored();
            seenSnapshot = DataScopeContext.get();
            seenUserId = DataScopeContext.userId();
        }
    }
}
