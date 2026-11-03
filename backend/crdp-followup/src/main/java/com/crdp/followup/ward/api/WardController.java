package com.crdp.followup.ward.api;

import com.crdp.followup.ward.app.WardService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 病区 / 中心字典（E13-T08 / A-WARD-001、A-WARD-008、C-WARD-001；docs/api/system.md §1，页面 /system/ward，
 * pages.md §10）。权限 {@code followup:ward:edit}（菜单 plt_menu 38，平台库 V40）——A-WARD-001「租户管理员
 * 维护」；病区值映射接口（A-WARD-006）随 E18 导入任务。接口资源由启动扫描登记（SCAN）。
 */
@Tag(name = "医院端病区管理")
@RestController
@RequestMapping("/api/hospital/followup/wards")
public class WardController {

    private final WardService service;

    public WardController(WardService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "病区列表（A-WARD-001）：含停用，按排序号升序；行含类型、别名、默认随访负责人、"
                            + "在册患者数与成员数（A-WARD-008 停用提示与 pages.md §10「成员、患者数」）")
    @GetMapping
    @RequireModule("followup")
    @RequiresPermission("followup:ward:edit")
    public ApiResponse<WardService.WardsView> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary =
                    "新建病区（A-WARD-001）：编码本院唯一（重复 40902）、创建后不可改；类型病区 / 中心；"
                            + "别名多值（; 分隔，匹配历史写法）；写 WARD_CREATE 审计")
    @PostMapping
    @RequireModule("followup")
    @RequiresPermission("followup:ward:edit")
    public ApiResponse<WardService.WardView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(
                service.create(
                        new WardService.CreateRequest(
                                request.code(),
                                request.name(),
                                request.wardType(),
                                request.aliases(),
                                request.defaultOwnerId(),
                                request.sortNo())));
    }

    @Operation(
            summary =
                    "编辑病区（A-WARD-001）：名称、类型、别名、默认随访负责人、排序；编码不可改"
                            + "（携带且不同 40001）；写 WARD_UPDATE 审计（前后值）")
    @PutMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:ward:edit")
    public ApiResponse<WardService.WardView> update(
            @PathVariable long id, @RequestBody UpdateRequest request) {
        return ApiResponse.ok(
                service.update(
                        id,
                        new WardService.UpdateRequest(
                                request.code(),
                                request.name(),
                                request.wardType(),
                                request.aliases(),
                                request.defaultOwnerId(),
                                request.sortNo())));
    }

    @Operation(
            summary =
                    "停用 / 启用病区（A-WARD-008）：病区下仍有在册患者时不能停用（40901，须先转出）；"
                            + "停用后不再进登记 / 转病区候选；不自动改派成员（OQ-10）；写 WARD_DISABLE /"
                            + " WARD_ENABLE 审计（被阻止写 DENIED）")
    @PutMapping("/{id}/status")
    @RequireModule("followup")
    @RequiresPermission("followup:ward:edit")
    public ApiResponse<WardService.WardView> changeStatus(
            @PathVariable long id, @RequestBody StatusRequest request) {
        return ApiResponse.ok(
                service.changeStatus(id, new WardService.StatusRequest(request.status())));
    }

    @Operation(summary = "病区成员与患者数（pages.md §10）：负责病区 = 本病区的启用用户")
    @GetMapping("/{id}/members")
    @RequireModule("followup")
    @RequiresPermission("followup:ward:edit")
    public ApiResponse<WardService.MembersView> members(@PathVariable long id) {
        return ApiResponse.ok(service.members(id));
    }

    /** 新建请求体（编码 ≤64，创建后不可改；aliases 清单或含 ; 的原始串均可）。 */
    public record CreateRequest(
            @Size(max = 64) String code,
            @Size(max = 128) String name,
            @Size(max = 16) String wardType,
            List<String> aliases,
            Long defaultOwnerId,
            Integer sortNo) {}

    /** 编辑请求体（code 可省略；携带且与当前不同 40001）。 */
    public record UpdateRequest(
            @Size(max = 64) String code,
            @Size(max = 128) String name,
            @Size(max = 16) String wardType,
            List<String> aliases,
            Long defaultOwnerId,
            Integer sortNo) {}

    /** 状态变更请求体：ENABLED / DISABLED。 */
    public record StatusRequest(@Size(max = 16) String status) {}
}
