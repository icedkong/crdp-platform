package com.crdp.followup.notice.api;

import com.crdp.followup.notice.app.NotifyRuleService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端通知设置（E09-T04 / A-NTF-001、C-NTF-001；docs/api/notify.md §2，页面 /system/notice）。 权限
 * followup:notice:config（菜单 plt_menu 35，平台迁移 V32）；接口资源由启动扫描登记（SCAN）。
 */
@Tag(name = "医院端通知设置")
@RestController
public class NotifyRuleController {

    private final NotifyRuleService service;

    public NotifyRuleController(NotifyRuleService service) {
        this.service = service;
    }

    @Operation(summary = "逐事件规则清单（A-NTF-001）：目录全集，缺行回落默认值")
    @GetMapping("/api/hospital/notify/rules")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<List<NotifyRuleService.RuleView>> rules() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary =
                    "修改单事件规则（A-NTF-001）：全量覆盖 enabled/advanceDays/sendTime/channels/recipients"
                            + "/smsTemplateCode，写 NOTICE_RULE_UPDATE 审计；短信模板只能是平台审核过的 NOTIFY 模板")
    @PutMapping("/api/hospital/notify/rules/{eventType}")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<NotifyRuleService.RuleView> update(
            @PathVariable("eventType") String eventType,
            @RequestBody NotifyRuleService.RuleUpdate request) {
        return ApiResponse.ok(service.update(eventType, request));
    }

    @Operation(summary = "可选短信模板（只读）：平台审核过的 NOTIFY 场景模板，租户只能选不能写（PLT-CFG-006）")
    @GetMapping("/api/hospital/notify/sms-templates")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<List<NotifyRuleService.SmsTemplateItem>> smsTemplates() {
        return ApiResponse.ok(service.smsTemplates());
    }
}
