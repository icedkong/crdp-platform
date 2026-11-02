package com.crdp.followup.notice.api;

import com.crdp.followup.notice.app.NotifySettingsService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端通知发送设置（E09-T07 落地；docs/api/notify.md §2 settings，通知设置页「发送设置」页签）。 免打扰时段与每日短信上限由 E09-T05
 * 汇总作业执行；逾期类是否允许个人关闭（A-NTF-007）即时生效。 权限 followup:notice:config。
 */
@Tag(name = "医院端通知发送设置")
@RestController
public class NotifySettingsController {

    private final NotifySettingsService service;

    public NotifySettingsController(NotifySettingsService service) {
        this.service = service;
    }

    @Operation(summary = "生效设置（A-NTF-005 / A-NTF-007）：免打扰时段、每日短信上限、逾期类可否个人关闭")
    @GetMapping("/api/hospital/notify/settings")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<NotifySettingsService.SettingsView> get() {
        return ApiResponse.ok(service.get());
    }

    @Operation(summary = "修改发送设置（全量覆盖，写 NOTICE_SETTINGS_UPDATE 审计）；" + "免打扰与上限的发送侧执行随 E09-T05 汇总作业")
    @PutMapping("/api/hospital/notify/settings")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<NotifySettingsService.SettingsView> update(
            @RequestBody NotifySettingsService.SettingsUpdate request) {
        return ApiResponse.ok(service.update(request));
    }
}
