package com.crdp.form.field.api;

import com.crdp.form.field.app.FieldDictionaryService;
import com.crdp.form.field.app.FieldImportService;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：医院端「字段字典」API（docs/api/form.md §3，页面
 * /system/field，pages.md §10）。查看与维护同码 followup:field:edit（菜单 plt_menu 37，平台迁移
 * V35）；表单清单登录即可（模板选择用）。接口资源由启动扫描登记（SCAN）。
 */
@Tag(name = "医院端字段字典")
@RestController
@RequestMapping("/api/hospital/form")
@Validated
public class HospitalFormFieldController {

    private final FieldDictionaryService service;
    private final FieldImportService importService;

    public HospitalFormFieldController(
            FieldDictionaryService service, FieldImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @Operation(
            summary =
                    "表单清单（form.md §1 列表口径）：{ code, name, usage, currentVersion, status }；"
                            + "完整 FormSchema 随 E11-T02")
    @PublicApi
    @GetMapping("/forms")
    @RequireModule("followup")
    public ApiResponse<List<FieldDictionaryService.FormSummaryView>> forms() {
        return ApiResponse.ok(service.forms());
    }

    @Operation(
            summary =
                    "字段清单（A-FLD-001）：按表单、可再按分类过滤；含选项（编码 + 显示名 + 停用标记）、"
                            + "软 / 硬范围、条件与派生定义、敏感标记、存储方式与启停状态")
    @GetMapping("/fields")
    @RequireModule("followup")
    @RequiresPermission("followup:field:edit")
    public ApiResponse<List<FieldDictionaryService.FieldView>> fields(
            @RequestParam("formCode") @Size(max = 64) String formCode,
            @RequestParam(value = "categoryKey", required = false) @Size(max = 32)
                    String categoryKey) {
        return ApiResponse.ok(service.fields(formCode, categoryKey));
    }

    @Operation(
            summary =
                    "维护字段（form.md §3）：全量覆盖显示名 / 帮助说明 / 单位 / 软范围 / 必填 / 状态（启停），"
                            + "可一并维护选项（显示名 / 排序 / 停用 / 新增，编码不可改、不删选项）；"
                            + "稳定键、字段编码、类型、存储方式、敏感标记、硬范围不可改（A-FLD-002、A-FLD-008）；"
                            + "写 FIELD_DICT_UPDATE 审计")
    @PutMapping("/fields/{key}")
    @RequireModule("followup")
    @RequiresPermission("followup:field:edit")
    public ApiResponse<FieldDictionaryService.FieldView> update(
            @PathVariable("key") @Size(max = 32) String key,
            @RequestParam("formCode") @Size(max = 64) String formCode,
            @RequestBody FieldDictionaryService.FieldUpdate request) {
        return ApiResponse.ok(service.update(formCode, key, request));
    }

    @Operation(
            summary =
                    "字段字典变更记录（A-FLD-009、C-FLD-009，E11-T08）：倒序最近 100 条，"
                            + "含前后值快照、操作人与时间；字段 / 表单不存在 40400")
    @GetMapping("/fields/{key}/logs")
    @RequireModule("followup")
    @RequiresPermission("followup:field:edit")
    public ApiResponse<List<FieldDictionaryService.FieldLogView>> logs(
            @PathVariable("key") @Size(max = 32) String key,
            @RequestParam("formCode") @Size(max = 64) String formCode) {
        return ApiResponse.ok(service.logs(formCode, key));
    }

    // ---- 字段字典批量导入（E11-T09 / A-FLD-013、C-FLD-008）----

    @Operation(summary = "字段字典导入模板（E11-T09）：27 列表头 + 三行示例（全部虚构数据，覆盖数值范围 / 选项 / 派生），" + "xlsx 单工作表")
    @GetMapping("/fields/import-template")
    @RequireModule("followup")
    @RequiresPermission("followup:field:edit")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode("字段字典导入模板.xlsx", StandardCharsets.UTF_8))
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(importService.template());
    }

    @Operation(
            summary =
                    "字段字典批量导入（E11-T09，验收「产品可用 Excel 批量导入字段定义」）：上传 xlsx（≤500 行），"
                            + "逐行校验并落库，单行失败不影响其他行，返回逐行 CREATED / UPDATED / FAILED 与原因；"
                            + "按「表单编码 + 稳定键」命中为更新、否则新增；字段编码 / 类型 / 存储方式 / 敏感标记不可改；"
                            + "写 FIELD_DICT_IMPORT 作业审计与 fe_field_dict_log 变更记录（A-FLD-009）")
    @PostMapping("/fields/import")
    @RequireModule("followup")
    @RequiresPermission("followup:field:edit")
    public ApiResponse<FieldImportService.ImportReport> importFields(
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, 40001, "请选择要导入的 xlsx 文件");
        }
        try (java.io.InputStream in = file.getInputStream()) {
            return ApiResponse.ok(importService.importFields(in));
        } catch (java.io.IOException ex) {
            throw new ApiException(400, 40001, "文件读取失败，请重新上传");
        }
    }
}
