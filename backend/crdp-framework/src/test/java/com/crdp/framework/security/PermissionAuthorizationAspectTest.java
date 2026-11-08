package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E14-T01 / OQ-13：权限码「任一命中即放行」。计划接口按 docs/api/followup.md §1 声明两个可 选码（A 用 {@code
 * followup:plan:view}，C 只有 {@code followup:plan:edit}），单值注解表达不了， 扩展为多值后任一码命中即通过、全部缺失才 40300。
 */
class PermissionAuthorizationAspectTest {

    private final PermissionAuthorizationAspect aspect = new PermissionAuthorizationAspect();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 视角会话（E20-T03）：平台账号身份，无医院权限码。 */
    private void loginView() {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "view-token",
                        "TENANT_VIEW",
                        7L,
                        "demoa",
                        "示例医院 A",
                        1L,
                        "platform.admin",
                        "平台管理员（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        null,
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private void login(String... permissionCodes) {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "it-token",
                        "HOSPITAL",
                        1L,
                        "demoa",
                        "示例医院 A",
                        9L,
                        "it-doctor",
                        "张医生（示例）",
                        List.of("FOLLOWUP_DOCTOR"),
                        List.of(),
                        List.of(permissionCodes),
                        List.of("followup"),
                        0L,
                        null,
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    static final class Endpoint {

        @RequiresPermission("followup:plan:view")
        public String single() {
            return "single";
        }

        @RequiresPermission({"followup:plan:view", "followup:plan:edit"})
        public String anyOf() {
            return "anyOf";
        }
    }

    private final Endpoint endpoint = new Endpoint();

    static final class EditEndpoint {

        @RequiresPermission("followup:patient:edit")
        public String edit() {
            return "edit";
        }
    }

    @Test
    void singleCodeStillEnforced() throws Throwable {
        login("followup:plan:view");
        assertEquals(
                "single",
                aspect.check(
                        joinPoint("single"),
                        endpoint.getClass()
                                .getMethod("single")
                                .getAnnotation(RequiresPermission.class)));
        login("followup:plan:edit");
        ApiException denied =
                assertThrows(
                        ApiException.class,
                        () ->
                                aspect.check(
                                        joinPoint("single"),
                                        endpoint.getClass()
                                                .getMethod("single")
                                                .getAnnotation(RequiresPermission.class)));
        assertEquals(40300, denied.code());
    }

    @Test
    void viewSessionPassesReadActionsOnly() throws Throwable {
        RequiresPermission view =
                Endpoint.class.getMethod("single").getAnnotation(RequiresPermission.class);
        RequiresPermission mixed =
                Endpoint.class.getMethod("anyOf").getAnnotation(RequiresPermission.class);

        loginView();
        assertEquals(
                "single",
                aspect.check(joinPoint("single"), view),
                "视角令牌对只读动作（view）放行（E20-T03 / PLT-VIEW-012）");
        assertEquals("anyOf", aspect.check(joinPoint("anyOf"), mixed), "多候选码任一 view 即读（OQ-13）");

        loginView();
        RequiresPermission edit =
                EditEndpoint.class.getMethod("edit").getAnnotation(RequiresPermission.class);
        ApiException denied =
                assertThrows(ApiException.class, () -> aspect.check(joinPoint("edit"), edit));
        assertEquals(40303, denied.code(), "视角令牌对写动作（edit）40303，fail closed");
    }

    @Test
    void anyOfCodePassesAndAllMissingDenies() throws Throwable {
        RequiresPermission annotation =
                endpoint.getClass().getMethod("anyOf").getAnnotation(RequiresPermission.class);

        login("followup:plan:view");
        assertEquals("anyOf", aspect.check(joinPoint("anyOf"), annotation));

        login("followup:plan:edit");
        assertEquals("anyOf", aspect.check(joinPoint("anyOf"), annotation));

        login("followup:task:view");
        ApiException denied =
                assertThrows(
                        ApiException.class, () -> aspect.check(joinPoint("anyOf"), annotation));
        assertEquals(40300, denied.code());
    }

    private org.aspectj.lang.ProceedingJoinPoint joinPoint(String method) throws Throwable {
        org.aspectj.lang.ProceedingJoinPoint mock =
                org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(mock.proceed()).thenReturn(method);
        return mock;
    }
}
