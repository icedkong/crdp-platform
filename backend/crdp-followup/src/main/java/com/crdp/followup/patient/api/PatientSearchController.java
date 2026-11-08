package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientSearchFieldService;
import com.crdp.followup.patient.app.PatientSearchService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 患者列表、检索与筛选（E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009；E13-T06 高级检索 A-PAT-014、
 * A-NFR-009、C-PAT-013；docs/api/patient.md §1，页面 /followup/patient，pages.md §3）。权限 {@code
 * followup:patient:view}（菜单 plt_menu 21），接口资源由启动扫描登记（SCAN）。
 *
 * <p>病区隔离由数据范围承担（@DataScope patient，E07-T02）：越范围患者行不可见（高级检索条件树同样被 改写，自动追加病区条件，A-NFR-009）；姓名 / 病案号
 * 检索为盲索引精确匹配。列表行姓名与病案号只返回脱敏值。
 *
 * <p>高级检索（E13-T06）：条件树为结构化 JSON（components.md §11.2 ConditionGroup），后端只接收结构化条件 ——字段必须出现在
 * search-fields 目录（字段字典生成）且编译为参数化查询，不允许原始 SQL。
 */
@Tag(name = "医院端患者列表")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientSearchController {

    private final PatientSearchService service;
    private final PatientSearchFieldService searchFields;

    public PatientSearchController(
            PatientSearchService service, PatientSearchFieldService searchFields) {
        this.service = service;
        this.searchFields = searchFields;
    }

    @Operation(
            summary =
                    "患者列表检索（A-PAT-001、A-PAT-002、A-PAT-014、C-PAT-009、C-PAT-013）：keyword 按"
                            + "姓名 / 病案号盲索引精确匹配；filters 见 README §2.4；condition 为高级检索"
                            + "条件树（{logic: AND|OR, items: [{field, op, value?} | 组]}，字段只能是"
                            + "search-fields 目录中的字典字段，后端编译为参数化查询并自动追加病区条件）；"
                            + "completenessDrill 为首页完整度卡片的缺失字段钻取（E19-T05：kind=BASELINE|"
                            + "FOLLOWUP + fieldKey + 卡片筛选，ID 集与卡片数字同一计算）；"
                            + "排序白名单 nextVisitDate / dischargeDate，默认下次随访升序；行内姓名与病案号"
                            + "脱敏；批量查询写数据访问审计")
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
                                body == null ? null : toFilters(body.filters()),
                                body == null ? null : body.condition(),
                                body == null ? null : toDrill(body.completenessDrill()))));
    }

    @Operation(summary = "筛选候选（列表页病区下拉，启用项）；随访状态等枚举文案由前端维护")
    @GetMapping("/filter-options")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientSearchService.FilterOptionsView> filterOptions() {
        return ApiResponse.ok(service.filterOptions());
    }

    @Operation(
            summary =
                    "高级检索可用字段目录（A-PAT-014、A-NFR-009）：由字段字典生成（BASELINE 表单、启用、"
                            + "非敏感、实体列存储且已登记列绑定），含取值形态 / 运算符 / 选项；条件树引用目录外"
                            + "字段一律 40001")
    @GetMapping("/search-fields")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientSearchFieldService.CatalogView> searchFields() {
        return ApiResponse.ok(searchFields.catalog());
    }

    /** 检索请求体（docs/api/README.md §2.4）。 */
    public record SearchBody(
            Integer pageNo,
            Integer pageSize,
            List<SortBody> sort,
            String keyword,
            FiltersBody filters,
            JsonNode condition,
            CompletenessDrillBody completenessDrill) {}

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

    /**
     * 完整度缺失字段钻取（E19-T05 / A-DASH-010、A-DASH-011「点击进入缺该字段的患者列表」）： 首页完整度卡片最常缺失字段下钻时由列表页按路由 query
     * 组装；ID 集与卡片数字同一计算（见 {@link PatientSearchService.CompletenessDrill}）。
     */
    public record CompletenessDrillBody(
            String kind,
            String fieldKey,
            Long wardId,
            String patientType,
            LocalDate from,
            LocalDate to,
            String timeField,
            Integer year) {}

    private static PatientSearchService.CompletenessDrill toDrill(CompletenessDrillBody drill) {
        if (drill == null) {
            return null;
        }
        return new PatientSearchService.CompletenessDrill(
                drill.kind(),
                drill.fieldKey(),
                drill.wardId(),
                drill.patientType(),
                drill.from(),
                drill.to(),
                drill.timeField(),
                drill.year());
    }

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
