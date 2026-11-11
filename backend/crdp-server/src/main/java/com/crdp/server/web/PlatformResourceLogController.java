package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.ResourceChangeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端资源变更记录 API（E05-T10 / PLT-PERM-012，P2）：菜单、接口资源变更历史的查询与导出。 权限沿用菜单资源查看 `platform:menu:view`（平台规格
 * §4 权限点表未单列资源记录权限；菜单与接口资源变更统一从「菜单资源」页进入）。
 */
@Tag(name = "平台端资源变更记录")
@RestController
@RequestMapping("/api/platform/resource-logs")
public class PlatformResourceLogController {

    private final ResourceChangeLogService service;

    public PlatformResourceLogController(ResourceChangeLogService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "资源变更记录分页（PLT-PERM-012，新→旧）：resourceType=MENU|API|FIELD（FIELD 随字段资源管理"
                            + " 接口落地后写入）、action=CREATE|UPDATE|DELETE|OFFLINE|REGISTER、channel=PAGE|SYNC、"
                            + "keyword 匹配资源名 / 标识 / 摘要")
    @GetMapping
    @RequiresPermission("platform:menu:view")
    public ApiResponse<ResourceChangeLogService.LogPage> search(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(
                service.search(resourceType, action, channel, keyword, pageNo, pageSize));
    }

    @Operation(
            summary =
                    "导出资源变更记录 CSV（PLT-PERM-012）：条件与查询同口径；UTF-8 BOM、首列水印、行数上限 5 万；"
                            + "每次导出写 DATA_ACCESS 审计")
    @GetMapping("/export")
    @RequiresPermission("platform:menu:view")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String keyword) {
        ResourceChangeLogService.ExportFile file =
                service.export(resourceType, action, channel, keyword);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(file.body());
    }
}
