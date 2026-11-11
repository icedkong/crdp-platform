package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientRegistrationService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出院登记 / 患者登记与病案号查重（E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002；docs/api/patient.md §2，页面
 * /followup/patient/new，pages.md §0.1）。权限 {@code followup:patient:create}（菜单 plt_menu 213），
 * 接口资源由启动扫描登记（SCAN）。
 */
@Tag(name = "医院端患者登记")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientRegistrationController {

    private final PatientRegistrationService service;

    public PatientRegistrationController(PatientRegistrationService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "病案号查重（A-PAT-007、C-PAT-002）：exists 按全院（范围外同号只报存在）；"
                            + "patient 为范围内已有患者的脱敏摘要（可跳转）；姓名 + 出生日期相同时返回"
                            + " possibleMatches（可能重复，提示不阻止）")
    @PostMapping("/check-duplicate")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:create")
    public ApiResponse<PatientRegistrationService.DuplicateCheckView> checkDuplicate(
            @RequestBody DuplicateCheckRequest request) {
        return ApiResponse.ok(
                service.check(
                        new PatientRegistrationService.DuplicateCheckRequest(
                                request.medicalRecordNo(), request.name(), request.birthDate())));
    }

    @Operation(
            summary =
                    "出院登记（A-PAT-003）/ 患者登记（C-PAT-001）：最小必填集落 pt_patient 实体列；"
                            + "病案号本院唯一，重复返回 40902（data 带范围内已有患者脱敏摘要）；"
                            + "写 PATIENT_CREATE 审计（敏感值只存摘要）")
    @PostMapping
    @RequireModule("followup")
    @RequiresPermission("followup:patient:create")
    public ApiResponse<PatientRegistrationService.CreatedView> create(
            @RequestBody CreateRequest request) {
        return ApiResponse.ok(
                service.create(
                        new PatientRegistrationService.CreateRequest(
                                request.medicalRecordNo(),
                                request.name(),
                                request.gender(),
                                request.birthDate(),
                                request.deptId(),
                                request.wardId(),
                                request.surgeryDate(),
                                request.admissionDate(),
                                request.dischargeDate(),
                                request.dischargeStatus(),
                                request.legacyNo(),
                                request.implant() == null ? null : request.implant().toPayload())));
    }

    @Operation(
            summary =
                    "登记候选（新建患者页下拉）：病区 / 科室（启用项），性别与出院状态为实体列编码"
                            + "（出院状态 A 规格字段 191：1=存活 / 2=病重自动出院 / 3=死亡）")
    @GetMapping("/registration-options")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:create")
    public ApiResponse<PatientRegistrationService.RegistrationOptionsView> options() {
        return ApiResponse.ok(service.options());
    }

    /** 查重请求体：medicalRecordNo 必填；name + birthDate 可选（C-PAT-002 可能重复提示）。 */
    public record DuplicateCheckRequest(
            @Size(max = 64) String medicalRecordNo,
            @Size(max = 64) String name,
            LocalDate birthDate) {}

    /** 登记请求体（字段即 pt_patient 实体列；A 出院登记最小必填集由服务端校验；implant 为医院 C 首条植入记录，implant 开关开启时必填）。 */
    public record CreateRequest(
            @Size(max = 64) String medicalRecordNo,
            @Size(max = 64) String name,
            @Size(max = 1) String gender,
            LocalDate birthDate,
            Long deptId,
            Long wardId,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            Integer dischargeStatus,
            @Size(max = 64) String legacyNo,
            ImplantPayloadRequest implant) {}

    /** C 首条植入记录载荷（结构同 implant 域 POST /patients/{id}/implants 请求体，C-PAT-001）。 */
    public record ImplantPayloadRequest(
            @Size(max = 16) String implantType,
            LocalDate implantDate,
            @Size(max = 16) String deviceType,
            @Size(max = 32) String brand,
            @Size(max = 128) String model,
            @Size(max = 64) String serialNo,
            @Size(max = 16) String pacingMode,
            @Size(max = 128) String hospital,
            @Size(max = 64) String doctor,
            @Size(max = 32) String indication,
            @Size(max = 255) String indicationNote,
            @Size(max = 255) String note,
            List<LeadPayloadRequest> leads) {

        com.crdp.followup.implant.app.ImplantService.ImplantPayload toPayload() {
            return new com.crdp.followup.implant.app.ImplantService.ImplantPayload(
                    implantType,
                    implantDate,
                    deviceType,
                    brand,
                    model,
                    serialNo,
                    pacingMode,
                    hospital,
                    doctor,
                    indication,
                    indicationNote,
                    note,
                    leads == null
                            ? null
                            : leads.stream()
                                    .map(
                                            lead ->
                                                    new com.crdp.followup.implant.app.ImplantService
                                                            .LeadPayload(
                                                            lead.id(),
                                                            lead.chamber(),
                                                            lead.position(),
                                                            lead.model(),
                                                            lead.implantDate(),
                                                            lead.status()))
                                    .toList());
        }
    }

    /** 电极载荷（结构同 implant 域 LeadPayloadRequest）。 */
    public record LeadPayloadRequest(
            @Size(max = 20) String id,
            @Size(max = 4) String chamber,
            @Size(max = 32) String position,
            @Size(max = 128) String model,
            LocalDate implantDate,
            @Size(max = 16) String status) {}
}
