package com.crdp.framework.datascope;

import com.crdp.framework.security.CurrentSession;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 运行时范围上下文的开与关。E07-T01 起注解支持标在类 / 接口上（@within）：应用服务类级标注覆盖全部 方法，Mapper 接口级标注主要为默认拒绝扫描与 MyBatis-Plus
 * 注入语句服务——Mapper 的 JDK 代理若 {@code @within} 不命中，运行时仍会因缺上下文被 40302 拒绝，方向是安全的（fail closed）。
 */
@Aspect
@Component
public class DataScopeAspect {

    @Around(
            "@annotation(com.crdp.framework.datascope.DataScope)"
                    + " || @within(com.crdp.framework.datascope.DataScope)")
    public Object open(ProceedingJoinPoint joinPoint) throws Throwable {
        var principal = CurrentSession.require();
        DataScopeContext.set(principal.scope(), principal.userId());
        try {
            return joinPoint.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

    @Around(
            "@annotation(com.crdp.framework.datascope.IgnoreScope)"
                    + " || @within(com.crdp.framework.datascope.IgnoreScope)")
    public Object ignore(ProceedingJoinPoint joinPoint) throws Throwable {
        DataScopeContext.ignore();
        try {
            return joinPoint.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }
}
