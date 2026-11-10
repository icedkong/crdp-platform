package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E01-T09 子域名 hello：返回当前令牌命中的租户与租户库名，用于演示 / 本机确认数据源路由。
 *
 * <p>A3（M3 审查）：接口用 {@code SELECT DATABASE()} 回显当前数据库名，属于环境信息泄露面，仅 demo / local / dev 演示剖面注册；prod
 * 剖面不注册（404）。权限链占位已拆到全剖面可用的 {@link FollowupPingController}。
 */
@Tag(name = "租户路由")
@Profile({"demo", "local", "dev"})
@RestController
public class HospitalHelloController {

    private final JdbcTemplate jdbc;

    public HospitalHelloController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "返回当前令牌命中的租户库名，用来确认数据源路由。")
    @PublicApi
    @GetMapping("/api/hospital/hello")
    public ApiResponse<HelloResponse> hello() {
        SessionPrincipal principal = CurrentSession.requireHospital();
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        return ApiResponse.ok(
                new HelloResponse(
                        principal.tenantCode(), principal.tenantName(), database, "租户路由已接通"));
    }
}
