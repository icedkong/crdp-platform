package com.crdp.followup.dashboard.api;

import com.crdp.followup.dashboard.app.DashboardKpiService;
import com.crdp.followup.dashboard.app.DashboardTaskService;
import com.crdp.followup.dashboard.app.DashboardTrendService;
import com.crdp.followup.stats.app.CompletenessStatsService;
import com.crdp.followup.stats.app.NodeOccurrenceStatsService;
import com.crdp.followup.stats.app.ScanProgressService;
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
 * 首页卡片数据（E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001；E19-T03 /
 * A-DASH-003、004、005、008、C-DASH-003、008；E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004；E19-T05 /
 * A-DASH-010、A-DASH-011、C-DASH-009； docs/api/stats.md §2）： 每张卡片独立取数 {@code GET
 * /api/hospital/followup/dashboard/cards/{cardCode}}，已落地 kpi（指标卡）、trend（趋势图）、task_board /
 * task_table（任务看板与任务表）、 yearly_rate / node_rate（每年随访率与节点随访率，口径 Q38）与 baseline_completeness /
 * followup_completeness（数据完整度两卡， 口径 Q39 / Q40）；其余卡片随 E19-T06 / E21 接入同一入口。
 *
 * <p>权限：卡片按卡片目录登记的权限码（均为 {@code followup:dashboard:view}，OQ-19；平台库 V59 建码、种子 V129 授角色）； 数据范围由
 * Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（越范围不可见，范围内无患者返回全 0 / 空序列）；
 * 接口资源由启动扫描登记（SCAN）。
 *
 * <p>病区筛选（A-DASH-005）：wardId 为页面级参数，全部卡片共用；病区候选 {@code GET /wards} 按登录快照取本人可见病区。 随访率卡片的数据时点 =
 * 节点随访事实表最近一次每日刷新（响应回显 asOf；当日完成 / 补录次日刷新前以最近刷新为准，OQ-106①）；完整度两卡按当前数据实时计算（Q39 / Q40「统计时点」），响应不带
 * asOf。
 */
@Tag(name = "医院端首页 Dashboard 卡片")
@RestController
@RequestMapping("/api/hospital/followup/dashboard")
public class DashboardCardController {

    private final DashboardKpiService kpiService;
    private final DashboardTrendService trendService;
    private final DashboardTaskService taskService;
    private final NodeOccurrenceStatsService rateService;
    private final CompletenessStatsService completenessService;
    private final ScanProgressService scanProgressService;

    public DashboardCardController(
            DashboardKpiService kpiService,
            DashboardTrendService trendService,
            DashboardTaskService taskService,
            NodeOccurrenceStatsService rateService,
            CompletenessStatsService completenessService,
            ScanProgressService scanProgressService) {
        this.kpiService = kpiService;
        this.trendService = trendService;
        this.taskService = taskService;
        this.rateService = rateService;
        this.completenessService = completenessService;
        this.scanProgressService = scanProgressService;
    }

    @Operation(
            summary =
                    "单卡数据（stats.md §3 cardCode）：kpi 指标卡（A-DASH-001 / C-DASH-001，指标集按 implant"
                            + " 开关，ERI / 扫描件随 E21 占位）、trend 趋势图（A-DASH-002：时间维度 surgery"
                            + " 手术日期默认 / discharge 出院 / admission 入院，粒度 month 默认 / quarter /"
                            + " year，分组 none 默认 / ward / gender / dischargeStatus / patientType /"
                            + " ageBand，from/to 默认最近 24 个月）、task_board 任务看板（A-DASH-003 /"
                            + " C-DASH-003：四列各前 20 张卡片；scope 我的 MINE 默认 / 本病区 WARD / 全部 ALL"
                            + " ——A-DASH-004，assigneeId 负责人筛选）、task_table 任务表（A-DASH-008：页签"
                            + " tab TODAY 今日默认 / OVERDUE 超时 / MINE 我负责，页签显示数量 + 当前页签前 10"
                            + " 行）、yearly_rate 每年随访率（A-DASH-006：按随访年度 N 一行，cohortYear"
                            + " 出院年份队列筛选，口径 Q38——逾期完成 / 补录 / 历史导入都计入已随访）、node_rate"
                            + " 节点随访率（A-DASH-012 / C-DASH-004：planId 随访计划缺省预置计划，行 = 节点 ×"
                            + " 第 N 次；开关 node_follow_rate 关闭返回 40301）、baseline_completeness 基线资料完整度"
                            + "（A-DASH-010：平均值 / 中位数 / 五段分布 / 8 分类明细 / 最常缺失字段前 10；patientType"
                            + " 患者类型、from+to 时间范围、timeField 出院 DISCHARGE 默认 / 登记 REGISTERED）、"
                            + "followup_completeness 随访资料完整度（A-DASH-011 / Q40：平均值 / 已统计记录数 / 按随访"
                            + "年度表格 / 最常缺失字段前 10；timeField 实际随访日期 VISIT 默认 / 出院 DISCHARGE、year"
                            + " 随访年度筛选；最常缺失字段点击钻取患者列表——患者检索 completenessDrill 过滤，同一计算）、"
                            + "scan_progress 补录进度（E21-T15 / C-DASH-007：待录入 / 已电子化 / 无需录入份数与"
                            + " 已电子化比例 + 补录随访记录条数；uploaderId 上传人、year 文档年份筛选；开关"
                            + " scan_progress 关闭返回 40301）；wardId"
                            + " 病区筛选（A-DASH-005）全部卡片共用；数字与列表钻取共用同一条件树（各列 / 页签响应内联 drill"
                            + " 目标与筛选参数）")
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
            @RequestParam(value = "groupBy", required = false) String groupBy,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "assigneeId", required = false) Long assigneeId,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "planId", required = false) Long planId,
            @RequestParam(value = "cohortYear", required = false) Integer cohortYear,
            @RequestParam(value = "patientType", required = false) String patientType,
            @RequestParam(value = "timeField", required = false) String timeField,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "uploaderId", required = false) Long uploaderId) {
        return switch (cardCode) {
            case "kpi" -> ApiResponse.ok(kpiService.kpi(wardId));
            case "trend" ->
                    ApiResponse.ok(
                            trendService.trend(
                                    wardId, from, to, timeDimension, granularity, groupBy));
            case "task_board" -> ApiResponse.ok(taskService.board(wardId, scope, assigneeId));
            case "task_table" -> ApiResponse.ok(taskService.table(wardId, tab));
            case "yearly_rate" -> ApiResponse.ok(rateService.yearlyRate(wardId, cohortYear));
            case "node_rate" -> ApiResponse.ok(rateService.nodeRate(planId, wardId, cohortYear));
            case "baseline_completeness" ->
                    ApiResponse.ok(
                            completenessService.baselineCard(
                                    new CompletenessStatsService.Query(
                                            wardId, patientType, from, to, timeField, null)));
            case "followup_completeness" ->
                    ApiResponse.ok(
                            completenessService.followupCard(
                                    new CompletenessStatsService.Query(
                                            wardId, patientType, from, to, timeField, year)));
            case "scan_progress" ->
                    ApiResponse.ok(scanProgressService.scanProgress(wardId, uploaderId, year));
            default ->
                    throw new ApiException(
                            400,
                            40001,
                            "cardCode（kpi / trend / task_board / task_table / yearly_rate /"
                                    + " node_rate / baseline_completeness / followup_completeness /"
                                    + " scan_progress）不合法");
        };
    }

    @Operation(
            summary =
                    "病区筛选候选（A-DASH-005「默认本人全部可见病区」/ C-DASH-008）：按登录快照的病区清单交集；"
                            + "全院 / 科室 / 本人快照回落全部启用病区（行级隔离仍由各卡片的 @DataScope 出行）")
    @GetMapping("/wards")
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:view")
    public ApiResponse<DashboardTaskService.WardOptionsView> wards() {
        return ApiResponse.ok(taskService.wardOptions());
    }
}
