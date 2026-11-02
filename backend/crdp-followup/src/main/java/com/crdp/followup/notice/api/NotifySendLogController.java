package com.crdp.followup.notice.api;

import com.crdp.followup.notice.app.NotifySendLogService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端通知发送记录（E09-T07 / A-NTF-006、C-NTF-006；docs/api/notify.md §4， 通知设置页「发送记录」页签）。权限
 * followup:notice:config（菜单 plt_menu 35，平台迁移 V32）；台账无患者维度， 不在 ControlledTables。
 */
@Tag(name = "医院端通知发送记录")
@RestController
public class NotifySendLogController {

    private final NotifySendLogService service;

    public NotifySendLogController(NotifySendLogService service) {
        this.service = service;
    }

    @Operation(
            summary = "条件查询发送台账（A-NTF-006）：按事件、渠道、结果、日期、接收人；" + "title/detail 不含患者信息；批量查询写数据访问审计")
    @PostMapping("/api/hospital/notify/send-logs/search")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<NotifySendLogService.SendLogPage> search(
            @RequestBody(required = false) NotifySendLogService.SendLogQuery query) {
        return ApiResponse.ok(service.search(query));
    }

    @Operation(
            summary =
                    "失败重试（A-NTF-006）：FAILED → QUEUED，短信由 E09-T05 汇总作业发送"
                            + "（同样受免打扰与每日上限约束）；非失败 40001、不存在 40400；写 NOTICE_SEND_RETRY 审计")
    @PostMapping("/api/hospital/notify/send-logs/{id}/retry")
    @RequireModule("followup")
    @RequiresPermission("followup:notice:config")
    public ApiResponse<NotifySendLogService.RetryResult> retry(@PathVariable("id") long id) {
        return ApiResponse.ok(service.retry(id));
    }
}
