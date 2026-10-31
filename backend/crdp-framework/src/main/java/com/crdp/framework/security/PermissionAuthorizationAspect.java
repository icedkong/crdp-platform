package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
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
        if (!principal.permissionCodes().contains(requiresPermission.value())) {
            throw new ApiException(403, 40300, "没有权限");
        }
        return joinPoint.proceed();
    }
}
