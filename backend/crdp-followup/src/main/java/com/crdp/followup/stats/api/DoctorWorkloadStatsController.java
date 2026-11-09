package com.crdp.followup.stats.api;

import com.crdp.followup.stats.app.DoctorWorkloadStatsService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医护负责患者数统计页接口（E19-T07 / A-DASH-009、C-DASH-010；docs/api/stats.md §2）： {@code GET
 * /api/hospital/followup/stats/doctor-workload?wardId=}。
 *
 * <p>权限 {@code followup:dashboard:view}（统计页与首页卡片同码，OQ-19；平台库 V59 建码、种子 V129 / V131 授角色）；数据范围由
 * Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（范围内无患者 → 空行集，不报错）。统计页菜单随平台库 V61 落库（页面
 * pages.md §9）。
 */
@Tag(name = "医院端统计")
@RestController
@RequestMapping("/api/hospital/followup/stats")
public class DoctorWorkloadStatsController {

    private final DoctorWorkloadStatsService service;

    public DoctorWorkloadStatsController(DoctorWorkloadStatsService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "医护负责患者数（A-DASH-009 / C-DASH-010，P1）：按患者责任医生一行列出负责患者数、在随访、"
                            + "本月应随访、逾期、每年随访率（口径 Q38，患者 × 年度去重汇总）；wardId 病区筛选"
                            + "（A-DASH-005）；每列内联钻取目标（患者列表 doctorId〔/ status〕、任务列表 tab=all"
                            + " + dueFrom/dueTo / status=OVERDUE）")
    @GetMapping("/doctor-workload")
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:view")
    public ApiResponse<DoctorWorkloadStatsService.DoctorWorkloadView> doctorWorkload(
            @RequestParam(value = "wardId", required = false) Long wardId) {
        return ApiResponse.ok(service.workload(wardId));
    }
}
