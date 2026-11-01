package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformDictAdminService;
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
 * E06-T10 / PLT-CFG-001：平台端「字典管理」API（docs/api/platform.md §7，pages.md §11 /platform/dict）。 查看与维护同码
 * platform:dict:edit（规格权限点表只有维护一个码，菜单按它下发）。 编码创建后不可改；字典项停用不删除；保存即时下发全部租户（缓存失效）并写平台审计。
 */
@Tag(name = "平台端字典管理")
@RestController
@RequestMapping("/api/platform/dicts")
@Validated
public class PlatformDictController {

    private final PlatformDictAdminService service;

    public PlatformDictController(PlatformDictAdminService service) {
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

    @Operation(summary = "字典类型列表（含停用行与生效项数）")
    @GetMapping
    @RequiresPermission("platform:dict:edit")
    public ApiResponse<List<PlatformDictAdminService.DictTypeView>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(summary = "字典项列表（含停用行；停用项不进租户生效集）")
    @GetMapping("/{code}/items")
    @RequiresPermission("platform:dict:edit")
    public ApiResponse<List<PlatformDictAdminService.DictItemView>> items(
            @PathVariable("code") String code) {
        return ApiResponse.ok(service.items(code));
    }

    @Operation(summary = "新建字典类型：编码全局唯一（40902）、小写字母开头，创建后不可改")
    @PostMapping
    @RequiresPermission("platform:dict:edit")
    public ApiResponse<PlatformDictAdminService.DictTypeView> create(
            @RequestBody DictRequest request) {
        return ApiResponse.ok(
                service.createDict(
                        new PlatformDictAdminService.DictSave(
                                request.code(), request.name(), request.description())));
    }

    @Operation(summary = "维护字典类型：名称 / 说明 / 停用启用（编码不可改）")
    @PutMapping("/{code}")
    @RequiresPermission("platform:dict:edit")
    public ApiResponse<PlatformDictAdminService.DictTypeView> update(
            @PathVariable("code") String code, @RequestBody DictRequest request) {
        return ApiResponse.ok(
                service.updateDict(
                        code,
                        new PlatformDictAdminService.DictUpdate(
                                request.name(), request.description(), request.status())));
    }

    @Operation(summary = "新增字典项：项编码字典内唯一（40902），创建后不可改")
    @PostMapping("/{code}/items")
    @RequiresPermission("platform:dict:edit")
    public ApiResponse<List<PlatformDictAdminService.DictItemView>> addItem(
            @PathVariable("code") String code, @RequestBody ItemRequest request) {
        return ApiResponse.ok(
                service.addItem(
                        code,
                        new PlatformDictAdminService.ItemSave(
                                request.itemCode(), request.label(), request.sortNo())));
    }

    @Operation(summary = "维护字典项：文字 / 排序 / 停用启用（停用不删除；项编码不可改）")
    @PutMapping("/{code}/items/{itemCode}")
    @RequiresPermission("platform:dict:edit")
    public ApiResponse<List<PlatformDictAdminService.DictItemView>> updateItem(
            @PathVariable("code") String code,
            @PathVariable("itemCode") String itemCode,
            @RequestBody ItemRequest request) {
        return ApiResponse.ok(
                service.updateItem(
                        code,
                        itemCode,
                        new PlatformDictAdminService.ItemUpdate(
                                request.label(), request.sortNo(), request.status())));
    }
}
