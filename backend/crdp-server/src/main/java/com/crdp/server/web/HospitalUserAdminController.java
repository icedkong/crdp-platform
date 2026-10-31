package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E04-T05 / PLT-AUTH-007：医院端用户管理里与登录锁定相关的管理员动作。 用户管理页面（E06+）落地前先提供解锁接口；重置密码（生成临时密码 + 强制改密）与完整用户
 * CRUD 属 E06 任务。
 */
@Tag(name = "医院端用户管理")
@RestController
public class HospitalUserAdminController {

    private final UserAdminService userAdminService;

    public HospitalUserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @Operation(summary = "解锁登录锁定（连续失败 5 次锁 30 分钟，管理员可提前解锁）。写审计。")
    @RequiresPermission("tenant:user:reset")
    @PostMapping("/api/hospital/system/users/{id}/unlock")
    public ApiResponse<Map<String, Object>> unlock(@PathVariable("id") long id) {
        // 解锁在当前租户上下文内进行（TokenAuthenticationFilter 已按会话设置）
        String username = userAdminService.unlockHospitalUser(id);
        return ApiResponse.ok(Map.of("userId", id, "username", username, "unlocked", true));
    }
}
