package com.crdp.framework.datascope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 调度等确需全院扫描的入口。必须写明原因，代码评审检查。
 *
 * <p>标在 Mapper 接口上（E07-T01）可覆盖 MyBatis-Plus 注入的继承语句（如 selectById）——这类方法 无法在方法级加注解。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IgnoreScope {
    String reason();
}
