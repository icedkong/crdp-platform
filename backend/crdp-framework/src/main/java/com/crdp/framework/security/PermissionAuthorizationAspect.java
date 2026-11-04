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

    @Around("@annotation(requiresPermission)")
    public Object check(ProceedingJoinPoint joinPoint, RequiresPermission requiresPermission)
            throws Throwable {
        SessionPrincipal principal = CurrentSession.require();
        // 多值为「任一码命中即放行」（OQ-13：C 只有 followup:plan:edit 也要能访问计划接口）
        boolean allowed =
                Arrays.stream(requiresPermission.value())
                        .anyMatch(principal.permissionCodes()::contains);
        if (!allowed) {
            throw new ApiException(403, 40300, "没有权限");
        }
        return joinPoint.proceed();
    }
}
