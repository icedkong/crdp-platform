package com.crdp.followup.contact.api;

import com.crdp.followup.contact.app.ContactLogService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沟通记录接口（E15-T04 / A-FU-006、A-FU-025、C-FU-019；docs/api/followup.md §6，页面 pages.md §4.8 沟通记录页签）。
 *
 * <p>权限：列表 {@code followup:patient:view}（患者档案菜单 21）；记录 / 修改 / 删除 {@code followup:record:edit} （按钮
 * 222，平台库 V43）。数据范围：患者面由 Mapper 语句上的 {@code @DataScope} 出行，单行写入口由服务层校验 （越范围
 * 40400，不暴露存在）。接口资源由启动扫描登记（SCAN）。
 *
 * <p>录音自动生成「通话记录」报告单（A-ATT-017）随 E16-T08；失访判定与未联系到自动跟进（A-FU-012 / 024）随 E15-T05。
 */
@Tag(name = "医院端沟通记录")
@RestController
@RequestMapping("/api/hospital/followup")
public class ContactLogController {

    private final ContactLogService service;

    public ContactLogController(ContactLogService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "患者沟通记录分页（A-FU-006）：倒序，行含方式、结果（六值枚举）、时间、约定下次联系、"
                            + "情况说明、所用联系方式版本、操作人与录音（fileId、格式、原名、大小、"
                            + "inlinePlayable——AMR 只保存与下载）；越范围患者 40400")
    @GetMapping("/patients/{id}/contacts")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<ContactLogService.ContactLogPage> list(
            @PathVariable("id") long id,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(service.list(id, pageNo, pageSize));
    }

    @Operation(
            summary =
                    "记录一次联系（A-FU-006）：{ taskId?, method, result, contactTime?, contactVersionId?,"
                            + " nextContactTime?, note?, audioFileIds[] }——录音须先经 POST /api/hospital/files"
                            + " 上传（真实类型与 50 MB 上限在文件服务校验），这里只收 AUDIO 类别且未挂他处的"
                            + " fileId；第一次联系任务进入进行中；result = DECEASED 同事务触发死亡终止"
                            + "（A-FU-007，返回 terminatedTasks）；批量写 CONTACT_CREATE 审计")
    @PostMapping("/patients/{id}/contacts")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<ContactLogService.CreateResult> create(
            @PathVariable("id") long id, @RequestBody ContactLogService.SaveBody body) {
        return ApiResponse.ok(service.create(id, body));
    }

    @Operation(
            summary =
                    "修改沟通记录（乐观锁 baseVersion，冲突 40900）：method / result 必填，contactTime 缺省"
                            + " 保持原值，可空字段传 null 即清空；结果改为「告知已故」同样触发死亡终止（幂等）；"
                            + "录音清单不可改（删记录重记）；写 CONTACT_UPDATE 审计")
    @PutMapping("/contacts/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<ContactLogService.ContactLogView> update(
            @PathVariable("id") long id, @RequestBody ContactLogService.UpdateBody body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @Operation(
            summary =
                    "删除沟通记录：连带软删录音（A-ATT-017「在任一处删除即两处同时删除」，E16-T08 后报告单侧"
                            + " 同步消失）；写 CONTACT_DELETE 审计")
    @DeleteMapping("/contacts/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<Void> delete(@PathVariable("id") long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
