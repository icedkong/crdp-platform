package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.SavedFilterService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人保存的筛选（E13-T06 / A-PAT-015；docs/api/patient.md §1，患者列表「我的筛选 ▾」）。 权限 {@code
 * followup:patient:view}（与列表检索同口径），接口资源由启动扫描登记（SCAN）。
 *
 * <p>payload = {filters?, condition?}（检索条件，不含检索词）；保存时校验条件树可编译 （字段字典目录白名单 + 参数化，A-NFR-009）；应用保存的筛选仍走
 * /patients/search 的完整校验与数据范围。 仅本人可见（读写都限定 user_id）；他人的 / 不存在的 id 一律 40400。
 */
@Tag(name = "医院端个人筛选")
@RestController
@RequestMapping("/api/hospital/followup/saved-filters")
public class SavedFilterController {

    private final SavedFilterService service;

    public SavedFilterController(SavedFilterService service) {
        this.service = service;
    }

    @Operation(summary = "本人保存的筛选（新在前，仅本人可见）")
    @GetMapping
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<SavedFilterService.SavedFilterList> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary =
                    "保存为个人筛选（{name, payload}；payload = 检索条件对象 {filters?, condition?}，"
                            + "不含检索词；条件树保存时按字段目录校验）")
    @PostMapping
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<SavedFilterService.SavedFilterView> create(@RequestBody CreateBody body) {
        return ApiResponse.ok(service.create(body.name(), body.payload()));
    }

    @Operation(summary = "修改个人筛选（重命名 / 覆盖条件，{name?, payload?} 至少一项）")
    @PutMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<SavedFilterService.SavedFilterView> update(
            @PathVariable("id") long id, @RequestBody UpdateBody body) {
        return ApiResponse.ok(service.update(id, body.name(), body.payload()));
    }

    @Operation(summary = "删除个人筛选（不存在 / 他人的一律 40400）")
    @DeleteMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<Void> delete(@PathVariable("id") long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    /** 保存请求体。 */
    public record CreateBody(String name, JsonNode payload) {}

    /** 修改请求体（至少一项）。 */
    public record UpdateBody(String name, JsonNode payload) {}
}
