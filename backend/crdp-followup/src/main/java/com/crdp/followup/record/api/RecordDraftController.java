package com.crdp.followup.record.api;

import com.crdp.followup.record.app.RecordDraftService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 随访记录服务器自动保存草稿（E25-T01 / NFR-SAVE-001、NFR-SAVE-003；followup.md §3，页面 pages.md §5 整页随访表单、组件
 * FormStatusBar / ConflictBanner）。权限 {@code followup:record:edit}（A/C 规格权限表原有码，按钮 plt_menu 222，平台库
 * V43），接口资源由启动扫描登记（SCAN）。
 */
@Tag(name = "医院端随访记录草稿")
@RestController
@RequestMapping("/api/hospital/followup/records")
public class RecordDraftController {

    private final RecordDraftService service;

    public RecordDraftController(RecordDraftService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "自动保存草稿（规格已定，tech-plan §6）：只合并变更字段、只过写入闸门，不触发提交逻辑；"
                            + "baseVersion 过期 409（40900，data 附当前版本 / 更新人 / 更新时间 / 两边不同字段，"
                            + "patient.md §4.1）；已提交 40901；「保留我的并覆盖」以 currentVersion 作 baseVersion "
                            + "重发并带 overwrite=true（后端写审计）")
    @PutMapping("/{id}/draft")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<RecordDraftService.DraftSavedView> saveDraft(
            @PathVariable("id") long id, @RequestBody DraftRequest request) {
        return ApiResponse.ok(
                service.saveDraft(
                        id,
                        new RecordDraftService.DraftCommand(
                                request.baseVersion(), request.changes(), request.overwrite())));
    }

    /** 请求体：changes 只带变更字段（null 值 = 清空该键，前端「只在有新改动时发送」）；overwrite 随冲突流程。 */
    public record DraftRequest(
            Integer baseVersion, Map<String, Object> changes, Boolean overwrite) {}
}
