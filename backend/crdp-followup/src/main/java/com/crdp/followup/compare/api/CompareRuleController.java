package com.crdp.followup.compare.api;

import com.crdp.followup.compare.app.CompareRuleService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端对比规则配置（E17-T01 / A-FU-027、C-FU-011 随 E21；docs/api/followup.md §4，页面 /system/abnormal-rule）。 权限
 * followup:abnormal:config（C 规格原有码；A 按 OQ-16 同授，菜单 plt_menu 40，平台库 V57）；接口资源由启动扫描登记（SCAN）。 功能开关
 * compare_highlight 关闭 → 40301（AGENTS.md 规则 7）。
 */
@Tag(name = "医院端对比规则配置")
@RestController
public class CompareRuleController {

    private final CompareRuleService service;

    public CompareRuleController(CompareRuleService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "对比标红规则清单（A-FU-027）：字段、对比对象（上一次 / 基线回落 / 固定阈值）、条件阈值与开关；" + "formCode 为空返回全部表单")
    @GetMapping("/api/hospital/followup/compare-rules")
    @RequireModule("followup")
    @RequiresPermission("followup:abnormal:config")
    public ApiResponse<List<CompareRuleService.RuleView>> list(
            @RequestParam(value = "formCode", required = false) String formCode) {
        return ApiResponse.ok(service.list(formCode));
    }

    @Operation(
            summary =
                    "修改单条规则（A-FU-027「规则开关与阈值由租户管理员配置」）：全量覆盖 enabled 与阈值参数"
                            + "（params 键集按规则类型校验），写 COMPARE_RULE_UPDATE 审计")
    @PutMapping("/api/hospital/followup/compare-rules/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:abnormal:config")
    public ApiResponse<CompareRuleService.RuleView> update(
            @PathVariable("id") long id, @RequestBody CompareRuleService.RuleUpdate request) {
        return ApiResponse.ok(service.update(id, request));
    }
}
