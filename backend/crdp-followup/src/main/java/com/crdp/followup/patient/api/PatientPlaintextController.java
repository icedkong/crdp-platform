package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PlaintextRevealService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感字段明文查看接口（E07-T05 / A-PAT-010、C-PAT-012；docs/api/patient.md §5）。 默认脱敏的列表 / 详情由
 * {@code @MaskedByPolicy} 序列化输出（framework FieldPolicyJacksonModule）；明文只走本专用接口： 权限 {@code
 * followup:patient:plain} + 二次确认（前端 popconfirm，OQ-57 定论：不输密码）+ 每次调用写数据访问审计。 列表、表格不提供逐行明文（AGENTS.md
 * §5.3）。
 */
@Tag(name = "医院端患者明文查看")
@RestController
public class PatientPlaintextController {

    private final PlaintextRevealService service;

    public PatientPlaintextController(PlaintextRevealService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "敏感字段明文查看（A-PAT-010、C-PAT-012）：默认脱敏；本接口逐字段返回明文，需"
                            + " followup:patient:plain，且字段对当前角色策略为 MASKED；每次调用写数据访问审计（含字段码与场景，不记明文）；"
                            + "同一用户每分钟 ≤ 30 次（42900）")
    @PostMapping("/api/hospital/followup/patients/{id}/plaintext")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:plain")
    public ResponseEntity<ApiResponse<PlaintextRevealService.RevealView>> reveal(
            @PathVariable("id") long id, @RequestBody RevealRequest request) {
        return ResponseEntity.ok()
                // 明文响应不落任何缓存（patient.md §8）
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(service.reveal(id, request.fields(), request.scene())));
    }

    /** 请求体（patient.md §5）：fields 为敏感字段目录码；scene = detail（患者档案）| form（表单）。 */
    public record RevealRequest(List<String> fields, String scene) {}
}
