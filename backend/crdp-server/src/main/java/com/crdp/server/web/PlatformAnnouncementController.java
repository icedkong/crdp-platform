package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformAnnouncementService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E05-T11 / PLT-CFG-009：平台端「系统公告」API（docs/api/platform.md §7，pages.md §11 /platform/notice，权限
 * platform:notice:edit——渠道与模板页随后续任务并入同一菜单）。 发布即生效：scope=ALL 全部租户、scope=TENANTS 指定租户（未知
 * 40400）；发布后不可编辑，更正走撤下 + 重新发布；发布与撤下写平台审计。
 */
@Tag(name = "平台端系统公告")
@RestController
@RequestMapping("/api/platform/announcements")
@Validated
public class PlatformAnnouncementController {

    private final PlatformAnnouncementService service;

    public PlatformAnnouncementController(PlatformAnnouncementService service) {
        this.service = service;
    }

    public record PublishRequest(
            @Size(max = 128) String title,
            @Size(max = 5000) String content,
            @Size(max = 16) String scope,
            List<Long> tenantIds) {}

    @Operation(summary = "发布公告（发布即生效）：ALL=全部租户、TENANTS=指定租户（未知 40400）")
    @PostMapping
    @RequiresPermission("platform:notice:edit")
    public ApiResponse<PlatformAnnouncementService.AnnouncementView> publish(
            @RequestBody PublishRequest request) {
        return ApiResponse.ok(
                service.publish(
                        new PlatformAnnouncementService.PublishCommand(
                                request.title(),
                                request.content(),
                                request.scope(),
                                request.tenantIds())));
    }

    @Operation(summary = "公告分页（新→旧；keyword 按标题模糊）")
    @GetMapping
    @RequiresPermission("platform:notice:edit")
    public ApiResponse<PlatformAnnouncementService.AnnouncementPage> page(
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.page(pageNo, pageSize, keyword));
    }

    @Operation(summary = "公告详情（含定向租户名单）")
    @GetMapping("/{id}")
    @RequiresPermission("platform:notice:edit")
    public ApiResponse<PlatformAnnouncementService.AnnouncementView> detail(
            @PathVariable("id") long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @Operation(summary = "撤下公告（医院端即刻不可见；已撤下 40901）")
    @PutMapping("/{id}/revoke")
    @RequiresPermission("platform:notice:edit")
    public ApiResponse<PlatformAnnouncementService.AnnouncementView> revoke(
            @PathVariable("id") long id) {
        return ApiResponse.ok(service.revoke(id));
    }
}
