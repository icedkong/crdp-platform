package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(20)
public class PermissionAuthorizationAspect {

    /** 医院端权限码的只读动作（{模块}:{资源}:{动作} 的第三段）：视角令牌只放行读接口（E20-T03 / PLT-VIEW-012）。 */
    static final String READ_ACTION = "view";

    @Around("@annotation(requiresPermission)")
    public Object check(ProceedingJoinPoint joinPoint, RequiresPermission requiresPermission)
            throws Throwable {
        SessionPrincipal principal = CurrentSession.require();
        if (principal.tenantView()) {
            // 租户视角会话（E20-T03）：视角令牌不带医院权限码，读接口（view 动作）放行——
            // 写 / 导出 / 明文等动作已在 TenantViewGuardFilter 按视角规则拦截（40303），
            // 这里是内层防线：非 view 动作即使漏到切面也拒绝，fail closed。
            boolean readable =
                    Arrays.stream(requiresPermission.value())
                            .anyMatch(code -> READ_ACTION.equals(actionOf(code)));
            if (!readable) {
                throw new ApiException(403, 40303, "租户视角只读，禁止写入");
            }
            return joinPoint.proceed();
        }
        // 多值为「任一码命中即放行」（OQ-13：C 只有 followup:plan:edit 也要能访问计划接口）
        boolean allowed =
                Arrays.stream(requiresPermission.value())
                        .anyMatch(principal.permissionCodes()::contains);
        if (!allowed) {
            throw new ApiException(403, 40300, "没有权限");
        }
        return joinPoint.proceed();
    }

    /** 权限码第三段（动作）；格式不合规时按写动作处理（不放宽）。 */
    private static String actionOf(String code) {
        if (code == null) {
            return "";
        }
        int at = code.lastIndexOf(':');
        return at < 0 ? code : code.substring(at + 1);
    }
}
