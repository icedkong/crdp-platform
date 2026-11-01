package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.system.dict.TenantDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T10 / PLT-CFG-002：医院端「机构设置 · 租户字典」API（docs/api/system.md §4，pages.md §10）。 查看与维护同码
 * tenant:dict:edit。继承平台字典：可新增本院自有项或停用 / 恢复平台项（不能改平台项文字与 排序）；本院自有字典可建可改。写操作写租户审计（PLT-CFG-011）。
 */
@Tag(name = "医院端租户字典")
@RestController
@RequestMapping("/api/hospital/system/dicts")
@Validated
public class HospitalDictController {

    private final TenantDictService service;

    public HospitalDictController(TenantDictService service) {
        this.service = service;
    }

    public record DictRequest(
            @Size(max = 64) String code,
            @Size(max = 128) String name,
            @Size(max = 255) String description,
            String status) {}

    public record ItemRequest(
            @Size(max = 64) String itemCode,
            @Size(max = 128) String label,
            Integer sortNo,
            String status) {}

    @Operation(summary = "字典类型列表：平台字典（继承）+ 本院自有字典，含生效项数")
    @GetMapping
    @RequiresPermission("tenant:dict:edit")
    public ApiResponse<List<TenantDictService.DictTypeView>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary = "字典项列表（生效集）：平台项 − 本院停用 + 本院自有项；平台项 editable=false" + "（只能停用 / 恢复），本院自有项可维护")
    @GetMapping("/{code}/items")
    @RequiresPermission("tenant:dict:edit")
    public ApiResponse<List<TenantDictService.DictItemView>> items(
            @PathVariable("code") String code) {
        return ApiResponse.ok(service.items(code));
    }

    @Operation(summary = "新建本院字典：编码本院唯一（含与平台字典冲突 40902），创建后不可改")
    @PostMapping
    @RequiresPermission("tenant:dict:edit")
    public ApiResponse<TenantDictService.DictTypeView> create(@RequestBody DictRequest request) {
        return ApiResponse.ok(
                service.createDict(
                        new TenantDictService.DictSave(
                                request.code(), request.name(), request.description())));
    }

    @Operation(summary = "维护本院字典（名称 / 说明 / 停用启用）；平台字典不可修改（40001）")
    @PutMapping("/{code}")
    @RequiresPermission("tenant:dict:edit")
    public ApiResponse<TenantDictService.DictTypeView> update(
            @PathVariable("code") String code, @RequestBody DictRequest request) {
        return ApiResponse.ok(
                service.updateDict(
                        code,
                        new TenantDictService.DictUpdate(
                                request.name(), request.description(), request.status())));
    }

    @Operation(summary = "新增本院自有项（加进平台字典或本院字典）：项编码在生效集内唯一（40902）")
    @PostMapping("/{code}/items")
    @RequiresPermission("tenant:dict:edit")
    public ApiResponse<List<TenantDictService.DictItemView>> addItem(
            @PathVariable("code") String code, @RequestBody ItemRequest request) {
        return ApiResponse.ok(
                service.addItem(
                        code,
                        new TenantDictService.ItemSave(
                                request.itemCode(), request.label(), request.sortNo())));
    }

    @Operation(summary = "维护字典项：本院自有项可改文字 / 排序 / 停用；平台项只能停用 / 恢复" + "（改文字与排序 40001）")
    @PutMapping("/{code}/items/{itemCode}")
    @RequiresPermission("tenant:dict:edit")
    public ApiResponse<List<TenantDictService.DictItemView>> updateItem(
            @PathVariable("code") String code,
            @PathVariable("itemCode") String itemCode,
            @RequestBody ItemRequest request) {
        return ApiResponse.ok(
                service.updateItem(
                        code,
                        itemCode,
                        new TenantDictService.ItemUpdate(
                                request.label(), request.sortNo(), request.status())));
    }
}
