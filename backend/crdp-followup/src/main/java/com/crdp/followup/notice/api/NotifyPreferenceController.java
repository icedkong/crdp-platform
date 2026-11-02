package com.crdp.followup.notice.api;

import com.crdp.followup.notice.app.NotifyPreferenceService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人通知偏好（E09-T07 / A-NTF-007、C-NTF-007；docs/api/notify.md §5，
 * 个人中心「通知偏好」页签）。登录即可（@PublicApi：不绑定权限码，医院端令牌必须有效）， 只读写本人 ntf_user_pref 行；逾期类能否关闭由租户设置决定，不允许时 40001。
 */
@PublicApi
@Tag(name = "医院端个人通知偏好")
@RestController
public class NotifyPreferenceController {

    private final NotifyPreferenceService service;

    public NotifyPreferenceController(NotifyPreferenceService service) {
        this.service = service;
    }

    @Operation(summary = "本人通知偏好（A-NTF-007）：已关短信的事件、逾期类可否关闭、" + "可选项（本院规则含短信渠道的事件）")
    @GetMapping("/api/hospital/profile/notify-pref")
    public ApiResponse<NotifyPreferenceService.PrefView> view() {
        return ApiResponse.ok(service.view(CurrentSession.requireHospital().userId()));
    }

    @Operation(
            summary =
                    "保存本人偏好（全量覆盖 smsOff，只影响短信；写 NOTICE_PREF_UPDATE 审计）；"
                            + "本院不允许关闭逾期类时提交含 TASK_OVERDUE → 40001")
    @PutMapping("/api/hospital/profile/notify-pref")
    public ApiResponse<NotifyPreferenceService.PrefView> update(
            @RequestBody NotifyPreferenceService.PrefUpdate request) {
        return ApiResponse.ok(service.update(CurrentSession.requireHospital().userId(), request));
    }
}
