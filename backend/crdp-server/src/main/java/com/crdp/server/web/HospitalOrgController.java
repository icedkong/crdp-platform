package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import com.crdp.system.org.DeptImportService;
import com.crdp.system.org.OrgAdminService;
import com.crdp.system.org.OrgAdminService.DeptImpactView;
import com.crdp.system.org.OrgAdminService.DeptSave;
import com.crdp.system.org.OrgAdminService.DeptView;
import com.crdp.system.org.OrgAdminService.OrgImpactView;
import com.crdp.system.org.OrgAdminService.OrgSave;
import com.crdp.system.org.OrgAdminService.OrgTreeView;
import com.crdp.system.org.OrgAdminService.OrgView;
import com.crdp.system.org.OrgAdminService.StatusChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * E06-T02 / PLT-ORG-001、002：医院端组织架构（院区与科室树）维护接口。 路径与 docs/api/system.md §1 同步（原「推断，待确认」按本实现定稿：院区
 * /orgs、科室 /depts 分资源）。 查看用 tenant:org:view，维护（含影响面查询，属维护流程的一部分）用 tenant:org:edit。
 */
@Tag(name = "医院端组织架构")
@RestController
@Validated
public class HospitalOrgController {

    private final OrgAdminService service;
    private final DeptImportService importService;

    public HospitalOrgController(OrgAdminService service, DeptImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    /** 院区与科室树。singleOrg=true 表示本院启用院区 ≤ 1，前端可隐藏院区层级（PLT-ORG-001）。 */
    @Operation(summary = "组织树（PLT-ORG-001、002）：院区 → 多级科室；含停用节点（维护视角）")
    @GetMapping("/api/hospital/system/orgs/tree")
    @RequiresPermission("tenant:org:view")
    public ApiResponse<OrgTreeView> tree() {
        return ApiResponse.ok(service.tree());
    }

    /** 停用 / 启用前的影响面（平台规格「停用前返回影响」）：科室数、启用科室数、成员数。患者数随 E08+ 患者表落地补充。 */
    @Operation(summary = "院区停用影响面（PLT-ORG-001）：科室数、启用科室数、科室成员数")
    @GetMapping("/api/hospital/system/orgs/{orgId}/impact")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<OrgImpactView> orgImpact(@PathVariable long orgId) {
        return ApiResponse.ok(service.orgImpact(orgId));
    }

    @Operation(summary = "科室停用影响面（PLT-ORG-002）：成员数、下级科室数、启用下级数")
    @GetMapping("/api/hospital/system/depts/{deptId}/impact")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<DeptImpactView> deptImpact(@PathVariable long deptId) {
        return ApiResponse.ok(service.deptImpact(deptId));
    }

    // ---- 科室批量导入（E06-T03 / PLT-ORG-003）----

    @Operation(summary = "科室导入模板：表头 + 两行示例（全部虚构数据），xlsx 单工作表")
    @GetMapping("/api/hospital/system/orgs/import-template")
    @RequiresPermission("tenant:org:edit")
    public ResponseEntity<byte[]> importTemplate() {
        return xlsx("科室导入模板.xlsx", importService.template());
    }

    @Operation(
            summary =
                    "科室批量导入（PLT-ORG-003）：上传 xlsx，逐行校验并落库，单行失败不影响其他行；"
                            + "返回逐行结果（CREATED / UPDATED / FAILED 与原因）；写 ORG_IMPORT 审计")
    @PostMapping("/api/hospital/system/orgs/import")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<DeptImportService.ImportReport> importDepts(
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, 40001, "请选择要导入的 xlsx 文件");
        }
        try (java.io.InputStream in = file.getInputStream()) {
            return ApiResponse.ok(importService.importDepts(in));
        } catch (java.io.IOException ex) {
            throw new ApiException(400, 40001, "文件读取失败，请重新上传");
        }
    }

    private static ResponseEntity<byte[]> xlsx(String filename, byte[] body) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + java.net.URLEncoder.encode(
                                        filename, java.nio.charset.StandardCharsets.UTF_8))
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    // ---- 院区（sys_org）----

    public record OrgCreateRequest(String code, String name) {}

    /** 更新只改名称；编码随请求携带但不允许变化（规格「创建后不可改」）。 */
    public record OrgUpdateRequest(String code, String name) {}

    /** action：ENABLE / DISABLE；reason 可选（≤200），写审计。 */
    public record OrgStatusRequest(
            @Size(max = 200) String action, @Size(max = 200) String reason) {}

    @Operation(summary = "新增院区（PLT-ORG-001）：编码租户内唯一，写 ORG_CREATE 审计")
    @PostMapping("/api/hospital/system/orgs")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<OrgView> createOrg(@RequestBody OrgCreateRequest request) {
        return ApiResponse.ok(service.createOrg(new OrgSave(request.code(), request.name())));
    }

    @Operation(summary = "编辑院区（PLT-ORG-001）：名称可改、编码不可改，写 ORG_UPDATE 审计")
    @PutMapping("/api/hospital/system/orgs/{orgId}")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<OrgView> updateOrg(
            @PathVariable long orgId, @RequestBody OrgUpdateRequest request) {
        return ApiResponse.ok(
                service.updateOrg(orgId, new OrgSave(request.code(), request.name())));
    }

    @Operation(
            summary =
                    "院区停用 / 启用（PLT-ORG-001）：action=ENABLE|DISABLE；有启用中的科室时 40901；"
                            + "重复状态流转 40901；写 ORG_STATUS 审计")
    @PostMapping("/api/hospital/system/orgs/{orgId}/status")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<OrgView> changeOrgStatus(
            @PathVariable long orgId, @RequestBody OrgStatusRequest request) {
        return ApiResponse.ok(
                service.changeOrgStatus(
                        orgId, new StatusChange(request.action(), request.reason())));
    }

    // ---- 科室（sys_dept）----

    /** 新建科室：orgId、name 必填；parentDeptId 省略或 0 = 院区顶层。 */
    public record DeptCreateRequest(
            Long orgId,
            Long parentDeptId,
            String code,
            String name,
            String deptType,
            Long leaderUserId,
            String hisCode,
            Integer sortNo) {}

    /** 编辑科室：code 不可改；orgId / parentDeptId 可整体移动（同级或跨科室移动，不得移入自己子树）。 */
    public record DeptUpdateRequest(
            Long orgId,
            Long parentDeptId,
            String code,
            String name,
            String deptType,
            Long leaderUserId,
            String hisCode,
            Integer sortNo) {}

    public record DeptStatusRequest(
            @Size(max = 200) String action, @Size(max = 200) String reason) {}

    @Operation(summary = "新增科室（PLT-ORG-002）：院区下多级科室；编码租户内唯一，写 DEPT_CREATE 审计")
    @PostMapping("/api/hospital/system/depts")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<DeptView> createDept(@RequestBody DeptCreateRequest request) {
        return ApiResponse.ok(
                service.createDept(
                        new DeptSave(
                                request.orgId(),
                                request.parentDeptId(),
                                request.code(),
                                request.name(),
                                request.deptType(),
                                request.leaderUserId(),
                                request.hisCode(),
                                request.sortNo())));
    }

    @Operation(summary = "编辑 / 移动科室（PLT-ORG-002）：名称、类型、负责人、HIS 编码、排序、上级与院区；写 DEPT_UPDATE 审计")
    @PutMapping("/api/hospital/system/depts/{deptId}")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<DeptView> updateDept(
            @PathVariable long deptId, @RequestBody DeptUpdateRequest request) {
        return ApiResponse.ok(
                service.updateDept(
                        deptId,
                        new DeptSave(
                                request.orgId(),
                                request.parentDeptId(),
                                request.code(),
                                request.name(),
                                request.deptType(),
                                request.leaderUserId(),
                                request.hisCode(),
                                request.sortNo())));
    }

    @Operation(
            summary =
                    "科室停用 / 启用（PLT-ORG-002）：action=ENABLE|DISABLE；有启用中的下级科室时 40901；"
                            + "停用科室不再出现在业务选择器；写 DEPT_STATUS 审计")
    @PostMapping("/api/hospital/system/depts/{deptId}/status")
    @RequiresPermission("tenant:org:edit")
    public ApiResponse<DeptView> changeDeptStatus(
            @PathVariable long deptId, @RequestBody DeptStatusRequest request) {
        return ApiResponse.ok(
                service.changeDeptStatus(
                        deptId, new StatusChange(request.action(), request.reason())));
    }
}
