package com.crdp.followup.stats.api;

import com.crdp.followup.stats.app.StatsDrillService;
import com.crdp.framework.security.*;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "医院端统计明细")
@RestController
@RequestMapping("/api/hospital/followup/stats/drill")
public class StatsDrillController {
    private final StatsDrillService service;

    public StatsDrillController(StatsDrillService service) {
        this.service = service;
    }

    @Operation(summary = "按同一统计集合分页钻取；额外要求患者查看权限，姓名始终脱敏")
    @PostMapping
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:view")
    public ApiResponse<StatsDrillService.Page> search(@RequestBody StatsDrillService.Query query) {
        return ApiResponse.ok(service.search(query));
    }
}
