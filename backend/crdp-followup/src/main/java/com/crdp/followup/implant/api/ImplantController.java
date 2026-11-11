package com.crdp.followup.implant.api;

import com.crdp.followup.implant.app.ImplantService;
import com.crdp.followup.implant.app.ImplantService.ImplantPayload;
import com.crdp.followup.implant.app.ImplantService.UpdateRequest;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 植入记录（一对多）与电极记录（E21-T01 / C-PAT-004、C-PAT-005）及更换 / 升级 / 电极修正 / 取出流程（E21-T02 /
 * C-PAT-006；docs/api/implant.md §1，pages.md §4.4 基线资料页签「植入记录」区块）。 查询 {@code
 * followup:patient:view}（菜单 21），写入 {@code followup:implant:edit}（按钮 228，平台库 V69；C 规格 §10 权限点表含「更换 /
 * 升级」）。 仅医院 C（功能开关 {@code implant}，关闭 40301；AGENTS.md §5.7）。
 */
@Tag(name = "医院端植入记录")
@RestController
@RequestMapping("/api/hospital/followup")
public class ImplantController {

    private final ImplantService service;

    public ImplantController(ImplantService service) {
        this.service = service;
    }

    @Operation(summary = "全部植入记录与电极（当前器械置顶，其余按植入日期新在前；每条含其电极清单）。" + "验收「一个患者多次植入、多根电极」的数据源")
    @GetMapping("/patients/{id}/implants")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<ImplantService.ImplantListView> list(@PathVariable("id") long id) {
        return ApiResponse.ok(service.list(id));
    }

    @Operation(
            summary =
                    "新建植入记录（首次植入 / 历史建档允许过去日期；新器械接替在用时旧记录自动结束为已更换）。"
                            + "在用器械的更换 / 升级 / 电极修正走 POST /implants/{id}/replace（E21-T02，电极逐根处置）")
    @PostMapping("/patients/{id}/implants")
    @RequireModule("followup")
    @RequiresPermission("followup:implant:edit")
    public ApiResponse<ImplantService.ImplantView> create(
            @PathVariable("id") long id, @RequestBody ImplantPayloadRequest request) {
        return ApiResponse.ok(service.create(id, request.toPayload()));
    }

    @Operation(
            summary =
                    "编辑植入记录与电极（baseVersion 乐观锁；状态系统维护；leads 为 null 不动电极）。"
                            + "写 IMPLANT_UPDATE 审计；字段级历史随 C 字段字典录入（E23-T20）C_IMPLANT 模板")
    @PutMapping("/implants/{implantId}")
    @RequireModule("followup")
    @RequiresPermission("followup:implant:edit")
    public ApiResponse<ImplantService.ImplantView> update(
            @PathVariable("implantId") long implantId, @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(implantId, request));
    }

    @Operation(
            summary =
                    "植入记录选项（植入类型 / 起搏器类型 / 品牌 / 起搏模式（统一字典 C-FLD-003）/ 植入指征 /"
                            + " 电极部位按腔室 / 电极状态）。业务字典（平台库 V70 预置 + 本院覆盖）现读生效项，"
                            + "停用项不进下拉、历史值照常显示（C-FLD-009，E21-T04）")
    @GetMapping("/implants/options")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<ImplantService.OptionsView> options() {
        return ApiResponse.ok(service.options());
    }

    @Operation(
            summary =
                    "更换 / 升级 / 电极修正 / 其他（E21-T02 / C-PAT-006 专用流程）：每次变更新增一条植入记录，"
                            + "目标（在用）记录自动结束为已更换、结束日期 = 新植入日期；电极逐根显式处置——"
                            + "KEEP 沿用（同一电极行挂到新记录下，保留原植入日期）/ ABANDON 废弃留置 / EXTRACT 已拔除，"
                            + "另可新植电极（日期缺省 = 手术日期）；LEAD_REVISION 脉冲发生器不变（器械四字段须与原记录一致）；"
                            + "OTHER 须填 note；FIRST 拒绝（走登记 / 新建）。baseVersion 乐观锁与目标不在用 40900。"
                            + "写 IMPLANT_REPLACE 审计；计划重新起算（C-FU-007）随 E21-T05 / T06")
    @PostMapping("/implants/{implantId}/replace")
    @RequireModule("followup")
    @RequiresPermission("followup:implant:edit")
    public ApiResponse<ImplantService.ImplantView> replace(
            @PathVariable("implantId") long implantId, @RequestBody ReplaceRequestRequest request) {
        return ApiResponse.ok(service.replace(implantId, request.toRequest()));
    }

    @Operation(
            summary =
                    "取出（E21-T02 / C-FU-007 状态面）：在用记录置已取出，结束日期 = 取出日期；"
                            + "在用电极须逐根显式标废弃留置 / 已拔除（KEEP 不属于取出）；取出后无在用器械，"
                            + "再植入走登记 / 新建。写 IMPLANT_EXPLANT 审计；计划侧处理随 E21-T06")
    @PostMapping("/implants/{implantId}/explant")
    @RequireModule("followup")
    @RequiresPermission("followup:implant:edit")
    public ApiResponse<ImplantService.ImplantView> explant(
            @PathVariable("implantId") long implantId, @RequestBody ExplantRequestRequest request) {
        return ApiResponse.ok(service.explant(implantId, request.toRequest()));
    }

    /** 新建请求体：植入记录字段 + 电极清单（无导线起搏器不录电极）。 */
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
            @Valid List<LeadPayloadRequest> leads) {

        ImplantPayload toPayload() {
            return new ImplantPayload(
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
                                                    new ImplantService.LeadPayload(
                                                            lead.id(),
                                                            lead.chamber(),
                                                            lead.position(),
                                                            lead.model(),
                                                            lead.implantDate(),
                                                            lead.status()))
                                    .toList());
        }
    }

    /**
     * 更换 / 升级请求体（E21-T02 / C-PAT-006）：type = 新记录植入类型；implant 不携带电极（经 leadActions /
     * newLeads）；在用电极逐根显式处置。
     */
    public record ReplaceRequestRequest(
            int baseVersion,
            @Size(max = 16) String type,
            @Size(max = 255) String note,
            @Valid ImplantPayloadRequest implant,
            @Valid List<LeadActionRequest> leadActions,
            @Valid List<LeadPayloadRequest> newLeads) {

        ImplantService.ReplaceRequest toRequest() {
            return new ImplantService.ReplaceRequest(
                    baseVersion,
                    type,
                    note,
                    implant == null ? null : implant.toPayload(),
                    leadActions == null
                            ? null
                            : leadActions.stream()
                                    .map(
                                            action ->
                                                    new ImplantService.LeadAction(
                                                            action.leadId(), action.action()))
                                    .toList(),
                    newLeads == null
                            ? null
                            : newLeads.stream()
                                    .map(
                                            lead ->
                                                    new ImplantService.LeadPayload(
                                                            lead.id(),
                                                            lead.chamber(),
                                                            lead.position(),
                                                            lead.model(),
                                                            lead.implantDate(),
                                                            lead.status()))
                                    .toList());
        }
    }

    /** 电极处置行：leadId + KEEP / ABANDON / EXTRACT。 */
    public record LeadActionRequest(@Size(max = 20) String leadId, @Size(max = 16) String action) {}

    /** 取出请求体：取出日期 + 原因（缺省「器械取出」）+ 在用电极处置（ABANDON / EXTRACT）。 */
    public record ExplantRequestRequest(
            int baseVersion,
            LocalDate date,
            @Size(max = 255) String reason,
            @Valid List<LeadActionRequest> leadActions) {

        ImplantService.ExplantRequest toRequest() {
            return new ImplantService.ExplantRequest(
                    baseVersion,
                    date,
                    reason,
                    leadActions == null
                            ? null
                            : leadActions.stream()
                                    .map(
                                            action ->
                                                    new ImplantService.LeadAction(
                                                            action.leadId(), action.action()))
                                    .toList());
        }
    }

    /** 电极载荷：腔室必填（RA / RV / LV），部位按腔室过滤，植入日期缺省同植入记录。 */
    public record LeadPayloadRequest(
            @Size(max = 20) String id,
            @Size(max = 4) String chamber,
            @Size(max = 32) String position,
            @Size(max = 128) String model,
            LocalDate implantDate,
            @Size(max = 16) String status) {}
}
