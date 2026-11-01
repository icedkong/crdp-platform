package com.crdp.framework.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * E04-T08 / PLT-AUTH-012：声明该接口为敏感操作，执行前要求二次认证。
 *
 * <p>标注在 Controller 处理方法（或类）上后，请求必须携带有效的 {@code X-Reauth-Token} 请求头（{@code POST /auth/reauth}
 * 用密码换取，5 分钟有效、绑定当前会话）， 否则 40304「敏感操作需二次认证」并写 result=DENIED 审计。 与 {@code @RequiresPermission}
 * 的关系：权限码校验在前（ApiResourceGuardFilter / 切面），二次认证在后—— 没权限返回 40300，有权限但未验证身份返回 40304。
 *
 * <p>当前接入：审计导出提交与下载（两端，PLT-AUD-007）、重置用户密码（PLT-ORG-008）； 明文查看接口随 E07-T05 落地时接入。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireReauth {

    /** 操作场景说明（审计与前端提示用），如「导出审计日志」。 */
    String value() default "";
}
