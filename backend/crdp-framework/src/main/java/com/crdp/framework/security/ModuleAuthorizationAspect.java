package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(10)
public class ModuleAuthorizationAspect {

    @Around("@annotation(requireModule)")
    public Object check(ProceedingJoinPoint joinPoint, RequireModule requireModule)
            throws Throwable {
        SessionPrincipal principal = CurrentSession.require();
        if (principal.hospital() && !principal.openedModules().contains(requireModule.value())) {
            throw new ApiException(403, 40301, "模块未开通，请联系平台");
        }
        return joinPoint.proceed();
    }
}
