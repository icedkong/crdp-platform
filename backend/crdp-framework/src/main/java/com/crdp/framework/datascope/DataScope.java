package com.crdp.framework.datascope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标在访问受控表的 Mapper 或应用服务方法上（也可标在类 / 接口上覆盖全部方法）。未声明时拦截器对受控表 默认拒绝，且 E07-T01 的启动扫描会在应用启动阶段直接报错。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
    /** 资源名，例如 patient。按病区 / 科室 / 本人的条件改写在 E07-T02 落地。 */
    String resource();
}
