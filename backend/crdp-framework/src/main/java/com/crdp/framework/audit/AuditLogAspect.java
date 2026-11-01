package com.crdp.framework.audit;

import com.crdp.framework.web.ApiException;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * {@link AuditLog} 切面（E08-T01 / PLT-AUD-001）。
 *
 * <p>{@code @Order(5)} 比权限切面（10）更外层：方法体内的 40300/40301（无权限 / 未开通） 也能被记录成 DENIED
 * 后原样抛出。审计写入自身失败按写入服务的失败语义处理（普通操作只告警）。
 */
@Aspect
@Component
@Order(5)
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditLogger auditLogger;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            append(joinPoint, auditLog, AuditResult.SUCCESS, null, null);
            return result;
        } catch (ApiException ex) {
            if (DeniedOperationLogger.DENIED_CODES.contains(ex.code())) {
                append(joinPoint, auditLog, AuditResult.DENIED, ex.code(), ex.getMessage());
                AuditMarkers.markDeniedRecorded();
            } else {
                append(joinPoint, auditLog, AuditResult.FAIL, ex.code(), ex.getMessage());
            }
            throw ex;
        } catch (Exception ex) {
            append(joinPoint, auditLog, AuditResult.FAIL, null, ex.getMessage());
            throw ex;
        }
    }

    private void append(
            ProceedingJoinPoint joinPoint,
            AuditLog auditLog,
            AuditResult result,
            Integer errorCode,
            String reason) {
        try {
            auditLogger.append(
                    AuditEntry.operation(auditLog.module(), auditLog.action())
                            .description(blankToNull(auditLog.description()))
                            .objectType(blankToNull(auditLog.objectType()))
                            .objectId(evalObjectId(joinPoint, auditLog.objectId()))
                            .result(result)
                            .errorCode(errorCode)
                            .reason(reason)
                            .build());
        } catch (RuntimeException writeFailure) {
            // 普通操作日志写入失败只告警（audit.md §1）；切面绝不掩盖业务结果
            log.error("audit aspect append failed action={}", auditLog.action(), writeFailure);
        }
    }

    /** objectId 取值：非表达式原样返回；# 开头按 SpEL 对方法参数求值；失败返回 null。 */
    private String evalObjectId(ProceedingJoinPoint joinPoint, String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        if (!objectId.startsWith("#")) {
            return objectId;
        }
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            MethodBasedEvaluationContext context =
                    new MethodBasedEvaluationContext(
                            null, method, joinPoint.getArgs(), parameterNames);
            Object value = spelParser.parseExpression(objectId).getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            log.warn("audit objectId SpEL 解析失败 expr={}", objectId);
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
