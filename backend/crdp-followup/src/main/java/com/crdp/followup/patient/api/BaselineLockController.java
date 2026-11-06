package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.BaselineLockService;
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
 * 基线锁定与解锁接口（E13-T02 / A-PAT-006；docs/api/patient.md §4；页面 pages.md §4.2 编辑置灰与 ReasonModal）。
 *
 * <p>权限 {@code followup:patient:lock}（A 规格权限表「锁定与解锁基线」，按钮 plt_menu 226，平台库 V48）—— 解锁的「专门权限」即此码：与编辑权
 * followup:patient:edit 分离，前端隐藏不能代替后端校验（AGENTS §5.9）。锁定 / 解锁各写操作审计留痕。
 */
@Tag(name = "医院端基线锁定")
@RestController
public class BaselineLockController {

    private final BaselineLockService service;

    public BaselineLockController(BaselineLockService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "锁定 / 解锁基线（A-PAT-006）：body { locked, reason }；解锁必须填写原因（空白 40001）；"
                            + "锁定后分节保存与暂存一律 40901（锁定后只读）；锁定 / 解锁写审计留痕；"
                            + "重复锁定 / 未锁定时解锁 40001；越范围 / 不存在 40400")
    @PostMapping("/api/hospital/followup/patients/{id}/lock")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:lock")
    public ApiResponse<BaselineLockService.LockView> setLocked(
            @PathVariable("id") long id, @RequestBody BaselineLockService.LockCommand command) {
        return ApiResponse.ok(service.setLocked(id, command));
    }
}
