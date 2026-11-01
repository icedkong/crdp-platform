package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端查看本院服务状态（E05-T03 / PLT-TEN-012）：宽限期内页面顶部显示续费提示的数据源，登录即可用（同 /auth/me 一类）。 前端横幅随 E06
 * 前端接入；此处只提供事实：状态、到期类型、到期日、距到期天数与宽限期是否只读。
 */
@Tag(name = "医院端租户状态")
@RestController
public class HospitalTenantStateController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate platform;

    public HospitalTenantStateController(JdbcTemplate platform) {
        this.platform = platform;
    }

    /** 本院服务状态快照。daysLeft 含到期当天为 0；已到期或长期有效为 null。 */
    public record TenantStateView(
            long tenantId,
            String code,
            String name,
            String status,
            String expireType,
            LocalDate expireAt,
            Integer daysLeft,
            boolean graceReadOnly) {}

    @Operation(summary = "本院服务状态（宽限期续费提示数据源）：状态、到期日、距到期天数、宽限期是否只读")
    @GetMapping("/api/hospital/auth/tenant-state")
    public ApiResponse<TenantStateView> tenantState() {
        var principal = CurrentSession.requireHospital();
        Map<String, Object> row =
                TenantContext.callAsPlatform(
                        () ->
                                platform.queryForMap(
                                        "SELECT id, code, name, status, expire_type, expire_at,"
                                                + " grace_read_only FROM plt_tenant WHERE id = ?",
                                        principal.tenantId()));
        LocalDate expireAt = localDate(row.get("expire_at"));
        Integer daysLeft =
                expireAt == null
                        ? null
                        : (int) (expireAt.toEpochDay() - LocalDate.now(ZONE).toEpochDay());
        return ApiResponse.ok(
                new TenantStateView(
                        ((Number) row.get("id")).longValue(),
                        String.valueOf(row.get("code")),
                        String.valueOf(row.get("name")),
                        String.valueOf(row.get("status")),
                        row.get("expire_type") == null
                                ? null
                                : String.valueOf(row.get("expire_type")),
                        expireAt,
                        daysLeft != null && daysLeft < 0 ? null : daysLeft,
                        ((Number) row.get("grace_read_only")).intValue() == 1));
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
