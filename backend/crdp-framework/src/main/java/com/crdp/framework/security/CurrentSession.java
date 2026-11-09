package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentSession {

    private CurrentSession() {}

    /** 当前会话（可能为空），审计等旁路记录用；鉴权入口仍用 require 系列抛 40100。 */
    public static Optional<SessionPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public static SessionPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new ApiException(401, 40100, "未登录或登录已失效");
        }
        return principal;
    }

    /**
     * 医院端业务会话。医院会话（HOSPITAL）直接通过；租户视角会话（TENANT_VIEW，E20-T06 起）在 TenantViewGuardFilter
     * 执行点判定（读一律放行、写须代为操作受控放行）后进入业务处理器， 本方法不再重复拦截——操作者身份按视角口径处理 （审计匿名「平台支持人员 会话号」、字段历史来源
     * PLATFORM_SUPPORT）。 平台会话（PLATFORM）仍 40300。
     */
    public static SessionPrincipal requireHospital() {
        SessionPrincipal principal = require();
        if (!principal.hospital() && !principal.tenantView()) {
            throw new ApiException(403, 40300, "平台账号不能访问医院端");
        }
        return principal;
    }

    public static SessionPrincipal requirePlatform() {
        SessionPrincipal principal = require();
        if (!principal.platform()) {
            throw new ApiException(403, 40300, "医院账号不能访问平台端");
        }
        return principal;
    }

    /**
     * 医院端外壳接口（E20-T04 / PLT-VIEW-002）：登录信息 / 权限码 / 菜单三条「装壳」接口同时 接受医院会话与租户视角会话（SecurityConfig
     * 已按端别放行）；业务控制器仍用 {@link #requireHospital()}—— 视角令牌对业务接口保持默认拒绝（E20-T03 执行点放开）。
     */
    public static SessionPrincipal requireHospitalShell() {
        SessionPrincipal principal = require();
        if (!principal.hospital() && !principal.tenantView()) {
            throw new ApiException(403, 40300, "平台账号不能访问医院端");
        }
        return principal;
    }

    /** 租户视角会话（E20-T04 / PLT-VIEW-003）：横幅会话信息与一键退出只接受视角令牌。 */
    public static SessionPrincipal requireTenantView() {
        SessionPrincipal principal = require();
        if (!principal.tenantView()) {
            throw new ApiException(403, 40300, "该接口只接受租户视角会话");
        }
        return principal;
    }
}
