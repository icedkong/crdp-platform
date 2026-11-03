package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientSearchService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者列表、检索与筛选（E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009；docs/api/patient.md §1，页面
 * /followup/patient，pages.md §3）。权限 {@code followup:patient:view}（菜单 plt_menu
 * 21），接口资源由启动扫描登记（SCAN）。
 *
 * <p>病区隔离由数据范围承担（@DataScope patient，E07-T02）：越范围患者行不可见；姓名 / 病案号 检索为盲索引精确匹配。列表行姓名与病案号只返回脱敏值。
 */
@Tag(name = "医院端患者列表")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientSearchController {

    private final PatientSearchService service;

    public PatientSearchController(PatientSearchService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "患者列表检索（A-PAT-001、A-PAT-002、C-PAT-009）：keyword 按姓名 / 病案号盲索引"
                            + "精确匹配；filters 见 README §2.4；排序白名单 nextVisitDate / dischargeDate，"
                            + "默认下次随访升序；行内姓名与病案号脱敏；批量查询写数据访问审计")
    @PostMapping("/search")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientSearchService.PatientPage> search(
            @RequestBody(required = false) SearchBody body) {
        return ApiResponse.ok(
                service.search(
                        new PatientSearchService.SearchRequest(
                                body == null ? null : body.pageNo(),
                                body == null ? null : body.pageSize(),
                                body == null || body.sort() == null
                                        ? null
                                        : body.sort().stream()
                                                .map(
                                                        item ->
                                                                new PatientSearchService.SortSpec(
                                                                        item.field(), item.order()))
                                                .toList(),
                                body == null ? null : body.keyword(),
                                body == null ? null : toFilters(body.filters()))));
    }

    @Operation(summary = "筛选候选（列表页病区下拉，启用项）；随访状态等枚举文案由前端维护")
    @GetMapping("/filter-options")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientSearchService.FilterOptionsView> filterOptions() {
        return ApiResponse.ok(service.filterOptions());
    }

    /** 检索请求体（docs/api/README.md §2.4）。 */
    public record SearchBody(
            Integer pageNo,
            Integer pageSize,
            List<SortBody> sort,
            String keyword,
            FiltersBody filters) {}

    public record SortBody(String field, String order) {}

    /** 简单筛选（全部可空；C 的器械类筛选随 E21 植入数据落地）。 */
    public record FiltersBody(
            List<Long> wardIds,
            List<Long> deptIds,
            List<String> status,
            Long doctorId,
            Integer dischargeStatus,
            String patientType,
            LocalDate surgeryFrom,
            LocalDate surgeryTo,
            LocalDate dischargeFrom,
            LocalDate dischargeTo,
            LocalDate nextVisitFrom,
            LocalDate nextVisitTo) {}

    private static PatientSearchService.Filters toFilters(FiltersBody filters) {
        if (filters == null) {
            return null;
        }
        return new PatientSearchService.Filters(
                filters.wardIds(),
                filters.deptIds(),
                filters.status(),
                filters.doctorId(),
                filters.dischargeStatus(),
                filters.patientType(),
                filters.surgeryFrom(),
                filters.surgeryTo(),
                filters.dischargeFrom(),
                filters.dischargeTo(),
                filters.nextVisitFrom(),
                filters.nextVisitTo());
    }
}
