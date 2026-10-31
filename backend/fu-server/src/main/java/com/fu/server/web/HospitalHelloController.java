package com.fu.server.web;

import com.fu.framework.security.CurrentSession;
import com.fu.framework.security.RequireModule;
import com.fu.framework.security.RequiresPermission;
import com.fu.framework.security.SessionPrincipal;
import com.fu.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "租户路由")
@RestController
public class HospitalHelloController {

    private final JdbcTemplate jdbc;

    public HospitalHelloController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "返回当前令牌命中的租户库名，用来确认数据源路由。")
    @GetMapping("/api/hospital/hello")
    public ApiResponse<HelloResponse> hello() {
        SessionPrincipal principal = CurrentSession.requireHospital();
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        return ApiResponse.ok(
                new HelloResponse(
                        principal.tenantCode(), principal.tenantName(), database, "租户路由已接通"));
    }

    @Operation(summary = "权限链占位：模块开通并且持有 followup:task:view 才能访问。")
    @GetMapping("/api/hospital/followup/ping")
    @RequireModule("followup")
    @RequiresPermission("followup:task:view")
    public ApiResponse<PingResponse> ping() {
        return ApiResponse.ok(new PingResponse("followup", "placeholder"));
    }

    public record PingResponse(String module, String status) {}
}
