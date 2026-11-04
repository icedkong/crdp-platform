package com.crdp.followup.plan.api;

import com.crdp.followup.plan.app.PlanService;
import com.crdp.followup.plan.domain.PlanNodeRule;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 随访计划配置（E14-T01 / A-FU-016、A-FU-018、A-FU-019、C-FU-001；docs/api/followup.md §1，页面 pages.md §8——页面随
 * E14-T02 落地，本任务先落数据面接口）。
 *
 * <p>权限码按 OQ-13 声明双候选（任一命中即放行）：查看类 {@code followup:plan:view}（A）或 {@code followup:plan:edit}（C
 * 只有此码）；发布 / 暂停 / 停用 {@code followup:plan:publish} 或 {@code followup:plan:edit}。 菜单与按钮在平台库 V42（24 /
 * 25 / 219 / 220 / 221）。计划患者列表与批量入组接口随 E14-T08 / E13-T07。
 */
@Tag(name = "医院端随访计划")
@RestController
@RequestMapping("/api/hospital/followup/plans")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @Operation(summary = "计划列表（A-FU-016/018）：名称、编码、版本、状态（含派生「随访中」RUNNING）、" + "在组人数；每族最新版本在前")
    @GetMapping
    @RequireModule("followup")
    @RequiresPermission({"followup:plan:view", "followup:plan:edit"})
    public ApiResponse<PlanService.PlansView> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary =
                    "计划详情（A-FU-016）：基本信息 + 节点两层（子计划 SUBPLAN / 节点 NODE，"
                            + "偏移、提醒、窗口、周期、事件类型 FORM|REMINDER、表单 code）")
    @GetMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission({"followup:plan:view", "followup:plan:edit"})
    public ApiResponse<PlanService.PlanDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @Operation(summary = "新建计划（未发布 v1）：编码本院族唯一（40902）、创建后不可改；写 PLAN_CREATE 审计")
    @PostMapping
    @RequireModule("followup")
    @RequiresPermission("followup:plan:edit")
    public ApiResponse<PlanService.PlanView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(
                service.create(
                        new PlanService.CreateRequest(
                                request.code(),
                                request.name(),
                                request.startDateField(),
                                request.cycleType(),
                                request.cycleYears(),
                                request.remark())));
    }

    @Operation(
            summary =
                    "编辑计划基本信息（A-FU-019）：仅未发布可改（已发布 40901，DENIED 审计）；"
                            + "带 baseVersion 乐观锁（冲突 40900）；写 PLAN_UPDATE 审计")
    @PutMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:plan:edit")
    public ApiResponse<PlanService.PlanView> update(
            @PathVariable long id, @RequestBody UpdateRequest request) {
        return ApiResponse.ok(
                service.update(
                        id,
                        request.baseVersion(),
                        new PlanService.UpdateRequest(
                                request.name(),
                                request.startDateField(),
                                request.cycleType(),
                                request.cycleYears(),
                                request.remark())));
    }

    @Operation(
            summary =
                    "整体保存节点（A-FU-016）：{ baseVersion, nodes[] } 两层树整体替换，仅未发布；"
                            + "结构校验（子计划重复方式、节点偏移 / 提醒 / 窗口 / 任务类型）失败 40001")
    @PutMapping("/{id}/nodes")
    @RequireModule("followup")
    @RequiresPermission("followup:plan:edit")
    public ApiResponse<PlanService.PlanDetailView> saveNodes(
            @PathVariable long id, @RequestBody SaveNodesRequest request) {
        return ApiResponse.ok(
                service.saveNodes(
                        id,
                        new PlanService.SaveNodesRequest(request.baseVersion(), request.nodes())));
    }

    @Operation(
            summary =
                    "发布计划（A-FU-018/019）：UNPUBLISHED → PUBLISHED；前置至少一个子计划与节点；"
                            + "同族旧已发布版本自动停用（不再允许入组 → 新入组用新版本）；写 PLAN_PUBLISH 审计")
    @PostMapping("/{id}/publish")
    @RequireModule("followup")
    @RequiresPermission({"followup:plan:publish", "followup:plan:edit"})
    public ApiResponse<PlanService.PlanView> publish(@PathVariable long id) {
        return ApiResponse.ok(service.publish(id));
    }

    @Operation(
            summary =
                    "复制为新版本（A-FU-019）：从已发布 / 暂停 / 停用版本复制出未发布草稿（节点深拷贝，"
                            + "版本号族内最大 +1）；同族已有草稿 40901；写 PLAN_COPY 审计")
    @PostMapping("/{id}/copy")
    @RequireModule("followup")
    @RequiresPermission("followup:plan:edit")
    public ApiResponse<PlanService.PlanView> copy(@PathVariable long id) {
        return ApiResponse.ok(service.copy(id));
    }

    @Operation(summary = "暂停计划（A-FU-018）：PUBLISHED → PAUSED，不开启新任务、不发通知；恢复从下一窗口起继续")
    @PostMapping("/{id}/pause")
    @RequireModule("followup")
    @RequiresPermission({"followup:plan:publish", "followup:plan:edit"})
    public ApiResponse<PlanService.PlanView> pause(@PathVariable long id) {
        return ApiResponse.ok(service.pause(id));
    }

    @Operation(summary = "恢复计划（A-FU-018）：PAUSED → PUBLISHED")
    @PostMapping("/{id}/resume")
    @RequireModule("followup")
    @RequiresPermission({"followup:plan:publish", "followup:plan:edit"})
    public ApiResponse<PlanService.PlanView> resume(@PathVariable long id) {
        return ApiResponse.ok(service.resume(id));
    }

    @Operation(
            summary =
                    "停用计划（A-FU-018）：PUBLISHED / PAUSED → DISABLED，不再允许入组；"
                            + "在组患者沿用原版本继续；写 PLAN_DISABLE 审计（被阻止写 DENIED）")
    @PostMapping("/{id}/disable")
    @RequireModule("followup")
    @RequiresPermission({"followup:plan:publish", "followup:plan:edit"})
    public ApiResponse<PlanService.PlanView> disable(@PathVariable long id) {
        return ApiResponse.ok(service.disable(id));
    }

    /** 新建请求体。 */
    public record CreateRequest(
            @Size(max = 64) String code,
            @Size(max = 128) String name,
            @Size(max = 32) String startDateField,
            @Size(max = 16) String cycleType,
            Integer cycleYears,
            @Size(max = 255) String remark) {}

    /** 编辑请求体（baseVersion = 详情返回的 lockVersion，乐观锁）。 */
    public record UpdateRequest(
            int baseVersion,
            @Size(max = 128) String name,
            @Size(max = 32) String startDateField,
            @Size(max = 16) String cycleType,
            Integer cycleYears,
            @Size(max = 255) String remark) {}

    /** 节点整体保存请求体（key / parentKey 为请求内引用，两层树整体替换）。 */
    public record SaveNodesRequest(int baseVersion, List<PlanNodeRule.NodeInput> nodes) {}
}
