package com.crdp.followup.dashboard.api;

import com.crdp.followup.dashboard.app.DashboardKpiService;
import com.crdp.followup.dashboard.app.DashboardTrendService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页卡片数据（E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001；docs/api/stats.md §2）： 每张卡片独立取数 {@code GET
 * /api/hospital/followup/dashboard/cards/{cardCode}}，本任务落地 kpi（指标卡）与 trend（趋势图）；
 * 任务区、每年随访率、节点随访率、完整度等卡片随 E19-T03 ~ E19-T05 接入同一入口。
 *
 * <p>权限：卡片按卡片目录登记的权限码（均为 {@code followup:dashboard:view}，OQ-19；平台库 V59 建码、种子 V129 授角色）； 数据范围由
 * Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（越范围不可见，范围内无患者返回全 0 / 空序列）；
 * 接口资源由启动扫描登记（SCAN）。病区筛选参数 wardId 随 E19-T03 的病区筛选器接入（A-DASH-005），接口先行支持。
 */
@Tag(name = "医院端首页 Dashboard 卡片")
@RestController
@RequestMapping("/api/hospital/followup/dashboard")
public class DashboardCardController {

    private final DashboardKpiService kpiService;
    private final DashboardTrendService trendService;

    public DashboardCardController(
            DashboardKpiService kpiService, DashboardTrendService trendService) {
        this.kpiService = kpiService;
        this.trendService = trendService;
    }

    @Operation(
            summary =
                    "单卡数据（stats.md §3 cardCode）：kpi 指标卡（A-DASH-001 / C-DASH-001，指标集按 implant"
                            + " 开关，ERI / 扫描件随 E21 占位）、trend 趋势图（A-DASH-002：时间维度 surgery"
                            + " 手术日期默认 / discharge 出院 / admission 入院，粒度 month 默认 / quarter /"
                            + " year，分组 none 默认 / ward / gender / dischargeStatus / patientType /"
                            + " ageBand，from/to 默认最近 24 个月）；wardId 病区筛选（E19-T03 接入）；"
                            + " 数字与列表钻取共用同一条件树（kpi 响应内联 drill 目标与筛选参数）")
    @GetMapping("/cards/{cardCode}")
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:view")
    public ApiResponse<Object> card(
            @PathVariable("cardCode") String cardCode,
            @RequestParam(value = "wardId", required = false) Long wardId,
            @RequestParam(value = "from", required = false) LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to,
            @RequestParam(value = "timeDimension", required = false) String timeDimension,
            @RequestParam(value = "granularity", required = false) String granularity,
            @RequestParam(value = "groupBy", required = false) String groupBy) {
        return switch (cardCode) {
            case "kpi" -> ApiResponse.ok(kpiService.kpi(wardId));
            case "trend" ->
                    ApiResponse.ok(
                            trendService.trend(
                                    wardId, from, to, timeDimension, granularity, groupBy));
            default -> throw new ApiException(400, 40001, "cardCode（kpi / trend）不合法");
        };
    }
}
