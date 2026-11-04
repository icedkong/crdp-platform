package com.crdp.followup.task.api;

import com.crdp.followup.task.app.FollowupTaskQueryService;
import com.crdp.followup.task.app.FollowupTaskStartService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的任务 / 任务列表与工作台入口（E15-T02 / A-FU-004、A-FU-005、C-FU-020；docs/api/followup.md §2，页面 /followup/task
 * 与 /followup/record/:recordId，pages.md §5、§7）。
 *
 * <p>权限：列表与队列 {@code followup:task:view}（菜单 plt_menu 22「我的任务」）；开始录入 {@code followup:record:edit}（按钮
 * 222，平台库 V43）。数据范围由 Mapper 语句上的 {@code @DataScope} 出行（越范围任务不可见，直接访问 40400）；接口资源由启动扫描登记（SCAN）。
 *
 * <p>认领 / 改派（A-FU-009）随 E15-T03；临时任务（A-FU-013）与失访确认（A-FU-012）随 E15-T06 / E15-T05。
 */
@Tag(name = "医院端随访任务")
@RestController
@RequestMapping("/api/hospital/followup/tasks")
public class FollowupTaskController {

    private final FollowupTaskQueryService queryService;
    private final FollowupTaskStartService startService;

    public FollowupTaskController(
            FollowupTaskQueryService queryService, FollowupTaskStartService startService) {
        this.queryService = queryService;
        this.startService = startService;
    }

    @Operation(
            summary =
                    "任务列表检索（A-FU-004、C-FU-020）：页签 scope = MINE 我的待办（默认，未完成四态）"
                            + "/ WARD 本病区 / ALL 全部；按状态、病区、负责人、计划日期窗口筛选；"
                            + "keyword 按姓名 / 病案号盲索引精确匹配；行含患者脱敏信息、节点、窗口、"
                            + "计划日期、状态与完成方式、责任人；页签徽标计数与病区候选随页返回；"
                            + "批量查询写数据访问审计")
    @PostMapping("/search")
    @RequireModule("followup")
    @RequiresPermission("followup:task:view")
    public ApiResponse<FollowupTaskQueryService.TaskPage> search(
            @RequestBody(required = false) SearchBody body) {
        return ApiResponse.ok(queryService.search(toRequest(body)));
    }

    @Operation(
            summary =
                    "开始录入（A-FU-005「从任务进入」）：无记录则创建 DRAFT 草稿并返回 recordId，"
                            + "任务进入进行中；幂等（已开始直接返回）；REMINDER 任务 40901（无需填表）")
    @PostMapping("/{id}/start")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<FollowupTaskStartService.StartView> start(@PathVariable("id") long id) {
        return ApiResponse.ok(startService.start(id));
    }

    @Operation(
            summary =
                    "表单页「上一条 / 下一条」队列（pages.md §5）：按当前列表筛选的顺序返回 cursor "
                            + "任务的前一条 / 后一条任务 id（尽头 null）；filter 参数与检索接口同构")
    @GetMapping("/queue")
    @RequireModule("followup")
    @RequiresPermission("followup:task:view")
    public ApiResponse<FollowupTaskQueryService.QueueView> queue(
            @RequestParam("cursor") long cursor,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "wardIds", required = false) List<Long> wardIds,
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            @RequestParam(value = "dueFrom", required = false) LocalDate dueFrom,
            @RequestParam(value = "dueTo", required = false) LocalDate dueTo,
            @RequestParam(value = "nodeName", required = false) String nodeName,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.ok(
                queryService.queue(
                        cursor,
                        new FollowupTaskQueryService.SearchRequest(
                                scope, status, wardIds, doctorId, dueFrom, dueTo, nodeName, keyword,
                                null, null)));
    }

    /** 检索请求体（docs/api/followup.md §2；字段全部可空）。 */
    public record SearchBody(
            String scope,
            List<String> status,
            List<Long> wardIds,
            Long doctorId,
            LocalDate dueFrom,
            LocalDate dueTo,
            String nodeName,
            String keyword,
            Integer pageNo,
            Integer pageSize) {}

    private static FollowupTaskQueryService.SearchRequest toRequest(SearchBody body) {
        if (body == null) {
            return null;
        }
        return new FollowupTaskQueryService.SearchRequest(
                body.scope(),
                body.status(),
                body.wardIds(),
                body.doctorId(),
                body.dueFrom(),
                body.dueTo(),
                body.nodeName(),
                body.keyword(),
                body.pageNo(),
                body.pageSize());
    }
}
