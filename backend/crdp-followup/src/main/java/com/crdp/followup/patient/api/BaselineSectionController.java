package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基线分节接口（E13-T02 / A-PAT-003、A-PAT-005；docs/api/patient.md §4；页面 pages.md §4.2–4.3）。 权限：查看
 * followup:patient:view（菜单 21），保存 / 暂存 followup:patient:edit（按钮 214，A 规格权限表「编辑基线档案」）； 锁定 / 解锁在
 * {@link BaselineLockController}（followup:patient:lock）。接口资源由启动扫描登记（SCAN）。
 */
@Tag(name = "医院端基线分节")
@RestController
public class BaselineSectionController {

    private final BaselineSectionService service;

    public BaselineSectionController(BaselineSectionService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "基线全量视图：按当前发布版模板分类列出全部分节（EMPTY/PARTIAL/DONE、data、meta、hasDraft）"
                            + "与分类进度（已填/应填）、锁定状态；越范围 / 不存在 40400")
    @GetMapping("/api/hospital/followup/patients/{id}/baseline")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<BaselineSectionService.BaselineView> baseline(@PathVariable("id") long id) {
        return ApiResponse.ok(service.baseline(id));
    }

    @Operation(
            summary =
                    "自动保存草稿（规格已定，tech-plan §6 草稿语义）：只合并变更字段、只过写入闸门，"
                            + "不做必填 / 跨字段校验、不写字段历史；baseVersion 过期 409（40900，data 附当前版本 / "
                            + "更新人 / 更新时间 / 两边不同字段，patient.md §4.1）；基线已锁定 40901；"
                            + "「保留我的并覆盖」以 currentVersion 作 baseVersion 重发并带 overwrite=true（写审计）")
    @PutMapping("/api/hospital/followup/baseline/{patientId}/sections/{code}/draft")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:edit")
    public ApiResponse<BaselineSectionService.DraftSavedView> saveDraft(
            @PathVariable("patientId") long patientId,
            @PathVariable("code") String code,
            @RequestBody DraftRequest request) {
        return ApiResponse.ok(
                service.saveDraft(
                        patientId,
                        code,
                        new BaselineSectionService.DraftCommand(
                                request.baseVersion(), request.changes(), request.overwrite())));
    }

    @Operation(
            summary =
                    "正式保存分节：完整校验（42200）→ 隐藏字段清空 → 写入闸门；同一事务写字段级历史"
                            + "（旧值、新值、人、时间）并重算基线完整度（A-PAT-019）、清除草稿；"
                            + "基线已锁定 40901（A-PAT-006 锁定后只读）；baseVersion 过期 40900")
    @PutMapping("/api/hospital/followup/baseline/{patientId}/sections/{code}")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:edit")
    public ApiResponse<BaselineSectionService.SaveResultView> saveSection(
            @PathVariable("patientId") long patientId,
            @PathVariable("code") String code,
            @RequestBody SaveRequest request) {
        return ApiResponse.ok(
                service.saveSection(
                        patientId,
                        code,
                        new BaselineSectionService.SaveCommand(
                                request.baseVersion(), request.data(), request.meta())));
    }

    @Operation(
            summary =
                    "分节字段级历史（A-PAT-005：谁、何时、旧值 → 新值；敏感字段不落值）；" + "fieldKey 缺省查全部分节字段；分节未保存过 40400")
    @GetMapping("/api/hospital/followup/baseline/{patientId}/sections/{code}/history")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<List<com.crdp.form.history.app.FieldHistoryService.HistoryView>> history(
            @PathVariable("patientId") long patientId,
            @PathVariable("code") String code,
            @RequestParam(value = "fieldKey", required = false) String fieldKey) {
        return ApiResponse.ok(service.sectionHistory(patientId, code, fieldKey));
    }

    /** 暂存请求体：changes 只带变更字段（null 值 = 清空该键）；overwrite 随冲突流程。 */
    public record DraftRequest(
            Integer baseVersion, Map<String, Object> changes, Boolean overwrite) {}

    /** 保存请求体：data 全量、meta 缺省保留原值。 */
    public record SaveRequest(
            Integer baseVersion, Map<String, Object> data, Map<String, Object> meta) {}
}
