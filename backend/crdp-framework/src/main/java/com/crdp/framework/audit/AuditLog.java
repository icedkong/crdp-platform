package com.crdp.framework.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式操作审计（audit.md §1「审计切面 @AuditLog + 写入服务」）。标在 app 层用例方法上： 方法正常返回写 result=SUCCESS；抛
 * 40300/40301/40302/40303/40306 写 result=DENIED（并置请求内标记， 全局兜底不再重复写）；其他异常写 result=FAIL。异常照常向上抛。
 *
 * <p>登录、数据访问（明文 / 导出 / 打印 / 下载 / 批量查询）与平台访问事件有各自的扩展字段， 用 {@link AuditLogger#append(AuditEntry)}
 * 编程式写入，不用本注解。
 *
 * <p>objectId 支持 SpEL 取参（如 {@code "#patientId"}）；解析失败按未填处理，不影响业务。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 业务模块：followup / system / platform / form 等。 */
    String module();

    /** 操作类型（事件名），如 ROLE_CREATE、PATIENT_UPDATE。 */
    String action();

    /** 操作描述（缺省取 action）。 */
    String description() default "";

    /** 对象类型，如 sys_role、patient。不记患者姓名等明文。 */
    String objectType() default "";

    /** 对象 ID，支持 SpEL（如 "#id"）。 */
    String objectId() default "";
}
