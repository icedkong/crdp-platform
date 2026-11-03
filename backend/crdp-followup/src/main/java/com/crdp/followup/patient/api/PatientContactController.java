package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientContactService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联系方式版本（E13-T03；docs/api/patient.md §6，pages.md §4 概览「联系方式与变更记录从概览进入」）。 查询 {@code
 * followup:patient:view}（菜单 plt_menu 21），新增版本 {@code followup:patient:edit}（按钮 plt_menu 214，平台库
 * V38）。 注意区分：{@code /contact-info} 是联系方式版本；{@code /contacts} 是沟通记录（followup.md §6，随 E15）。
 */
@Tag(name = "医院端患者联系方式")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientContactController {

    private final PatientContactService service;

    public PatientContactController(PatientContactService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "联系方式：当前与历史版本（E13-T03，脱敏）。电话1 在前、版本新在前，当前版本 current=true；"
                            + "明文只走 /plaintext 专用通道（patient.md §5）")
    @GetMapping("/{id}/contact-info")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientContactService.ContactInfoView> contactInfo(
            @PathVariable("id") long id) {
        return ApiResponse.ok(service.infoOf(id));
    }

    @Operation(
            summary =
                    "新增联系方式版本（不覆盖旧值，验收「联系方式改动保留历史」）：换号 / 微信标志变化时新增一条，"
                            + "记录生效日期与来源；与当前版本完全相同 40001；写 PATIENT_CONTACT_ADD 审计（脱敏摘要）")
    @PostMapping("/{id}/contact-info")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:edit")
    public ApiResponse<PatientContactService.ContactVersionView> addVersion(
            @PathVariable("id") long id, @RequestBody AddContactRequest request) {
        return ApiResponse.ok(
                service.addVersion(
                        id,
                        new PatientContactService.AddRequest(
                                request.slot(),
                                request.phone(),
                                request.effectiveDate(),
                                request.source(),
                                request.sourceRefId(),
                                request.wechatAdded(),
                                request.remark())));
    }

    /** 新增版本请求体：slot（PHONE_1 / PHONE_2）与 phone 必填；effectiveDate 缺省今天；source 缺省 MANUAL。 */
    public record AddContactRequest(
            @Size(max = 16) String slot,
            @Size(max = 32) String phone,
            LocalDate effectiveDate,
            @Size(max = 32) String source,
            Long sourceRefId,
            Boolean wechatAdded,
            @Size(max = 255) String remark) {}
}
