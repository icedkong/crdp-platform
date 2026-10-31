package com.crdp.platform.tenant;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 从平台库读启用 / 试用租户清单，供调度框架扇出。 */
@Component
public class JdbcTenantDirectory implements TenantDirectory {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TenantRef> enabledTenants() {
        return TenantContext.callAsPlatform(
                () ->
                        jdbcTemplate.query(
                                """
                                SELECT id, code FROM plt_tenant
                                WHERE status IN ('ENABLED', 'TRIAL')
                                ORDER BY id
                                """,
                                (rs, rowNum) ->
                                        new TenantRef(rs.getLong("id"), rs.getString("code"))));
    }
}
