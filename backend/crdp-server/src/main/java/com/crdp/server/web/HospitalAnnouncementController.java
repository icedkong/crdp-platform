package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformAnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E05-T11 / PLT-CFG-009：医院端「系统公告」读取——平台发布的公告按范围（全部 / 指定租户）下发到本院， 登录即可访问（同 /profile
 * 一类，个人可见，不占权限码；平台账号 40300 由端别隔离兜底）。 公告数据在平台库（plt_announcement），此处只读，不写租户库。
 */
@PublicApi
@Tag(name = "医院端系统公告")
@RestController
public class HospitalAnnouncementController {

    private final PlatformAnnouncementService service;

    public HospitalAnnouncementController(PlatformAnnouncementService service) {
        this.service = service;
    }

    @Operation(summary = "本院可见的生效公告（全部租户或定向命中；新→旧，上限 50 条）")
    @GetMapping("/api/hospital/announcements")
    public ApiResponse<List<PlatformAnnouncementService.TenantAnnouncementView>> list() {
        long tenantId = CurrentSession.requireHospital().tenantId();
        return ApiResponse.ok(service.listForTenant(tenantId));
    }
}
