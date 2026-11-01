package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * E08-T01 / PLT-AUD-001：@AuditLog 切面。成功写 SUCCESS；403xx（40300/40301/40302/40303）写 DENIED
 * 并照常抛出原异常；其他异常写 FAIL。DENIED 已由切面记录时置标记，供全局兜底去重。
 */
class AuditLogAspectTest {

    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private SampleService proxy;

    /** 被切面代理的样例服务：模拟 app 层方法上的 @AuditLog 用法。 */
    static class SampleService {

        @AuditLog(module = "system", action = "ROLE_CREATE", description = "新建角色")
        public Long createRole(String name) {
            return 9L;
        }

        @AuditLog(module = "system", action = "ROLE_DELETE", objectType = "sys_role")
        public void deleteRole(long id) {
            throw new ApiException(403, 40300, "没有权限");
        }

        @AuditLog(module = "system", action = "ROLE_SYNC", objectType = "sys_role")
        public void syncRole(long id) {
            throw new ApiException(403, 40301, "模块未开通，请联系平台");
        }

        @AuditLog(module = "system", action = "ROLE_IMPORT")
        public void importRoles() {
            throw new IllegalStateException("模板文件损坏");
        }

        @AuditLog(module = "system", action = "ROLE_GRANT", objectId = "#roleId")
        public void grant(long roleId) {}
    }

    @BeforeEach
    void setUp() {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new AuditLogAspect(auditLogger));
        proxy = factory.getProxy();
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token-1",
                        "HOSPITAL",
                        7L,
                        "demoa",
                        "示例医院",
                        42L,
                        "doctor.demo",
                        "张医生（示例）",
                        List.of("DOCTOR"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        AuditMarkers.clearDenied();
    }

    @Test
    void successIsRecordedWithAnnotationFields() {
        TenantContext.useTenant(7L, "demoa");
        proxy.createRole("随访员（示例）");

        verify(auditLogger)
                .append(
                        argThat(
                                entry ->
                                        entry.type() == AuditType.OPERATION
                                                && "system".equals(entry.module())
                                                && "ROLE_CREATE".equals(entry.action())
                                                && "新建角色".equals(entry.description())
                                                && entry.result() == AuditResult.SUCCESS
                                                && entry.userId() == null));
    }

    @Test
    void permissionDeniedIsRecordedAsDeniedAndRethrown() {
        TenantContext.useTenant(7L, "demoa");
        assertThatThrownBy(() -> proxy.deleteRole(9L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40300));

        verify(auditLogger)
                .append(
                        argThat(
                                entry ->
                                        entry.type() == AuditType.OPERATION
                                                && entry.result() == AuditResult.DENIED
                                                && entry.errorCode() != null
                                                && entry.errorCode() == 40300
                                                && "sys_role".equals(entry.objectType())));
        // 切面已记录 DENIED：全局兜底不再重复写
        assertThat(AuditMarkers.deniedRecorded()).isTrue();
    }

    @Test
    void moduleClosedIsDenied() {
        TenantContext.useTenant(7L, "demoa");
        assertThatThrownBy(() -> proxy.syncRole(9L)).isInstanceOf(ApiException.class);
        verify(auditLogger)
                .append(
                        argThat(
                                entry ->
                                        entry.result() == AuditResult.DENIED
                                                && entry.errorCode() == 40301));
    }

    @Test
    void unexpectedFailureIsRecordedAsFailAndRethrown() {
        TenantContext.useTenant(7L, "demoa");
        assertThatThrownBy(() -> proxy.importRoles()).isInstanceOf(IllegalStateException.class);
        verify(auditLogger)
                .append(
                        argThat(
                                entry ->
                                        entry.result() == AuditResult.FAIL
                                                && entry.errorCode() == null));
    }

    @Test
    void objectIdSpelIsEvaluated() {
        TenantContext.useTenant(7L, "demoa");
        proxy.grant(33L);
        verify(auditLogger).append(argThat(entry -> "33".equals(entry.objectId())));
    }
}
