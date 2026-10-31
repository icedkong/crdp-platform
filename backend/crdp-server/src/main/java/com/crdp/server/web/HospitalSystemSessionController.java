package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.SessionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E04-T06 / PLT-AUTH-009 在线用户与强制下线（docs/api/system.md §8）。 在线用户页面（/system/session，pages.md §设置）属
 * E06+，本任务先提供接口与权限码（平台库 V11 按钮 312 / 313）。
 */
@Tag(name = "医院端在线会话管理")
@RestController
public class HospitalSystemSessionController {

    private final SessionAdminService sessionAdminService;

    public HospitalSystemSessionController(SessionAdminService sessionAdminService) {
        this.sessionAdminService = sessionAdminService;
    }

    @Operation(summary = "列出本院全部在线会话（账号、IP、登录时间、最后活跃）。只返回会话号，不返回令牌。")
    @RequiresPermission("tenant:session:view")
    @GetMapping("/api/hospital/system/sessions")
    public ApiResponse<List<SessionAdminService.OnlineSessionView>> sessions() {
        return ApiResponse.ok(sessionAdminService.listTenantSessions());
    }

    @Operation(summary = "强制下线指定会话（被下线令牌下次请求即 40100）。写审计。")
    @RequiresPermission("tenant:session:kick")
    @PostMapping("/api/hospital/system/sessions/{sessionId}/kick")
    public ApiResponse<SessionAdminService.OnlineSessionView> kick(
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.ok(sessionAdminService.kick(sessionId));
    }
}
