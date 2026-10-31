package com.crdp.framework.datascope;

import com.crdp.framework.security.CurrentSession;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class DataScopeAspect {

    @Around("@annotation(com.crdp.framework.datascope.DataScope)")
    public Object open(ProceedingJoinPoint joinPoint) throws Throwable {
        DataScopeContext.set(CurrentSession.require().scope());
        try {
            return joinPoint.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

    @Around("@annotation(com.crdp.framework.datascope.IgnoreScope)")
    public Object ignore(ProceedingJoinPoint joinPoint) throws Throwable {
        DataScopeContext.ignore();
        try {
            return joinPoint.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }
}
