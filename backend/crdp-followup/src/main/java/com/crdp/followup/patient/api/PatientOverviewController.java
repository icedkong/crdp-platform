package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientOverviewService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 档案概览与患者时间轴（E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010；docs/api/patient.md §2、§7，页面
 * /followup/patient/:id，pages.md §4）。权限 {@code followup:patient:view}（隐藏菜单 plt_menu 21 下的详情路由）。
 *
 * <p>越范围 / 不存在的患者一律 40400（不暴露对象存在）；姓名 / 病案号只返回脱敏值，明文走 E07-T05 专用接口。
 * 纯概览读取不写审计（与查重、状态历史同口径；批量查询与明文查看才有 DATA_ACCESS 审计）。
 */
@Tag(name = "医院端患者档案")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientOverviewController {

    private final PatientOverviewService service;

    public PatientOverviewController(PatientOverviewService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "患者概览（A-PAT-004、C-PAT-010）：PatientHeader 数据——脱敏基本信息、状态、病区 / 科室、"
                            + "责任医生、基线与随访资料完整度、下次随访窗口（E14 写入）、最近随访关键指标（E15 写入）、"
                            + "C 当前器械（E21）；附带 actions 可用操作列表（按权限 + 状态计算）")
    @GetMapping("/{id}/summary")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientOverviewService.PatientOverviewView> summary(
            @PathVariable("id") long id) {
        return ApiResponse.ok(service.summaryOf(id));
    }

    @Operation(
            summary =
                    "患者时间轴（A-PAT-013）：按时间顺序返回出院节点与状态变更事件（含死亡）；"
                            + "每年随访 / 计划外随访 / 终点事件节点随 E15 接入，C 器械事件随 E21")
    @GetMapping("/{id}/followup-timeline")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientOverviewService.TimelineView> timeline(@PathVariable("id") long id) {
        return ApiResponse.ok(service.timelineOf(id));
    }
}
