package com.crdp.framework.datascope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标在访问受控表的 Mapper 或应用服务方法上。未声明时，拦截器对受控表默认拒绝。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
    /** 资源名，例如 patient。M0 只登记，完整改写留到 E07。 */
    String resource();
}
