package com.crdp.framework.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公共接口标记（PLT-PERM-004，E05-T06）：登录即可访问、不绑定权限码的接口（如个人中心、菜单、自查会话）， 以及登录前的匿名接口（登录、验证码、双因素等）。
 *
 * <p>启动扫描据此把接口资源登记为 public_access=1；标注了 {@link RequiresPermission} 的方法以权限码为准（更严）， 不按公共接口处理。
 * 是否匿名（不登录也能访问）仍由 SecurityConfig 的 permitAll 清单决定，注解只负责登记。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {}
