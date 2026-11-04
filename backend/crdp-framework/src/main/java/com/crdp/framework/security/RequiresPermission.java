package com.crdp.framework.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限码，格式 {模块}:{资源}:{动作}，与底座规格一致。
 *
 * <p>E14-T01 / OQ-13 起支持多值：同一接口对两家医院用不同码（如计划查看 A 用 {@code followup:plan:view}、C 只有 {@code
 * followup:plan:edit}，docs/api/followup.md §1），声明为
 * {@code @RequiresPermission({"followup:plan:view", "followup:plan:edit"})} 后「任一码命中即放行」， 全部缺失才
 * 40300。单值写法不受影响。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 候选权限码（至少一个；任一命中即通过）。 */
    String[] value();
}
