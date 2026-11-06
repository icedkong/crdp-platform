package com.crdp.followup.report.api;

import com.crdp.followup.report.app.ReportSearchService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全院报告单列表与检索（E16-T05 / A-ATT-016、C-SCAN-014；docs/api/report.md §2，页面 /followup/report， pages.md
 * §6.4）。独立菜单「报告单与资料」（plt_menu 23），权限 {@code followup:report:view}（OQ-12 定论的菜单码）。
 *
 * <p>按病区隔离由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（越范围患者的报告单不可见）； 行内姓名 /
 * 病案号只输出脱敏值；批量查询写 BATCH_QUERY 数据访问审计。
 */
@Tag(name = "医院端报告单（全院列表与检索）")
@RestController
@RequestMapping("/api/hospital/followup/reports")
public class ReportSearchController {

    private final ReportSearchService service;

    public ReportSearchController(ReportSearchService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "全院报告单检索（A-ATT-016、C-SCAN-014）：患者关键词（姓名 / 病案号盲索引精确、"
                            + "随访编号 / 原系统编号等值，并 LIKE 匹配名称 / 医院 / 所见 / 诊断等文字字段）、"
                            + "类型、报告时间与上传日期范围、上传人、位置（UNASSIGNED 未指定位置 / "
                            + "BASELINE 含基线 / FOLLOWUP 含随访记录）、电子化状态（C）、病区筛选；"
                            + "行含患者脱敏信息、位置标签（多个由前端折叠 +N）与缩略图首附件；"
                            + "点击行进入患者档案报告单页签由前端路由；批量查询写数据访问审计")
    @PostMapping("/search")
    @RequireModule("followup")
    @RequiresPermission("followup:report:view")
    public ApiResponse<ReportSearchService.ReportPage> search(
            @RequestBody(required = false) SearchBody body) {
        return ApiResponse.ok(
                service.search(
                        body == null
                                ? null
                                : new ReportSearchService.SearchRequest(
                                        body.pageNo(),
                                        body.pageSize(),
                                        body.keyword(),
                                        body.typeCode(),
                                        body.reportDateFrom(),
                                        body.reportDateTo(),
                                        body.uploadedFrom(),
                                        body.uploadedTo(),
                                        body.creatorId(),
                                        body.locationScope(),
                                        body.digitizeStatus(),
                                        body.wardIds())));
    }

    /** 检索请求体（docs/api/report.md §2；字段全部可空 = 不过滤）。 */
    record SearchBody(
            Integer pageNo,
            Integer pageSize,
            String keyword,
            String typeCode,
            LocalDate reportDateFrom,
            LocalDate reportDateTo,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            Long creatorId,
            String locationScope,
            String digitizeStatus,
            List<Long> wardIds) {}
}
