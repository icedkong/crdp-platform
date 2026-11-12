package com.crdp.followup.record.api;

import com.crdp.followup.record.app.RecordEntryService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 随访工作台记录接口（E15-T02 / A-FU-005、C-FU-020；docs/api/followup.md §3，页面
 * /followup/record/:recordId，pages.md §5）。
 *
 * <p>权限：查看 {@code followup:task:view}（表单页路由）；录入 / 提交 {@code followup:record:edit}（按钮 222）。 自动保存草稿
 * {@code PUT /records/{id}/draft} 已随 E25-T01（RecordDraftController）。
 *
 * <p>E21-T11 / C-FU-026（Q17）修改与删除已提交：edit-session / PUT 双码（A {@code followup:record:edit}、C {@code
 * followup:record:edit-submitted}，任一命中即放行）——功能开关 {@code record_edit_submitted} 开启（医院 C）时服务层进一步要求必须
 * edit-submitted（仅有录入码 40300，验收「修改已提交记录需单独权限」）；DELETE 单码 {@code followup:record:delete}（两院同码，敏感——
 * 平台库 V72 按钮 229 / 230 登记，默认不可分配、平台按租户放开）。
 */
@Tag(name = "医院端随访记录")
@RestController
@RequestMapping("/api/hospital/followup/records")
public class RecordEntryController {

    private final RecordEntryService service;

    public RecordEntryController(RecordEntryService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "记录详情（A-FU-005 表单页装配）：data / meta / version / status + 任务上下文"
                            + "（患者脱敏、节点、计划日期、窗口、任务状态与完成方式、下次计划日期）；"
                            + "context 为 null 表示记录未关联任务或越范围不可见；E21-T11 起附 "
                            + "visitNo / sequenceNo / entryDefaults（C-FU-008 自动带入：随访编号、随访序号、当前器械缺省）")
    @GetMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:task:view")
    public ApiResponse<RecordEntryService.RecordDetailView> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @Operation(
            summary =
                    "提交（A-FU-005「提交后任务完成」）：完整校验（42200）→ SUBMITTED（baseVersion "
                            + "过期 40900、已提交 40901）→ 字段历史 → 任务完成（完成方式按窗口派生，"
                            + "已过直接录入期 40901 只能历史补录）→ 是否死亡 = 是时终止该患者未开始任务；"
                            + "E21-T17 / C-SCAN-012：transcribeReportId 非空 = 转录态提交，同一事务把报告单"
                            + "自动加入该记录位置并置「已电子化」（scan_progress 关闭 40301），结果附 transcribedReportId")
    @PostMapping("/{id}/submit")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<RecordEntryService.SubmitResultView> submit(
            @PathVariable("id") long id, @RequestBody(required = false) SubmitBody body) {
        return ApiResponse.ok(
                service.submit(
                        id,
                        body == null
                                ? null
                                : new RecordEntryService.SubmitCommand(
                                        body.baseVersion(),
                                        body.data(),
                                        body.dataMeta(),
                                        body.transcribeReportId())));
    }

    @Operation(
            summary =
                    "开始修改已提交记录（C-FU-026 / Q17）：返回当前 version（PUT 的 baseVersion）；"
                            + "开关开启的租户必须 followup:record:edit-submitted（40300），"
                            + "关闭的租户（A / B）followup:record:edit 即可；只有 SUBMITTED 记录可修改（40901）")
    @PostMapping("/{id}/edit-session")
    @RequireModule("followup")
    @RequiresPermission({"followup:record:edit", "followup:record:edit-submitted"})
    public ApiResponse<RecordEntryService.EditSessionView> editSession(
            @PathVariable("id") long id, @RequestBody(required = false) EditSessionBody body) {
        return ApiResponse.ok(service.editSession(id, body == null ? null : body.reason()));
    }

    @Operation(
            summary =
                    "保存修改已提交记录（C-FU-026「修改留字段级历史」）：完整校验（42200）→ SUBMITTED "
                            + "原地更新 + version + 1（保存后生成 v2）→ 字段级历史（含原因）→ 对比标红复算；"
                            + "reason 必填（OQ-14 暂定 A / C 都必填）；权限同 edit-session")
    @PutMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission({"followup:record:edit", "followup:record:edit-submitted"})
    public ApiResponse<RecordEntryService.UpdateResultView> update(
            @PathVariable("id") long id, @RequestBody UpdateBody body) {
        return ApiResponse.ok(
                service.updateSubmitted(
                        id,
                        body == null
                                ? null
                                : new RecordEntryService.UpdateCommand(
                                        body.baseVersion(),
                                        body.data(),
                                        body.dataMeta(),
                                        body.reason(),
                                        body.transcribeReportId())));
    }

    @Operation(
            summary =
                    "删除随访记录（C-FU-026 / PLT-AUD-001）：软删除、reason 必填、记审计；"
                            + "关联任务只断开链接（终态不动）；两院同码 followup:record:delete（敏感，平台按租户放开）")
    @DeleteMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:record:delete")
    public ApiResponse<Void> delete(
            @PathVariable("id") long id, @RequestBody(required = false) DeleteBody body) {
        service.deleteRecord(id, body == null ? null : body.reason());
        return ApiResponse.ok(null);
    }

    /**
     * 提交请求体：data 全量、baseVersion 乐观锁（与草稿同口径）；transcribeReportId 非空 = 转录态 （E21-T17 / C-SCAN-012：表单页
     * ?compare= 报告单 id &transcribe=1 带入，提交后报告单自动置「已电子化」）。
     */
    public record SubmitBody(
            Integer baseVersion,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            Long transcribeReportId) {}

    /** 开始修改请求体（ReasonModal「开始修改」带入的原因，写入审计）。 */
    public record EditSessionBody(String reason) {}

    /**
     * 保存修改请求体（followup.md §3：baseVersion / data / dataMeta / reason 必填；transcribeReportId 同
     * SubmitBody）。
     */
    public record UpdateBody(
            Integer baseVersion,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            String reason,
            Long transcribeReportId) {}

    /** 删除请求体（reason 必填，C-FU-026）。 */
    public record DeleteBody(String reason) {}
}
