package com.crdp.followup.record.api;

import com.crdp.followup.record.app.RecordEntryService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 随访工作台记录接口（E15-T02 / A-FU-005、C-FU-020；docs/api/followup.md §3，页面
 * /followup/record/:recordId，pages.md §5）。
 *
 * <p>权限：查看 {@code followup:task:view}（表单页路由）；录入 / 提交 {@code followup:record:edit}（按钮 222）。 自动保存草稿
 * {@code PUT /records/{id}/draft} 已随 E25-T01（RecordDraftController）；修改已提交 / 删除 / previous / history
 * 随 E15-T07 / E16 / E17。
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
                            + "context 为 null 表示记录未关联任务或越范围不可见")
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
                            + "已过直接录入期 40901 只能历史补录）→ 是否死亡 = 是时终止该患者未开始任务")
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
                                        body.baseVersion(), body.data(), body.dataMeta())));
    }

    /** 提交请求体：data 全量、baseVersion 乐观锁（与草稿同口径）。 */
    public record SubmitBody(
            Integer baseVersion, Map<String, Object> data, Map<String, Object> dataMeta) {}
}
